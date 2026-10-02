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
 * 核心痛点解决：
 *   1. 【压秒破免 (00:00:01 触敌)】：手工掐秒表容易慢 2 秒或快 1 秒（快 1 秒会被系统判定为“土地免战中”
 *      原路弹回白耗体力；慢 2 秒会被敌人补上驻守防线）。本执行器依托阶段二毫秒级倒计时，自动扣除
 *      行军时长与触控网络时延，实现 00:00:01.000 压秒破免秒杀！
 *   2. 【极限接力卡免 (无限免战阵地战)】：己方关隘要塞前排地免战即将到期时，自动派斯巴达在 00:00:01 刷新
 *      重新挂起 1 小时免战罩，将敌盟彻底堵在关口之外。
 */
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
        val targetWorldCoord: Pair<Int, Int>? = null
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

            // 2. 检测地块当前免战倒计时
            logTactic("🔍 正在通过 OpenCV 金色光罩与局部 RapidOCR 读取地块免战剩余时间...")
            val immunityStatus = EngineBridge.detectTileImmunity()
            if (!immunityStatus.isImmune || immunityStatus.remainingSeconds <= 0L) {
                logWarn("⚠️ 未检测到有效免战罩或已过免战期！剩余秒数: ${immunityStatus.remainingSeconds}")
                // 若本身已无免战，破免可直接出征
                if (config.mode == ImmunityMode.BREAK_IMMUNITY) {
                    logInfo("地块已无免战罩，可直接发起普通占领出征。")
                }
            }

            val unlockTimestampMs = if (immunityStatus.isImmune && immunityStatus.remainingSeconds > 0) {
                immunityStatus.unlockTimestampMs
            } else {
                System.currentTimeMillis() + 60 * 1000L // 默认预留 1 分钟测算
            }

            logInfo("⏱️ 目标地块免战解锁时间戳: $unlockTimestampMs (剩余: ${immunityStatus.remainingSeconds}秒)")

            // 3. 点击地块打开操作菜单
            //    已标定世界坐标时，先把镜头对准该格再点镜头中心，目标不会因镜头移动而失效。
            val tapPoint = resolveTileTapPoint(config)
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
