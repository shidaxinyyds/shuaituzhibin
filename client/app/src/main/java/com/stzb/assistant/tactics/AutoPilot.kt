package com.stzb.assistant.tactics

import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.knowledge.KnowledgeBaseManager
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 无人托管自治中枢 (AutoPilot)
 *
 * 这是「智能巡查」按钮应有的真实语义：**点一下之后，程序自己感知战场并执行**——
 *   1. 持续抓帧 → 判定当前游戏场景（大地图 / 地块菜单 / 出征面板 / 弹窗）；
 *   2. 出现干扰弹窗时自动收起，迷航时自动回到大地图；
 *   3. 大地图空闲且没有任务在跑时，按优先级自主下发已配置的战术意图
 *      （巡检守护 → 铺路翻地 → 集火攻城），一次只跑一个；
 *   4. 巡航节奏跟随知识库的昼夜窗口（夜间放慢），阈值为知识库配置。
 *
 * ## 为什么启动要设前置门槛
 * 托管的核心是「自己判断该不该动手」。本项目历史上所有"点击不生效"的根因，
 * 最终都指向**感知层不可用**（native OCR 走空桩，`classifyGameState` 恒为 UNKNOWN）。
 * 在感知不可用的情况下开启托管，只会得到一个"永远在大地图之外、不断乱点"的机器。
 * 因此 [start] 会明确拒绝启动并说明原因，而不是让用户以为托管在工作。
 * 一旦构建期链接了 ncnn/OpenCV（见 cpp/CMakeLists.txt），感知恢复，
 * 下面的循环即可完整运转——不需要再改这里的任何逻辑。
 */
object AutoPilot {

    private const val TAG = "AutoPilot"

    /** 用户配置的托管意图。 */
    data class Intents(
        val raidDefense: Boolean = false,
        val pavingTargets: List<PointF> = emptyList(),
        /** 与 [pavingTargets] 一一对应的世界坐标（未标定时为空）。 */
        val pavingWorldTargets: List<Pair<Int, Int>> = emptyList(),
        val siegeTarget: PointF? = null,
        /** 攻城目标的世界坐标（未标定时为 null）。 */
        val siegeWorldTarget: Pair<Int, Int>? = null,
        val siegeHitEpochMs: Long = 0L,
        val dailyLogistics: Boolean = false,
        val farmingTarget: PointF? = null,
        val farmingWorldTarget: Pair<Int, Int>? = null,
        val farmingBookmark: String? = null,
        val farmingTroopSlot: Int = 2,
        val autoFarmingEnabled: Boolean = false,
        val autoLevelingEnabled: Boolean = false,
        val levelingTarget: PointF? = null,
        val levelingWorldTarget: Pair<Int, Int>? = null,
        val levelingBookmark: String? = null,
        val levelingSlotA: Int = 2,
        val levelingSlotB: Int = 3,
        val levelingTileLevel: Int = 7
    )

    /** 启动失败的原因，供 UI 原样展示。 */
    sealed class StartResult {
        object Started : StartResult()
        data class Rejected(val reason: String) : StartResult()
    }

    interface Listener {
        fun onPilotChanged(running: Boolean)
        fun onPilotTick(stateDesc: String, action: String)
    }

    @Volatile
    var isRunning: Boolean = false
        private set

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private val listeners = CopyOnWriteArrayList<Listener>()

    // ---- 托管运行期状态 ----
    //
    // 这些状态原先写成 loop() 里的局部变量（`raidStarted` / `pavingSignature` / `siegeDone`），
    // 有两个问题：
    //   1. `raidStarted` 与 `siegeDone` 一旦置位**永不复位**——巡检守护异常退出或被
    //      「停止当前」停掉之后，夜间防护就静默消失了，托管再也不会把它拉起来；
    //      攻城同理，用户改了目标或命中时刻也不会重新下发。
    //   2. 状态更新散落在循环各处，容易漏改。
    // 现在统一为成员状态、集中更新，并在 start() 时重置。

