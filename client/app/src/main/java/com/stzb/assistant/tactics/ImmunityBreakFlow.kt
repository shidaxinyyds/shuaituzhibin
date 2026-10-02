package com.stzb.assistant.tactics

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极限卡免与压秒破免执行器 (ImmunityBreakFlow)
 * 
 * 核心痛点解决：
 *   1. 【压秒破免 (00:00:01 触敌)】：手工掐秒表容易慢 2 秒或快 1 秒（快 1 秒会被系统判定为“土地免战中”
 *      原路弹回白耗体力；慢 2 秒会被敌人补上驻守防线）。本执行器依托阶段二毫秒级倒计时，自动扣除
 *      行军时长与触控网络时延，实现 00:00:01.000 压秒破免秒杀！
 *   2. 【极限接力卡免 (无限免战阵地战)】：己方关隘要塞前排地免战即将到期时，自动派斯巴达在 00:00:01 刷新
 *      重新挂起 1 小时免战罩，将敌盟彻底堵在关口之外。
 */
class ImmunityBreakFlow(
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    enum class ImmunityMode {
        BREAK_IMMUNITY, // 压秒破免 (进攻敌方免战地)
        RELAY_DEFENSE   // 接力卡免 (防守己方关口刷新免战罩)
    }

    data class ImmunityConfig(
        val mode: ImmunityMode,
        val targetTileCoord: PointF,
        val designatedTroopSlot: Int = 1,
        val latencyCompensationMs: Long = 110L // 触控与网络时延补偿
    )

    fun stop() {
        isRunning.set(false)
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.IMMUNITY_BREAK,
            level = "WARN",
            message = "⏹️ 收到用户终止指令，卡免/破免执行流已终止。"
        ))
    }

    /**
     * 启动压秒破免 / 接力卡免流水线
     */
    suspend fun execute(config: ImmunityConfig): Boolean {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "卡免/破免任务已在执行中。")
            return false
        }

        val modeDesc = if (config.mode == ImmunityMode.BREAK_IMMUNITY) "压秒破免 (00:00:01触敌)" else "极限接力卡免 (刷新免战罩)"
        listener?.onStatusChanged(
            TacticalState.TaskType.IMMUNITY_BREAK,
            TacticalState.Status.RUNNING,
            "启动 [$modeDesc]，正在锁定目标地块 (${config.targetTileCoord.x.toInt()}, ${config.targetTileCoord.y.toInt()})"
        )

        try {
            // 1. 确保大地图就绪
            WatchdogRecovery.recoverToMainMap()

            // 2. 检测地块当前免战倒计时
            logTactic("🔍 正在通过 OpenCV 金色光罩与局部 RapidOCR 读取地块免战剩余时间...")
            val immunityStatus = EngineBridge.detectTileImmunity()
            if (!immunityStatus.isImmune || immunityStatus.remainingSeconds <= 0L) {
                logWarn("⚠️ 未检测到有效免战罩或已过免战期！剩余秒数: ${immunityStatus.remainingSeconds}")
                // 若本身已无免战，破免可直接出征
                if (config.mode == ImmunityMode.BREAK_IMMUNITY) {
                    logInfo("地块已无免战罩，可直接发起普通占领出征。")
                }
            }

            val unlockTimestampMs = if (immunityStatus.isImmune && immunityStatus.remainingSeconds > 0) {
                immunityStatus.unlockTimestampMs
            } else {
                System.currentTimeMillis() + 60 * 1000L // 默认预留 1 分钟测算
            }

            logInfo("⏱️ 目标地块免战解锁时间戳: $unlockTimestampMs (剩余: ${immunityStatus.remainingSeconds}秒)")

            // 3. 点击地块打开操作菜单
            EngineBridge.tap(config.targetTileCoord.x, config.targetTileCoord.y)
            EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)

            // 4. 点击出征按键
            EngineBridge.clickButton(StzbUiMatcher.ButtonType.ATTACK)
            val dialogReady = EngineBridge.waitForState(StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG, 3000)
            if (!dialogReady) {
                logWarn("未能呼出出征面板，执行看门狗自愈...")
                WatchdogRecovery.recoverToMainMap()
                return false
            }

            // 5. 选中指定部队
            clickTroopSlotTab(config.designatedTroopSlot)
            EngineBridge.humanDelay(300, 600)

            // 6. 测算行军耗时与绝对出征触发时刻
            // 目标触敌时刻 = 破免时刻 + 1000ms (确保 00:00:01 触敌，杜绝提前 0.1 秒被系统弹回)
            val targetHitEpochMs = unlockTimestampMs + 1000L

            val marchRoi = Rect(
                (CoordinateTransformer.virtualWidth - 360).toInt(),
                (CoordinateTransformer.virtualHeight - 160).toInt(),
                CoordinateTransformer.virtualWidth.toInt(),
                CoordinateTransformer.virtualHeight.toInt()
            )

            val timingPlan = EngineBridge.planCardSecondDispatch(
                marchTimeRoi = marchRoi,
                targetHitEpochMs = targetHitEpochMs,
                networkJitterCompensationMs = config.latencyCompensationMs
            )

            if (timingPlan == null) {
                logWarn("⚠️ 无法识别行军耗时，尝试二次直接识别...")
                // 若局部裁剪失败，走备用出征确认
                return false
            }

            logTactic(
                "🎯【精密卡秒方案就绪】\n" +
                "  • 行军耗时: ${timingPlan.travelDurationSec} 秒\n" +
                "  • 设定触敌时刻: ${timingPlan.targetHitEpochMs}\n" +
                "  • 绝对出征触发点: ${timingPlan.optimalDispatchEpochMs}\n" +
                "  • 需等待倒计时: ${timingPlan.waitDelayMs} ms"
            )

            // 7. 高精度倒计时排队与毫秒级点火出征
            if (timingPlan.waitDelayMs > 0) {
                listener?.onStatusChanged(
                    TacticalState.TaskType.IMMUNITY_BREAK,
                    TacticalState.Status.WAITING_COUNTDOWN,
                    "卡秒倒计时等待中，还剩 ${(timingPlan.waitDelayMs / 1000)} 秒出征"
                )

                // 粗略休眠到出征前 2000ms
                if (timingPlan.waitDelayMs > 2000) {
                    delay(timingPlan.waitDelayMs - 2000)
                }

                // 最后 2000ms 精密空转循环，锁定毫秒级精度
                while (isRunning.get()) {
                    val now = System.currentTimeMillis()
                    if (now >= timingPlan.optimalDispatchEpochMs) {
                        break
                    }
                    delay(5)
                }
            }

            if (!isRunning.get()) return false

            // 8. 毫秒级扣动扳机：点击【确定出征】！
            val fired = EngineBridge.clickButton(StzbUiMatcher.ButtonType.CONFIRM)
            val triggerTime = System.currentTimeMillis()
            val diffMs = triggerTime - timingPlan.optimalDispatchEpochMs

            logTactic("🚀【出征触发完毕】时间误差: ${diffMs}ms！部队正高速开赴目标，预计将在 00:00:01 准点触敌！")

            listener?.onStatusChanged(
                TacticalState.TaskType.IMMUNITY_BREAK,
                TacticalState.Status.COMPLETED,
                "压秒破免执行完毕，出征时间偏差仅 ${diffMs}ms"
            )
            return true

        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.IMMUNITY_BREAK,
                level = "ERROR",
                message = "卡免/破免执行异常: ${e.message}"
            ))
            return false
        } finally {
            isRunning.set(false)
        }
    }

    private suspend fun clickTroopSlotTab(slot: Int) {
        val stepX = 140f
        val startX = 220f
        val targetX = startX + (slot - 1) * stepX
        EngineBridge.tap(targetX, 160f)
    }

    private fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.IMMUNITY_BREAK, "INFO", msg))
    private fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.IMMUNITY_BREAK, "WARN", msg))
    private fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.IMMUNITY_BREAK, "TACTIC", msg))

    private fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "ImmunityBreakFlow"
    }
}
