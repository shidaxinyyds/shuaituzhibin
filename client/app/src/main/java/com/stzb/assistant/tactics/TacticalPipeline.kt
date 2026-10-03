package com.stzb.assistant.tactics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 战术流水线总调度中枢 (TacticalPipeline)
 * 
 * 借鉴 MaaFramework 管道调度模型，提供全业务战术任务生命周期管理：
 *   1. 统一管理铺路、卡免破免、集火攻城、夜袭防守四大核心战术流；
 *   2. 支持跨协程安全启停、暂停与状态广播；
 *   3. 维护任务执行日志流水与对外监听回调。
 */
class TacticalPipeline(private val context: Context) : TacticalState.TacticalEventListener {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var currentJob: Job? = null
    private var activeTaskType: TacticalState.TaskType? = null

    val roadPavingFlow = RoadPavingFlow(this)
    val immunityBreakFlow = ImmunityBreakFlow(this)
    val siegeSyncFlow = SiegeSyncFlow(context, this)
    val raidDefenseFlow = RaidDefenseFlow(context, this)
    val nightSentinelFlow: NightSentinelFlow get() = raidDefenseFlow
    val dailyLogisticsFlow = DailyLogisticsFlow(context, this)
    val accurateFarmingFlow = AccurateFarmingFlow(context, this)

    private val listeners = CopyOnWriteArrayList<TacticalState.TacticalEventListener>()
    private val logHistory = CopyOnWriteArrayList<TacticalState.TacticalLog>()

    /**
     * 当前正在执行的任务类型；null 表示空闲。
     * 无人托管用它判断"要不要下发新任务"，避免与正在跑的流水线抢控制权。
     */
    val currentTaskType: TacticalState.TaskType?
        get() = activeTaskType

    fun registerListener(listener: TacticalState.TacticalEventListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: TacticalState.TacticalEventListener) {
        listeners.remove(listener)
    }

    fun getLogHistory(): List<TacticalState.TacticalLog> = logHistory

