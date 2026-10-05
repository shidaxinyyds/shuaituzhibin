package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.antiban.TimingFingerprintEngine
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.RaidRadarDetector
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import com.stzb.assistant.runtime.TouchGate
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

typealias SentinelConfig = RaidDefenseFlow.DefenseConfig

/**
 * 【阶段 2 核心落地】暗夜天眼防沦哨兵 (NightSentinelFlow)
 *
 * 彻底消除旧版“出征反击送死、盲目驻守白给、挂机15分钟掉线”三大硬伤，
 * 践行商业化 T0 级核心保命方案：
 *
 * 1. 【主城 2 格警戒圈判定 (5x5 核心威胁区)】：
 *    - 基于世界坐标切比雪夫距离 (|X - X0| <= 2 && |Y - Y0| <= 2) 或屏幕投影半径判定；
 *    - 结合游戏原生界面顶部【受袭/被攻击预警红标 (Top Alert Badge)】与倒计时秒数读数；
 *    - 过滤远距离无效行军，只对危及主城及贴脸城皮的真威胁拉响警报与动作。
 *
 * 2. 【60 秒无损秒回撤退主力保命】：
 *    - 发现警戒圈内致命敌袭且倒计时进入最后 60 秒时，若熟睡玩家未应答接管，
 *      全自动点开部队列表执行【撤退】，将外驻/调动主力一键秒回城内；
 *    - 彻底保留 25,000 兵力与预备兵，杜绝夜间被敌人当肉刷战功；
 *    - 危急时刻（城防告急）支持尝试启动【闭城/坚守】与资源就地征兵（焦土自保）。
 *
 * 3. 【防掉线微保活与自动重连 (Anti-Disconnect Micro-Keepalive)】：
 *    - 周期性（默认 5 分钟）在绝对安全空白区派发 2 像素轻微滑动，重置系统空闲计时器，防止 15 分钟掉线；
 *    - 自动拦截并点击“重新连接”或网络中断确认弹窗，实现断线自愈重连。
 */
