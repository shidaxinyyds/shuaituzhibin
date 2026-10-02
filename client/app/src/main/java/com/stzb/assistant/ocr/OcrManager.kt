package com.stzb.assistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.benjaminwan.ocrlibrary.OcrEngine
import com.benjaminwan.ocrlibrary.OcrResult
import com.benjaminwan.ocrlibrary.TextBlock
import java.util.regex.Pattern

/**
 * 率土之滨专用 OCR 视觉识别中枢 (单例模式)
 * 基于 NCNN C++ 核心，10~15ms 毫秒级本地离线推理，0 网络请求，0 隐私泄露
 */
object OcrManager {

    private const val TAG = "OcrManager"
    private var ocrEngine: OcrEngine? = null
    private var isInitialized = false

    // 预编译正则，提升极端高频识图性能
    private val PATTERN_STAMINA = Pattern.compile("(\\d{1,3})\\s*/\\s*120")
    private val PATTERN_COORDINATE = Pattern.compile("[Xx][：:\\s]*(\\d{1,4})[\\s,，]+[Yy][：:\\s]*(\\d{1,4})")
    private val PATTERN_COUNTDOWN = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[:：]\\s*(\\d{2})")
    private val PATTERN_SHORT_COUNTDOWN = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

    /**
     * 引擎初始化，在 Application 启动时异步预热
     */
    @Synchronized
    fun init(context: Context): Boolean {
        if (isInitialized && ocrEngine != null) return true
        return try {
            Log.i(TAG, "正在初始化 RapidOCR 本地离线引擎...")
            ocrEngine = OcrEngine(context.applicationContext).apply {
                padding = 20
                boxScoreThresh = 0.5f
                boxThresh = 0.3f
                unClipRatio = 1.6f
                doAngle = false // 游戏文字均为标准横排，关闭角度检测提高 40% 速度
                mostAngle = false
            }
            isInitialized = true
            Log.i(TAG, "RapidOCR 引擎初始化成功，模型已加载进内存。")
            true
        } catch (e: Exception) {
            Log.e(TAG, "RapidOCR 初始化失败: ${e.message}", e)
            false
        }
    }

    /**
     * 核心全量识别
     */
    fun detect(bitmap: Bitmap, maxSideLen: Int = 0): OcrResult? {
        val engine = ocrEngine ?: return null
        return try {
            val emptyOutput = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            engine.detect(bitmap, emptyOutput, maxSideLen)
        } catch (e: Exception) {
            Log.e(TAG, "OCR 推理异常: ${e.message}")
            null
        }
    }

    /**
     * 提取武将体力（格式：xx/120）
     * 返回：当前体力值（如 98），未识别到返回 null
     */
    fun parseStamina(roiBitmap: Bitmap): Int? {
        val res = detect(roiBitmap) ?: return null
        val matcher = PATTERN_STAMINA.matcher(res.strRes)
        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull()
        }
        return null
    }

    /**
     * 提取大地图坐标（格式：X: 521, Y: 890）
     * 返回：Pair(x, y)，未识别到返回 null
     */
    fun parseCoordinates(roiBitmap: Bitmap): Pair<Int, Int>? {
        val res = detect(roiBitmap) ?: return null
        val matcher = PATTERN_COORDINATE.matcher(res.strRes)
        if (matcher.find()) {
            val x = matcher.group(1)?.toIntOrNull()
            val y = matcher.group(2)?.toIntOrNull()
            if (x != null && y != null) return Pair(x, y)
        }
        return null
    }

    /**
     * 提取倒计时秒数（支持 hh:mm:ss 或 mm:ss）
     * 返回：剩余总秒数，未识别到返回 null
     */
    fun parseCountdownSeconds(roiBitmap: Bitmap): Long? {
        val res = detect(roiBitmap) ?: return null
        val fullMatcher = PATTERN_COUNTDOWN.matcher(res.strRes)
        if (fullMatcher.find()) {
            val h = fullMatcher.group(1)?.toLongOrNull() ?: 0L
            val m = fullMatcher.group(2)?.toLongOrNull() ?: 0L
            val s = fullMatcher.group(3)?.toLongOrNull() ?: 0L
            return h * 3600 + m * 60 + s
        }

        val shortMatcher = PATTERN_SHORT_COUNTDOWN.matcher(res.strRes)
        if (shortMatcher.find()) {
            val m = shortMatcher.group(1)?.toLongOrNull() ?: 0L
            val s = shortMatcher.group(2)?.toLongOrNull() ?: 0L
            return m * 60 + s
        }
        return null
    }

    /**
     * 在截图中检索指定关键字，并返回其屏幕相对中心坐标
     */
    fun findKeywords(roiBitmap: Bitmap, keywords: List<String>): List<KeywordMatch> {
        val res = detect(roiBitmap) ?: return emptyList()
        val matches = mutableListOf<KeywordMatch>()

        for (block in res.textBlocks) {
            for (kw in keywords) {
                if (block.text.contains(kw)) {
                    val centerX = block.boxPoint.map { it.x }.average().toFloat()
                    val centerY = block.boxPoint.map { it.y }.average().toFloat()
                    matches.add(KeywordMatch(kw, block.text, centerX, centerY, block.boxScore))
                }
            }
        }
        return matches
    }
}

data class KeywordMatch(
    val targetKeyword: String,
    val matchedFullText: String,
    val centerX: Float,
    val centerY: Float,
    val confidence: Float
)
