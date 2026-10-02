package com.stzb.assistant.tactics

import android.graphics.PointF
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
        val latencyCompensationMs: Long = 100L,
        /**
         * 目标城池的**世界坐标**（大地图格坐标）。提供且地图投影已标定时，
         * 流程会先把镜头对准该格再点镜头中心，目标不会因镜头移动而失效。
         */
        val cityWorldCoord: Pair<Int, Int>? = null
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

            // 3.5 可行性预检：若某队的"最晚出征时刻"已经过去，它**不可能**按时触敌。
            //
            // 为什么必须拦：原先会照常往下走，于是 waitMs 为负、时刻表打出
            // "倒计时: -90s"，部队仍在错误的时机被送出去——卡秒静默失败，
            // 而且可能因此在对自己不利的时机开战。宁可明确失败并说清还差多少。
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

                // 提前一段时间打开出征面板做好准备。
                // ⚠️ 这个余量必须覆盖 `prepareTroopPanel` + `prepareButtonTap` 的全部耗时：
                //    前者要等待地块菜单与选队面板出现（各有 1.8~2 秒超时），
                //    后者要抓屏并定位【确定出征】。余量不够的后果是**直接晚打**，
                //    而晚打在集火里可能比不打更糟。
                if (waitMs > PANEL_PREP_MARGIN_MS) {
                    delay(waitMs - PANEL_PREP_MARGIN_MS)
                }

                if (!isRunning.get()) break

                // 打开目标城池出征面板并切到该部队
                prepareTroopPanel(config.cityVirtualCoord, currentTask.troopSlot)

                // **先架枪**：面板已就绪，把【确定出征】的触控点现在定位好。
                // 定位要抓屏 + 识别/匹配，耗时几十到几百毫秒；若放到精确等待之后再做，
                // 这段耗时会整个算进击发误差里（而且完全没被补偿）。
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

                // 毫秒级扣动扳机：只做一次手势派发（定位已完成）
                val fired = EngineBridge.firePreparedTap(prepared)
                val realFired = fired.dispatchEpochMs
                val errorMs = realFired - currentTask.optimalDispatchEpochMs
                if (fired.dispatched) {
                    logTactic("🚀 ${currentTask.roleName} 击发成功！绝对时刻: $realFired，误差: ${errorMs}ms")
                    if (errorMs > LATE_TOLERANCE_MS) {
                        // 晚到超出容忍范围时必须说清楚，而不是把"晚了 800ms"当成成功。
                        // 最常见的原因是面板准备吃掉了余量（见 PANEL_PREP_MARGIN_MS）。
                        logWarn(
                            "⚠️ ${currentTask.roleName} 晚打 ${errorMs}ms（容忍 ${LATE_TOLERANCE_MS}ms）。" +
                                "多半是出征面板准备耗时超过了预留余量；" +
                                "可把任务时间提前更多，或减少队伍数量。"
                        )
                    }
                } else {
                    // 原实现完全不看返回值就打印"击发成功"，把"根本没点中"包装成"卡秒精准"。
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
     * 测量并计算全部队列队伍的耗时与触发时刻
     */
    private suspend fun measureAllDispatches(
        config: SiegeConfig,
        queue: PriorityQueue<ScheduledDispatch>
    ): PriorityQueue<ScheduledDispatch> {
        val resultQueue = PriorityQueue<ScheduledDispatch>()

        // 点击目标城池并打开出征面板
        // 已标定世界坐标时，先把镜头对准该格再点镜头中心，目标不会因镜头移动而失效。
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

        // 行军耗时文本区改用统一锚点表（原先是写死的"右下角 360x160"）
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

        // 测算完毕，先关闭面板回到大地图
        WatchdogRecovery.recoverToMainMap()
        return resultQueue
    }

    private suspend fun prepareTroopPanel(cityCoord: PointF, slot: Int) {
        EngineBridge.tap(cityCoord.x, cityCoord.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 1800)
        // 预热路径同样确认面板出现，失败只告警（预热不成功时后面还会再试一次）
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

    /**
     * 解析"该点哪个屏幕坐标才能点到目标城池"。
     *
     * 有世界坐标且地图投影已标定时：先把镜头对准该格，然后点镜头中心——
     * "点哪里"是即时算出来的，不依赖取点时的旧屏幕坐标。
     * 否则回退到取点屏幕坐标（保持原有行为）。
     */
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
        // 统一锚点表，不再按 1280 宽画布写死 220/140/160
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

        /**
         * 提前打开出征面板的余量。
         *
         * 必须覆盖 `prepareTroopPanel`（等待地块菜单 + 选队面板，各有 1.8~2 秒超时）
         * 与 `prepareButtonTap`（抓屏 + 定位【确定出征】）的**全部**耗时。
         * 余量不足的后果是直接晚打——在集火里晚打可能比不打更糟。
         */
        private const val PANEL_PREP_MARGIN_MS = 4000L

        /** 击发时刻的可容忍迟到量；超过它必须显式告警，而不是当成"卡秒成功"。 */
        private const val LATE_TOLERANCE_MS = 150L
    }
}
