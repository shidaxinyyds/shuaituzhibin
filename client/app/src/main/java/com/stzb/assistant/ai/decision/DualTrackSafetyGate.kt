package com.stzb.assistant.ai.decision

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ai.microbrain.OrderIntent
import com.stzb.assistant.ai.microbrain.TacticalOrder
import com.stzb.assistant.ai.microbrain.isConcreteTargetName
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.tactics.AccurateFarmingFlow
import com.stzb.assistant.tactics.DailyLogisticsFlow
import com.stzb.assistant.tactics.ImmunityBreakFlow
import com.stzb.assistant.tactics.RaidDefenseFlow
import com.stzb.assistant.tactics.RoadPavingFlow
import com.stzb.assistant.tactics.SiegeSyncFlow
import com.stzb.assistant.tactics.TacticalPipeline
import com.stzb.assistant.tactics.TacticalState

/**
 * 双轨效用决策与安全守门员 (DualTrackSafetyGate)
 *
 * ## 现状（本轮已修复并正式接线）
 * 本类此前被**刻意摘出调用链**，原因是 `verifyAndDispatch` 会丢弃军令解析出的世界坐标
 * （例如 (782,451)），改用「屏幕正中」作为点击目标——那正是"识别到意图、却点在屏幕中间"
 * 的成因。摘出去只是不再犯这个错，并没有让这个"安全闸门"真正可用。
 *
 * 本轮把它修成**可以安全启用**的形态，并接进悬浮控制台的军师页签（识别军令 → 执行军令）：
 *   1. **不再改写目标**：订单里的世界坐标被原样向下传递，
 *      由各战术流自己用 `MapNavigator` 把镜头对准该格（它们都支持 `worldCoord`），
 *      屏幕坐标只作为"已对准镜头中心"的占位点，不再冒充目标；
 *   2. **宁可拒绝，也不猜**：如果订单带世界坐标而地图**未标定**，
 *      世界坐标无法换算成点击位置——此时**直接拒绝下发并说明原因**，
 *      而不是退化成点屏幕正中（那等于把部队派到随机地点）；
 *   3. **防幻觉校验**保留：坐标范围（**取知识库 `rules.mapCoordMax`，不写死率土 1500**）、
 *      时间戳不能是过去、
 *      体力基线（**一律取自知识库 `requiredStaminaNow()`，不在本文件写死数字**；
 *      体力未经实测时不伪造、也不拿默认值过闸，而是如实跳过并在日志里说明），
 *      再加 Utility AI 效用打分。
 *
 * ## 职责
 * 端侧微脑（自然语言军令/邮件/法令）与物理点击执行器之间的"防呆防幻觉闸门"：
 *   1. 严格校验坐标与时间戳是否物理可行；
 *   2. 校验体力安全基线；
 *   3. Utility AI 效用打分（军令权重 + 体力余量 + 解析置信度）；
 *   4. 通过后翻译成 `TacticalPipeline` 对应任务并安全激活。
 */
class DualTrackSafetyGate(private val context: Context) {

    interface SafetyGateCallback {
        fun onOrderVerified(order: TacticalOrder, utilityScore: Float)
        fun onOrderRejected(order: TacticalOrder, reason: String)
        fun onExecutionDispatched(taskType: TacticalState.TaskType, summary: String)
    }

    private val pipeline = TacticalPipeline.getInstance(context)
    private var callback: SafetyGateCallback? = null

    fun setCallback(cb: SafetyGateCallback) {
        this.callback = cb
    }

    data class VerificationResult(
        val isPassed: Boolean,
        val utilityScore: Float,
        val rejectReason: String? = null
    )

    /**
     * 订单目标的解析结论。
     *
     * 之所以显式建模"拒绝"，是因为这里最容易发生的错误是
     * "拿不到真实目标 → 退化成点屏幕中间 → 看起来执行了、实际点错地方"。
     */
    private sealed class TargetResolution {
        /** 需要目标且已确定可用的世界坐标。 */
        data class WorldTarget(val worldX: Int, val worldY: Int) : TargetResolution()
        /** 该意图不需要点击目标（例如休整、主城守护）。 */
        object NoTargetNeeded : TargetResolution()
        /** 无法安全确定目标，必须拒绝。 */
        data class Refused(val reason: String) : TargetResolution()
    }

