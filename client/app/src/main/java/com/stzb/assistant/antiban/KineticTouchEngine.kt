package com.stzb.assistant.antiban

import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 拟人化物理触控与惯性动力学引擎 (KineticTouchEngine)
 * 
 * 核心痛点解决：
 *   1. 彻底抹杀“绝对像素坐标点击”风控特征（采用 2D 双变量高斯径向分布抖动）；
 *   2. 彻底抹杀“固定 50ms 按下时长”特征（模拟真实人体指腹接触弹性与肉垫接触时间 70~175ms）；
 *   3. 模拟【人体手臂惯性滑动过冲与微回弹 (Inertial Overshoot & Rebound)】：
 *      手势滑动拖拽地图时，手指由于动量会自然轻微滑过目标点 5~15px，并在抬起瞬间做微弱惯性减速回弹；
 *   4. 三次贝塞尔曲线随机偏折法向扰动，生成独一无二的人类手势轨迹。
 */
object KineticTouchEngine {

    private const val TAG = "KineticTouchEngine"

    /**
     * 生成符合指腹接触物理特性的按压时长 (Touch Down -> Touch Up)
     * 均值 115ms，标准差 22ms，硬性截断在 [68ms, 178ms]
     */
    fun generateContactDuration(): Long {
        val u1 = maxOf(1e-7, Random.nextDouble())
        val u2 = Random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)

