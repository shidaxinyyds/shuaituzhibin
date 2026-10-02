package com.stzb.assistant.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.json.JSONObject
import kotlin.math.ceil

/**
 * 场景指纹 —— 不依赖 OCR 的「当前是否在游戏大地图」判据
 *
 * ## 为什么需要它
 * 本项目所有依赖文字的识别都建立在 native OCR 之上，而 native OCR 需要构建期链接
 * ncnn/OpenCV（见 `cpp/CMakeLists.txt`）。在拿不到 ncnn 的构建里，
 * `OcrManager.detect()` 恒返回 null，于是 `classifyGameState()` 恒为 `UNKNOWN`。
 *
 * 这个"恒为 UNKNOWN"带来一个非常具体的危害：`WatchdogRecovery.recoverToMainMap()`
 * 因为认不出当前场景，会**反复盲点地图空白区**试图"自愈回大地图"——即使它本来就在
 * 大地图上。这正是用户感知到的"乱点"。
 *
 * ## 原理：用固定美术做指纹
 * 游戏大地图底部有一条功能栏（武将 / 库藏 / 内政 / 势力 / 国家 五张卡片）。
 * 这五张卡片是**固定的游戏美术**：位置固定、内容固定，不随镜头移动而变化
 * （它们是 HUD，不是世界内容）。因此对这几个区域做图像指纹，就能判断
 * "大地图 UI 是否正显示在屏幕上"。
 *
 * 坐标是**在真机截图（2712x1220）上实测**的，不是折算的：用白色标签字形做列簇分割，
 * 测出 5 段标签的中心分别为 0.0979 / 0.1433 / 0.1893 / 0.2353 / 0.2812，
 * **间距高度等距（0.0458）**，标签宽度一致（104px）。
 *
 * ## 指纹是自校准的，不是预置模板
 * [calibrate] 需要用户**站在大地图时记一次**（悬浮控制台 → 标定页签）。
 * 这样做的三个好处：
 *   1. 不依赖我这边猜的模板，用的是用户自己设备的真实渲染结果；
 *   2. 不同分辨率、不同赛季皮肤都能各自校准；
 *   3. 校准是用户可验证的动作——不是"看不见的魔法"。
 *
 * ## 诚实的边界
 *   * 指纹**只能**回答"像不像大地图"，无法区分各类弹窗，因此它只可能返回 MAIN_MAP，
 *     其余一律交回 UNKNOWN，绝不冒充其它场景。
 *   * 它是**启发式**判据，不是 OCR 的替代品：OCR 可用时永远优先用 OCR。
 *   * 游戏大版本更新若改了底部功能栏美术，需要重新校准。
 *   * [Match] 会带上每个区域的汉明距离，日志里能看到具体数值，
 *     便于在真机上判断阈值是否合适，而不是只能信"匹配/不匹配"这一个结论。
 */
object SceneFingerprint {

    private const val TAG = "SceneFingerprint"
    private const val PREFS = "stzb_scene_fingerprint"

    /** 每个区域缩成 8x8 = 64 位均值哈希，与 `ScreenCaptureService.averageHash` 同一思路。 */
    private const val HASH_SIZE = 8

    /**
     * 单区域判定为"同一画面"的汉明距离上限（64 位里允许 10 位不同）。
     *
     * 这个数字不是拍出来的，是用真机截图实测校准的。把本类的算法等价实现在 Python 里、
     * 对用户的真实截图跑过以下测试：
     *
     * | 场景                                   | 实测汉明距离 |
     * |----------------------------------------|--------------|
     * | 同一张大地图截图自比                   | 0            |
     * | **真实变体**：悬浮面板打开时的同一界面 | **1~2**      |
     * | JPEG q=75 再压缩（模拟编码噪声）       | 0            |
     * | 完全不同画面（App 竖屏界面）           | **19~32**    |
     *
     * 也就是说真实变化只造成 1~2 位差异，而"根本不是这个画面"在 19 位以上。
     * 阈值取 10：比真实变化留了 5 倍余量，又远低于错误画面的下限。
     *
     * 为什么宁可宽容：两种误判的代价不对称。
     *   * **漏判**（明明是大地图却说不像）→ `recoverToMainMap()` 退回盲点地图空白区，
     *     正是我们要消除的"乱点"；
     *   * **误判**（不像大地图却说是）→ 后续 `waitForState` 会失败并继续，
     *     代价只是白等一轮。
     * 因此阈值偏向宽容一侧。
     */
    private const val MATCH_TOLERANCE = 10

