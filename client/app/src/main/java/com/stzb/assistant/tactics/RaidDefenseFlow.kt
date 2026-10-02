package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ocr.RaidRadarDetector
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 深夜敌袭应急处置总控与决策 C 自动反击中枢 (RaidDefenseFlow)
 * 
 * 核心痛点与用户最高优先级决策落地：
 *   1. 凌晨 3:00~5:00 真实沙盘夜战偷家全天候 24h 自动化巡检守护；
 *   2. 发现敌袭红线后，瞬间触发 AlarmRinger 高分贝鸣镝警报 + 强节奏马达震动 + 保持屏幕常亮唤醒；
 *   3. 【商业化王牌战术 - 决策 C：自动反击——拆除敌人跳板要塞与断其链接地】：
 *      不再被动坐以待毙或单纯丢弃资源，而是依托 RaidRadarDetector 源头回溯算法定位敌军出发要塞/前排跳板地，
 *      自动指挥高机动骑兵/斯巴达拆迁队反扑该跳板地，断其补给链与行军前线！
 *   4. 【决策 A 兜底防御】：若敌方跳板地超出视野或无法触达，自动切换为被袭主基地紧急调兵驻守拦截。
 */
class RaidDefenseFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    data class DefenseConfig(
        val baseAnchor: PointF? = null,              // 己方主城/防守核心要塞虚拟锚点 (默认居中)
        val counterAttackSquadSlot: Int = 1,        // 决策 C 反击所用的高机动拆迁骑兵槽位
        val patrolIntervalMs: Long = 4000L,         // 夜战雷达巡检周期 (毫秒)
        val enableAudioAlarm: Boolean = true,       // 是否拉响高分贝警报与震动
        val enableDecisionC: Boolean = true         // 是否全自动执行决策 C 反击拆除
    )

    fun stop() {
        isRunning.set(false)
        AlarmRinger.stopAlarm(context)
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.RAID_DEFENSE,
            level = "WARN",
            message = "⏹️ 深夜敌袭巡检总控已安全关闭，警报已复位。"
        ))
    }

    /**
     * 启动深夜敌袭雷达守护巡检常驻协程
     */
    suspend fun startPatrol(config: DefenseConfig) {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "敌袭防御巡检已在运行中。")
            return
        }

        listener?.onStatusChanged(
            TacticalState.TaskType.RAID_DEFENSE,
            TacticalState.Status.RUNNING,
            "深夜敌袭巡检雷达已全天候激活，守护中..."
        )

        logTactic("🛡️【深夜雷达巡检已启动】巡检频率: ${config.patrolIntervalMs}ms/次，决策 C 自动断路反击已就绪。")

        try {
            while (isRunning.get()) {
                // 1. 确保大地图主界面
                val currentState = EngineBridge.detectGameState()
                if (currentState != StzbUiMatcher.GameState.MAIN_MAP) {
                    WatchdogRecovery.recoverToMainMap()
                }

                // 2. OpenCV 扫描全景敌袭红线与边缘呼吸警报光晕
                val raidReport = EngineBridge.scanRaidThreats(config.baseAnchor)

                if (raidReport.hasThreat) {
                    handleRaidEvent(raidReport, config)
                }

                // 3. 拟人随机周期休眠
                delay(config.patrolIntervalMs)
            }
        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.RAID_DEFENSE,
                level = "ERROR",
                message = "敌袭防御总控巡检异常: ${e.message}"
            ))
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 突发敌袭综合处置流
     */
    private suspend fun handleRaidEvent(report: RaidRadarDetector.RaidReport, config: DefenseConfig) {
        val levelStr = if (report.threatLevel == RaidRadarDetector.ThreatLevel.CRITICAL) "CRITICAL (极度高危)" else "WARNING (中度预警)"

        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.RAID_DEFENSE,
            level = "ERROR",
            message = "🚨【警报！发现深夜敌袭夜战偷家】\n" +
                    "  • 威胁等级: $levelStr\n" +
                    "  • 屏幕边缘呼吸红光: ${report.isScreenEdgeAlert}\n" +
                    "  • 识别红线行军轨迹: ${report.detectedVectors.size} 条\n" +
                    "  • 受威胁己方基地: (${report.playerTargetPoint?.x?.toInt()}, ${report.playerTargetPoint?.y?.toInt()})\n" +
                    "  • 敌方进攻源头跳板地: (${report.enemyOriginPoint?.x?.toInt()}, ${report.enemyOriginPoint?.y?.toInt()})"
        ))

        listener?.onStatusChanged(
            TacticalState.TaskType.RAID_DEFENSE,
            TacticalState.Status.RUNNING,
            "🚨 发现敌袭偷家！正在执行紧急处置与反击..."
        )

        // 步骤 1：立即拉响高分贝鸣镝警报，唤醒熟睡中的玩家
        if (config.enableAudioAlarm) {
            AlarmRinger.startAlarm(context)
        }

        // 步骤 2：全自动执行【决策 C：拆除敌人跳板要塞与断其链接地】
        if (config.enableDecisionC && report.enemyOriginPoint != null) {
            val successC = executeDecisionC(report.enemyOriginPoint, config.counterAttackSquadSlot)
            if (successC) {
                logTactic("⚔️【决策 C 执行大捷】已成功对敌方进攻跳板发起反攻断地出征！")
                return
            } else {
                logWarn("决策 C 跳板地点击出征受阻，平滑降级执行【决策 A：紧急调兵驻守】！")
            }
        }

        // 步骤 3：降级兜底方案【决策 A：己方受袭基地紧急驻守】
        if (report.playerTargetPoint != null) {
            executeDecisionA(report.playerTargetPoint, config.counterAttackSquadSlot)
        }
    }

    /**
     * 【决策 C 核心实现】：自动反击——拆除敌人跳板要塞与断其链接地
     */
    private suspend fun executeDecisionC(enemyOriginPoint: PointF, squadSlot: Int): Boolean {
        logTactic("🎯【执行决策 C 反击】正在锁定敌方源头跳板要塞/链接地: (${enemyOriginPoint.x.toInt()}, ${enemyOriginPoint.y.toInt()})")

        // 1. 点击敌军源头地块
        EngineBridge.tap(enemyOriginPoint.x, enemyOriginPoint.y)
        val menuOpened = EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)
        if (!menuOpened) {
            logWarn("未能打开敌方源头地块菜单，尝试二次点击...")
            EngineBridge.tap(enemyOriginPoint.x, enemyOriginPoint.y)
            if (!EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2000)) {
                return false
            }
        }

        // 2. 点击【出征】发起反击
        val attackSuccess = EngineBridge.clickButton(StzbUiMatcher.ButtonType.ATTACK)
        if (!attackSuccess) {
            logWarn("敌方跳板地出征按键未点亮或不可点击。")
            return false
        }

        // 3. 等待出征面板
        val dialogOpened = EngineBridge.waitForState(StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG, 3000)
        if (!dialogOpened) return false

        // 4. 切换至高机动拆迁骑兵队
        clickTroopSlotTab(squadSlot)
        EngineBridge.humanDelay(300, 500)

        // 5. 点击【确定出征】
        val confirmSuccess = EngineBridge.clickButton(StzbUiMatcher.ButtonType.CONFIRM)
        EngineBridge.humanDelay(800, 1200)

        // 6. 恢复大地图
        WatchdogRecovery.recoverToMainMap()
        return confirmSuccess
    }

    /**
     * 【决策 A 核心实现】：己方受威胁要塞/主城紧急调兵驻守拦截
     */
    private suspend fun executeDecisionA(playerBasePoint: PointF, squadSlot: Int): Boolean {
        logTactic("🛡️【执行决策 A 驻守】正在紧急驰援己方目标地: (${playerBasePoint.x.toInt()}, ${playerBasePoint.y.toInt()})")

        EngineBridge.tap(playerBasePoint.x, playerBasePoint.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2000)

        // 点击【驻守】按键
        val defendClicked = EngineBridge.clickButton(StzbUiMatcher.ButtonType.DEFEND)
        if (!defendClicked) return false

        EngineBridge.waitForState(StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG, 2500)
        clickTroopSlotTab(squadSlot)
        EngineBridge.humanDelay(300, 500)

        val confirmSuccess = EngineBridge.clickButton(StzbUiMatcher.ButtonType.CONFIRM)
        EngineBridge.humanDelay(800, 1200)
        WatchdogRecovery.recoverToMainMap()
        return confirmSuccess
    }

    private suspend fun clickTroopSlotTab(slot: Int) {
        val stepX = 140f
        val startX = 220f
        val targetX = startX + (slot - 1) * stepX
        EngineBridge.tap(targetX, 160f)
    }

    private fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.RAID_DEFENSE, "INFO", msg))
    private fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.RAID_DEFENSE, "WARN", msg))
    private fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.RAID_DEFENSE, "TACTIC", msg))

    private fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "RaidDefenseFlow"
    }
}
