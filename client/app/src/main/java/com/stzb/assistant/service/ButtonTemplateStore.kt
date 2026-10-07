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
 * ## 模板从哪来（三条路，按可信度排序）
 * 1. **手动登记**：在悬浮窗「标定」页签点选按键位置，把该处裁下来登记（⑧ 号按钮）。
 *    ——这是 OCR 完全不可用时最直接的引导方式，必须有。
 * 2. **随用随学**：OCR 可用时，每次成功定位到某按键就把那一小片登记下来；
 *    模板命中时也会在高置信度下刷新自己（见 `StzbUiMatcher.refreshTemplateIfConfident`），
 *    于是美术小改版不需要用户重新登记。
 * 3. **随包种子**：`assets/templates/<gameId>/seeds/<ButtonType>.png`。
 *    ——全新用户装上就"库里不是空的"，代价是种子只能覆盖**我们真有截图可裁**的那些按键，
 *    且必须按游戏分域（见 [seedAssetPath] 为什么不走 [PerGameScope.assetDirs] 的旧兜底）。
 *    种子是**在别人手机上截的像素**，所以永远排在"本机登记"之后：本机那份才贴近当前美术。
 *
 * ## 存储
 * 每个 [StzbUiMatcher.ButtonType] 一个 PNG，放在 `filesDir/button_templates_<gameId>/`
 * （**按游戏分域**，见 [PerGameScope]；旧的无后缀目录会一次性改名接管给率土）。
 * 用文件而不是 SharedPreferences：小图 base64 之后会撑爆 prefs，而 prefs 是全量重写的。
 *
 * 上下文通过 [attach] 注入（与本工程 `CoordinateTransformer` / `UiAnchors` 一致），
 * 这样按键定位的热路径不必层层传 Context。未 attach 时一切安全降级为"没有模板"。
 */
object ButtonTemplateStore {

    private const val TAG = "ButtonTemplateStore"
    private const val DIR_NAME = "button_templates"

    /**
     * 随包种子在 `assets/templates/<gameId>/` 下的子目录名。
     *
     * 用子目录而不是直接放 `templates/` 下：那层已经有早期脚本用的固定裁剪，
     * 混在一起就分不清"哪个是能被当成 ButtonType 用的种子"。
     */
    private const val SEED_DIR = "seeds"

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

    /** 切游戏重载的钩子是否已注册（保证只注册一次）。 */
    @Volatile
    private var switchHookRegistered = false

    /** 内存缓存；`Source.NONE` 表示"已确认没有该模板"，避免反复读盘。 */
    private val cache = HashMap<String, Loaded>()

    /** 模板来源。界面要能分清"本机登记的"和"随包内置的"，因为可信度不同。 */
    enum class Source { NONE, USER, SEED }

    private data class Loaded(val bitmap: Bitmap?, val source: Source)

    fun attach(context: Context) {
        appContext = context.applicationContext
        synchronized(cache) { cache.clear() }
        Log.i(TAG, "按键模板库已就绪，目录: ${dir()?.absolutePath ?: "(未注入上下文)"}")
        // 模板是**从这款游戏的界面上裁下来的像素**。留着率土的"出征"模板去匹配三战界面，
        // 匹配器照样会给出一个峰值，我们就照着那个位置点下去 —— 必须按游戏分域。
        if (!switchHookRegistered) {
            switchHookRegistered = true
            PerGameScope.reloadOnProfileSwitch { reload() }
        }
    }

    /** 换游戏后切到新游戏的目录，并丢掉内存里上一款游戏的模板。 */
    private fun reload() {
        synchronized(cache) { cache.clear() }
        Log.i(TAG, "已按 [${PerGameScope.gameId()}] 切换按键模板目录: ${dir()?.absolutePath ?: "(未注入上下文)"}")
    }

    private fun dir(): File? {
        val ctx = appContext ?: return null
        // 目录带游戏后缀（button_templates_stzb / button_templates_sgz），
        // 旧目录 button_templates 一次性改名接管给率土。
        return PerGameScope.scopedDir(ctx, DIR_NAME)
    }

    private fun fileFor(type: StzbUiMatcher.ButtonType): File? =
        dir()?.let { File(it, "${type.name}.png") }

    /**
     * 随包种子的 assets 路径。
     *
     * 刻意**只**认游戏专属目录，不用 [PerGameScope.assetDirs]：那条口径的最后一项是
     * 旧的无后缀 `templates/`，里面装的是早期脚本用的固定裁剪（`attack_template.png`
     * 等），既不是按 [StzbUiMatcher.ButtonType] 命名的、也没按游戏分域。
     * 让它当种子 = 让另一款游戏吃到率土的按钮像素，正是分域要禁止的那类事。
     */
    private fun seedAssetPath(type: StzbUiMatcher.ButtonType): String =
        "templates/${PerGameScope.gameId()}/$SEED_DIR/${type.name}.png"

    /** 这台设备上有没有这个按键的模板（本机登记 **或** 随包种子）。 */
    @Synchronized
    fun has(type: StzbUiMatcher.ButtonType): Boolean = entry(type).bitmap != null

    /** 该按键的模板来自哪里（界面据此区分"已登记"与"内置种子"）。 */
    @Synchronized
    fun sourceOf(type: StzbUiMatcher.ButtonType): Source = entry(type).source

    /**
     * 取出模板位图。
     *
     * **调用方不得 recycle 返回的位图**：它就是缓存里那一份，回收等于把整个模板作废
     * （这个坑踩过一次，症状是"命中过一次之后该按键再也点不到"）。
     */
    @Synchronized
    fun load(type: StzbUiMatcher.ButtonType): Bitmap? = entry(type).bitmap

