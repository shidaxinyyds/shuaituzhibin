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

    /**
     * OCR 引擎是否真的可用。
     *
     * 为什么需要这个标志：native 层的空桩（`OcrStub.cpp`）过去会"成功初始化并返回空结果"，
     * 于是"OCR 根本不存在"被伪装成"OCR 正常但暂时没识别到文字"，导致故障长期无法定位。
     * 现在空桩会让 `init` 失败、构造抛异常，这个标志就会是 false，
     * UI 与日志都能如实告诉用户"文字识别不可用"，而不是让所有战术流程静默超时。
     */
    @Volatile
    var isEngineAvailable: Boolean = false
        private set

    /** 引擎不可用的具体原因，供界面与日志直接展示。 */
    @Volatile
    var unavailableReason: String? = null
        private set

    /** 避免每帧都刷同一条"引擎不可用"日志。 */
    @Volatile
    private var warnedEngineMissing = false

    // 预编译正则，提升极端高频识图性能
    private val PATTERN_STAMINA = Pattern.compile("(\\d{1,3})\\s*/\\s*120")
    private val PATTERN_COORDINATE = Pattern.compile("[Xx][：:\\s]*(\\d{1,4})[\\s,，]+[Yy][：:\\s]*(\\d{1,4})")
    private val PATTERN_COUNTDOWN = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*[:：]\\s*(\\d{2})")
    private val PATTERN_SHORT_COUNTDOWN = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

    /**
     * HUD 右上角「当前大地图坐标」的读数格式。
     *
     * 真实格式是 **城池/郡名 + 括号内的 "X,Y"**，例如 `武威 (228,132)`
     * （取自真机截图）。原先只有 [PATTERN_COORDINATE] 那种
     * `X: 521, Y: 890` 的写法，与游戏实际 HUD **不符**，所以这条识别从来没成功过，
     * 世界坐标因此整条链路都是断的。
     */
    private val PATTERN_HUD_COORD = Pattern.compile("\\((\\d{1,4})\\s*[,，]\\s*(\\d{1,4})\\)")

    /**
     * 引擎初始化，在 Application 启动时异步预热
     */
    @Synchronized
    fun init(context: Context): Boolean {
        if (isInitialized && ocrEngine != null) return true
        return try {
            Log.i(TAG, "正在初始化 RapidOCR 本地离线引擎...")
            val engine = OcrEngine(context.applicationContext).apply {
                padding = 20
                boxScoreThresh = 0.5f
                boxThresh = 0.3f
                unClipRatio = 1.6f
                doAngle = false // 游戏文字均为标准横排，关闭角度检测提高 40% 速度
                mostAngle = false
            }
            ocrEngine = engine
            isInitialized = true
            isEngineAvailable = true
            unavailableReason = null
            warnedEngineMissing = false
            // 注意措辞：此前这里写「模型已加载进内存」，而在空桩之上它同样会打印，
            // 属于把"没加载"说成"已加载"。现在只在确认真引擎后才这样陈述。
            Log.i(TAG, "RapidOCR 引擎初始化成功（native 推理已就绪）。")
            true
        } catch (e: Exception) {
            ocrEngine = null
            isInitialized = false
            isEngineAvailable = false
            unavailableReason =
                "native OCR 不可用（构建期缺少 ncnn/OpenCV，或模型资产缺失）；" +
                    "所有依赖文字的识别都会失败。原因: ${e.message}"
            Log.e(TAG, unavailableReason, e)
            false
        }
    }

    /**
     * 核心全量识别。
     *
     * 返回 null 表示"引擎不可用/推理异常"——**这与"识别到 0 个文字"是两件事**，
     * 调用方必须区分：前者应提示用户修复环境，后者才是正常的空画面。
     */
    fun detect(bitmap: Bitmap, maxSideLen: Int = 0): OcrResult? {
        val engine = ocrEngine
        if (engine == null) {
            if (!warnedEngineMissing) {
                warnedEngineMissing = true
                Log.w(TAG, unavailableReason ?: "OCR 引擎未初始化，detect() 直接返回 null。")
            }
            return null
        }
        // ⚠️ 关键修复：emptyOutput 此前每帧新建且从不回收，720p 一帧≈3.7MB，
        // 长时间挂机的 OCR 高频调用必然 OOM。native 只把它当输出画布，
        // 取回 OcrResult 后必须在 finally 回收，异常路径也不能漏。
        val emptyOutput = try {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OCR 输出位图分配失败（内存不足）: ${e.message}")
            return null
        }
        return try {
            engine.detect(bitmap, emptyOutput, maxSideLen)
        } catch (e: Exception) {
            Log.e(TAG, "OCR 推理异常: ${e.message}")
            null
        } finally {
            if (!emptyOutput.isRecycled) emptyOutput.recycle()
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
     * 从一段文本里解析 HUD 的大地图坐标读数，格式如 `武威 (228,132)`。
     *
     * 纯函数，不抓屏、不依赖引擎状态，便于单独验证正则是否正确。
     *
     * @return Pair(x, y)；未匹配返回 null。
     */
    fun parseHudWorldCoordinate(text: String): Pair<Int, Int>? {
        if (text.isBlank()) return null
        val m = PATTERN_HUD_COORD.matcher(text)
        if (m.find()) {
            val x = m.group(1)?.toIntOrNull()
            val y = m.group(2)?.toIntOrNull()
            if (x != null && y != null) return Pair(x, y)
        }
        // 兼容 "X: 521, Y: 890" 这类写法（部分界面/皮肤会这样显示）
        val legacy = PATTERN_COORDINATE.matcher(text)
        if (legacy.find()) {
            val x = legacy.group(1)?.toIntOrNull()
            val y = legacy.group(2)?.toIntOrNull()
            if (x != null && y != null) return Pair(x, y)
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
