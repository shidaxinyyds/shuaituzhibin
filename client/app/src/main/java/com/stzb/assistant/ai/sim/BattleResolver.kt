package com.stzb.assistant.ai.sim

import com.stzb.assistant.battle.BattleOutcome
import com.stzb.assistant.battle.BattleReport
import com.stzb.assistant.knowledge.BattleModel
import com.stzb.assistant.knowledge.DefenderDatabase
import com.stzb.assistant.knowledge.GameProfile
import com.stzb.assistant.knowledge.KnowledgeBaseManager

/**
 * 战损定性档 (BattleOutlook)
 *
 * 它刻意**不叫** "胜/负"：本模型算的是**战损率**，不是战斗结果。
 * 翻车（[DEFEAT]）的含义是"战损高到这一队基本废了"，而不是"一定会输"——
 * 兵种克制、战法触发、守军分兵都会让真实结果偏离估算。
 * 把这两件事混为一谈，就是在给玩家一个我们并不具备的结论。
 */
enum class BattleOutlook {
    /** 战损低于基准：可以放心打 */
    LOW_LOSS,

    /** 正常损耗：按推荐兵力打通常能接受 */
    ACCEPTABLE,

    /** 重损：要打就得接受掉一大截兵 */
    COSTLY,

    /** 翻车档：估算战损高到这一队基本废了，建议换地/补兵 */
    DEFEAT
}

/** 推演结果：要么给出一个区间，要么如实说"算不了"与原因。 */
sealed class BattleSimResult {

    data class Ok(val estimate: BattleEstimate) : BattleSimResult()

    /**
     * 算不了。
     *
     * 这不是错误分支，而是**正常分支**：知识库没有该等级的地块基线、守军总兵力未知、
     * 带兵数低于推演下限时，这里必须返回原因，而不是硬凑一个数字。
     */
    data class Unavailable(val reason: String) : BattleSimResult()
}

/**
 * 一次推演的完整输入与输出。
 *
 * @param pointLossRate 点估计战损率（只用于算区间与分档，**不单独展示**）
 * @param lossLowRate 区间下界
 * @param lossHighRate 区间上界
 */
data class BattleEstimate(
    val landLevel: Int,
    val mySoldiers: Int,
    val referenceSoldiers: Int,
    val garrison: Int,
    val threatScore: Int,
    val threatName: String,
    val morale: Int,
    val moraleStandard: Int,
    val pointLossRate: Double,
    val lossLowRate: Double,
    val lossHighRate: Double,
    val outlook: BattleOutlook
) {
    /** 预计损失兵力的区间（按输入的带兵数换算，比百分比更直观）。 */
    fun lossSoldiersLow(): Int = Math.round(lossLowRate * mySoldiers).toInt()
    fun lossSoldiersHigh(): Int = Math.round(lossHighRate * mySoldiers).toInt()
}

/** 战报实测与模型口径的对照结果。 */
data class BattleVerification(
    val measuredLossRate: Double,
    val outlook: BattleOutlook,
    val verdict: String
)

/**
 * PVE 战斗推演器 (BattleResolver)
 *
 * ## 这个模型的能力边界（务必先读这段）
 * 它是**区间估算**，不是战斗公式。端上拿不到的输入有：双方武将等级/加点/装备、
 * 战法组合与发动概率、兵种克制关系、地形与士气加成。任何一个都足以让真实战损
 * 偏离估算几十个百分点。因此本模型只承诺两件事：
 *   1. **方向正确**：带兵越多/守将越弱/士气越高 => 估算战损越低（单调性有闸门守着）；
 *   2. **不装懂**：输入不全就返回 [BattleSimResult.Unavailable]，绝不输出单点结论。
 *
 * ## 全部系数与锚点都来自知识库
 *   * 系数：`GameProfile.battleModel`（可热更，见 [BattleModel]）；
 *   * 基准兵力：`LandSuggestion.recommendedSoldiers`；
 *   * 守军总量：`LandSuggestion.defenderTotalSoldiers`（0 = 未知 => 拒算）；
 *   * 守将威胁：`HeroEntry.threatScore`（未命中库 = 0 分，不臆断成高危）。
 *
 * 本文件不含任何一款游戏的数值常量：换游戏只需换知识包。
 */
