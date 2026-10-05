package com.stzb.assistant.antiban

import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import kotlinx.coroutines.delay

/**
 * 商业级防封总控中枢 (AntiBanCoordinator)
 * 
 * 统一调度：
 *   1. 【时间维度】：外高斯非对称延迟注入、疲劳度渐进叠加、昼夜节律调控、生理微歇断点；
 *   2. 【空间维度】：2D 双变量高斯微抖、真实指腹接触时长、手臂惯性滑动过冲与回弹；
 *   3. 【环境维度】：Shizuku 零无障碍痕迹运行、C++ 原生反挂钩巡检。
 */
object AntiBanCoordinator {

    private const val TAG = "AntiBanCoordinator"

    /**
     * 在任何真实交互点击前，注入**有界**拟人行为延迟。
     *
     * ## 这里刻意**不再**内嵌“生理微歇”（这是个真实缺陷的修复）
     * 旧实现在每一次动作延迟里都先查一次微歇，于是：
     *
     * ```
     * injectActionDelay(350, (700-350)/3)   // 调用方读到的是“最多 0.7 秒”
     * ```
     *
     * 而微歇一次就是 **120~330 秒**。更糟的是它落在**动作之间**：夜哨的
     * “点编队页签 → 点撤退”之间、扫荡的“唤起轮盘 → 点扫荡”之间，一旦被塞进
     * 三分钟的停顿，游戏里这几秒的战术窗口就彻底错过了——用户的部队会白死。
     * 那不是拟人，是自伤。
     *
     * 现在微歇只由 [maybeTakeMicroBreak] 在**整轮之间的安全缝**里显式发起
     * （见 `SquadLevelingFlow` 的轮末），那里停几分钟不影响任何时序契约。
     *
     * @return 实际停顿毫秒数（调用方无需使用，仅便于日志/自检取证）
     */
    suspend fun injectActionDelay(minMs: Long = 1200L, maxMs: Long = 2500L): Long {
        // 先登记“刚刚有真实操作”：疲劳与微歇只按**连续操作时长**累积，
        // 闲置够久则归零（详见 TimingFingerprintEngine.noteHumanAction）。
        TimingFingerprintEngine.noteHumanAction()
        val delayMs = TimingFingerprintEngine.generateBoundedDelayMs(minMs, maxMs)
        delay(delayMs)
        return delayMs
    }

    /**
     * 到点了就歇一会儿，没到点立刻返回 0。
     *
     * 只在**长周期挂机循环的轮与轮之间**调用；不要在弹窗流程、压秒、
     * 敌袭警报这类有时序契约的路径上调用（见 [injectActionDelay] 的说明）。
     *
     * 停顿按 5 秒切片，切片之间询问 [shouldContinue]：用户按下停止时
     * 最长 5 秒就能退出，而不是让“下线休息”变成“关不掉的三分钟卡顿”。
     *
     * @param shouldContinue 返回 false 表示应当立即结束休息（如已请求停止）
     * @return 实际休息毫秒数；0 表示本轮未到微歇时点
     */
    suspend fun maybeTakeMicroBreak(shouldContinue: () -> Boolean = { true }): Long {
        if (!TimingFingerprintEngine.shouldTakeMicroBreak()) return 0L
        val budget = TimingFingerprintEngine.consumeMicroBreak()
        Log.i(TAG, "☕【防封风控保护】模拟真实玩家短暂离开，计划休息 ${budget / 1000} 秒...")
        var slept = 0L
        while (slept < budget && shouldContinue()) {
            val slice = minOf(5000L, budget - slept)
            delay(slice)
            slept += slice
        }
        if (slept < budget) {
            Log.i(TAG, "⏹️ 微歇被提前结束（已休息 ${slept / 1000} 秒/${budget / 1000} 秒）。")
        }
        return slept
    }

    /**
     * 生成抗风控 2D 高斯微抖坐标
     */
    fun randomizePoint(x: Float, y: Float, maxJitter: Float = 6f): PointF {
        return KineticTouchEngine.generateJitteredPoint(x, y, maxJitter)
    }

    /**
     * 获取拟人按压触碰时长 (ms)
     */
    fun getTouchContactDuration(): Long {
        return KineticTouchEngine.generateContactDuration()
    }

    /**
     * 生成惯性过冲平滑滑动轨迹
     */
    fun createSwipePath(start: PointF, end: PointF): Path {
        return KineticTouchEngine.createInertialSwipePath(start, end)
    }

    /**
     * 启动运行环境防封健康体检
     */
    fun checkHealth(context: Context): StealthEnvironmentManager.StealthAuditReport {
        return StealthEnvironmentManager.performAudit(context)
    }
}
