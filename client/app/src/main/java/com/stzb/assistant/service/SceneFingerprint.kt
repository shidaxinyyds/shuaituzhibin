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
 *   * **换分辨率不需要重校**：捕获画布恒为高 720（[CoordinateTransformer.refreshMetrics]
 *     按长宽比推宽度），所以像素密度不同的机器看到的是同一张画布；区域矩形一律
 *     按高折算（见 [REF_CANVAS_WIDTH]），长宽比也不进入几何。
 *     但这建立在一条**尚未在第二台真机上验证**的假设上：游戏 HUD 的尺寸随屏幕短边缩放。
 *     若某台机器上 HUD 改按宽缩放，这里会整体错位，症状是 [describe] 显示已校准而
 *     [match] 的距离普遍偏大 —— 那种情况下重录一次即可（校准用的就是那台机器自己的像素）。
 *   * [Match] 会带上每个区域的汉明距离，日志里能看到具体数值，
 *     便于在真机上判断阈值是否合适，而不是只能信"匹配/不匹配"这一个结论。
 */
object SceneFingerprint {

    private const val TAG = "SceneFingerprint"
    private const val PREFS = "stzb_scene_fingerprint"

    /** 每个区域缩成 8x8 = 64 位均值哈希，与 `ScreenCaptureService.averageHash` 同一思路。 */
    private const val HASH_SIZE = 8

    /**
     * 测得 [REGIONS] 那批比例时所用的参考画布宽。
     *
     * 那台机器是 2712x1220，按 [CoordinateTransformer.refreshMetrics] 的公式
     * `roundToInt(720 * 2712 / 1220)` 真实得到的是 **1601** 宽；这里写成整数 1600
     * 只带来约 0.07px 的区域偏移（一张卡片宽约 70px），可忽略，但必须记下这个差异，
     * 免得后来人以为"参考宽"和画布宽是同一个数。
     *
     * 区域矩形一律按**画布高**折算像素，这个常量只是把当年量到的「宽比例」还原成像素，
     * 于是长宽比不再进入几何：16:9 的机器画布宽 1280，底部那排卡片仍然在同样的
     * 像素位置上（HUD 尺寸由屏幕短边决定）。
     * 实测（21 张真机截图，把画布裁到不同长宽比后重算）：
     *   * 按高折算：1280 / 1440 / 1584 / 1600 / 1620 宽的画布上，区域像素**一字不差**；
     *   * 按宽折算：换到 1280 宽时五个区域的汉明距离变成 **20~46**（阈值 10），
     *     也就是把指纹切到了卡片左侧的箭头与卡片之间的缝隙上 —— 区域整体错位约 25px，
     *     而一张卡片才 70px 宽。同一台机器上自校准会把这个错位"恒定地"吸收掉，
     *     但安全余量被吃掉：正例最差距离从 12 涨到 18，而负例最近距离只有 20。
     */
    private const val REF_CANVAS_WIDTH = 1600f

    /**
     * 区域几何的口径版本。
     *
     * V1 的区域矩形按「画布宽的比例」算（换长宽比就切错内容，见 [REF_CANVAS_WIDTH]），
     * V2 改为按高算。基准哈希是**用旧矩形取样的像素**，换了矩形它们就不再可比，
     * 所以载入时版本不符就作废，并明确要求用户重录一次 —— 绝不能拿旧哈希继续判，
     * 那会把"没结论"伪装成"不像大地图"，正好触发本判据要消除的乱点。
     */
    private const val GEOM_VERSION = 2

