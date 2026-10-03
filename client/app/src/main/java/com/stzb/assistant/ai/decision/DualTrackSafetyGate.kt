package com.stzb.assistant.ai.decision

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ai.microbrain.OrderIntent
import com.stzb.assistant.ai.microbrain.TacticalOrder
import com.stzb.assistant.tactics.ImmunityBreakFlow
import com.stzb.assistant.tactics.RaidDefenseFlow
import com.stzb.assistant.tactics.RoadPavingFlow
import com.stzb.assistant.tactics.SiegeSyncFlow
import com.stzb.assistant.tactics.TacticalPipeline
import com.stzb.assistant.tactics.TacticalState

/**
 * 双轨效用决策与安全守门员 (DualTrackSafetyGate)
 *
 * ## ⚠️ 当前状态：未接入任何执行链路，请勿直接启用
 * 本类已从悬浮控制台的调用链中移除，原因是它的 `verifyAndDispatch` 会
 * **丢弃军令解析出的世界坐标**（例如 (782,451)），改用「屏幕正中」作为点击目标
 * （其内部注释自称"确保大地图沙盘世界坐标与屏幕虚拟像素严格解耦……
 * 屏幕操作点固定使用各机型自适应的标准屏幕中心安全区"）。
 * 这正是"识别到了意图、却点在屏幕中间"的直接成因，属于必须修掉的行为。
 *
 * 目标坐标现已改为：由用户在悬浮控制台**点选地块**得到屏幕坐标，
 * 直接交给 `TacticalPipeline`，中途不再经过任何会改写目标的环节。
 *
 * 如需重新启用本类，必须先完成两件事，否则它会退回同一个缺陷：
 *   1. 让 `verifyAndDispatch` 使用订单里的真实坐标，而不是屏幕中心；
 *   2. 让世界坐标（地图格坐标）到屏幕坐标的换算有真实依据
 *      （例如通过 HUD 读出的当前镜头中心坐标 + 标定好的每格像素数）。
 *
 * 核心职责（设计意图，仅供参考）：
 *   作为端侧生成式大模型与物理点击执行器之间的“防呆防幻觉闸门”与“效用评估中枢”：
 *   1. 【防大模型幻觉】：严格校验坐标是否在 [1~1500]、时间戳是否合理、提前量是否在物理行军区间内；
 *   2. 【物理可行性校验】：检查剩余体力 (夜战双倍消耗)、兵种克制比率与反封控规则；
 *   3. 【Utility AI 效用打分】：量化候选方案收益得分，确保每次调度都最优；
 *   4. 【战术调度下发】：将通过校验的 TacticalOrder 自动翻译为 TacticalPipeline 对应任务并安全激活。
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
     * 校验并执行端侧微脑生成的战术指令
     */
    fun verifyAndDispatch(order: TacticalOrder, currentStamina: Int = 100): Boolean {
        Log.i(TAG, "安全守门员开始审查战术指令: [${order.orderId}] ${order.intent.desc} 目标:${order.targetName}")

        val result = evaluateOrder(order, currentStamina)
        if (!result.isPassed) {
            Log.w(TAG, "战术指令被安全守门员拦截: ${result.rejectReason}")
            callback?.onOrderRejected(order, result.rejectReason ?: "未知风险阻断")
            return false
        }

        Log.i(TAG, "战术指令安全审查通过！综合效用得分: ${result.utilityScore}分")
        callback?.onOrderVerified(order, result.utilityScore)

        // 调度物理执行流水线
        return dispatchToPipeline(order)
    }

    /**
     * 严苛的多维安全性与效用评估矩阵
     */
    private fun evaluateOrder(order: TacticalOrder, currentStamina: Int): VerificationResult {
        // 1. 防幻觉门禁：坐标边界判定 (若有坐标)
        val coord = order.targetCoord
        if (coord != null) {
            if (coord.first !in 1..1500 || coord.second !in 1..1500) {
                return VerificationResult(false, 0f, "坐标超出率土十三州有效大地图范围: (${coord.first}, ${coord.second})")
            }
        }

        // 2. 防幻觉门禁：时间戳合理性判定
        val now = System.currentTimeMillis()
        if (order.targetTime > 0 && order.targetTime < now - 300 * 1000) {
            return VerificationResult(false, 0f, "目标攻城时间已早于当前时刻5分钟以上，禁止向历史发兵")
        }

        // 3. 物理门禁：体力安全基线
        val minStaminaRequired = 20
        if (currentStamina < minStaminaRequired) {
            return VerificationResult(false, 0f, "队伍平均体力过低 (当前:$currentStamina，基线:$minStaminaRequired)，防暴毙拦截")
        }

        // 4. Utility AI 效用函数打分
        // Score = 基础分 + 军令权重 + 体力余量权重 + 时间紧迫度权重
        val wPriority = when (order.intent) {
            OrderIntent.ALLIANCE_SIEGE -> 40f
            OrderIntent.DEFEND_GATE -> 35f
            OrderIntent.ROAD_PAVING -> 30f
            OrderIntent.RETREAT_AND_GUARD -> 25f
            OrderIntent.SPARTAN_SCOUT -> 15f
            OrderIntent.STAMINA_RECOVERY -> 10f
        }
        val wStamina = (currentStamina.toFloat() / 100f) * 30f
        val wConfidence = order.confidence * 30f

        val finalScore = (wPriority + wStamina + wConfidence).coerceIn(0f, 100f)

        return VerificationResult(true, finalScore)
    }

    /**
     * 下发至确定性战术流水线执行
     */
    private fun dispatchToPipeline(order: TacticalOrder): Boolean {
        try {
            // 确保大地图沙盘世界坐标与屏幕虚拟像素严格解耦：
            // 若为自然语言军令提取的世界坐标 (1..1500)，大地图寻路后目标默认对齐在镜头中央区域；
            // 屏幕操作点固定使用各机型自适应的标准屏幕中心安全区
            val screenCenterX = com.stzb.assistant.service.CoordinateTransformer.virtualWidth / 2f
            val screenCenterY = com.stzb.assistant.service.CoordinateTransformer.virtualHeight / 2f
            val targetPoint = PointF(screenCenterX, screenCenterY)

            when (order.intent) {
                OrderIntent.ALLIANCE_SIEGE -> {
                    val config = SiegeSyncFlow.SiegeConfig(
                        cityVirtualCoord = targetPoint,
                        targetBaseHitEpochMs = if (order.targetTime > 0) order.targetTime else System.currentTimeMillis() + 300 * 1000L,
                        mainSquadSlot = 1,
                        demolitionSlots = listOf(2, 3)
                    )
                    pipeline.startSiegeSync(config)
                    callback?.onExecutionDispatched(TacticalState.TaskType.SIEGE_SYNC, "已启动同盟集火攻城任务: ${order.targetName}")
                }

                OrderIntent.ROAD_PAVING -> {
                    val config = RoadPavingFlow.PavingConfig(
                        targetTileList = listOf(targetPoint),
                        candidateTroopSlots = listOf(1, 2, 3),
                        minMoraleThreshold = 100,
                        maxPavingCount = 30
                    )
                    pipeline.startRoadPaving(config)
                    callback?.onExecutionDispatched(TacticalState.TaskType.ROAD_PAVING, "已启动自动铺路翻地任务: ${order.targetName}")
                }

                OrderIntent.DEFEND_GATE, OrderIntent.RETREAT_AND_GUARD -> {
                    val config = RaidDefenseFlow.DefenseConfig(
                        baseAnchor = targetPoint,
                        counterAttackSquadSlot = 1,
                        patrolIntervalMs = 2500L,
                        enableAudioAlarm = true,
                        enableDecisionC = true
                    )
                    pipeline.startNightSentinel(config)
                    callback?.onExecutionDispatched(TacticalState.TaskType.NIGHT_SENTINEL, "已启动暗夜哨兵主城与要塞守护: ${order.targetName}")
                }

                OrderIntent.SPARTAN_SCOUT -> {
                    val config = ImmunityBreakFlow.ImmunityConfig(
                        mode = ImmunityBreakFlow.ImmunityMode.BREAK_IMMUNITY,
                        targetTileCoord = targetPoint,
                        designatedTroopSlot = 1,
                        latencyCompensationMs = 110L
                    )
                    pipeline.startImmunityBreak(config)
                    callback?.onExecutionDispatched(TacticalState.TaskType.IMMUNITY_BREAK, "已启动斯巴达卡免探路任务: ${order.targetName}")
                }

                OrderIntent.STAMINA_RECOVERY -> {
                    pipeline.stopCurrentTask()
                    callback?.onExecutionDispatched(TacticalState.TaskType.STAMINA_ROTATION, "休整养精蓄锐，已暂停主动进军任务")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "下发战术流水线失败: ${e.message}")
            return false
        }
    }

    companion object {
        private const val TAG = "DualTrackSafetyGate"
    }
}
