package com.stzb.assistant.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import java.io.File

/**
 * 语义按键的**模板库**：把"某个按键长什么样"存成小图，供 [TemplateMatcher] 定位。
 *
 * ## 它解决的问题
 * 按键定位此前**只有 OCR 一条路**。默认构建里 native OCR 是空桩，于是整条路失效，
 * 表现为"点不到按键""点击不准"。模板匹配不需要 OCR、不需要模型、不需要网络，
 * 因此在 OCR 不可用时它**是唯一还能工作的定位手段**。
 *
 * ## 模板从哪来（两条路，缺一不可）
 * 1. **手动登记**：在悬浮窗「标定」页签点选按键位置，把该处裁下来登记。
 *    ——这是 OCR 完全不可用时唯一的引导方式，必须有。
 * 2. **随用随学**：OCR 可用时，每次成功定位到某按键就把那一小片登记下来。
 *    ——模板会随使用自动长出来，不需要用户逐个标定。
 *
 * ## 存储
 * 每个 [StzbUiMatcher.ButtonType] 一个 PNG，放在 `filesDir/button_templates/`。
 * 用文件而不是 SharedPreferences：小图 base64 之后会撑爆 prefs，而 prefs 是全量重写的。
 *
 * 上下文通过 [attach] 注入（与本工程 `CoordinateTransformer` / `UiAnchors` 一致），
 * 这样按键定位的热路径不必层层传 Context。未 attach 时一切安全降级为"没有模板"。
 */
object ButtonTemplateStore {

    private const val TAG = "ButtonTemplateStore"
    private const val DIR_NAME = "button_templates"

    /** 模板在按键周围的裁剪半径（设计画布坐标，像素）。 */
    const val CROP_HALF_WIDTH = 90
    const val CROP_HALF_HEIGHT = 40

    /**
     * 模板的最低灰度标准差。
     *
     * 低于它说明这一片几乎是纯色。纯色模板不仅匹配不出东西，更糟的是
     * `TM_CCOEFF_NORMED` 要除以模板标准差，近似均匀的模板会让匹配结果
     * 出现**虚假的极高峰值**——等于让匹配器乱指。因此宁可在登记时就拒绝。
     */
    private const val MIN_TEMPLATE_CONTRAST = 6.0

    @Volatile
    private var appContext: Context? = null

    /** 内存缓存；null 值表示"已确认没有该模板"，避免反复读盘。 */
    private val cache = HashMap<String, Bitmap?>()

    fun attach(context: Context) {
        appContext = context.applicationContext
        synchronized(cache) { cache.clear() }
        Log.i(TAG, "按键模板库已就绪，目录: ${dir()?.absolutePath ?: "(未注入上下文)"}")
    }

    private fun dir(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    private fun fileFor(type: StzbUiMatcher.ButtonType): File? =
        dir()?.let { File(it, "${type.name}.png") }

    @Synchronized
    fun has(type: StzbUiMatcher.ButtonType): Boolean = load(type) != null

    @Synchronized
    fun load(type: StzbUiMatcher.ButtonType): Bitmap? {
        val key = type.name
        if (cache.containsKey(key)) return cache[key]
        val f = fileFor(type)
        val bmp = if (f != null && f.isFile) {
            try {
                BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "读取按键模板 ${type.name} 失败: ${e.message}")
                null
            }
        } else {
            null
        }
        cache[key] = bmp
        return bmp
    }

