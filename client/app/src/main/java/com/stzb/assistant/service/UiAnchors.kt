package com.stzb.assistant.service

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import org.json.JSONObject

/**
 * 战术 UI 锚点表 (UiAnchors) —— 单一来源、按比例表达、可被用户标定
 *
 * ## 为什么要有这个文件
 * 出征选队标签的坐标曾在 4 个 Flow 里各写一份硬编码：
 * `RoadPavingFlow` / `ImmunityBreakFlow` / `SiegeSyncFlow` / `RaidDefenseFlow`
 * 全都是 `startX = 220f, stepX = 140f, y = 160f`；另有 2 份"右下角 360x160"行军耗时 ROI
 * 与 1 份 `Rect(180,220,650,550)` 卡片 ROI。
 *
 * 这里有两个独立问题：
 *   1. **一式四份**：改一处必须记得改四处，天然腐化点；
 *   2. **不随长宽比缩放**：这组常量是按 1280 宽的画布量的。在 20:9 机型上设计画布宽是 1600，
 *      而游戏的出征面板是按屏幕比例铺开的——于是同一个 `x = 220` 在不同机型上
 *      落在面板里**不同的相对位置**。这正是"某些机型选不到部队、确定键点不亮"的成因。
 *
 * 因此改为：**用相对设计画布的比例表达**（0.0~1.0），并按需持久化用户标定值。
 * 比例表达天然跟随机型缩放；标定值则让真机上量到的准确位置可以覆盖默认值。
 *
 * ## 按游戏分域（见 [PerGameScope]）
 * 标定值是**在这款游戏的界面上量的**，因此存储键带游戏后缀（`data_stzb` / `data_sgz`），
 * 切换知识库时整套重载。未标定的游戏只用默认比例，绝不借用别的手游的量。
 *
 * ## 关于默认值的来源
 * 默认比例是把原有常量按 "1280x720 参考画布" 折算得到的
 * （例如 `220/1280 = 0.1719`、`160/720 = 0.2222`），**不是**在真机上量出来的。
 * 所以真机首次使用时应走一次标定；[describeAll] 会明确标出哪些仍是默认值。
 */
object UiAnchors {

    private const val TAG = "UiAnchors"
    private const val PREFS = "stzb_ui_anchors"

    /** 折算默认值所用的参考画布（旧常量就是按它量的）。 */
    private const val REF_WIDTH = 1280f
    private const val REF_HEIGHT = 720f

    @Volatile
    private var appContext: Context? = null

    /** 切游戏重载的钩子是否已注册（保证只注册一次）。 */
    @Volatile
    private var switchHookRegistered = false

    /**
     * 点锚点。`defaultFx/defaultFy` 是相对设计画布的比例。
     */
    enum class Key(val label: String, val defaultFx: Float, val defaultFy: Float) {
        TROOP_TAB_1("出征面板·部队一标签", 220f / REF_WIDTH, 160f / REF_HEIGHT),
        TROOP_TAB_2("出征面板·部队二标签", 360f / REF_WIDTH, 160f / REF_HEIGHT),
        TROOP_TAB_3("出征面板·部队三标签", 500f / REF_WIDTH, 160f / REF_HEIGHT),
        TROOP_TAB_4("出征面板·部队四标签", 640f / REF_WIDTH, 160f / REF_HEIGHT),
        TROOP_TAB_5("出征面板·部队五标签", 780f / REF_WIDTH, 160f / REF_HEIGHT),
        MAP_BLANK("地图空白点（收起浮层）", 640f / REF_WIDTH, 0.62f),
        BOOKMARK_ENTRY("大地图书签/标记入口（左上侧）", 46f / REF_WIDTH, 140f / REF_HEIGHT);

        /** 该锚点当前是否被用户标定过（false 表示用的是折算默认值）。 */
        val isCalibrated: Boolean
            get() = UiAnchors.isPointCalibrated(this)
    }

