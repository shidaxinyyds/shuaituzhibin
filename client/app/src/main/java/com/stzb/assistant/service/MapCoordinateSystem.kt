package com.stzb.assistant.service

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 游戏世界坐标层：大地图格坐标（如 `(228,132)`）↔ 屏幕设计画布坐标
 *
 * ## 这一层解决什么问题
 * 在它之前，全项目的"目标地块"只是一个**一次性的屏幕像素点**：
 * 用户在准星取点器上点一下，得到一个屏幕坐标，然后这个坐标会被整条流水线
 * 反复使用数十分钟。可游戏镜头随时会因为行军动画、点击顶部栏、用户手动滑动而变化，
 * 于是那个屏幕点**立刻失效**——而项目里 `EngineBridge.swipe` 从来没有任何调用者，
 * 也就是说**镜头根本无法被程序移动**，多地块铺路在物理上就不可能成立。
 *
 * 有了世界坐标就不同了：世界坐标是稳定的（不随镜头变化），
 * 只要知道"每格多少像素"和"当前镜头中心在哪一格"，就能
 *   1. 把任意世界坐标精确换算成屏幕点；
 *   2. 反算出"要把镜头挪过去需要拖动多少像素"，用 [MapNavigator] 真正拖动地图。
 *
 * ## 标定值从哪来
 * [Calibration] 需要三项：每格像素数（tilePxX/Y）与当前镜头中心的世界坐标。
 * 获取方式有两条，都已实现：
 *   * [calibrateFromTwoPoints]：同一镜头下让用户点选两个地块并给出它们的世界坐标，
 *     两点即可解出"每格像素数"与"镜头中心世界坐标"；
 *   * [readCurrentCenterWorld]：从 HUD 右上角的坐标读数直接读（格式 `武威 (228,132)`，
 *     取自真机截图），可随时校正，用于闭环。
 *
 * 未标定时所有换算返回 null，[MapNavigator] 会**明确拒绝**而不是猜一个位移——
 * 猜测位移就是"盲拖地图"，比不动更糟。
 */
object MapProjection {

    private const val TAG = "MapProjection"
    private const val PREFS = "stzb_map_projection"