    /**
     * 从一帧里按"按键所在位置"裁出模板并登记。
     *
     * @param centerX/centerY 按键中心（设计画布坐标）
     * @return 成功返回 true
     */
    @Synchronized
    fun saveFromFrame(
        type: StzbUiMatcher.ButtonType,
        frame: Bitmap,
        centerX: Int,
        centerY: Int
    ): Boolean {
        val f = fileFor(type)
        if (f == null) {
            Log.w(TAG, "尚未注入上下文，无法登记按键模板。")
            return false
        }
        val rect = cropRectFor(frame, centerX, centerY) ?: run {
            Log.w(TAG, "按键 ${type.name} 的中心 ($centerX, $centerY) 太靠近画面边缘，无法裁剪模板。")
            return false
        }

        // 拒绝"纯色/低对比"的模板。
        //
        // 这不只是质量问题，而是**安全问题**：`TM_CCOEFF_NORMED` 内部要除以模板标准差，
        // 一个几乎均匀的模板会让这个除法失去意义，从而在画面里冒出**极高的伪峰值**——
        // 也就是说，登记一个纯色块会让匹配器自信地指向一个完全错误的位置。
        // 宁可拒绝登记（用户换个位置再试），也不能存进一个会乱指的模板。
        val contrast = grayscaleStdDev(frame, rect)
        if (contrast < MIN_TEMPLATE_CONTRAST) {
            Log.w(
                TAG,
                "拒绝登记按键 ${type.name} 的模板：该区域灰度标准差仅 " +
                    "${"%.1f".format(contrast)}（要求 ≥ $MIN_TEMPLATE_CONTRAST），" +
                    "几乎是纯色，用它做匹配会产生错误的峰值。请对准按键上文字/边框较明显的位置。"
            )
            return false
        }

        return try {
            val crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
            f.outputStream().use { out ->
                crop.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            crop.recycle()
            cache.remove(type.name) // 让下次读取拿到新图
            Log.i(TAG, "已登记按键模板 ${type.name}（${rect.width()}x${rect.height()}）")
            true
        } catch (e: Exception) {
            Log.w(TAG, "登记按键模板 ${type.name} 失败: ${e.message}")
            false
        }
    }

    /** 计算裁剪矩形；越界或尺寸过小时返回 null（**不** clamp，避免存进半张边缘图）。 */
    fun cropRectFor(frame: Bitmap, centerX: Int, centerY: Int): Rect? {
        val l = centerX - CROP_HALF_WIDTH
        val t = centerY - CROP_HALF_HEIGHT
        val r = centerX + CROP_HALF_WIDTH
        val b = centerY + CROP_HALF_HEIGHT
        if (l < 0 || t < 0 || r > frame.width || b > frame.height) return null
        if (r - l < 8 || b - t < 8) return null
        return Rect(l, t, r, b)
    }

    /**
     * 某个区域内的灰度标准差（越大越有"花纹"，越适合做模板）。
     *
     * 抽样步长 4：判断"是不是纯色"不需要逐像素精确，抽样足够且快得多。
     */
    private fun grayscaleStdDev(frame: Bitmap, rect: Rect): Double {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return 0.0
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var y = rect.top
        while (y < rect.bottom) {
            var x = rect.left
            while (x < rect.right) {
                val p = frame.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                sum += lum
                sumSq += lum * lum
                n++
                x += 4
            }
            y += 4
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return kotlin.math.sqrt(kotlin.math.max(0.0, sumSq / n - mean * mean))
    }

    @Synchronized
    fun clear(type: StzbUiMatcher.ButtonType) {
        fileFor(type)?.delete()
        cache.remove(type.name)
    }

    @Synchronized
    fun clearAll() {
        dir()?.listFiles()?.forEach { it.delete() }
        cache.clear()
    }

    /** 已登记模板的按键类型，供界面展示"现在有哪些按键能脱离 OCR 定位"。 */
    @Synchronized
    fun registeredTypes(): List<StzbUiMatcher.ButtonType> =
        StzbUiMatcher.ButtonType.values().filter { has(it) }

    /** 一句话摘要，直接给界面用。 */
    @Synchronized
    fun describe(): String {
        if (appContext == null) return "按键模板：尚未初始化"
        val list = registeredTypes()
        if (list.isEmpty()) {
            return "按键模板：尚无。OCR 不可用时将无法定位按键，" +
                "请到「标定」页签用十字准星登记按键位置。"
        }
        return "按键模板：${list.size} 个已登记（${list.joinToString("、") { it.name }}）" +
            "——这些按键即便 OCR 不可用也能定位"
    }
}
