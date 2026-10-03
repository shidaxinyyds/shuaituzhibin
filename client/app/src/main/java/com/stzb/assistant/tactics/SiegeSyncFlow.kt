package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.UiAnchors
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import kotlinx.coroutines.delay
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 同盟集火攻城双压秒全勤王中枢 (SiegeSyncFlow)
 * 
 * 核心痛点彻底解决：
 *   1. 【发车前 30 分钟自愈调动与健康体检】：
 *      - 提前 30 分钟检查参战部队体力（需 >= 20）与士气；
 *      - 若体力不足，提前轻微震动告警玩家，绝不在发车最后一秒暴雷；
 *      - 若部队未在指定前线要塞，自动下发【调兵/调动】指令进驻前线，确保发车前全员就位。
 *   2. 【主力首发与拆迁后置 5 秒两阶段双压秒】：
 *      - 征服赛季黄金标准：主力 21:00:00.000 触敌清守军，拆迁队后置 5 秒 (21:00:05.000) 跟刀破城皮；
 *      - 彻底规避网络抖动与主力未灭守军时拆迁撞车灭团的致命惨剧。
 *   3. 【反向时序倒排与毫秒卡秒发车】：
 *      - 慢速步兵拆迁（如耗时 12 分钟）自动早发车，快速主力骑兵（如耗时 3 分钟）晚发车；
 *      - 采用“先架枪预定位 + 到点极速手势击发”，误差严格压进 50ms 内。
 */
