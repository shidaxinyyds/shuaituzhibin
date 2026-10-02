package com.stzb.assistant.service

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.DefenderEvaluator
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.OpenCvMatcher
import com.stzb.assistant.ocr.RaidRadarDetector
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 自动化引擎桥梁总控 (EngineBridge)
 * 职责：
 *   1. 统一接入 720p 自适应截屏 (ScreenCaptureService)；
 *   2. 统一接入【无障碍 + Shizuku】双通道拟人触控互备体系；
 *   3. 统一接入【RapidOCR 文字识别 + OpenCV 视觉分析】双模感知；
 *   4. 【阶段二核心扩展】：全面暴露现代化率土全场景状态机、语义按键定位、
 *      深夜红线敌袭雷达、土地金色免战罩倒计时、2026 赛季士气与攻城毫秒卡秒总控！
 */
object EngineBridge {

    private const val TAG = "EngineBridge"

    val isCaptureReady: Boolean
        get() = ScreenCaptureService.isCapturing.get()

    val isTouchReady: Boolean
        get() = AutoTouchService.isConnected || ShizukuTouchManager.hasPermission()

    val isEngineReady: Boolean
        get() = isCaptureReady && isTouchReady

    /**
     * 捕获当前自适应 720p 归一化单帧
     */
    fun captureFrame(): Bitmap? {
        val service = ScreenCaptureService.instance ?: return null
        return service.captureCurrentFrame()
    }

    /**
     * 截取指定虚拟 ROI 局部矩形区域
     */
    fun captureRoi(roi: Rect): Bitmap? {
        val full = captureFrame() ?: return null
        return try {
            val vWidth = CoordinateTransformer.virtualWidth.toInt()
            val vHeight = CoordinateTransformer.virtualHeight.toInt()

            val validLeft = maxOf(0, roi.left)
            val validTop = maxOf(0, roi.top)
            val validRight = minOf(vWidth, roi.right)
            val validBottom = minOf(vHeight, roi.bottom)
            val width = validRight - validLeft
            val height = validBottom - validTop
            if (width <= 0 || height <= 0) {
                full.recycle()
                return null
            }

            val cropped = Bitmap.createBitmap(full, validLeft, validTop, width, height)
            full.recycle()
            cropped
        } catch (e: Exception) {
            Log.e(TAG, "裁剪局部 ROI 异常: ${e.message}")
            full.recycle()
            null
        }
    }

    /**
     * 拟人化双通道智能点击 (支持 1280x720 或自适应虚拟坐标)
     * 优先采用 Shizuku 系统底层注入；若无则平滑降级走无障碍
     */
    suspend fun tap(virtualX: Float, virtualY: Float): Boolean {
        val jitteredVirtual = com.stzb.assistant.antiban.AntiBanCoordinator.randomizePoint(virtualX, virtualY, 5f)
        return if (ShizukuTouchManager.hasPermission()) {
            val realPoint = CoordinateTransformer.toReal(jitteredVirtual.x, jitteredVirtual.y)
            ShizukuTouchManager.clickReal(realPoint.x, realPoint.y)
        } else {
            val touch = AutoTouchService.instance
            if (touch == null) {
                Log.w(TAG, "触控服务未开启 (无障碍与 Shizuku 均未就绪)。")
                false
            } else {
                touch.clickVirtual(jitteredVirtual.x, jitteredVirtual.y)
            }
        }
    }