    /**
     * 矩形锚点（局部识别区域）。
     * 用比例表达，`right/bottom` 取 1.0 表示贴到画布右/下边缘。
     */
    enum class RectKey(
        val label: String,
        val defaultL: Float,
        val defaultT: Float,
        val defaultR: Float,
        val defaultB: Float
    ) {
        TROOP_CARD(
            "出征面板·部队卡片区",
            180f / REF_WIDTH,
            220f / REF_HEIGHT,
            650f / REF_WIDTH,
            550f / REF_HEIGHT
        ),
        MARCH_TIME(
            "行军耗时文本区（右下）",
            (REF_WIDTH - 360f) / REF_WIDTH,
            (REF_HEIGHT - 160f) / REF_HEIGHT,
            1.0f,
            1.0f
        ),
        HUD_WORLD_COORD(
            "HUD 当前坐标读数（右上）",
            // 实测值（真机截图 2712x1220）：坐标文字区为 x 0.9049~0.9598, y 0.1451~0.1943，
            // 这里四周各留约 0.6~0.8% 余量以适配其它皮肤的字号差异。
            //
            // 原先的 0.88/0.09/1.00/0.22 是凭肉眼估的，会把上方「于禁冬至画像 / 分享赢888虎符」
            // 那条亮色横幅（实测 y 0.089~0.143）一起圈进来，给 OCR 引入无关文字干扰。
            0.892f,
            0.126f,
            0.974f,
            0.214f
        ),
        DEFENDER_PANEL(
            "查看守军·武将名单区",
            // 这是**宽松的默认值**：守军面板是居中的模态框，先把中部大部分圈进来。
            //
            // 偏大是**有意**的，而且是偏安全的方向：`DefenderEvaluator` 对多个守军取"最危险"，
            // 因此多圈进来的文字只会让评级更危险，**不会**让危险守将被降级放过。
            // 但偏大也会把无关武将名带进来（例如顶部横幅里的名字），
            // 所以真机上建议到「标定」页签用「⑦ 标定识别区域」把它收紧。
            0.12f,
            0.12f,
            0.88f,
            0.78f
        );

        val isCalibrated: Boolean
            get() = UiAnchors.isRectCalibrated(this)
    }

    private val pointOverrides = HashMap<Key, PointF>()
    private val rectOverrides = HashMap<RectKey, Rect>()

    /**
     * 该点锚点是否已被用户标定。
     *
     * 做成对象上的公开函数而不是让枚举直接读私有表：
     * `enum class` 嵌套在 `object` 内时是静态嵌套类，直接访问外部 object 的
     * 实例成员存在可见性/限定名上的坑，走公开访问器可以完全避开。
     */
    fun isPointCalibrated(key: Key): Boolean = pointOverrides.containsKey(key)

    /** 该矩形锚点是否已被用户标定。 */
    fun isRectCalibrated(key: RectKey): Boolean = rectOverrides.containsKey(key)

    /** 绑定应用上下文并载入用户标定值。应在 Application 启动时调用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        load()
        // 锚点是"这台设备 + 这款游戏 + 这个机型"的产物，切游戏必须整套换掉：
        // 率土出征面板量出来的部队标签位置用在三战面板上，就是往空白处点。
        // 只注册一次（attach 可能被重复调用）。
        if (!switchHookRegistered) {
            switchHookRegistered = true
            PerGameScope.reloadOnProfileSwitch { reload() }
        }
    }

    /** 换游戏后重新载入：先丢掉内存里上一款游戏的标定，再读本游戏的域。 */
    private fun reload() {
        pointOverrides.clear()
        rectOverrides.clear()
        load()
        Log.i(TAG, "已按 [${PerGameScope.gameId()}] 重新载入锚点：${describeAll()}")
    }

    // ==========================================================
    // 点锚点
    // ==========================================================

    /** 取锚点在设计画布上的坐标。 */
    fun point(key: Key): PointF {
        pointOverrides[key]?.let { return PointF(it.x, it.y) }
        return PointF(
            CoordinateTransformer.virtualWidth * key.defaultFx,
            CoordinateTransformer.virtualHeight * key.defaultFy
        )
    }

