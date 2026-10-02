package com.stzb.assistant.tactics

import android.content.Context
import android.util.Log
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
     * 启动自动铺路任务
     */
    fun startRoadPaving(config: RoadPavingFlow.PavingConfig) {
        stopCurrentTask()
        activeTaskType = TacticalState.TaskType.ROAD_PAVING
        currentJob = scope.launch {
            roadPavingFlow.startPaving(config)
        }
    }

    /**
     * 启动极限卡免 / 压秒破免任务
     */
    fun startImmunityBreak(config: ImmunityBreakFlow.ImmunityConfig) {
        stopCurrentTask()
        activeTaskType = TacticalState.TaskType.IMMUNITY_BREAK
        currentJob = scope.launch {
            immunityBreakFlow.execute(config)
        }
    }

    /**
     * 启动同盟集火攻城卡秒排队任务
     */
    fun startSiegeSync(config: SiegeSyncFlow.SiegeConfig) {
        stopCurrentTask()
        activeTaskType = TacticalState.TaskType.SIEGE_SYNC
        currentJob = scope.launch {
            siegeSyncFlow.execute(config)
        }
    }

    /**
     * 启动深夜敌袭巡检与决策 C 自动反击守护任务
     */
    fun startRaidDefense(config: RaidDefenseFlow.DefenseConfig) {
        stopCurrentTask()
        activeTaskType = TacticalState.TaskType.RAID_DEFENSE
        currentJob = scope.launch {
            raidDefenseFlow.startPatrol(config)
        }
    }

    /**
     * 安全停止当前执行的任务
     */
    fun stopCurrentTask() {
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
            onStatusChanged(prev, TacticalState.Status.INTERRUPTED, "当前战术流水线已由总控停止。")
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
