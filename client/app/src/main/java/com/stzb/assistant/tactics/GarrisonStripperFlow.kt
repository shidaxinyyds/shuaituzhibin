package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.stzb.assistant.antiban.TimingFingerprintEngine
import com.stzb.assistant.ai.rag.SlgRagEngine
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import kotlinx.coroutines.delay

/**
 * PVP 驻守剥皮透视与阵容反打智能流 (GarrisonStripperFlow)
 *
 * 核心痛点与价值：
 *   1. 【100兵斯巴达无损撞驻守】：自动向敌方要塞/主城/红地派出 100 兵轻骑兵撞击驻守；
 *   2. 【0.2s 极速战报感知】：战斗结束后自动触发战报抓取与 RapidOCR 文本提取；
 *   3. 【敌方首队武将与流派秒级透析】：识破敌方驻守第一队核心武将（大营/中军/前锋）与流派；
 *   4. 【RAG 智能阵容克制矩阵】：匹配 SlgRagEngine 与 PVP 战法克制库，
 *      生成“克制推荐阵容”与“严禁白给阵容”；
 *   5. 【悬浮 HUD 卡片弹出】：毫秒级推送到浮窗卡片，为指挥官与玩家抢占敌方换防前黄金窗口期；
 *   6. 【可选自动化反击】：支持直接拉起我方对应克制主力秒接出征。
 */
class GarrisonStripperFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener
) {

    @Volatile
    private var isRunning: Boolean = false

    data class GarrisonConfig(
        val targetTileCoord: PointF? = null,
        val targetWorldCoord: Pair<Int, Int>? = null,
        val bookmarkName: String? = null,
        val spartanTroopSlot: Int = 5,
        val autoDispatchCounter: Boolean = false,
        val counterTroopSlot: Int = 1,
        val maxWaitBattleReportSec: Int = 30
    )

    data class GarrisonAnalysisResult(
        val detectedHeroes: List<String>,
        val archetype: String,
        val recommendedCounters: List<String>,
        val forbiddenSquads: List<String>,
        val rawAdvice: String,
        val summaryText: String
    )

    fun stop() {
        isRunning = false
        logTactic("⏹️ PVP 驻守剥皮透视已收到停止请求")
    }

    suspend fun execute(config: GarrisonConfig): GarrisonAnalysisResult? {
        isRunning = true
        notifyStatus(TacticalState.Status.RUNNING, "开始执行 PVP 驻守剥皮透视...")
        logTactic("⚔️ PVP 驻守剥皮启动: 斯巴达=${config.spartanTroopSlot}队, 自动反打=${config.autoDispatchCounter}")

        try {
            // 1. 回到主界面
            WatchdogRecovery.recoverToMainMap()

            // 2. 对准目标地块
            val tapPoint = resolveTargetPoint(config)
            if (tapPoint == null) {
                logTactic("❌ 未能解析到有效目标坐标或书签，流程中止")
                notifyStatus(TacticalState.Status.FAILED, "缺少有效目标地块")
                return null
            }

            if (!isRunning) return null

            // 3. 点击地块呼出出征菜单
            logTactic("🎯 点击目标地块 (${tapPoint.x.toInt()}, ${tapPoint.y.toInt()}) 唤起轮盘...")
            if (!EngineBridge.tap(tapPoint.x, tapPoint.y)) {
                logTactic("❌ 点击目标地块失败")
                notifyStatus(TacticalState.Status.FAILED, "点击目标失败")
                return null
            }
            EngineBridge.humanDelay(600, 1000)

            // 4. 点击「出征」按钮
            val attackOutcome = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.ATTACK)
            if (!attackOutcome.clicked) {
                logTactic("❌ 未找到或未能点击「出征」按钮，流程中止")
                notifyStatus(TacticalState.Status.FAILED, "未找到出征按钮")
                return null
            }
            EngineBridge.humanDelay(800, 1400)

            // 5. 选中斯巴达探路部队
            logTactic("🐴 选中第 ${config.spartanTroopSlot} 队斯巴达探路骑兵...")
            if (!selectTroopSlot(config.spartanTroopSlot)) {
                logTactic("⚠️ 选择槽位 ${config.spartanTroopSlot} 失败，尝试默认槽位")
            }
            EngineBridge.humanDelay(500, 900)

            // 6. 点击确认出征
            logTactic("🚀 派出斯巴达探路骑兵撞击敌方驻守...")
            val marchOutcome = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
            if (!marchOutcome.clicked) {
                // 部分版本为 MARCH 或直接确认
                EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.MARCH)
            }
            EngineBridge.humanDelay(800, 1200)

            // 7. 等待战报生成并打开战报
            notifyStatus(TacticalState.Status.WAITING_COUNTDOWN, "斯巴达行军碰撞中，等待战报生成...")
            val reportOpened = waitForBattleReport(config.maxWaitBattleReportSec)
            if (!reportOpened) {
                logTactic("⚠️ 未能在 ${config.maxWaitBattleReportSec} 秒内捕获新战报红点，尝试从战报列表巡检")
            }

            if (!isRunning) return null

            // 8. 抓取战报画面并 OCR 解析敌方守将与阵容
            logTactic("📜 正在分析碰撞战报，提取敌方驻守第一队武将与流派...")
            val result = analyzeLatestBattleReport()

            if (result != null) {
                logTactic(
                    "🔍【透视成功】敌方驻守首队:【${result.archetype}】 武将: ${result.detectedHeroes.joinToString("、")}\n" +
                    "⚔️ 克制推荐: ${result.recommendedCounters.joinToString(" / ")}\n" +
                    "🚫 严禁忌用: ${result.forbiddenSquads.joinToString(" / ")}\n" +
                    "💡 战术建议: ${result.rawAdvice}"
                )
                notifyStatus(
                    TacticalState.Status.COMPLETED,
                    "透视成功: 敌方首队【${result.archetype}】，克制推荐: ${result.recommendedCounters.firstOrNull() ?: "无"}"
                )

                // 9. 若开启了自动反打，且存在克制队配置
                if (config.autoDispatchCounter && config.counterTroopSlot > 0) {
                    logTactic("⚡ 自动反打启用，正在调集第 ${config.counterTroopSlot} 队克制主力实施突击！")
                    dispatchCounterAttack(tapPoint, config.counterTroopSlot)
                }

                return result
            } else {
                logTactic("⚠️ 战报分析未获取到明确敌方阵容，建议手动查看战报")
                notifyStatus(TacticalState.Status.COMPLETED, "战报已生成，未能自动识别流派")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "PVP 驻守剥皮流程异常: ${e.message}", e)
            notifyStatus(TacticalState.Status.FAILED, "执行异常: ${e.message}")
            return null
        } finally {
            isRunning = false
        }
    }

    /**
     * 等待新战报到达并点击打开
     *
     * 轮询周期不再固定：固定 `delay(500)` 的抓屏+OCR 红点检测循环，在
     * 时刻序列上就是一根尖峰。现在改成均值 500ms 的泊松间隔，且**不跨过
     * deadline**（旧写法最坏情况会多等 500ms 才退出）。
     */
    private suspend fun waitForBattleReport(maxWaitSec: Int): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitSec * 1000L
        while (System.currentTimeMillis() < deadline && isRunning) {
            val alerts = EngineBridge.detectMailAlert(minConfidence = 0.5f)
            if (alerts.isNotEmpty()) {
                val badge = alerts.first()
                logTactic("📩 检测到战报/战况红点，位置: (${badge.centerX.toInt()}, ${badge.centerY.toInt()})，正在快速打开...")
                EngineBridge.tap(badge.centerX, badge.centerY)
                EngineBridge.humanDelay(800, 1500)
                return true
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            delay(minOf(TimingFingerprintEngine.poissonIntervalMs(REPORT_POLL_MEAN_MS), remaining))
        }
        return false
    }

    /**
     * 剖析战报画面并判定阵容流派与克制关系
     */
    fun analyzeLatestBattleReport(): GarrisonAnalysisResult? {
        val frame = EngineBridge.captureFrame() ?: return null
        val ocrResult = try {
            OcrManager.detectRoi(frame)
        } finally {
            frame.recycle()
        }

        val text = ocrResult?.strRes ?: ""
        return parseBattleTextToAnalysis(text)
    }

    /**
     * 将战报文本解析为阵容流派与克制建议（纯逻辑，供离线/实机双重验证）
     */
    fun parseBattleTextToAnalysis(reportText: String): GarrisonAnalysisResult {
        val detectedHeroes = extractHeroes(reportText)
        val archetype = detectArchetype(detectedHeroes, reportText)
        val (recommended, forbidden, advice) = queryCountersForArchetype(archetype, detectedHeroes)

        val summary = if (detectedHeroes.isNotEmpty()) {
            "敌方驻守首队:【$archetype】(${detectedHeroes.joinToString("/")})"
        } else {
            "敌方驻守首队:【$archetype】"
        }

        return GarrisonAnalysisResult(
            detectedHeroes = detectedHeroes,
            archetype = archetype,
            recommendedCounters = recommended,
            forbiddenSquads = forbidden,
            rawAdvice = advice,
            summaryText = summary
        )
    }

    /**
     * 识别武将名字
     */
    fun extractHeroes(text: String): List<String> {
        val hits = mutableListOf<String>()
        for (hero in KNOWN_HEROES) {
            if (text.contains(hero) && !hits.contains(hero)) {
                hits.add(hero)
            }
        }
        return hits
    }

    /**
     * 流派判定算法
     */
    fun detectArchetype(heroes: List<String>, fullText: String): String {
        val heroSet = heroes.toSet()

        // 1. 神赏法刀
        if (heroSet.any { it in listOf("吕蒙", "陆逊", "周瑜", "灵帝", "朱儁", "陈宫") } &&
            (fullText.contains("神兵") || fullText.contains("大赏") || fullText.contains("反计") || heroSet.contains("吕蒙"))
        ) {
            return "神赏法刀"
        }

        // 2. 网瘾蜀骑
        if (heroSet.contains("马岱") || (heroSet.contains("关羽") && heroSet.contains("徐庶")) ||
            (heroSet.contains("马岱") && heroSet.contains("马云禄"))
        ) {
            return "网瘾蜀骑"
        }

        // 3. 垒实肉步
        if ((heroSet.any { it in listOf("皇甫嵩", "汉董卓", "刘备", "赵云", "郝昭") }) &&
            (fullText.contains("垒实") || fullText.contains("健卒") || fullText.contains("桃园") || heroSet.contains("刘备"))
        ) {
            return "垒实肉步"
        }

        // 4. 大营砍王
        if (heroSet.contains("魏延") && heroSet.contains("马超")) {
            return "大营砍王"
        }

        // 5. 魏智
        if (heroSet.any { it in listOf("荀彧", "郭嘉", "贾诩", "荀攸") } &&
            (heroSet.count { it in listOf("荀彧", "郭嘉", "贾诩", "荀攸") } >= 2 || fullText.contains("魏智"))
        ) {
            return "魏智"
        }

        // 6. 流氓队 / 爷爷队
        if (heroSet.contains("孙权") && (heroSet.contains("张机") || heroSet.contains("关银屏"))) {
            return "流氓队"
        }
        if (heroSet.contains("皇甫嵩") && (heroSet.contains("张机") || heroSet.contains("陆抗"))) {
            return "爷爷队"
        }

        // 7. 菜刀队
        if (heroSet.contains("马超") && (heroSet.contains("张辽") || heroSet.contains("曹操"))) {
            return "传统菜刀"
        }

        return if (heroes.isNotEmpty()) "${heroes.first()}驻守队" else "常规驻守防守队"
    }

    /**
     * 阵容克制建议库
     */
    fun queryCountersForArchetype(
        archetype: String,
        heroes: List<String>
    ): Triple<List<String>, List<String>, String> {
        return when (archetype) {
            "神赏法刀" -> Triple(
                listOf("网瘾蜀骑(先手秒大营)", "垒实肉步(硬扛3回合)", "流氓队(孙权规避免控)"),
                listOf("无战必传统菜刀(被前3回合直接融化)", "脆皮爆发队"),
                "敌方法刀爆发极高且带双封。建议利用蜀骑极速先手点杀大营，或用高减伤垒实肉步拖过前三回合反打；切忌上普通物理菜刀送武勋！"
            )
            "网瘾蜀骑" -> Triple(
                listOf("双封神赏法刀(吕蒙战必封普攻)", "垒实肉步(吸收物理单点)", "战必断金菜刀"),
                listOf("智力脆皮法师队", "无防御防守队(大营一触即死)"),
                "敌方蜀骑物理单点破防极强。核心克制是封禁马岱与徐庶的普攻叠层，上带战必断金或白衣渡江队伍可稳稳拿捏！"
            )
            "垒实肉步" -> Triple(
                listOf("禁疗破防法刀(李儒/荀彧驱逐禁疗)", "网瘾蜀骑(极致爆发穿透)"),
                listOf("普通物理菜刀(刮痧被反弹致死)", "慢速持续输出队"),
                "敌方肉步回血与免控极强，普通物理伤害打上去近乎刮痧。必须上禁疗或单点爆发穿透，绝不要上普通菜刀送武勋！"
            )
            "大营砍王" -> Triple(
                listOf("双封法刀(战必封马超普攻)", "垒实肉步(健卒迎击吸收)"),
                listOf("缺乏防御的主动法师队", "脆皮步兵队"),
                "魏延跳大极具威胁。上战必断金锁住前锋与中军普攻，即可彻底废掉敌方输出轴！"
            )
            "魏智" -> Triple(
                listOf("传统高速菜刀(先手物理切碎)", "网瘾蜀骑(高速点杀)"),
                listOf("慢速回复肉步(荀彧100%禁疗彻底废掉肉步)"),
                "魏智谋略伤害极高且荀彧带绝对禁疗。千万不要上慢速肉步送死，上高速物理菜刀或蜀骑抢先手直接秒杀！"
            )
            "流氓队", "爷爷队" -> Triple(
                listOf("高频控制法刀", "网瘾蜀骑", "禁疗队"),
                listOf("慢速单核主动队(输出被孙权规避全吃)"),
                "孙权带全队解控与规避。需用高频伤害破除规避层数，配合高频主动或物理连击点杀！"
            )
            "传统菜刀" -> Triple(
                listOf("双封法刀(战必断金+白衣渡江)", "垒实肉步"),
                listOf("无控脆皮队"),
                "菜刀完全依赖前三回合普攻爆发。战必断金一出，敌方直接变成无牙老虎！"
            )
            else -> Triple(
                listOf("网瘾蜀骑", "神赏法刀", "垒实肉步"),
                listOf("残血低兵力队"),
                "常规驻守目标。建议以兵力压制为主，满士气出征，留意敌方兵力与红度。"
            )
        }
    }

    /**
     * 自动派发克制部队反打
     */
    private suspend fun dispatchCounterAttack(tapPoint: PointF, counterSlot: Int) {
        WatchdogRecovery.recoverToMainMap()
        EngineBridge.humanDelay(500, 800)
        EngineBridge.tap(tapPoint.x, tapPoint.y)
        EngineBridge.humanDelay(600, 1000)
        EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.ATTACK)
        EngineBridge.humanDelay(800, 1200)
        selectTroopSlot(counterSlot)
        EngineBridge.humanDelay(500, 900)
        EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CONFIRM)
        logTactic("🚀 第 $counterSlot 队克制主力已成功出征，执行阵容反打！")
    }

    private suspend fun selectTroopSlot(slotIndex: Int): Boolean {
        val slot = slotIndex.coerceIn(1, UiAnchors.troopTabSlotCount)
        val tabPoint = UiAnchors.troopTab(slot)
        return EngineBridge.tap(tabPoint.x, tabPoint.y)
    }

    private suspend fun resolveTargetPoint(config: GarrisonConfig): PointF? {
        if (!config.bookmarkName.isNullOrBlank()) {
            when (MapNavigator.jumpByBookmark(config.bookmarkName)) {
                is MapNavigator.Result.Reached -> return MapProjection.viewportCenterCanvas()
                else -> Log.w(TAG, "书签跳转未成，尝试坐标")
            }
        }
        if (config.targetWorldCoord != null && MapProjection.isCalibrated) {
            val (wx, wy) = config.targetWorldCoord
            when (MapNavigator.centerOn(wx, wy)) {
                is MapNavigator.Result.Reached -> return MapProjection.viewportCenterCanvas()
                else -> Log.w(TAG, "世界坐标跳转未成，回退屏幕坐标")
            }
        }
        return config.targetTileCoord
    }

    private fun notifyStatus(status: TacticalState.Status, detail: String) {
        listener.onStatusChanged(TacticalState.TaskType.GARRISON_RADAR, status, detail)
    }

    private fun logTactic(msg: String) {
        listener.onLogEmitted(
            TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.GARRISON_RADAR,
                level = "TACTIC",
                message = msg
            )
        )
    }

    companion object {
        private const val TAG = "GarrisonStripperFlow"

        /** 战报红点轮询的**期望**周期（实际间隔由泊松采样，不是固定值）。 */
        private const val REPORT_POLL_MEAN_MS = 500L

        val KNOWN_HEROES = listOf(
            "吕蒙", "陆逊", "周瑜", "灵帝", "朱儁", "陈宫", "张机", "孙权", "关银屏",
            "马超", "张辽", "曹操", "魏延", "马岱", "关羽", "徐庶", "马云禄", "皇甫嵩",
            "汉董卓", "刘备", "赵云", "郝昭", "荀彧", "郭嘉", "贾诩", "荀攸", "陆抗",
            "张春华", "司马懿", "庞德", "姜维", "黄月英", "甘宁", "太史慈", "祝融夫人"
        )
    }
}