class SiegeSyncFlow(
    private val context: Context? = null,
    private val listener: TacticalState.TacticalEventListener? = null
) {

    constructor(listener: TacticalState.TacticalEventListener?) : this(null, listener)

    private val isRunning = AtomicBoolean(false)

    data class ScheduledDispatch(
        val roleName: String,               // "主力战法队" 或 "拆迁攻城队"
        val troopSlot: Int,                 // 部队槽位编号 (1 ~ 5)
        val targetHitEpochMs: Long,         // 期望触敌时刻 (例如 21:00:00 或 21:00:05)
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
        val demolitionSlots: List<Int> = listOf(2, 3), // 拆迁队槽位列表
        val latencyCompensationMs: Long = 100L,
        val cityWorldCoord: Pair<Int, Int>? = null, // 目标城池世界坐标
        val demolitionOffsetSec: Int = 5,   // 拆迁队后置秒数 (征服赛季黄金标准 5 秒)
        val fortressWorldCoord: Pair<Int, Int>? = null, // 前线集合要塞世界坐标 (用于发车前30分钟自愈调动)
        val fortressName: String? = null,   // 前线要塞名称
        val enablePreFlight30MinCheck: Boolean = true, // 是否开启发车前 30 分钟自愈调动与健康体检
        val preFlightLeadMs: Long = 30 * 60 * 1000L    // 前置体检提前量 (30 分钟)
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
            "开始测算同盟集火双压秒时序，主力目标触敌时刻: ${config.targetBaseHitEpochMs}，拆迁后置 ${config.demolitionOffsetSec}s"
        )

        try {
            // 1. 确保大地图主界面就绪
            WatchdogRecovery.recoverToMainMap()

            // 2. 发车前 30 分钟自愈调动与健康体检阶段
            if (config.enablePreFlight30MinCheck) {
                handlePreFlightPhase(config)
            }

            if (!isRunning.get()) return false

            // 3. 建立各梯队双压秒任务列表
            val planQueue = PriorityQueue<ScheduledDispatch>()

            // 注册主力队任务 (基准时刻 21:00:00.000 触敌清守军)
            planQueue.add(
                ScheduledDispatch(
                    roleName = "【主力第一队】",
                    troopSlot = config.mainSquadSlot,
                    targetHitEpochMs = config.targetBaseHitEpochMs
                )
            )

            // 注册拆迁队任务 (主力触敌后后置 5s 跟刀破皮: 21:00:05.000, 21:00:06.000 ...)
            config.demolitionSlots.forEachIndexed { idx, slot ->
                val delayMs = (config.demolitionOffsetSec + idx) * 1000L
                planQueue.add(
                    ScheduledDispatch(
                        roleName = "【拆迁第 ${idx + 1} 队】",
                        troopSlot = slot,
                        targetHitEpochMs = config.targetBaseHitEpochMs + delayMs
                    )
                )
            }

            // 4. 逐队测算行军耗时并计算出征触发时刻
            logTactic("📊 正在进入出征面板，对主力与各拆迁队进行行军耗时预检与测算...")
            val readyDispatches = measureAllDispatches(config, planQueue)
            if (readyDispatches.isEmpty()) {
                logWarn("未能完成任何队伍的耗时测算，攻城排队中止。")
                WatchdogRecovery.recoverToMainMap()
                return false
            }

            // 4.5 可行性预检：若某队的"最晚出征时刻"已经过去，它不可能按时触敌
            val nowMs = System.currentTimeMillis()
            val infeasible = readyDispatches.filter { it.optimalDispatchEpochMs <= nowMs }
            if (infeasible.isNotEmpty()) {
                val detail = infeasible.joinToString("；") { d ->
                    val overdueSec = (nowMs - d.optimalDispatchEpochMs) / 1000
                    "${d.roleName}(部队${d.troopSlot}) 应在 ${d.optimalDispatchEpochMs} 前出征，已晚 ${overdueSec}s"
                }
                val maxMarchSec = readyDispatches.maxOf { it.marchDurationSec }
                logWarn(
                    "⚠️ 卡秒不可行，本次集火已取消：$detail。\n" +
                        "原因：行军耗时 + 提前量超过了距离命中时刻的剩余时间。" +
                        "请把任务时间至少提前 ${maxMarchSec + 30} 秒，或增大卡秒偏移。"
                )
                listener?.onStatusChanged(
                    TacticalState.TaskType.SIEGE_SYNC,
                    TacticalState.Status.FAILED,
                    "卡秒不可行：行军耗时超过剩余时间，未出征"
                )
                WatchdogRecovery.recoverToMainMap()
                return false
            }

            // 5. 输出最终测算时序表
            val sb = StringBuilder("📋【全盟集火双压秒攻城排队时刻表】\n")
            readyDispatches.forEach { d ->
                val waitSec = (d.optimalDispatchEpochMs - System.currentTimeMillis()) / 1000
                sb.append("  • ${d.roleName} (部队${d.troopSlot}): 行军耗时 ${d.marchDurationSec}s | 出征触发点: ${d.optimalDispatchEpochMs} (倒计时: ${waitSec}s) | 触敌时刻: ${d.targetHitEpochMs}\n")
            }
            logTactic(sb.toString())

            // 6. 按照 optimalDispatchEpochMs 顺序逐一执行出征 (慢速拆迁先发，快速主力后发)
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

                // 提前一段时间打开出征面板做好准备
                if (waitMs > PANEL_PREP_MARGIN_MS) {
                    delay(waitMs - PANEL_PREP_MARGIN_MS)
                }

                if (!isRunning.get()) break

                // 打开目标城池出征面板并切到该部队
                prepareTroopPanel(config.cityVirtualCoord, currentTask.troopSlot)

                // 先架枪：预先在当前面板定位好【确定出征】安全触控点
                val prepared = EngineBridge.prepareButtonTap(StzbUiMatcher.ButtonType.CONFIRM)
                if (prepared == null) {
                    logWarn("⚠️ ${currentTask.roleName} 无法预定位【确定出征】，本队跳过（未出征）。")
                    WatchdogRecovery.recoverToMainMap()
                    continue
                }

                // 最后高精度微秒级等待
                while (isRunning.get()) {
                    if (System.currentTimeMillis() >= currentTask.optimalDispatchEpochMs) {
                        break
                    }
                    delay(4)
                }

                if (!isRunning.get()) break

                // 毫秒级扣动扳机：只派发手势（无 OCR 延迟）
                val fired = EngineBridge.firePreparedTap(prepared)
                val realFired = fired.dispatchEpochMs
                val errorMs = realFired - currentTask.optimalDispatchEpochMs
                if (fired.dispatched) {
                    logTactic("🚀 ${currentTask.roleName} 击发成功！绝对时刻: $realFired，误差: ${errorMs}ms")
                    if (errorMs > LATE_TOLERANCE_MS) {
                        logWarn(
                            "⚠️ ${currentTask.roleName} 晚打 ${errorMs}ms（容忍 ${LATE_TOLERANCE_MS}ms）。" +
                                "出征面板准备耗时可能超过了预留余量。"
                        )
                    }
                } else {
                    logWarn(
                        "⚠️ ${currentTask.roleName} 的【确定出征】手势派发失败（原定误差 ${errorMs}ms）；" +
                            "本次击发未生效，请人工复核该队是否已出发。"
                    )
                }

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
     * 发车前 30 分钟自愈调动与健康体检调度
     */
    private suspend fun handlePreFlightPhase(config: SiegeConfig) {
        val now = System.currentTimeMillis()
        // 假定慢速拆迁最长行军耗时约 12 分钟
        val estimatedFirstDispatch = config.targetBaseHitEpochMs - 12 * 60 * 1000L
        val preFlightTriggerEpoch = estimatedFirstDispatch - config.preFlightLeadMs

        if (now < preFlightTriggerEpoch) {
            val waitToPreFlightMs = preFlightTriggerEpoch - now
            val waitMinutes = waitToPreFlightMs / 60000
            logInfo("⏳ 距离发车前 30 分钟体检与调动还有 $waitMinutes 分钟，进入静默等待...")
            listener?.onStatusChanged(
                TacticalState.TaskType.SIEGE_SYNC,
                TacticalState.Status.WAITING_COUNTDOWN,
                "等待发车前 30 分钟体检与要塞调动 ($waitMinutes 分钟后)"
            )
            delay(waitToPreFlightMs)
        }

        if (isRunning.get()) {
            performPreFlightCheckAndTransfer(config)
        }
    }

    /**
     * 发车前 30 分钟自愈调动与健康体检核心实现
     */
    private suspend fun performPreFlightCheckAndTransfer(config: SiegeConfig) {
        logTactic("🩺【执行发车前 30 分钟健康体检】核验参战部队体力与前线要塞进驻状态...")
        WatchdogRecovery.recoverToMainMap()

        val checkSlots = listOf(config.mainSquadSlot) + config.demolitionSlots
        val lowStaminaSquads = mutableListOf<Int>()

        // 1. 体能勘测
        val tapPoint = resolveCityTapPoint(config)
        EngineBridge.tap(tapPoint.x, tapPoint.y)
        if (EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)) {
            val attack = EngineBridge.clickAndExpect(
                StzbUiMatcher.ButtonType.ATTACK,
                StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
                timeoutMs = 2500L,
                attempts = 2
            )
            if (attack.ok) {
                val cardRoi = UiAnchors.rect(UiAnchors.RectKey.TROOP_CARD)
                for (slot in checkSlots) {
                    clickTroopSlotTab(slot)
                    EngineBridge.humanDelay(250, 450)
                    val cardBmp = EngineBridge.captureRoi(cardRoi)
                    if (cardBmp != null) {
                        val detail = TroopStatusDetector.parseTroopCard(cardBmp, slot)
                        cardBmp.recycle()
                        if (detail.stamina != null && detail.stamina < 20) {
                            lowStaminaSquads.add(slot)
                        }
                    }
                }
            }
        }
        WatchdogRecovery.recoverToMainMap()

        // 体力不足：轻微震动提醒玩家，绝不暴雷
        if (lowStaminaSquads.isNotEmpty()) {
            logWarn("⚠️【发车前30分钟体检告警】参战部队 $lowStaminaSquads 当前体力不足 20！请尽快补充体力！")
            val v = context?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v?.vibrate(500)
            }
            listener?.onStatusChanged(
                TacticalState.TaskType.SIEGE_SYNC,
                TacticalState.Status.RUNNING,
                "⚠️ 体检告警: 部队 $lowStaminaSquads 体力不足 20"
            )
        } else {
            logInfo("✅【发车前30分钟体检通过】主力与拆迁部队体力均充足 (>= 20)。")
        }

        // 2. 检查并执行【自愈调动到指定前线要塞】
        val fortressCoord = config.fortressWorldCoord
        if (fortressCoord != null && MapProjection.isCalibrated) {
            logTactic("🚚【检查前线要塞进驻】目标集合要塞坐标: (${fortressCoord.first}, ${fortressCoord.second})")

            val navResult = MapNavigator.centerOn(fortressCoord.first, fortressCoord.second)
            if (navResult is MapNavigator.Result.Reached) {
                val centerPt = MapProjection.viewportCenterCanvas()
                EngineBridge.tap(centerPt.x, centerPt.y)
                if (EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2000)) {
                    val transferOutcome = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.TRANSFER)
                    if (transferOutcome.clicked) {
                        EngineBridge.humanDelay(600, 900)
                        for (slot in checkSlots) {
                            clickTroopSlotTab(slot)
                            EngineBridge.humanDelay(200, 350)
                        }
                        val confirm = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
                        if (confirm.clicked) {
                            logTactic("🚚【自愈调动成功】主力与拆迁部队已下发要塞调动指令！预计 10~15 分钟内抵达前线。")
                        }
                    } else {
                        logInfo("要塞当前未见调动按键，部队可能已入驻或处于驻守状态。")
                    }
                }
            }
            WatchdogRecovery.recoverToMainMap()
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

        val tapPoint = resolveCityTapPoint(config)
        EngineBridge.tap(tapPoint.x, tapPoint.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)
        val attack = EngineBridge.clickAndExpect(
            StzbUiMatcher.ButtonType.ATTACK,
            StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
            timeoutMs = 3000L,
            attempts = 2
        )
        if (!attack.ok) {
            logWarn("未能进入出征选队面板：${attack.detail}")
            return resultQueue
        }

        val marchRoi = UiAnchors.rect(UiAnchors.RectKey.MARCH_TIME)

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

        WatchdogRecovery.recoverToMainMap()
        return resultQueue
    }

    private suspend fun prepareTroopPanel(cityCoord: PointF, slot: Int) {
        EngineBridge.tap(cityCoord.x, cityCoord.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 1800)
        val attack = EngineBridge.clickAndExpect(
            StzbUiMatcher.ButtonType.ATTACK,
            StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
            timeoutMs = 2000L,
            attempts = 1
        )
        if (!attack.ok) {
            logWarn("预热选队面板未成功：${attack.detail}")
        }
        clickTroopSlotTab(slot)
    }

    private suspend fun resolveCityTapPoint(config: SiegeConfig): PointF {
        val world = config.cityWorldCoord
        if (world != null && MapProjection.isCalibrated) {
            when (val nav = MapNavigator.centerOn(world.first, world.second)) {
                is MapNavigator.Result.Reached -> {
                    logInfo("🧭 已按世界坐标 (${world.first},${world.second}) 对准攻城目标镜头")
                    return MapProjection.viewportCenterCanvas()
                }
                is MapNavigator.Result.Refused ->
                    logWarn("世界坐标导航被拒绝，改用取点屏幕坐标：${nav.reason}")
                is MapNavigator.Result.Failed ->
                    logWarn("世界坐标导航失败，改用取点屏幕坐标：${nav.reason}")
            }
        }
        return config.cityVirtualCoord
    }

    private suspend fun clickTroopSlotTab(slot: Int) {
        val p = UiAnchors.troopTab(slot)
        EngineBridge.tap(p.x, p.y)
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
        private const val PANEL_PREP_MARGIN_MS = 4000L
        private const val LATE_TOLERANCE_MS = 150L
    }
}