    /**
     * 统一的"启动一个受管任务"入口。
     *
     * ## 为什么必须有这个函数
     * 原先四个 `startXxx()` 各自写成：
     * ```
     * stopCurrentTask()
     * activeTaskType = <TYPE>
     * currentJob = scope.launch { ...flow... }
     * ```
     * 问题在于**任务正常结束后 `activeTaskType` 永远不会被复位**——
     * 没有任何完成回调去清它。于是 `currentTaskType` 会永久停留在一个非 null 值上。
     *
     * 这会直接掐死「无人托管」：AutoPilot 用 `currentTaskType != null` 判断
     * "是否有任务在跑"，一旦第一个任务跑完，它就**永远认为还在忙**，
     * 此后再也不下发第二个任务，只是不断打印"保持观察"。
     *
     * 现在所有启动路径都必须走这里：任务结束（正常/异常/被取消）时由
     * [Job.invokeOnCompletion] 复位状态与广播，并**用 Job 身份做校验**，
     * 避免把后续新任务的状态误清掉。
     *
     * @param block 任务主体，内部需自行处理异常（本函数也会兜底记录并广播 FAILED）
     */
    private fun launchTask(type: TacticalState.TaskType, block: suspend () -> Unit) {
        stopCurrentTask()
        activeTaskType = type

        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e // 主动取消不算异常
            } catch (e: Exception) {
                Log.e(TAG, "任务 [${type.displayName}] 异常退出: ${e.message}", e)
                onStatusChanged(type, TacticalState.Status.FAILED, "异常退出: ${e.message}")
            }
        }
        currentJob = job

        job.invokeOnCompletion { cause ->
            // 只有"当前登记的仍是这个 Job"时才复位；
            // 若期间已经启动了新任务，currentJob 已被替换，这里必须让位。
            synchronized(this) {
                if (currentJob === job) {
                    currentJob = null
                    val finished = activeTaskType
                    activeTaskType = null
                    if (cause == null && finished != null) {
                        // 正常跑完：广播一次，让胶囊与日志状态归位（此前完全没有这一步）
                        onStatusChanged(finished, TacticalState.Status.COMPLETED, "任务流程已结束。")
                    }
                }
            }
        }
    }

    /**
     * 启动自动铺路任务
     */
    fun startRoadPaving(config: RoadPavingFlow.PavingConfig) {
        launchTask(TacticalState.TaskType.ROAD_PAVING) {
            roadPavingFlow.startPaving(config)
        }
    }

    /**
     * 启动极限卡免 / 压秒破免任务
     */
    fun startImmunityBreak(config: ImmunityBreakFlow.ImmunityConfig) {
        launchTask(TacticalState.TaskType.IMMUNITY_BREAK) {
            immunityBreakFlow.execute(config)
        }
    }

    data class TimedDispatchConfig(
        val taskName: String,
        val bookmarkName: String? = null,
        val targetTileCoord: android.graphics.PointF,
        val targetWorldCoord: Pair<Int, Int>? = null,
        val actionType: com.stzb.assistant.ocr.StzbUiMatcher.ButtonType = com.stzb.assistant.ocr.StzbUiMatcher.ButtonType.ATTACK,
        val troopSlot: Int = 1,
        val latencyCompensationMs: Long = 110L
    )

    /**
     * 启动挂牌到点出征 / 扫荡 / 屯田 / 驻守定时任务
     */
    fun startTimedDispatch(config: TimedDispatchConfig) {
        launchTask(TacticalState.TaskType.TACTICAL_SCHEDULE) {
            executeTimedDispatch(config)
        }
    }

    private suspend fun executeTimedDispatch(config: TimedDispatchConfig): Boolean {
        onStatusChanged(
            TacticalState.TaskType.TACTICAL_SCHEDULE,
            TacticalState.Status.RUNNING,
            "开始执行定时任务 [${config.taskName}]，目标动作: ${config.actionType.primaryKeyword}，槽位: ${config.troopSlot}"
        )
        onLogEmitted(TacticalState.TacticalLog(
            TacticalState.TaskType.TACTICAL_SCHEDULE,
            "INFO",
            "🚀 定时战术启动: ${config.taskName} (动作=${config.actionType.primaryKeyword}, 部队=${config.troopSlot}队)"
        ))

        // 1. 确保大地图就绪
        WatchdogRecovery.recoverToMainMap()

        // 2. 目标对准：官方书签优先 (0 漂移)，其次世界坐标导航，最后屏幕坐标
        var tapPoint: android.graphics.PointF = config.targetTileCoord
        if (!config.bookmarkName.isNullOrBlank()) {
            onLogEmitted(TacticalState.TacticalLog(
                TacticalState.TaskType.TACTICAL_SCHEDULE,
                "INFO",
                "🔖 正在通过官方书签检索 [${config.bookmarkName}] 实施 0 漂移对准..."
            ))
            when (val nav = com.stzb.assistant.service.MapNavigator.jumpByBookmark(config.bookmarkName)) {
                is com.stzb.assistant.service.MapNavigator.Result.Reached -> {
                    tapPoint = com.stzb.assistant.service.MapProjection.viewportCenterCanvas()
                    onLogEmitted(TacticalState.TacticalLog(
                        TacticalState.TaskType.TACTICAL_SCHEDULE,
                        "INFO",
                        "🔖 官方书签 [${config.bookmarkName}] 对准完成，锁定镜头中心"
                    ))
                }
                is com.stzb.assistant.service.MapNavigator.Result.Refused -> {
                    onLogEmitted(TacticalState.TacticalLog(
                        TacticalState.TaskType.TACTICAL_SCHEDULE,
                        "WARN",
                        "书签跳转被拒绝: ${nav.reason}，回退使用坐标对准"
                    ))
                }
                is com.stzb.assistant.service.MapNavigator.Result.Failed -> {
                    onLogEmitted(TacticalState.TacticalLog(
                        TacticalState.TaskType.TACTICAL_SCHEDULE,
                        "WARN",
                        "书签跳转失败: ${nav.reason}，回退使用坐标对准"
                    ))
                }
            }
        }

        if (tapPoint == config.targetTileCoord && config.targetWorldCoord != null && com.stzb.assistant.service.MapProjection.isCalibrated) {
            val (wx, wy) = config.targetWorldCoord
            when (val nav = com.stzb.assistant.service.MapNavigator.centerOn(wx, wy)) {
                is com.stzb.assistant.service.MapNavigator.Result.Reached -> {
                    tapPoint = com.stzb.assistant.service.MapProjection.viewportCenterCanvas()
                    onLogEmitted(TacticalState.TacticalLog(
                        TacticalState.TaskType.TACTICAL_SCHEDULE,
                        "INFO",
                        "🧭 已按世界坐标 ($wx, $wy) 对准目标地块镜头中心"
                    ))
                }
                is com.stzb.assistant.service.MapNavigator.Result.Refused ->
                    onLogEmitted(TacticalState.TacticalLog(TacticalState.TaskType.TACTICAL_SCHEDULE, "WARN", "世界坐标导航被拒绝: ${nav.reason}"))
                is com.stzb.assistant.service.MapNavigator.Result.Failed ->
                    onLogEmitted(TacticalState.TacticalLog(TacticalState.TaskType.TACTICAL_SCHEDULE, "WARN", "世界坐标导航失败: ${nav.reason}"))
            }
        }

        // 3. 点击地块唤起操作轮盘
        com.stzb.assistant.service.EngineBridge.tap(tapPoint.x, tapPoint.y)
        com.stzb.assistant.service.EngineBridge.waitForState(com.stzb.assistant.ocr.StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)

        // 4. 点击指定动作按键 (出征/扫荡/屯田/驻守) 并等待出征面板
        val actionRes = com.stzb.assistant.service.EngineBridge.clickAndExpect(
            config.actionType,
            com.stzb.assistant.ocr.StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
            timeoutMs = 3000L,
            attempts = 2
        )
        if (!actionRes.ok) {
            onLogEmitted(TacticalState.TacticalLog(
                TacticalState.TaskType.TACTICAL_SCHEDULE,
                "WARN",
                "未能进入出征选队面板：${actionRes.detail}"
            ))
            WatchdogRecovery.recoverToMainMap()
            onStatusChanged(
                TacticalState.TaskType.TACTICAL_SCHEDULE,
                TacticalState.Status.FAILED,
                "未能点击【${config.actionType.primaryKeyword}】按键"
            )
            return false
        }

        // 5. 选中指定部队槽位
        val tabPoint = com.stzb.assistant.service.UiAnchors.troopTab(config.troopSlot)
        com.stzb.assistant.service.EngineBridge.tap(tabPoint.x, tabPoint.y)
        com.stzb.assistant.service.EngineBridge.humanDelay(300, 600)

        // 6. 确认出征
        val confirmRes = com.stzb.assistant.service.EngineBridge.clickButtonDiagnosed(com.stzb.assistant.ocr.StzbUiMatcher.ButtonType.CONFIRM)
        if (!confirmRes.ok) {
            onLogEmitted(TacticalState.TacticalLog(
                TacticalState.TaskType.TACTICAL_SCHEDULE,
                "WARN",
                "点击【确定】按键未成功：${confirmRes.detail}"
            ))
            WatchdogRecovery.recoverToMainMap()
            onStatusChanged(
                TacticalState.TaskType.TACTICAL_SCHEDULE,
                TacticalState.Status.FAILED,
                "未能完成出征确认"
            )
            return false
        }

        onLogEmitted(TacticalState.TacticalLog(
            TacticalState.TaskType.TACTICAL_SCHEDULE,
            "INFO",
            "✅ 定时任务 [${config.taskName}] 派发成功！动作: ${config.actionType.primaryKeyword}，第 ${config.troopSlot} 队已出征。"
        ))
        onStatusChanged(
            TacticalState.TaskType.TACTICAL_SCHEDULE,
            TacticalState.Status.COMPLETED,
            "定时任务 [${config.taskName}] 已顺利派发出征"
        )
        return true
    }

    /**
     * 启动同盟集火攻城卡秒排队任务
     */
    fun startSiegeSync(config: SiegeSyncFlow.SiegeConfig) {
        launchTask(TacticalState.TaskType.SIEGE_SYNC) {
            siegeSyncFlow.execute(config)
        }
    }

    /**
     * 根据同盟邮件法令卡片一键启动双压秒全勤攻城
     */
    fun startSiegeFromMail(mailPlan: AllianceMailParser.SiegeMailPlan) {
        val screenCenter = android.graphics.PointF(
            com.stzb.assistant.service.CoordinateTransformer.virtualWidth / 2f,
            com.stzb.assistant.service.CoordinateTransformer.virtualHeight / 2f
        )
        val config = SiegeSyncFlow.SiegeConfig(
            cityVirtualCoord = screenCenter,
            targetBaseHitEpochMs = mailPlan.targetHitEpochMs,
            mainSquadSlot = 1,
            demolitionSlots = listOf(2, 3),
            demolitionOffsetSec = mailPlan.demolitionOffsetSec,
            cityWorldCoord = mailPlan.targetWorldCoord,
            fortressWorldCoord = mailPlan.fortressWorldCoord,
            fortressName = mailPlan.fortressName,
            enablePreFlight30MinCheck = true
        )
        startSiegeSync(config)
    }

    /**
     * 启动深夜敌袭巡检与决策 C 自动反击守护任务
     */
    fun startRaidDefense(config: RaidDefenseFlow.DefenseConfig) {
        launchTask(TacticalState.TaskType.NIGHT_SENTINEL) {
            raidDefenseFlow.startPatrol(config)
        }
    }

    /**
     * 启动暗夜天眼哨兵守护流
     */
    fun startNightSentinel(config: RaidDefenseFlow.DefenseConfig) {
        launchTask(TacticalState.TaskType.NIGHT_SENTINEL) {
            raidDefenseFlow.startPatrol(config)
        }
    }

    /**
     * 启动单账号日常后勤全托管流程 (税收/伤兵征兵/体力防溢/城建升级)
     */
    fun startDailyLogistics(config: DailyLogisticsFlow.LogisticsConfig) {
        launchTask(TacticalState.TaskType.LOGISTICS_STEWARD) {
            dailyLogisticsFlow.execute(config)
        }
    }

    /**
     * 启动全自动屯田打铁管家流程 (高等级资源地屯田/3令防溢出/工坊宝物锻造)
     */
    fun startAccurateFarming(config: AccurateFarmingFlow.FarmingConfig) {
        launchTask(TacticalState.TaskType.FARMING_STEWARD) {
            accurateFarmingFlow.execute(config)
        }
    }

    /**
     * 仅停止当前正在执行的任务（不影响其它已登记的后台守护）
     */
    fun stopCurrentTask() {
        val prev = activeTaskType
        when (prev) {
            TacticalState.TaskType.ROAD_PAVING -> roadPavingFlow.stop()
            TacticalState.TaskType.IMMUNITY_BREAK,
            TacticalState.TaskType.TACTICAL_SCHEDULE -> immunityBreakFlow.stop()
            TacticalState.TaskType.SIEGE_SYNC -> siegeSyncFlow.stop()
            TacticalState.TaskType.RAID_DEFENSE,
            TacticalState.TaskType.NIGHT_SENTINEL -> raidDefenseFlow.stop()
            TacticalState.TaskType.LOGISTICS_STEWARD -> dailyLogisticsFlow.stop()
            TacticalState.TaskType.FARMING_STEWARD -> accurateFarmingFlow.stop()
            else -> {}
        }
        currentJob?.cancel()
        currentJob = null
        activeTaskType = null
        if (prev != null) {
            onStatusChanged(prev, TacticalState.Status.INTERRUPTED, "当前任务已停止。")
        }
    }

    /**
     * 停止全部任务、后台守护与报警（总控急停）
     */
    fun stopAll() {
        roadPavingFlow.stop()
        immunityBreakFlow.stop()
        siegeSyncFlow.stop()
        raidDefenseFlow.stop()
        dailyLogisticsFlow.stop()
        accurateFarmingFlow.stop()
        AlarmRinger.stopAlarm(context)

        currentJob?.cancel()
        currentJob = null

        val prev = activeTaskType
        activeTaskType = null
        if (prev != null) {
            onStatusChanged(prev, TacticalState.Status.INTERRUPTED, "全部战术流水线已由总控停止。")
        }
    }

    override fun onStatusChanged(taskType: TacticalState.TaskType, status: TacticalState.Status, detail: String) {
        Log.i(TAG, "战术状态变迁: [${taskType.displayName}] -> ${status.desc} ($detail)")
        for (l in listeners) {
            try {
                l.onStatusChanged(taskType, status, detail)
            } catch (e: Exception) {
                Log.e(TAG, "广播状态异常: ${e.message}")
            }
        }
    }

    override fun onLogEmitted(log: TacticalState.TacticalLog) {
        logHistory.add(log)
        if (logHistory.size > 200) {
            logHistory.removeAt(0)
        }
        for (l in listeners) {
            try {
                l.onLogEmitted(log)
            } catch (e: Exception) {
                Log.e(TAG, "广播日志异常: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "TacticalPipeline"

        @Volatile
        private var instance: TacticalPipeline? = null

        fun getInstance(context: Context): TacticalPipeline {
            return instance ?: synchronized(this) {
                instance ?: TacticalPipeline(context.applicationContext).also { instance = it }
            }
        }
    }
}