    /**
     * 校验并执行端侧微脑生成的战术指令。
     *
     * ⚠️ 本函数是 `suspend`：目标解析不再靠猜，而是走真实的地图标定链路。
     * 调用方需在协程中调用（悬浮控制台已经是 `scope.launch`）。
     *
     * @param currentStamina 队伍体力的**实测值**；`null` 表示"现在没读过"。
     *   旧默认值是 `100`，那是一个编出来的数字：军师页的「执行军令」从不传体力，
     *   于是这道闸永远以"体力满"过闸、永远拦不住任何东西，日志还写得像真校验过。
     *   现在未知就是未知：体力硬基线只在有实测值时参与判定，
     *   效用分也不再白送体力那一份。真正的逐槽体力门槛在各战术流里
     *   （RoadPavingFlow / SiegeSyncFlow 选队时会用 OCR 实测体力再判一次）。
     */
    suspend fun verifyAndDispatch(order: TacticalOrder, currentStamina: Int? = null): Boolean {
        Log.i(TAG, "安全守门员开始审查战术指令: [${order.orderId}] ${order.intent.desc} 目标:${order.targetName}")

        val result = evaluateOrder(order, currentStamina)
        if (!result.isPassed) {
            Log.w(TAG, "战术指令被安全守门员拦截: ${result.rejectReason}")
            callback?.onOrderRejected(order, result.rejectReason ?: "未知风险阻断")
            return false
        }

        val resolution = resolveTarget(order)
        if (resolution is TargetResolution.Refused) {
            Log.w(TAG, "目标不可安全确定，拒绝下发: ${resolution.reason}")
            callback?.onOrderRejected(order, resolution.reason)
            return false
        }

        Log.i(TAG, "战术指令安全审查通过！综合效用得分: ${result.utilityScore}分")
        callback?.onOrderVerified(order, result.utilityScore)

        return dispatchToPipeline(order, resolution)
    }

    /**
     * 严苛的多维安全性与效用评估矩阵
     */
    private fun evaluateOrder(order: TacticalOrder, currentStamina: Int?): VerificationResult {
        val rules = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules

        // 1. 防幻觉门禁：坐标边界判定 (若有坐标)。界取**当前游戏**的地图尺寸，不写死率土的 1500
        val coord = order.targetCoord
        if (coord != null) {
            if (!rules.isValidWorldCoord(coord.first, coord.second)) {
                return VerificationResult(
                    false, 0f,
                    "坐标超出本游戏有效大地图范围 [1,${rules.mapCoordMax}]: (${coord.first}, ${coord.second})"
                )
            }
        }

        // 2. 防幻觉门禁：时间戳合理性判定
        val now = System.currentTimeMillis()
        if (order.targetTime > 0 && order.targetTime < now - 300 * 1000) {
            return VerificationResult(false, 0f, "目标攻城时间已早于当前时刻5分钟以上，禁止向历史发兵")
        }

        // 3. 物理门禁：体力安全基线（**基线一律取自知识库，不在这里写死**）
        //
        //    旧写法是 `val minStaminaRequired = 20`。20 恰好等于 staminaPerAction 的默认值，
        //    所以看上去从来"是对的"，实际上它是第二权威：把三战改成夜战双倍（应为 40）时，
        //    RoadPavingFlow / SiegeSyncFlow 会跟着知识库改，这道闸却不动，
        //    两处判据从此开始打架——这正是 P4 要消除的那类重复定义。
        val minStaminaRequired = rules.requiredStaminaNow()
        if (currentStamina != null && currentStamina < minStaminaRequired) {
            val nightTag = if (rules.isNightNow()) "，含夜间倍率 ${rules.nightStaminaMultiplier}x" else ""
            return VerificationResult(
                false, 0f,
                "队伍体力过低 (实测:$currentStamina，基线:$minStaminaRequired$nightTag)，防暴毙拦截"
            )
        }

        // 4. Utility AI 效用函数打分
        // Score = 基础分 + 军令权重 + 体力余量权重 + 解析置信度权重
        val wPriority = when (order.intent) {
            OrderIntent.ALLIANCE_SIEGE -> 40f
            OrderIntent.DEFEND_GATE -> 35f
            OrderIntent.ROAD_PAVING -> 30f
            OrderIntent.RETREAT_AND_GUARD -> 25f
            OrderIntent.SPARTAN_SCOUT -> 15f
            OrderIntent.STAMINA_RECOVERY -> 10f
        }
        // 体力份：未实测就给 0（而不是拿默认 100 算成一个看上去很好的分数）。
        // 分母取知识库 maxStamina，不假设"体力满值就是 100"（率土是 120，旧写法会让满体力算出 36 分）。
        val wStamina = currentStamina?.let {
            (it.toFloat() / rules.maxStamina.coerceAtLeast(1)) * 30f
        } ?: 0f
        val wConfidence = order.confidence * 30f

        val finalScore = (wPriority + wStamina + wConfidence).coerceIn(0f, 100f)
        if (currentStamina == null) {
            Log.i(TAG, "体力未实测：体力硬基线（$minStaminaRequired）本轮跳过，效用分不计体力份；实际选队时仍由各战术流实测体力把关。")
        }

        return VerificationResult(true, finalScore)
    }

