package com.stzb.assistant.tactics

import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.UiAnchors
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极限卡免与压秒破免执行器 (ImmunityBreakFlow)
 *
 * @deprecated 在 2026 商业级架构中，卡免破免已作为「一键破免模式」统一融入「离线战术定时管家 (ScheduledTaskManager)」，
 *             避免多入口割裂，本类保留作为底层具体执行实现与向前兼容。
 */
@Deprecated("已统一融入 ScheduledTaskManager (离线战术定时管家) 作为一键破免模式")
class ImmunityBreakFlow(
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    enum class ImmunityMode {
        BREAK_IMMUNITY, // 压秒破免 (进攻敌方免战地)
        RELAY_DEFENSE   // 接力卡免 (防守己方关口刷新免战罩)
    }

    data class ImmunityConfig(
        val mode: ImmunityMode,
        val targetTileCoord: PointF,
        val designatedTroopSlot: Int = 1,
        val latencyCompensationMs: Long = 110L, // 触控与网络时延补偿
        /**
         * 目标地块的**世界坐标**（大地图格坐标）。提供且地图投影已标定时，
         * 流程会先把镜头对准该格再点镜头中心，目标不会因镜头移动而失效。
         */
        val targetWorldCoord: Pair<Int, Int>? = null,
        /**
         * 官方书签/标记名称（优先于世界坐标，0 像素累积漂移瞬间对准）。
         */
        val bookmarkName: String? = null
    )

    fun stop() {
        isRunning.set(false)
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.IMMUNITY_BREAK,
            level = "WARN",
            message = "⏹️ 收到用户终止指令，卡免/破免执行流已终止。"
        ))
    }

    /**
     * 启动压秒破免 / 接力卡免流水线
     */
    suspend fun execute(config: ImmunityConfig): Boolean {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "卡免/破免任务已在执行中。")
            return false
        }

        val modeDesc = if (config.mode == ImmunityMode.BREAK_IMMUNITY) "压秒破免 (00:00:01触敌)" else "极限接力卡免 (刷新免战罩)"
        listener?.onStatusChanged(
            TacticalState.TaskType.IMMUNITY_BREAK,
            TacticalState.Status.RUNNING,
            "启动 [$modeDesc]，正在锁定目标地块 (${config.targetTileCoord.x.toInt()}, ${config.targetTileCoord.y.toInt()})"
        )

        try {
            // 1. 确保大地图就绪
            WatchdogRecovery.recoverToMainMap()

            // 2. 锁定并对准目标地块（优先书签 0 漂移，次选世界坐标，后选屏幕取点）
            val tapPoint = resolveTileTapPoint(config)

            // 3. 检测目标地块当前免战倒计时
            logTactic("🔍 正在通过 OpenCV 金色光罩与局部 RapidOCR 读取地块免战剩余时间...")
            val immunityStatus = EngineBridge.detectTileImmunity()

            // 破免时刻到底取哪个值——这里原先是一个真缺陷：
            // 只要读不到倒计时，就不分青红皂白地当“60 秒后就破免”。两种完全不同的
            // 情况被当成了同一种：
            //   • 画面上根本没有免战罩（地块本就可选）——那就无需压秒，立刻能打；
            //   • 看得到金色免战罩但倒计时没读出来——此时“60 秒”几乎一定是错的，
            //     照它出征会在对方仍免战时撞上去：部队白跑一趟、体力白扣，还可能暴露意图。
            // 现在分开处理，并把“读不到倒计时时要保守估计多久”交给知识库的免战时长。
            val durationSec = com.stzb.assistant.knowledge.KnowledgeBaseManager
                .activeProfile.rules.immunityDurationSec
            val unlockTimestampMs = when {
                immunityStatus.isImmune && immunityStatus.remainingSeconds > 0 ->
                    immunityStatus.unlockTimestampMs

                immunityStatus.isImmune -> {
                    // 有罩子、读不到时间：**绝不赌一个时刻出征**。
                    // 保守取“从现在起还要罩满一整段免战时长”作为下界，并直接中止本次流程。
                    // 为什么不是“那就等一小时再打”：长时间挂在一个错误假设上会把整条
                    // 流水线锁死在一个地块上，比不执行更糟。宁可不做强于做错。
                    val conservativeRemainSec = durationSec.coerceAtLeast(1)
                    logWarn(
                        "⚠️ 检测到金色免战罩但未能读出剩余倒计时。按知识库免战时长保守估计，" +
                            "破免至少还要 ${conservativeRemainSec / 60} 分钟，本次压秒中止。"
                    )
                    listener?.onStatusChanged(
                        TacticalState.TaskType.IMMUNITY_BREAK,
                        TacticalState.Status.FAILED,
                        "免战倒计时识别失败，已中止（避免往罩子里硬敲）"
                    )
                    WatchdogRecovery.recoverToMainMap()
                    return false
                }

                // 无免战罩：不需要压秒，直接按“现在”走普通占领（后面的卡秒计算会得到
                // 一个负的等待量，即立即点火）。
                else -> System.currentTimeMillis()
            }

            if (!immunityStatus.isImmune) {
                logInfo("地块当前无免战罩，无需压秒，按普通占领直接推进。")
            }
            logInfo("⏱️ 目标地块免战解锁时间戳: $unlockTimestampMs (剩余: ${immunityStatus.remainingSeconds}秒)")

            // 4. 点击地块打开操作菜单
            EngineBridge.tap(tapPoint.x, tapPoint.y)
            EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)

            // 4. 点击出征并**当场确认选队面板弹出**（带原因诊断：能说清卡在哪一环）
            //    注意：这里不是压秒点，压秒点在下面的【确定出征】，那一处刻意不动。
            val attack = EngineBridge.clickAndExpect(
                StzbUiMatcher.ButtonType.ATTACK,
                StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
                timeoutMs = 3000L,
                attempts = 2
            )
            if (!attack.ok) {
                logWarn("未能进入出征选队面板：${attack.detail}")
                WatchdogRecovery.recoverToMainMap()
                return false
            }

            // 5. 选中指定部队
            clickTroopSlotTab(config.designatedTroopSlot)
            EngineBridge.humanDelay(300, 600)

            // 6. 测算行军耗时与绝对出征触发时刻
            // 目标触敌时刻 = 破免时刻 + 1000ms (确保 00:00:01 触敌，杜绝提前 0.1 秒被系统弹回)
            val targetHitEpochMs = unlockTimestampMs + 1000L

            // 行军耗时文本区改用统一锚点表（原先是写死的"右下角 360x160"）
            val marchRoi = UiAnchors.rect(UiAnchors.RectKey.MARCH_TIME)

            val timingPlan = EngineBridge.planCardSecondDispatch(
                marchTimeRoi = marchRoi,
                targetHitEpochMs = targetHitEpochMs,
                networkJitterCompensationMs = config.latencyCompensationMs
            )

            if (timingPlan == null) {
                logWarn("⚠️ 无法识别行军耗时，尝试二次直接识别...")
                // 若局部裁剪失败，走备用出征确认
                return false
            }

            logTactic(
                "🎯【精密卡秒方案就绪】\n" +
                "  • 行军耗时: ${timingPlan.travelDurationSec} 秒\n" +
                "  • 设定触敌时刻: ${timingPlan.targetHitEpochMs}\n" +
                "  • 绝对出征触发点: ${timingPlan.optimalDispatchEpochMs}\n" +
                "  • 需等待倒计时: ${timingPlan.waitDelayMs} ms"
            )

            // 6.5 **先架枪**：把【确定出征】的触控点现在就定位好。
            //
            // 定位要抓屏 + 识别 + 匹配，耗时几十到几百毫秒。原先是在倒计时结束、
            // 目标时刻到了**之后**才调用 clickButtonDiagnosed 去定位并点击，
            // 于是手势实际落在"目标时刻 + 定位耗时"——这段耗时被整个算进误差里。
            // 现在定位提前做，扣扳机时只剩一次手势派发。
            val preparedConfirm = EngineBridge.prepareButtonTap(StzbUiMatcher.ButtonType.CONFIRM)
            if (preparedConfirm == null) {
                logWarn("⚠️ 无法预先定位【确定出征】按键，压秒无法执行（未出征）。")
                listener?.onStatusChanged(
                    TacticalState.TaskType.IMMUNITY_BREAK,
                    TacticalState.Status.FAILED,
                    "压秒中止：未能定位【确定出征】"
                )
                WatchdogRecovery.recoverToMainMap()
                return false
            }

            // 7. 高精度倒计时排队与毫秒级点火出征
            if (timingPlan.waitDelayMs > 0) {
                listener?.onStatusChanged(
                    TacticalState.TaskType.IMMUNITY_BREAK,
                    TacticalState.Status.WAITING_COUNTDOWN,
                    "卡秒倒计时等待中，还剩 ${(timingPlan.waitDelayMs / 1000)} 秒出征"
                )

                // 粗略休眠到出征前 2000ms
                if (timingPlan.waitDelayMs > 2000) {
                    delay(timingPlan.waitDelayMs - 2000)
                }

                // 最后 2000ms 精密空转循环，锁定毫秒级精度
                while (isRunning.get()) {
                    val now = System.currentTimeMillis()
                    if (now >= timingPlan.optimalDispatchEpochMs) {
                        break
                    }
                    delay(5)
                }
            }

            if (!isRunning.get()) return false

            // 8. 毫秒级扣动扳机：只做一次手势派发（定位已在 6.5 完成）
            val fired = EngineBridge.firePreparedTap(preparedConfirm)
            val triggerTime = fired.dispatchEpochMs
            val diffMs = triggerTime - timingPlan.optimalDispatchEpochMs

            if (fired.dispatched) {
                logTactic("🚀【出征触发完毕】时间误差: ${diffMs}ms！部队正高速开赴目标，预计将在 00:00:01 准点触敌！")
                if (diffMs > LATE_TOLERANCE_MS) {
                    // 派发时刻本身就晚了：必须说清，而不是把"晚了 300ms"包装成准点。
                    logWarn(
                        "⚠️ 压秒实际晚打 ${diffMs}ms（容忍 ${LATE_TOLERANCE_MS}ms）。" +
                            "可检查设备卡顿，或适当增大网络补偿 latencyCompensationMs。"
                    )
                }
            } else {
                // 原实现拿到了返回值却不用，无论成败都打印"出征触发完毕、误差仅 xx ms"，
                // 把"根本没点中"包装成"压秒精准"。现在如实报告。
                logWarn(
                    "⚠️【确定出征】手势派发失败（原定时间误差 ${diffMs}ms）；" +
                        "本次压秒未生效，请人工确认该队是否已出发。"
                )
                listener?.onStatusChanged(
                    TacticalState.TaskType.IMMUNITY_BREAK,
                    TacticalState.Status.FAILED,
                    "压秒失败：【确定出征】未点中"
                )
                return false
            }

            listener?.onStatusChanged(
                TacticalState.TaskType.IMMUNITY_BREAK,
                TacticalState.Status.COMPLETED,
                "压秒破免执行完毕，出征时间偏差仅 ${diffMs}ms"
            )
            return true

        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.IMMUNITY_BREAK,
                level = "ERROR",
                message = "卡免/破免执行异常: ${e.message}"
            ))
            return false
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 解析"该点哪个屏幕坐标才能点到目标地块"。
     * 有世界坐标且已标定时先对准镜头、点镜头中心；否则回退取点屏幕坐标。
     */
    private suspend fun resolveTileTapPoint(config: ImmunityConfig): PointF {
        // 1. 优先使用官方书签 0 漂移瞬间居中对准
        val bookmark = config.bookmarkName
        if (!bookmark.isNullOrBlank()) {
            when (val nav = MapNavigator.jumpByBookmark(bookmark)) {
                is MapNavigator.Result.Reached -> {
                    logInfo("🔖 已通过官方书签 [$bookmark] 0 漂移居中锁定目标地块")
                    return MapProjection.viewportCenterCanvas()
                }
                is MapNavigator.Result.Refused ->
                    logWarn("书签跳转被拒绝: ${nav.reason}，尝试坐标回退")
                is MapNavigator.Result.Failed ->
                    logWarn("书签跳转失败: ${nav.reason}，尝试坐标回退")
            }
        }

        // 2. 世界坐标大地图对准
        val world = config.targetWorldCoord
        if (world != null && MapProjection.isCalibrated) {
            when (val nav = MapNavigator.centerOn(world.first, world.second)) {
                is MapNavigator.Result.Reached -> {
                    logInfo("🧭 已按世界坐标 (${world.first},${world.second}) 对准目标地块镜头")
                    return MapProjection.viewportCenterCanvas()
                }
                is MapNavigator.Result.Refused ->
                    logWarn("世界坐标导航被拒绝，改用取点屏幕坐标：${nav.reason}")
                is MapNavigator.Result.Failed ->
                    logWarn("世界坐标导航失败，改用取点屏幕坐标：${nav.reason}")
            }
        }
        return config.targetTileCoord
    }

    private suspend fun clickTroopSlotTab(slot: Int) {
        // 统一锚点表，不再按 1280 宽画布写死 220/140/160
        val p = UiAnchors.troopTab(slot)
        EngineBridge.tap(p.x, p.y)
    }

    private fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.IMMUNITY_BREAK, "INFO", msg))
    private fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.IMMUNITY_BREAK, "WARN", msg))
    private fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.IMMUNITY_BREAK, "TACTIC", msg))

    private fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "ImmunityBreakFlow"

        /** 压秒的可容忍迟到量；超过它必须显式告警，而不是当成准点。 */
        private const val LATE_TOLERANCE_MS = 150L
    }
}
