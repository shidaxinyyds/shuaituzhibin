package com.stzb.assistant.ai.sim

import com.stzb.assistant.battle.BattleReport
import com.stzb.assistant.knowledge.GameProfile
import com.stzb.assistant.knowledge.KnowledgeBaseManager
import com.stzb.assistant.ocr.DefenderEvaluator

/**
 * 守军结论的"合流层" (DefenderOutlook)
 *
 * ## 为什么单独一层、而不是改 [DefenderEvaluator]
 * [DefenderEvaluator] 是**危险度天梯**：它回答"这块地危险不危险"，靠的是守将名单命中
 * 知识库档位，输入是名字、输出是离散档位。它没有、也不该有兵力概念。
 * 战斗推演是另一个问题：它回答"打下去会掉多少兵"，靠的是兵力比与威胁分加权。
 *
 * 两者结论必须**同时**呈现给玩家，但不能互相改写：
 *   * 天梯说 DANGER、推演说"损失可控" —— 两个都要说，玩家自己决定（通常是别打）；
 *   * 天梯说 SAFE、推演说"翻车" —— 这说明兵力严重不足，同样两个都要说。
 * 任何一方去覆盖另一方，都是在用一个维度的结论冒充两个维度。
 *
 * 因此本类**只读** [DefenderEvaluator.EvaluationResult]，不改它的评级与推荐语，
 * 只在其后追加推演段。`tools/validate_decision_logic.py` 对 evaluate() 的结构是强耦合的，
 * 这条边界同时也是那道闸门的边界。
 */
object DefenderOutlook {

    /**
     * 生成"打地推演"整段文本。
     *
     * @param result 已有的守军天梯评估结果（只读取，不做任何改写）
     * @param landLevel 地块等级；不知道就传 0，会如实返回"不给结论"
     * @param mySoldiers 本次带兵数；不知道就传 0，同上
     * @param morale 士气；-1 = 未读到（按基准士气算并在文本里标注）
     */
    fun describe(
        result: DefenderEvaluator.EvaluationResult,
        landLevel: Int,
        mySoldiers: Int,
        morale: Int = -1,
        profile: GameProfile = KnowledgeBaseManager.activeProfile
    ): String {
        val names = result.matchedDefenders.map { it.name }
        val prefix = buildString {
            append("🎯【打地推演】经验估算，非官方公式\n")
            // 天梯没读到守军名单时，推演里的"守将威胁"恒为 0 分 ——
            // 这是一个会系统性低估战损的输入缺口，必须写在结论之前，而不是藏在注释里。
            if (result.tier == DefenderEvaluator.SafetyTier.UNKNOWN) {
                append("  ⚠️ 守军名单未读到：本次推演**不含守将威胁**，只按兵力比与士气估算，结果偏乐观。\n")
            }
        }
        val sim = BattleResolver.estimate(landLevel, mySoldiers, names, morale, profile)
        // 士气没读到时必须写在结论里：推演默认按基准士气代入，而士气低会显著抬高战损。
        // 不注明“代入值”，玩家会把“士气 120/120（基准）”当成实测读数。
        val moraleNote = if (morale < 0) {
            "  ℹ️ 士气未读到：按知识库基准士气代入（士气低会显著抬高战损）。\n"
        } else {
            ""
        }
        return when (sim) {
            is BattleSimResult.Unavailable -> prefix + moraleNote + "  本次不给结论：${sim.reason}"
            is BattleSimResult.Ok -> prefix + moraleNote + renderEstimate(sim.estimate, profile, morale >= 0)
        }
    }

    /**
     * @param moraleMeasured 士气是玩家填的实测值（true）还是按基准代入的（false）。
     *        两者必须分开标：把一个代入值印成实测读数，玩家会据此判断“士气没问题”，
     *        而士气差一档足以把结论从“可打”推到“重损”。
     */
    private fun renderEstimate(e: BattleEstimate, profile: GameProfile, moraleMeasured: Boolean): String {
        val threatDesc = when {
            e.threatName.isNotBlank() -> "${e.threatScore} 分（${e.threatName}）"
            else -> "0 分（未识别到守将）"
        }
        val moraleDesc = if (e.morale == e.moraleStandard) {
            "${e.morale}/${e.moraleStandard}（基准）"
        } else {
            "${e.morale}/${e.moraleStandard}（低于基准，战损已上调）"
        }
        val moraleLabel = if (moraleMeasured) "（实测）" else "（代入值）"
        return buildString {
            append("  • 你带兵 ${e.mySoldiers} ｜ 知识库推荐 ${e.referenceSoldiers}")
            append(" ｜ 守军约 ${e.garrison}\n")
            append("  • 守将威胁 $threatDesc\n")
            append("  • 士气$moraleLabel$moraleDesc\n")
            append("  → 预计战损 ${pct(e.lossLowRate)}~${pct(e.lossHighRate)}")
            append("（约 ${e.lossSoldiersLow()}~${e.lossSoldiersHigh()} 兵）")
            append("，判定：${outlookDesc(e.outlook)}\n")
            val bm = profile.battleModel
            append("  ↳ 区间宽度由知识库 uncertaintyRatio=${bm.uncertaintyRatio} 给出；")
            append("真实战损还受战法触发与兵种克制影响，务必留补刀余量。")
        }
    }

    /**
     * 把一份战报的实测战损与模型口径对照成一段文本。
     *
     * @return 战报里读不到战损率时返回 null（调用方不应上屏"0%"——
     *         那会被读成"零战损"，而事实是"没读到"）
     */
    fun describeVerification(
        report: BattleReport,
        profile: GameProfile = KnowledgeBaseManager.activeProfile
    ): String? {
        val v = BattleResolver.verify(report, profile) ?: return null
        val p = Math.round(v.measuredLossRate * 1000.0) / 10.0
        return "🔍【战报实测对照】本次战损 $p% → ${v.verdict}"
    }

    private fun outlookDesc(outlook: BattleOutlook): String = when (outlook) {
        BattleOutlook.LOW_LOSS -> "🟢 低损（低于基准战损）"
        BattleOutlook.ACCEPTABLE -> "🟡 正常损耗（与基准战损同档）"
        BattleOutlook.COSTLY -> "🟠 重损（越过重损阈值，做好补兵）"
        BattleOutlook.DEFEAT -> "🔴 翻车档（这一队基本会被打空，建议换地或加兵）"
    }

    private fun pct(ratio: Double): String {
        val p = Math.round(ratio * 1000.0) / 10.0
        return if (p == Math.floor(p)) "${p.toInt()}%" else "$p%"
    }
}
