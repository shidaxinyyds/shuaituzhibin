package com.stzb.assistant.battle

import org.json.JSONArray
import org.json.JSONObject

/**
 * 战报的胜负结论 (BattleOutcome)
 *
 * [UNKNOWN] 不是"平局"、也不是"还没打完"，而是**我们没能读到、或读到了互相矛盾的字样**。
 * 它必须能被表达出来：把"没读到胜负"落成 [WIN] 会让战报统计一路偏乐观，
 * 而这份统计是要拿去给玩家看胜率、给推演做校准的。
 */
enum class BattleOutcome {
    WIN,
    LOSS,
    DRAW,
    UNKNOWN
}

/**
 * 一场战报的结构化结果 (BattleReport)
 *
 * ## 为什么要有它
 * 战报页是端上唯一能拿到的"真值"：它同时给出了胜负、回合数、双方兵力与损失。
 * 过去这些信息只以 `SlgRagEngine.diagnoseBattleReport` 的**一段自然语言**存在——
 * 玩家看完就没了，机器既没法累计胜率，也没法拿它校准推演模型。
 * 这里把可机器复用的部分抽出来，落成 JSONL 存盘（[BattleReportStore]）。
 *
 * ## 两条口径
 *   1. **只存读得到的字段**：读不到就留 0 / 空表 / [BattleOutcome.UNKNOWN]，
 *      绝不补一个"看起来合理"的默认值（那等于伪造真值）。
 *   2. **一个可信字段都没有的战报不入库**：[hasAnySignal] 为 false 时
 *      调用方应直接丢弃——存一堆空壳记录只会把统计的分母弄脏。
 *
 * @param capturedAtMs 抓取时刻（墙钟毫秒）
 * @param gameId 产出这份战报的知识包 id（切游戏后统计不会串味）
 * @param outcome 胜负；[BattleOutcome.UNKNOWN] = 未读到/自相矛盾
 * @param rounds 回合数；0 = 未读到
 * @param troopNumbers 战报里「兵力 xxxx」读到的数字，按出现顺序（可能含双方）
 * @param lossNumbers 战报里「损失 xxxx」读到的数字，按出现顺序
 * @param mechanicHits 命中知识包 `pveMechanicNotes` 的机制词（暴走/反击/狂怒/免疫…）
 * @param rawText 识别原文（截断保存，用于回溯"这条记录是怎么来的"）
 */
data class BattleReport(
    val capturedAtMs: Long,
    val gameId: String,
    val outcome: BattleOutcome,
    val rounds: Int,
    val troopNumbers: List<Int>,
    val lossNumbers: List<Int>,
    val mechanicHits: List<String>,
    val rawText: String
) {

    /**
     * 本次战报的「（损失, 兵力）」配对；配不出来返回 null。
     *
     * 口径：第一个 > 0 的兵力数与第一个损失数配对——战报页通常我方在前，
     * 而**这个假设必须能被玩家看见**：摘要里会把原始数一并印出来
     * （“战损 16.7%（3000/18000）”），配错了（把守方的数配进来）一眼就能发现。
     * 只印百分比不印原始数，一个错配的数就无从复核，还会被当成真值去调兵。
     *
     * 损失 > 兵力时直接返回 null（说明配对错了，宁可不给结论）；
     * 损失为 0 是有效观测（零战损），与“没读到”必须区分开。
     */
    fun lossPair(): Pair<Int, Int>? {
        val troops = troopNumbers.firstOrNull { it > 0 } ?: return null
        val loss = lossNumbers.firstOrNull() ?: return null
        if (loss < 0 || loss > troops) return null
        return loss to troops
    }

    /**
     * 本次战报能算出来的**实测战损率**（损失 ÷ 兵力）；算不出返回 null。
     *
     * 具体用哪两个数配对见 [lossPair]；这里只负责把配对结果换算成比例。
     */
    fun observedLossRate(): Double? {
        val pair = lossPair() ?: return null
        return pair.first.toDouble() / pair.second.toDouble()
    }

    /**
     * 是否至少读到了一个可信字段。
     *
     * 全空壳（胜负未知、无回合、无数字、无机制词）的战报没有任何信息量，
     * 入库后只会让"近 N 场胜率"的分母变大而分子不变。
     */
    fun hasAnySignal(): Boolean =
        outcome != BattleOutcome.UNKNOWN || rounds > 0 ||
            troopNumbers.isNotEmpty() || lossNumbers.isNotEmpty() || mechanicHits.isNotEmpty()

    /**
     * 一行摘要（入库提示 / 数据面板用）：**只印读到的字段**。
     *
     * 不印“胜负未知、回合未知”这类占位：一串“未知”会把真正关键的那一项盖住，
     * 而玩家从“没印”就能看出没读到，不需要额外占位符。
     */
    fun summary(): String {
        val parts = mutableListOf<String>()
        when (outcome) {
            BattleOutcome.WIN -> parts.add("胜")
            BattleOutcome.LOSS -> parts.add("负")
            BattleOutcome.DRAW -> parts.add("平")
            BattleOutcome.UNKNOWN -> Unit
        }
        if (rounds > 0) parts.add("$rounds 回合")
        lossPair()?.let { pair ->
            val p = Math.round(pair.first.toDouble() / pair.second.toDouble() * 1000.0) / 10.0
            val pct = if (p == Math.floor(p)) "${p.toInt()}%" else "$p%"
            // 原始数一并印出：这是唯一能让玩家当场发现“配错阵营”的信息。
            parts.add("战损 $pct（${pair.first}/${pair.second}）")
        }
        if (mechanicHits.isNotEmpty()) parts.add("机制 " + mechanicHits.joinToString("/"))
        return if (parts.isEmpty()) "（没有一个字段可读）" else parts.joinToString(" ｜ ")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("captured_at_ms", capturedAtMs)
        put("game_id", gameId)
        put("outcome", outcome.name)
        put("rounds", rounds)
        put("troop_numbers", JSONArray(troopNumbers))
        put("loss_numbers", JSONArray(lossNumbers))
        put("mechanic_hits", JSONArray(mechanicHits))
        put("raw_text", rawText)
    }

    companion object {

        /**
         * 从 JSONL 的一行还原；结构坏掉返回 null（单条坏记录不能让整份战报库读不出来）。
         *
         * 枚举名用 `valueOf` 而不用 `when (name)`：将来给 [BattleOutcome] 加档位时，
         * 这里不需要跟着改；真读到不认识的名字（旧版本写下的），退回 UNKNOWN。
         */
        fun fromJson(obj: JSONObject): BattleReport? = try {
            BattleReport(
                capturedAtMs = obj.optLong("captured_at_ms", 0L),
                gameId = obj.optString("game_id", ""),
                outcome = runCatching { BattleOutcome.valueOf(obj.optString("outcome", "UNKNOWN")) }
                    .getOrDefault(BattleOutcome.UNKNOWN),
                rounds = obj.optInt("rounds", 0),
                troopNumbers = intList(obj.optJSONArray("troop_numbers")),
                lossNumbers = intList(obj.optJSONArray("loss_numbers")),
                mechanicHits = strList(obj.optJSONArray("mechanic_hits")),
                rawText = obj.optString("raw_text", "")
            )
        } catch (e: Throwable) {
            null
        }

        /** 只收非负整数：负数是坏数据，留着会把战损率算成负数。 */
        private fun intList(arr: JSONArray?): List<Int> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val v = arr.optInt(i, -1)
                if (v >= 0) v else null
            }
        }

        private fun strList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val v = arr.optString(i, "")
                if (v.isNotBlank()) v else null
            }
        }
    }
}
