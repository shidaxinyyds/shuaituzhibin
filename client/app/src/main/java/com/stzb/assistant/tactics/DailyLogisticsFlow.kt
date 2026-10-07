package com.stzb.assistant.tactics

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单账号日常后勤全托管引擎 (DailyLogisticsFlow)
 *
 * 核心痛点解决：
 *   1. 【定时自动税收/征税 + 熔断防误点扣玉】：
 *      - 每日税收有次数上限（[LogisticsConfig.maxDailyTaxTimes]，默认 3 次），
 *        按 **自然日** 持久化计数，跨天自动清零；
 *      - 同一自然日内有冷却时间，防止短时间内重复点击；
 *      - **付费熔断**：点【税收】之前先用 OCR 检查屏幕是否出现"玉/元宝/符/充值"等
 *        付费字样，一旦命中就**放弃本轮并记录**，杜绝把免费征税误点到付费强征上去。
 *   2. 【自动预备役征兵/伤兵补充】：巡检各部队槽位伤兵损耗，低于健康阈值时补充预备役；
 *   3. 【体力防溢出巡回】：体力上限取自当前知识库，接近上限告警、到上限即满溢，
 *      并**真正派发一次练兵/演武去消耗体力**（而不是只打印一句"建议消耗"）；
 *   4. 【城建/技术自动升级】：巡检内政与军事设施升级队列，空闲时自动排队升级；
 *   5. 【全链路看门狗防卡死】：每步操作后状态自愈，高仿生触控微扰动，保障 7x24 稳定运转。
 */
class DailyLogisticsFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener
) {

    @Volatile
    private var isRunning: Boolean = false

    data class LogisticsConfig(
        val enableTaxLevy: Boolean = true,
        /** 每日税收次数上限（自然日内计数，跨天清零）。 */
        val maxDailyTaxTimes: Int = 3,
        /** 两次税收之间的最小间隔，防止同一分钟内反复点击。 */
        val taxCooldownMs: Long = 60_000L,
        /** 是否在检测到付费字样时熔断放弃（强烈建议保持 true）。 */
        val abortTaxOnPaidCost: Boolean = true,
        val enableReserveRecruitment: Boolean = true,
        val recruitSlots: List<Int> = listOf(1, 2, 3),
        val minTroopHealthPercent: Float = 0.85f,
        val enableStaminaProtection: Boolean = true,
        val staminaOverflowThreshold: Int = 110,
        /** 体力达到阈值时是否真的派发练兵/演武消耗体力（false 则只告警）。 */
        val enableStaminaConsumption: Boolean = true,
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
     * 执行税收与征税逻辑（含**每日上限 + 冷却 + 付费熔断**三重保护）
     *
     * ## 之前的问题
     * 原先只要"看到 TAX 就点、看到 CONFIRM 就点"，而 `ButtonType.TAX` 的别名表里
     * **包含"强征"**——在率土里那通常是要消耗玉符的付费征税。
     * 同时 `maxDailyTaxTimes` 字段声明了却从未被读取。
     * 结果是：参数上写着一个"3 次上限"，实际既不限次也不区分免费/付费，
     * 存在**误点付费强征、扣掉玩家玉符**的真实风险。
     *
     * ## 现在的行为
     *   1. 自然日计数（SharedPreferences 持久化），达到 [LogisticsConfig.maxDailyTaxTimes] 直接熔断；
     *   2. 同日内冷却 [LogisticsConfig.taxCooldownMs]，冷却中直接跳过；
     *   3. 点击前用 OCR 扫一遍屏幕，命中付费字样（玉/元宝/符/充值/购买）
     *      → **放弃本轮**并写入日志，宁可少收一次税，也不动玩家的人民币资源；
     *   4. 只有真正完成了点击，才把当日计数 +1（失败不计数，避免"以为收过其实没收到"）。
     */
    private suspend fun performTaxLevy(config: LogisticsConfig): Boolean {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val prefs = taxPrefs()
        // 复用 todayTaxCount()，避免"读计数"的逻辑散落两处而漂移
        val countToday = todayTaxCount()

        // 熔断 1：自然日次数上限
        if (countToday >= config.maxDailyTaxTimes) {
            logTactic("🛑【税收熔断】今日已完成 $countToday/${config.maxDailyTaxTimes} 次，跳过本轮（跨天自动重置）。")
            return false
        }

        // 熔断 2：同日内冷却
        val lastAt = prefs.getLong(KEY_TAX_LAST_AT, 0L)
        val sinceLast = System.currentTimeMillis() - lastAt
        if (lastAt > 0L && sinceLast in 0 until config.taxCooldownMs) {
            val waitSec = (config.taxCooldownMs - sinceLast) / 1000
            logTactic("⏳【税收冷却】距上次征税仅 ${sinceLast / 1000}s，需再等 ${waitSec}s，跳过本轮。")
            return false
        }

        logTactic("💰 正在检查今日主城税收/征税状态（今日第 ${countToday + 1}/${config.maxDailyTaxTimes} 次）...")
        val center = MapProjection.viewportCenterCanvas()

        // 点击主城中心唤起城建与内政轮盘
        EngineBridge.tap(center.x, center.y)
        EngineBridge.humanDelay(600, 1000)

        // 熔断 3：付费代价检测。界面已经打开，此时扫一帧最贴近"即将点击的那个按钮"。
        if (config.abortTaxOnPaidCost) {
            // ⚠️ fail-closed：OCR 不可用时**不能**当作"没看到付费字样"继续点，
            // 否则叠加下方找不到【征税】就点【确定】的兜底，会在别的弹窗上误点真金白银扣玉。
            // 安全机制在依赖缺失时必须选择停手，而不是选择放行。
            if (!OcrManager.isEngineAvailable) {
                logTactic(
                    "🛑【税收付费熔断】OCR 引擎不可用，无法确认当前界面是否付费强征，" +
                        "为防误扣玉本轮放弃征税（引擎恢复后自动继续）。"
                )
                WatchdogRecovery.recoverToMainMap()
                return false
            }
            val hit = detectPaidCostKeyword()
            if (hit != null) {
                logTactic(
                    "🛑【税收付费熔断】界面出现付费字样「$hit」，为避免误点强征扣玉，本轮已放弃。" +
                        "如确认该界面是免费征税，可在配置中关闭 abortTaxOnPaidCost。"
                )
                WatchdogRecovery.recoverToMainMap()
                return false
            }
        }

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

            // 处理可能弹出的征税确认框（再次做一次付费检测，弹窗上常写"消耗 X 玉符"）
            if (config.abortTaxOnPaidCost) {
                val hit2 = detectPaidCostKeyword()
                if (hit2 != null) {
                    logTactic("🛑【税收付费熔断】确认弹窗出现付费字样「$hit2」，已放弃确认，未消耗任何玉符。")
                    WatchdogRecovery.recoverToMainMap()
                    return false
                }
            }

            val confirmBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.CONFIRM)
            if (confirmBtn != null) {
                EngineBridge.tap(confirmBtn.safeTouchPoint.x, confirmBtn.safeTouchPoint.y)
                EngineBridge.humanDelay(600, 900)
            }

            // 只有确实走完点击流程才记账，避免"以为收过其实没收到"
            val newCount = countToday + 1
            prefs.edit()
                .putString(KEY_TAX_DATE, today)
                .putInt(KEY_TAX_COUNT, newCount)
                .putLong(KEY_TAX_LAST_AT, System.currentTimeMillis())
                .apply()
            logTactic("✅ 今日税收征收完成（$newCount/${config.maxDailyTaxTimes}），已写入当日计数。")
        } else {
            logTactic("ℹ️ 当前主城未见可用税收按键（可能今日税收已领完或处于冷却中），本次不计入次数。")
        }

        WatchdogRecovery.recoverToMainMap()
        return true
    }

    /**
     * 在当前画面上检测"付费代价"字样。
     *
     * 为什么要单独做：率土的"征税"与"强征"在语义按键匹配时高度相似
     * （`ButtonType.TAX` 的别名同时含"征税"与"强征"），
     * 仅靠按键文字无法区分免费/付费，必须看**上下文里有没有代价提示**。
     *
     * @return 命中的付费关键词；未命中返回 null（表示"没看到付费字样"，
     *         而不是"确认免费"——这是刻意的保守设计：只在看到明确付费证据时才熔断）
     */
    private fun detectPaidCostKeyword(): String? {
        if (!OcrManager.isEngineAvailable) return null
        val frame = EngineBridge.captureFrame() ?: return null
        return try {
            val ocr = OcrManager.detect(frame) ?: return null
            val text = ocr.strRes
            if (text.isBlank()) return null
            PAID_COST_KEYWORDS.firstOrNull { text.contains(it) }
        } catch (e: Exception) {
            Log.w(TAG, "付费代价关键词检测异常: ${e.message}")
            null
        } finally {
            frame.recycle()
        }
    }

    private fun taxPrefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_LOGISTICS, Context.MODE_PRIVATE)

    /** 当日税收次数，供 UI / 日志展示真实进度（而不是只存在于字段里）。 */
    fun todayTaxCount(): Int {
        val prefs = taxPrefs()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return if (prefs.getString(KEY_TAX_DATE, "") == today) prefs.getInt(KEY_TAX_COUNT, 0) else 0
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
        // 体力上限一律取当前知识库，不再用写死的 120。
        //
        // 为什么这不只是“换个数字”：旧实现拿常量 120 做满溢判据、又拿默认 110 做告警判据，
        // 两者都假定了“上限至少 120”。一旦某个游戏的体力上限是 100（三战类玩法常见），
        // `stamina >= 120` 与 `stamina >= 110` **永远不成立**，整个防溢出巡回就会“跑得很勤但什
        // 么也不做”——体力满溢挂机损失恰恰是这项功能要防的东西。现在上限来自热更可达的库，
        // 并把告警水位夹到上限之内，让两级判据在任何上限下都真的能命中。
        val staminaMax = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules.maxStamina
        val warnAt = config.staminaOverflowThreshold.coerceIn(1, staminaMax)

        logTactic("⚡ 正在巡回检查各编队体力水位（上限 $staminaMax，防溢出告警线 $warnAt）...")
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
                        // 与 validate_logistics_farming.py 的 M3 水位模型保持一致：
                        //   >= 上限 满溢（OVERFLOW_CRITICAL）/ >= 告警线 告警（OVERFLOW_WARNING）/ 其余正常
                        // “是否满溢”直接用感知层给出的 [TroopSlotDetail.isStaminaFull]，不再在这里
                        // 拿 stamina 与 staminaMax 再比一遍：同一个判据写两处，一旦两处取数不同源
                        // （比如热更只改到了其中一边）就会出现“卡片上显示满溢、流程却说没满”。
                        when {
                            detail.isStaminaFull -> {
                                logTactic("🚨 部队[$slot] 体力已满溢 $stamina/$staminaMax，立即派发消耗动作...")
                                if (config.enableStaminaConsumption) {
                                    consumeOverflowStamina(slot)
                                }
                            }
                            stamina >= warnAt -> {
                                logTactic("⚠️ 部队[$slot] 体力 $stamina/$staminaMax 接近满溢，派发消耗动作...")
                                if (config.enableStaminaConsumption) {
                                    consumeOverflowStamina(slot)
                                } else {
                                    logTactic("（已按配置关闭体力消耗派发，仅告警）")
                                }
                            }
                            else -> logTactic("部队[$slot] 当前体力: $stamina/$staminaMax (正常)")
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

    /**
     * 体力接近/达到满溢时，**真正**派发一次消耗动作。
     *
     * ## 为什么必须有它
     * 原先体力巡检在达标时只写一句"建议安排练兵/屯田消耗体力"就结束了——
     * 那句话是写给玩家看的，程序自己什么都没做，体力照样 120 溢出。
     * 这属于典型的"有检测、无处置"，也是本轮要消灭的"摆设"之一。
     *
     * ## 处置链路
     * 当前已处于【出征选队面板】（调用方在面板内逐队巡检），
     * 因此在面板内直接找【练兵】(TRAIN) 语义按键：
     *   - 找到 → 点击并确认，让该队进入练兵消耗体力；
     *   - 找不到 → **如实报告"本界面无练兵入口，已记录待处理"**，绝不假装成功。
     *
     * 刻意不做的事：不盲点坐标、不在没有语义证据时猜测按钮。
     * 宁可少消耗一次体力，也不能因为猜错按钮把部队派出去送死。
     *
     * @return true 表示确实派发了消耗动作
     */
    private suspend fun consumeOverflowStamina(slot: Int): Boolean {
        val trainBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.TRAIN)
        if (trainBtn == null) {
            logTactic(
                "ℹ️ 部队[$slot] 体力偏高，但当前界面未找到【练兵】语义入口（可能不在练武场/演武面板）。" +
                    "已如实记录待人工处理，不做盲点。"
            )
            return false
        }

        logTactic("🏃 部队[$slot] 派发【练兵】消耗体力: ${trainBtn.matchedText}")
        val clicked = EngineBridge.tap(trainBtn.safeTouchPoint.x, trainBtn.safeTouchPoint.y)
        if (!clicked) {
            logTactic("⚠️ 部队[$slot]【练兵】手势派发失败，体力未消耗。")
            return false
        }
        EngineBridge.humanDelay(600, 1000)

        val confirm = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
        if (confirm.ok) {
            logTactic("✅ 部队[$slot] 练兵指令已下发，体力溢出风险已解除。")
            return true
        }
        logTactic("⚠️ 部队[$slot] 练兵确认未点中（${confirm.detail}），请人工复核。")
        return false
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

        /** 税收熔断状态的持久化文件与键。 */
        const val PREFS_LOGISTICS = "stzb_logistics_state"
        const val KEY_TAX_DATE = "tax_date"
        const val KEY_TAX_COUNT = "tax_count"
        const val KEY_TAX_LAST_AT = "tax_last_at"

        /**
         * 会让征税**产生真实人民币代价**的关键词。
         *
         * 命中任意一个就放弃本轮，宁可少收一次税，也不动玩家的玉符/元宝。
         * 这是"免费税收完毕自动熔断防误点扣玉"的实际落地依据。
         */
        private val PAID_COST_KEYWORDS = listOf(
            "玉符", "玉符不足", "元宝", "充 值", "充值", "购买", "花费", "消耗玉"
        )
    }
}