    private fun entry(type: StzbUiMatcher.ButtonType): Loaded {
        val key = type.name
        cache[key]?.let { return it }

        // 顺序即优先级：本机截的那份最贴近当前美术（皮肤、分辨率、版本都一致），
        // 种子只是"别人手机上的像素"，只在没有本机版本时兜底。
        val f = fileFor(type)
        if (f != null && f.isFile) {
            try {
                BitmapFactory.decodeFile(f.absolutePath)?.let {
                    val loaded = Loaded(it, Source.USER)
                    cache[key] = loaded
                    return loaded
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取按键模板 ${type.name} 失败: ${e.message}")
            }
        }
        decodeSeed(type)?.let {
            val loaded = Loaded(it, Source.SEED)
            cache[key] = loaded
            return loaded
        }
        val none = Loaded(null, Source.NONE)
        cache[key] = none
        return none
    }

    /** 从 assets 里解出种子图；没有就是常态（不打日志，否则 19 个按键会把日志刷满）。 */
    private fun decodeSeed(type: StzbUiMatcher.ButtonType): Bitmap? {
        val ctx = appContext ?: return null
        return try {
            ctx.assets.open(seedAssetPath(type)).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
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
        if (appContext == null) {
            Log.w(TAG, "尚未注入上下文，无法登记按键模板。")
            return false
        }
        val rect = cropRectFor(frame, centerX, centerY) ?: run {
            Log.w(TAG, "按键 ${type.name} 的中心 ($centerX, $centerY) 太靠近画面边缘，无法裁剪模板。")
            return false
        }
        val crop = try {
            Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) {
            Log.w(TAG, "登记按键模板 ${type.name} 失败（裁剪）: ${e.message}")
            return false
        }
        return saveBitmap(type, crop, "裁自 ${rect.width()}x${rect.height()}")
    }

    /**
     * 把一帧里某个位置裁成候选模板，**不写盘**。
     *
     * 给"先自检、合格才登记"的调用方用（见 `StzbUiMatcher.refreshTemplateIfConfident`）：
     * 自动学习出来的东西一旦写坏，用户是没机会像手动登记那样当场重来的。
     * 越界或尺寸过小返回 null。
     */
    fun cropCandidate(frame: Bitmap, centerX: Int, centerY: Int): Bitmap? {
        val rect = cropRectFor(frame, centerX, centerY) ?: return null
        return try {
            Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 登记一张已经裁好的模板图。
     *
     * @param note 写进日志的来源说明，便于真机上判断这张模板是哪来的
     */
    @Synchronized
    fun saveBitmap(
        type: StzbUiMatcher.ButtonType,
        crop: Bitmap,
        note: String = "${crop.width}x${crop.height}"
    ): Boolean {
        val f = fileFor(type) ?: run {
            Log.w(TAG, "尚未注入上下文，无法登记按键模板。")
            return false
        }

        // 拒绝"纯色/低对比"的模板。
        //
        // 这不只是质量问题，而是**安全问题**：`TM_CCOEFF_NORMED` 内部要除以模板标准差，
        // 一个几乎均匀的模板会让这个除法失去意义，从而在画面里冒出**极高的伪峰值**——
        // 也就是说，登记一个纯色块会让匹配器自信地指向一个完全错误的位置。
        // 宁可拒绝登记（用户换个位置再试），也不能存进一个会乱指的模板。
        val contrast = grayscaleStdDev(crop, Rect(0, 0, crop.width, crop.height))
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
            f.outputStream().use { out ->
                crop.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            cache.remove(type.name) // 让下次读取拿到新图
            Log.i(TAG, "已登记按键模板 ${type.name}（$note）")
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
        // 只清本机登记/学习出来的那批。assets 里的种子删不掉（本来就在 APK 内），
        // 所以"清除全部标定"之后仍然可能有按键能定位 —— describe() 必须如实说明这点。
        dir()?.listFiles()?.forEach { it.delete() }
        cache.clear()
    }

    /** 已登记模板的按键类型（**只算本机登记的**，不含随包种子），供界面展示。 */
    @Synchronized
    fun registeredTypes(): List<StzbUiMatcher.ButtonType> =
        StzbUiMatcher.ButtonType.values().filter { sourceOf(it) == Source.USER }

    /** 只用随包种子、本机还没登记过的按键类型：这些是"能定位但可信度低一档"的。 */
    @Synchronized
    fun seedOnlyTypes(): List<StzbUiMatcher.ButtonType> =
        StzbUiMatcher.ButtonType.values().filter { sourceOf(it) == Source.SEED }

    /** 一句话摘要，直接给界面用。 */
    @Synchronized
    fun describe(): String {
        if (appContext == null) return "按键模板：尚未初始化"
        val user = registeredTypes()
        val seeds = seedOnlyTypes()
        if (user.isEmpty() && seeds.isEmpty()) {
            return "按键模板：尚无。OCR 不可用时将无法定位按键，" +
                "请到「标定」页签用十字准星登记按键位置。"
        }
        val parts = buildString {
            if (user.isNotEmpty()) {
                append("本机登记 ${user.size} 个（${user.joinToString("、") { it.name }}）")
            }
            if (seeds.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append("随包种子 ${seeds.size} 个（${seeds.joinToString("、") { it.name }}）")
            }
        }
        return "按键模板：$parts ——这些按键即便 OCR 不可用也能定位" +
            if (seeds.isNotEmpty() && user.isEmpty()) "（种子取自别的机型/版本，命中不稳时请重新登记）" else ""
    }
}