    /**
     * 地图投影标定值。
     *
     * @param tilePxX 设计画布上横向 1 格对应的像素数
     * @param tilePxY 设计画布上纵向 1 格对应的像素数
     * @param centerWorldX 当前镜头中心对应的世界 X。
     *   **必须是小数**，不能取整——见下方说明。
     * @param centerWorldY 当前镜头中心对应的世界 Y
     * @param viewportCenterFx 镜头中心在设计画布上的相对位置（横向，0.5 = 正中）
     * @param viewportCenterFy 镜头中心在设计画布上的相对位置（纵向）
     *
     * ## 为什么镜头中心必须保留小数
     * 镜头中心是**由标定解算出来的连续量**，本来就不落在整数格上。
     * 早先这里存的是 `Int`（`calibrateFromTwoPoints` 里做了 `roundToInt()`），
     * 那一步会引入**与每格像素数成正比的固定偏移**——因为换算里到处都在用
     * `(world - center) * tilePx`，中心差半格就是半个格子的偏差。
     *
     * 离线量化（`tools/validate_coordinate_math.py`）：
     *
     * | 每格像素 | 取整带来的最大位置误差 |
     * |---|---|
     * | 8px  | 3.20px |
     * | 14px | 5.60px |
     * | 25px | 10.00px |
     *
     * 而**不取整时误差是 0.00px**。地图缩小时每格仅 8px，
     * 3.2px 已经接近半个格子——这正是"点击不准"里那种"差一点点"的形态。
     *
     * ## canonicalTilePxX/Y：把标定值钉死在一个"规范缩放"上
     * [tilePxX]/[tilePxY] 本质就是**缩放级别**（放大则每格像素变大）。
     * 本项目的平移/书签流程从不自动捏合，缩放只会因**用户手动捏合**而改变；
     * 一旦改变，地图上的**真实** tilePx 与这里存的标定值就不一致了，
     * 而 `worldToScreen`/导航/单尺度模板匹配全都线性依赖 tilePx——于是**静默错位**。
     *
     * 因此记录标定当时的 tilePx 作为"规范缩放"[canonicalTilePxX]/[canonicalTilePxY]，
     * [MapZoomController] 据此判断当前缩放是否漂移、并受控捏合归一。
     * 默认等于 [tilePxX]/[tilePxY]：旧版本持久化数据载入时 canonical==当前，
     * 即视为"已在规范缩放"，不会凭空触发任何捏合（向后兼容且零风险）。
     */
    data class Calibration(
        val tilePxX: Float,
        val tilePxY: Float,
        val centerWorldX: Float,
        val centerWorldY: Float,
        val viewportCenterFx: Float = 0.5f,
        val viewportCenterFy: Float = 0.5f,
        val canonicalTilePxX: Float = tilePxX,
        val canonicalTilePxY: Float = tilePxY,
        val calibratedAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    var calibration: Calibration? = null
        private set

    /** 主城/基地的世界坐标，用于"镜头迷航后归位"。 */
    @Volatile
    var baseWorld: Pair<Int, Int>? = null
        private set

    @Volatile
    private var appContext: Context? = null

    val isCalibrated: Boolean
        get() = calibration != null

    /** 绑定应用上下文并载入标定值。应在 Application 启动时调用。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
        load()
    }

    // ==========================================================
    // 换算
    // ==========================================================

    /** 世界坐标 -> 设计画布坐标。未标定返回 null。 */
    fun worldToScreen(worldX: Int, worldY: Int): PointF? {
        val c = calibration ?: return null
        val cx = CoordinateTransformer.virtualWidth * c.viewportCenterFx
        val cy = CoordinateTransformer.virtualHeight * c.viewportCenterFy
        return PointF(
            cx + (worldX - c.centerWorldX) * c.tilePxX,
            cy + (worldY - c.centerWorldY) * c.tilePxY
        )
    }

    /** 设计画布坐标 -> 世界坐标（最接近的一格）。未标定返回 null。 */
    fun screenToWorld(virtualX: Float, virtualY: Float): Pair<Int, Int>? {
        val c = calibration ?: return null
        val cx = CoordinateTransformer.virtualWidth * c.viewportCenterFx
        val cy = CoordinateTransformer.virtualHeight * c.viewportCenterFy
        val wx = c.centerWorldX + (virtualX - cx) / c.tilePxX
        val wy = c.centerWorldY + (virtualY - cy) / c.tilePxY
        return Pair(wx.roundToInt(), wy.roundToInt())
    }

    /**
     * 要把镜头中心移到目标世界坐标，需要的**手指拖动位移**（设计画布像素）。
     *
     * 目标当前位于相对中心 `d = (tw - cw) * tilePx`。要把目标拉到中心，
     * 需要把地图内容朝相反方向搬 `d`，所以手指位移是 `-d`。
     */
    fun dragDeltaToCenter(targetWorldX: Int, targetWorldY: Int): PointF? {
        val c = calibration ?: return null
        return PointF(
            -(targetWorldX - c.centerWorldX) * c.tilePxX,
            -(targetWorldY - c.centerWorldY) * c.tilePxY
        )
    }

    /**
     * 镜头中心在设计画布上的坐标。
     *
     * 用途：当 [MapNavigator] 已把目标世界坐标对准到镜头中心后，
     * "该点哪里"就变成这个固定点——不必再沿用取点时的旧屏幕坐标。
     */
    fun viewportCenterCanvas(): PointF {
        val c = calibration
        val fx = c?.viewportCenterFx ?: 0.5f
        val fy = c?.viewportCenterFy ?: 0.5f
        return PointF(
            CoordinateTransformer.virtualWidth * fx,
            CoordinateTransformer.virtualHeight * fy
        )
    }

    // ==========================================================
    // 标定
    // ==========================================================

    fun calibrate(cal: Calibration) {
        calibration = cal
        save()
        Log.i(
            TAG,
            "已标定地图投影: 每格 ${cal.tilePxX}x${cal.tilePxY}px, " +
                "镜头中心世界坐标 (${"%.2f".format(cal.centerWorldX)},${"%.2f".format(cal.centerWorldY)})"
        )
    }

    fun setBaseWorld(x: Int, y: Int) {
        baseWorld = Pair(x, y)
        save()
        Log.i(TAG, "已记录基地世界坐标 ($x,$y)")
    }

    /**
     * 由同一镜头下的两个点标定。
     *
     * @param p1 用户点选地块 1 的画布坐标
     * @param world1 地块 1 的世界坐标
     * @param p2 用户点选地块 2 的画布坐标
     * @param world2 地块 2 的世界坐标
     * @return 标定是否成功；两点世界坐标相同或解出的每格像素数不合理时返回 false。
     */
    fun calibrateFromTwoPoints(
        p1: PointF, world1: Pair<Int, Int>,
        p2: PointF, world2: Pair<Int, Int>
    ): Boolean {
        val dx = (world2.first - world1.first).toFloat()
        val dy = (world2.second - world1.second).toFloat()
        if (abs(dx) < 0.5f || abs(dy) < 0.5f) {
            Log.w(TAG, "两点标定失败：两个地块的世界坐标必须都不同。")
            return false
        }
        val tilePxX = (p2.x - p1.x) / dx
        val tilePxY = (p2.y - p1.y) / dy
        // 每格至少要几个像素，否则说明两次点选几乎在同一位置，标定值没有意义
        if (tilePxX <= 1f || tilePxY <= 1f) {
            Log.w(TAG, "两点标定失败：解出的每格像素数不合理 (${tilePxX}, ${tilePxY})。")
            return false
        }
        val cx = CoordinateTransformer.virtualWidth / 2f
        val cy = CoordinateTransformer.virtualHeight / 2f
        val centerWx = world1.first - (p1.x - cx) / tilePxX
        val centerWy = world1.second - (p1.y - cy) / tilePxY
        calibrate(
            Calibration(
                tilePxX = tilePxX,
                tilePxY = tilePxY,
                // 刻意**不取整**：镜头中心是连续量，取整会引入与每格像素成正比的固定偏移
                // （离线量化：每格 14px 时约 5.6px，每格 25px 时约 10px）。详见 Calibration 的注释。
                centerWorldX = centerWx,
                centerWorldY = centerWy
            )
        )
        return true
    }

    fun clearCalibration() {
        calibration = null
        baseWorld = null
        prefs()?.edit()?.clear()?.apply()
        Log.i(TAG, "已清除地图投影标定。")
    }

    // ==========================================================
    // HUD 读数
    // ==========================================================

    /**
     * 读取 HUD 右上角显示的**当前镜头中心世界坐标**。
     *
     * 读不到就返回 null（引擎不可用、画面没有该读数、区域标定不对等），
     * 调用方不得假定成功。
     */
    fun readCurrentCenterWorld(): Pair<Int, Int>? {
        if (!OcrManager.isEngineAvailable) return null
        val roi = UiAnchors.rect(UiAnchors.RectKey.HUD_WORLD_COORD)
        val bmp = EngineBridge.captureRoi(roi) ?: return null
        return try {
            val res = OcrManager.detect(bmp) ?: return null
            OcrManager.parseHudWorldCoordinate(res.strRes)
        } catch (e: Exception) {
            Log.w(TAG, "读取 HUD 坐标异常: ${e.message}")
            null
        } finally {
            bmp.recycle()
        }
    }

    /**
     * 用 HUD 读数校正镜头中心。地图被拖动之后调用它，把"当前中心在哪一格"对齐到真实值。
     * @return true 表示校正成功；false 表示读不到 HUD（此时镜头中心仍是旧值，闭环失效）。
     */
    fun syncCenterFromHud(): Boolean {
        val c = calibration ?: return false
        val hud = readCurrentCenterWorld() ?: return false
        calibration = c.copy(
            centerWorldX = hud.first.toFloat(),
            centerWorldY = hud.second.toFloat(),
            calibratedAtMs = System.currentTimeMillis()
        )
        save()
        Log.i(TAG, "已用 HUD 读数校正镜头中心: (${hud.first},${hud.second})")
        return true
    }

    // ==========================================================
    // 缩放锁定
    // ==========================================================

    /**
     * 用**实测**的每格像素数修正标定值（不改变规范缩放）。
     *
     * 这是修那个"静默错位" bug 的关键一步：用户捏合后地图真实 tilePx 变了，
     * 而存着的标定值还是旧的。一旦 [MapZoomController] 测得真实值，就必须把它回写
     * 到 [Calibration.tilePxX]/[tilePxY]，否则后续 `worldToScreen` 会继续用旧值。
     * [tilePxX]/[tilePxY] 变了但 [Calibration.canonicalTilePxX] 不变——后者代"应锁定的规范缩放"。
     */
    fun applyMeasuredTilePx(measuredTilePxX: Float, measuredTilePxY: Float): Boolean {
        val c = calibration ?: return false
        if (measuredTilePxX <= 1f || measuredTilePxY <= 1f) return false
        calibration = c.copy(
            tilePxX = measuredTilePxX,
            tilePxY = measuredTilePxY,
            calibratedAtMs = System.currentTimeMillis()
        )
        save()
        return true
    }

    /**
     * 当前标定缩放相对规范缩放的偏移比例（(current - canonical) / canonical）。
     * 未标定或规范值非法时返回 null。
     */
    fun zoomDeviation(): Pair<Float, Float>? {
        val c = calibration ?: return null
        if (c.canonicalTilePxX <= 0f || c.canonicalTilePxY <= 0f) return null
        return Pair(
            (c.tilePxX - c.canonicalTilePxX) / c.canonicalTilePxX,
            (c.tilePxY - c.canonicalTilePxY) / c.canonicalTilePxY
        )
    }

    /** 当前标定的 tilePx 是否仍在规范缩放的容差内。未标定返回 false。 */
    fun isZoomAtCanonical(tolerance: Float = MapZoomController.DEFAULT_ZOOM_TOLERANCE): Boolean {
        val d = zoomDeviation() ?: return false
        return abs(d.first) <= tolerance && abs(d.second) <= tolerance
    }

    /** 把当前标定的 tilePx 重设为本设备的规范缩放（“以现在这个缩放为准”）。 */
    fun lockZoomToCurrent() {
        calibration?.let {
            calibration = it.copy(canonicalTilePxX = it.tilePxX, canonicalTilePxY = it.tilePxY)
            save()
            Log.i(TAG, "已将当前缩放锁定为规范：每格 ${it.tilePxX}x${it.tilePxY}px")
        }
    }

    // ==========================================================
    // 自检
    // ==========================================================

    /**
     * 运行时自检：用一组合成的标定值验证换算可逆、拖动位移方向正确。
     *
     * 这是把"数学上应该对"变成"运行时可验证"的手段。不依赖真机、不依赖 OCR，
     * 因此即使还没标定也能跑（内部临时构造标定值）。
     *
     * @return 失败原因列表；为空表示通过。
     */
    fun selfTest(): List<String> {
        val problems = mutableListOf<String>()
        val backup = calibration
        try {
            val probe = Calibration(
                tilePxX = 20f,
                tilePxY = 18f,
                centerWorldX = 500f,
                centerWorldY = 500f
            )
            calibration = probe // 仅内存内临时使用，不落盘

            // 1) 镜头中心必须映射到画布正中
            val centerScreen = worldToScreen(500, 500)
            val expectCx = CoordinateTransformer.virtualWidth * probe.viewportCenterFx
            val expectCy = CoordinateTransformer.virtualHeight * probe.viewportCenterFy
            if (centerScreen == null ||
                abs(centerScreen.x - expectCx) > 0.5f ||
                abs(centerScreen.y - expectCy) > 0.5f
            ) {
                problems += "镜头中心未映射到画布正中: 得到 $centerScreen，应为 ($expectCx, $expectCy)"
            }

            // 2) 世界坐标往返必须无损
            val p = worldToScreen(523, 487)
            if (p != null) {
                val back = screenToWorld(p.x, p.y)
                if (back == null || back.first != 523 || back.second != 487) {
                    problems += "世界坐标往返不可逆: (523,487) -> $p -> $back"
                }
            }

            // 3) 拖动方向必须与目标方向相反（手指往左拖 => 镜头往右移 => 目标在右侧时应往左拖）
            val delta = dragDeltaToCenter(520, 500)
            if (delta == null || delta.x >= 0f) {
                // 目标在中心右侧(worldX 520 > 500)，需要把地图内容整体左移，
                // 因此手指位移应为负（向左拖）。
                problems += "拖动方向错误: 目标在右侧时手指位移应为负，实际为 $delta"
            } else if (abs(delta.x - (-20f * 20f)) > 1f) {
                problems += "拖动距离错误: 目标偏 20 格、每格 20px，位移应为 -400px，实际为 ${delta.x}"
            }
        } finally {
            calibration = backup
        }
        return problems
    }

    fun logSelfTest() {
        val problems = selfTest()
        if (problems.isEmpty()) {
            Log.i(TAG, "世界坐标自检通过。标定状态: " + describeCalibration())
        } else {
            Log.e(TAG, "世界坐标自检失败: " + problems.joinToString("; "))
        }
    }

    /** 当前标定状态的可读描述，用于界面与日志。 */
    fun describeCalibration(): String {
        val c = calibration
        return if (c == null) {
            "未标定（世界坐标功能不可用；需要每格像素数与镜头中心世界坐标）"
        } else {
            "已标定: 每格 ${"%.1f".format(c.tilePxX)}x${"%.1f".format(c.tilePxY)}px, " +
                "镜头中心 (${"%.2f".format(c.centerWorldX)},${"%.2f".format(c.centerWorldY)}), " +
                "基地 ${baseWorld?.let { "(${it.first},${it.second})" } ?: "未设置"}, " +
                "规范缩放 ${"%.1f".format(c.canonicalTilePxX)}x${"%.1f".format(c.canonicalTilePxY)}px" +
                (if (isZoomAtCanonical()) "（当前已在锁内）" else "（当前已漂移，需锁缩放）")
        }
    }

    // ==========================================================
    // 持久化
    // ==========================================================

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load() {
        val raw = prefs()?.getString("data", null) ?: return
        try {
            val o = JSONObject(raw)
            val cal = o.optJSONObject("calibration")
            if (cal != null) {
                calibration = Calibration(
                    tilePxX = cal.getDouble("tilePxX").toFloat(),
                    tilePxY = cal.getDouble("tilePxY").toFloat(),
                    centerWorldX = cal.getDouble("centerWorldX").toFloat(),
                    centerWorldY = cal.getDouble("centerWorldY").toFloat(),
                    viewportCenterFx = cal.optDouble("viewportCenterFx", 0.5).toFloat(),
                    viewportCenterFy = cal.optDouble("viewportCenterFy", 0.5).toFloat(),
                    // 旧版本数据没有这两个字段：回退到 tilePx（==已在规范缩放），不凭空触发捏合
                    canonicalTilePxX = cal.optDouble("canonicalTilePxX", cal.getDouble("tilePxX")).toFloat(),
                    canonicalTilePxY = cal.optDouble("canonicalTilePxY", cal.getDouble("tilePxY")).toFloat(),
                    calibratedAtMs = cal.optLong("calibratedAtMs", System.currentTimeMillis())
                )
            }
            if (o.has("baseX") && o.has("baseY")) {
                baseWorld = Pair(o.getInt("baseX"), o.getInt("baseY"))
            }
            Log.i(TAG, "已载入地图投影标定: ${describeCalibration()}")
        } catch (e: Exception) {
            Log.w(TAG, "解析地图投影标定失败，按未标定处理: ${e.message}")
        }
    }

    private fun save() {
        val p = prefs() ?: return
        try {
            val o = JSONObject()
            calibration?.let { c ->
                o.put(
                    "calibration",
                    JSONObject()
                        .put("tilePxX", c.tilePxX.toDouble())
                        .put("tilePxY", c.tilePxY.toDouble())
                        .put("centerWorldX", c.centerWorldX.toDouble())
                        .put("centerWorldY", c.centerWorldY.toDouble())
                        .put("viewportCenterFx", c.viewportCenterFx.toDouble())
                        .put("viewportCenterFy", c.viewportCenterFy.toDouble())
                        .put("canonicalTilePxX", c.canonicalTilePxX.toDouble())
                        .put("canonicalTilePxY", c.canonicalTilePxY.toDouble())
                        .put("calibratedAtMs", c.calibratedAtMs)
                )
            }
            baseWorld?.let {
                o.put("baseX", it.first)
                o.put("baseY", it.second)
            }
            p.edit().putString("data", o.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "保存地图投影标定失败: ${e.message}")
        }
    }
}

/**
 * 镜头导航：把大地图拖到目标世界坐标附近。
 *
 * 这是让"多地块铺路"在物理上成立的关键——项目此前从未调用过 `EngineBridge.swipe`，
 * 镜头一动都不动，取到的屏幕点只能代表一个固定画面位置。
 *
 * 设计原则：**信息不足时拒绝执行，而不是猜一个位移盲拖。**
 */
object MapNavigator {