    /** 部队槽位对应的点锚点键（1 起；越界钳到 1..5）。 */
    fun troopTabKey(slot: Int): Key {
        val keys = arrayOf(
            Key.TROOP_TAB_1, Key.TROOP_TAB_2, Key.TROOP_TAB_3, Key.TROOP_TAB_4, Key.TROOP_TAB_5
        )
        return keys[(slot - 1).coerceIn(0, keys.size - 1)]
    }

    /** 部队标签锚点的可标定槽位数（1..5）。 */
    val troopTabSlotCount: Int get() = 5

    /**
     * 出征面板部队标签坐标。
     *
     * @param slot 部队槽位（1 起）。超出 1..5 时按第 5 个锚点加等距外推，
     *             保持与原实现"越界也能给出一个点"的行为一致。
     */
    fun troopTab(slot: Int): PointF {
        val index = slot.coerceAtLeast(1)
        if (index <= troopTabSlotCount) return point(troopTabKey(index))

        val last = point(troopTabKey(troopTabSlotCount))
        val secondLast = point(troopTabKey(troopTabSlotCount - 1))
        val stepX = last.x - secondLast.x
        return PointF(last.x + stepX * (index - troopTabSlotCount), last.y)
    }

    /** 用设计画布坐标覆盖某个点锚点（即"标定"）。 */
    fun calibrate(key: Key, virtualX: Float, virtualY: Float) {
        pointOverrides[key] = PointF(virtualX, virtualY)
        save()
        Log.i(TAG, "已标定锚点 [${key.label}] = ($virtualX, $virtualY)")
    }

    // ==========================================================
    // 矩形锚点
    // ==========================================================

    /** 取矩形锚点在设计画布上的像素矩形，并钳制在画布范围内。 */
    fun rect(key: RectKey): Rect {
        rectOverrides[key]?.let { return Rect(it) }
        val w = CoordinateTransformer.virtualWidth
        val h = CoordinateTransformer.virtualHeight
        return clampToCanvas(
            Rect(
                (w * key.defaultL).toInt(),
                (h * key.defaultT).toInt(),
                (w * key.defaultR).toInt(),
                (h * key.defaultB).toInt()
            )
        )
    }

    /**
     * 用设计画布坐标覆盖某个矩形锚点。
     *
     * @return 是否成功保存；矩形过小时返回 false（**不**存进去）
     *
     * 两处防御：
     * 1. **先归一化**。调用方给出的两个角不保证先后（用户可能从右下往左上方拖选），
     *    而 [clampToCanvas] 里的 `coerceIn` 会把反向矩形压成 **1px 宽的细条**——
     *    不崩，但那个识别区域会**永久失效**，用户只看到"识别不到文字"，查不出原因。
     * 2. **拒绝过小的矩形**。一个 16px 以下的区域同样永远读不到字，
     *    存进去只会制造一个看起来已标定、实际无用的锚点。宁可保留原值并说明。
     */
    fun calibrateRect(key: RectKey, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        val l = minOf(left, right)
        val r = maxOf(left, right)
        val t = minOf(top, bottom)
        val b = maxOf(top, bottom)
        if (r - l < MIN_CALIB_RECT_PX || b - t < MIN_CALIB_RECT_PX) {
            Log.w(
                TAG,
                "拒绝标定矩形锚点 [${key.label}] = ($l,$t,$r,$b)：尺寸过小" +
                    "（${r - l}x${b - t}，要求至少 ${MIN_CALIB_RECT_PX}px）。" +
                    "过小的识别区域会永远读不到文字，因此保留原值。"
            )
            return false
        }
        rectOverrides[key] = clampToCanvas(Rect(l, t, r, b))
        save()
        Log.i(TAG, "已标定矩形锚点 [${key.label}] = ${rectOverrides[key]}")
        return true
    }

    /** 标定矩形的最小边长（设计画布像素）。低于它的区域没有识别价值。 */
    private const val MIN_CALIB_RECT_PX = 16