    /** 至少这么大比例的区域命中，才认为"在大地图"。 */
    private const val MIN_MATCH_RATIO = 0.6f

    /**
     * 一个指纹区域，坐标为设计画布的比例。
     *
     * 实测来源：真机截图 2712x1220，底部功能栏 5 张卡片。
     * `y` 取 0.800~0.970 —— 卡片顶部标签带实测在 0.800~0.880，卡片底部实测约 0.982，
     * 因此 0.970 留在卡片内部。这个边界不是随便取的：实测把下边界从 0.970 放到 1.000，
     * 哈希会偏移 **9 位**（超过噪声量级），说明取到卡片外的地图会实质性地改变指纹。
     */
    private data class Region(
        val label: String,
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float
    )

    private val REGIONS = listOf(
        Region("武将卡", 0.0760f, 0.800f, 0.1198f, 0.970f),
        Region("库藏卡", 0.1213f, 0.800f, 0.1652f, 0.970f),
        Region("内政卡", 0.1674f, 0.800f, 0.2113f, 0.970f),
        Region("势力卡", 0.2131f, 0.800f, 0.2574f, 0.970f),
        Region("国家卡", 0.2592f, 0.800f, 0.3031f, 0.970f)
    )

    @Volatile
    private var appContext: Context? = null

    private val baseline = LinkedHashMap<String, Long>()

    @Volatile
    var calibratedAtMs: Long = 0L
        private set

    val isCalibrated: Boolean
        get() = baseline.size >= REGIONS.size

    /** 绑定应用上下文并载入已保存的指纹。应在 Application 启动时调用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        load()
    }

    /**
     * 记录当前画面的场景指纹。**调用时必须正站在大地图主界面**。
     *
     * @return true 表示成功记录了全部区域；false 表示有区域无法取样（画布尺寸异常等）。
     */
    fun calibrate(frame: Bitmap): Boolean {
        val fresh = LinkedHashMap<String, Long>()
        for (r in REGIONS) {
            val h = regionHash(frame, r)
            if (h == null) {
                Log.w(TAG, "场景指纹取样失败，放弃本次校准（区域: ${r.label}）")
                return false
            }
            fresh[r.label] = h
        }
        baseline.clear()
        baseline.putAll(fresh)
        calibratedAtMs = System.currentTimeMillis()
        save()
        Log.i(TAG, "场景指纹已记录 ${fresh.size} 个区域（大地图基准）")
        return true
    }

    /**
     * 与大地图基准比对。
     *
     * @return null 表示**还没校准**或**无法取样**——调用方必须把它当作"没有结论"，
     *         而不是"不匹配"。
     */
    fun match(frame: Bitmap): Match? {
        if (!isCalibrated) return null
        if (REGIONS.isEmpty()) return null

        var matched = 0
        val distances = ArrayList<Pair<String, Int>>(REGIONS.size)
        for (r in REGIONS) {
            val base = baseline[r.label] ?: return null
            val h = regionHash(frame, r) ?: return null
            val d = java.lang.Long.bitCount(base xor h)
            distances.add(Pair(r.label, d))
            if (d <= MATCH_TOLERANCE) matched++
        }
        return Match(matched, REGIONS.size, distances)
    }

    data class Match(
        val matched: Int,
        val total: Int,
        /** 每个区域的汉明距离，便于在真机上核对阈值是否合适。 */
        val distances: List<Pair<String, Int>>
    ) {
        fun describe(): String =
            "$matched/$total 区域命中 [${distances.joinToString(", ") { "${it.first}=${it.second}" }}]"
    }

    /** 判定该匹配结果是否足以认为"正在显示大地图 UI"。 */
    fun isMainMap(m: Match): Boolean {
        if (m.total <= 0) return false
        val need = maxOf(1, ceil(m.total * MIN_MATCH_RATIO).toInt())
        return m.matched >= need
    }