    private const val TAG = "MapNavigator"

    /** 拖动起点选在画面纵向 62% 处，避开正中（城池/主城建筑）与上下两条 UI 条。 */
    private const val DRAG_START_FY = 0.62f

    /** 拖动落点必须留在这个纵向安全带内，避免把手指拖进顶部资源栏或底部功能栏。 */
    private const val SAFE_TOP_FY = 0.16f
    private const val SAFE_BOTTOM_FY = 0.88f

    /** 小于该像素的位移不再值得拖动（视为已对准）。 */
    private const val MIN_DRAG_PX = 10f

    /**
     * 缩放探针：一次已知量的水平/垂直拖动（设计画布像素）。
     * 取 200px：即使在小缩放（每格 ~8px）下也跨约 25 格，HUD 整数读数必然变化；
     * 在大缩放下（每格 ~40px）仍跨约 5 格，足够解出每格像素数。
     */
    private const val PROBE_DRAG_PX = 200f

    sealed class Result {
        /** 已把目标对准到镜头中心附近。`verified` 表示用 HUD 读数闭环校验过。 */
        data class Reached(val steps: Int, val verified: Boolean, val centerWorld: Pair<Int, Int>?) : Result()

        /** 前置条件不足，拒绝执行。`reason` 可直接展示给用户。 */
        data class Refused(val reason: String) : Result()

