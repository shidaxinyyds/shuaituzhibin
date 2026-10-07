package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.antiban.AntiBanCoordinator
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import kotlinx.coroutines.delay
import java.util.regex.Pattern

/**
 * 全赛季二三队无损速升 40 级与低损练级流水线 (SquadLevelingFlow)
 *
 * 核心痛点解决：
 *   1. 【全赛季全生命周期护航】：突破 48h 开荒局限，贯穿 80~90 天全赛季二队、三队甚至四队 20~40 级练级；
 *   2. 【软柿子地块一键绑定】：无缝衔接 SoftTileRadarFlow 锁定的最低战损 Lv.7/Lv.8 地块，自动扫荡；
 *   3. 【体力永动双队轮换】：
 *      - 二队扫荡至体力 < 20 时，自动无缝切换三队继续扫荡；
 *      - 双队体力皆耗尽时，自动进入定时省电休眠，待体力恢复后自动唤醒恢复流水线；
 *   4. 【三维防翻车严苛硬熔断】：
 *      - 熔断 1：单场战损率 > 15%（单次死兵超阈值）立即急停并高音蜂鸣告警；
 *      - 熔断 2：部队实时兵力 < 70% 立即终止后续扫荡；
 *      - 熔断 3：检测到任一武将处于“重伤”状态立即硬熔断停机；
 *   5. 【扫荡间歇秒补预备兵】：战损在 5%~15% 安全区间内时，自动调用快速分兵补齐满编再行出征。
 */
class SquadLevelingFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener
) {

    @Volatile
    private var isRunning: Boolean = false

    data class LevelingConfig(
        val targetTileCoord: PointF? = null,
        val targetWorldCoord: Pair<Int, Int>? = null,
        val bookmarkName: String? = null,
        val tileLevel: Int = 7,
        val squadSlotA: Int = 2,
        val squadSlotB: Int = 3,
        val staminaMinThreshold: Int = 20,
        val maxCasualtyRate: Float = 0.15f,     // 15% 战损硬熔断
        val minHealthPercent: Float = 0.70f,     // 70% 兵力熔断
        val haltOnSevereInjury: Boolean = true,  // 重伤熔断
        val autoReplenishReserves: Boolean = true,
        val maxRounds: Int = 15
    )

    data class SweepRoundResult(
        val roundIndex: Int,
        val slotUsed: Int,
        val lossSoldiers: Int,
        val lossRate: Float,
        val remainingTroops: Int,
        val maxTroops: Int,
        val staminaRemaining: Int?,
        val isFused: Boolean,
        val fuseReason: String?
    )

    fun stop() {
        isRunning = false
        logTactic("⏹️ 二三队速升 40 级练级流水线已收到终止请求")
    }

    suspend fun execute(config: LevelingConfig): Boolean {
        isRunning = true
        notifyStatus(TacticalState.Status.RUNNING, "启动二三队低损速升 40 级练级流水线...")
        logTactic(
            "⚔️ 练级流水线启动: 目标=Lv.${config.tileLevel}地块, 主练=${config.squadSlotA}队, 轮换=${config.squadSlotB}队, " +
            "体力底线=${config.staminaMinThreshold}, 熔断阈值: 单场战损>${(config.maxCasualtyRate * 100).toInt()}% / 兵力<${(config.minHealthPercent * 100).toInt()}%"
        )

        var currentSlot = config.squadSlotA
        var completedRounds = 0
        var slotAExhausted = false
        var slotBExhausted = false

        try {
            WatchdogRecovery.recoverToMainMap()

            val tapPoint = resolveTargetPoint(config)
            if (tapPoint == null) {
                logTactic("❌ 未能锁定有效练级目标地块（书签或坐标为空），流程中止")
                notifyStatus(TacticalState.Status.FAILED, "缺少有效目标地块")
                return false
            }

            while (isRunning && completedRounds < config.maxRounds) {
                // 1. 检查当前编队体力与伤病状态
                logTactic("🔍 正在巡查第 $currentSlot 队出征前状态（体力/兵力/重伤）...")
                val preCheck = inspectSquadStatus(currentSlot)

                if (preCheck.isWounded && config.haltOnSevereInjury) {
                    logTactic("🚨【重伤熔断触发】检测到第 $currentSlot 队有武将处于重伤状态，立即硬熔断停机！")
                    triggerFuseHalt("武将重伤熔断")
                    return false
                }

                val curTroops = preCheck.currentTroops ?: 20000
                val maxTroops = preCheck.maxTroops ?: 20000
                val healthRatio = if (maxTroops > 0) curTroops.toFloat() / maxTroops else 1.0f

                if (healthRatio < config.minHealthPercent) {
                    logTactic("🚨【血量熔断触发】第 $currentSlot 队兵力 $curTroops/$maxTroops (${(healthRatio * 100).toInt()}%) < ${(config.minHealthPercent * 100).toInt()}%，立即硬熔断！")
                    triggerFuseHalt("兵力不足熔断")
                    return false
                }

                // 体力检查与双队轮换
                val stamina = preCheck.stamina
                if (stamina != null && stamina < config.staminaMinThreshold) {
                    logTactic("🔋 第 $currentSlot 队体力已降至 $stamina (< ${config.staminaMinThreshold})，准备轮换！")
                    if (currentSlot == config.squadSlotA) {
                        slotAExhausted = true
                        currentSlot = config.squadSlotB
                        logTactic("🔄 自动无缝切换到备用练级队：第 ${config.squadSlotB} 队")
                        continue
                    } else {
                        slotBExhausted = true
                    }

                    if (slotAExhausted && slotBExhausted) {
                        logTactic("💤 主练与轮换双队体力皆已耗尽（均 < ${config.staminaMinThreshold}）。进入低功耗待机恢复...")
                        notifyStatus(TacticalState.Status.COMPLETED, "双队体力已耗尽，已达成最佳练级收益")
                        return true
                    }
                }

                // 2. 预备兵秒补（如果损失处于 5%~20% 安全微损区间）
                if (config.autoReplenishReserves && healthRatio in config.minHealthPercent..0.95f) {
                    logTactic("🩹 当前兵力 ${(healthRatio * 100).toInt()}%，触发预备兵秒补至满编...")
                    replenishReserveTroops(currentSlot)
                    EngineBridge.humanDelay(600, 1000)
                }

                if (!isRunning) break

                // 3. 点击目标地块唤起轮盘
                completedRounds++
                notifyStatus(TacticalState.Status.RUNNING, "第 $completedRounds/${config.maxRounds} 轮扫荡执行中（使用第 $currentSlot 队）...")
                logTactic("🎯 [第 $completedRounds 轮] 点击目标地块 (${tapPoint.x.toInt()}, ${tapPoint.y.toInt()}) 唤起操作菜单...")
                if (!EngineBridge.tap(tapPoint.x, tapPoint.y)) {
                    logTactic("❌ 点击地块失败，重试看门狗自愈")
                    WatchdogRecovery.recoverToMainMap()
                    continue
                }
                EngineBridge.humanDelay(600, 1000)

                // 4. 点击「扫荡」按钮 (SWEEP)
                logTactic("🧹 点击「扫荡」按钮...")
                val sweepBtn = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.SWEEP)
                if (!sweepBtn.clicked) {
                    logTactic("⚠️ 未检测到「扫荡」按钮，尝试「出征」备选")
                    val atkBtn = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.ATTACK)
                    if (!atkBtn.clicked) {
                        logTactic("❌ 无法进入扫荡/出征面板，本轮跳过")
                        WatchdogRecovery.recoverToMainMap()
                        continue
                    }
                }
                EngineBridge.humanDelay(800, 1300)

                // 5. 选中当前练级编队
                selectTroopSlot(currentSlot)
                EngineBridge.humanDelay(500, 900)

                // 6. 点击确认出征
                val confirmOutcome = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
                if (!confirmOutcome.clicked) {
                    EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.MARCH)
                }
                logTactic("🚀 第 $currentSlot 队已成功出征，执行 Lv.${config.tileLevel} 土地扫荡！")

                // 7. 等待扫荡战斗结算（常规土地往返与碰撞约 25~45 秒）
                notifyStatus(TacticalState.Status.WAITING_COUNTDOWN, "等待扫荡部队触敌与回营...")
                //    预算必须覆盖上面写的 25~45s：`humanDelay` 现在**硬上限**
                //    （这正是 P3a 的修复），旧的 (20000, 30000) 在夜里反而能“意外
                //    等多久算多久”，而现在最多就是 maxMs——继续沿用会让第 8 步
                //    的战损复核读到“还没回营”的旧画面（比真实战损乐观）。
                EngineBridge.humanDelay(30000, 45000)

                // 8. 战损复核与单场战损硬熔断检查
                val postCheck = inspectBattleLoss(currentSlot, curTroops)
                logTactic(
                    "📊【第 $completedRounds 轮扫荡战报】损失兵力: ${postCheck.lossSoldiers} 兵 " +
                    "(战损率: ${"%.1f".format(postCheck.lossRate * 100)}%), 剩余兵力: ${postCheck.remainingTroops}/${postCheck.maxTroops}"
                )

                if (postCheck.lossRate > config.maxCasualtyRate) {
                    logTactic(
                        "🚨【单场战损硬熔断拦截】单场战损率 ${"%.1f".format(postCheck.lossRate * 100)}% > 阈值 " +
                        "${(config.maxCasualtyRate * 100).toInt()}%，触发防翻车熔断保护，立即终止扫荡！"
                    )
                    triggerFuseHalt("单场战损超限熔断")
                    return false
                }

                logTactic("✅ 第 $completedRounds 轮扫荡顺利完成，战损极低，经验值已获取！")
                WatchdogRecovery.recoverToMainMap()
                EngineBridge.humanDelay(1500, 2500)

                // 9. 轮间安全缝：到点才歇（每 45~80 分钟一次，一次 2~5 分钟）。
                //    这里是全链路里**唯一**该放长休息的位置：本轮已结算完毕、
                //    已回到主地图、下一轮尚未开始，停几分钟不破任何时序契约；
                //    而它抹掉的是“连续数小时零停顿”这条最易提取的 24h 挂机指纹。
                //    注意末尾一轮不再歇：否则流程会在一句“圆满完成”前白等三分钟。
                if (isRunning && completedRounds < config.maxRounds) {
                    val rested = AntiBanCoordinator.maybeTakeMicroBreak { isRunning }
                    if (rested > 0L) {
                        logTactic("☕ 已模拟真实玩家离开 ${rested / 1000} 秒，继续第 ${completedRounds + 1} 轮。")
                    }
                }
            }

            val finalMsg = "练级流水线圆满完成！累计完成 $completedRounds 轮低损扫荡，武将经验高效入账"
            logTactic("🎉 $finalMsg")
            notifyStatus(TacticalState.Status.COMPLETED, finalMsg)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "练级流水线异常中断: ${e.message}", e)
            notifyStatus(TacticalState.Status.FAILED, "异常中断: ${e.message}")
            return false
        } finally {
            isRunning = false
        }
    }

    data class SquadInspection(
        val stamina: Int?,
        val currentTroops: Int?,
        val maxTroops: Int?,
        val isWounded: Boolean
    )

    /**
     * 巡查编队当前体能与伤员情况
     */
    private suspend fun inspectSquadStatus(slotIndex: Int): SquadInspection {
        val frame = EngineBridge.captureFrame() ?: return SquadInspection(100, 20000, 20000, false)
        return try {
            val ocr = OcrManager.detectRoi(frame)
            val text = ocr?.strRes ?: ""
            val isWounded = text.contains("重伤") || text.contains("休养") || text.contains("治疗中")
            val stamina = extractStamina(text)
            val (cur, max) = extractTroopCounts(text)
            SquadInspection(stamina, cur, max, isWounded)
        } finally {
            frame.recycle()
        }
    }

    data class LossInspection(
        val lossSoldiers: Int,
        val lossRate: Float,
        val remainingTroops: Int,
        val maxTroops: Int
    )

    /**
     * 战后计算损失兵力与战损率
     */
    private suspend fun inspectBattleLoss(slotIndex: Int, beforeTroops: Int): LossInspection {
        val frame = EngineBridge.captureFrame()
        var cur = beforeTroops
        var max = 20000
        if (frame != null) {
            try {
                val ocr = OcrManager.detectRoi(frame)
                val text = ocr?.strRes ?: ""
                val (readCur, readMax) = extractTroopCounts(text)
                if (readCur != null) cur = readCur
                if (readMax != null) max = readMax
            } finally {
                frame.recycle()
            }
        }

        val loss = (beforeTroops - cur).coerceAtLeast(0)
        val lossRate = if (beforeTroops > 0) loss.toFloat() / beforeTroops else 0f
        return LossInspection(loss, lossRate, cur, max)
    }

    /**
     * 快速补充预备兵
     */
    private suspend fun replenishReserveTroops(slotIndex: Int) {
        val recruitBtn = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.RECRUIT)
        if (recruitBtn.clicked) {
            EngineBridge.humanDelay(600, 1000)
            // 确认快速分兵预备兵
            EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
            EngineBridge.humanDelay(500, 800)
            EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CANCEL)
        }
    }

    private fun triggerFuseHalt(reason: String) {
        AlarmRinger.triggerEmergencyAlarm(context, "练级熔断: $reason")
        notifyStatus(TacticalState.Status.FAILED, "熔断保护生效: $reason")
    }

    /**
     * 从部队卡片文案里抽体力。
     *
     * 分母按**当前知识库的体力上限**拼装，不写死 120：上限不是 120 的游戏
     * 用写死分母时这一项恒为 null，练级轮换就会永远等不到"体力满"而要么不轮换、
     * 要么按未知体力乱轮换（静默错，不报错）。
     */
    fun extractStamina(text: String): Int? {
        val max = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules.maxStamina
        val m = Pattern.compile("(\\d{1,3})\\s*/\\s*" + max + "\\b").matcher(text)
        if (m.find()) return m.group(1)?.toIntOrNull()
        return null
    }

    fun extractTroopCounts(text: String): Pair<Int?, Int?> {
        val m = Pattern.compile("(\\d{3,5})\\s*/\\s*(\\d{3,5})").matcher(text)
        if (m.find()) {
            val c = m.group(1)?.toIntOrNull()
            val mx = m.group(2)?.toIntOrNull()
            return Pair(c, mx)
        }
        return Pair(null, null)
    }

    private suspend fun selectTroopSlot(slotIndex: Int): Boolean {
        val slot = slotIndex.coerceIn(1, UiAnchors.troopTabSlotCount)
        val tabPoint = UiAnchors.troopTab(slot)
        return EngineBridge.tap(tabPoint.x, tabPoint.y)
    }

    private suspend fun resolveTargetPoint(config: LevelingConfig): PointF? {
        if (!config.bookmarkName.isNullOrBlank()) {
            when (MapNavigator.jumpByBookmark(config.bookmarkName)) {
                is MapNavigator.Result.Reached -> return MapProjection.viewportCenterCanvas()
                else -> Log.w(TAG, "书签跳转未成，尝试坐标")
            }
        }
        if (config.targetWorldCoord != null && MapProjection.isCalibrated) {
            val (wx, wy) = config.targetWorldCoord
            when (MapNavigator.centerOn(wx, wy)) {
                is MapNavigator.Result.Reached -> return MapProjection.viewportCenterCanvas()
                else -> Log.w(TAG, "世界坐标跳转未成，回退屏幕坐标")
            }
        }
        return config.targetTileCoord
    }

    private fun notifyStatus(status: TacticalState.Status, detail: String) {
        listener.onStatusChanged(TacticalState.TaskType.SQUAD_LEVELING, status, detail)
    }

    private fun logTactic(msg: String) {
        listener.onLogEmitted(
            TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.SQUAD_LEVELING,
                level = "TACTIC",
                message = msg
            )
        )
    }

    companion object {
        private const val TAG = "SquadLevelingFlow"
    }
}