    /**
     * 解析该订单的可用目标。
     *
     * 判据（刻意保守）：
     *   * 需要坐标的意图 + 订单有世界坐标 + 地图已标定 → 放行（世界坐标原样下发）；
     *   * 需要坐标的意图 + 订单有世界坐标 + **地图未标定** → 拒绝（无法换算成点击位置）；
     *   * 需要坐标的意图 + 订单无世界坐标 → 拒绝（不允许用屏幕正中顶替）；
     *   * 守护/休整类意图 → 不需要点击目标（哨兵靠世界坐标切比雪夫判定，与镜头无关）。
     */
    private fun resolveTarget(order: TacticalOrder): TargetResolution {
        val needsTile = when (order.intent) {
            OrderIntent.ALLIANCE_SIEGE,
            OrderIntent.ROAD_PAVING,
            OrderIntent.SPARTAN_SCOUT -> true
            OrderIntent.DEFEND_GATE,
            OrderIntent.RETREAT_AND_GUARD,
            OrderIntent.STAMINA_RECOVERY -> false
        }
        if (!needsTile) return TargetResolution.NoTargetNeeded

        val coord = order.targetCoord
            ?: return TargetResolution.Refused(
                "订单「${order.targetName}」未解析出世界坐标，无法确定点击目标。" +
                    "为避免点到屏幕正中的随机位置，本次拒绝下发——" +
                    "请在军令文本里明确写出坐标（如 582,391），或在对应页签手动点选地块。"
            )

        if (!MapProjection.isCalibrated) {
            return TargetResolution.Refused(
                "地图投影未标定，无法把世界坐标 (${coord.first}, ${coord.second}) 换算成点击位置。" +
                    "请先到「标定」页签完成两点标定（或从 HUD 读一次当前坐标）后再执行，本次拒绝下发。"
            )
        }

        return TargetResolution.WorldTarget(coord.first, coord.second)
    }

    /** 屏幕中心占位点：仅在已按世界坐标对准镜头后使用（各流内部会自己对准）。 */
    private fun viewportCenter(): PointF =
        PointF(CoordinateTransformer.virtualWidth / 2f, CoordinateTransformer.virtualHeight / 2f)