    /** 巡检守护是否已由本托管拉起。 */
    private var raidDispatched = false

    /** 巡检守护最近一次拉起的时间戳，用于重拉冷却。 */
    private var raidLastStartAtMs = 0L

    /** 已下发的铺路目标签名（目标集合一变就重新下发）。 */
    private var pavingSignature = ""

    /** 已下发的攻城签名（目标或命中时刻一变就重新下发）。 */
    private var siegeSignature = ""

    /** 已下发的屯田目标签名。 */
    private var farmingSignature = ""

    /** 已下发的练级流水线目标签名。 */
    private var levelingSignature = ""

    /** 后勤任务最近一次执行时间戳。 */
    private var logisticsLastRunAtMs = 0L

    /** 巡检守护距上次拉起不足该时长时不再重拉，避免巡检自身反复失败形成热循环。 */
    private const val RAID_RESTART_COOLDOWN_MS = 60_000L

    /** 攻城提前量：到达命中时刻前多久开始下发。 */
    private const val SIEGE_LEAD_MS = 60_000L

    /** 后勤全托管周期执行间隔 (默认 30 分钟)。 */
    private const val LOGISTICS_INTERVAL_MS = 1800_000L

    /** 每次 start() 重置运行期状态，避免上一次运行的残留影响本次。 */
    private fun resetRuntimeState() {
        raidDispatched = false
        raidLastStartAtMs = 0L
        pavingSignature = ""
        siegeSignature = ""
        farmingSignature = ""
        levelingSignature = ""
        logisticsLastRunAtMs = 0L
    }

    fun registerListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun unregisterListener(l: Listener) {
        listeners.remove(l)
    }

    /**
     * 启动无人托管。
     *
     * @param intentsProvider 每次循环重新读取意图，因此用户中途点选新地块会立即生效。
     */
    fun start(pipeline: TacticalPipeline, intentsProvider: () -> Intents): StartResult {
        if (isRunning) return StartResult.Started

        if (!EngineBridge.isCaptureReady) {
            return StartResult.Rejected("屏幕捕获未开启：请先在主界面完成第 4 项「启动屏幕捕获」。")
        }
        if (!EngineBridge.isTouchReady) {
            return StartResult.Rejected("触控通道未就绪：请先在主界面完成第 2 项「开启无障碍服务通道」。")
        }
        if (!OcrManager.isEngineAvailable) {
            return StartResult.Rejected(
                "感知层不可用，无法托管：" +
                    (OcrManager.unavailableReason
                        ?: "本地 OCR 引擎未编译（构建期缺少 ncnn/OpenCV）。") +
                    " 缺少屏幕文字识别时，程序无法判断当前处于哪个界面，" +
                    "开启托管只会变成盲点，因此这里直接拒绝启动。"
            )
        }

        isRunning = true
        resetRuntimeState()
        job = scope.launch { loop(pipeline, intentsProvider) }
        notifyChanged()
        return StartResult.Started
    }

    fun stop(pipeline: TacticalPipeline? = null) {
        if (!isRunning && job == null) return
        isRunning = false
        job?.cancel()
        job = null
        notifyChanged()
        pipeline?.let { emit(it, "WARN", "无人托管已停止。") }
    }

    // ==========================================================
    // 主循环
    // ==========================================================

