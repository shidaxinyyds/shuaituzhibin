package com.stzb.assistant.ocr

/**
 * 2026 征服赛季土地守军难度智能评估字典
 * 用于自动“查看守军”后秒级打分，彻底杜绝开荒期撞到强控/暴走守军导致的恶性团灭
 */
object DefenderEvaluator {

    enum class SafetyTier(val desc: String, val weight: Int) {
        SAFE("🟢 白给软柿子（极力推荐）", 1),
        MODERATE("🟡 中等难度（兵力充足可打）", 2),
        HARD("🟠 较难（损兵偏多）", 3),
        DANGER("🔴 极高危翻车队（坚决避让）", 5)
    }

    data class EvaluationResult(
        val tier: SafetyTier,
        val matchedDefenders: List<Pair<String, SafetyTier>>,
        val totalRiskScore: Int,
        val recommendation: String
    )

    // T3: 极高危翻车武将（带强控如暴走、混乱、怯战、禁疗、或超高爆发）
    private val TIER_DANGER = setOf(
        "郭嘉", "李儒", "法正", "陈宫", "黄忠", "陆逊", "庞统", "吕蒙", "贾诩", "周瑜"
    )

    // T2: 较难武将（输出平稳但硬度高）
    private val TIER_HARD = setOf(
        "张任", "严颜", "廖化", "管亥", "潘璋", "张勋", "纪灵", "曹仁"
    )

    // T1: 中等武将
    private val TIER_MODERATE = setOf(
        "于禁", "徐晃", "鲍信", "严白虎", "华雄", "公孙瓒", "朱儁"
    )

    // T0: 白给软柿子（战法不稳定、无硬控、极低战损）
    private val TIER_SAFE = setOf(
        "邓茂", "田续", "裴元绍", "审配", "李典", "陶谦", "韩馥", "孔融", "刘焉", "张宝"
    )

    /**
     * 对识别出的 3 名守军武将进行综合危险度评级
     */
    fun evaluate(recognizedNames: List<String>): EvaluationResult {
        val matches = mutableListOf<Pair<String, SafetyTier>>()
        var maxRiskTier = SafetyTier.SAFE
        var totalScore = 0

        for (name in recognizedNames) {
            val cleanName = name.trim().replace(" ", "")
            val matchedTier = findTier(cleanName)
            matches.add(Pair(cleanName, matchedTier))
            totalScore += matchedTier.weight

            if (matchedTier.weight > maxRiskTier.weight) {
                maxRiskTier = matchedTier
            }
        }

        val recommendation = when (maxRiskTier) {
            SafetyTier.DANGER -> "检测到致命强控/爆发守将，极易暴毙团灭，建议换地！"
            SafetyTier.HARD -> "难度较高，可能伴随较高战损，建议补强兵力后再打。"
            SafetyTier.MODERATE -> "难度适中，主力兵力达标即可稳健拿下。"
            SafetyTier.SAFE -> "极佳软柿子守军，战损极低，推荐主力立刻出征收割！"
        }

        return EvaluationResult(
            tier = maxRiskTier,
            matchedDefenders = matches,
            totalRiskScore = totalScore,
            recommendation = recommendation
        )
    }

    private fun findTier(name: String): SafetyTier {
        for (hero in TIER_DANGER) {
            if (name.contains(hero)) return SafetyTier.DANGER
        }
        for (hero in TIER_HARD) {
            if (name.contains(hero)) return SafetyTier.HARD
        }
        for (hero in TIER_MODERATE) {
            if (name.contains(hero)) return SafetyTier.MODERATE
        }
        for (hero in TIER_SAFE) {
            if (name.contains(hero)) return SafetyTier.SAFE
        }
        return SafetyTier.MODERATE // 未知武将默认取中间评级
    }
}