    /**
     * 拟人化三次贝塞尔平滑滑动 (集成惯性过冲与动力学轨迹)
     */
    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = Random.nextLong(400, 600)
    ): Boolean {
        return if (ShizukuTouchManager.hasPermission()) {
            val p0 = CoordinateTransformer.toReal(startX, startY)
            val p3 = CoordinateTransformer.toReal(endX, endY)
            ShizukuTouchManager.swipeReal(p0.x, p0.y, p3.x, p3.y, durationMs)
        } else {
            val touch = AutoTouchService.instance ?: return false
            touch.swipeVirtual(startX, startY, endX, endY, durationMs)
        }
    }

    // ==========================================
    // 阶段二：率土全场景状态机与语义按键中枢
    // ==========================================

    /**
     * 识别当前游戏所处的场景状态 (大地图 / 地块菜单 / 出征面板 / 守军面板等)
     */
    fun detectGameState(): StzbUiMatcher.GameState {
        val frame = captureFrame() ?: return StzbUiMatcher.GameState.UNKNOWN
        val state = StzbUiMatcher.classifyGameState(frame)
        frame.recycle()
        return state
    }

    /**
     * 在屏幕中寻找指定语义按键 (如“出征”、“扫荡”、“驻守”、“确定”等)
     */
    fun findButton(type: StzbUiMatcher.ButtonType): StzbUiMatcher.ButtonResult? {
        val frame = captureFrame() ?: return null
        val btn = StzbUiMatcher.findButton(frame, type)
        frame.recycle()
        return btn
    }

    /**
     * 寻找指定按键并执行防封拟人点击
     */
    suspend fun clickButton(type: StzbUiMatcher.ButtonType): Boolean {
        val btn = findButton(type) ?: return false
        Log.i(TAG, "点击语义按键 [${type.name}]: 安全触控点 (${btn.safeTouchPoint.x}, ${btn.safeTouchPoint.y})")
        return tap(btn.safeTouchPoint.x, btn.safeTouchPoint.y)
    }

    /**
     * 等待指定场景出现 (轮询，最大超时 timeoutMs)
     */
    suspend fun waitForState(targetState: StzbUiMatcher.GameState, timeoutMs: Long = 3000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (detectGameState() == targetState) return true
            delay(250)
        }
        return false
    }

    /**
     * 等待指定按键出现
     */
    suspend fun waitForButton(type: StzbUiMatcher.ButtonType, timeoutMs: Long = 3000L): StzbUiMatcher.ButtonResult? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val btn = findButton(type)
            if (btn != null) return btn
            delay(250)
        }
        return null
    }

    // ==========================================
    // 阶段二：深夜敌袭雷达与源头回溯中枢
    // ==========================================

    /**
     * 全图巡检敌袭红线与边缘闪烁报警
     * @param baseAnchor 己方基地参考点（默认居中）
     */
    fun scanRaidThreats(baseAnchor: PointF? = null): RaidRadarDetector.RaidReport {
        val frame = captureFrame() ?: return RaidRadarDetector.RaidReport(
            false, RaidRadarDetector.ThreatLevel.NONE, false, emptyList(), null, null, System.currentTimeMillis()
        )
        val report = RaidRadarDetector.scanRaidThreats(frame, baseAnchor)
        frame.recycle()
        return report
    }

    // ==========================================
    // 阶段二：土地免战罩与倒计时中枢
    // ==========================================

    /**
     * 检测地块金色免战罩与毫秒级破免倒计时
     */
    fun detectTileImmunity(searchArea: Rect? = null): TileStatusDetector.ImmunityStatus {
        val frame = captureFrame() ?: return TileStatusDetector.ImmunityStatus(false, 0L, 0L, null)
        val status = TileStatusDetector.detectTileImmunity(frame, searchArea)
        frame.recycle()
        return status
    }

    /**
     * 解析地块详情面板 (等级、木铁石粮、施工状态)
     */
    fun parseTileDetail(roi: Rect): TileStatusDetector.LandTileDetail {
        val bmp = captureRoi(roi) ?: return TileStatusDetector.LandTileDetail(
            0, TileStatusDetector.ResourceType.UNKNOWN, false, 0L, false, false
        )
        val detail = TileStatusDetector.parseTileDetail(bmp)
        bmp.recycle()
        return detail
    }

    // ==========================================
    // 阶段二：部队体能、2026士气120与攻城卡秒中枢
    // ==========================================

    /**
     * 剖析出征面板中指定部队槽位的信息 (体力、士气、兵力)
     */
    fun parseTroopCard(cardRoi: Rect, slotIndex: Int): TroopStatusDetector.TroopSlotDetail? {
        val bmp = captureRoi(cardRoi) ?: return null
        val detail = TroopStatusDetector.parseTroopCard(bmp, slotIndex)
        bmp.recycle()
        return detail
    }

    /**
     * 测算攻城毫秒卡秒方案
     */
    fun planCardSecondDispatch(
        marchTimeRoi: Rect,
        targetHitEpochMs: Long,
        networkJitterCompensationMs: Long = 100L
    ): TroopStatusDetector.MarchTimingPlan? {
        val bmp = captureRoi(marchTimeRoi) ?: return null
        val plan = TroopStatusDetector.calculateCardSecondTiming(bmp, targetHitEpochMs, networkJitterCompensationMs)
        bmp.recycle()
        return plan
    }

    // ==========================================
    // 通用辅助 API
    // ==========================================

    /**
     * 评估守军难度 (传入“查看守军”面板的武将名显示区域)
     */
    fun evaluateDefenderPanel(roi: Rect): DefenderEvaluator.EvaluationResult? {
        val bmp = captureRoi(roi) ?: return null
        val ocrResult = OcrManager.detect(bmp)
        bmp.recycle()
        if (ocrResult == null) return null

        val names = ocrResult.textBlocks.map { it.text.trim() }
        return DefenderEvaluator.evaluate(names)
    }

    /**
     * 模拟真实人类思考随机停顿 (抗大数据行为检测，集成外高斯长尾与生理微歇)
     */
    suspend fun humanDelay(minMs: Long = 1200, maxMs: Long = 2500) {
        val variance = (maxMs - minMs) / 3
        com.stzb.assistant.antiban.AntiBanCoordinator.injectActionDelay(minMs, variance)
    }

    /**
     * 判定当前画面是否属于静止巡航状态 (用于 0.5 FPS 变动感知)
     */
    fun isScreenMotionless(frame: Bitmap): Boolean {
        val service = ScreenCaptureService.instance ?: return false
        return !service.isScreenChanged(frame)
    }
}