    private suspend fun loop(pipeline: TacticalPipeline, intentsProvider: () -> Intents) {
        emit(pipeline, "INFO", "🛡️ 无人托管已启动：持续感知战场 → 自动收起干扰 → 自主执行已配置目标。")

        // loop 是独立的 suspend 函数，不继承 launch 的 CoroutineScope 接收者，
        // 因此这里用 coroutineContext.isActive（suspend 函数内可用）而非裸 isActive。
        while (coroutineContext.isActive && isRunning) {
            try {
                val intents = intentsProvider()
                val state = EngineBridge.detectGameState()
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())

                when {
                    // 1. 大地图空闲：按优先级自主下发
                    state == StzbUiMatcher.GameState.MAIN_MAP -> {
                        val busy = pipeline.currentTaskType != null
                        if (busy) {
                            tick("大地图（有任务在跑）", "保持观察，不重复下发")
                        } else {
                            val action = dispatchWhenIdle(pipeline, intents)
                            val msg = when (action.kind) {
                                ActionKind.RAID -> "拉起夜间巡检守护"
                                ActionKind.PAVING -> "下发铺路翻地（${intents.pavingTargets.size} 块）"
                                ActionKind.SIEGE -> "下发集火攻城"
                                ActionKind.FARMING -> "下发高等级地块屯田与打铁巡检"
                                ActionKind.LOGISTICS -> "下发单账号日常后勤全托管"
                                ActionKind.LEVELING -> "下发二三队低损速升40级流水线"
                                ActionKind.NONE -> action.desc
                            }
                            tick("大地图空闲", msg)
                        }
                    }

                    // 2. 弹出层/次级面板/未知场景：交给看门狗自愈回大地图
                    state == StzbUiMatcher.GameState.TILE_ACTION_MENU ||
                        state == StzbUiMatcher.GameState.DEFENDER_INFO_DIALOG ||
                        state == StzbUiMatcher.GameState.COORDINATE_SEARCH_DIALOG ||
                        state == StzbUiMatcher.GameState.FORTRESS_BUILD_DIALOG -> {
                        tick(stateLabel(state), "检测到浮层，执行自愈收起")
                        WatchdogRecovery.recoverToMainMap(maxAttempts = 2)
                    }

                    // 3. 出征选队面板：正在执行的流程会自己完成，托管不插手
                    state == StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG -> {
                        tick("出征选队面板", "流程推进中，托管让行")
                    }

                    // 4. 敌袭告警：只关心巡检守护，不在这里顺手派发铺路/攻城
                    state == StzbUiMatcher.GameState.ALERT_RAID_ACTIVE -> {
                        val started = ensureRaidPatrol(pipeline, intents, System.currentTimeMillis())
                        tick("⚠️ 敌袭告警", if (started) "已拉起巡检守护" else "巡检守护已在运行")
                    }

                    else -> {
                        tick("过渡/未知画面（$time）", "等待界面稳定")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "托管循环异常: ${e.message}", e)
                emit(pipeline, "ERROR", "托管循环异常: ${e.message}")
            }

            delay(tickIntervalMs())
        }

        emit(pipeline, "WARN", "无人托管循环已退出。")
    }

    private enum class ActionKind { RAID, PAVING, SIEGE, FARMING, LOGISTICS, LEVELING, NONE }

    private data class Action(val kind: ActionKind, val signature: String = "", val desc: String = "")

    /**
     * 确保夜间巡检守护在运行。
     *
     * 判定"是否已拉起"**不能**只看一个布尔量：巡检可能异常退出，也可能被
     * 「停止当前」手动停掉。前者会让夜间防护**静默消失**——这是最不该发生的事。
     * 因此当"已拉起、但当前任务已经不是巡检"时，冷却期满后重新拉起，
     * 并把原因明确写进日志，而不是悄悄重启。
     *
     * @return true 表示本次真的重新拉起了
     */
    private fun ensureRaidPatrol(pipeline: TacticalPipeline, intents: Intents, nowMs: Long): Boolean {
        if (!intents.raidDefense) return false

        // ⚠️ 必须用 isRaidPatrolActive（它同时接受 RAID_DEFENSE 与 NIGHT_SENTINEL），
        // 而不是直接判 `== RAID_DEFENSE`：TacticalPipeline 的两个入口都登记 NIGHT_SENTINEL，
        // 直接判 RAID_DEFENSE 会恒为 false，导致每 60 秒把正在跑的哨兵拆掉重建，
        // 进而把 startPatrol() 的 lastKeepAliveTimeMs 反复归零 → 5 分钟微保活永不触发。
        val patrolAlive = pipeline.isRaidPatrolActive
        val needsRestart = !raidDispatched ||
            (!patrolAlive && nowMs - raidLastStartAtMs >= RAID_RESTART_COOLDOWN_MS)
        if (!needsRestart) return false

        if (raidDispatched) {
            emit(
                pipeline, "WARN",
                "巡检守护已不在运行（被手动停止或异常退出），托管将在冷却期后重新拉起；" +
                    "若不想被拉起，请关闭无人托管。"
            )
        }
        pipeline.startRaidDefense(buildDefenseConfig())
        raidDispatched = true
        raidLastStartAtMs = nowMs
        return true
    }