object BattleResolver {

    /** 战损率上限：留 5% 的余量给"几乎全灭"这种极端情形，不让区间顶到 100%。 */
    private const val MAX_LOSS_RATE = 0.95

    /** 区间上界的天花板（战损不可能超过 100%）。 */
    private const val MAX_LOSS_CEILING = 0.98

    /**
     * 正向推演：打某一块地大概会掉多少兵。
     *
     * @param landLevel 地块等级（必须是知识库里登记过的等级）
     * @param mySoldiers 本次出征的兵力
     * @param defenderNames 已识别到的守将名（可空表；空表 = 威胁按 0 分算，
     *                      但输出里会明确写"未识别到守将"）
     * @param morale 出征时的士气；传 -1 表示"未知"——未知时按基准士气算，
     *               并在输出里标注"士气未读到"，而不是假装士气是满的
     */
    fun estimate(
        landLevel: Int,
        mySoldiers: Int,
        defenderNames: List<String> = emptyList(),
        morale: Int = -1,
        profile: GameProfile = KnowledgeBaseManager.activeProfile
    ): BattleSimResult {
        val bm = profile.battleModel
        if (mySoldiers < bm.minSoldiersForSim) {
            return BattleSimResult.Unavailable(
                "带兵 $mySoldiers 低于推演下限 ${bm.minSoldiersForSim}：这个量级的战斗，" +
                    "任何百分比都给不出有意义的差异。"
            )
        }

        val suggestion = profile.defenderDb.landSuggestions[landLevel]
            ?: return BattleSimResult.Unavailable(
                "知识库没有 Lv$landLevel 的地块基线（已登记等级：" +
                    profile.defenderDb.landSuggestions.keys.sorted().joinToString("/") +
                    "），无法给出有依据的战损区间。"
            )
        val reference = suggestion.recommendedSoldiers
        if (reference <= 0) {
            return BattleSimResult.Unavailable(
                "Lv$landLevel 的推荐带兵在知识库里是 $reference，没有基准就算不出兵力比。"
            )
        }
        val garrison = suggestion.defenderTotalSoldiers
        if (garrison <= 0) {
            return BattleSimResult.Unavailable(
                "Lv$landLevel 的守军总兵力在知识库里记为 0（尚未录入可靠数据），" +
                    "推演会因此高估可打性，本次不给结论。"
            )
        }

        val moraleStandard = profile.rules.moraleStandard.coerceAtLeast(1)
        val effectiveMorale = if (morale < 0) moraleStandard else morale.coerceIn(0, moraleStandard)

        val ratio = mySoldiers.toDouble() / reference.toDouble()
        val strengthTerm =
            if (ratio >= 1.0) bm.lossBaseRate / ratio
            else bm.lossBaseRate + bm.underStrengthPenalty * (1.0 - ratio)

        val threatScore = defenderNames
            .map { threatScoreOf(it, profile.defenderDb) }
            .maxOrNull() ?: 0
        val threatName = defenderNames
            .map { it.trim().replace(" ", "") }
            .filter { it.isNotBlank() }
            .maxByOrNull { threatScoreOf(it, profile.defenderDb) } ?: ""

        val moraleShortfall = (moraleStandard - effectiveMorale).toDouble() / moraleStandard.toDouble()
        val moraleTerm = bm.moralePenaltyAtZero * moraleShortfall.coerceIn(0.0, 1.0)

        val point = (strengthTerm +
            threatScore * bm.threatPenaltyPerPoint +
            moraleTerm).coerceIn(0.0, MAX_LOSS_RATE)

        val low = (point * (1.0 - bm.uncertaintyRatio)).coerceIn(0.0, MAX_LOSS_RATE)
        val high = (point * (1.0 + bm.uncertaintyRatio)).coerceIn(0.0, MAX_LOSS_CEILING)

        return BattleSimResult.Ok(
            BattleEstimate(
                landLevel = landLevel,
                mySoldiers = mySoldiers,
                referenceSoldiers = reference,
                garrison = garrison,
                threatScore = threatScore,
                threatName = threatName,
                morale = effectiveMorale,
                moraleStandard = moraleStandard,
                pointLossRate = point,
                lossLowRate = low,
                lossHighRate = high,
                outlook = outlookOf(point, bm)
            )
        )
    }