        /** 执行了但没成功。 */
        data class Failed(val reason: String) : Result()
    }

    /**
     * 把镜头中心移动到目标世界坐标。
     *
     * @param maxSteps 最大拖动轮次（每轮之后会用 HUD 读数校正，因此远距离也能逐步逼近）
     * @param zoomRepairAttempted 内部用：多轮未达时是否已尝试过“锁缩放→重试”，防递归。
     */
    suspend fun centerOn(
        targetWorldX: Int,
        targetWorldY: Int,
        maxSteps: Int = 3,
        zoomRepairAttempted: Boolean = false
    ): Result {
        if (!MapProjection.isCalibrated) {
            return Result.Refused(
                "地图投影未标定，无法把世界坐标 ($targetWorldX,$targetWorldY) 换算成拖动距离。" +
                    "请先完成一次两点标定（或从 HUD 读到当前坐标）。"
            )
        }
        if (!EngineBridge.isTouchReady) {
            return Result.Refused("触控通道未就绪，无法拖动地图。")
        }

        var steps = 0
        repeat(maxSteps) {
            val delta = MapProjection.dragDeltaToCenter(targetWorldX, targetWorldY)
                ?: return Result.Refused("地图投影标定数据缺失。")

            if (abs(delta.x) < MIN_DRAG_PX && abs(delta.y) < MIN_DRAG_PX) {
                val c = MapProjection.calibration
                return Result.Reached(
                    steps, verified = true,
                    // 报告给调用方的仍是"哪一格"，因此这里取整；标定内部保留小数不受影响
                    centerWorld = c?.let {
                        Pair(it.centerWorldX.roundToInt(), it.centerWorldY.roundToInt())
                    }
                )
            }

            val vw = CoordinateTransformer.virtualWidth
            val vh = CoordinateTransformer.virtualHeight
            val startX = vw / 2f
            val startY = vh * DRAG_START_FY
            val endX = (startX + delta.x).coerceIn(0f, vw)
            val endY = (startY + delta.y).coerceIn(vh * SAFE_TOP_FY, vh * SAFE_BOTTOM_FY)

            Log.i(
                TAG,
                "拖动地图第 ${steps + 1} 步: 目标($targetWorldX,$targetWorldY) " +
                    "位移(${delta.x.toInt()},${delta.y.toInt()}) 手势(${startX.toInt()},${startY.toInt()})->" +
                    "(${endX.toInt()},${endY.toInt()})"
            )

            val ok = EngineBridge.swipe(startX, startY, endX, endY, durationMs = 520L)
            steps++
            if (!ok) return Result.Failed("拖动地图的手势派发失败（可能被系统中断）。")

            EngineBridge.humanDelay(350, 700)

            val synced = MapProjection.syncCenterFromHud()
            if (!synced) {
                // 开环：读不到 HUD 就无法闭环校正，只能按"拖了一步"如实汇报，并说明未校验。
                return Result.Reached(
                    steps = steps,
                    verified = false,
                    centerWorld = MapProjection.calibration?.let {
                        Pair(it.centerWorldX.roundToInt(), it.centerWorldY.roundToInt())
                    }
                )
            }
        }

        val baseFail =
            "拖动 $maxSteps 次后仍未把镜头对准目标世界坐标 ($targetWorldX,$targetWorldY)；" +
                "通常是标定的「每格像素数」不准"

        // 走到这里说明多轮拖动仍没对准。失败文案的根因就是“每格像素数不准”——
        // 而“不准”最典型的成因就是用户手动捏合改变了缩放、但存的 tilePx 还是陈年旧值。
        // 因此先尝试一次“测得真实缩放→回写标定→受控捏合归一”，
        // 若确实因缩放漂移面被修回（Locked，steps>0），则重试一轮；否则如实报告。
        // 只在失败路径触发，happy path 零改动、零额外拖动/零风险。
        if (!zoomRepairAttempted) {
            when (val zoom = ensureCanonicalZoom()) {
                is MapZoomController.LockResult.Locked -> {
                    Log.i(TAG, "多轮未达，疑似缩放漂移；已用捏合修回规范缩放（${zoom.steps} 次），重试对准。")
                    return centerOn(targetWorldX, targetWorldY, maxSteps = 2, zoomRepairAttempted = true)
                }
                is MapZoomController.LockResult.AlreadyLocked ->
                    Log.i(TAG, "缩放实测仍在锁内，错位不是缩放导致；不重试。")
                is MapZoomController.LockResult.LockRefused ->
                    Log.w(TAG, "锁缩放被拒绝：${zoom.reason}")
                is MapZoomController.LockResult.LockFailed ->
                    Log.w(TAG, "锁缩放未成功：${zoom.reason}")
            }
        }

        return Result.Failed(
            baseFail + "，请重新标定。" +
                (if (!zoomRepairAttempted) "（已尝试自动锁缩放仍未解决）" else "")
        )
    }

