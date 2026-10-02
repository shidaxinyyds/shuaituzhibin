package com.stzb.assistant.tactics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 战术定时任务中枢 (ScheduledTaskManager)
 * 
 * 核心功能：
 *   1. 用户可配置的定时战术任务入口 (每天 XX:XX 执行巡检/铺路/攻城)；
 *   2. 支持添加、编辑、删除、开启/关闭定时任务，SharedPreferences 本地持久化；
 *   3. 内置后台高精度心跳时钟，到达指定时刻自动唤醒并激活对应战术流水线；
 *   4. 防重触发保护：同一分钟内的任务仅触发一次。
 */
object ScheduledTaskManager {

    private const val TAG = "ScheduledTaskManager"
    private const val PREFS_NAME = "stzb_scheduled_tasks"
    private const val KEY_TASKS_JSON = "tasks_json"

    data class ScheduledTask(
        val id: String,
        var name: String,
        var timeStr: String,          // 格式: "HH:mm"，如 "12:00", "20:00"
        var taskType: TacticalState.TaskType,
        var isEnabled: Boolean = true
    )

    private val tasks = CopyOnWriteArrayList<ScheduledTask>()
    private var tickerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var lastTriggeredMinute = ""

    interface TaskChangeListener {
        fun onTasksUpdated(taskList: List<ScheduledTask>)
    }

    private val listeners = CopyOnWriteArrayList<TaskChangeListener>()

    fun registerListener(listener: TaskChangeListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregisterListener(listener: TaskChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 初始化任务调度器并从持久化存储恢复任务列表
     */
    fun init(context: Context, pipeline: TacticalPipeline) {
        loadTasks(context)
        startTicker(context, pipeline)
        Log.i(TAG, "定时任务管理器已启动，已加载 ${tasks.size} 项定时计划。")
    }

    fun getTasks(): List<ScheduledTask> = tasks.toList()

    fun addTask(context: Context, name: String, timeStr: String, taskType: TacticalState.TaskType) {
        val newTask = ScheduledTask(
            id = "SCH-" + System.currentTimeMillis().toString().takeLast(6),
            name = name,
            timeStr = timeStr,
            taskType = taskType,
            isEnabled = true
        )
        tasks.add(newTask)
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "已添加定时任务: [${newTask.timeStr}] ${newTask.name}")
    }

    fun removeTask(context: Context, id: String) {
        tasks.removeAll { it.id == id }
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "已删除定时任务: $id")
    }

    fun toggleTask(context: Context, id: String, enabled: Boolean) {
        tasks.find { it.id == id }?.isEnabled = enabled
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "定时任务 [$id] 状态更新为: $enabled")
    }

    fun resetToDefaults(context: Context) {
        tasks.clear()
        tasks.add(ScheduledTask("SCH-001", "午间要塞巡视与防守", "12:00", TacticalState.TaskType.RAID_DEFENSE, true))
        tasks.add(ScheduledTask("SCH-002", "晚间同盟攻城集火", "20:00", TacticalState.TaskType.SIEGE_SYNC, true))
        tasks.add(ScheduledTask("SCH-003", "深夜防偷家巡检守护", "00:30", TacticalState.TaskType.RAID_DEFENSE, true))
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "已重置为默认定时作息表")
    }

    private fun notifyListeners() {
        val current = tasks.toList()
        listeners.forEach { it.onTasksUpdated(current) }
    }

    private fun loadTasks(context: Context) {
        tasks.clear()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_TASKS_JSON, null)
        if (!jsonStr.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val typeStr = obj.optString("taskType", TacticalState.TaskType.RAID_DEFENSE.name)
                    val type = try {
                        TacticalState.TaskType.valueOf(typeStr)
                    } catch (e: Exception) {
                        TacticalState.TaskType.RAID_DEFENSE
                    }
                    tasks.add(
                        ScheduledTask(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            timeStr = obj.getString("timeStr"),
                            taskType = type,
                            isEnabled = obj.optBoolean("isEnabled", true)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析定时任务缓存异常，加载默认配置: ${e.message}")
            }
        }

        // 若首次启动无配置，预置 3 个黄金作息定时任务
        if (tasks.isEmpty()) {
            tasks.add(ScheduledTask("SCH-001", "午间要塞巡视与防守", "12:00", TacticalState.TaskType.RAID_DEFENSE, true))
            tasks.add(ScheduledTask("SCH-002", "晚间同盟攻城集火", "20:00", TacticalState.TaskType.SIEGE_SYNC, true))
            tasks.add(ScheduledTask("SCH-003", "深夜防偷家巡检守护", "00:30", TacticalState.TaskType.RAID_DEFENSE, true))
            saveTasks(context)
        }
    }

    private fun saveTasks(context: Context) {
        val array = JSONArray()
        for (task in tasks) {
            val obj = JSONObject().apply {
                put("id", task.id)
                put("name", task.name)
                put("timeStr", task.timeStr)
                put("taskType", task.taskType.name)
                put("isEnabled", task.isEnabled)
            }
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TASKS_JSON, array.toString()).apply()
    }

    private fun startTicker(context: Context, pipeline: TacticalPipeline) {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val currentMinute = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (currentMinute != lastTriggeredMinute) {
                    val matchedTasks = tasks.filter { it.isEnabled && it.timeStr == currentMinute }
                    for (task in matchedTasks) {
                        Log.i(TAG, "⏰ 触发定时战术任务: [${task.timeStr}] ${task.name} (${task.taskType.displayName})")
                        triggerTask(task, pipeline)
                    }
                    if (matchedTasks.isNotEmpty()) {
                        lastTriggeredMinute = currentMinute
                    }
                }
                delay(15000) // 每 15 秒轮询一次时钟
            }
        }
    }

    private fun triggerTask(task: ScheduledTask, pipeline: TacticalPipeline) {
        when (task.taskType) {
            TacticalState.TaskType.RAID_DEFENSE -> {
                pipeline.startRaidDefense(
                    RaidDefenseFlow.DefenseConfig(
                        counterAttackSquadSlot = 1,
                        enableAudioAlarm = true,
                        enableDecisionC = true
                    )
                )
            }
            TacticalState.TaskType.ROAD_PAVING -> {
                val cx = com.stzb.assistant.service.CoordinateTransformer.virtualWidth / 2f
                val cy = com.stzb.assistant.service.CoordinateTransformer.virtualHeight / 2f
                pipeline.startRoadPaving(
                    RoadPavingFlow.PavingConfig(
                        targetTileList = listOf(android.graphics.PointF(cx, cy)),
                        candidateTroopSlots = listOf(1, 2, 3),
                        minMoraleThreshold = 100
                    )
                )
            }
            TacticalState.TaskType.SIEGE_SYNC -> {
                val cx = com.stzb.assistant.service.CoordinateTransformer.virtualWidth / 2f
                val cy = com.stzb.assistant.service.CoordinateTransformer.virtualHeight / 2f
                pipeline.startSiegeSync(
                    SiegeSyncFlow.SiegeConfig(
                        cityVirtualCoord = android.graphics.PointF(cx, cy),
                        targetBaseHitEpochMs = System.currentTimeMillis() + 60 * 1000L,
                        mainSquadSlot = 1
                    )
                )
            }
            else -> {}
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