    /**
     * 单区域判定为"同一画面"的汉明距离上限（64 位里允许 10 位不同）。
     *
     * 这个数字不是拍出来的，是用真机截图实测校准的。把本类的算法等价实现在 Python 里、
     * 对用户的真实截图跑过以下测试：
     *
     * | 场景                                   | 实测汉明距离 |
     * |----------------------------------------|--------------|
     * | 同一张大地图截图自比                   | 0            |
     * | JPEG q=75 再压缩（模拟编码噪声）       | 0            |
     * | **真实变体**：悬浮面板打开时的同一界面 | **1~2**      |
     * | 完全不同画面（App 竖屏界面）           | **19~32**    |
     *
     * 后来拿到 21 张真机截图（基准大地图 1 张 + 判为大地图的 11 张 + 其它界面 9 张，
     * 全部参与，没有一张被搁置），以现在这套几何重跑一遍，
     * 得到的是**更窄但也更真实**的一组数字（`tools/validate_scene_fingerprint.py` 可复现）：
     *   * 11 张大地图抓屏与基准比对：每个区域最差 武将9 / 库藏5 / 内政10 / 势力4 / **国家12**，
     *     即绝大多数区域落在阈值内，只有"国家卡"这一张会越界（原因见 [MIN_MATCH_RATIO]）；
     *   * 9 张其它界面：**没有任何一张凑到 3 个区域**，最近的单个区域也有 **17** 位。
     *
     * 也就是说真实变化最大 12 位，"根本不是这个画面"最小 17 位。阈值取 10：
     * 落在真实变化之下、又留出了与错误画面之间的距离。
     *
     * 为什么宁可宽容：两种误判的代价不对称。
     *   * **漏判**（明明是大地图却说不像）→ `recoverToMainMap()` 退回盲点地图空白区，
     *     正是我们要消除的"乱点"；
     *   * **误判**（不像大地图却说是）→ 后续 `waitForState` 会失败并继续，
     *     代价只是白等一轮。
     * 因此阈值偏向宽容一侧。
     */
    private const val MATCH_TOLERANCE = 10

    /**
     * 至少这么大比例的区域命中，才认为"在大地图"。
     *
     * 0.6（5 个里对 3 个）不是拍的，真机截图实测给出的下界是 4/5：
     * 底部第 5 张卡的文案会在**「国家」与「同盟」之间变化**（取决于玩家当前状态），
     * 那一块的美术随之改变——21 张真机截图复现跑出的该区域最差汉明距离 **12**
     * （更早一批单图测量给过 13），恰好越出 [MATCH_TOLERANCE]=10。
     * 也就是说"同为大地图、只差一个字"的两种状态里，**必然有一个区域对不上**。
     * 若这里写成 1.0（要求全对），这个指纹会在玩家状态切换时随机失灵。
     *
     * 上界：**9 张完全不同的界面（战报 / 信件 / 城池 / 科技树 / 跳转面板 / 部队总览 …）
     * 实测全部 0/5**，一帧都没有凑到 3 个区域。但这里必须写清余量到底在哪：
     * 负例最近的**单个**区域只有 **17** 位，而正例最差是 12 位 —— 单区域维度上那条沟
     * 只有 5 位宽，真正撑住判别力的是"要同时凑够 3 个区域"这一条。
     * 所以 [MATCH_TOLERANCE] 与 [MIN_MATCH_RATIO] 是一对，动任何一个都要重跑闸门。
     */
    private const val MIN_MATCH_RATIO = 0.6f

    /**
     * 一个指纹区域。
     *
     * **x 用「参考画布宽的比例」，y 用「画布高的比例」** —— 两者都最终按**高**折算成像素，
     * 因为画布高恒为 [CoordinateTransformer.BASE_HEIGHT]（见 [regionRect]）。
     * 参考画布宽取测得这批数字时的那台机器（20:9 名义上 1600，实际画布 1601，见
     * [REF_CANVAS_WIDTH]）。在那台已校准的机器上，两种口径取到的框最多错开 1px，
     * 区域哈希相差 0/0/1/4/2 位（阈值 10）—— 也就是说**同一台机器上**旧基准并不会
     * 因为这次改口径而失效。但在别的长宽比上差的是 20~46 位（见 [REF_CANVAS_WIDTH]），
     * 而我们无法从存盘的哈希里判断它是哪种情况，所以 [GEOM_VERSION] 不符时一律作废。
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
        // 最后一张卡的文案不是常量：真机实测会在「国家」与「同盟」之间切换，
        // 该区域因此是唯一一个可能长期对不上的区域 —— [MIN_MATCH_RATIO] 留 0.6 就是为它留的。
        Region("国家卡", 0.2592f, 0.800f, 0.3031f, 0.970f)
    )

    @Volatile
    private var appContext: Context? = null

    /** 切游戏重载的钩子是否已注册（保证只注册一次）。 */
    @Volatile
    private var switchHookRegistered = false