    /**
     * 大地图空闲时决定并下发一个动作，并**集中**更新运行期状态。
     *
     * 状态更新集中在这里是有原因的：上一版把它们散落在循环各处，
     * 结果 `raidStarted` / `siegeDone` 两处置位之后再也没人复位。
     *
     * 优先级：巡检守护 → 铺路 → 攻城。返回 NONE 时 desc 说明为什么无事可做。
     */
    private fun dispatchWhenIdle(pipeline: TacticalPipeline, intents: Intents): Action {
        val now = System.currentTimeMillis()

        // 1) 巡检守护（长驻型任务）
        if (ensureRaidPatrol(pipeline, intents, now)) {
            return Action(ActionKind.RAID)
        }

        // 2) 铺路：目标集合变化才重新下发，避免同一批目标反复触发
        if (intents.pavingTargets.isNotEmpty()) {
            val sig = pavingSignatureOf(intents)
            if (sig != pavingSignature) {
                val defaults = KnowledgeBaseManager.activeProfile.tacticalDefaults
                val rules = KnowledgeBaseManager.activeProfile.rules
                pipeline.startRoadPaving(
                    RoadPavingFlow.PavingConfig(
                        targetTileList = intents.pavingTargets,
                        candidateTroopSlots = defaults.pavingDefaultSlots,
                        minMoraleThreshold = rules.minMoraleForPaving,
                        // 有世界坐标时，铺路流程会自己把镜头对准每一格再点，目标不会因镜头移动而失效
                        worldTargetList = intents.pavingWorldTargets
                    )
                )
                pavingSignature = sig
                return Action(ActionKind.PAVING, signature = sig)
            }
        }

        // 3) 攻城：目标或命中时刻一变就重新武装；到达命中时刻前 SIEGE_LEAD_MS 才下发
        val siegeTarget = intents.siegeTarget
        if (siegeTarget != null) {
            val sig = siegeSignatureOf(intents, siegeTarget)
            if (sig != siegeSignature && intents.siegeHitEpochMs > 0L &&
                now >= intents.siegeHitEpochMs - SIEGE_LEAD_MS
            ) {
                val defaults = KnowledgeBaseManager.activeProfile.tacticalDefaults
                val rules = KnowledgeBaseManager.activeProfile.rules
                pipeline.startSiegeSync(
                    SiegeSyncFlow.SiegeConfig(
                        cityVirtualCoord = siegeTarget,
                        targetBaseHitEpochMs = intents.siegeHitEpochMs,
                        mainSquadSlot = defaults.siegeMainSquadSlot,
                        demolitionSlots = defaults.siegeDemolitionSlots,
                        latencyCompensationMs = rules.immunityPaddingMs,
                        cityWorldCoord = intents.siegeWorldTarget
                    )
                )
                siegeSignature = sig
                return Action(ActionKind.SIEGE, signature = sig)
            }
        }

        // 4) 屯田打铁管家：配置了屯田地块或启用了自动屯田打铁
        if (intents.autoFarmingEnabled && (intents.farmingTarget != null || intents.farmingWorldTarget != null || !intents.farmingBookmark.isNullOrBlank())) {
            val sig = farmingSignatureOf(intents)
            if (sig != farmingSignature) {
                pipeline.startAccurateFarming(
                    AccurateFarmingFlow.FarmingConfig(
                        targetTileCoord = intents.farmingTarget,
                        targetWorldCoord = intents.farmingWorldTarget,
                        bookmarkName = intents.farmingBookmark,
                        farmingTroopSlot = intents.farmingTroopSlot,
                        minTileLevel = 5,
                        enableBlacksmithCheck = true
                    )
                )
                farmingSignature = sig
                return Action(ActionKind.FARMING, signature = sig)
            }
        }

        // 5) 日常后勤全托管：税收/伤兵补兵/体力防溢/城建升级
        if (intents.dailyLogistics && (now - logisticsLastRunAtMs >= LOGISTICS_INTERVAL_MS)) {
            pipeline.startDailyLogistics(
                DailyLogisticsFlow.LogisticsConfig(
                    enableTaxLevy = true,
                    enableReserveRecruitment = true,
                    enableStaminaProtection = true,
                    enableCityConstruction = true
                )
            )
            logisticsLastRunAtMs = now
            return Action(ActionKind.LOGISTICS)
        }

        // 6) 二三队低损速升 40 级流水线：配置了练级地块且启用了自动练级
        if (intents.autoLevelingEnabled && (intents.levelingTarget != null || intents.levelingWorldTarget != null || !intents.levelingBookmark.isNullOrBlank())) {
            val sig = levelingSignatureOf(intents)
            if (sig != levelingSignature) {
                pipeline.startSquadLeveling(
                    SquadLevelingFlow.LevelingConfig(
                        targetTileCoord = intents.levelingTarget,
                        targetWorldCoord = intents.levelingWorldTarget,
                        bookmarkName = intents.levelingBookmark,
                        tileLevel = intents.levelingTileLevel,
                        squadSlotA = intents.levelingSlotA,
                        squadSlotB = intents.levelingSlotB,
                        staminaMinThreshold = 20,
                        maxCasualtyRate = 0.15f,
                        minHealthPercent = 0.70f,
                        haltOnSevereInjury = true,
                        autoReplenishReserves = true
                    )
                )
                levelingSignature = sig
                return Action(ActionKind.LEVELING, signature = sig)
            }
        }

        return Action(ActionKind.NONE, desc = describeIdle(intents, now))
    }