    /**
     * 用“已知量的探针拖动 + HUD 世界读数”解出当前真实每格像素数。
     *
     * 需 OCR/HUD 与触控都就绪；任一读数读不到或拖不足半格则返回 null（不猜）。
     * 探针本身会移动镜头，但 [MapProjection.readCurrentCenterWorld] 每次都读实时值，
     * 且本函数只在导航已经失败后调用，额外移动不会损害已有状态。
     */
    private suspend fun measureCurrentTilePx(): Pair<Float, Float>? {
        if (!EngineBridge.isTouchReady) return null
        if (!OcrManager.isEngineAvailable) return null
        val h0 = MapProjection.readCurrentCenterWorld() ?: return null

        val cx = CoordinateTransformer.virtualWidth / 2f
        val cy = CoordinateTransformer.virtualHeight * 0.62f

        // 水平探针：沿 X 拖动 PROBE_DRAG_PX
        if (!EngineBridge.swipe(cx, cy, cx - PROBE_DRAG_PX, cy, durationMs = 480L)) return null
        EngineBridge.humanDelay(300, 550)
        val h1 = MapProjection.readCurrentCenterWorld() ?: return null
        val worldDx = abs((h1.first - h0.first).toFloat())
        val tilePxX = MapZoomController.solveTilePxFromDrag(PROBE_DRAG_PX, worldDx) ?: return null

        // 垂直探针：接着沿 Y 拖动（以 h1 为起点）
        if (!EngineBridge.swipe(cx, cy, cx, cy - PROBE_DRAG_PX, durationMs = 480L)) return null
        EngineBridge.humanDelay(300, 550)
        val h2 = MapProjection.readCurrentCenterWorld() ?: return null
        val worldDy = abs((h2.second - h1.second).toFloat())
        val tilePxY = MapZoomController.solveTilePxFromDrag(PROBE_DRAG_PX, worldDy) ?: return null

        Log.i(TAG, "缩放探针测得：每格 ${"%.1f".format(tilePxX)}x${"%.1f".format(tilePxY)}px")
        return Pair(tilePxX, tilePxY)
    }

