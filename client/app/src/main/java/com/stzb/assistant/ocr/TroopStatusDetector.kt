package com.stzb.assistant.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.util.regex.Pattern

/**
 * 部队出征面板、体能、2026新赛季士气120与毫秒卡秒感知器 (TroopStatusDetector)
 * 
 * 核心痛点解决：
 *   1. 武将体力精准识别 (xx/120)，规避体力满溢损失与体力不足白点；
 *   2. 【2026 征服赛季核心士气感知】：识别士气值 (满士气 120 增伤 16%，低于 80 严重衰减)，
 *      杜绝盲目远射空耗体力，强制判定满士气或补给线中转；
 *   3. 兵力伤病识别 (如 28500/30000)；
 *   4. 【攻城毫秒级卡秒中枢】：高精度提取行军耗时，推算 21:00:00 主力与 21:00:01 拆迁跟刀的
 *      绝对毫秒出征触发时间戳 (含网络与触控时延补偿)。
 */
object TroopStatusDetector {

    private const val TAG = "TroopStatusDetector"

    /**
     * 士气档位。
     *
     * 名字里**不再**带 120/100：这两个数过去是按率土写死的，可三战满士气是 100。
     * 写死的后果是切到三战后，一支士气 100（=满士气）的部队会被判成
     * “介于 100~119 的普通档”，而 120 那条判据在三战永远不可能命中——
     * 档位判定与当前游戏无关，等于没判。
     * 现在档位一律由**当前激活知识库**的上限与基准换算得到（见 [gradeMorale]）。
     */
    enum class MoraleGrade {
        OPTIMAL,     // 满士气（增伤档）
        NORMAL,      // 达到基准士气（无减损）
        LOW_PENALTY, // 低于基准士气（战力削弱，远射/打架需谨慎）
        UNKNOWN
    }

    /** 当前生效的游戏机制数值（体力/士气上限、基准士气都来自这里，可随热更变化）。 */
    private fun rules() = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules

    /**
     * 按当前知识库把士气值归档。
     * 基准值与上限值都取自知识库，因此热更改一个数，判档立刻跟着变。
     */
    private fun gradeMorale(morale: Int?): MoraleGrade {
        val r = rules()
        return when {
            morale == null -> MoraleGrade.UNKNOWN
            morale >= r.maxMorale -> MoraleGrade.OPTIMAL
            morale >= r.moraleStandard -> MoraleGrade.NORMAL
            else -> MoraleGrade.LOW_PENALTY
        }
    }

    data class TroopSlotDetail(
        val slotIndex: Int,          // 部队编号 1 ~ 5
        val stamina: Int?,           // 体力值 (0 ~ 上限，上限取自知识库)
        val isStaminaFull: Boolean,  // 体力是否已到知识库上限 (满溢，回复在白白浪费)
        val morale: Int?,            // 士气值 (0 ~ 上限，上限取自知识库)
        val moraleGrade: MoraleGrade,// 士气等级评估（相对当前游戏的上限/基准）
        val currentTroops: Int?,     // 当前兵力
        val maxTroops: Int?,         // 满编兵力
        val isFullHealth: Boolean    // 是否满编无伤
    )

    data class MarchTimingPlan(
        val travelDurationSec: Long,       // 游戏内行军耗时总秒数
        val estimatedArrivalTimeStr: String,// 游戏面板显示的预计抵达时间 (如 21:00:15)
        val targetHitEpochMs: Long,        // 同盟集火触敌绝对目标时间戳
        val optimalDispatchEpochMs: Long,  // 自动化助手应当点击“确定出征”的绝对毫秒时间戳
        val waitDelayMs: Long              // 距离出征点击还需等待的毫秒倒计时
    )

    /**
     * 体力形如 "88/120"。分母写死 120 的话，一旦某个游戏的体力上限不是 120，
     * 这一整项就再也读不出来（OCR 读到 "88/100" 直接不匹配 → 体力未知）。
     * 因此分母在**调用时**按当前知识库拼装，而不是做成静态常量。
     */
    private fun staminaPattern(maxStamina: Int): Pattern =
        Pattern.compile("(\\d{1,3})\\s*/\\s*" + maxStamina + "\\b")

    private val PATTERN_MORALE = Pattern.compile("(?:士气|气)[\\s:：]*(\\d{2,3})")
    private val PATTERN_TROOPS = Pattern.compile("(\\d{3,5})\\s*/\\s*(\\d{3,5})")
    private val PATTERN_TIME = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[:：]\\s*(\\d{2})")
    private val PATTERN_SHORT_TIME = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

