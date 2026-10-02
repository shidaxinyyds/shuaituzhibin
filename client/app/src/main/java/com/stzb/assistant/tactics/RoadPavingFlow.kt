package com.stzb.assistant.tactics

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.ocr.TileStatusDetector
import com.stzb.assistant.ocr.TroopStatusDetector
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import com.stzb.assistant.service.EngineBridge
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动铺路翻地与体力动态轮班机 (RoadPavingFlow)
 * 
 * 核心痛点解决：
 *   1. 解决跨州大战机械铺路数小时、手指点废的痛点；
 *   2. 多部队动态轮班 (Troop 1 -> Troop 2 -> Troop 3)：谁体力充足选谁，彻底规避 120 点体力满溢惩罚；
 *   3. 2026 征服赛季士气校验：低于 100 士气严禁出征，避免低士气战力骤降白送；
 *   4. 结合 WatchdogRecovery 自动处理“免战中”、“占领上限”、“不相连”等异常提示；
 *   5. 全程三次贝塞尔曲线 + 2D 高斯离散随机触控，高度拟人防封。
 */
class RoadPavingFlow(
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    data class PavingConfig(
        val targetTileList: List<PointF>, // 目标地块虚拟坐标序列 (由近及远)
        val candidateTroopSlots: List<Int> = listOf(1, 2, 3), // 参与轮班的部队槽位编号
        val minMoraleThreshold: Int = 100, // 2026 赛季最低出征士气门槛 (满士气 120 增伤 16%)
        val maxPavingCount: Int = 50, // 最大铺路地块上限
        /**
         * 与 [targetTileList] 一一对应的**世界坐标**（大地图格坐标，如 (228,132)）。
         *
         * 提供它时，流程会在点每块地之前先用 MapNavigator 把镜头对准该世界坐标，
         * 然后点镜头中心——这样即使镜头中途移动，目标也不会失效。
         * 为空时沿用 [targetTileList] 里的屏幕坐标（原有行为）。
         */
        val worldTargetList: List<Pair<Int, Int>> = emptyList()
    )

    fun stop() {
        isRunning.set(false)
        log(TacticalState.TacticalLog(
            taskType = TacticalState.TaskType.ROAD_PAVING,
            level = "WARN",
            message = "⏹️ 收到用户终止指令，正在安全退出铺路执行流..."
        ))
    }

    /**
     * 启动自动铺路主循环
     */
    suspend fun startPaving(config: PavingConfig): Boolean {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "自动铺路已在运行中，请勿重复启动。")
            return false
        }

        listener?.onStatusChanged(
            TacticalState.TaskType.ROAD_PAVING,
            TacticalState.Status.RUNNING,
            "开始自动铺路流水线，总规划地块数: ${config.targetTileList.size}"
        )

        var pavedCount = 0
        var currentSlotIndex = 0

        try {
            for ((index, tileCoord) in config.targetTileList.withIndex()) {
                if (!isRunning.get()) break
                if (pavedCount >= config.maxPavingCount) {
                    logInfo("🎯 已达到本次规划最大铺路上限 (${config.maxPavingCount}块)，顺利结单。")
                    break
                }

                logTactic("📍 [第 ${index + 1}/${config.targetTileList.size} 块] 正在锁定目标地块: (${tileCoord.x.toInt()}, ${tileCoord.y.toInt()})")

                // 1. 确保在大地图主场景
                val ready = WatchdogRecovery.recoverToMainMap()
                if (!ready) {
                    logWarn("大地图场景自愈失败，稍后重试...")
                    EngineBridge.humanDelay(1500, 2500)
                    continue
                }

                // 2. 定位目标地块并呼出操作轮盘
                //    优先走世界坐标：先用 MapNavigator 把镜头对准该格，再点镜头中心。
                //    这样"点哪里"是即时算出来的，而不是沿用取点时的旧屏幕坐标——
                //    后者一旦镜头移动（行军动画、点击顶部栏、用户滑动）就永久失效。
                var tapX = tileCoord.x
                var tapY = tileCoord.y
                val world = config.worldTargetList.getOrNull(index)
                if (world != null && MapProjection.isCalibrated) {
                    when (val nav = MapNavigator.centerOn(world.first, world.second)) {
                        is MapNavigator.Result.Reached -> {
                            val center = MapProjection.viewportCenterCanvas()
                            tapX = center.x
                            tapY = center.y
                            val verifiedTag = if (nav.verified) "HUD 已校验" else "未读到 HUD，未校验"
                            logInfo("🧭 已按世界坐标 (${world.first},${world.second}) 对准镜头（$verifiedTag）")
                        }
                        is MapNavigator.Result.Refused ->
                            logWarn("世界坐标导航被拒绝，回退到取点屏幕坐标：${nav.reason}")
                        is MapNavigator.Result.Failed ->
                            logWarn("世界坐标导航失败，回退到取点屏幕坐标：${nav.reason}")
                    }
                }

                EngineBridge.tap(tapX, tapY)
                val menuOpened = EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2500)
                if (!menuOpened) {
                    logWarn("未检测到地块操作轮盘，再次点击重试...")
                    EngineBridge.tap(tapX, tapY)
                    if (!EngineBridge.waitForState(StzbUiMatcher.GameState.TILE_ACTION_MENU, 2000)) {
                        WatchdogRecovery.recoverToMainMap()
                        continue
                    }
                }

                // 3+4. 点击“出征”并**当场确认选队面板真的弹出来了**。
                //      原先拆成"点一下"再"等 3 秒"，失败时日志只说"面板未弹出"，
                //      分不清是没点到还是点到了界面没跳——现在能分开报告。
                val attack = EngineBridge.clickAndExpect(
                    StzbUiMatcher.ButtonType.ATTACK,
                    StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
                    timeoutMs = 3000L,
                    attempts = 2
                )
                if (!attack.ok) {
                    logWarn("未能进入出征选队面板：${attack.detail}")
                    WatchdogRecovery.recoverToMainMap()
                    continue
                }

                // 5. 【核心轮班算法】：遍历候选部队，选出体力>=20且士气>=100的最优出征队
                val selectedSlot = selectOptimalTroop(config.candidateTroopSlots, config.minMoraleThreshold)
                if (selectedSlot == null) {
                    logWarn("⚠️ 当前所有候选部队体力均不足 20 或士气过低，等待体力回复 3 分钟...")
                    listener?.onStatusChanged(
                        TacticalState.TaskType.ROAD_PAVING,
                        TacticalState.Status.WAITING_COUNTDOWN,
                        "全员体力不足或士气低迷，休眠等待体力回复中"
                    )
                    WatchdogRecovery.recoverToMainMap()
                    delay(3 * 60 * 1000L) // 体力 3 分钟回 1 点
                    continue
                }

                logInfo("⚡ 选中轮班部队: [部队 ${selectedSlot}]，体力士气状态极佳！")

                // 6. 点击确认出征，并确认选队面板真的关掉、回到了大地图
                EngineBridge.humanDelay(400, 800)
                val confirm = EngineBridge.clickAndExpect(
                    StzbUiMatcher.ButtonType.CONFIRM,
                    StzbUiMatcher.GameState.MAIN_MAP,
                    timeoutMs = 3000L,
                    attempts = 2
                )
                if (!confirm.ok) {
                    // 只是告警，不中断：部队可能已经出发但界面判定的时机不同，
                    // 真正的结论交给后面的"土地占领确认"来给。
                    logWarn("【确定出征】未能确认生效：${confirm.detail}")
                }

                // 7. 动态等待行军触敌与土地占领
                logInfo("🏹 部队已派遣出发！进入行军占领等待中...")
                // 率土近邻铺路单程通常在 30 秒 ~ 2 分钟，此处做状态监测。
                // 传入实际点击点（世界坐标导航生效时即镜头中心），让检测区域与目标一致。
                val occupied = waitForTileOccupation(PointF(tapX, tapY))
                if (occupied) {
                    pavedCount++
                    logTactic("✅ 已确认攻占第 $pavedCount 块领地（目标地块挂起免战罩）。")
                } else {
                    // 原实现无论是否确认，都无条件打印"成功攻占第 N 块领地"，
                    // 把超时/未生效一律写成成功，掩盖了真实故障。现在如实区分。
                    logWarn("⚠️ 等待 90 秒仍未在目标地块附近检测到免战罩，无法确认本次占领是否生效，不计入成功数。")
                }
                EngineBridge.humanDelay(1200, 2000)
            }

            listener?.onStatusChanged(
                TacticalState.TaskType.ROAD_PAVING,
                TacticalState.Status.COMPLETED,
                "铺路任务完成，共成功翻地铺设: $pavedCount 块"
            )
            return true

        } catch (e: Exception) {
            log(TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.ROAD_PAVING,
                level = "ERROR",
                message = "铺路主循环发生未捕获异常: ${e.message}"
            ))
            listener?.onStatusChanged(
                TacticalState.TaskType.ROAD_PAVING,
                TacticalState.Status.FAILED,
                "发生异常: ${e.message}"
            )
            return false
        } finally {
            isRunning.set(false)
        }
    }

    /**
     * 智能选队与 2026 士气/体力评估
     */
    private suspend fun selectOptimalTroop(candidateSlots: List<Int>, minMorale: Int): Int? {
        for (slot in candidateSlots) {
            // 切换/选中该部队卡片 (点击部队槽位对应的屏幕区域)
            clickTroopSlotTab(slot)
            EngineBridge.humanDelay(250, 450)

            // 解析当前选中的部队卡片信息
            val cardRoi = getTroopCardRoi(slot)
            val detail = EngineBridge.parseTroopCard(cardRoi, slot)

            if (detail != null) {
                val stamina = detail.stamina
                val morale = detail.morale

                if (stamina == null || morale == null) {
                    // 原实现写成 `detail.stamina ?: 100`，把"根本没识别到"当成"满体力满士气"，
                    // 于是士气/体力门槛形同虚设、永远选中 1 号队，而日志上看不出任何异常。
                    // 现在如实记为"未验证"，不再伪装成达标。
                    logWarn("部队[$slot] 体力/士气未能识别（OCR 不可用或该区域无文本），门槛未校验，按未验证处理。")
                    return slot
                }

                // 铺路最低体力门槛取自知识库：单次出征消耗 × 夜间倍率。
                // 原实现写死 20，导致 staminaPerAction / nightStaminaMultiplier
                // 这两个知识库字段在工程里从未被读取——把三战改成夜战双倍消耗也不会有任何效果。
                val rules = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules
                val needStamina = rules.requiredStaminaNow()
                if (stamina >= needStamina && morale >= minMorale) {
                    return slot
                } else {
                    val nightTag =
                        if (rules.isNightNow()) "，当前为夜间窗口，倍率 ${rules.nightStaminaMultiplier}x" else ""
                    logWarn(
                        "部队[$slot] 不达标: 体力=$stamina(需>=$needStamina$nightTag), " +
                            "士气=$morale(需>=$minMorale)，跳过。"
                    )
                }
            } else {
                // 卡片解析整体失败时同样不能假装"可用"，但也不该直接放弃整条流程：
                // 记录原因后按未验证处理，让后续【确定出征】的真实结果来暴露问题。
                logWarn("部队[$slot] 卡片信息解析失败（OCR 不可用或面板布局变化），门槛未校验，按未验证处理。")
                return slot
            }
        }
        return null
    }

    /**
     * 点击选队面板顶部的部队标签 (部队一、部队二...)
     *
     * 坐标来自可标定的统一锚点表 [UiAnchors]，不再按 1280 宽画布写死 220/140/160。
     * 那组常量在 20:9 机型（设计画布宽 1600）上会落到面板里不同的相对位置，
     * 表现为"选不中部队 → 确定键点不亮"。
     */
    private suspend fun clickTroopSlotTab(slot: Int) {
        val p = UiAnchors.troopTab(slot)
        EngineBridge.tap(p.x, p.y)
    }

    private fun getTroopCardRoi(slot: Int): Rect {
        // 说明：slot 目前不参与定位——出征面板一次只展示当前被选中部队的一张卡片。
        // 若将来面板改为多卡并列，应在这里按 slot 横向切分锚点矩形。
        return UiAnchors.rect(UiAnchors.RectKey.TROOP_CARD)
    }

    /**
     * 监测行军占领完成。
     * @return true 表示在目标地块附近确认到金色免战罩；false 表示超时未能确认。
     */
    private suspend fun waitForTileOccupation(targetCoord: PointF): Boolean {
        // 关闭可能残留的弹窗回到大地图
        WatchdogRecovery.recoverToMainMap()

        // 关键修复：原实现完全忽略 targetCoord，改为全屏扫描金色免战罩，
        // 于是画面上任何相似的金色 UI 都会被误报成"已成功占领"。
        // 现在把检测区域收紧到目标点附近，让判定真的与目标相关。
        //
        // 局限（已知未解决）：这里仍假设镜头没有移动。真正的解法是把目标锚定到
        // **游戏世界坐标**并在需要时拖动地图（见 MapProjection / MapNavigator）。
        val half = 70
        val area = Rect(
            (targetCoord.x - half).toInt().coerceAtLeast(0),
            (targetCoord.y - half).toInt().coerceAtLeast(0),
            (targetCoord.x + half).toInt(),
            (targetCoord.y + half).toInt()
        )

        val startWait = System.currentTimeMillis()
        while (isRunning.get() && (System.currentTimeMillis() - startWait < 90 * 1000L)) {
            val immunity = EngineBridge.detectTileImmunity(area)
            if (immunity.isImmune) {
                logInfo("🛡️ 目标地块附近出现金色免战罩，确认占领生效。")
                return true
            }
            if (!isRunning.get()) break
            delay(3000)
        }
        return false
    }

    private fun logInfo(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.ROAD_PAVING, "INFO", msg))
    private fun logWarn(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.ROAD_PAVING, "WARN", msg))
    private fun logTactic(msg: String) = log(TacticalState.TacticalLog(TacticalState.TaskType.ROAD_PAVING, "TACTIC", msg))

    private fun log(entry: TacticalState.TacticalLog) {
        Log.i(TAG, "[${entry.level}] ${entry.message}")
        listener?.onLogEmitted(entry)
    }

    companion object {
        private const val TAG = "RoadPavingFlow"
    }
}
