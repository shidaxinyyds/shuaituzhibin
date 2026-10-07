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

    /** 折算默认值所用的采信门槛。 */
    private const val DEFAULT_MATCH_THRESHOLD = 0.80f

    private const val PREFS = "stzb_defender_classifier"
    private const val KEY_THRESHOLD = "match_threshold"

    /** 可用调节区间：低于 0.50 近乎乱放（误认守将会把危险度往高/低带偏），高于 0.95 近乎全拒。 */
    private const val MIN_MATCH_THRESHOLD = 0.50f
    private const val MAX_MATCH_THRESHOLD = 0.95f

    /**
     * 同尺寸归一化相关系数（TM_CCOEFF_NORMED）的采信门槛**默认值**说明：
     *
     * 离线自测（tools/defender_gallery/_selftest.py，与运行时同构的彩色 matchTemplate）：
     * 正确匹配 ≥ 0.85，错误/竞争者上限 0.766，二者分离清晰。取 0.80 卡在中间：
     * 自测 100% 精度、零误认、98.8% 覆盖。同名不同卡面的守将会因分数不足而**退回
     * UNKNOWN（宁可漏、绝不错认）**，交由 OCR 名字通道与后续扩库兜底。
     */
    @Volatile
    private var matchThreshold = DEFAULT_MATCH_THRESHOLD

    @Volatile
    private var thresholdLoaded = false

    /** 当前生效门槛（供日志与标定入口读取；首次读取会载入持久化值）。 */
    val currentMatchThreshold: Float
        get() {
            ensureThresholdLoaded()
            return matchThreshold
        }

    /**
     * 设定采信门槛并持久化。仅接受 [$MIN_MATCH_THRESHOLD, $MAX_MATCH_THRESHOLD]，
     * 越界则拒绝并保留原值——一次误设会把头像通道整体打死或整体放开，属于安全红线。
     *
     * 用途：真机 MediaProjection 抓帧与截图非同一路径、分数会整体下移，首次真机按
     * classify 日志（含各槽最高分与当前门槛）回溯微调本值，**无需重新编译**即可收敛。
     * @return 是否设置成功
     */
    fun setMatchThreshold(value: Float): Boolean {
        if (value.isNaN() || value < MIN_MATCH_THRESHOLD || value > MAX_MATCH_THRESHOLD) {
            Log.w(
                TAG,
                "拒绝设置门槛 $value：超出可用区间 [$MIN_MATCH_THRESHOLD, $MAX_MATCH_THRESHOLD]，" +
                    "保留 ${"%.3f".format(matchThreshold)}。"
            )
            return false
        }
        // 必须**先**把持久化状态载回来再赋值：原实现先赋值再 ensureThresholdLoaded()，
        // 于是"本进程第一次设门槛"会用旧持久值把刚设的值盖回去（界面显示新值、
        // 实际匹配仍用旧值，重启后又变成旧值）。
        ensureThresholdLoaded()
        matchThreshold = value
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putFloat(scopedThresholdKey(), value)?.apply()
        Log.i(
            TAG,
            "已设 [${com.stzb.assistant.service.PerGameScope.gameId()}] 的守军头像采信门槛为 " +
                "${"%.3f".format(value)}（已持久化）。"
        )
        return true
    }

    /**
     * 门槛的存储键带游戏后缀。
     *
     * 门槛是"在这款游戏的头像美术 + 这台机型的抓帧路径"上收敛出来的经验值：
     * 率土上调到 0.86 不代表三战也该 0.86，共用一个值会让其中一款要么整体打死、
     * 要么整体放开（这两端都正是注释里写明的安全红线）。
     */
    private fun scopedThresholdKey(): String =
        com.stzb.assistant.service.PerGameScope.key(KEY_THRESHOLD)

    /** 从持久化恢复门槛（若曾标定）。appContext 未就绪时留待 init 之后重试。 */
    private fun ensureThresholdLoaded() {
        val ctx = appContext ?: return
        if (thresholdLoaded) return
        synchronized(this) {
            if (thresholdLoaded) return
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = scopedThresholdKey()
            if (sp.contains(key)) {
                matchThreshold = sp.getFloat(key, DEFAULT_MATCH_THRESHOLD)
                    .coerceIn(MIN_MATCH_THRESHOLD, MAX_MATCH_THRESHOLD)
            } else {
                // 本游戏没设过：回到出厂值，绝不沿用上一款游戏的门槛。
                matchThreshold = DEFAULT_MATCH_THRESHOLD
            }
            thresholdLoaded = true
        }
    }

    @Volatile
    private var appContext: Context? = null

    /** 切游戏钩子是否已注册（保证只注册一次）。 */
    @Volatile
    private var switchHookRegistered = false

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
        ensureThresholdLoaded()
        if (!switchHookRegistered) {
            switchHookRegistered = true
            // 切游戏：整套头像库与门槛都要跟着换。头像库是照着某款游戏的守将美术建的，
            // 拿它去认另一款游戏的守将，输出的是一条**看起来很有依据的错评级**。
            com.stzb.assistant.service.PerGameScope.reloadOnProfileSwitch { resetForGameSwitch() }
        }
    }

    /** 换游戏后作废内存里的头像库与门槛，下一次 classify 按新游戏重新载入。 */
    private fun resetForGameSwitch() {
        synchronized(this) {
            loaded = false
            thresholdLoaded = false
            heroTemplateKeys.clear()
            slotRects = emptyList()
        }
        ensureThresholdLoaded()
        Log.i(
            TAG,
            "已按 [${com.stzb.assistant.service.PerGameScope.gameId()}] 作废头像库缓存，" +
                "门槛回到本游戏的持久值 ${"%.3f".format(matchThreshold)}。"
        )
    }

    val isReady: Boolean get() = loaded && heroTemplateKeys.isNotEmpty()

    private fun ensureLoaded(): Boolean {
        if (loaded) return heroTemplateKeys.isNotEmpty()
        val ctx = appContext ?: return false
        // OpenCV 原生库尚未就绪时不要尝试位图转换，留待下次调用重试。
        if (!OpenCvMatcher.isAvailable) return false
        synchronized(this) {
            if (loaded) return heroTemplateKeys.isNotEmpty()
            // 候选目录：本游戏专属 →（仅率土）旧的无后缀目录。
            // 绝不跨游戏兜底：读不到就如实"没有头像库"，而不是拿别款游戏的守将美术来认人。
            var loadedFrom: String? = null
            for (dir in com.stzb.assistant.service.PerGameScope.assetDirs(ASSET_DIR)) {
                if (loadFromDir(ctx, dir)) { loadedFrom = dir; break }
            }
            if (loadedFrom == null) {
                Log.w(TAG, "本游戏没有可用的头像模板库（已尝试目录，均无 index.json），守军头像通道不启用。")
            }
            loaded = true
        }
        return heroTemplateKeys.isNotEmpty()
    }

    /** 从某个资产目录载入 index.json 与全部头像模板；失败返回 false（由调用方换下一个目录）。 */
    private fun loadFromDir(ctx: Context, dir: String): Boolean {
        return try {
            val json = ctx.assets.open("$dir/index.json")
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
            var loadedCount = 0
            while (names.hasNext()) {
                val name = names.next()
                val hash = heroes.getString(name)
                val key = "defender:$name"
                if (OpenCvMatcher.loadTemplateFromAsset(ctx, "$dir/$hash.png", key)) {
                    heroTemplateKeys[name] = key
                    loadedCount++
                }
            }
            Log.i(TAG, "头像模板库载入完成（$dir）: $loadedCount 名守将, 槽位 ${slotRects.size}")
            loadedCount > 0
        } catch (e: Throwable) {
            Log.w(TAG, "从 [$dir] 载入头像模板库失败: ${e.message}")
            false
        }
    }

    /**
     * 对一帧全屏画面识别三行头像槽，返回置信命中的武将名（按槽序、去重）。
     * @param frame 720p 设计画布全屏帧（未回收，由调用方管理生命周期）
     */
    fun classify(frame: Bitmap): List<String> {
        if (!ensureLoaded()) return emptyList()
        if (slotRects.isEmpty() || heroTemplateKeys.isEmpty()) return emptyList()

        val thr = currentMatchThreshold
        val hits = ArrayList<String>()
        Log.d(TAG, "本次比对门槛=${"%.3f".format(thr)}（真机可按各槽最高分用 setMatchThreshold 微调）")
        try {
            for ((idx, rect) in slotRects.withIndex()) {
                val slot = cropNormalized(frame, rect) ?: continue
                var bestName: String? = null
                var bestScore = 0f
                try {
                    for ((name, key) in heroTemplateKeys) {
                        val r = OpenCvMatcher.match(slot, key, thr)
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