        val duration = 115.0 + z * 22.0
        val clamped = duration.coerceIn(68.0, 178.0).toLong()
        return clamped
    }

    /**
     * 生成 2D 双变量高斯离散点击坐标
     * @param targetX 理论目标 X
     * @param targetY 理论目标 Y
     * @param maxRadius 最大允许偏移半径 (像素，默认 7px)
     */
    fun generateJitteredPoint(targetX: Float, targetY: Float, maxRadius: Float = 7f): PointF {
        // 极坐标变换生成双变量正态分布
        val u1 = maxOf(1e-7, Random.nextDouble())
        val u2 = Random.nextDouble()
        val r = (sqrt(-2.0 * ln(u1)) * (maxRadius * 0.45)).coerceAtMost(maxRadius.toDouble())
        val theta = 2.0 * Math.PI * u2

        val offsetX = (r * cos(theta)).toFloat()
        val offsetY = (r * sin(theta)).toFloat()

        return PointF(targetX + offsetX, targetY + offsetY)
    }

    /**
     * 生成包含【惯性过冲与微回弹】的高仿真人类滑动轨迹 (Cubic Bézier + Inertial Rebound)
     * @param start 起始物理坐标
     * @param end 终止物理坐标
     * @return 注入了过冲与自然法向弧度的平滑 Path
     */
    fun createInertialSwipePath(start: PointF, end: PointF): Path {
        val path = Path()
        path.moveTo(start.x, start.y)

        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        if (distance < 12f) {
            path.lineTo(end.x, end.y)
            return path
        }

        // 1. 单位方向向量与法向向量
        val unitX = dx / distance
        val unitY = dy / distance
        val normalX = -unitY
        val normalY = unitX

        // 2. 模拟手臂天然侧向弯曲弧度 (随机偏左或偏右，距离越长偏离越明显，最大 35px)
        val curvature = minOf(35f, distance * 0.15f) * if (Random.nextBoolean()) 1f else -1f
        val arcJitter1 = curvature * (0.8f + Random.nextFloat() * 0.4f)
        val arcJitter2 = curvature * (0.6f + Random.nextFloat() * 0.4f)

        // 3. 计算【惯性过冲点 P_overshoot】：滑行终点前沿方向冲出 4% ~ 8% 距离 (最大 25px)
        val overshootDist = minOf(22f, distance * (0.04f + Random.nextFloat() * 0.04f))
        val pOverX = end.x + unitX * overshootDist
        val pOverY = end.y + unitY * overshootDist

        // 4. 第一段控制点与过冲控制点
        val p1X = start.x + dx * 0.32f + normalX * arcJitter1
        val p1Y = start.y + dy * 0.32f + normalY * arcJitter1

        val p2X = start.x + dx * 0.78f + normalX * arcJitter2
        val p2Y = start.y + dy * 0.78f + normalY * arcJitter2

        // 5. 三次贝塞尔冲向过冲点
        path.cubicTo(p1X, p1Y, p2X, p2Y, pOverX, pOverY)

        // 6. 微回弹：由过冲点缓和归位至最终坐标 end (模拟手腕制动微颤)
        val reboundCtrlX = pOverX - unitX * (overshootDist * 0.3f) + normalX * (Random.nextFloat() * 2f - 1f)
        val reboundCtrlY = pOverY - unitY * (overshootDist * 0.3f) + normalY * (Random.nextFloat() * 2f - 1f)
        path.quadTo(reboundCtrlX, reboundCtrlY, end.x, end.y)

        Log.d(TAG, "生成惯性过冲滑动轨迹: 距离=${distance.toInt()}px, 过冲量=${"%.1f".format(overshootDist)}px")
        return path
    }

    /**
     * 规划一条带**非均匀速度剖面**的滑动（拆成多条连续 stroke）。
     *
     * 为什么不能只用上面的 [createInertialSwipePath]：
     * `StrokeDescription(path, startTime, duration)` 对 path 是**弧长均匀 + 时间线性**
     * 采样的，于是整条滑动虽然是弯的、却是**恒速**的——恒速本身就是最强的机器特征。
     * 本方法把几何交给 [TrajectoryPlanner]，得到若干段时长满钟形剖面的连续子手势。
     *
     * @param bounds 物理像素可用区；过冲与弧度都会被夹在里面，避免坐标越界被系统丢手势
     */
    fun planInertialSwipe(
        start: PointF,
        end: PointF,
        totalMs: Long,
        bounds: TrajectoryPlanner.Bounds
    ): List<TrajectoryPlanner.StrokeSegment> =
        TrajectoryPlanner.plan(start, end, totalMs, bounds, random = Random)

    /** 把采样点串成框架 Path（子段采样点足够密，交系统线性采样也不会出棱角）。 */
    fun toPath(points: List<PointF>): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }

    /**
     * 运行期自检：不依赖真机、不依赖 OCR，纯数学闭环。
     *
     * 验证的是“拟人参数是否真的在区间内”与“多段轨迹是否真的既连续又发钟形”，
     * 这两类一旦失效都是**静默**的（看上去一切正常，但特征已经变成机器）。
     *
     * @return 失败原因列表；为空表示通过
     */
    fun selfTest(): List<String> {
        val problems = mutableListOf<String>()

        // 1) 轨迹规划器几何与时序不变量
        problems += TrajectoryPlanner.selfTest().map { "轨迹: $it" }

        // 2) 按压时长必须落在声明的 [68,178]ms 里，且不是常数
        run {
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            var outOfRange = 0
            repeat(4000) {
                val d = generateContactDuration()
                if (d < 68L || d > 178L) outOfRange++
                if (d < min) min = d
                if (d > max) max = d
            }
            if (outOfRange > 0) problems += "按压时长越界 $outOfRange/4000 次（声明区间 [68,178]）"
            if (max - min < 40L) problems += "按压时长几乎恒定: [$min,$max]，指腹弹性特征已丢失"
        }

        // 3) 拖动偏差不应超出声明半径
        run {
            var exceeded = 0
            repeat(4000) {
                val p = generateJitteredPoint(400f, 300f, 7f)
                if (hypot((p.x - 400f).toDouble(), (p.y - 300f).toDouble()) > 7.001) exceeded++
            }
            if (exceeded > 0) problems += "拖动半径超出声明值 $exceeded/4000 次（maxRadius=7px）"
        }

        // 4) 多点轨迹确实被拆开了，且首尾对齐
        run {
            val bounds = TrajectoryPlanner.Bounds(0f, 0f, 1280f, 720f)
            val s = PointF(640f, 500f)
            val e = PointF(640f, 200f)
            val plan = TrajectoryPlanner.plan(s, e, 500L, bounds, random = Random(4242))
            if (plan.size < 2) problems += "长滑动未拆成多段: ${plan.size}"
            val toPathFirst = plan.first().points.first()
            if (hypot((toPathFirst.x - s.x).toDouble(), (toPathFirst.y - s.y).toDouble()) > 0.6f) {
                problems += "轨迹起点未对齐请求起点"
            }
            val path = toPath(plan.last().points)
            if (path.isEmpty) problems += "末段转 Path 为空"
        }

        return problems
    }

    /**
     * 启动期自检并落日志（与 `CoordinateTransformer` / `MapZoomController.logSelfTest()`
     * 同一约定：失效必须一开机就写进日志）。
     */
    fun logSelfTest() {
        val problems = selfTest()
        if (problems.isEmpty()) {
            Log.i(TAG, "拟人触控自检通过：多段变速轨迹既连续又真的非恒速，按压时长/抖动半径均在声明契约内。")
        } else {
            Log.e(TAG, "拟人触控自检失败: " + problems.joinToString("; "))
        }
    }
}
