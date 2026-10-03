package com.stzb.assistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject

/**
 * 守军武将头像确定性分类器 (DefenderTemplateClassifier)
 *
 * 职责：在「查看守军」面板打开时，按**固定归一化槽位**裁出三行武将立绘，
 * 与 assets/defender_refs 下的头像模板库逐一比对，识别出当前守将身份。
 * 这是守军评估的**主识别通道**（不依赖 OCR 是否读清名字），与
 * [com.stzb.assistant.service.EngineBridge] 里 OCR 命中守军库的名字通道互补。
 *
 * ## 为什么用归一化槽位而不是绝对像素
 * 截图是设备原生分辨率(如 2712x1220)，运行时抓帧是 720 高等比设计画布
 * (本机为 1600x720)。两者是**等比缩放**，故槽位以「占宽/高的比例」表达即可跨尺度对齐。
 * 槽位与规范尺寸**从 index.json 读取**（由 tools/defender_gallery/build_gallery.py 生成），
 * 保证运行时裁剪与建库裁剪是同一套几何，模板匹配即为同尺寸比较。
 *
 * ## 安全取向
 * 头像误判为某守将会把危险度往高或低带偏；这里以「宁可漏、不乱认」为阈值基调，
 * 且任何异常都吞掉返回空列表——守军评估是辅助决策，绝不能拖垮调用它的手动入口。
 */
object DefenderTemplateClassifier {

    private const val TAG = "DefenderTemplateClassifier"
    private const val ASSET_DIR = "defender_refs"

    /**
     * 同尺寸归一化相关系数（TM_CCOEFF_NORMED）的采信门槛。
     *
     * 离线自测（tools/defender_gallery/_selftest.py，与运行时同构的彩色 matchTemplate）：
     * 正确匹配 ≥ 0.85，错误/竞争者上限 0.766，二者分离清晰。取 0.80 卡在中间：
     * 自测 100% 精度、零误认、98.8% 覆盖。同名不同卡面的守将会因分数不足而**退回
     * UNKNOWN（宁可漏、绝不错认）**，交由 OCR 名字通道与后续扩库兜底。
     * 真机抓帧与截图非同一路径，分数会整体偏低——首次真机按 classify 日志回溯微调。
     */
    private const val MATCH_THRESHOLD = 0.80f

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var loaded = false

    /** 武将名 -> OpenCvMatcher 模板缓存键。 */
    private val heroTemplateKeys = LinkedHashMap<String, String>()

    /** 归一化槽位（l,t,r,b），来自 index.json，与建库工具单一真源。 */
    private var slotRects: List<FloatArray> = emptyList()
    private var canonW = 260
    private var canonH = 150

    /** 仅登记上下文，真正的模板载入延后到首次 classify（避免开机即占内存）。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val isReady: Boolean get() = loaded && heroTemplateKeys.isNotEmpty()

    private fun ensureLoaded(): Boolean {
        if (loaded) return heroTemplateKeys.isNotEmpty()
        val ctx = appContext ?: return false
        // OpenCV 原生库尚未就绪时不要尝试位图转换，留待下次调用重试。
        if (!OpenCvMatcher.isAvailable) return false
        synchronized(this) {
            if (loaded) return heroTemplateKeys.isNotEmpty()
            try {
                val json = ctx.assets.open("$ASSET_DIR/index.json")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(json)

                val canon = root.getJSONArray("canon")
                canonW = canon.getInt(0)
                canonH = canon.getInt(1)

                val slots = root.getJSONArray("slots")
                slotRects = (0 until slots.length()).map { i ->
                    val a = slots.getJSONArray(i)
                    floatArrayOf(
                        a.getDouble(0).toFloat(), a.getDouble(1).toFloat(),
                        a.getDouble(2).toFloat(), a.getDouble(3).toFloat()
                    )
                }

                val heroes = root.getJSONObject("heroes") // 武将名 -> 文件名哈希
                val names = heroes.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val hash = heroes.getString(name)
                    val key = "defender:$name"
                    if (OpenCvMatcher.loadTemplateFromAsset(ctx, "$ASSET_DIR/$hash.png", key)) {
                        heroTemplateKeys[name] = key
                    }
                }
                Log.i(TAG, "头像模板库载入完成: ${heroTemplateKeys.size} 名守将, 槽位 ${slotRects.size}")
            } catch (e: Throwable) {
                Log.w(TAG, "载入头像模板库失败: ${e.message}")
            }
            loaded = true
        }
        return heroTemplateKeys.isNotEmpty()
    }

    /**
     * 对一帧全屏画面识别三行头像槽，返回置信命中的武将名（按槽序、去重）。
     * @param frame 720p 设计画布全屏帧（未回收，由调用方管理生命周期）
     */
    fun classify(frame: Bitmap): List<String> {
        if (!ensureLoaded()) return emptyList()
        if (slotRects.isEmpty() || heroTemplateKeys.isEmpty()) return emptyList()

        val hits = ArrayList<String>()
        try {
            for ((idx, rect) in slotRects.withIndex()) {
                val slot = cropNormalized(frame, rect) ?: continue
                var bestName: String? = null
                var bestScore = 0f
                try {
                    for ((name, key) in heroTemplateKeys) {
                        val r = OpenCvMatcher.match(slot, key, MATCH_THRESHOLD)
                        if (r.isFound && r.score > bestScore) {
                            bestScore = r.score
                            bestName = name
                        }
                    }
                } finally {
                    slot.recycle()
                }
                Log.d(TAG, "槽#$idx 命中=${bestName ?: "无"} 最高分=${"%.3f".format(bestScore)}")
                val hit = bestName
                if (hit != null && !hits.contains(hit)) hits.add(hit)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "头像分类异常: ${e.message}")
        }
        return hits
    }

    /** 按归一化矩形从全屏帧裁剪，并缩放到与模板一致的规范尺寸。 */
    private fun cropNormalized(frame: Bitmap, rect: FloatArray): Bitmap? {
        val w = frame.width
        val h = frame.height
        val l = (rect[0] * w).toInt().coerceIn(0, w - 1)
        val t = (rect[1] * h).toInt().coerceIn(0, h - 1)
        val r = (rect[2] * w).toInt().coerceIn(l + 1, w)
        val b = (rect[3] * h).toInt().coerceIn(t + 1, h)
        return try {
            val crop = Bitmap.createBitmap(frame, l, t, r - l, b - t)
            val scaled = Bitmap.createScaledBitmap(crop, canonW, canonH, true)
            if (scaled !== crop) crop.recycle()
            scaled
        } catch (e: Throwable) {
            null
        }
    }
}
