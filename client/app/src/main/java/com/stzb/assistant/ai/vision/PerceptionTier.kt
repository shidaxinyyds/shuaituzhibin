package com.stzb.assistant.ai.vision

import org.json.JSONArray

/**
 * 感知能力升级阶梯 (PerceptionTier)
 * ===================================================================
 * 本项目的第一性原则是「**确定性优先，ML 兜底**」。这条阶梯把它变成可执行的东西：
 * 从最便宜、最可解释、零数据的层级往上排，**只有当下一级被真机实测证明"兜不住"
 * 某个具体缺口时，才允许爬一级**——绝不为了"用了 AI"而用 AI。
 *
 * 成本从下到上单调递增（数据、训练、GPU、维护、不确定性都更高）：
 *
 *   [DETERMINISTIC]  模板匹配 + 颜色/HSV + 几何。零训练数据，改图重裁一张模板即可。
 *   [OCR]            PP-OCR 读数字/文本/按钮（率土兵堆数量、守军、界面词都在这一层解决）。
 *   [CLASSIFIER]     轻量 CNN 分类器。专治"模板太脆"（建筑等级、稀有度、buff 图标）。
 *                    数据只需**几百张裁剪样本**，不用逐个体画框——比检测器省一个数量级。
 *   [DETECTOR]       YOLO 目标检测。**仅**为"密集/遮挡/跨缩放的多目标"而生（动作类、
 *                    连续 3D 地图的舰队）。需要几千个准确框 + GPU + 每次改版重训，最贵。
 *   [POLICY]         决策/操作策略（规则微脑 / RL / 行为克隆）。实时动作类"会玩"才需要。
 *
 * 每个游戏的 [VisionPolicy] 声明它默认开到哪一级；[com.stzb.assistant.runtime.VisionRuntime]
 * 据此决定是否加载对应引擎（例如 SLG 默认不含 [DETECTOR]，YOLO 权重即便存在也不会被加载）。
 */
enum class PerceptionTier {
    DETERMINISTIC,
    OCR,
    CLASSIFIER,
    DETECTOR,
    POLICY,
}

/**
 * 某个游戏启用的感知层级集合（随 [com.stzb.assistant.knowledge.GameProfile] 云端热更）。
 *
 * 设计要点：
 *   - 用「白名单」而非「黑名单」——没显式开的层级一律视为关，Fail-Closed；
 *   - SLG/MMO 默认**不含** [PerceptionTier.DETECTOR]，即"这一盘不用 YOLO"；
 *   - 只有动作类（如 DNF）才默认开 [PerceptionTier.DETECTOR] + [PerceptionTier.POLICY]。
 */
data class VisionPolicy(val enabledTiers: Set<PerceptionTier>) {

    /** 该层级是否被本游戏启用。未显式启用即返回 false（Fail-Closed）。 */
    fun allows(tier: PerceptionTier): Boolean = tier in enabledTiers

    companion object {
        /** SLG 沙盘类（率土/三战/谋定/RoK）：确定性 + OCR + 轻量分类器，不用检测器。 */
        val SLG_DEFAULT = VisionPolicy(
            setOf(PerceptionTier.DETERMINISTIC, PerceptionTier.OCR, PerceptionTier.CLASSIFIER)
        )

        /** 回合制 MMO（梦幻西游）：重 UI 流程 + OCR，通常连分类器都不一定需要。 */
        val MMO_DEFAULT = VisionPolicy(
            setOf(PerceptionTier.DETERMINISTIC, PerceptionTier.OCR)
        )

        /** 实时动作类（DNF）：需要目标检测 + 决策策略。 */
        val ACTION_DEFAULT = VisionPolicy(
            setOf(
                PerceptionTier.DETERMINISTIC, PerceptionTier.OCR, PerceptionTier.CLASSIFIER,
                PerceptionTier.DETECTOR, PerceptionTier.POLICY
            )
        )

        /** 序列化为 JSON 数组（写入 GameProfile.toJson）。 */
        fun toJson(policy: VisionPolicy): JSONArray =
            JSONArray().apply { policy.enabledTiers.forEach { put(it.name) } }

        /**
         * 从 JSON 解析（缺失/为空一律回退 [SLG_DEFAULT]，保证旧缓存 JSON 无该字段时
         * 也**不会**误开检测器——向后兼容且 Fail-Closed）。
         */
        fun fromJson(arr: JSONArray?): VisionPolicy {
            if (arr == null || arr.length() == 0) return SLG_DEFAULT
            val tiers = mutableSetOf<PerceptionTier>()
            for (i in 0 until arr.length()) {
                runCatching { PerceptionTier.valueOf(arr.getString(i).trim().uppercase()) }
                    .onSuccess { tiers.add(it) }
            }
            return if (tiers.isEmpty()) SLG_DEFAULT else VisionPolicy(tiers)
        }
    }
}