    private fun clampToCanvas(r: Rect): Rect {
        val w = CoordinateTransformer.virtualWidth.toInt()
        val h = CoordinateTransformer.virtualHeight.toInt()
        // 保证非空：宽高至少 1px，否则下游 createBitmap 会抛异常
        val left = r.left.coerceIn(0, maxOf(0, w - 1))
        val top = r.top.coerceIn(0, maxOf(0, h - 1))
        val right = r.right.coerceIn(left + 1, w)
        val bottom = r.bottom.coerceIn(top + 1, h)
        return Rect(left, top, right, bottom)
    }

    /**
     * 清除**本游戏**的全部标定，回到折算默认值。
     *
     * 这里刻意不用 `prefs.edit().clear()`：prefs 文件现在同时装着好几款游戏的
     * 标定，clear() 会把别的游戏一起抹掉，用户会发现"清一个游戏，另一个也白标了"。
     */
    fun clearAllCalibration() {
        pointOverrides.clear()
        rectOverrides.clear()
        appContext?.let { PerGameScope.removeScopedString(it, PREFS, "data") }
        Log.i(TAG, "已清除 [${PerGameScope.gameId()}] 的全部 UI 锚点标定，回到默认值。")
    }

    /**
     * 一行可打进 logcat 的锚点概览。
     * 排查"选不到部队""识别区域为空"时先看它：能立刻区分是默认值不准还是标定丢了。
     */
    fun describeAll(): String {
        val sb = StringBuilder("UI 锚点（画布 ${CoordinateTransformer.virtualWidth}x${CoordinateTransformer.virtualHeight}）")
        Key.values().forEach { k ->
            val p = point(k)
            val src = if (k.isCalibrated) "已标定" else "默认"
            sb.append("\n  [").append(src).append("] ").append(k.label)
                .append(" = (").append(p.x.toInt()).append(", ").append(p.y.toInt()).append(")")
        }
        RectKey.values().forEach { k ->
            val r = rect(k)
            val src = if (k.isCalibrated) "已标定" else "默认"
            sb.append("\n  [").append(src).append("] ").append(k.label).append(" = ").append(r)
        }
        return sb.toString()
    }

    /** 默认值是否覆盖了真机。未标定项越多，"点不准"的风险越高。 */
    fun uncalibratedCount(): Int =
        Key.values().count { !it.isCalibrated } + RectKey.values().count { !it.isCalibrated }

    // ==========================================================
    // 持久化
    // ==========================================================

    private fun load() {
        val ctx = appContext ?: return
        // 键按游戏分域（data_stzb / data_sgz），旧键 data 一次性迁进率土域。
        val raw = PerGameScope.readScopedString(ctx, PREFS, "data") ?: return
        try {
            val root = JSONObject(raw)
            root.optJSONObject("points")?.let { points ->
                Key.values().forEach { k ->
                    val arr = points.optJSONArray(k.name) ?: return@forEach
                    if (arr.length() >= 2) {
                        pointOverrides[k] = PointF(arr.getDouble(0).toFloat(), arr.getDouble(1).toFloat())
                    }
                }
            }
            root.optJSONObject("rects")?.let { rects ->
                RectKey.values().forEach { k ->
                    val arr = rects.optJSONArray(k.name) ?: return@forEach
                    if (arr.length() >= 4) {
                        rectOverrides[k] = clampToCanvas(
                            Rect(
                                arr.getInt(0), arr.getInt(1), arr.getInt(2), arr.getInt(3)
                            )
                        )
                    }
                }
            }
            Log.i(
                TAG,
                "已载入 [${PerGameScope.gameId()}] 的 UI 锚点标定：点 ${pointOverrides.size} 项 / 矩形 ${rectOverrides.size} 项。"
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析 UI 锚点标定失败，改用默认值: ${e.message}")
        }
    }

    private fun save() {
        val ctx = appContext ?: return
        try {
            val points = JSONObject()
            pointOverrides.forEach { (k, p) ->
                points.put(k.name, org.json.JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
            }
            val rects = JSONObject()
            rectOverrides.forEach { (k, r) ->
                rects.put(
                    k.name,
                    org.json.JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom)
                )
            }
            val root = JSONObject().put("points", points).put("rects", rects)
            PerGameScope.writeScopedString(ctx, PREFS, "data", root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "保存 UI 锚点标定失败: ${e.message}")
        }
    }
}
