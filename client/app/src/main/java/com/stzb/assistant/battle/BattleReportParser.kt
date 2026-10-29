package com.stzb.assistant.battle

import com.stzb.assistant.knowledge.GameProfile
import com.stzb.assistant.knowledge.KnowledgeBaseManager

/**
 * 战报文本结构化解析器 (BattleReportParser)
 *
 * ## 它做什么 / 不做什么
 * 输入是战报页的 OCR 原文，输出 [BattleReport]。它**只做文本层面的字段抽取**，
 * 不负责截图、不负责识别、也不做任何战斗结论——那分别归 `OcrManager`、
 * `SlgRagEngine` 与本包里的统计/推演。
 *
 * ## 为什么所有中文词都来自知识包
 * 胜负、回合、兵力、损失这四类字样是**游戏文案**。过去这类词散落在引擎里
 * （`SCENE_*` / `VOCAB_*` 的教训就在这里）：换一款游戏，词对不上，
 * 解析器不会报错，只会静默地一条记录都产不出来。
 * 因此本文件**不出现任何中文字面量作为匹配词**，全部经
 * [GameProfile.sceneKeywords] 的 `REPORT_*` 组读取。
 *
 * ## fail-closed 三条
 *   1. 缺组 / 组内为空 => 该字段如实为"未知"，不退化成默认档；
 *   2. 胜负字样**同时命中两类**（例如"胜利"与"战败"都在屏幕上）=> 判 UNKNOWN，
 *      因为这说明要么 OCR 混进了别处文字，要么这是一张合成图；
 *   3. 一个可信字段都没读到 => 返回 null，调用方不应入库。
 */
object BattleReportParser {

    /** 组 ID：与知识包 `sceneKeywords` 的键逐字一致。 */
    private const val KEY_RESULT_WIN = "REPORT_RESULT_WIN"
    private const val KEY_RESULT_LOSE = "REPORT_RESULT_LOSE"
    private const val KEY_RESULT_DRAW = "REPORT_RESULT_DRAW"
    private const val KEY_ROUNDS = "REPORT_ROUNDS"
    private const val KEY_TROOPS = "REPORT_TROOPS"
    private const val KEY_LOSS = "REPORT_LOSS"

    /** 原文留档上限：够回溯，又不至于让一份战报库被单条记录撑爆。 */
    private const val RAW_KEEP_CHARS = 1200

    /** 回合数的可信区间。超出的读数只可能是把别处数字粘了过来。 */
    private val ROUNDS_RANGE = 1..30

    /**
     * 解析一段战报文本。
     *
     * @param text OCR 原文（可含换行/空格；内部会先压掉所有空白再做匹配，
     *             因为 OCR 常在汉字之间插空格，"胜 利" 与 "胜利" 必须一视同仁）
     * @param profile 用于取词的知识包；默认取当前激活档（切游戏后自动跟着变）
     * @return 至少读到一个可信字段时返回记录；否则 null
     */
    fun parse(
        text: String,
        profile: GameProfile = KnowledgeBaseManager.activeProfile
    ): BattleReport? {
        val compact = text.replace(Regex("\\s+"), "")
        if (compact.isBlank()) return null

        val outcome = resolveOutcome(compact, profile)
        val rounds = firstRounds(compact, wordsOf(profile, KEY_ROUNDS))
        val troops = numbersAfter(compact, wordsOf(profile, KEY_TROOPS))
        val losses = numbersAfter(compact, wordsOf(profile, KEY_LOSS))
        // 机制词来自 pveMechanicNotes 的键：既有的"守军暴走/反击/免疫"判定，
        // 这里只是把命中结果一并落到记录上，不新增第二套词表。
        val mechanics = profile.pveMechanicNotes.keys
            .map { it.replace(" ", "") }
            .filter { it.isNotBlank() && compact.contains(it) }
            .distinct()

        val report = BattleReport(
            capturedAtMs = System.currentTimeMillis(),
            gameId = profile.gameId,
            outcome = outcome,
            rounds = rounds,
            troopNumbers = troops,
            lossNumbers = losses,
            mechanicHits = mechanics,
            rawText = text.trim().take(RAW_KEEP_CHARS)
        )
        return if (report.hasAnySignal()) report else null
    }

    /** 取某一组词；缺组或空组 => 空表（调用方据此把字段判为未知）。 */
    private fun wordsOf(profile: GameProfile, groupId: String): List<String> =
        profile.sceneKeywords[groupId].orEmpty()
            .map { it.replace(" ", "") }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * 胜负判定：**恰好命中一类**才给结论。
     *
     * 命中 0 类 = 没读到 / 本游戏没配词；命中 2 类以上 = 互相矛盾。
     * 两种情况都只能是 UNKNOWN —— "两类都命中就挑严重的那个"看似保守，
     * 实际会凭空造出一个结论，而这里没有任何依据判断哪一个是本次战报的。
     */
    private fun resolveOutcome(compact: String, profile: GameProfile): BattleOutcome {
        val win = wordsOf(profile, KEY_RESULT_WIN).any { compact.contains(it) }
        val lose = wordsOf(profile, KEY_RESULT_LOSE).any { compact.contains(it) }
        val draw = wordsOf(profile, KEY_RESULT_DRAW).any { compact.contains(it) }
        val hits = listOf(win, lose, draw).count { it }
        return when {
            hits != 1 -> BattleOutcome.UNKNOWN
            win -> BattleOutcome.WIN
            lose -> BattleOutcome.LOSS
            else -> BattleOutcome.DRAW
        }
    }

    /**
     * 回合数：优先读"8回合"这种前置数字，其次读"回合数 8"这种后置数字。
     * 读到多个候选时取**第一个落在可信区间内**的（战报页里通常只有一个）。
     */
    private fun firstRounds(compact: String, markers: List<String>): Int {
        for (marker in markers) {
            val idx = compact.indexOf(marker)
            if (idx < 0) continue
            // 前置：marker 前 4 个字符内以数字收尾（"共8回合"）
            val head = compact.substring((idx - 4).coerceAtLeast(0), idx)
            Regex("(\\d{1,2})\\D{0,3}$").find(head)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { if (it in ROUNDS_RANGE) return it }
            // 后置：marker 后紧跟（可隔少量非数字）一个两位以内数字（"回合数 8"）
            val tail = compact.substring(idx + marker.length).take(6)
            Regex("^\\D{0,3}(\\d{1,2})").find(tail)?.groupValues?.get(1)?.toIntOrNull()
                ?.let { if (it in ROUNDS_RANGE) return it }
        }
        return 0
    }

    /**
     * 取出「标记词 + 少量分隔符 + 数字」里的所有数字。
     *
     * 只容许标记词与数字之间隔着最多 4 个非数字字符（"损失：1234"、"兵力     5678"）。
     * 不设上限会把同页下一个无关数字也吞进来，那会让战损率算出离谱的值。
     */
    private fun numbersAfter(compact: String, markers: List<String>): List<Int> {
        val found = mutableListOf<Int>()
        for (marker in markers) {
            var from = 0
            while (true) {
                val idx = compact.indexOf(marker, from)
                if (idx < 0) break
                val tail = compact.substring(idx + marker.length).take(12)
                Regex("^\\D{0,4}(\\d{1,7})").find(tail)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { if (it in 0..9_999_999) found.add(it) }
                from = idx + marker.length
            }
        }
        return found.distinct()
    }
}
