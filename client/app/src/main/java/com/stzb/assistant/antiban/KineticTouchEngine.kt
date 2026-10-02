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
}