    private fun pavingSignatureOf(intents: Intents): String = buildString {
        append(intents.pavingTargets.joinToString("|") { "${it.x.toInt()},${it.y.toInt()}" })
        if (intents.pavingWorldTargets.isNotEmpty()) {
            append('#')
            append(intents.pavingWorldTargets.joinToString("|") { "${it.first},${it.second}" })
        }
    }

    private fun siegeSignatureOf(intents: Intents, target: PointF): String = buildString {
        append("${target.x.toInt()},${target.y.toInt()}@${intents.siegeHitEpochMs}")
        intents.siegeWorldTarget?.let { append("#${it.first},${it.second}") }
    }

    private fun farmingSignatureOf(intents: Intents): String = buildString {
        append(intents.farmingBookmark ?: "")
        append('#')
        intents.farmingTarget?.let { append("${it.x.toInt()},${it.y.toInt()}") }
        append('#')
        intents.farmingWorldTarget?.let { append("${it.first},${it.second}") }
        append("@slot${intents.farmingTroopSlot}")
    }

    private fun levelingSignatureOf(intents: Intents): String = buildString {
        append(intents.levelingBookmark ?: "")
        append('#')
        intents.levelingTarget?.let { append("${it.x.toInt()},${it.y.toInt()}") }
        append('#')
        intents.levelingWorldTarget?.let { append("${it.first},${it.second}") }
        append("@s${intents.levelingSlotA}+${intents.levelingSlotB}")
    }

