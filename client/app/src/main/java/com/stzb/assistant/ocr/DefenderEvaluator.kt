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
        /**
         * **没有拿到守军名单**，因此无从判断危险度。
         *
         * 这个值的存在是一个安全设计：此前"识别到 0 个守军名字"会一路走到
         * `maxRiskTier` 的初始值 `SAFE`，于是输出
         * 「🟢 极佳软柿子守军…推荐主力立刻出征收割！」——
         * **把"什么都没读到"说成"大力推荐去打"**。这是最危险的一类假阴性：
         * 它不是不报，而是报了一个鼓励进攻的结论。
         *
         * 权重取 0：它不代表"更安全"，而代表"不在危险度量表上"，
         * 因此不应参与 `resolveTier` 的"取更危险一侧"比较。
         */
        UNKNOWN("⚪ 未知（未读到守军名单，不可据此判断）", 0),
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
        // 一条守军名字都没读到 → 明确说"无法判断"，**绝不**返回 SAFE。
        //
        // 原实现从 maxRiskTier = SAFE 起算，空输入时循环不执行，于是直接输出
        // 「🟢 极佳软柿子守军，战损极低，推荐主力立刻出征收割！」——
        // 把"什么都没读到"包装成"强烈推荐去打"。这类错误比其他假阴性更危险：
        // 它会主动推动用户去做一个可能团灭的决定。
        if (recognizedNames.none { it.isNotBlank() }) {
            return EvaluationResult(
                tier = SafetyTier.UNKNOWN,
                matchedDefenders = emptyList(),
                totalRiskScore = 0,
                recommendation = "⚪ 没有识别到任何守军名字，**无法判断该地是否可打**。" +
                    "请确认已打开「查看守军」面板、且文字识别可用；" +
                    "在拿到守军名单之前，请不要据此决定是否出征。"
            )
        }

        val matches = mutableListOf<DefenderMatch>()
        // 从 UNKNOWN（权重 0）起算：只要识别到任何一个守军，评级就会被真实结果覆盖，
        // 不会停留在"未知"上。
        var maxRiskTier = SafetyTier.UNKNOWN
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
            SafetyTier.UNKNOWN -> "⚪ 未能判定危险度，请重试或人工查看守军。"
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
                return DefenderMatch(hero.name, resolveTier(SafetyTier.DANGER, hero.threatScore), hero.tag, hero.counterTip)
            }
        }
        // 2. 较难武将探测
        for (hero in db.hardHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, resolveTier(SafetyTier.HARD, hero.threatScore), hero.tag, hero.counterTip)
            }
        }
        // 3. 软柿子白给武将探测
        for (hero in db.safeHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, resolveTier(SafetyTier.SAFE, hero.threatScore), hero.tag, hero.counterTip)
            }
        }
        // 4. 中等武将探测
        for (hero in db.moderateHeroes) {
            if (name.contains(hero.name) || hero.name.contains(name)) {
                return DefenderMatch(hero.name, resolveTier(SafetyTier.MODERATE, hero.threatScore), hero.tag, hero.counterTip)
            }
        }

        // 未知武将默认取中间评级
        return DefenderMatch(name, SafetyTier.MODERATE, "常规守军", "兵力充沛即可攻打")
    }

    /** 知识库给守将的威胁评分（1~5）-> 安全等级。 */
    private fun tierForScore(score: Int): SafetyTier = when {
        score <= 1 -> SafetyTier.SAFE
        score == 2 -> SafetyTier.MODERATE
        score <= 4 -> SafetyTier.HARD
        else -> SafetyTier.DANGER
    }

    /**
     * 由「所属列表给出的最低危险度」与「该武将自己的威胁评分」共同决定评级，取更危险的一侧。
     *
     * 为什么需要这一步：原实现只按"命中哪个列表"返回固定等级，
     * 同一个列表里的所有武将被一视同仁，`HeroEntry.threatScore`（1~5 分）
     * 虽然定义在知识库里、也被序列化，却**从未参与任何决策**。
     * 现在它真正生效；同时以列表等级作为下限，避免"危险列表里的武将因低分被降级放过"。
     */
    private fun resolveTier(listFloor: SafetyTier, threatScore: Int): SafetyTier {
        val byScore = tierForScore(threatScore)
        return if (byScore.weight >= listFloor.weight) byScore else listFloor
    }
}
