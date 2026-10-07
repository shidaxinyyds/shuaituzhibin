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
 * @deprecated 在 2026 年版本中，网易官方已原生支持连续出征；且大地图长途多格铺路易受透视漂移影响卡死。
 *             已被「离线战术定时管家 (ScheduledTaskManager)」与「单账号日常后勤全托管」平替，
 *             本类仅保留作为向前兼容。
 */
@Deprecated("官方已出连续出征，长途铺路属于易卡死的伪痛点，由离线战术管家与后勤托管平替")
class RoadPavingFlow(
    private val listener: TacticalState.TacticalEventListener? = null
) {

    private val isRunning = AtomicBoolean(false)

    data class PavingConfig(
        val targetTileList: List<PointF>, // 目标地块虚拟坐标序列 (由近及远)
        val candidateTroopSlots: List<Int> = listOf(1, 2, 3), // 参与轮班的部队槽位编号
        /**
         * 出征士气下限。
         *
         * 语义 = 知识库 `rules.minMoraleForPaving`（率土 100 / 三战 80），**调用方应显式传入**
         * （ScheduledTaskManager 与 AutoPilot 均已按知识库传值），这里的默认值只为兑现旧调用点。
         * 原先写在这一行的“满士气相比基准值能增伤百分之几”已删除：那是一条无处可证的
         * 数字，而它写在默认值旁边，很容易被当成依据反过来为默认值辩护。士气是否打折
         * 只看知识库的 `moraleStandard` 基准（见 TroopStatusDetector.gradeMorale）。
         */
        val minMoraleThreshold: Int = 100,
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
        var tileIndex = 0
        var tileAttempt = 0
        var stoppedByUser = false
        val totalTiles = config.targetTileList.size

        // 逐格步进基准：取自知识库（可热更），但只当**下限**用，实际间隔在其上叠随机抖动。
        //
        // 为什么不直接用这个值做固定 delay：两块地之间的间隔完全一致，是行为
        // 特征里最容易被提取的“周期性”信号（对时间戳做一阶差分就能看出来），比坐标偏差
        // 还好认。所以热更这个字段的语义是“改节奏基准”，不是“改成恒速”。
        //
        // 云端给了不合理的小值（0 / 负数）时按 800ms 兜底：零间隔连续猛点是
        // 最直接的机器特征，宁可慢也不能这样跑。
        val stepBaseMs = com.stzb.assistant.knowledge.KnowledgeBaseManager
            .activeProfile.tacticalDefaults.pavingStepIntervalMs.coerceAtLeast(800L)

        try {
            while (tileIndex < totalTiles) {
                if (!isRunning.get()) {
                    stoppedByUser = true
                    break
                }
                if (pavedCount >= config.maxPavingCount) {
                    logInfo("🎯 已达到本次规划最大铺路上限 (${config.maxPavingCount}块)，顺利结单。")
                    break
                }

                // 【本循环为什么不允许出现"跳过本格"的 continue】
                //
                // 率土铺路必须逐格相邻推进：第 N+1 块地的出征依托是第 N 块已经翻下来的地。
                // 一旦中间跳过一块，后面每一块都失去依托，而循环还会若无其事地跑完，
                // 日志上留下的只是一串"成功"。旧实现恰恰如此：体力不足时执行
                // `delay(3 * 60 * 1000L); continue`——continue 前进到了**下一格**，
                // 于是一边白耗时间，一边把整条计划静默打成不可能完成的碎片。
                //
                // 现在失败只有两种归宿：原地重试同一格，或者如实中止整轮。
                when (paveOneTile(tileIndex, totalTiles, config.targetTileList[tileIndex], config, stepBaseMs)) {
                    TileOutcome.OCCUPIED -> {
                        pavedCount++
                        tileIndex++
                        tileAttempt = 0
                    }

                    TileOutcome.OCCUPATION_UNCONFIRMED -> return abortPaving(
                        tileIndex, pavedCount,
                        "第 ${tileIndex + 1} 块地已出征但未能确认占领生效"
                    )

                    TileOutcome.NO_QUALIFIED_TROOP -> return abortPaving(
                        tileIndex, pavedCount,
                        "第 ${tileIndex + 1} 块地在等待上限内没有任何候选部队达到出征门槛"
                    )

                    TileOutcome.TRANSIENT_FAILURE -> {
                        if (!isRunning.get()) {
                            // 等待途中被用户终止：按"中止"处理，不能算成失败预算耗尽。
                            stoppedByUser = true
                            break
                        }
                        tileAttempt++
                        if (tileAttempt >= MAX_TILE_ATTEMPTS) {
                            return abortPaving(
                                tileIndex, pavedCount,
                                "第 ${tileIndex + 1} 块地连续 $tileAttempt 次无法推进"
                            )
                        }
                        logWarn("⏪ 原地重试第 ${tileIndex + 1} 块地（第 ${tileAttempt + 1}/$MAX_TILE_ATTEMPTS 次）。铺路不许跳格，因此不前进。")
                    }
                }
            }

            if (stoppedByUser) {
                // 旧实现在用户终止后照样播报"任务圆满完成"，把中止伪装成成功。
                listener?.onStatusChanged(
                    TacticalState.TaskType.ROAD_PAVING,
                    TacticalState.Status.INTERRUPTED,
                    "铺路已按指令停止：本次确认占领 $pavedCount 块（原计划 $totalTiles 块，未完成）。"
                )
            } else {
                listener?.onStatusChanged(
                    TacticalState.TaskType.ROAD_PAVING,
                    TacticalState.Status.COMPLETED,
                    "铺路任务完成，共成功翻地铺设: $pavedCount 块"
                )
            }
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

    /** 单格推进的结果。由主循环决定“前进 / 原地重试 / 中止”，本函数自己不跳格。 */
    private enum class TileOutcome {
        /** 已确认占领生效——唯一允许前进的结果。 */
        OCCUPIED,

        /** 出征已发出但无法确认占领：不能拿“也许占了”当代替“确认占了”去铺下一格。 */
        OCCUPATION_UNCONFIRMED,

        /** 等待上限内无一支候选部队达标。 */
        NO_QUALIFIED_TROOP,

        /** 场景/面板/导航等瞬时故障：可重试，仍针对**同一格**。 */
        TRANSIENT_FAILURE
    }

    /**
     * [awaitQualifiedTroop] 的结果。
     * 不用 sealed class 而用"枚举 + 可空槽位"：这四个分支的差别在于
     * **接下来该做什么**（前进 / 重进本格 / 中止），而不在于携带的数据。
     */
    private enum class TroopWait {
        /** 等到了达标部队，槽位随结果一并返回。 */
        READY,

        /** 面板在等待期间被关掉了：需要重新进入本格，而不是接着等。 */
        PANEL_LOST,

        /** 总预算用尽仍未出现达标部队。 */
        NO_TROOP,

        /** 用户中途下达了终止指令。 */
        CANCELLED
    }

    /**
     * 如实中止本轮铺路：说清楚停在哪一格、已经真正确认了几块。
     *
     * 为什么宁可中止也不跳过：跳过一格后，后面的地块全部失去相邻依托，
     * 但循环会把它“跑完”并汇报成功——那是比失败更坏的结果（用户拿到的是
     * 一份带空洞的路）。返回值恒为 false，便于调用方写成 `return abortPaving(...)`。
     */
    private fun abortPaving(tileIndex: Int, pavedCount: Int, reason: String): Boolean {
        val detail = "⛔ 铺路中止：$reason。已确认占领 $pavedCount 块，停在第 ${tileIndex + 1} 块；" +
            "后续地块尚未铺设，本条计划**未完成**。"
        logWarn(detail)
        listener?.onStatusChanged(
            TacticalState.TaskType.ROAD_PAVING,
            TacticalState.Status.FAILED,
            detail
        )
        return false
    }

    /**
     * 推进**一块**地：对准镜头 → 呼出轮盘 → 进入选队面板 → 等一支达标部队 → 出征 → 确认占领。
     */
    private suspend fun paveOneTile(
        index: Int,
        total: Int,
        tileCoord: PointF,
        config: PavingConfig,
        stepBaseMs: Long
    ): TileOutcome {
        logTactic("📍 [第 ${index + 1}/$total 块] 正在锁定目标地块: (${tileCoord.x.toInt()}, ${tileCoord.y.toInt()})")

        // 1. 确保在大地图主场景
        val ready = WatchdogRecovery.recoverToMainMap()
        if (!ready) {
            logWarn("大地图场景自愈失败，稍后重试本格...")
            EngineBridge.humanDelay(1500, 2500)
            return TileOutcome.TRANSIENT_FAILURE
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
                return TileOutcome.TRANSIENT_FAILURE
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
            return TileOutcome.TRANSIENT_FAILURE
        }

        // 5. 【核心轮班算法】：面板内选队。**所有门槛均来自知识库**，不把数字写进话术：
        //    体力门槛 = staminaPerAction × 夜间倍率（三战夜战双倍），
        //    旧日志里把“体力>=20 且 士气>=100”刷成固定文案，夜间实际要 40 时，
        //    日志说的和判的不一样，看日志的人比看代码的人更容易错。
        val rules = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules
        val needStamina = rules.requiredStaminaNow()
        val nightTag = if (rules.isNightNow()) " × 夜间倍率 ${rules.nightStaminaMultiplier}" else ""
        logInfo(
            "🔎 本格出征门槛：体力 ≥ $needStamina（单次消耗 ${rules.staminaPerAction}$nightTag），" +
                "士气 ≥ ${config.minMoraleThreshold}。"
        )

        val (troopWait, troopSlot) = awaitQualifiedTroop(config.candidateTroopSlots, config.minMoraleThreshold)
        val selectedSlot: Int = when (troopWait) {
            TroopWait.READY -> troopSlot ?: return TileOutcome.TRANSIENT_FAILURE
            TroopWait.PANEL_LOST -> {
                logWarn("选队面板在等待期间被关闭，回到本格重新进入。")
                return TileOutcome.TRANSIENT_FAILURE
            }
            TroopWait.NO_TROOP -> return TileOutcome.NO_QUALIFIED_TROOP
            TroopWait.CANCELLED -> return TileOutcome.TRANSIENT_FAILURE
        }

        logInfo("⚡ 选中轮班部队: [部队 $selectedSlot]，已通过体力/士气门槛校验。")

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
        // 传入实际点击点（世界坐标导航生效时即镜头中心），让检测区域与目标一致。
        val occupied = waitForTileOccupation(PointF(tapX, tapY))
        if (!occupied) {
            // 原实现无论是否确认，都无条件打印"成功攻占第 N 块领地"，
            // 把超时/未生效一律写成成功，掩盖了真实故障。现在如实区分。
            logWarn(
                "⚠️ 等待 ${TILE_OCCUPY_TIMEOUT_MS / 1000} 秒仍未在目标地块附近检测到免战罩，" +
                    "无法确认本次占领是否生效。"
            )
            return TileOutcome.OCCUPATION_UNCONFIRMED
        }
        logTactic("✅ 已确认攻占本块领地（目标地块挂起免战罩）。")

        // 步进间隔：以知识库基准为下限叠加随机上界，保留拟人化拖动。
        EngineBridge.humanDelay(stepBaseMs, stepBaseMs + 1200L)
        return TileOutcome.OCCUPIED
    }

    /**
     * 在**已经打开的选队面板里**原地等待一支达标部队出现。
     *
     * 为什么必须等而不是跳过：铺路的相邻性要求第 N 块一定被铺下来（见主循环说明）。
     * 旧实现是 `WatchdogRecovery.recoverToMainMap(); delay(3 * 60 * 1000L); continue`，
     * 而 continue 在逐格循环里会**前进到下一格**：既白耗 3 分钟，又静默把计划打成碎片。
     *
     * 节奏说明：这里的轮询间隔只是“每隔一会儿回面板复查一次”的操作频率，
     * 总时长靠 [TROOP_WAIT_BUDGET_MS] 封顶。刻意**不**按“还差几点体力 ×
     * 每点回复时长”折算等待时间——体力回复速率无可靠来源，把它算进调度
     * 等于拿一条无依据的假设当依据；而每次复查都重新读 OCR，实际回复快慢由画面结果说话。
     */
    private suspend fun awaitQualifiedTroop(candidateSlots: List<Int>, minMorale: Int): Pair<TroopWait, Int?> {
        val deadline = System.currentTimeMillis() + TROOP_WAIT_BUDGET_MS
        var firstSweep = true

        while (isRunning.get()) {
            // 面板还在吗？第一趟不必探：能走到这里说明 clickAndExpect 刚刚确认过。
            if (!firstSweep && !EngineBridge.waitForState(
                    StzbUiMatcher.GameState.TROOP_DISPATCH_DIALOG,
                    PANEL_ALIVE_PROBE_MS
                )
            ) {
                return TroopWait.PANEL_LOST to null
            }
            firstSweep = false

            selectOptimalTroop(candidateSlots, minMorale)?.let { return TroopWait.READY to it }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                logWarn(
                    "⌛ 已原地等待 ${TROOP_WAIT_BUDGET_MS / 60000} 分钟，仍无部队达到出征门槛" +
                        "（这是等待上限，不代表体力回复速率）。"
                )
                return TroopWait.NO_TROOP to null
            }

            val napMs = minOf(remaining, TROOP_POLL_MIN_MS)
            listener?.onStatusChanged(
                TacticalState.TaskType.ROAD_PAVING,
                TacticalState.Status.WAITING_COUNTDOWN,
                "本格候选部队均未达标，原地等待约 ${napMs / 1000} 秒后复查（预算剩 ${remaining / 1000} 秒）"
            )
            EngineBridge.humanDelay(napMs, minOf(remaining, TROOP_POLL_MAX_MS))
        }

        return TroopWait.CANCELLED to null
    }

    /**
     * 智能选队与 2026 士气/体力评估
     *
     * 资格判据只有一条：体力 >= 知识库需求、士气 >= 铺路下限。本函数**不会**因为
     * 优选而放宽它，也不会在原本有人可选时改成无人可选——它只在**已达标**的队里
     * 优先拿士气不打折的那一支（[TroopStatusDetector.MoraleGrade.LOW_PENALTY]）。
     *
     * 为什么要这一层优选：铺路是长时间连续作业，拿一支士气低于基准（战力打折）
     * 的队去反复接战，代价是整轮的额外战损；而按旧实现“遇到第一个达标的就走”，
     * 1 号队只要达标就永远是 1 号队，后面满士气的那支根本没机会被选中。
     * 士气档位由知识库（maxMorale / moraleStandard）决定，因此换游戏不会错位。
     */
    private suspend fun selectOptimalTroop(candidateSlots: List<Int>, minMorale: Int): Int? {
        // 需求体力不随槽位变化，提到循环外：既少算一次，也避免循环内不同轮次
        // 跨过夜间窗口边界时，前后两个槽位用两套门槛互相比。
        val rules = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules
        val needStamina = rules.requiredStaminaNow()
        val nightTag =
            if (rules.isNightNow()) "，当前为夜间窗口，倍率 ${rules.nightStaminaMultiplier}x" else ""

        // 第一个“达标但士气打折”的队：只有在找遍所有候选都没找到更好的队时，才用它。
        var lowMoraleFallback: Int? = null

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
                if (stamina >= needStamina && morale >= minMorale) {
                    if (detail.moraleGrade != TroopStatusDetector.MoraleGrade.LOW_PENALTY) {
                        logInfo("✅ 选中部队[$slot]：体力=$stamina, 士气=$morale（${detail.moraleGrade}），战力未打折。")
                        return slot
                    }
                    // 达标但士气低于基准：不立刻用（否则后面的满士气队没机会），先记为兜底。
                    if (lowMoraleFallback == null) {
                        lowMoraleFallback = slot
                        logWarn(
                            "部队[$slot] 已达标但士气打折（$morale < 基准 ${rules.moraleStandard}），" +
                                "先继续找士气正常的队，没有再回来用它。"
                        )
                    }
                } else {
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

        // 找遍候选没有士气正常的队：仍然可以用那支达标的（士气打折 ≠ 不能出征），
        // 不能因为“找不到最优”就谎报“无人可用”而把本轮铺路整段停下来。
        return lowMoraleFallback
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
        val half = TILE_IMMUNITY_HALF_SPAN
        val area = Rect(
            (targetCoord.x - half).toInt().coerceAtLeast(0),
            (targetCoord.y - half).toInt().coerceAtLeast(0),
            (targetCoord.x + half).toInt(),
            (targetCoord.y + half).toInt()
        )

        val startWait = System.currentTimeMillis()
        while (isRunning.get() && (System.currentTimeMillis() - startWait < TILE_OCCUPY_TIMEOUT_MS)) {
            val immunity = EngineBridge.detectTileImmunity(area)
            if (immunity.isImmune) {
                logInfo("🛡️ 目标地块附近出现金色免战罩，确认占领生效。")
                return true
            }
            if (!isRunning.get()) break
            delay(TILE_OCCUPY_POLL_MS)
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

        /** 同一格最多推进尝试次数。用尽仍无法推进就如实中止整轮，绝不跳格。 */
        private const val MAX_TILE_ATTEMPTS = 3

        /**
         * 在选队面板里等待达标部队出现的**总预算**。
         *
         * 它的语义是“最多等多久”，不是对体力回复速率的断言（回复速率无可靠来源，
         * 因此也不拿它去折算“还差几点 × 每点时长”）。到点仍无达标队则如实中止，
         * 而不是跳过本格。
         */
        private const val TROOP_WAIT_BUDGET_MS = 15 * 60 * 1000L

        /** 复查节奏的下界/上界：面板里“隔一会儿再看一眼”的操作频率。 */
        private const val TROOP_POLL_MIN_MS = 45 * 1000L
        private const val TROOP_POLL_MAX_MS = 90 * 1000L

        /** 每轮复查前先确认选队面板还在（用户可能手动把它关了）。 */
        private const val PANEL_ALIVE_PROBE_MS = 800L

        /**
         * 等待金色免战罩出现的超时与轮询间隔。
         *
         * ⚠️ 待校准：行军触敌时长取决于距离、队伍速度与目标等级，这两个值是按
         * “近邻铺路常见耗时”取中的经验值，不是从游戏数据里来的。它们只影响
         * “多久之后承认自己无法确认”：到点只会不计成功并中止本轮，不会谎报成功。
         */
        private const val TILE_OCCUPY_TIMEOUT_MS = 90 * 1000L
        private const val TILE_OCCUPY_POLL_MS = 3000L

        /** 免战罩检测窗口半径（以本次实际点击点为中心）。 */
        private const val TILE_IMMUNITY_HALF_SPAN = 70
    }
}
