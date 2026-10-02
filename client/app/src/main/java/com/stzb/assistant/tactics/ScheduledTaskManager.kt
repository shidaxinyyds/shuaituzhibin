package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.service.EngineBridge
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
 *   1. 用户自行编排的定时战术任务（每天 HH:mm 执行巡检/铺路/攻城）；
 *   2. 支持新增、编辑、删除、启用/暂停，SharedPreferences 本地持久化；
 *   3. 后台心跳时钟，到点自动激活对应战术流水线；
 *   4. 同一分钟内只触发一次，避免重复下发。
 *
 * ## 本次修复的三处问题
 *   a. **不再内置任何任务**。原实现在 `resetToDefaults()` 与首次启动的 `loadTasks()`
 *      两处各自硬编码了同样的 3 条"黄金作息"任务，用户一装好就"已被安排好"，
 *      且想清空也没入口。现在首次启动就是空列表，由用户自己建。
 *   b. **任务必须带真实目标**。原先定时铺路/攻城没有目标，触发时直接把
 *      **屏幕正中**当作目标地块去点——那既不是用户意图，还可能在城里造成误操作。
 *      现在目标随任务一起保存；没有目标的任务会被跳过并记录原因，绝不盲点。
 *   c. **触发前检查运行环境**。原先不检查屏幕捕获/触控是否就绪，
 *      未就绪时触发只会静默空转，日志上看不出任何异常。
 */
object ScheduledTaskManager {

    private const val TAG = "ScheduledTaskManager"
    private const val PREFS_NAME = "stzb_scheduled_tasks"
    private const val KEY_TASKS_JSON = "tasks_json"

    data class ScheduledTask(
        val id: String,
        var name: String,
        var timeStr: String,                       // 格式 "HH:mm"
        var taskType: TacticalState.TaskType,
        var isEnabled: Boolean = true,
        /** 目标地块 X（设计画布坐标）。为 null 表示未设目标。 */
        var targetX: Float? = null,
        /** 目标地块 Y（设计画布坐标）。 */
        var targetY: Float? = null,
        /**
         * 目标地块的**世界坐标**（大地图格坐标）。
         * 有值时到点触发会先把镜头对准该格再点，目标不会因镜头移动而失效。
         */
        var targetWorldX: Int? = null,
        var targetWorldY: Int? = null,
        /** 攻城卡秒的基准命中时刻，相对触发时刻的秒数偏移。 */
        var hitOffsetSeconds: Long = 60L,
        /**
         * 除主目标之外的其余目标，编码为 "x,y,wx,wy;x,y,wx,wy"（世界坐标可缺省为空串）。
         *
         * 为什么要额外存：用户在「铺路」页签可能一次点了 5 块地，
         * 而任务原先只记住第一块，到点只铺 1 块——用户看到的是
         * 「我选了 5 块，结果只铺了 1 块」，而且**界面从头到尾没有任何提示**。
         * 主字段 [targetX]/[targetY] 保持不变，以兼容已经存到本地的旧数据。
         */
        var extraTargetsRaw: String = ""
    ) {
        /** 目标点；未设置时为 null，调用方必须据此拒绝执行而不是退化成屏幕中心。 */
        fun targetPoint(): PointF? {
            val x = targetX ?: return null
            val y = targetY ?: return null
            return PointF(x, y)
        }

        fun hasTarget(): Boolean = targetX != null && targetY != null

        /** 目标的世界坐标；未记录时为 null。 */
        fun targetWorld(): Pair<Int, Int>? {
            val x = targetWorldX ?: return null
            val y = targetWorldY ?: return null
            return Pair(x, y)
        }

        /**
         * 完整目标集合（主目标在前，其后是附加目标）。
         *
         * 解析失败的单条会被**跳过**而不是抛异常——本地存储可能被手工改过，
         * 一个坏字符不该让整个定时计划无法加载。
         */
        fun allTargets(): List<TargetPoint> {
            val list = mutableListOf<TargetPoint>()
            val px = targetX
            val py = targetY
            if (px != null && py != null) {
                list.add(TargetPoint(px, py, targetWorldX, targetWorldY))
            }
            for (part in extraTargetsRaw.split(';')) {
                if (part.isBlank()) continue
                val f = part.split(',')
                if (f.size < 2) continue
                val x = f[0].trim().toFloatOrNull() ?: continue
                val y = f[1].trim().toFloatOrNull() ?: continue
                list.add(
                    TargetPoint(
                        x, y,
                        f.getOrNull(2)?.trim()?.toIntOrNull(),
                        f.getOrNull(3)?.trim()?.toIntOrNull()
                    )
                )
            }
            return list
        }
    }