    /** 需要的命中区域数，用于在界面上说明判据。 */
    fun requiredMatches(): Int =
        maxOf(1, ceil(REGIONS.size * MIN_MATCH_RATIO).toInt())

    fun tolerance(): Int = MATCH_TOLERANCE

    fun clear() {
        baseline.clear()
        calibratedAtMs = 0L
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.clear()?.apply()
        Log.i(TAG, "已清除场景指纹。")
    }

    /**
     * 一行可读状态，含**每个区域的基准哈希**，便于确认校准确实发生了
     * （而不是"点了没反应但没人知道"）。
     */
    fun describe(): String {
        if (!isCalibrated) {
            return "未记录（需站在大地图时点一次「记录场景指纹」；未记录时不使用该判据）"
        }
        val when_ = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(calibratedAtMs))
        return "已记录于 $when_，判据 ${requiredMatches()}/${REGIONS.size} 区域命中" +
            "（单区域汉明距离 ≤ ${MATCH_TOLERANCE}/64）：" +
            REGIONS.joinToString("、") { it.label }
    }

    // ==========================================================
    // 取样与持久化
    // ==========================================================

    private fun regionRect(r: Region): Rect? {
        val w = CoordinateTransformer.virtualWidth.toInt()
        val h = CoordinateTransformer.virtualHeight.toInt()
        if (w <= 1 || h <= 1) return null
        val left = (w * r.l).toInt().coerceIn(0, w - 2)
        val top = (h * r.t).toInt().coerceIn(0, h - 2)
        val right = (w * r.r).toInt().coerceIn(left + 1, w)
        val bottom = (h * r.b).toInt().coerceIn(top + 1, h)
        return Rect(left, top, right, bottom)
    }

    private fun regionHash(frame: Bitmap, r: Region): Long? {
        val rect = regionRect(r) ?: return null
        if (rect.left + rect.width() > frame.width || rect.top + rect.height() > frame.height) {
            Log.w(TAG, "区域 ${r.label} 超出画面范围 $rect vs ${frame.width}x${frame.height}")
            return null
        }
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            crop = Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
            scaled = Bitmap.createScaledBitmap(crop, HASH_SIZE, HASH_SIZE, true)
            val pixels = IntArray(HASH_SIZE * HASH_SIZE)
            scaled.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)

            val gray = IntArray(pixels.size)
            var total = 0
            for (i in pixels.indices) {
                val p = pixels[i]
                val g = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                gray[i] = g
                total += g
            }
            val avg = total / gray.size
            var hash = 0L
            for (i in gray.indices) {
                if (gray[i] >= avg) hash = hash or (1L shl i)
            }
            hash
        } catch (e: Exception) {
            Log.w(TAG, "计算区域指纹异常 [${r.label}]: ${e.message}")
            null
        } finally {
            crop?.recycle()
            scaled?.recycle()
        }
    }

    private fun load() {
        val raw = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getString("data", null) ?: return
        try {
            val o = JSONObject(raw)
            // 哈希是 64 位整数，JSON 数字是 double，会有精度损失，因此按字符串存。
            val h = o.optJSONObject("hashes") ?: return
            baseline.clear()
            for (r in REGIONS) {
                val s = h.optString(r.label, "")
                if (s.isNotEmpty()) {
                    s.toLongOrNull()?.let { baseline[r.label] = it }
                }
            }
            calibratedAtMs = o.optLong("calibratedAtMs", 0L)
            Log.i(TAG, "已载入场景指纹: ${describe()}")
        } catch (e: Exception) {
            Log.w(TAG, "解析场景指纹失败，按未校准处理: ${e.message}")
        }
    }

    private fun save() {
        val ctx = appContext ?: return
        try {
            val hashes = JSONObject()
            baseline.forEach { (k, v) -> hashes.put(k, v.toString()) }
            val root = JSONObject()
                .put("hashes", hashes)
                .put("calibratedAtMs", calibratedAtMs)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("data", root.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存场景指纹失败: ${e.message}")
        }
    }
}