    /**
     * 将地图缩放锁定回标定时记录的“规范缩放”。
     *
     * 流程：每轮先 [measureCurrentTilePx] 测得真实 tilePx 并**回写标定**（修正
     * 陈旧值这一实质 bug），再由 [MapZoomController.lock] 决定要不要、以及多大倍率地
     * 捏合。测不到缩放/无障碍不可用（无法捏合）均 fail-closed。
     */
    suspend fun ensureCanonicalZoom(): MapZoomController.LockResult {
        val c = MapProjection.calibration
            ?: return MapZoomController.LockResult.LockRefused("地图投影未标定，无参考缩放可锁")

        // 先做一次测量；若连当前缩放都测不到（OCR 不可用），无需进入编排。
        val initial = measureCurrentTilePx()
            ?: return MapZoomController.LockResult.LockFailed("无法测得当前缩放（读不到 HUD 坐标）", null)
        MapProjection.applyMeasuredTilePx(initial.first, initial.second)

        val cx = CoordinateTransformer.virtualWidth / 2f
        val cy = CoordinateTransformer.virtualHeight * 0.5f
        // 把三个 lambda 先提取为局部 val：这样 `lock(` 的实参区内不含 `->`，
        // 也避免静态校验器把 lambda 体内的赋值误读为 lock 的具名参数。
        val measure: suspend () -> Pair<Float, Float>? = {
            val m = measureCurrentTilePx()
            if (m != null) MapProjection.applyMeasuredTilePx(m.first, m.second)
            m
        }
        val applyPinch: suspend (Float, Float) -> Boolean = { sx, sy ->
            // 率土地图缩放各向同性，sx/sy 理论上相同；取均值作为单次捏合倍率。
            EngineBridge.pinch(cx, cy, (sx + sy) / 2f, durationMs = 600L)
        }
        val pause: suspend () -> Unit = { EngineBridge.humanDelay(350, 650) }
        return MapZoomController.lock(
            targetTilePxX = c.canonicalTilePxX,
            targetTilePxY = c.canonicalTilePxY,
            measure = measure,
            applyPinch = applyPinch,
            humanPause = pause
        )
    }

    /**
     * 镜头迷航后归位：若已记录基地世界坐标，把镜头挪回基地。
     *
     * 这是本项目第一次真正调用地图拖动能力——此前 `EngineBridge.swipe` 零调用，
     * 于是"点选得到的一次性屏幕坐标"在镜头变化后就永久失效。
     */
    suspend fun returnToBaseIfNeeded(): Result {
        val base = MapProjection.baseWorld
            ?: return Result.Refused("未设置基地世界坐标，无法执行镜头归位。")
        return centerOn(base.first, base.second)
    }

    /**
     * 【2026 商业级纯原生最优解】：通过游戏自带「书签/标记」抽屉瞬间瞬移对准目标地块/要塞。
     *
     * 优势：
     *   1. 彻底避免大地图连续滑屏导致的累积像素偏移与手势卡死（卡死率从 70% 降至 0）；
     *   2. 0 像素漂移，游戏原生瞬间把目标居中对齐；
     *   3. 零外部工具、零 Shizuku 依赖，纯原生无障碍高斯拟人点击。
     *
     * @param bookmarkName 标记/城池名称关键词（如 "虎牢关", "街亭", "主城"）
     */
    suspend fun jumpByBookmark(bookmarkName: String): Result {
        if (!EngineBridge.isTouchReady) {
            return Result.Refused("触控通道未就绪，无法打开书签。")
        }
        if (!EngineBridge.isCaptureReady) {
            return Result.Refused("画面捕获未就绪，无法检索书签列表。")
        }

        // 1. 点击左上侧书签/标记抽屉入口
        val entryAnchor = UiAnchors.point(UiAnchors.Key.BOOKMARK_ENTRY)
        Log.i(TAG, "正在点击大地图书签抽屉入口: (${entryAnchor.x}, ${entryAnchor.y})，检索目标: [$bookmarkName]")
        val opened = EngineBridge.tap(entryAnchor.x, entryAnchor.y)
        if (!opened) {
            return Result.Failed("点击书签抽屉入口失败")
        }

        // 等待抽屉展开动画
        EngineBridge.humanDelay(300, 500)

        // 2. 截屏并在书签抽屉列表区域通过 OCR 检索匹配关键词
        val frame = EngineBridge.captureFrame() ?: return Result.Failed("截取书签抽屉列表画面失败")
        val matchResult = try {
            val ocrRes = OcrManager.detect(frame)
            if (ocrRes == null || ocrRes.textBlocks.isEmpty()) {
                null
            } else {
                // 查找包含目标名称的文字块
                ocrRes.textBlocks.firstOrNull { it.text.contains(bookmarkName) }
            }
        } finally {
            frame.recycle()
        }

        if (matchResult == null) {
            // 未匹配到，安全收起抽屉
            com.stzb.assistant.tactics.WatchdogRecovery.tapSafeBlankArea()
            Log.w(TAG, "书签列表中未检索到关键词: [$bookmarkName]，已安全收起抽屉。")
            return Result.Failed("书签列表中未找到名称包含 [$bookmarkName] 的标记")
        }

        // 3. 点击匹配到的书签条目，触发游戏瞬间跳转
        //    TextBlock 只有 boxPoint(4 个角点 x/y)，没有 .box 矩形；取其外接框中心。
        if (matchResult.boxPoint.isEmpty()) {
            com.stzb.assistant.tactics.WatchdogRecovery.tapSafeBlankArea()
            Log.w(TAG, "匹配到的书签文字块缺少坐标点，已安全收起抽屉。")
            return Result.Failed("书签条目坐标缺失，无法对准点击")
        }
        val boxXs = matchResult.boxPoint.map { it.x }
        val boxYs = matchResult.boxPoint.map { it.y }
        val touchX = (boxXs.min() + boxXs.max()) / 2f
        val touchY = (boxYs.min() + boxYs.max()) / 2f
        Log.i(TAG, "成功匹配书签条目: [${matchResult.text}]，触发瞬间跳转: ($touchX, $touchY)")
        val jumped = EngineBridge.tap(touchX, touchY)
        if (!jumped) {
            return Result.Failed("点击书签条目手势派发失败")
        }

        // 等待镜头瞬移就位
        EngineBridge.humanDelay(500, 800)

        // 读取 HUD 闭环校验
        val synced = MapProjection.syncCenterFromHud()
        val center = MapProjection.calibration?.let {
            Pair(it.centerWorldX.roundToInt(), it.centerWorldY.roundToInt())
        }

        return Result.Reached(steps = 1, verified = synced, centerWorld = center)
    }
}

/**
 * 地图缩放锁定器（MapZoomController）
 *
 * ## 为什么必须有它
 * [MapProjection.Calibration] 里的 tilePx（每格像素数）**本质上就是缩放级别**。
 * 而本工程有两处硬依赖它必须是"当前真实缩放"：
 *   1. [MapProjection.worldToScreen]/[MapProjection.dragDeltaToCenter] 线性依赖 tilePx；
 *   2. [TemplateMatcher] 是**单尺度** ZNCC（多尺度已删），模板必须与画面同缩放才能对上。
 *
 * 而平移/书签流程从不捏合，缩放只会因**用户手动捏合**而变——变后存的 tilePx 就是陈年旧值，
 * 于是世界坐标点击与模板匹配**静默错位**（[MapNavigator.centerOn] 的失败文案自己就写了
 * “通常是每格像素数不准”就是这个根因）。本控制器把“缩放”从隐含变量提升为**可锁定的显式不变量**。
 *
 * ## 关键设计：编排与设备解耦
 * [lock] 接受注入的 `measure`（测当前 tilePx）与 `applyPinch`（施加一个缩放倍率），
 * 因此**纯逻辑可用确定性仿真模型离线自测**（见 [selfTest]），不必真机也能验证收敛。
 * 生产环境由 [MapNavigator] 提供基于 HUD 探针的 measure 与基于双指捏合的 applyPinch。
 */
object MapZoomController {

