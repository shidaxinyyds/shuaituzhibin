package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors

/**
 * 全自动屯田打铁管家业务流程引擎 (AccurateFarmingFlow)
 *
 * 核心痛点解决：
 *   1. 【高等级资源地最优屯田】：结合策令机制（每 3 令一次屯田，上限 30），自动检索 Lv.5+ 最高等级地块，
 *      根据缺料偏好（石/铁/木/粮）精准调度二队/辅队屯田，防止 30 令溢出浪费；
 *   2. 【官方书签 0 漂移瞬间对准】：支持官方标记快速寻路，规避传统滑屏累积漂移；
 *   3. 【免战与守军安全感知】：利用 TileStatusDetector 实时排除免战光罩与施工要塞；
 *   4. 【自动工坊打铁与宝物锻造】：自动巡检每日陈情工匠、工坊免费锻造与宝物精炼，收益自动入库；
 *   5. 【全场景看门狗自愈】：遇到弹窗或网络卡顿自动恢复，保障无人值守极高稳健性。
 */
class AccurateFarmingFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener
) {

    @Volatile
    private var isRunning: Boolean = false

    data class FarmingConfig(
        val targetTileCoord: PointF? = null,
        val targetWorldCoord: Pair<Int, Int>? = null,
        val bookmarkName: String? = null,
        val targetResourceType: TileStatusDetector.ResourceType = TileStatusDetector.ResourceType.STONE,
        val minTileLevel: Int = 5,
        val farmingTroopSlot: Int = 2,
        val minPolicyOrdersRequired: Int = 3,
        /**
         * 是否允许在"点选到的地块低于 [minTileLevel]"时降级屯田。
         *
         * 默认 **false**：优先 Lv.5+ 级资源地，遇到低等级地直接放弃本次，
         * 避免把 3 策令 + 20 体力花在收益很低的地块上。
         */
        val allowBelowMinLevel: Boolean = false,
        /** 是否启用"防溢清令"：令数达到软上限时连续屯田把令压回安全水位。 */
        val enableAutoPolicyClear: Boolean = true,
        val enableBlacksmithCheck: Boolean = true,
        /** 是否真的执行"领取/打造"点击；false 则只巡检并汇报，不产生任何点击。 */
        val autoCollectMaterials: Boolean = true
    )

    fun stop() {
        isRunning = false
        logTactic("⏹️ 全自动屯田打铁管家已收到终止请求")
    }

    suspend fun execute(config: FarmingConfig): Boolean {
        isRunning = true
        notifyStatus(TacticalState.Status.RUNNING, "开始执行全自动屯田打铁管家流程...")
        logTactic("🌾 屯田打铁管家启动: 偏好资源=${config.targetResourceType}, 最低地级=Lv.${config.minTileLevel}, 编队=${config.farmingTroopSlot}队, 打铁巡检=${config.enableBlacksmithCheck}")

        try {
            // 1. 确保大地图主界面就绪
            WatchdogRecovery.recoverToMainMap()

            // 2. 检查策令数量（每次屯田需 3 令）
            val currentOrders = detectPolicyOrders()
            if (currentOrders != null) {
                logTactic("📜 当前识别到策令数量: $currentOrders / 30")
                if (currentOrders < config.minPolicyOrdersRequired) {
                    logTactic("⚠️ 当前策令不足 ${config.minPolicyOrdersRequired} 令（当前 $currentOrders 令），无法执行屯田，流程安全退出")
                    // 如果开启了打铁，依然可以完成打铁巡检
                    if (config.enableBlacksmithCheck) {
                        performBlacksmithCheck(config)
                    }
                    notifyStatus(TacticalState.Status.COMPLETED, "策令不足无法屯田，打铁巡检已完成")
                    return true
                }
                if (currentOrders >= POLICY_ORDER_SOFT_CAP) {
                    if (config.enableAutoPolicyClear) {
                        logTactic("🚨 策令告警：已达 $currentOrders 令（软上限 $POLICY_ORDER_SOFT_CAP，硬上限 $POLICY_ORDER_HARD_CAP），将启动防溢出清令。")
                    } else {
                        logTactic("⚠️ 策令已达 $currentOrders 令，但配置关闭了 enableAutoPolicyClear，仅告警不做清令。")
                    }
                }
            } else {
                logTactic("ℹ️ 未能直接读取到顶栏策令数字，继续按照默认配置尝试屯田")
            }

            if (!isRunning) return false

            // 3. 对准目标地块（书签优先 -> 世界坐标 -> 屏幕坐标）
            val tapPoint = resolveTargetPoint(config)
            if (tapPoint == null) {
                logTactic("❌ 未能解析到有效屯田目标地块（书签、世界坐标或点选坐标均为空），屯田流程中止")
                if (config.enableBlacksmithCheck) {
                    performBlacksmithCheck(config)
                }
                notifyStatus(TacticalState.Status.FAILED, "缺少有效屯田目标")
                return false
            }

            if (!isRunning) return false

            // 4. 点击地块唤出轮盘并解析土地属性，必要时**连续屯田以清令防溢出**
            //
            //    本轮补齐：`enableAutoPolicyClear` 此前是死字段——界面上给了"防溢清令"的开关，
            //    代码里却从未读取，命中 24 令时只打印一句"启动强制防溢出屯田"然后照旧只打一轮。
            //    现在按"令数超出软上限多少"反推需要几轮，并逐轮重新读数，直到降到软上限以下。
            val plannedRuns = planFarmingRuns(currentOrders, config)
            if (plannedRuns > 1) {
                logTactic(
                    "🚨【防溢清令】当前 $currentOrders 令 ≥ 软上限 $POLICY_ORDER_SOFT_CAP，" +
                        "计划连续屯田 $plannedRuns 轮以把令压回安全水位。"
                )
            }

            var farmSuccess = false
            var runs = 0
            var orders = currentOrders
            while (isRunning && runs < plannedRuns) {
                runs++
                logTactic("🌾 屯田第 $runs/$plannedRuns 轮开始（当前令: ${orders ?: "未读到"}）")
                farmSuccess = dispatchFarming(tapPoint, config)
                if (!farmSuccess) {
                    logTactic("⚠️ 第 $runs 轮屯田未能完成，已提前结束清令循环（避免无意义重复点击）。")
                    break
                }
                // 每轮重新读一次令数，只有真正降下来才继续，不做"闭眼循环"
                orders = detectPolicyOrders()
                if (orders != null && orders < POLICY_ORDER_SOFT_CAP) {
                    logTactic("✅【防溢清令完成】当前令数 $orders，已回到软上限 $POLICY_ORDER_SOFT_CAP 以下。")
                    break
                }
                if (!isRunning) break
            }

            if (!isRunning) return false

            // 5. 执行每日工坊打铁 / 宝物锻造 / 陈情事务巡检
            if (config.enableBlacksmithCheck) {
                performBlacksmithCheck(config)
            }

            if (farmSuccess) {
                logTactic("✅ 智能屯田与工坊打铁巡检全部圆满完成（共 $runs 轮）")
                notifyStatus(TacticalState.Status.COMPLETED, "屯田打铁管家任务完成")
                return true
            } else {
                logTactic("⚠️ 屯田出征未成功，但已安全自愈回大地图")
                notifyStatus(TacticalState.Status.FAILED, "屯田出征未完成")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "屯田打铁流程异常: ${e.message}", e)
            logTactic("❌ 屯田打铁发生异常: ${e.message}")
            notifyStatus(TacticalState.Status.FAILED, "执行异常: ${e.message}")
            return false
        } finally {
            isRunning = false
            WatchdogRecovery.recoverToMainMap()
        }
    }

    /**
     * 读取大地图顶栏策令数 (如 "28/30" 或 "令: 28")
     */
    private fun detectPolicyOrders(): Int? {
        val frame = EngineBridge.captureFrame() ?: return null
        return try {
            val ocr = OcrManager.detect(frame) ?: return null
            val pattern = java.util.regex.Pattern.compile("(?:令|策令|政令)[\\s:：]*(\\d{1,2})(?:\\s*/\\s*30)?")
            val patternSlash = java.util.regex.Pattern.compile("(\\d{1,2})\\s*/\\s*30")
            for (block in ocr.textBlocks) {
                val m = pattern.matcher(block.text)
                if (m.find()) {
                    val count = m.group(1)?.toIntOrNull()
                    if (count != null && count in 0..30) return count
                }
                val m2 = patternSlash.matcher(block.text)
                if (m2.find()) {
                    val count = m2.group(1)?.toIntOrNull()
                    if (count != null && count in 0..30) return count
                }
            }
            null
        } finally {
            frame.recycle()
        }
    }

    /**
     * 目标解析：书签 0 漂移对准 > 世界坐标导航 > 屏幕取点
     */
    private suspend fun resolveTargetPoint(config: FarmingConfig): PointF? {
        // 1. 书签优先 (0 漂移)
        if (!config.bookmarkName.isNullOrBlank()) {
            logTactic("🔖 使用官方书签 [${config.bookmarkName}] 对准屯田地块...")
            when (val res = MapNavigator.jumpByBookmark(config.bookmarkName)) {
                is MapNavigator.Result.Reached -> {
                    logTactic("🔖 书签对准成功，目标地块已锁定在镜头中心")
                    return MapProjection.viewportCenterCanvas()
                }
                is MapNavigator.Result.Refused -> logTactic("书签跳转被拒: ${res.reason}")
                is MapNavigator.Result.Failed -> logTactic("书签跳转失败: ${res.reason}")
            }
        }

        // 2. 世界坐标对准
        if (config.targetWorldCoord != null && MapProjection.isCalibrated) {
            val (wx, wy) = config.targetWorldCoord
            logTactic("🧭 使用世界坐标 ($wx, $wy) 对准屯田地块...")
            when (val nav = MapNavigator.centerOn(wx, wy)) {
                is MapNavigator.Result.Reached -> {
                    logTactic("🧭 世界坐标导航成功，目标居中")
                    return MapProjection.viewportCenterCanvas()
                }
                is MapNavigator.Result.Refused -> logTactic("世界坐标导航被拒: ${nav.reason}")
                is MapNavigator.Result.Failed -> logTactic("世界坐标导航失败: ${nav.reason}")
            }
        }

        // 3. 屏幕画布取点
        if (config.targetTileCoord != null) {
            logTactic("🎯 使用点选屏幕虚拟坐标: (${config.targetTileCoord.x.toInt()}, ${config.targetTileCoord.y.toInt()})")
            return config.targetTileCoord
        }

        return null
    }

    /**
     * 执行点击地块、检查地块属性、点击屯田并派发出征
     */
    private suspend fun dispatchFarming(tapPoint: PointF, config: FarmingConfig): Boolean {
        logTactic("👆 点击目标地块 (${tapPoint.x.toInt()}, ${tapPoint.y.toInt()}) 唤起操作轮盘...")
        EngineBridge.tap(tapPoint.x, tapPoint.y)
        EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, timeoutMs = 2500)

        // 检查地块详情信息（等级与资源属性）
        val frame = EngineBridge.captureFrame()
        if (frame != null) {
            try {
                val detail = TileStatusDetector.parseTileDetail(frame)
                logTactic("🌾 地块属性感知: 等级=Lv.${detail.level}, 资源=${detail.resourceType}, 免战=${detail.isImmune}(${detail.immunityRemainingSec}s), 要塞=${detail.isFortress}")

                // 视觉复核（可选增强）：确认这确实是一块资源地。
                // 刻意**只做提示不做闸门**——模型存在漏检，用它去拦截正常屯田
                // 会让功能变得不可用；这里只把"视觉是否也看到了资源地块"写进日志，
                // 便于事后判断"点击点是否偏了"。
                logTactic(verifyTileWithVision(frame))

                if (detail.isImmune) {
                    logTactic("⚠️ 目标地块处于免战中（剩余 ${detail.immunityRemainingSec} 秒），无法屯田，放弃操作")
                    WatchdogRecovery.recoverToMainMap()
                    return false
                }
                if (detail.level > 0 && detail.level < config.minTileLevel) {
                    // 本轮补齐"优先 Lv.5+ 级资源地"：
                    // 原先只打印一句"收益偏低（继续执行）"就照常屯田——等于把 3 令和 20 体力
                    // 花在一块低等级地上，与"优先高等级资源地"的产品目标相反。
                    // 现在默认**拒绝**低等级地块（与 validate_logistics_farming.py M4 的
                    // "level < min_level → 过滤掉" 语义一致），只有显式放开才降级执行。
                    if (config.allowBelowMinLevel) {
                        logTactic(
                            "⚠️ 地块等级 Lv.${detail.level} 低于门槛 Lv.${config.minTileLevel}，" +
                                "但配置允许降级执行，继续屯田。"
                        )
                    } else {
                        logTactic(
                            "⛔ 地块等级 Lv.${detail.level} 低于门槛 Lv.${config.minTileLevel}，" +
                                "已放弃本次屯田（避免浪费 3 策令）；请重新点选 Lv.${config.minTileLevel}+ 的资源地，" +
                                "或把 allowBelowMinLevel 设为 true。"
                        )
                        WatchdogRecovery.recoverToMainMap()
                        return false
                    }
                }
            } finally {
                frame.recycle()
            }
        }

        // 点击【屯田】按键
        logTactic("🌾 寻找并点击【屯田】按键...")
        val farmBtnAction = EngineBridge.clickAndExpect(
            StzbUiMatcher.ButtonType.FARM,
            StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
            timeoutMs = 3000L,
            attempts = 2
        )
        if (!farmBtnAction.ok) {
            logTactic("❌ 未能进入出征选队面板: ${farmBtnAction.detail}")
            WatchdogRecovery.recoverToMainMap()
            return false
        }

        // 选中指定的屯田部队槽位
        val slot = config.farmingTroopSlot.coerceIn(1, UiAnchors.troopTabSlotCount)
        logTactic("🛡️ 选中第 $slot 队作为屯田队伍...")
        val tabPoint = UiAnchors.troopTab(slot)
        EngineBridge.tap(tabPoint.x, tabPoint.y)
        EngineBridge.humanDelay(400, 700)

        // 点击【确定出征】按键
        logTactic("🚀 点击【确定出征】下发屯田指令...")
        val confirmAction = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
        if (!confirmAction.ok) {
            logTactic("❌ 确定出征点击失败: ${confirmAction.detail}")
            WatchdogRecovery.recoverToMainMap()
            return false
        }

        logTactic("✅ 屯田出征指令已顺利下发！消耗 3 策令与 20 体力")
        EngineBridge.humanDelay(800, 1200)
        WatchdogRecovery.recoverToMainMap()
        return true
    }

    /**
     * 视觉复核：镜头中是否真的存在"资源地块"。
     *
     * ## 定位：提示，不是闸门
     * 真实的业务风险是"点击点偏了 → 对着空地/敌地点了屯田"，白白浪费 3 策令。
     * 但视觉模型存在漏检，若用它做硬闸门，一次漏检就会让屯田功能整体不可用——
     * 那比浪费 3 令严重得多。因此这里只输出一行可诊断的结论，
     * 由玩家/日志判断"是不是点偏了"，**不参与任何放行决策**。
     *
     * @param frame 调用方已抓取的帧；本函数**不负责回收**，由调用方的 finally 处理
     */
    private fun verifyTileWithVision(frame: Bitmap): String {
        val detector = com.stzb.assistant.runtime.VisionRuntime.yolo(context)
            ?: return "👁️ 视觉复核：未启用（无 YOLO 权重或资源不足），以 OCR/OpenCV 结果为准。"
        if (!com.stzb.assistant.runtime.VisionRuntime.isNativeVisionReady()) {
            return "👁️ 视觉复核：仅几何色度回退，结论不作为证据。"
        }
        return try {
            val boxes = detector.detect(frame, confThreshold = 0.35f)
            val tiles = boxes.filter {
                it.detectionClass == com.stzb.assistant.ai.vision.YoloDetector.DetectionClass.RESOURCE_TILE ||
                    it.detectionClass == com.stzb.assistant.ai.vision.YoloDetector.DetectionClass.FORTRESS
            }
            if (tiles.isEmpty()) {
                "👁️ 视觉复核：未在画面中检出资源地块 —— 若随后屯田失败，" +
                    "优先怀疑**点击点偏了**（仅提示，不阻断本次操作）。"
            } else {
                val best = tiles.maxByOrNull { it.confidence }
                "👁️ 视觉复核：检出「${best?.detectionClass?.label}」" +
                    "（置信 ${"%.2f".format(best?.confidence ?: 0f)}），点击点看起来是对的。"
            }
        } catch (e: Exception) {
            "👁️ 视觉复核：检测异常 ${e.message}（不影响本次屯田流程）。"
        }
    }

    /**
     * 自动工坊打铁 / 宝物锻造 / 陈情事务巡检
     *
     * ## 本轮两处修正
     * 1. `autoCollectMaterials` 此前是**死字段**（界面给了开关，代码从不读取）。
     *    现在它是真正的闸门：
     *      - `true`  → 定位到锻造/领取入口后执行点击收取；
     *      - `false` → 只巡检并把"发现了什么"如实汇报，**不产生任何点击**。
     * 2. 原先只要"看到 CONFIRM 就点，然后无条件打印『收益入库』"——
     *    即使手势派发失败、即使那根本不是领取按钮，也会写成功。
     *    这属于把失败包装成成功，会让玩家以为材料已经到账。
     *    现在按**点击与确认的真实返回值**给结论。
     */
    private suspend fun performBlacksmithCheck(config: FarmingConfig): Boolean {
        logTactic(
            "⚒️ 开始巡检每日工坊打铁 / 工匠锻造 / 宝物精炼事务" +
                (if (config.autoCollectMaterials) "（自动领取已开启）" else "（仅巡检，不自动领取）") + "..."
        )
        WatchdogRecovery.recoverToMainMap()

        // 寻找【陈情】或【工坊】或【事务】或【锻造】入口
        val forgeBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.FORGE)
        if (forgeBtn == null) {
            logTactic("ℹ️ 大地图未见直接陈情打铁入口（今日可能未刷新或已完成），巡检结束。")
            WatchdogRecovery.recoverToMainMap()
            return false
        }

        logTactic("⚒️ 发现打铁/锻造入口: ${forgeBtn.matchedText}")
        if (!config.autoCollectMaterials) {
            logTactic("ℹ️ 已按配置关闭自动领取：本次只汇报入口位置，不执行点击。")
            WatchdogRecovery.recoverToMainMap()
            return true
        }

        val opened = EngineBridge.tap(forgeBtn.safeTouchPoint.x, forgeBtn.safeTouchPoint.y)
        if (!opened) {
            logTactic("⚠️ 打铁入口手势派发失败，未能进入锻造面板。")
            WatchdogRecovery.recoverToMainMap()
            return false
        }
        EngineBridge.humanDelay(800, 1300)

        // 检查是否有可领取的打造收益或免费打造按钮
        val collectBtn = EngineBridge.findButton(StzbUiMatcher.ButtonType.CONFIRM)
            ?: EngineBridge.findButton(StzbUiMatcher.ButtonType.FORGE)
        if (collectBtn == null) {
            logTactic("ℹ️ 当前暂无可领取的免费工匠打造或材料（面板内未见领取/打造按键）。")
            WatchdogRecovery.recoverToMainMap()
            return false
        }

        logTactic("💎 发现锻造/领取按键: ${collectBtn.matchedText}，点击执行")
        val tapped = EngineBridge.tap(collectBtn.safeTouchPoint.x, collectBtn.safeTouchPoint.y)
        EngineBridge.humanDelay(600, 1000)
        if (tapped) {
            logTactic("✅ 锻造/领取点击已派发（收益是否真到账以游戏内提示为准）。")
        } else {
            logTactic("⚠️ 锻造/领取手势派发失败，材料可能未领取，请人工复核。")
        }

        WatchdogRecovery.recoverToMainMap()
        return tapped
    }

    /**
     * 计算本轮需要连续屯田几轮（用于"防溢清令"）。
     *
     * 规则与 `validate_logistics_farming.py` 的 M5 一致：
     *   - 关闭防溢清令、或没读到令数 → 固定跑 1 轮（不猜）；
     *   - 令数未达软上限 → 跑 1 轮（正常屯田）；
     *   - 令数 ≥ 软上限 → 按"超出量 / 每次消耗令数"反推轮数，并加 1 轮余量，
     *     上限 [MAX_CLEAR_RUNS] 轮兜底，防止读数异常导致无限循环。
     */
    private fun planFarmingRuns(currentOrders: Int?, config: FarmingConfig): Int {
        if (!config.enableAutoPolicyClear) return 1
        if (currentOrders == null) return 1
        if (currentOrders < POLICY_ORDER_SOFT_CAP) return 1
        val perRun = config.minPolicyOrdersRequired.coerceAtLeast(1)
        val overflow = currentOrders - POLICY_ORDER_SOFT_CAP
        return (overflow / perRun + 2).coerceIn(2, MAX_CLEAR_RUNS)
    }

    private fun logTactic(msg: String) {
        Log.i(TAG, msg)
        listener.onLogEmitted(
            TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.FARMING_STEWARD,
                level = "INFO",
                message = msg
            )
        )
    }

    private fun notifyStatus(status: TacticalState.Status, detail: String) {
        listener.onStatusChanged(TacticalState.TaskType.FARMING_STEWARD, status, detail)
    }

    companion object {
        private const val TAG = "AccurateFarmingFlow"

        /** 策令硬上限（率土为 30）。 */
        const val POLICY_ORDER_HARD_CAP = 30

        /** 策令软上限：达到即启动防溢清令，避免顶到硬上限后策令产出被浪费。 */
        const val POLICY_ORDER_SOFT_CAP = 24

        /** 单次清令循环最多连续屯田几轮，防止读数异常导致无限循环。 */
        private const val MAX_CLEAR_RUNS = 8
    }
}