    /**
     * 剖析出征面板中选中的部队卡片信息
     * @param cardRoiBitmap 部队卡片区域 (通常在出征界面中左部或选队列表)
     * @param slotIndex 当前分析的部队槽位 (1 ~ 5)
     */
    fun parseTroopCard(cardRoiBitmap: Bitmap, slotIndex: Int): TroopSlotDetail {
        val ocrResult = OcrManager.detectRoi(cardRoiBitmap)
        val text = ocrResult?.strRes ?: ""

        val r = rules()

        // 1. 体力抽取（分母 = 当前知识库的体力上限）
        var stamina: Int? = null
        val staminaMatcher = staminaPattern(r.maxStamina).matcher(text)
        if (staminaMatcher.find()) {
            stamina = staminaMatcher.group(1)?.toIntOrNull()
        }

        // 2. 士气抽取
        var morale: Int? = null
        val moraleMatcher = PATTERN_MORALE.matcher(text)
        if (moraleMatcher.find()) {
            morale = moraleMatcher.group(1)?.toIntOrNull()
        } else if (stamina != r.maxMorale && text.contains(r.maxMorale.toString())) {
            // 容错：OCR 有时把"士气"标签吃掉、只剩一个数字，于是按"出现了满士气数值"补。
            //
            // ⚠️ 这仍是**猜测**，且是已知弱点：卡片上任何位置出现该数字都会命中。
            // 之所以保留，是因为率土部队卡确实常只读到裸数字；但它绝不能再写死 120——
            // 三战满士气 100 时，"100/xxx" 的体力分母就会被当成满士气，凭空判成"士气极佳"。
            // 同时先排除"体力本身已经等于该数值"的情况，避免把满体力误读成满士气。
            morale = r.maxMorale
        }

        val moraleGrade = gradeMorale(morale)

        // 3. 兵力抽取
        var currentTroops: Int? = null
        var maxTroops: Int? = null
        val troopsMatcher = PATTERN_TROOPS.matcher(text)
        if (troopsMatcher.find()) {
            currentTroops = troopsMatcher.group(1)?.toIntOrNull()
            maxTroops = troopsMatcher.group(2)?.toIntOrNull()
        }

        val isFullHealth = currentTroops != null && maxTroops != null && currentTroops >= maxTroops

        Log.d(TAG, "部队[$slotIndex] 感知完成: 体力=$stamina, 士气=$morale($moraleGrade), 兵力=$currentTroops/$maxTroops")

        return TroopSlotDetail(
            slotIndex = slotIndex,
            stamina = stamina,
            isStaminaFull = (stamina != null && stamina >= r.maxStamina),
            morale = morale,
            moraleGrade = moraleGrade,
            currentTroops = currentTroops,
            maxTroops = maxTroops,
            isFullHealth = isFullHealth
        )
    }

    /**
     * 【攻城毫秒级卡秒中枢】
     * 根据出征面板显示的行军耗时与同盟要求的统一触敌绝对时刻，计算点击“出征”的精确毫秒时间戳
     * 
     * @param marchTimeRoiBitmap 出征面板右下角或底部行军耗时显示区 (如 "耗时 00:03:15")
     * @param targetHitEpochMs 同盟集火要求的触敌绝对时间戳 (如 21:00:00.000 的 epoch ms)
     * @param networkJitterCompensationMs 系统触控发起到游戏服务端判定的网络/渲染时延补偿 (推荐 80~120ms)
     */
    fun calculateCardSecondTiming(
        marchTimeRoiBitmap: Bitmap,
        targetHitEpochMs: Long,
        networkJitterCompensationMs: Long = 100L
    ): MarchTimingPlan? {
        val ocrResult = OcrManager.detectRoi(marchTimeRoiBitmap) ?: return null
        val text = ocrResult.strRes

        var durationSec: Long = 0L

        // 匹配 HH:MM:SS
        val fullMatcher = PATTERN_TIME.matcher(text)
        if (fullMatcher.find()) {
            val h = fullMatcher.group(1)?.toLongOrNull() ?: 0L
            val m = fullMatcher.group(2)?.toLongOrNull() ?: 0L
            val s = fullMatcher.group(3)?.toLongOrNull() ?: 0L
            durationSec = h * 3600 + m * 60 + s
        } else {
            // 匹配 MM:SS
            val shortMatcher = PATTERN_SHORT_TIME.matcher(text)
            if (shortMatcher.find()) {
                val m = shortMatcher.group(1)?.toLongOrNull() ?: 0L
                val s = shortMatcher.group(2)?.toLongOrNull() ?: 0L
                durationSec = m * 60 + s
            }
        }

        if (durationSec <= 0L) {
            Log.w(TAG, "未能在面板中解析出行军耗时: rawText='$text'")
            return null
        }

        val now = System.currentTimeMillis()
        val durationMs = durationSec * 1000L

        // 核心公式：绝对点击出征时刻 = 目标触敌时刻 - 行军耗时 - 拟人与网络补偿
        val optimalDispatchEpochMs = targetHitEpochMs - durationMs - networkJitterCompensationMs
        val waitDelayMs = optimalDispatchEpochMs - now

        Log.i(TAG, "【毫秒卡秒测算成功】行军耗时: ${durationSec}s, 目标触敌: $targetHitEpochMs, 最佳出征时刻: $optimalDispatchEpochMs, 需等待: ${waitDelayMs}ms")

        return MarchTimingPlan(
            travelDurationSec = durationSec,
            estimatedArrivalTimeStr = text,
            targetHitEpochMs = targetHitEpochMs,
            optimalDispatchEpochMs = optimalDispatchEpochMs,
            waitDelayMs = waitDelayMs
        )
    }
}
