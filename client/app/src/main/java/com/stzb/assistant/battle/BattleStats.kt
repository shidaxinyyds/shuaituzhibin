package com.stzb.assistant.battle

/**
 * 战报统计 (BattleStats)
 *
 * 把 [BattleReport] 记录聚合成一段可直接上屏的文本（数据面板用）。
 *
 * ## 为什么统计口径要写死在这里、而不是散在 UI
 * 同一个"胜率"，把 UNKNOWN 算进分母和不算进分母能差出十几个百分点。
 * 这份统计会被玩家拿去决定"这一队还打不打"，所以口径必须只有一份，
 * 且必须在界面上**把分母写出来**（"近 20 场里有 14 场读到了胜负"），
 * 而不是只印一个漂亮的百分比。
 *
 * ## 三条口径
 *   1. UNKNOWN **不进胜率分母**：它代表"没读到"，不代表"输了"；
 *   2. 样本少于 [MIN_SAMPLE_FOR_CONFIDENCE] 时，结论前面必须挂"样本不足"；
 *   3. 回合/战损只用**有该字段的记录**求均值，且把参与计算的条数印出来。
 */
object BattleStats {

    /** 低于这个样本数就不给"趋势"结论，只给原始计数。 */
    const val MIN_SAMPLE_FOR_CONFIDENCE = 5

    /**
     * 渲染一段统计文本。
     *
     * @param reports 最近若干场（调用方通常传 [BattleReportStore.recent]）
     * @param windowLabel 这批数据的口径说明，例如"近 20 场"
     */
    fun render(reports: List<BattleReport>, windowLabel: String = "近 ${reports.size} 场"): String {
        if (reports.isEmpty()) {
            return "📊 战报统计：$windowLabel，暂无样本。\n" +
                "把游戏切到战报详情页后点「战报会诊」即可自动入库（不额外读取任何操作）。"
        }

        val win = reports.count { it.outcome == BattleOutcome.WIN }
        val loss = reports.count { it.outcome == BattleOutcome.LOSS }
        val draw = reports.count { it.outcome == BattleOutcome.DRAW }
        val unknown = reports.count { it.outcome == BattleOutcome.UNKNOWN }
        val decisive = win + loss + draw

        return buildString {
            append("📊 战报统计（$windowLabel，共 ${reports.size} 场）\n")
            if (decisive > 0) {
                append("  • 读到胜负的 $decisive 场：胜 $win / 负 $loss / 平 $draw")
                append("（胜率 ${pct(win.toDouble() / decisive.toDouble())}）\n")
            } else {
                append("  • 一场都没读到胜负字样，不给胜率（避免把「没读到」当成「没赢」）\n")
            }
            if (unknown > 0) {
                append("  • 另有 $unknown 场未读到胜负（不计入上面的胜率分母）\n")
            }

            val roundSamples = reports.mapNotNull { if (it.rounds > 0) it.rounds else null }
            if (roundSamples.isNotEmpty()) {
                append("  • 平均回合：${oneDecimal(roundSamples.average())}")
                append("（${roundSamples.size} 场有回合数）\n")
            } else {
                append("  • 回合数：一场都没读到\n")
            }

            val rates = reports.mapNotNull { it.observedLossRate() }
            if (rates.isNotEmpty()) {
                append("  • 平均实测战损：${pct(rates.average())}")
                append("（${rates.size} 场能同时读到兵力与损失）\n")
            } else {
                append("  • 实测战损：还没有能同时读到「兵力」与「损失」的战报\n")
            }

            val mechanics = reports.flatMap { it.mechanicHits }
                .groupingBy { it }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3)
            if (mechanics.isNotEmpty()) {
                append("  • 出现最多的守军机制：")
                append(mechanics.joinToString("、") { "${it.key}(${it.value}次)" })
                append('\n')
            }

            if (reports.size < MIN_SAMPLE_FOR_CONFIDENCE) {
                append("⚠️ 样本不足 $MIN_SAMPLE_FOR_CONFIDENCE 场，以上只作记录，不构成趋势判断。")
            } else if (decisive < MIN_SAMPLE_FOR_CONFIDENCE) {
                append("⚠️ 读到胜负的场次不足 $MIN_SAMPLE_FOR_CONFIDENCE 场，胜率仅供参考。")
            }
        }
    }

    /** 百分比取整：战损/胜率这类量给到小数点后 1 位就已经超出模型精度了。 */
    private fun pct(ratio: Double): String {
        val p = Math.round(ratio * 1000.0) / 10.0
        return if (p == Math.floor(p)) "${p.toInt()}%" else "$p%"
    }

    private fun oneDecimal(value: Double): String {
        val v = Math.round(value * 10.0) / 10.0
        return if (v == Math.floor(v)) "${v.toInt()}" else "$v"
    }
}
