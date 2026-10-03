package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import kotlinx.coroutines.delay

/**
 * 单账号日常后勤全托管引擎 (DailyLogisticsFlow)
 *
 * 核心痛点解决：
 *   1. 【定时自动税收/征税】：每日主城税收、防止铜钱产出浪费与遗漏；
 *   2. 【自动预备役征兵/伤兵补充】：巡检各部队槽位伤兵损耗，当低于健康阈值时快速补充预备役满编；
 *   3. 【体力防溢出巡回】：监测主力与铺路队体力（满 120/120），在即将溢出（>= 110）时调度预警与练兵；
 *   4. 【城建/技术自动升级】：巡检内政与军事设施升级队列，空闲时自动排队升级；
 *   5. 【全链路看门狗防卡死】：每步操作后状态自愈，高仿生触控微扰动，保障单账号 7x24 小时稳定运转。
 */
class DailyLogisticsFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener
) {

    @Volatile
    private var isRunning: Boolean = false

    data class LogisticsConfig(
        val enableTaxLevy: Boolean = true,
        val maxDailyTaxTimes: Int = 3,
        val enableReserveRecruitment: Boolean = true,
        val recruitSlots: List<Int> = listOf(1, 2, 3),
        val minTroopHealthPercent: Float = 0.85f,
        val enableStaminaProtection: Boolean = true,
        val staminaOverflowThreshold: Int = 110,
        val enableCityConstruction: Boolean = true,
        val cityBookmarkName: String? = "主城",
        val cityWorldCoord: Pair<Int, Int>? = null
    )

    fun stop() {
        isRunning = false
        logTactic("⏹️ 日常后勤全托管已收到终止请求")
    }

    suspend fun execute(config: LogisticsConfig): Boolean {
        isRunning = true
        notifyStatus(TacticalState.Status.RUNNING, "开始执行单账号日常后勤全托管流程...")
        logTactic("📦 后勤全托管启动: 税收=${config.enableTaxLevy}, 征兵=${config.enableReserveRecruitment}, 体力防溢=${config.enableStaminaProtection}, 城建=${config.enableCityConstruction}")

        try {
            // 1. 确保大地图就绪并对准主城
            if (!ensureMainMapAndTargetCity(config)) {
                notifyStatus(TacticalState.Status.FAILED, "未能对准主城，后勤流程中断")
                return false
            }

            if (!isRunning) return false

            // 2. 自动税收与征税
            if (config.enableTaxLevy) {
                performTaxLevy(config)
            }

            if (!isRunning) return false

            // 3. 自动预备役征兵与伤兵补充
            if (config.enableReserveRecruitment) {
                performReserveRecruitment(config)
            }

            if (!isRunning) return false

            // 4. 主力体力防溢出巡回检查
            if (config.enableStaminaProtection) {
                performStaminaProtection(config)
            }

            if (!isRunning) return false

            // 5. 城建与内政设施队列自动升级
            if (config.enableCityConstruction) {
                performCityConstruction(config)
            }

            logTactic("✅ 单账号日常后勤全托管流程全部执行完毕")
            notifyStatus(TacticalState.Status.COMPLETED, "日常后勤全托管巡检圆满完成")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "日常后勤流程异常: ${e.message}", e)
            logTactic("❌ 日常后勤发生异常: ${e.message}")
            notifyStatus(TacticalState.Status.FAILED, "执行异常: ${e.message}")
            return false
        } finally {
            isRunning = false
            WatchdogRecovery.recoverToMainMap()
        }
    }

    /**
     * 确保处于大地图主界面，并优先通过书签/坐标对准主城
     */
    private suspend fun ensureMainMapAndTargetCity(config: LogisticsConfig): Boolean {
        logTactic("🧭 检查游戏场景并对准主城...")
        WatchdogRecovery.recoverToMainMap()

        // 1. 书签 0 漂移瞬间对准
        if (!config.cityBookmarkName.isNullOrBlank()) {
            logTactic("🔖 使用官方书签 [${config.cityBookmarkName}] 对准主城...")
            when (val res = MapNavigator.jumpByBookmark(config.cityBookmarkName)) {
                is MapNavigator.Result.Reached -> {
                    logTactic("🔖 官方书签对准成功，主城位于镜头中心")
                    return true
                }
                is MapNavigator.Result.Refused -> logTactic("书签对准拒绝: ${res.reason}，尝试坐标对准")
                is MapNavigator.Result.Failed -> logTactic("书签对准失败: ${res.reason}，尝试坐标对准")
            }
        }

        // 2. 世界坐标对准
        if (config.cityWorldCoord != null && MapProjection.isCalibrated) {
            val (wx, wy) = config.cityWorldCoord
            logTactic("🧭 使用世界坐标 ($wx, $wy) 对准主城...")
            when (val nav = MapNavigator.centerOn(wx, wy)) {
                is MapNavigator.Result.Reached -> {
                    logTactic("🧭 世界坐标对准完成")
                    return true
                }
                is MapNavigator.Result.Refused -> logTactic("世界坐标导航拒绝: ${nav.reason}")
                is MapNavigator.Result.Failed -> logTactic("世界坐标导航失败: ${nav.reason}")
            }
        }

        logTactic("⚠️ 未能通过书签或坐标精确定位主城，使用当前屏幕中心尝试")
        return true
    }

    /**
     * 执行税收与征税逻辑
     */
    private suspend fun performTaxLevy(config: LogisticsConfig): Boolean {
        logTactic("💰 正在检查今日主城税收/征税状态...")
        val center = MapProjection.viewportCenterCanvas()

        // 点击主城中心唤起城建与内政轮盘
        EngineBridge.tap(center.x, center.y)
        EngineBridge.humanDelay(600, 1000)

        // 查找【税收】按键
        var taxBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.TAX)
        if (taxBtn == null) {
            // 尝试查找【确定】或【内政】进入
            taxBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.CONFIRM)
        }

        if (taxBtn != null) {
            logTactic("💰 发现税收/征税入口: ${taxBtn.matchedText}，执行安全点击")
            EngineBridge.tap(taxBtn.safeTouchPoint.x, taxBtn.safeTouchPoint.y)
            EngineBridge.humanDelay(800, 1200)

            // 处理可能弹出的征税确认框
            val confirmBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.CONFIRM)
            if (confirmBtn != null) {
                EngineBridge.tap(confirmBtn.safeTouchPoint.x, confirmBtn.safeTouchPoint.y)
                EngineBridge.humanDelay(600, 900)
            }
            logTactic("✅ 今日税收征收指令已下发完成")
        } else {
            logTactic("ℹ️ 当前主城未见可用税收按键（可能今日税收已领完或处于冷却中）")
        }

        WatchdogRecovery.recoverToMainMap()
        return true
    }

    /**
     * 巡检各槽位伤兵损耗并补充预备役
     */
    private suspend fun performReserveRecruitment(config: LogisticsConfig): Boolean {
        logTactic("🛡️ 正在巡检部队伤兵与预备役配置情况...")
        val center = MapProjection.viewportCenterCanvas()

        // 点击主城进入出征/部队面板
        EngineBridge.tap(center.x, center.y)
        EngineBridge.humanDelay(600, 1000)

        // 点击【征兵】按钮
        val recruitAction = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.RECRUIT)
        if (!recruitAction.ok) {
            logTactic("⚠️ 未检测到【征兵】直接按钮，尝试通过出征选队面板巡检")
            val dispatchRes = EngineBridge.clickAndExpect(
                StzbUiMatcher.ButtonType.ATTACK,
                StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
                timeoutMs = 2500L,
                attempts = 2
            )
            if (!dispatchRes.ok) {
                logTactic("未能打开部队面板，跳过本轮征兵巡检")
                WatchdogRecovery.recoverToMainMap()
                return false
            }
        }

        // 逐槽位检查伤病与补兵
        for (slot in config.recruitSlots.filter { it in 1..UiAnchors.troopTabSlotCount }) {
            if (!isRunning) break
            val tabPoint = UiAnchors.troopTab(slot)
            EngineBridge.tap(tabPoint.x, tabPoint.y)
            EngineBridge.humanDelay(400, 700)

            val frame = EngineBridge.captureFrame() ?: continue
            try {
                val cardRoi = UiAnchors.rect(UiAnchors.RectKey.TROOP_CARD)
                val cardBmp = cropSafe(frame, cardRoi)
                if (cardBmp != null) {
                    val detail = TroopStatusDetector.parseTroopCard(cardBmp, slot)
                    cardBmp.recycle()

                    val cur = detail.currentTroops
                    val max = detail.maxTroops
                    if (cur != null && max != null && max > 0) {
                        val healthRatio = cur.toFloat() / max.toFloat()
                        if (healthRatio < config.minTroopHealthPercent) {
                            logTactic("⚠️ 部队[$slot] 兵力不足 ($cur/$max, ${(healthRatio * 100).toInt()}%)，触发快速补充预备役！")
                            // 点击快速征兵/确认补充
                            val confirmBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.CONFIRM)
                                ?: EngineBridge.findButton(StzbUiMatcher.ButtonType.RECRUIT)
                            if (confirmBtn != null) {
                                EngineBridge.tap(confirmBtn.safeTouchPoint.x, confirmBtn.safeTouchPoint.y)
                                EngineBridge.humanDelay(500, 800)
                                logTactic("✅ 部队[$slot] 补兵指令下发成功")
                            }
                        } else {
                            logTactic("🟢 部队[$slot] 兵力健康 ($cur/$max, ${(healthRatio * 100).toInt()}%)，无需补兵")
                        }
                    }
                }
            } finally {
                frame.recycle()
            }
        }

        WatchdogRecovery.recoverToMainMap()
        return true
    }

    /**
     * 主力与辅队体力防溢出巡回检查
     */
    private suspend fun performStaminaProtection(config: LogisticsConfig): Boolean {
        logTactic("⚡ 正在巡回检查各编队体力水位（防溢出阈值: ${config.staminaOverflowThreshold}/120）...")
        val center = MapProjection.viewportCenterCanvas()

        EngineBridge.tap(center.x, center.y)
        EngineBridge.humanDelay(600, 1000)

        val res = EngineBridge.clickAndExpect(
            StzbUiMatcher.ButtonType.ATTACK,
            StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
            timeoutMs = 2500L,
            attempts = 2
        )
        if (!res.ok) {
            logTactic("未能打开出征面板查看体力，跳过体力巡检")
            WatchdogRecovery.recoverToMainMap()
            return false
        }

        for (slot in 1..UiAnchors.troopTabSlotCount) {
            if (!isRunning) break
            val tabPoint = UiAnchors.troopTab(slot)
            EngineBridge.tap(tabPoint.x, tabPoint.y)
            EngineBridge.humanDelay(300, 600)

            val frame = EngineBridge.captureFrame() ?: continue
            try {
                val cardRoi = UiAnchors.rect(UiAnchors.RectKey.TROOP_CARD)
                val cardBmp = cropSafe(frame, cardRoi)
                if (cardBmp != null) {
                    val detail = TroopStatusDetector.parseTroopCard(cardBmp, slot)
                    cardBmp.recycle()

                    val stamina = detail.stamina
                    if (stamina != null) {
                        if (stamina >= config.staminaOverflowThreshold) {
                            logTactic("🚨 警告：部队[$slot] 体力达到 $stamina/120，即将溢出！建议安排练兵/屯田消耗体力")
                        } else {
                            logTactic("部队[$slot] 当前体力: $stamina/120 (正常)")
                        }
                    }
                }
            } finally {
                frame.recycle()
            }
        }

        WatchdogRecovery.recoverToMainMap()
        return true
    }

    /**
     * 城建与内政设施队列自动升级
     */
    private suspend fun performCityConstruction(config: LogisticsConfig): Boolean {
        logTactic("🏗️ 正在巡查主城城建与设施升级队列...")
        val center = MapProjection.viewportCenterCanvas()

        EngineBridge.tap(center.x, center.y)
        EngineBridge.humanDelay(600, 1000)

        // 查找【建设】或【设施】按键
        var buildBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.BUILD)
        if (buildBtn == null) {
            buildBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.UPGRADE)
        }

        if (buildBtn != null) {
            logTactic("🏗️ 点击城建设施入口: ${buildBtn.matchedText}")
            EngineBridge.tap(buildBtn.safeTouchPoint.x, buildBtn.safeTouchPoint.y)
            EngineBridge.humanDelay(800, 1300)

            // 检查是否有可升级项
            val upgradeBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.UPGRADE)
                ?: EngineBridge.findButton(StzbUiMatcher.ButtonType.CONFIRM)
            if (upgradeBtn != null) {
                logTactic("🔨 发现可升级设施，执行建筑升级下发")
                EngineBridge.tap(upgradeBtn.safeTouchPoint.x, upgradeBtn.safeTouchPoint.y)
                EngineBridge.humanDelay(500, 800)
            } else {
                logTactic("ℹ️ 当前设施暂无可升级项或建筑队列正在忙碌")
            }
        } else {
            logTactic("未发现城建设施面板入口")
        }

        WatchdogRecovery.recoverToMainMap()
        return true
    }

    private fun cropSafe(src: Bitmap, rect: android.graphics.Rect): Bitmap? {
        val l = rect.left.coerceIn(0, src.width - 1)
        val t = rect.top.coerceIn(0, src.height - 1)
        val r = rect.right.coerceIn(l + 1, src.width)
        val b = rect.bottom.coerceIn(t + 1, src.height)
        val w = r - l
        val h = b - t
        if (w <= 0 || h <= 0) return null
        return Bitmap.createBitmap(src, l, t, w, h)
    }

    private fun logTactic(msg: String) {
        Log.i(TAG, msg)
        listener.onLogEmitted(
            TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.LOGISTICS_STEWARD,
                level = "INFO",
                message = msg
            )
        )
    }

    private fun notifyStatus(status: TacticalState.Status, detail: String) {
        listener.onStatusChanged(TacticalState.TaskType.LOGISTICS_STEWARD, status, detail)
    }

    companion object {
        private const val TAG = "DailyLogisticsFlow"
    }
}