    /** 一个目标地块：设计画布坐标 + 可选世界坐标。 */
    data class TargetPoint(
        val x: Float,
        val y: Float,
        val worldX: Int? = null,
        val worldY: Int? = null
    )

    /**
     * 把目标列表编码为 [ScheduledTask.extraTargetsRaw]（跳过第一块，它由主字段保存）。
     *
     * ⚠️ 坐标必须用 `Locale.US` 格式化。在德语/法语等区域 `"%.2f"` 会输出**逗号**小数点，
     * 而这里用逗号做字段分隔符——直接就会把编码格式破坏掉，
     * 表现为"任务能存进去但重启后目标全丢"。
     */
    fun encodeExtraTargets(points: List<TargetPoint>): String =
        points.drop(1).joinToString(";") { p ->
            val x = String.format(Locale.US, "%.2f", p.x)
            val y = String.format(Locale.US, "%.2f", p.y)
            val wx = p.worldX?.toString() ?: ""
            val wy = p.worldY?.toString() ?: ""
            "$x,$y,$wx,$wy"
        }

    private val tasks = CopyOnWriteArrayList<ScheduledTask>()
    private var tickerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var lastTriggeredMinute = ""
    private var started = false

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
     * 启动调度器。**幂等**：可被多处调用（主程序、屏幕捕获服务、悬浮窗），
     * 重复调用不会重开时钟、也不会清掉用户已配置的任务。
     *
     * 之所以要能在多处调用：原实现只在悬浮窗创建时 init，
     * 于是"用户没点开悬浮胶囊 → 定时任务永远不会触发"，
     * 而定时的意义恰恰是无人值守时执行。
     */
    @Synchronized
    fun init(context: Context, pipeline: TacticalPipeline) {
        if (!started) {
            loadTasks(context)
            started = true
            Log.i(TAG, "定时任务管理器已启动，已加载 ${tasks.size} 项定时计划。")
        }
        startTicker(pipeline)
    }

    fun getTasks(): List<ScheduledTask> = tasks.toList()

