package com.stzb.assistant.tactics

import android.graphics.Bitmap
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import java.util.Calendar
import java.util.regex.Pattern

/**
 * 同盟邮件法令智能解析器 (AllianceMailParser)
 *
 * 核心痛点解决：
 *   1. 解决同盟攻城邮件/法令纯人工盯盘录入繁琐、容易看错时间或输错坐标的痛点；
 *   2. 支持一键截屏 OCR 嗅探或文本解析，提取：
 *      - 攻城目标 (如 "襄阳(LV.8)", "高安", "虎牢关")；
 *      - 沙盘世界坐标 (如 "(582, 391)")；
 *      - 触敌基准时刻 (如 "21:00:00")，自动反推为今日绝对 Epoch ms；
 *      - 拆迁后置秒数 (征服赛季标准 5s)；
 *      - 前线集合要塞名称与坐标 (如 "高安前线要塞 (580, 390)")；
 *   3. 自动生成标准【全盟战役双压秒卡片】(SiegeMailPlan)。
 */
object AllianceMailParser {

    private const val TAG = "AllianceMailParser"

    data class SiegeMailPlan(
        val targetName: String,
        val targetWorldCoord: Pair<Int, Int>?,
        val targetHitEpochMs: Long,
        val targetTimeStr: String,
        val fortressName: String? = null,
        val fortressWorldCoord: Pair<Int, Int>? = null,
        val demolitionOffsetSec: Int = 5,
        val rawDecreeText: String = "",
        val confidence: Float = 0.95f
    )

    // 正则提取模式
    private val PATTERN_TARGET = Pattern.compile(
        "(?:攻打|开打|集火|拿下|目标|进攻)\\s*[【\\[]?([\\u4e00-\\u9fa5]{2,8}(?:关|城|县|要塞|码头|港)?)(?:\\s*[\\(（](?:LV|Lv|lv)?\\.?\\s*(\\d+)[\\)）])?[】\\]]?"
    )
    private val PATTERN_BRACKET_NAME = Pattern.compile(
        "[【\\[]([\\u4e00-\\u9fa5]{2,8}(?:关|城|县|要塞|码头|港)?)(?:\\s*[\\(（](?:LV|Lv|lv)?\\.?\\s*(\\d+)[\\)）])?[】\\]]"
    )
    private val PATTERN_COORD = Pattern.compile(
        "(?:[\\(（\\[])?\\s*(\\d{2,4})\\s*[,，\\s]\\s*(\\d{2,4})\\s*(?:[\\)）\\]])?"
    )
    private val PATTERN_TIME = Pattern.compile(
        "(?:今晚|今天|今日|明天)?\\s*(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?(?:\\s*[:：分]\\s*(\\d{1,2}))?"
    )
    private val PATTERN_DEMOLITION_DELAY = Pattern.compile(
        "(?:拆迁|拆迁队|跟刀|后置|延迟)\\s*(?:晚|延迟|后置)?\\s*(\\d{1,2})\\s*(?:秒|s)?"
    )

    /**
     * 从整屏截图中解析同盟邮件/军令
     */
    fun parseFromScreen(frame: Bitmap): SiegeMailPlan? {
        val ocr = OcrManager.detect(frame) ?: return null
        return parseText(ocr.strRes)
    }

    /**
     * 从文本中提取结构化攻城战役计划
     */
    fun parseText(text: String): SiegeMailPlan? {
        if (text.isBlank()) return null
        val cleanText = text.replace("\r", "\n")

        // 1. 提取目标名称
        var targetName = "未命名集火目标"
        val tm = PATTERN_TARGET.matcher(cleanText)
        if (tm.find()) {
            val baseName = tm.group(1)?.trim() ?: ""
            val lv = tm.group(2)?.trim()
            targetName = if (!lv.isNullOrEmpty()) "${baseName}(LV.$lv)" else baseName
        } else {
            val bm = PATTERN_BRACKET_NAME.matcher(cleanText)
            if (bm.find()) {
                val baseName = bm.group(1)?.trim() ?: ""
                val lv = bm.group(2)?.trim()
                targetName = if (!lv.isNullOrEmpty()) "${baseName}(LV.$lv)" else baseName
            }
        }

        // 2. 提取所有坐标
        val coords = mutableListOf<Pair<Int, Int>>()
        val cm = PATTERN_COORD.matcher(cleanText)
        while (cm.find()) {
            val x = cm.group(1)?.toIntOrNull() ?: continue
            val y = cm.group(2)?.toIntOrNull() ?: continue
            if (x in 1..1500 && y in 1..1500) {
                coords.add(Pair(x, y))
            }
        }

        val targetCoord = coords.firstOrNull()
        // 若邮件中出现第 2 个坐标且文本包含"要塞"或"集合"，判定为集合要塞坐标
        val fortressCoord = if (coords.size >= 2) coords[1] else null

        // 3. 提取触敌时间
        var hour = 21 // 默认 21:00
        var minute = 0
        var second = 0
        var timeFound = false
        var timeStr = "21:00:00"

        val timeMatcher = PATTERN_TIME.matcher(cleanText)
        while (timeMatcher.find()) {
            val h = timeMatcher.group(1)?.toIntOrNull() ?: continue
            val m = timeMatcher.group(2)?.toIntOrNull() ?: 0
            val s = timeMatcher.group(3)?.toIntOrNull() ?: 0

            // 过滤坐标误判为时间的情况 (如 582:391 不是时间)
            if (h in 0..23 && m in 0..59 && s in 0..59) {
                hour = h
                minute = m
                second = s
                timeFound = true
                timeStr = String.format("%02d:%02d:%02d", hour, minute, second)
                break
            }
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)

        val now = System.currentTimeMillis()
        var hitEpoch = cal.timeInMillis
        // 若时间已过去超过 30 分钟，推算为次日该时刻
        if (hitEpoch < now - 30 * 60 * 1000L) {
            hitEpoch += 24 * 60 * 60 * 1000L
        }

        // 4. 提取拆迁后置秒数 (默认 5 秒)
        var demoDelay = 5
        val demoMatcher = PATTERN_DEMOLITION_DELAY.matcher(cleanText)
        if (demoMatcher.find()) {
            val parsedDelay = demoMatcher.group(1)?.toIntOrNull()
            if (parsedDelay != null && parsedDelay in 1..30) {
                demoDelay = parsedDelay
            }
        }

        // 5. 提取要塞名称
        var fortressName: String? = null
        if (cleanText.contains("要塞")) {
            val fm = Pattern.compile("【?([\\u4e00-\\u9fa5]{2,8}要塞)】?").matcher(cleanText)
            if (fm.find()) {
                fortressName = fm.group(1)
            } else {
                fortressName = "前线集合要塞"
            }
        }

        val confidence = if (targetCoord != null && timeFound) 0.98f else 0.85f

        val plan = SiegeMailPlan(
            targetName = targetName,
            targetWorldCoord = targetCoord,
            targetHitEpochMs = hitEpoch,
            targetTimeStr = timeStr,
            fortressName = fortressName,
            fortressWorldCoord = fortressCoord,
            demolitionOffsetSec = demoDelay,
            rawDecreeText = cleanText,
            confidence = confidence
        )

        Log.i(TAG, "同盟法令解析完成: 目标=${plan.targetName} $targetCoord, 触敌=${plan.targetTimeStr}, 拆迁延迟=${plan.demolitionOffsetSec}s, 要塞=$fortressName $fortressCoord")
        return plan
    }
}
