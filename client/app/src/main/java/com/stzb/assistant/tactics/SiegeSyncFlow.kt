package com.stzb.assistant.tactics

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.delay
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 同盟集火攻城毫秒卡秒排队器 (SiegeSyncFlow)
 * 
 * 核心痛点解决：
 *   1. 解决同盟每晚集火攻城人工看表手抖、主力与拆迁队撞车团灭的致命痛点；
 *   2. 精准实现【主力 21:00:00.000 触敌垫刀，拆迁队 21:00:01.000 跟刀破城皮】；
 *   3. 自动支持【步兵拆迁队速度慢、需提前 10 分钟先走；主力骑兵速度快、后走】的智能时序反向排序排队；
 *   4. 全流程队列调度，到点自动唤醒、选队、毫秒级击发。
 */
class SiegeSyncFlow(
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    data class ScheduledDispatch(
        val roleName: String,               // "主力战法队" 或 "拆迁攻城队"
        val troopSlot: Int,                 // 部队槽位编号 (1 ~ 5)
        val targetHitEpochMs: Long,         // 期望触敌时刻 (例如 21:00:00 或 21:00:01)
        var marchDurationSec: Long = 0L,    // 行军单程耗时
        var optimalDispatchEpochMs: Long = 0L // 算出的最终毫秒出征触发点
    ) : Comparable<ScheduledDispatch> {
        override fun compareTo(other: ScheduledDispatch): Int {
            return this.optimalDispatchEpochMs.compareTo(other.optimalDispatchEpochMs)
        }
    }

    data class SiegeConfig(
        val cityVirtualCoord: PointF,       // 目标城池/要塞在屏幕上的虚拟坐标
        val targetBaseHitEpochMs: Long,     // 主力统一触敌基准时刻 (毫秒戳，如 21:00:00.000)
        val mainSquadSlot: Int = 1,         // 主力队槽位 (21:00:00.000 触敌)
        val demolitionSlots: List<Int> = listOf(2, 3), // 拆迁队槽位列表 (依次 21:00:01, 21:00:02 触敌)
        val latencyCompensationMs: Long = 100L
    )

    fun stop() {
        isRunning.set(false)
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.SIEGE_SYNC,
            level = "WARN",
            message = "⏹️ 用户已手动终止攻城排队流。"
        ))
    }

    /**
     * 启动同盟集火攻城全自动卡秒排队流水线
     */
    suspend fun execute(config: SiegeConfig): Boolean {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "攻城卡秒排队流已在运行中。")
            return false
        }

        listener?.onStatusChanged(
            TacticalState.TaskType.SIEGE_SYNC,
            TacticalState.Status.RUNNING,
            "开始测算同盟集火卡秒时序，主力目标触敌时刻: ${config.targetBaseHitEpochMs}"
        )

        try {
            // 1. 确保大地图主界面就绪
            WatchdogRecovery.recoverToMainMap()

            // 2. 建立各梯队任务列表
            val planQueue = PriorityQueue<ScheduledDispatch>()

            // 注册主力队任务 (目标 21:00:00.000)
            planQueue.add(
                ScheduledDispatch(
                    roleName = "【主力第一队】",
                    troopSlot = config.mainSquadSlot,
                    targetHitEpochMs = config.targetBaseHitEpochMs
                )
            )

            // 注册拆迁队任务 (主力触敌后 +1s, +2s 跟刀)
            config.demolitionSlots.forEachIndexed { idx, slot ->
                planQueue.add(
                    ScheduledDispatch(
                        roleName = "【拆迁第 ${idx + 1} 队】",
                        troopSlot = slot,
                        targetHitEpochMs = config.targetBaseHitEpochMs + (idx + 1) * 1000L
                    )
                )
            }

            // 3. 逐队测算行军耗时并计算出征触发时刻
            logTactic("📊 正在进入出征面板，对主力与各拆迁队进行行军耗时预检与测算...")
            val readyDispatches = measureAllDispatches(config, planQueue)
            if (readyDispatches.isEmpty()) {
                logWarn("未能完成任何队伍的耗时测算，攻城排队中止。")
                WatchdogRecovery.recoverToMainMap()
                return false
            }

            // 4. 输出最终测算时序表
            val sb = StringBuilder("📋【同盟集火精密攻城排队时刻表】\n")
            readyDispatches.forEach { d ->
                val waitSec = (d.optimalDispatchEpochMs - System.currentTimeMillis()) / 1000
                sb.append("  • ${d.roleName} (部队${d.troopSlot}): 行军耗时 ${d.marchDurationSec}s | 出征触发点: ${d.optimalDispatchEpochMs} (倒计时: ${waitSec}s) | 触敌时刻: ${d.targetHitEpochMs}\n")
            }
            logTactic(sb.toString())

            // 5. 按照 optimalDispatchEpochMs 顺序逐一执行出征
            while (isRunning.get() && readyDispatches.isNotEmpty()) {
                val currentTask = readyDispatches.poll() ?: break

                val now = System.currentTimeMillis()
                val waitMs = currentTask.optimalDispatchEpochMs - now

                logInfo("⏳ 队列当前执行: ${currentTask.roleName}，距离出征触发还有 ${(waitMs / 1000)} 秒...")

                listener?.onStatusChanged(
                    TacticalState.TaskType.SIEGE_SYNC,
                    TacticalState.Status.WAITING_COUNTDOWN,
                    "等待 ${currentTask.roleName} 出征，还剩 ${(waitMs / 1000)} 秒"
                )

                // 提前 4 秒打开出征面板做好准备
                if (waitMs > 4000) {
                    delay(waitMs - 4000)
                }

                if (!isRunning.get()) break

                // 打开目标城池出征面板并切到该部队
                prepareTroopPanel(config.cityVirtualCoord, currentTask.troopSlot)

                // 最后高精度微秒级等待
                while (isRunning.get()) {
                    if (System.currentTimeMillis() >= currentTask.optimalDispatchEpochMs) {
                        break
                    }
                    delay(4)
                }

                if (!isRunning.get()) break

                // 毫秒级扣动扳机：点击【确定出征】
                EngineBridge.clickButton(StzbUiMatcher.ButtonType.CONFIRM)
                val realFired = System.currentTimeMillis()
                val errorMs = realFired - currentTask.optimalDispatchEpochMs
                logTactic("🚀 ${currentTask.roleName} 击发成功！绝对时刻: $realFired，误差: ${errorMs}ms")

                // 留出短暂间隔，看门狗回退大地图准备下一个任务
                EngineBridge.humanDelay(800, 1500)
                WatchdogRecovery.recoverToMainMap()
            }

            listener?.onStatusChanged(
                TacticalState.TaskType.SIEGE_SYNC,
                TacticalState.Status.COMPLETED,
                "同盟集火攻城排队队列全部按时发射完毕！"
            )
            return true

        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.SIEGE_SYNC,
                level = "ERROR",
                message = "攻城排队流异常: ${e.message}"
            ))
            return false
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 测量并计算全部队列队伍的耗时与触发时刻
     */
    private suspend fun measureAllDispatches(
        config: SiegeConfig,
        queue: PriorityQueue<ScheduledDispatch>
    ): PriorityQueue<ScheduledDispatch> {
        val resultQueue = PriorityQueue<ScheduledDispatch>()

        // 点击目标城池并打开出征面板
        EngineBridge.tap(config.cityVirtualCoord.x, config.cityVirtualCoord.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)
        EngineBridge.clickButton(StzbUiMatcher.ButtonType.ATTACK)
        val dialogReady = EngineBridge.waitForState(StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG, 3000)
        if (!dialogReady) return resultQueue

        val marchRoi = Rect(
            (CoordinateTransformer.virtualWidth - 360).toInt(),
            (CoordinateTransformer.virtualHeight - 160).toInt(),
            CoordinateTransformer.virtualWidth.toInt(),
            CoordinateTransformer.virtualHeight.toInt()
        )

        for (task in queue) {
            clickTroopSlotTab(task.troopSlot)
            EngineBridge.humanDelay(300, 500)

            val timingPlan = EngineBridge.planCardSecondDispatch(
                marchTimeRoi = marchRoi,
                targetHitEpochMs = task.targetHitEpochMs,
                networkJitterCompensationMs = config.latencyCompensationMs
            )

            if (timingPlan != null) {
                task.marchDurationSec = timingPlan.travelDurationSec
                task.optimalDispatchEpochMs = timingPlan.optimalDispatchEpochMs
                resultQueue.add(task)
            } else {
                // 估算兜底 (主力默认 2分30秒，拆迁默认 6分10秒)
                val fallbackSec = if (task.roleName.contains("主力")) 150L else 370L
                task.marchDurationSec = fallbackSec
                task.optimalDispatchEpochMs = task.targetHitEpochMs - fallbackSec * 1000L - config.latencyCompensationMs
                resultQueue.add(task)
            }
        }

        // 测算完毕，先关闭面板回到大地图
        WatchdogRecovery.recoverToMainMap()
        return resultQueue
    }

    private suspend fun prepareTroopPanel(cityCoord: PointF, slot: Int) {
        EngineBridge.tap(cityCoord.x, cityCoord.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 1800)
        EngineBridge.clickButton(StzbUiMatcher.ButtonType.ATTACK)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG, 2000)
        clickTroopSlotTab(slot)
    }

    private suspend fun clickTroopSlotTab(slot: Int) {
        val stepX = 140f
        val startX = 220f
        val targetX = startX + (slot - 1) * stepX
        EngineBridge.tap(targetX, 160f)
    }

    private fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.SIEGE_SYNC, "INFO", msg))
    private fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.SIEGE_SYNC, "WARN", msg))
    private fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.SIEGE_SYNC, "TACTIC", msg))

    private fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "SiegeSyncFlow"
    }
}