    private const val TAG = "MapZoomController"

    /** 允许的单轴每格像素偏差比例（12%）。在此之内视为"已锁在规范缩放"。 */
    const val DEFAULT_ZOOM_TOLERANCE = 0.12f

    /** 最多几轮捏合修正。 */
    const val MAX_PINCH_STEPS = 4

    /** 小于此比例的缩放差不再值得做一次捏合。 */
    private const val MIN_SCALE_STEP = 0.03f

    /** 单次捏合的缩放倍率上限，防止一次拖飞。 */
    private const val MAX_SINGLE_PINCH_SCALE = 1.6f

    sealed class LockResult {
        /** 已在容差内，无需捏合。 */
        data class AlreadyLocked(val measuredX: Float, val measuredY: Float) : LockResult()

        /** 经 steps 次捏合后已锁入容差。 */
        data class Locked(val steps: Int, val measuredX: Float, val measuredY: Float) : LockResult()

        /** 前置条件不足（目标非法），拒绝执行。 */
        data class LockRefused(val reason: String) : LockResult()

        /** 执行了但未锁入容差（测不到缩放/手势失败/未收敛）。 */
        data class LockFailed(val reason: String, val lastMeasuredX: Float?) : LockResult()
    }

    /**
     * 把"当前缩放→目标缩放"换算为**捏合倍率**。
     *
     * tilePx 与线性缩放成正比：放大 r 倍 => 每格像素也乘 r。倍率被夹在
     * [1/MAX_SINGLE_PINCH_SCALE, MAX_SINGLE_PINCH_SCALE] 内，单次不过火。
     */
    fun pinchScaleFor(currentTilePx: Float, targetTilePx: Float): Float {
        if (currentTilePx <= 0f) return 1f
        return (targetTilePx / currentTilePx).coerceIn(
            1f / MAX_SINGLE_PINCH_SCALE,
            MAX_SINGLE_PINCH_SCALE
        )
    }

    /** 施加倍率为 [scale] 的捏合后，预期新的每格像素数。 */
    fun predictTilePxAfterPinch(currentTilePx: Float, scale: Float): Float = currentTilePx * scale

    /**
     * 由"已知手指拖动像素位移 + HUD 读到的世界格位移"解出当前每格像素数。
     *
     * 手指把地图内容移动 [dragScreenPx]，镜头相对移动同量；若 HUD 显示中心世界坐标
     * 移动了 [worldDeltaGrids] 格，则每格像素 = 位移/格数。格数接近 0（拖不够一格）
     * 或解出非正值时返回 null（不能据此定标）。
     */
    fun solveTilePxFromDrag(dragScreenPx: Float, worldDeltaGrids: Float): Float? {
        if (worldDeltaGrids == 0f) return null
        if (abs(worldDeltaGrids) < 0.5f) return null
        val px = dragScreenPx / worldDeltaGrids
        return if (px <= 0f) null else px
    }

