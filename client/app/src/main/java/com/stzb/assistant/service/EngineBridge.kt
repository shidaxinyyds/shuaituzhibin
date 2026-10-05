package com.stzb.assistant.service

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.DefenderEvaluator
import com.stzb.assistant.ocr.OcrManager
// OpenCvMatcher 此前因“只导入不使用”被删；现在 detectMailAlert 真的用上了它，重新导入。
import com.stzb.assistant.ocr.OpenCvMatcher
import com.stzb.assistant.ocr.RaidRadarDetector
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.antiban.TimingFingerprintEngine
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

    /** 8x8 均值哈希的汉明距离阈值：达到即认为画面发生显著变化。 */
    private const val MOTION_THRESHOLD = 5

    /**
     * [clickAndExpect] 失败重试前的静置时间。
     * 过渡动画是"没读到文字/匹配不上"的常见原因，静置一下确实可能自愈；
     * 但也不能太长——压秒类操作对时间很敏感，因此只给 250ms。
     */
    private const val RETRY_SETTLE_MS = 250L

    /**
     * UI 轮询的**期望**周期（不再是固定周期）。
     *
     * 固定的 `delay(250)` 抓屏+识别循环在时间序列上就是一根尖峰：对
     * 截图时刻做傅里叶分析能直接看到它，而这是比“点击坐标不准”更难辩
     * 护的机器指纹。这里把它当均值交给泊松采样器，实际间隔在
     * ~112ms..750ms 之间连续变化（均值仍≈ 250ms，响应延迟不变差）。
     */
    private const val POLL_INTERVAL_MS = 250L

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
     * 拟人化点击（支持自适应虚拟坐标）。
     *
     * **触控通道策略（本轮修正）**：无障碍是**主通道**——它也是产品在界面上
     * 要求用户开启的唯一通道；Shizuku 降为**透明兜底**，不在界面上出现。
     *
     * 为什么改：原实现是"只要 Shizuku 有权限就**独占**点击链路"，而且**没有反向兜底**：
     * `return if (ShizukuTouchManager.hasPermission()) { clickReal(...) } else { …无障碍… }`。
     * 于是存在一条静默失效路径——**Shizuku 权限曾获批、而其服务后来不可用**时，
     * `clickReal` 会一直返回 false，而**正在运行的无障碍通道永远不会被尝试**。
     * 用户看到的现象只是"点了没反应"，日志里也只有一句泛泛的失败。
     * 这与"仅保留无障碍手势通道"的产品决定（见 `MainActivity` 的说明）也是矛盾的。
     */
    suspend fun tap(virtualX: Float, virtualY: Float): Boolean {
        // 微量高斯拟人微扰动 (控制在 1.0px 以内，误差 < ±5dp)
        val jitteredVirtual = com.stzb.assistant.antiban.AntiBanCoordinator.randomizePoint(virtualX, virtualY, 1.0f)

        // 1) 主通道：系统无障碍手势
        val touch = AutoTouchService.instance
        if (touch != null && touch.clickVirtual(jitteredVirtual.x, jitteredVirtual.y)) {
            return true
        }

        // 2) 兜底：Shizuku（仅在无障碍不可用或未成功时使用）
        if (ShizukuTouchManager.hasPermission()) {
            val realPoint = CoordinateTransformer.toReal(jitteredVirtual.x, jitteredVirtual.y)
            if (ShizukuTouchManager.clickReal(realPoint.x, realPoint.y)) {
                Log.i(TAG, "无障碍通道未成功，本次点击由 Shizuku 兜底完成。")
                return true
            }
        }

        Log.w(
            TAG,
            "两种触控通道都未能点击：无障碍=" +
                (if (touch != null) "已连接但未成功" else "未连接") +
                "，Shizuku=" + (if (ShizukuTouchManager.hasPermission()) "有权限但未成功" else "无权限") +
                "。请检查无障碍服务是否仍在运行。"
        )
        return false
    }

    /**
     * 拟人化三次贝塞尔平滑滑动 (集成惯性过冲与动力学轨迹)
     * 通道策略与 [tap] 相同：**无障碍主通道 + Shizuku 兜底**，且两边都失败时说明原因。
     */
    suspend fun swipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long = Random.nextLong(400, 600)
    ): Boolean {
        val touch = AutoTouchService.instance
        if (touch != null && touch.swipeVirtual(startX, startY, endX, endY, durationMs)) {
            return true
        }
        if (ShizukuTouchManager.hasPermission()) {
            val p0 = CoordinateTransformer.toReal(startX, startY)
            val p3 = CoordinateTransformer.toReal(endX, endY)
            if (ShizukuTouchManager.swipeReal(p0.x, p0.y, p3.x, p3.y, durationMs)) {
                Log.i(TAG, "无障碍通道未成功，本次滑动由 Shizuku 兜底完成。")
                return true
            }
        }
        Log.w(
            TAG,
            "两种触控通道都未能滑动：无障碍=" +
                (if (touch != null) "已连接但未成功" else "未连接") +
                "，Shizuku=" + (if (ShizukuTouchManager.hasPermission()) "有权限但未成功" else "无权限") + "。"
        )
        return false
    }

    /**
     * 双指捏合缩放（把地图缩放往 target/current 倍率方向推一步）。
     *
     * ⚠️ **与 tap/swipe 不同，缩放没有 Shizuku 兜底通道**：Shizuku 走的是 `input` shell
     * 命令，`input swipe` 只能单指，**无法表达多指捏合**。因此本方法只走无障碍手势；
     * 无障碍不可用时诚实返回 false（由调用方 fail-closed），而不是假装能缩放。
     *
     * @param scale >1 放大（每格像素变大）、<1 缩小。倍率已在 [MapZoomController] 夹过上限。
     */
    suspend fun pinch(
        virtualCenterX: Float,
        virtualCenterY: Float,
        scale: Float,
        durationMs: Long = 500L
    ): Boolean {
        val touch = AutoTouchService.instance
        if (touch == null) {
            Log.w(
                TAG,
                "无法捏合缩放：无障碍触控未连接。Shizuku 的 `input` 命令不支持多指手势，"
                    + "故缩放只能经无障碍通道派发。请确认无障碍服务在运行。"
            )
            return false
        }
        return touch.pinchVirtual(virtualCenterX, virtualCenterY, scale, durationMs)
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
     * 按键点击的结果，失败时带**可区分的原因**。
     */
    data class ClickOutcome(
        val clicked: Boolean,
        val failure: StzbUiMatcher.ButtonLookupFailure?,
        val detail: String
    ) {
        /**
         * 与 [ClickAndExpect.ok] 同名对齐的"可继续推进"判据。
         *
         * **为什么必须补上它**：`clickButtonDiagnosed()` 返回本类型，
         * 而 `clickAndExpect()` 返回 [ClickAndExpect]。两个结果类型都有 `ok` 才不容易误用——
         * 此前有 3 处调用方（`TacticalPipeline.startTimedDispatch` 的 confirmRes、
         * `DailyLogisticsFlow.performReserveRecruitment` 的 recruitAction、
         * `AccurateFarmingFlow.dispatchFarming` 的 confirmAction）
         * 把本类型当 [ClickAndExpect] 用，写了 `.ok` 而这个成员当时并不存在，
         * 直接导致整个模块**编译失败**（`Unresolved reference: ok`）。
         *
         * 语义边界：本类型不具备场景校验能力，
         * 因此 [ok] 只代表"按键已定位并成功派发点击"，**不代表目标界面已经出现**。
         * 需要确认界面跳转时请改用 `clickAndExpect()`。
         */
        val ok: Boolean get() = clicked
    }

    /**
     * 带原因诊断的按键点击。
     *
     * 原先 [clickButton] 失败只返回 `false`，调用方只知道"没点到"，
     * 却分不清是 OCR 不可用、这一帧没读到文字、还是不在该界面——
     * 而这三者的处置方式完全不同（见 [StzbUiMatcher.ButtonLookupFailure]）。
     * 真机上这是最影响排查效率的一环。
     */
    suspend fun clickButtonDiagnosed(type: StzbUiMatcher.ButtonType): ClickOutcome {
        val frame = captureFrame()
            ?: return ClickOutcome(
                false,
                StzbUiMatcher.ButtonLookupFailure.NO_TEXT,
                "抓屏失败：屏幕捕获通道不可用，无法定位按键 [${type.name}]"
            )

        val lookup = try {
            StzbUiMatcher.findButtonWithReason(frame, type)
        } finally {
            frame.recycle()
        }

        val btn = lookup.button
        if (btn == null) {
            val reason = lookup.failure?.desc ?: "未知原因"
            Log.w(TAG, "未能定位按键 [${type.name}]：$reason（本次读到 ${lookup.scannedTextCount} 个文本块）")
            return ClickOutcome(
                false,
                lookup.failure,
                "未能定位按键 [${type.name}]：$reason（读到 ${lookup.scannedTextCount} 个文本块）"
            )
        }

        Log.i(TAG, "点击语义按键 [${type.name}]: 安全触控点 (${btn.safeTouchPoint.x}, ${btn.safeTouchPoint.y})")
        val ok = tap(btn.safeTouchPoint.x, btn.safeTouchPoint.y)
        return if (ok) {
            ClickOutcome(
                true, null,
                "已点击 [${type.name}] @ (${btn.safeTouchPoint.x.toInt()}, ${btn.safeTouchPoint.y.toInt()})"
            )
        } else {
            ClickOutcome(
                false,
                StzbUiMatcher.ButtonLookupFailure.TAP_DISPATCH_FAILED,
                "已定位到按键 [${type.name}]，但手势派发失败（触控通道可能被中断）"
            )
        }
    }

    // 说明：这里刻意**不**再提供返回 Boolean 的 `clickButton` 便捷重载。
    // 所有按键点击都只有 [clickButtonDiagnosed] 一条路径，理由是：
    //   1. 布尔接口会丢掉失败原因（OCR 不可用 / 没读到文字 / 不在该界面），
    //      而这三者的处置方式完全不同，是排查"点不到"时最关键的信息；
    //   2. 调用方拿到 false 之后往往只写一句笼统的日志，甚至不看返回值——
    //      本项目此前就有"没点中却打印击发成功"的问题。
    // 少一个重载，就少一条能让诊断信息消失的路径。

    /**
     * 「点击 + 确认目标场景真的出现了」的合成操作。
     *
     * ## 为什么不能只用帧差（[tapAndVerify]）
     * 地图本身持续有动画（行军、云影），帧差几乎恒为正——用帧差判断"点地图有没有生效"
     * 会大量假阳性。但对**预期会引起界面跳转**的点击（出征 → 选队面板），
     * **场景判定**才是最贴切的证据：它直接回答"我要的那个界面出现了没有"。
     * 两种判据各有适用面，不能互相替代。
     *
     * ## 相比原先 `clickButtonDiagnosed(...) + waitForState(...)` 的差别
     * 那个松散组合把两件事分开报告：点击成功但面板没弹出时，日志只会说
     * 「出征面板未弹出」，让人以为是流程/场景判定的问题。这里能明确区分
     * **「没点到」**与**「点到了但界面没跳」**，并且只在前者值得重试时才重试。
     *
     * ## 重试策略（刻意不是"无脑重试"）
     * * `OCR_UNAVAILABLE` → 直接放弃。引擎没编译进来，重试一百次也一样，纯浪费挂机时间。
     * * 其余失败（没读到文字、匹配不上、派发失败、界面没跳）→ 短暂静置后重试，
     *   因为过渡动画造成的失败确实可能自愈。
     */
    suspend fun clickAndExpect(
        type: StzbUiMatcher.ButtonType,
        expected: StzbUiMatcher.GameState,
        timeoutMs: Long = 3000L,
        attempts: Int = 2
    ): ClickAndExpect {
        val tries = attempts.coerceAtLeast(1)
        var lastDetail = "尚未尝试"
        var dispatched = false

        for (i in 1..tries) {
            val outcome = clickButtonDiagnosed(type)
            if (outcome.clicked) dispatched = true

            when {
                outcome.failure == StzbUiMatcher.ButtonLookupFailure.OCR_UNAVAILABLE -> {
                    return ClickAndExpect(
                        false, false, i,
                        "${outcome.detail}；OCR 引擎不可用，重试无意义"
                    )
                }

                !outcome.clicked -> {
                    lastDetail = outcome.detail
                    Log.w(TAG, "第 $i/$tries 次未能点到 [${type.name}]：$lastDetail")
                }

                waitForState(expected, timeoutMs) -> {
                    return ClickAndExpect(
                        true, true, i,
                        "已点击 [${type.name}] 并确认进入 ${expected.name}（第 $i 次尝试）"
                    )
                }

                else -> {
                    lastDetail = "已点击 [${type.name}]，但 ${timeoutMs}ms 内未出现 ${expected.name}"
                    Log.w(TAG, "第 $i/$tries 次点击后未进入期望场景 ${expected.name}")
                }
            }

            if (i < tries) delay(RETRY_SETTLE_MS)
        }

        return ClickAndExpect(
            dispatched, false, tries,
            "尝试 $tries 次仍未进入 ${expected.name}：$lastDetail"
        )
    }

    /** [clickAndExpect] 的结果。 */
    data class ClickAndExpect(
        /** 是否至少成功派发过一次点击手势。 */
        val clicked: Boolean,
        /** 期望场景是否真的出现了。 */
        val sceneReached: Boolean,
        val attemptsUsed: Int,
        val detail: String
    ) {
        val ok: Boolean get() = clicked && sceneReached
    }

    /**
     * 带效果校验的点击：对比点击前后的画面哈希，判断这次点击是否真的让界面发生了变化。
     *
     * ## 为什么需要它
     * [tap] 只保证"手势被系统派发成功"，**并不代表点中了目标**。
     * 本项目此前所有"点完即认为成功"的判断都依赖 OCR 场景识别，
     * 而感知不可用时那条路恒失败——于是要么盲点，要么把失败当成成功写进日志。
     * 帧差是一个**不依赖 OCR** 的独立证据，两者结合才谈得上"确认点击生效"。
     *
     * ## 局限（务必知悉）
     * 画面没变化**不等于**没点中：有些操作（例如选中同一个部队槽位）
     * 本来就不改变画面。因此本方法只适合"预期会引起界面跳转"的点击，
     * 调用方不要把它当成万能判据。
     *
     * @param attempts 最多尝试次数；第 2 次起会带一个小偏移再点
     * @return true 表示至少有一次点击之后画面发生了显著变化
     */
    suspend fun tapAndVerify(
        virtualX: Float,
        virtualY: Float,
        attempts: Int = 2,
        waitMs: Long = 700L,
        retryOffsetPx: Float = 6f
    ): Boolean {
        val capture = ScreenCaptureService.instance ?: return tap(virtualX, virtualY)
        val tries = attempts.coerceAtLeast(1)

        for (i in 0 until tries) {
            val before = captureFrame()
            val beforeHash = before?.let { capture.averageHash(it) }
            before?.recycle()
            if (beforeHash == null) {
                // 拿不到帧就无法校验；退回普通点击，并如实返回其结果
                Log.w(TAG, "无法抓取校验帧，退化为普通点击。")
                return tap(virtualX, virtualY)
            }

            val offset = if (i == 0) 0f else retryOffsetPx * i
            if (!tap(virtualX + offset, virtualY + offset)) {
                Log.w(TAG, "第 ${i + 1} 次点击手势派发失败。")
                continue
            }

            delay(waitMs)

            val after = captureFrame()
            val afterHash = after?.let { capture.averageHash(it) }
            after?.recycle()
            if (afterHash != null &&
                java.lang.Long.bitCount(afterHash xor beforeHash) >= MOTION_THRESHOLD
            ) {
                Log.i(TAG, "点击校验通过：画面发生显著变化（第 ${i + 1} 次尝试）。")
                return true
            }
            Log.d(TAG, "第 ${i + 1} 次点击后画面无明显变化，可能未命中或该操作本就不改变画面。")
        }
        return false
    }

    /**
     * 等待指定场景出现 (轮询，最大超时 timeoutMs)
     */
    suspend fun waitForState(targetState: StzbUiMatcher.GameState, timeoutMs: Long = 3000L): Boolean {
        val start = System.currentTimeMillis()
        while (true) {
            if (detectGameState() == targetState) return true
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) return false
            // 泊松周期去周期性；**绝不超过剩余预算**，所以本方法的
            // 契约从旧的“最多 timeoutMs+250ms”收紧为硬 timeoutMs。
            delay(minOf(TimingFingerprintEngine.poissonIntervalMs(POLL_INTERVAL_MS), timeoutMs - elapsed))
        }
    }

    /**
     * 等待指定按键出现
     */
    suspend fun waitForButton(type: StzbUiMatcher.ButtonType, timeoutMs: Long = 3000L): StzbUiMatcher.ButtonResult? {
        val start = System.currentTimeMillis()
        while (true) {
            val btn = findButton(type)
            if (btn != null) return btn
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) return null
            delay(minOf(TimingFingerprintEngine.poissonIntervalMs(POLL_INTERVAL_MS), timeoutMs - elapsed))
        }
    }

    // ==========================================
    // 阶段二：深夜敌袭雷达与源头回溯中枢
    // ==========================================

    /**
     * 全图巡检敌袭红线、主城警戒圈判定与顶部受袭红标
     * @param baseAnchor 己方基地参考点（默认居中）
     * @param baseWorldCoord 己方基地世界坐标
     * @param alertRadiusTiles 主城警戒圈格数 (默认 2 格，5x5 威胁区)
     */
    fun scanRaidThreats(
        baseAnchor: PointF? = null,
        baseWorldCoord: Pair<Int, Int>? = null,
        alertRadiusTiles: Int = 2
    ): RaidRadarDetector.RaidReport {
        val frame = captureFrame() ?: return RaidRadarDetector.RaidReport(
            hasThreat = false,
            threatLevel = RaidRadarDetector.ThreatLevel.NONE,
            isScreenEdgeAlert = false,
            detectedVectors = emptyList(),
            enemyOriginPoint = null,
            playerTargetPoint = null,
            timestampMs = System.currentTimeMillis(),
            isTopAlertActive = false,
            isWithinAlertCircle = false,
            remainingCountdownSeconds = null,
            targetWorldCoord = null
        )
        val report = RaidRadarDetector.scanRaidThreats(frame, baseAnchor, baseWorldCoord, alertRadiusTiles)
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

    /**
     * 敌对占领地（红地）检测：路由到 [TileStatusDetector.detectEnemyTiles]（确定性 HSV 红地分割）。
     *
     * 与“金色免战罩”同源的形状/颜色通道，而非依赖任何神经网络权重；
     * 宁可漏报、不可误报（具体阈值见 [TileStatusDetector.detectEnemyTiles]）。
     * @param searchArea 限定搜索区域（可选，默认全图）
     * @return 全画面绝对坐标的红地外接矩形，按面积从大到小；无则空列表
     */
    fun detectEnemyTiles(searchArea: Rect? = null): List<Rect> {
        val frame = captureFrame() ?: return emptyList()
        return try {
            TileStatusDetector.detectEnemyTiles(frame, searchArea)
        } finally {
            frame.recycle()
        }
    }

    /**
     * 邮件红点/未读角标精定位：路由到 [OpenCvMatcher.findRedBadgeClusters]，
     * 以“圆度”作置信度门控（低于 [minConfidence] 的红簇不采信，宁缺毋滥）。
     *
     * 这是对“整块固定 ROI 色密度粗判”的升级：输出的是逐个真实红角标的质心与外接框。
     * @param roi 限定搜索区域（如右上角邮件按钮一带），null=全图
     * @return 达标的红角标（质心/外接框/置信度），按面积从大到小；无则空列表
     */
    fun detectMailAlert(
        roi: Rect? = null,
        minConfidence: Float = 0.5f
    ): List<OpenCvMatcher.MatchResult> {
        val frame = captureFrame() ?: return emptyList()
        return try {
            OpenCvMatcher.findRedBadgeClusters(frame, roi).filter { it.score >= minConfidence }
        } finally {
            frame.recycle()
        }
    }

    /**
     * 预先定位好的按键触控点（设计画布坐标）。
     *
     * 为什么需要"先架枪、后扣扳机"：定位一次按键要**抓屏 + 识别 + 匹配**，
     * 耗时从几十毫秒到几百毫秒不等。压秒流程若在目标时刻**才**开始定位，
     * 手势实际是在"目标时刻 + 定位耗时"才派发出去的——
     * 这段耗时被整个算进了误差里，而且完全没被补偿。
     *
     * 所以压秒流程必须在等待之前就把点算好，扣扳机时只做一次手势派发。
     */
    data class PreparedTap(
        val type: StzbUiMatcher.ButtonType,
        val x: Float,
        val y: Float
    )

    /** 扣扳机的结果；[dispatchEpochMs] 是**手势派发那一刻**的时间戳。 */
    data class FiredTap(val dispatched: Boolean, val dispatchEpochMs: Long)

    /**
     * 只定位、不点击。定位失败返回 null。
     *
     * 注意这里**会计入识别健康度统计**（走 [StzbUiMatcher.findButtonWithReason]），
     * 因为它确实是挂机期间的一次真实识别调用。
     */
    suspend fun prepareButtonTap(type: StzbUiMatcher.ButtonType): PreparedTap? {
        val frame = captureFrame() ?: run {
            Log.w(TAG, "预定位按键 [${type.name}] 失败：抓屏不可用。")
            return null
        }
        val lookup = try {
            StzbUiMatcher.findButtonWithReason(frame, type)
        } finally {
            frame.recycle()
        }
        val b = lookup.button
        if (b == null) {
            Log.w(TAG, "预定位按键 [${type.name}] 失败：${lookup.failure?.desc ?: "未知原因"}")
            return null
        }
        Log.i(TAG, "已预定位按键 [${type.name}] @ (${b.safeTouchPoint.x.toInt()}, ${b.safeTouchPoint.y.toInt()})，等待扣扳机")
        return PreparedTap(type, b.safeTouchPoint.x, b.safeTouchPoint.y)
    }

    /**
     * 扣扳机：把预定位好的点派发出去，并返回**派发那一刻**的时间戳。
     *
     * 时间戳取在 [tap] 之前——"手势派发时刻"才是压秒要对齐的物理事件，
     * 而不是派发完成之后。取在后面会让误差看起来比实际小。
     */
    suspend fun firePreparedTap(prepared: PreparedTap): FiredTap {
        val at = System.currentTimeMillis()
        val ok = tap(prepared.x, prepared.y)
        return FiredTap(ok, at)
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
     * 对单帧采集守将名单：头像通道（主）+ OCR 命中守军库的名字通道（辅），合并去重。
     * 不评估、不回收 frame（生命周期由调用方管理）。
     *
     *  - **头像通道（主）**：按固定归一化槽位裁三行立绘，与 defender_refs 模板库比对，
     *    识别守将身份（不依赖 OCR 是否读清名字）。
     *  - **OCR 名字通道（辅）**：只采信「命中守军库」的名字，避免把
     *    “土地Lv/出征/推荐/数字”等无关文本当守将而凭空虚增危险度。
     */
    fun collectDefenderHeroes(frame: Bitmap, nameRoi: Rect): List<String> {
        val portraitHeroes = com.stzb.assistant.ocr.DefenderTemplateClassifier.classify(frame)
        val ocrNames = cropBitmap(frame, nameRoi)?.let { crop ->
            try {
                OcrManager.detect(crop)?.textBlocks?.map { it.text.trim().replace(" ", "") }
                    ?: emptyList()
            } finally {
                if (crop !== frame) crop.recycle()
            }
        } ?: emptyList()
        val knownOcrNames = DefenderEvaluator.filterKnownHeroes(ocrNames)
        Log.d(
            "EngineBridge",
            "守军采集: 头像=${portraitHeroes.size} OCR命中=${knownOcrNames.size} 名单=${portraitHeroes + knownOcrNames}"
        )
        return (portraitHeroes + knownOcrNames).distinct()
    }

    /**
     * 评估守军难度（Lv6/Lv7 双队完整版）。
     *
     * 背景：此前只评估面板**当前显示**的那一队，而高等级地块用「守军1/守军2」分页、
     * 单帧只含一队，于是“取最危险”会低估实际风险。这里补上交互闭环：
     *  1) 读当前显示队（守军1）；
     *  2) 用 OCR **确定性定位**「守军2」页签——找不到即判定为单队地块，**绝不盲点坐标**；
     *  3) 命中则点击切页、重读守军2，把两队守将**并集**交给 [DefenderEvaluator.evaluate]
     *     （evaluate 对多守将取最危险，故并集=两队里最该警惕的那一个）；
     *  4) 尽量点回守军1 复原面板，避免停留在第二队误导后续操作。
     *
     * 依赖 [tap]/[humanDelay] 故为 suspend；由手动「军师」按钮在协程 + IO 线程内调用。
     * 页签定位有 OCR 误差：以 [minTabConfidence] 过滤低置信块，且整条链路为手动触发、
     * 只采信命中守军库的名字，误点风险被限制在“多切一次页”，不会污染危险度。
     * 一个都没识别到时 [DefenderEvaluator.evaluate] 返回 UNKNOWN（不会误报 SAFE）。
     */
    suspend fun evaluateDefenderPanelDual(
        nameRoi: Rect,
        teamTabLabel1: String = "守军1",
        teamTabLabel2: String = "守军2",
        minTabConfidence: Float = 0.60f,
    ): DefenderEvaluator.EvaluationResult? {
        val frame = captureFrame() ?: return null
        val team1: List<String>
        val tab2: com.stzb.assistant.ocr.KeywordMatch?
        val tab1: com.stzb.assistant.ocr.KeywordMatch?
        try {
            team1 = collectDefenderHeroes(frame, nameRoi)
            // 切页前两侧页签都在原位（分页只换主区三行，左侧页签不移动）；
            // 单次全帧 OCR 同时定位两侧页签，避免两次全帧识别的开销。
            val tabMatches = OcrManager.findKeywords(
                frame, tabKeywordsOf(teamTabLabel1) + tabKeywordsOf(teamTabLabel2)
            ).filter { it.confidence >= minTabConfidence }
            tab2 = tabMatches.firstOrNull { it.matchedFullText.replace(" ", "").contains(teamTabLabel2) }
            tab1 = tabMatches.firstOrNull { it.matchedFullText.replace(" ", "").contains(teamTabLabel1) }
        } finally {
            frame.recycle()
        }

        var team2: List<String> = emptyList()
        if (tab2 != null) {
            if (tap(tab2.centerX, tab2.centerY)) {
                humanDelay(500, 900)
                val frame2 = captureFrame()
                if (frame2 != null) {
                    team2 = try {
                        collectDefenderHeroes(frame2, nameRoi)
                    } finally {
                        frame2.recycle()
                    }
                }
                if (tab1 != null) {
                    tap(tab1.centerX, tab1.centerY) // 复原到守军1
                } else {
                    Log.d("EngineBridge", "未定位到「$teamTabLabel1」页签，跳过复原。")
                }
            } else {
                Log.w("EngineBridge", "点击「$teamTabLabel2」页签失败，本次仅评估当前显示队。")
            }
        } else {
            Log.d("EngineBridge", "未检出「$teamTabLabel2」页签，判定为单队地块，仅评估当前显示队。")
        }

        val merged = (team1 + team2).distinct()
        Log.i(
            "EngineBridge",
            "守军评估(双队): 队1=${team1.size} 队2=${team2.size} 合计=${merged.size} " +
                "头像门槛=${com.stzb.assistant.ocr.DefenderTemplateClassifier.currentMatchThreshold}"
        )
        return DefenderEvaluator.evaluate(merged)
    }

    /** 生成页签检索词：兼容 OCR 在中文字与数字间插入的空格（"守军2"↔"守军 2"）。 */
    private fun tabKeywordsOf(label: String): List<String> {
        val spaced = label.replace(Regex("(\\D)(\\d)"), "$1 $2")
        return listOf(label, spaced).distinct()
    }

    /** 从已有全屏位图按矩形裁剪子区域（钳制越界）；矩形覆盖全图时可能直接返回原图。 */
    private fun cropBitmap(src: Bitmap, roi: Rect): Bitmap? {
        val l = roi.left.coerceIn(0, src.width - 1)
        val t = roi.top.coerceIn(0, src.height - 1)
        val r = roi.right.coerceIn(l + 1, src.width)
        val b = roi.bottom.coerceIn(t + 1, src.height)
        return try {
            Bitmap.createBitmap(src, l, t, r - l, b - t)
        } catch (e: Throwable) {
            Log.w("EngineBridge", "裁剪守军名区失败: ${e.message}")
            null
        }
    }

    /**
     * 模拟真实人类思考随机停顿。
     *
     * **硬契约：结果一定落在 [minMs, maxMs] 内。**调用点（地图拖动每步、
     * 抽屉动画、页签切换、扫荡等结算）都是按“不超过 maxMs”安排自己的
     * 时间预算的，因此这里不允许旧实现那种“节律×疲劳×指数长尾”把 350ms
     * 拖成十几秒，也不允许在两次动作之间塞进分钟的微歇（那会直接错过
     * 撤退/压秒的战术窗口）。分布形状与昼夜语义由
     * [TimingFingerprintEngine.generateBoundedDelayMs] 在**区间内**完成。
     */
    suspend fun humanDelay(minMs: Long = 1200, maxMs: Long = 2500) {
        com.stzb.assistant.antiban.AntiBanCoordinator.injectActionDelay(minMs, maxMs)
    }

    /**
     * 判定当前画面是否属于静止巡航状态 (用于 0.5 FPS 变动感知)
     */
    fun isScreenMotionless(frame: Bitmap): Boolean {
        val service = ScreenCaptureService.instance ?: return false
        return !service.isScreenChanged(frame)
    }
}