    /** 无事可做时给出**可操作**的说明，而不是默默什么都不做。 */
    private fun describeIdle(intents: Intents, nowMs: Long): String {
        val noTargets = intents.pavingTargets.isEmpty() && intents.siegeTarget == null &&
            !intents.autoFarmingEnabled && !intents.dailyLogistics && !intents.autoLevelingEnabled
        return when {
            noTargets && intents.raidDefense -> "巡检守护运行中，尚未配置铺路/攻城/屯田/后勤/练级目标"
            noTargets -> "尚未配置任何托管目标：请在各页签配置目标，或开启巡检守护"
            intents.siegeTarget != null && intents.siegeHitEpochMs > nowMs ->
                "攻城目标已武装，等到命中时刻前 ${SIEGE_LEAD_MS / 1000} 秒才会下发"
            intents.autoLevelingEnabled -> "二三队低损练级已武装，正在监控体力与出征窗口"
            intents.autoFarmingEnabled -> "屯田打铁已武装，正在监控策令与空闲窗口"
            intents.dailyLogistics -> "日常后勤全托管运行中，定期巡查税收与伤兵"
            else -> "已配置的目标均已下发，等待其完成（改选目标可重新下发）"
        }
    }

    private fun buildDefenseConfig(): RaidDefenseFlow.DefenseConfig {
        val defaults = KnowledgeBaseManager.activeProfile.tacticalDefaults
        return RaidDefenseFlow.DefenseConfig(
            counterAttackSquadSlot = defaults.immunityDefaultTroopSlot,
            // 巡检周期也取自知识库，让 raidPatrolIntervalMs 这类参数真正参与运行
            patrolIntervalMs = defaults.raidPatrolIntervalMs,
            enableAudioAlarm = defaults.raidAlarmSound,
            enableDecisionC = defaults.raidDecisionCAutoCounter
        )
    }

    // ==========================================================
    // 节奏与通知
    // ==========================================================

    /**
     * 巡航间隔跟随知识库的昼夜窗口：夜间放慢，减少高频操作特征。
     *
     * 昼夜判定已收敛到 `GameRules.isNightNow()`，避免这里再维护一份重复实现
     * （两份实现一旦漂移，"夜间减速"就会在不同模块表现不一致）。
     */
    private fun tickIntervalMs(): Long = if (isNightNow()) 6000L else 4000L

    private fun isNightNow(): Boolean =
        KnowledgeBaseManager.activeProfile.rules.isNightNow()

    /** 场景的中文可读名称，用于悬浮窗状态展示。 */
    private fun stateLabel(state: StzbUiMatcher.GameState): String = when (state) {
        StzbUiMatcher.GameState.MAIN_MAP -> "大地图主界面"
        StzbUiMatcher.GameState.TILE_ACTION_MENU -> "地块操作菜单"
        StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG -> "出征选队面板"
        StzbUiMatcher.GameState.DEFENDER_INFO_DIALOG -> "守军信息面板"
        StzbUiMatcher.GameState.COORDINATE_SEARCH_DIALOG -> "坐标检索面板"
        StzbUiMatcher.GameState.FORTRESS_BUILD_DIALOG -> "筑城/要塞面板"
        StzbUiMatcher.GameState.ALERT_RAID_ACTIVE -> "敌袭告警"
        else -> "过渡/未知画面"
    }

    private fun notifyChanged() {
        listeners.forEach { it.onPilotChanged(isRunning) }
    }

    private fun tick(stateDesc: String, action: String) {
        listeners.forEach { it.onPilotTick(stateDesc, action) }
    }

    /** 把托管决策写进游戏内日志流，用户能在「日志」页签看到每一步在做什么。 */
    private fun emit(pipeline: TacticalPipeline, level: String, message: String) {
        Log.i(TAG, message)
        pipeline.onLogEmitted(
            TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.RAID_DEFENSE,
                level = level,
                message = message
            )
        )
    }
}