    /** 新增任务。铺路/攻城类任务必须带目标点，否则会被触发时拒绝执行。 */
    fun addTask(
        context: Context,
        name: String,
        timeStr: String,
        taskType: TacticalState.TaskType,
        targetX: Float? = null,
        targetY: Float? = null,
        hitOffsetSeconds: Long = 60L,
        targetWorldX: Int? = null,
        targetWorldY: Int? = null,
        extraTargetsRaw: String = ""
    ): ScheduledTask {
        val newTask = ScheduledTask(
            id = "SCH-" + System.currentTimeMillis().toString().takeLast(7),
            name = name,
            timeStr = timeStr,
            taskType = taskType,
            isEnabled = true,
            targetX = targetX,
            targetY = targetY,
            targetWorldX = targetWorldX,
            targetWorldY = targetWorldY,
            hitOffsetSeconds = hitOffsetSeconds,
            extraTargetsRaw = extraTargetsRaw
        )
        tasks.add(newTask)
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "已添加定时任务: [${newTask.timeStr}] ${newTask.name}")
        return newTask
    }

    /** 编辑已有任务。 */
    fun updateTask(
        context: Context,
        id: String,
        name: String,
        timeStr: String,
        taskType: TacticalState.TaskType,
        targetX: Float?,
        targetY: Float?,
        hitOffsetSeconds: Long,
        targetWorldX: Int? = null,
        targetWorldY: Int? = null,
        extraTargetsRaw: String = ""
    ): Boolean {
        val task = tasks.find { it.id == id } ?: return false
        task.name = name
        task.timeStr = timeStr
        task.taskType = taskType
        task.targetX = targetX
        task.targetY = targetY
        task.targetWorldX = targetWorldX
        task.targetWorldY = targetWorldY
        task.hitOffsetSeconds = hitOffsetSeconds
        task.extraTargetsRaw = extraTargetsRaw
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "已更新定时任务: $id")
        return true
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

    /** 清空全部定时计划。取代原先的「恢复默认计划」。 */
    fun clearAll(context: Context) {
        tasks.clear()
        saveTasks(context)
        notifyListeners()
        Log.i(TAG, "已清空全部定时计划。")
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
                            isEnabled = obj.optBoolean("isEnabled", true),
                            targetX = if (obj.has("targetX") && !obj.isNull("targetX")) {
                                obj.getDouble("targetX").toFloat()
                            } else null,
                            targetY = if (obj.has("targetY") && !obj.isNull("targetY")) {
                                obj.getDouble("targetY").toFloat()
                            } else null,
                            targetWorldX = if (obj.has("targetWorldX") && !obj.isNull("targetWorldX")) {
                                obj.getInt("targetWorldX")
                            } else null,
                            targetWorldY = if (obj.has("targetWorldY") && !obj.isNull("targetWorldY")) {
                                obj.getInt("targetWorldY")
                            } else null,
                            hitOffsetSeconds = obj.optLong("hitOffsetSeconds", 60L),
                            extraTargetsRaw = obj.optString("extraTargetsRaw", "")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析定时任务缓存异常，按空计划处理: ${e.message}")
            }
        }
        // 注意：这里**刻意不再预置任何任务**。
        // 定时计划应当完全由用户编排；预置任务会让用户以为是自己设置的。
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
                if (task.targetX != null) put("targetX", task.targetX!!.toDouble()) else put("targetX", JSONObject.NULL)
                if (task.targetY != null) put("targetY", task.targetY!!.toDouble()) else put("targetY", JSONObject.NULL)
                if (task.targetWorldX != null) put("targetWorldX", task.targetWorldX!!) else put("targetWorldX", JSONObject.NULL)
                if (task.targetWorldY != null) put("targetWorldY", task.targetWorldY!!) else put("targetWorldY", JSONObject.NULL)
                put("hitOffsetSeconds", task.hitOffsetSeconds)
                // 附加目标一并持久化：漏掉这一行就会表现为"任务能存进去，重启后目标变少"
                put("extraTargetsRaw", task.extraTargetsRaw)
            }
            array.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_TASKS_JSON, array.toString()).apply()
    }

    private fun startTicker(pipeline: TacticalPipeline) {
        if (tickerJob?.isActive == true) return
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

    /**
     * 把任务的 `"HH:mm"` 解析成"**今天该时刻**"的绝对毫秒时间戳。
     *
     * ## 为什么必须这么做
     * 原先攻城命中时刻是按"**触发那一刻** + 偏移"算的：
     * ```
     * targetBaseHitEpochMs = System.currentTimeMillis() + hitOffsetSeconds * 1000L
     * ```
     * 而轮询是每 15 秒一拍，触发实际发生在那一分钟内的**不确定时刻**。
     * 于是用户设"20:59 触发 + 60 秒偏移"期望 21:00:00 触敌，
     * 实际却可能落在 21:00:03 ~ 21:00:14——**最多 14 秒的抖动**。
     * 对一个毫秒级的"卡秒"功能来说，这个抖动足以让整件事失去意义。
     *
     * 改成按计划时刻本身计算基准，抖动就与命中时刻无关了（只影响"提前量"的长短）。
     *
     * @return 解析失败时返回 null（调用方回退到当前时刻并记录告警）
     */
    private fun scheduledEpochMsFor(task: ScheduledTask): Long? {
        val parts = task.timeStr.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return try {
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, h)
                set(java.util.Calendar.MINUTE, m)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
        } catch (e: Exception) {
            Log.w(TAG, "解析任务时刻 [${task.timeStr}] 失败: ${e.message}")
            null
        }
    }

    private fun triggerTask(task: ScheduledTask, pipeline: TacticalPipeline) {
        // 运行环境未就绪时触发只会空转，必须显式记录，而不是装作执行了。
        if (!EngineBridge.isCaptureReady || !EngineBridge.isTouchReady) {
            Log.w(
                TAG,
                "跳过定时任务「${task.name}」：运行环境未就绪 " +
                    "(屏幕捕获=${EngineBridge.isCaptureReady}, 触控=${EngineBridge.isTouchReady})"
            )
            return
        }

        // 槽位与阈值一律取自当前知识库，使切库/热更真正影响定时任务的行为。
        val defaults = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.tacticalDefaults
        val rules = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules

        when (task.taskType) {
            TacticalState.TaskType.RAID_DEFENSE -> {
                pipeline.startRaidDefense(
                    RaidDefenseFlow.DefenseConfig(
                        counterAttackSquadSlot = defaults.immunityDefaultTroopSlot,
                        enableAudioAlarm = defaults.raidAlarmSound,
                        enableDecisionC = defaults.raidDecisionCAutoCounter
                    )
                )
            }

            TacticalState.TaskType.ROAD_PAVING -> {
                val targets = task.allTargets()
                if (targets.isEmpty()) {
                    // 关键修复：原先这里直接用屏幕中心当目标。
                    Log.w(TAG, "跳过定时铺路「${task.name}」：未设置目标地块。请在编辑任务时先点选地块。")
                    return
                }

                // ⚠️ 两个列表在 RoadPavingFlow 里是**按下标**对应的
                // （worldTargetList.getOrNull(index) 与 targetTileList[index] 配对）。
                // 因此绝不能只把"有世界坐标的那些"压缩成一个短列表——
                // 那会让第 3 块地的世界坐标被套到第 2 块地上，变成"对准 A 却点 B"。
                // 正确做法：要么全部用世界坐标，要么全部退回屏幕坐标，两者都保持一一对应。
                val withWorld = targets.filter { it.worldX != null && it.worldY != null }
                val useWorld = withWorld.isNotEmpty()
                if (useWorld && withWorld.size != targets.size) {
                    Log.w(
                        TAG,
                        "定时铺路「${task.name}」：${targets.size - withWorld.size} 块地缺少世界坐标，" +
                            "为避免坐标错位，本次只处理有世界坐标的 ${withWorld.size} 块。" +
                            "建议到「标定」页签补齐标定。"
                    )
                }
                val chosen = if (useWorld) withWorld else targets
                val tileList = chosen.map { PointF(it.x, it.y) }
                val worldList = if (useWorld) {
                    chosen.map { Pair(it.worldX!!, it.worldY!!) }
                } else {
                    emptyList()
                }

                Log.i(TAG, "定时铺路「${task.name}」下发 ${tileList.size} 块地（世界坐标对齐=${useWorld}）")
                pipeline.startRoadPaving(
                    RoadPavingFlow.PavingConfig(
                        targetTileList = tileList,
                        candidateTroopSlots = defaults.pavingDefaultSlots,
                        minMoraleThreshold = rules.minMoraleForPaving,
                        // 有世界坐标时，铺路流程会先把镜头对准该格再点，目标不会因镜头移动失效
                        worldTargetList = worldList
                    )
                )
            }

            TacticalState.TaskType.SIEGE_SYNC -> {
                val target = task.allTargets().firstOrNull()
                if (target == null) {
                    Log.w(TAG, "跳过定时攻城「${task.name}」：未设置目标城池。请在编辑任务时先点选城池。")
                    return
                }
                // 按**计划时刻**计算命中基准，而不是"现在 + 偏移"。
                // 轮询每 15 秒一拍，用"现在"当基准会把最多 14 秒的不确定抖动
                // 直接算进命中时刻——对毫秒级卡秒来说是致命的。
                val baseEpoch = scheduledEpochMsFor(task)
                if (baseEpoch == null) {
                    Log.w(TAG, "跳过定时攻城「${task.name}」：任务时刻 [${task.timeStr}] 无法解析为绝对时间。")
                    return
                }
                val hitEpoch = baseEpoch + task.hitOffsetSeconds * 1000L
                val nowMs = System.currentTimeMillis()
                val lateMs = nowMs - baseEpoch
                Log.i(
                    TAG,
                    "定时攻城「${task.name}」：计划基准 ${task.timeStr}:00，" +
                        "实际触发晚 ${lateMs}ms；命中时刻按计划基准计算为 $hitEpoch。"
                )

                // 若命中时刻已经过去，**不要**照常下发：卡秒的价值就在那一秒，
                // 晚打既失去意义，还可能在错误的时机把部队送上去。
                // 宁可明确失败，让用户去调偏移或手动处理。
                if (hitEpoch <= nowMs) {
                    val missed = nowMs - hitEpoch
                    Log.w(
                        TAG,
                        "跳过定时攻城「${task.name}」：按计划基准算出的命中时刻已过去 ${missed}ms" +
                            "（偏移 ${task.hitOffsetSeconds}s 相对触发延迟太短）。" +
                            "请增大卡秒偏移，或把任务时间提前。"
                    )
                    return
                }

                pipeline.startSiegeSync(
                    SiegeSyncFlow.SiegeConfig(
                        cityVirtualCoord = PointF(target.x, target.y),
                        targetBaseHitEpochMs = hitEpoch,
                        mainSquadSlot = defaults.siegeMainSquadSlot,
                        demolitionSlots = defaults.siegeDemolitionSlots,
                        latencyCompensationMs = rules.immunityPaddingMs,
                        cityWorldCoord = if (target.worldX != null && target.worldY != null) {
                            Pair(target.worldX, target.worldY)
                        } else null
                    )
                )
            }

            else -> {
                Log.w(TAG, "定时任务「${task.name}」的类型 ${task.taskType} 暂无执行实现，已忽略。")
            }
        }
    }

    fun stop() {
        tickerJob?.cancel()
        tickerJob = null
    }
}