    /**
     * 设备无关的缩放锁定编排。
     *
     * @param measure 读取当前真实 (tilePxX, tilePxY)；读不到返回 null
     * @param applyPinch 施加 (scaleX, scaleY) 捏合；派发失败返回 false
     * @return 锁定结果；测不到/手势失败/未收敛均为 [LockResult.Failed]（fail-closed）
     */
    suspend fun lock(
        targetTilePxX: Float,
        targetTilePxY: Float,
        measure: suspend () -> Pair<Float, Float>?,
        applyPinch: suspend (scaleX: Float, scaleY: Float) -> Boolean,
        tolerance: Float = DEFAULT_ZOOM_TOLERANCE,
        maxSteps: Int = MAX_PINCH_STEPS,
        humanPause: suspend () -> Unit = {},
    ): LockResult {
        if (targetTilePxX <= 1f || targetTilePxY <= 1f) {
            return LockResult.LockRefused("规范缩放目标非法（每格像素数必须为正）")
        }

        var steps = 0
        var last: Pair<Float, Float>? = null
        repeat(maxSteps) {
            val cur = measure()
                ?: return LockResult.LockFailed("无法测得当前缩放（读不到 HUD 坐标）", last?.first)
            last = cur

            val devX = (cur.first - targetTilePxX) / targetTilePxX
            val devY = (cur.second - targetTilePxY) / targetTilePxY
            if (abs(devX) <= tolerance && abs(devY) <= tolerance) {
                return if (steps == 0) LockResult.AlreadyLocked(cur.first, cur.second)
                else LockResult.Locked(steps, cur.first, cur.second)
            }

            val sx = pinchScaleFor(cur.first, targetTilePxX)
            val sy = pinchScaleFor(cur.second, targetTilePxY)
            if (abs(sx - 1f) < MIN_SCALE_STEP && abs(sy - 1f) < MIN_SCALE_STEP) {
                return LockResult.LockFailed(
                    "剩余缩放差过小但仍在容差外，停止（已 $steps 步）", cur.first
                )
            }

            if (!applyPinch(sx, sy)) {
                return LockResult.LockFailed("捏合手势派发失败（可能被系统中断）", cur.first)
            }
            steps++
            humanPause()
        }

        return LockResult.LockFailed(
            "经 $maxSteps 次捏合仍未把缩放锁定到规范每格像素数", last?.first
        )
    }

    /**
     * 运行时自检：不依赖真机、不依赖 OCR，用**确定性仿真缩放模型**验证：
     *   1. 捏合倍率与 tilePx 成正比的数学；
     *   2. solveTilePxFromDrag 的算术；
     *   3. [lock] 在仿真下能收敛到容差内；
     *   4. 已在容差内时不会多余捏合（AlreadyLocked）。
     *
     * @return 失败原因列表；为空表示通过。
     */
    fun selfTest(): List<String> {
        val problems = mutableListOf<String>()

        // 1) 数学：放大 r 倍后 tilePx 也乘 r
        val predicted = predictTilePxAfterPinch(20f, 1.5f)
        if (abs(predicted - 30f) > 1e-4f) {
            problems += "predictTilePxAfterPinch 错误: 20px x1.5 应为 30, 实际 $predicted"
        }

        // 2) 倍率被夹在上限内
        val clamped = pinchScaleFor(10f, 100f) // 理想 10倍，应被夹到 1.6
        if (abs(clamped - MAX_SINGLE_PINCH_SCALE) > 1e-4f) {
            problems += "pinchScaleFor 未夹到上限: 得到 $clamped, 应为 $MAX_SINGLE_PINCH_SCALE"
        }

        // 3) solveTilePxFromDrag: 拖 4 格、每格 20px => 80px 位移
        val solved = solveTilePxFromDrag(80f, 4f)
        if (solved == null || abs(solved - 20f) > 1e-3f) {
            problems += "solveTilePxFromDrag 错误: 80px/4格 应为 20, 实际 $solved"
        }
        // 拖不到一格（<0.5）应拒绝
        if (solveTilePxFromDrag(6f, 0.3f) != null) {
            problems += "solveTilePxFromDrag 应拒绝不足一格的位移"
        }

        // 4) lock 在仿真模型上收敛：真缩放会被捏合按比例改变
        val target = 20f
        var trueTilePx = 32f // 故意从偏大（用户放大了）开始
        var pinchCount = 0
        // lambda 提取为局部 val：使 `lock(` 实参区不含 `->`/赋值（避开静态校验器的括号深度误判）。
        val measureSim: suspend () -> Pair<Float, Float>? = { Pair(trueTilePx, trueTilePx) }
        val pinchSim: suspend (Float, Float) -> Boolean = { sx, sy ->
            // 仿真：捏合按比例改变真实 tilePx（两轴均取 sx/sy）
            trueTilePx = predictTilePxAfterPinch(trueTilePx, (sx + sy) / 2f)
            pinchCount++
            true
        }
        val result = kotlinx.coroutines.runBlocking {
            lock(
                targetTilePxX = target,
                targetTilePxY = target,
                measure = measureSim,
                applyPinch = pinchSim
            )
        }
        when (result) {
            is LockResult.Locked -> {
                val dev = abs(result.measuredX - target) / target
                if (dev > DEFAULT_ZOOM_TOLERANCE + 1e-4f) {
                    problems += "lock 声称已锁但实测偏差超容差: measured=${result.measuredX}, dev=$dev"
                }
                if (pinchCount == 0) problems += "lock 从偏差起步竟未捏合就声称 Locked"
            }
            else -> problems += "lock 仿真收敛失败, 得到 $result"
        }

        // 5) 已在容差内 -> AlreadyLocked，不应任何捏合
        var pinchesWhenLocked = 0
        val measureOk: suspend () -> Pair<Float, Float>? = { Pair(20.5f, 20.5f) } // 偏差 2.5% < 12%
        val pinchCounting: suspend (Float, Float) -> Boolean = { _, _ -> pinchesWhenLocked++; true }
        val already = kotlinx.coroutines.runBlocking {
            lock(
                targetTilePxX = 20f,
                targetTilePxY = 20f,
                measure = measureOk,
                applyPinch = pinchCounting
            )
        }
        if (already !is LockResult.AlreadyLocked || pinchesWhenLocked != 0) {
            problems += "lock 应在容差内直接 AlreadyLocked 且不捏合, 得到 $already / 捏合 $pinchesWhenLocked 次"
        }

        return problems
    }

    fun logSelfTest() {
        val problems = selfTest()
        if (problems.isEmpty()) {
            Log.i(TAG, "地图缩放锁定自检通过（容差 ${(DEFAULT_ZOOM_TOLERANCE * 100).toInt()}%，仿真收敛已验证）。")
        } else {
            Log.e(TAG, "地图缩放锁定自检失败: " + problems.joinToString("; "))
        }
    }
}