    /**
     * 下发至确定性战术流水线执行。
     *
     * **关键点**：这里把世界坐标原样传下去（各流的 `worldCoord` 参数），
     * 由流内部用 `MapNavigator` 对准镜头后再点镜头中心。
     * 屏幕坐标只是占位，绝不作为目标依据。
     */
    private fun dispatchToPipeline(order: TacticalOrder, resolution: TargetResolution): Boolean {
        try {
            val center = viewportCenter()
            val world = (resolution as? TargetResolution.WorldTarget)?.let { Pair(it.worldX, it.worldY) }

            when (order.intent) {
                OrderIntent.ALLIANCE_SIEGE -> {
                    val config = SiegeSyncFlow.SiegeConfig(
                        cityVirtualCoord = center,
                        targetBaseHitEpochMs = if (order.targetTime > 0) order.targetTime else System.currentTimeMillis() + 300 * 1000L,
                        mainSquadSlot = 1,
                        demolitionSlots = listOf(2, 3),
                        // 世界坐标下传：攻城流自己会把镜头对准目标城池
                        cityWorldCoord = world,
                        // 军令里的目标名往往就是要塞/城池的官方书签名，能对上就用书签 0 漂移。
                        // ❗ 判据必须用 [isConcreteTargetName]：旧写法 `it != "未明目标"` 恒真
                        //    （解析器读不到地名时返回的是 "目标据点" 这一类占位名），
                        //    于是攻城流会拿到一个游戏里根本不存在的书签名去漂移。
                        fortressName = order.targetName.takeIf { it.isConcreteTargetName() }
                    )
                    pipeline.startSiegeSync(config)
                    callback?.onExecutionDispatched(
                        TacticalState.TaskType.SIEGE_SYNC,
                        "已启动同盟集火攻城任务: ${order.targetName} @ ${world ?: "无坐标"}"
                    )
                }

                OrderIntent.ROAD_PAVING -> {
                    // ⚠️ 两个列表必须**一一对应**：给世界坐标就必须给等长的屏幕坐标列表，
                    // 否则会出现"对准 A 却点 B"的错位（ScheduledTaskManager 里对此有详细说明）。
                    val config = RoadPavingFlow.PavingConfig(
                        targetTileList = listOf(center),
                        candidateTroopSlots = listOf(1, 2, 3),
                        minMoraleThreshold = 100,
                        maxPavingCount = 30,
                        worldTargetList = listOfNotNull(world)
                    )
                    pipeline.startRoadPaving(config)
                    callback?.onExecutionDispatched(
                        TacticalState.TaskType.ROAD_PAVING,
                        "已启动自动铺路翻地任务: ${order.targetName} @ ${world ?: "无坐标"}"
                    )
                }

                OrderIntent.DEFEND_GATE, OrderIntent.RETREAT_AND_GUARD -> {
                    // 守护类不需要点击目标：哨兵以"主城世界坐标 + 2 格切比雪夫"判定威胁，
                    // 与当前镜头位置无关。若已知基地世界坐标，顺手带上作为像素兜底锚点。
                    val baseWorld = MapProjection.baseWorld
                    val config = RaidDefenseFlow.DefenseConfig(
                        baseAnchor = if (baseWorld != null) center else null,
                        baseWorldCoord = baseWorld,
                        counterAttackSquadSlot = 1,
                        patrolIntervalMs = 2500L,
                        enableAudioAlarm = true,
                        enableDecisionC = true
                    )
                    pipeline.startNightSentinel(config)
                    callback?.onExecutionDispatched(
                        TacticalState.TaskType.NIGHT_SENTINEL,
                        "已启动暗夜哨兵主城与要塞守护: ${order.targetName}" +
                            (if (baseWorld == null) "（提示：未记录基地世界坐标，像素兜底判定精度有限，建议先标定）" else "")
                    )
                }

                OrderIntent.SPARTAN_SCOUT -> {
                    val config = ImmunityBreakFlow.ImmunityConfig(
                        mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                        targetTileCoord = center,
                        designatedTroopSlot = 1,
                        latencyCompensationMs = 110L,
                        // 世界坐标交给流内部对准，避免闸门自己改写目标
                        targetWorldCoord = world
                    )
                    pipeline.startImmunityBreak(config)
                    callback?.onExecutionDispatched(
                        TacticalState.TaskType.IMMUNITY_BREAK,
                        "已启动卡免探路任务: ${order.targetName} @ ${world ?: "无坐标"}"
                    )
                }

                OrderIntent.STAMINA_RECOVERY -> {
                    pipeline.stopCurrentTask()
                    callback?.onExecutionDispatched(
                        TacticalState.TaskType.STAMINA_ROTATION,
                        "休整养精蓄锐，已暂停主动进军任务"
                    )
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "下发战术流水线失败: ${e.message}")
            callback?.onOrderRejected(order, "下发流水线异常: ${e.message}")
            return false
        }
    }

    /**
     * 便捷入口：把一条自然语言军令直接走"解析 → 闸门 → 下发"的完整链路。
     *
     * 供悬浮控制台的「执行军令」按钮调用；解析与安全校验都在这里收敛，
     * 避免调用方各自拼装（那正是之前出现"解析了却没人下发"的原因）。
     */
    suspend fun parseAndDispatch(decreeText: String, currentStamina: Int? = null): Pair<Boolean, String> {
        val order = com.stzb.assistant.ai.microbrain.EdgeSlmEngine(context)
            .parseAllianceDecree(decreeText)
        val ok = verifyAndDispatch(order, currentStamina)
        val detail = if (ok) {
            "✅ 军令【${order.targetName}】已通过安全门禁并下发。"
        } else {
            "⛔ 军令【${order.targetName}】被安全门禁拦截，请查看下方日志了解原因。"
        }
        return Pair(ok, detail)
    }

    /**
     * 辅助入口：把"后勤 / 屯田"这类**不带军令文本**的意图也纳入同一道下发口径。
     *
     * 刻意不提供"无目标的屯田"：屯田必须有地块（坐标或书签），
     * 否则 AccurateFarmingFlow 会在启动后立刻判定"缺少有效屯田目标"而失败——
     * 与其下发一个注定失败的任务，不如在这里就把原因说清楚。
     */
    fun dispatchLogisticsAndFarming(
        logistics: Boolean,
        farmingTarget: PointF? = null,
        farmingWorld: Pair<Int, Int>? = null,
        farmingBookmark: String? = null
    ): String {
        val wantFarming = farmingTarget != null || farmingWorld != null || !farmingBookmark.isNullOrBlank()
        val parts = mutableListOf<String>()

        if (logistics) {
            pipeline.startDailyLogistics(DailyLogisticsFlow.LogisticsConfig())
            parts.add("日常后勤全托管")
        }
        if (wantFarming) {
            pipeline.startAccurateFarming(
                AccurateFarmingFlow.FarmingConfig(
                    targetTileCoord = farmingTarget,
                    targetWorldCoord = farmingWorld,
                    bookmarkName = farmingBookmark
                )
            )
            parts.add("屯田打铁管家")
        }

        return if (parts.isEmpty()) {
            "未下发任何任务：既没有勾选后勤，也没有提供屯田目标地块/书签。"
        } else {
            "已下发: ${parts.joinToString("、")}"
        }
    }

    companion object {
        private const val TAG = "DualTrackSafetyGate"
    }
}