    private val baseline = LinkedHashMap<String, Long>()

    @Volatile
    var calibratedAtMs: Long = 0L
        private set

    /**
     * 存盘的指纹是**旧口径几何**取样的、已作废（见 [GEOM_VERSION]）。
     *
     * 单列一个标记而不是静默丢掉：静默丢掉后界面上只会显示"未记录"，
     * 用户不知道自己需要做一次动作；而这里明确说"口径升级过，请重录一次"。
     */
    @Volatile
    var needsRecalibration: Boolean = false
        private set

    val isCalibrated: Boolean
        get() = baseline.size >= REGIONS.size

    /** 绑定应用上下文并载入已保存的指纹。应在 Application 启动时调用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        load()
        // 基准哈希是在**这一款游戏**的大地图主界面上取的；换游戏还不换基准，
        // 就会拿 A 的底部功能栏去判 B 的界面"像主界面"。分域 + 切游戏重载。
        if (!switchHookRegistered) {
            switchHookRegistered = true
            PerGameScope.reloadOnProfileSwitch { reload() }
        }
    }

    /** 换游戏后重新载入（未校准的游戏如实回到"无结论"）。 */
    private fun reload() {
        baseline.clear()
        calibratedAtMs = 0L
        needsRecalibration = false
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
        // 刚刚是在**当前口径**下取样的，作废标记随之解除。
        needsRecalibration = false
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
        needsRecalibration = false
        // 只清本游戏那一份基准：edit().clear() 会把其它游戏的指纹一起抹掉。
        appContext?.let { PerGameScope.removeScopedString(it, PREFS, "data") }
        Log.i(TAG, "已清除 [${PerGameScope.gameId()}] 的场景指纹。")
    }

    /**
     * 一行可读状态，含**每个区域的基准哈希**，便于确认校准确实发生了
     * （而不是"点了没反应但没人知道"）。
     */
    fun describe(): String {
        if (!isCalibrated) {
            if (needsRecalibration) {
                return "基准已作废（几何口径升级到 V$GEOM_VERSION：区域矩形改按画布高折算）" +
                    "——请站在大地图时重点一次「记录场景指纹」；重录前不使用该判据"
            }
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
        // x 也按**高**折算（不是按宽）：画布高恒为 720，所以"高比例"就是机型无关的常量；
        // 而"宽比例"会随长宽比整体伸缩，把左对齐的 HUD 卡片切成别的内容 ——
        // 见 [REF_CANVAS_WIDTH] 里那组实测距离。y 本来就是高比例，保持不变。
        val sx = h / CoordinateTransformer.BASE_HEIGHT
        val left = (r.l * REF_CANVAS_WIDTH * sx).toInt().coerceIn(0, w - 2)
        val top = (h * r.t).toInt().coerceIn(0, h - 2)
        val right = (r.r * REF_CANVAS_WIDTH * sx).toInt().coerceIn(left + 1, w)
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
        val ctx = appContext ?: return
        // 键按游戏分域（data_stzb / data_sgz），旧键 data 一次性迁进率土域。
        val raw = PerGameScope.readScopedString(ctx, PREFS, "data") ?: return
        try {
            val o = JSONObject(raw)
            // 口径不符就整体作废：区域矩形已经换了折算方式（宽比例 → 按高折算），
            // 旧哈希对应的取样像素根本不在现在的位置上，继续判就会把"没结论"
            // 伪装成"不像大地图"，那正是本判据要消除的乱点。
            val geom = o.optInt("geomVersion", 1)
            if (geom != GEOM_VERSION) {
                needsRecalibration = true
                Log.w(
                    TAG,
                    "存盘的场景指纹是几何口径 V$geom 取样的，当前 V$GEOM_VERSION：" +
                        "基准已作废，需要站在大地图重录一次。"
                )
                return
            }
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
            Log.i(TAG, "已载入 [${PerGameScope.gameId()}] 的场景指纹: ${describe()}")
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
                // 口径版本必须与哈希一起存：没有它，换几何之后旧哈希会被当成有效基准继续判。
                .put("geomVersion", GEOM_VERSION)
            PerGameScope.writeScopedString(ctx, PREFS, "data", root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "保存场景指纹失败: ${e.message}")
        }
    }
}