    /**
     * 反向校验：拿一份真实战报的实测战损，对照模型的分档阈值。
     *
     * 为什么这一层很重要：推演模型的第一版系数一定是拍的（见 [BattleModel] 的注释）。
     * 没有这条对照，那些系数永远不会被真实数据推翻；而有了它，玩家每打一次地，
     * 都能看到"这次实测落在哪一档、离模型假设差多远"。
     *
     * @return 战报里读不到战损率时返回 null（不是 0——0 是有效观测）
     */
    fun verify(
        report: BattleReport,
        profile: GameProfile = KnowledgeBaseManager.activeProfile
    ): BattleVerification? {
        val measured = report.observedLossRate() ?: return null
        val bm = profile.battleModel
        val outlook = outlookOf(measured, bm)
        val verdict = when (outlook) {
            BattleOutlook.LOW_LOSS ->
                "实测战损低于模型基准（${pct(bm.lossBaseRate)}），这次打得比估算更干净。"
            BattleOutlook.ACCEPTABLE ->
                "实测战损落在模型的正常损耗区间内，与估算口径一致。"
            BattleOutlook.COSTLY ->
                "实测战损已越过重损阈值（${pct(bm.costlyLossRate)}）：" +
                    "说明这块地的守军或你的兵力配比比模型假设更差。"
            BattleOutlook.DEFEAT ->
                "实测战损已越过翻车阈值（${pct(bm.defeatLossRate)}）：" +
                    "这一队基本被打空，强烈建议先补兵、换软柿子，或提高带兵量再打。"
        }
        val tail = when (report.outcome) {
            BattleOutcome.LOSS -> "（战报结论：作战失败）"
            BattleOutcome.DRAW -> "（战报结论：平局/未清干净）"
            BattleOutcome.WIN -> "（战报结论：胜利）"
            BattleOutcome.UNKNOWN -> "（胜负字样未读到，只看战损）"
        }
        return BattleVerification(measuredLossRate = measured, outlook = outlook, verdict = verdict + tail)
    }

    /** 战损率 -> 定性档。阈值全部来自知识库，不在代码里拍数。 */
    private fun outlookOf(lossRate: Double, bm: BattleModel): BattleOutlook = when {
        lossRate >= bm.defeatLossRate -> BattleOutlook.DEFEAT
        lossRate >= bm.costlyLossRate -> BattleOutlook.COSTLY
        lossRate >= bm.lossBaseRate -> BattleOutlook.ACCEPTABLE
        else -> BattleOutlook.LOW_LOSS
    }

    /**
     * 取某个守将名在守军库里的威胁分；没命中库返回 0。
     *
     * 返 0 而不是 2（DefenderEvaluator 兜底成 MODERATE）是**有意的分工**：
     * 那里评的是"这块地危险不危险"，陌生守将默认按中等风险才安全；
     * 这里算的是"战损要加多少个百分点"，没有任何依据的武将不该凭空抬高数字。
     */
    private fun threatScoreOf(name: String, db: DefenderDatabase): Int {
        val clean = name.trim().replace(" ", "")
        if (clean.isEmpty()) return 0
        val all = db.dangerHeroes + db.hardHeroes + db.moderateHeroes + db.safeHeroes
        val hit = all.firstOrNull { hero ->
            val hn = hero.name.replace(" ", "")
            hn.isNotBlank() && (clean.contains(hn) || hn.contains(clean))
        }
        return hit?.threatScore ?: 0
    }

    private fun pct(ratio: Double): String {
        val p = Math.round(ratio * 1000.0) / 10.0
        return if (p == Math.floor(p)) "${p.toInt()}%" else "$p%"
    }
}
