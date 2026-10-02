package com.stzb.assistant.service

import android.graphics.Path
import android.graphics.PointF
import com.stzb.assistant.antiban.KineticTouchEngine
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 拟人化三次贝塞尔轨迹算法生成器 (Cubic Bézier Curve)
 * 作用：模拟真实人类手指在触摸屏上滑动的弧度、加速度与微颤，
 * 彻底消除机械直线移动痕迹，绕过游戏厂商基于滑动特征的行为风控检测。
 */
object BezierTrajectory {

    /**
     * 生成一条平滑拟人的贝塞尔滑动 Path (集成动力学惯性过冲与微回弹)
     */
    fun createHumanPath(p0: PointF, p3: PointF): Path {
        return KineticTouchEngine.createInertialSwipePath(p0, p3)
    }

    /**
     * 生成非线性人体加速时间分布 (Ease-in-out)
     * 保证起点启动加速、中段匀速、终点减速入位
     */
    fun easeInOut(t: Float): Float {
        return if (t < 0.5f) {
            2f * t * t
        } else {
            -1f + (4f - 2f * t) * t
        }
    }
}
