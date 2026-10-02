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
    val siegeSyncFlow = SiegeSyncFlow(this)
    val raidDefenseFlow = RaidDefenseFlow(context, this)

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

    /**
     * 启动同盟集火攻城卡秒排队任务
     */
    fun startSiegeSync(config: SiegeSyncFlow.SiegeConfig) {
        launchTask(TacticalState.TaskType.SIEGE_SYNC) {
            siegeSyncFlow.execute(config)
        }
    }

    /**
     * 启动深夜敌袭巡检与决策 C 自动反击守护任务
     */
    fun startRaidDefense(config: RaidDefenseFlow.DefenseConfig) {
        launchTask(TacticalState.TaskType.RAID_DEFENSE) {
            raidDefenseFlow.startPatrol(config)
        }
    }

    /**
     * 仅停止当前正在执行的任务（不影响其它已登记的后台守护）
     */
    fun stopCurrentTask() {
        val prev = activeTaskType
        when (prev) {
            TacticalState.TaskType.ROAD_PAVING -> roadPavingFlow.stop()
            TacticalState.TaskType.IMMUNITY_BREAK -> immunityBreakFlow.stop()
            TacticalState.TaskType.SIEGE_SYNC -> siegeSyncFlow.stop()
            TacticalState.TaskType.RAID_DEFENSE -> raidDefenseFlow.stop()
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
