package com.stzb.assistant.ocr

import com.stzb.assistant.knowledge.KnowledgeBaseManager

/**
 * 土地守军难度智能评估字典 (动态挂接当前激活的游戏知识库)
 * 
 * 核心特性：
 *   1. 【数据驱动与热更新接入】：优先从 KnowledgeBaseManager 当前激活的游戏知识库中实时读取；
 *   2. 【克制与避让建议】：不仅返回难度等级，还同步返回具体守将的危险机制 (暴走/怯战/禁疗) 与克制策略；
 *   3. 【多游戏自适应】：切到《率土》评估率土守军，切到《三战》评估三战守军。
 */
object DefenderEvaluator {

    enum class SafetyTier(val desc: String, val weight: Int) {
        SAFE("🟢 白给软柿子（极力推荐）", 1),
        MODERATE("🟡 中等难度（兵力充足可打）", 2),
        HARD("🟠 较难（损兵偏多）", 3),
        DANGER("🔴 极高危翻车队（坚决避让）", 5)
    }

    data class DefenderMatch(
        val name: String,
        val tier: SafetyTier,
        val tag: String,
        val counterTip: String
    )

    data class EvaluationResult(
        val tier: SafetyTier,
        val matchedDefenders: List<DefenderMatch>,
        val totalRiskScore: Int,
        val recommendation: String
    )

    /**
     * 对识别出的守军武将进行综合危险度评级与克制策略打分
     */
    fun evaluate(recognizedNames: List<String>): EvaluationResult {
        val matches = mutableListOf<DefenderMatch>()
        var maxRiskTier = SafetyTier.SAFE
        var totalScore = 0

        val defenderDb = KnowledgeBaseManager.activeProfile.defenderDb

        for (name in recognizedNames) {
            val cleanName = name.trim().replace(" ", "")
            val match = findMatch(cleanName, defenderDb)
            matches.add(match)
            totalScore += match.tier.weight

            if (match.tier.weight > maxRiskTier.weight) {
                maxRiskTier = match.tier
            }
        }

        val recommendation = when (maxRiskTier) {
            SafetyTier.DANGER -> "⚠️ 检测到致命强控/爆发守将，极易暴毙团灭，建议换地！"
            SafetyTier.HARD -> "⚠️ 难度较高，可能伴随较高战损，建议补强兵力后再打。"
            SafetyTier.MODERATE -> "难度适中，主力兵力达标即可稳健拿下。"
            SafetyTier.SAFE -> "🟢 极佳软柿子守军，战损极低，推荐主力立刻出征收割！"
        }

        return EvaluationResult(
            tier = maxRiskTier,
            matchedDefenders = matches,
            totalRiskScore = totalScore,
            recommendation = recommendation
        )
    }

    private fun findMatch(name: String, db: com.stzb.assistant.knowledge.DefenderDatabase): DefenderMatch {
        // 1. 危险武将探测
        for (hero in db.dangerHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, SafetyTier.DANGER, hero.tag, hero.counterTip)
            }
        }
        // 2. 较难武将探测
        for (hero in db.hardHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, SafetyTier.HARD, hero.tag, hero.counterTip)
            }
        }
        // 3. 软柿子白给武将探测
        for (hero in db.safeHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, SafetyTier.SAFE, hero.tag, hero.counterTip)
            }
        }
        // 4. 中等武将探测
        for (hero in db.moderateHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, SafetyTier.MODERATE, hero.tag, hero.counterTip)
            }
        }

        // 未知武将默认取中间评级
        return DefenderMatch(name, SafetyTier.MODERATE, "常规守军", "兵力充沛即可攻打")
    }
}