open class NightSentinelFlow(
    protected val context: Context,
    protected val listener: TacticalState.TacticalEventListener? = null
) {
    protected val isRunning = AtomicBoolean(false)
    private var lastKeepAliveTimeMs: Long = 0L
    private var lastThreatTimeMs: Long = 0L
    private var hasAlarmed = false

    /** 上一次做"视觉双通道确认"的时刻；用于限流，避免持续告警时反复跑检测。 */
    private var lastVisionCheckMs: Long = 0L

    /**
     * 停止暗夜哨兵巡检与警报
     */
    open fun stop() {
        isRunning.set(false)
        AlarmRinger.stopAlarm(context)
        hasAlarmed = false
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.NIGHT_SENTINEL,
            level = "WARN",
            message = "⏹️ 暗夜天眼防沦哨兵已安全停止，警报与唤醒锁已复位。"
        ))
    }

    /**
     * 启动暗夜天眼哨兵全天候守护巡检
     */
    suspend fun startPatrol(config: SentinelConfig) {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "暗夜天眼哨兵已在运行中。")
            return
        }

        listener?.onStatusChanged(
            TacticalState.TaskType.NIGHT_SENTINEL,
            TacticalState.Status.RUNNING,
            "暗夜天眼防沦哨兵已全天候激活，守护 5x5 主城警戒圈..."
        )

        logTactic("🛡️【暗夜天眼防沦哨兵启动】巡检周期: ${config.patrolIntervalMs}ms，" +
                "警戒圈: ${config.alertCircleRadiusTiles}格(5x5)，60秒主力秒回撤退: ${config.enableAutoRetreat}，" +
                "防掉线微保活: ${config.enableKeepAliveJiggle}(${config.keepAliveIntervalMs / 1000}s/次)")

        lastKeepAliveTimeMs = System.currentTimeMillis()
        lastThreatTimeMs = 0L
        hasAlarmed = false

        try {
            while (isRunning.get()) {
                // 1. 确保大地图主界面（若有弹窗优先关窗重连，避免误入死胡同）。
                //    动画面属于触控，必须先抢所有权；抢不到说明有前台任务正在独占画面，
                //    本轮跳过导航，绝不与前台抢屏（抓屏/告警在锁外照常进行）。
                val currentState = EngineBridge.detectGameState()
                if (currentState != StzbUiMatcher.GameState.MAIN_MAP) {
                    TouchGate.tryGuardedTouch {
                        val dismissed = WatchdogRecovery.dismissAnyDialog()
                        if (!dismissed) {
                            WatchdogRecovery.recoverToMainMap()
                        }
                    }
                }

                // 2. 5分钟防掉线微保活：重置游戏客户端 15 分钟无操作掉线倒计时。
                //    属非关键触控，抢不到所有权就跳过且不更新计时（下轮继续试），
                //    避免在别人正操作画面时多此一举地滑一下抢屏。
                if (config.enableKeepAliveJiggle) {
                    val now = System.currentTimeMillis()
                    if (now - lastKeepAliveTimeMs >= config.keepAliveIntervalMs) {
                        val done = TouchGate.tryGuardedTouch { performKeepAliveJiggle() }
                        if (done != null) lastKeepAliveTimeMs = now
                    }
                }

                // 3. 扫描敌袭红线、主城警戒圈判定与顶部受袭预警红标
                val raidReport = EngineBridge.scanRaidThreats(
                    baseAnchor = config.baseAnchor,
                    baseWorldCoord = config.baseWorldCoord ?: MapProjection.baseWorld,
                    alertRadiusTiles = config.alertCircleRadiusTiles
                )

                if (raidReport.hasThreat) {
                    handleRaidEvent(raidReport, config)
                } else {
                    // 若此前曾拉响警报，且威胁已解除持续超过 60 秒，自动解除鸣叫
                    if (hasAlarmed && (System.currentTimeMillis() - lastThreatTimeMs > 60_000L)) {
                        AlarmRinger.stopAlarm(context)
                        hasAlarmed = false
                        logInfo("🕊️ 警戒圈内敌袭威胁已彻底解除，警报已自动复位。")
                    }
                }

                // 4. 拟人随机周期休眠
                //
                //    巡检周期不能是硬 `delay(N)`：固定间隔的抓屏+识别循环在时刻
                //    序列上就是一根尖峰。但这里也不能像普通轮询那样放到 3×均值
                //    ——那是安全攸关路径，长尾会把敌袭发现时间拖到三倍。
                //    因此取一个**刻意的窄带 ±30%**：足够打散等间距采样网格，
                //    代价上限只是“比标称周期晚三成发现”，两权相取其轻。
                run {
                    val mean = config.patrolIntervalMs.coerceAtLeast(1000L)
                    val jittered = TimingFingerprintEngine.poissonIntervalMs(
                        mean,
                        floorMs = (mean * 0.7f).toLong(),
                        ceilMs = (mean * 1.3f).toLong()
                    )
                    delay(jittered)
                }
            }
        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.NIGHT_SENTINEL,
                level = "ERROR",
                message = "暗夜天眼哨兵巡检异常: ${e.message}"
            ))
        } finally {
            isRunning.set(false)
            AlarmRinger.stopAlarm(context)
            hasAlarmed = false
        }
    }

    /**
     * 敌袭突发事件分流与保命处置
     */
    private suspend fun handleRaidEvent(report: RaidRadarDetector.RaidReport, config: SentinelConfig) {
        lastThreatTimeMs = System.currentTimeMillis()

        val countdownStr = report.remainingCountdownSeconds?.let { "${it}s" } ?: "未知/逼近中"
        val circleDesc = if (report.isWithinAlertCircle) "💥 已突入主城 2 格警戒圈(5x5)！" else "🛡️ 圈外远距离行军"

        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.NIGHT_SENTINEL,
            level = if (report.threatLevel == RaidRadarDetector.ThreatLevel.CRITICAL) "ERROR" else "WARN",
            message = "🚨【暗夜天眼哨兵告警：发现敌袭夜战偷家】\n" +
                    "  • 威胁等级: ${report.threatLevel}\n" +
                    "  • 顶部原生预警红标: ${report.isTopAlertActive} (倒计时: $countdownStr)\n" +
                    "  • 2 格警戒圈判定: $circleDesc\n" +
                    "  • 屏幕边缘呼吸红闪: ${report.isScreenEdgeAlert}\n" +
                    "  • 识别红线行军数: ${report.detectedVectors.size} 条\n" +
                    "  • 受威胁目标地: (${report.playerTargetPoint?.x?.toInt()}, ${report.playerTargetPoint?.y?.toInt()})\n" +
                    "  • ${corroborateWithVision()}"
        ))

        // 仅远距离行军且未进入警戒圈且无顶部红标：保持静默跟踪，不惊扰玩家
        if (report.threatLevel == RaidRadarDetector.ThreatLevel.WARNING &&
            !report.isWithinAlertCircle && !report.isTopAlertActive) {
            listener?.onStatusChanged(
                TacticalState.TaskType.NIGHT_SENTINEL,
                TacticalState.Status.RUNNING,
                "⚠️ 发现圈外远距离敌军行军，密切跟踪中..."
            )
            return
        }

        // 突入警戒圈或顶部红标生效：最高级别险情 (CRITICAL)
        listener?.onStatusChanged(
            TacticalState.TaskType.NIGHT_SENTINEL,
            TacticalState.Status.RUNNING,
            "🚨 主城警戒圈遭敌袭！正在执行 60 秒秒回保命与应急处置..."
        )

        // 步骤 1：立即拉响高分贝警报 + 强节奏振动，唤醒熟睡中的玩家
        if (config.enableAudioAlarm && !hasAlarmed) {
            AlarmRinger.startAlarm(context)
            hasAlarmed = true
        }

        // 步骤 2：【商业化黄金王牌——60 秒无损秒回撤退主力保命】
        //    警报已在步骤 1 无条件拉响（不受锁约束）；下面的点击属触控，需在有限等待内取得画面所有权，
        //    取不到（有前台任务正独占）则如实告警，绝不打断正在压秒的前台任务。
        val countdown = report.remainingCountdownSeconds
        val isEmergencyTime = countdown == null || countdown <= 60 || report.isWithinAlertCircle
        if (config.enableAutoRetreat && isEmergencyTime) {
            val retreated = TouchGate.guardedTouchWithTimeout(EMERGENCY_TOUCH_WAIT_MS) {
                executeAutoRetreat(config.retreatSquadSlots)
            }
            if (retreated == null) {
                logWarn(
                    "⚠️ 画面被前台任务占用，紧急秒回撤退在 ${EMERGENCY_TOUCH_WAIT_MS / 1000}s 内未取得控制权；" +
                        "警报已响、主力暂未撤回，请手动处置或待前台任务结束。"
                )
            }
        }

        // 步骤 3：紧急防沦闭城/坚守与焦土自保 (倒计时 <= 30s 或屏幕剧烈红闪)
        if (config.enableEmergencyFortify && ((countdown != null && countdown <= 30) || report.isScreenEdgeAlert)) {
            TouchGate.guardedTouchWithTimeout(EMERGENCY_TOUCH_WAIT_MS) { executeEmergencyFortify() }
        }

        // 步骤 4：决策 C 自动反击 (可选配置：若玩家开启，且已成功推算出敌军源头要塞)
        if (config.enableDecisionC && report.enemyOriginPoint != null) {
            val successC = TouchGate.guardedTouchWithTimeout(EMERGENCY_TOUCH_WAIT_MS) {
                executeDecisionC(report.enemyOriginPoint, config.counterAttackSquadSlot)
            } == true
            if (successC) {
                logTactic("⚔️【决策 C 反击大捷】已成功对敌方进攻跳板发起反攻断地出征！")
            } else {
                logWarn("决策 C 跳板地反击受阻或未获画面控制权，已安全退回大地图坚守。")
            }
        }
    }

    /**
     * 视觉双通道确认（YOLO），用于**提高**敌袭判定的可信度。
     *
     * ## 设计红线：绝不允许削弱既有判定
     * OpenCV 雷达（HSV 红掩膜 + 霍夫线 + 顶部红标）是当前唯一的判定依据。
     * 这里的结果**只进日志，不参与等级决策**：
     *   * 检出告警图标/行军红线 → 记为"双通道确认"；
     *   * 未检出 → **不降低等级**，仅记为"单通道命中（视觉未确认）"。
     *
     * 为什么必须这样：视觉模型存在**漏检**可能，一旦用"视觉没检出"去下调等级，
     * 漏检一次就等于撤掉了 60 秒秒回保护——那是本功能存在的全部意义。
     * 宁可多一次不必要的撤退，也绝不能漏掉一次真实偷家。
     *
     * @return 一行诊断结论，永远非空（便于直接拼进告警文本）
     */
    private suspend fun corroborateWithVision(): String {
        val now = System.currentTimeMillis()
        if (now - lastVisionCheckMs < VISION_CORROBORATE_INTERVAL_MS) {
            return "视觉双通道：本轮限流跳过（${VISION_CORROBORATE_INTERVAL_MS / 1000}s 内已确认过）。"
        }
        lastVisionCheckMs = now

        val detector = com.stzb.assistant.runtime.VisionRuntime.yolo(context)
            ?: return "视觉双通道：不可用（${com.stzb.assistant.runtime.VisionRuntime.describe()}）。"

        if (!com.stzb.assistant.runtime.VisionRuntime.isNativeVisionReady()) {
            // 只有真正的 ncnn 推理结果才能作为"证据"；几何色度回退不算数。
            return "视觉双通道：仅几何色度回退，结论不作为证据（判定仍以 OpenCV 雷达为准）。"
        }

        val frame = EngineBridge.captureFrame() ?: return "视觉双通道：抓屏失败，跳过。"
        return try {
            val boxes = detector.detect(frame, confThreshold = 0.40f)
            // 契约重锚后 YOLO 只做大地图多目标；敌袭告警由 OpenCV 雷达（确定性主判）负责，
            // 视觉侧仅用「行军线」检出作旁证（敌军移动迹象）。
            val alert = boxes.filter {
                it.detectionClass == com.stzb.assistant.ai.vision.YoloDetector.DetectionClass.MARCH_LINE
            }
            if (alert.isNotEmpty()) {
                val top = alert.maxByOrNull { it.confidence }
                "🟢 视觉双通道确认：检出「${top?.detectionClass?.label}」" +
                    "（置信 ${"%.2f".format(top?.confidence ?: 0f)}），判定可信度提高。"
            } else {
                "⚠️ 视觉未检出告警图标（仅作诊断；**等级不下调**，仍按 OpenCV 雷达判定处置）。"
            }
        } catch (e: Exception) {
            "视觉双通道：检测异常 ${e.message}（不影响既定处置）。"
        } finally {
            frame.recycle()
        }
    }

    /**
     * 【核心保命王牌】：60秒无损秒回撤退主力部队 (避免 25000 兵力与预备兵被敌军吃掉)
     */
    private suspend fun executeAutoRetreat(squadSlots: List<Int>): Boolean {
        logTactic("🏃【执行 60 秒无损秒回撤退】正在撤回主力队 $squadSlots，保全 25000 满编兵力与预备兵...")

        var anyRetreated = false
        WatchdogRecovery.recoverToMainMap()

        for (slot in squadSlots) {
            // 点击对应编队标签
            val tabPoint = UiAnchors.troopTab(slot)
            EngineBridge.tap(tabPoint.x, tabPoint.y)
            EngineBridge.humanDelay(350, 600)

            // 检索是否存在【撤退】语义按键
            val retreatBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.RETREAT)
            if (retreatBtn != null) {
                val tapped = EngineBridge.tap(retreatBtn.safeTouchPoint.x, retreatBtn.safeTouchPoint.y)
                if (tapped) {
                    EngineBridge.humanDelay(500, 800)
                    // 二次确认弹窗中的【确定】
                    val confirm = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
                    if (confirm.clicked) {
                        logTactic("✅【部队 $slot 秒回撤退成功】已成功撤回主城营地，免遭围歼！")
                        anyRetreated = true
                    }
                }
            } else {
                logInfo("部队 $slot 当前未在城外行军/驻守，或已安全在城内。")
            }

            // 轻点空白区收起弹窗
            WatchdogRecovery.tapSafeBlankArea()
            EngineBridge.humanDelay(250, 450)
        }

        WatchdogRecovery.recoverToMainMap()
        return anyRetreated
    }

    /**
     * 【紧急焦土与防沦闭城】：坚守 3 小时免战准备 + 征兵花光资源
     */
    private suspend fun executeEmergencyFortify(): Boolean {
        logTactic("🏰【触发紧急防沦闭城】敌军兵临城下或耐久告急，尝试开启【坚守】与焦土征兵...")
        WatchdogRecovery.recoverToMainMap()

        // 1. 点击主城中心地块
        val center = UiAnchors.point(UiAnchors.Key.MAP_BLANK)
        EngineBridge.tap(center.x, center.y)
        EngineBridge.humanDelay(500, 800)

        // 2. 检索并点击【坚守】（闭城）
        val frame = EngineBridge.captureFrame()
        if (frame != null) {
            val matches = OcrManager.findKeywords(frame, listOf("坚守", "闭城"))
            frame.recycle()
            if (matches.isNotEmpty()) {
                val fortifyBtn = matches.first()
                EngineBridge.tap(fortifyBtn.centerX, fortifyBtn.centerY)
                EngineBridge.humanDelay(600, 900)
                val confirm = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
                if (confirm.clicked) {
                    logTactic("🛡️【坚守指令下达成功】主城已开启闭城准备，即将进入绝对免战！")
                    WatchdogRecovery.recoverToMainMap()
                    return true
                }
            }
        }

        // 3. 尝试【快速征兵】花光资源（焦土政策：木铁石粮全变兵，让敌军打下来也是空壳）
        val recruitBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.RECRUIT)
        if (recruitBtn != null) {
            EngineBridge.tap(recruitBtn.safeTouchPoint.x, recruitBtn.safeTouchPoint.y)
            EngineBridge.humanDelay(500, 800)
            EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
            logTactic("🌾【焦土政策生效】已将主城现存资源转为征兵队列，杜绝敌盟掠夺！")
        }

        WatchdogRecovery.recoverToMainMap()
        return false
    }

    /**
     * 【防掉线微保活】：在地图安全空白区派发 2 像素轻微滑动，重置系统空闲计时器
     */
    private suspend fun performKeepAliveJiggle() {
        val p = UiAnchors.point(UiAnchors.Key.MAP_BLANK)
        val success = EngineBridge.swipe(p.x, p.y, p.x + 2f, p.y + 1f, durationMs = 120L)
        if (success) {
            logInfo("💓【防掉线微保活】已派发 5 分钟微触控心跳，持续维持游戏在线活跃状态。")
        } else {
            logWarn("⚠️ 防掉线微保活触控未成功派发，无障碍服务可能受阻。")
        }
    }

    /**
     * 【决策 C 反击】：拆除敌人跳板要塞与断其链接地
     */
    protected suspend fun executeDecisionC(enemyOriginPoint: PointF, squadSlot: Int): Boolean {
        logTactic("🎯【执行决策 C 反击】正在锁定敌方源头跳板要塞/链接地: (${enemyOriginPoint.x.toInt()}, ${enemyOriginPoint.y.toInt()})")

        // 1. 点击敌军源头地块
        EngineBridge.tap(enemyOriginPoint.x, enemyOriginPoint.y)
        val menuOpened = EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)
        if (!menuOpened) {
            logWarn("未能打开敌方源头地块菜单，尝试二次点击...")
            EngineBridge.tap(enemyOriginPoint.x, enemyOriginPoint.y)
            if (!EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2000)) {
                return false
            }
        }

        // 2. 点击【出征】发起反击，并确认选队面板弹出
        val attack = EngineBridge.clickAndExpect(
            StzbUiMatcher.ButtonType.ATTACK,
            StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
            timeoutMs = 3000L,
            attempts = 2
        )
        if (!attack.ok) {
            logWarn("敌方跳板地未能进入出征面板：${attack.detail}")
            return false
        }

        // 3. 切换至高机动拆迁骑兵队
        val tabPoint = UiAnchors.troopTab(squadSlot)
        EngineBridge.tap(tabPoint.x, tabPoint.y)
        EngineBridge.humanDelay(300, 500)

        // 4. 点击【确定出征】
        val confirm = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
        if (!confirm.clicked) {
            logWarn("反击时未能点击【确定出征】：${confirm.detail}")
        }
        EngineBridge.humanDelay(800, 1200)

        WatchdogRecovery.recoverToMainMap()
        return confirm.clicked
    }

    protected fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.NIGHT_SENTINEL, "INFO", msg))
    protected fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.NIGHT_SENTINEL, "WARN", msg))
    protected fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.NIGHT_SENTINEL, "TACTIC", msg))

    protected fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "NightSentinelFlow"

        /**
         * 视觉双通道确认的最小间隔。
         *
         * 一次 ncnn 推理在移动端的开销在几十到几百毫秒量级；夜战告警一旦成立，
         * `handleRaidEvent` 会按巡检周期反复进入，若每轮都跑检测就是无意义的持续开销。
         * 因此限定 30 秒最多确认一次——它只用于**提高判定可信度**，不承担实时性职责。
         */
        private const val VISION_CORROBORATE_INTERVAL_MS = 30_000L

        /** 守护执行关键触控时，最多等待画面所有权这么久；超时放弃（警报已响）。 */
        private const val EMERGENCY_TOUCH_WAIT_MS = 12_000L
    }
}
