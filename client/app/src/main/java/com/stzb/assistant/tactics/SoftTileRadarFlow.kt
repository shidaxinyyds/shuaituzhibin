package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import com.stzb.assistant.ai.rag.SlgRagEngine
import com.stzb.assistant.ocr.DefenderTemplateClassifier
import com.stzb.assistant.ocr.OcrManager
import com.stzb.assistant.ocr.StzbUiMatcher
import com.stzb.assistant.service.CoordinateTransformer
import com.stzb.assistant.service.EngineBridge
import com.stzb.assistant.service.MapNavigator
import com.stzb.assistant.service.MapProjection
import com.stzb.assistant.service.UiAnchors
import kotlinx.coroutines.delay

/**
 * PVE 软柿子雷达与扫地精算师业务引擎 (SoftTileRadarFlow)
 *
 * 核心痛点解决：
 *   1. 【全自动批量点击查看守军】：自动遍历主城/要塞周边 6~9 级土地，逐一点击「查看守军」；
 *   2. 【双通道守军识别】：双队头像模板匹配 + RapidOCR 守将名与技能提取；
 *   3. 【SlgRagEngine 难度精算评级】：
 *      - D (软柿子稳开) - 优先开荒/扫荡
 *      - C (常规防守)   - 适中兵力稳吃
 *      - B (较难有损)   - 易产生战损
 *      - S (极危翻车点) - 严禁触碰，带控制/反弹/引爆技能
 *   4. 【生成软柿子排行榜 (Soft-Touch Leaderboard)】：呈现最低战损目标清单；
 *   5. 【一键绑定练级流水线】：最优软柿子地块可无缝绑定至 SquadLevelingFlow 实施扫荡。
 */
class SoftTileRadarFlow(
    private val context: Context,
    private val listener: TacticalState.TacticalEventListener
) {

    @Volatile
    private var isRunning: Boolean = false

    data class RadarConfig(
        val centerCoord: PointF? = null,
        val centerWorldCoord: Pair<Int, Int>? = null,
        val bookmarkName: String? = null,
        val scanRadiusTiles: Int = 2, // 扫描半径（1~3格）
        val targetMinLevel: Int = 6,
        val targetMaxLevel: Int = 9,
        val maxScanCount: Int = 12
    )

    data class TileRadarItem(
        val canvasCoord: PointF,
        val worldCoord: Pair<Int, Int>?,
        val level: Int,
        val defenderHeroes: List<String>,
        val rating: String,          // SlgRagEngine 的六档：“D(软柿子稳开)”“C(中等但本等级推荐)”
        // “C-(中等需慎)”“C(常规防守)”“B(较难有损)”“S(极危翻车点)”；
        // 下面的排序只看首字母，新增档位必须保持首字母与难度同序。
        val isSafe: Boolean,
        val advice: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val scannedLeaderboard = ArrayList<TileRadarItem>()

    fun getLeaderboard(): List<TileRadarItem> = synchronized(scannedLeaderboard) {
        ArrayList(scannedLeaderboard)
    }

    fun getBestSoftTile(): TileRadarItem? = synchronized(scannedLeaderboard) {
        scannedLeaderboard.filter { it.isSafe }.minByOrNull { item ->
            when {
                item.rating.startsWith("D") -> 0
                item.rating.startsWith("C") -> 1
                item.rating.startsWith("B") -> 2
                else -> 3
            }
        } ?: scannedLeaderboard.firstOrNull()
    }

    fun stop() {
        isRunning = false
        logTactic("⏹️ PVE 软柿子雷达已收到终止请求")
    }

    suspend fun execute(config: RadarConfig): List<TileRadarItem> {
        isRunning = true
        synchronized(scannedLeaderboard) { scannedLeaderboard.clear() }

        notifyStatus(TacticalState.Status.RUNNING, "开始执行 PVE 软柿子雷达扫描...")
        logTactic("📡 软柿子雷达启动: 半径=${config.scanRadiusTiles}格, 目标等级=Lv.${config.targetMinLevel}~Lv.${config.targetMaxLevel}, 上限=${config.maxScanCount}块")

        try {
            WatchdogRecovery.recoverToMainMap()

            // 1. 对准中心
            val center = resolveCenterPoint(config) ?: MapProjection.viewportCenterCanvas()
            logTactic("🧭 锁定扫描雷达中心: (${center.x.toInt()}, ${center.y.toInt()})")

            // 2. 生成待扫描网格点
            val scanPoints = generateGridPoints(center, config.scanRadiusTiles, config.maxScanCount)
            logTactic("🗺️ 已生成 ${scanPoints.size} 个地块侦察扫描点，开始逐一探测守军...")

            for ((index, pt) in scanPoints.withIndex()) {
                if (!isRunning) break

                notifyStatus(TacticalState.Status.RUNNING, "正在探测第 ${index + 1}/${scanPoints.size} 块地...")
                val item = scanSingleTile(pt, config)
                if (item != null) {
                    synchronized(scannedLeaderboard) {
                        scannedLeaderboard.add(item)
                    }
                    val icon = if (item.isSafe) "🟢" else "⚠️"
                    logTactic(
                        "$icon【发现守军】Lv.${item.level} 地块 -> 评级: ${item.rating}\n" +
                        "  守将: ${if (item.defenderHeroes.isNotEmpty()) item.defenderHeroes.joinToString("、") else "常规守军"}\n" +
                        "  建议: ${item.advice}"
                    )
                }

                EngineBridge.humanDelay(400, 800)
            }

            val totalScanned = scannedLeaderboard.size
            val softCount = scannedLeaderboard.count { it.isSafe }
            val best = getBestSoftTile()

            val summary = if (best != null) {
                "扫描完成: 探测 $totalScanned 块，软柿子 $softCount 块。最优推荐 Lv.${best.level}(${best.rating})"
            } else {
                "扫描完成: 探测 $totalScanned 块，未发现高性价比白给地块"
            }

            logTactic("🏁【雷达精算完毕】$summary")
            notifyStatus(TacticalState.Status.COMPLETED, summary)

            return getLeaderboard()
        } catch (e: Exception) {
            Log.e(TAG, "软柿子雷达扫描异常: ${e.message}", e)
            notifyStatus(TacticalState.Status.FAILED, "雷达异常: ${e.message}")
            return getLeaderboard()
        } finally {
            isRunning = false
        }
    }

    /**
     * 单地块侦察与难度判定
     */
    private suspend fun scanSingleTile(pt: PointF, config: RadarConfig): TileRadarItem? {
        // 1. 点击地块
        if (!EngineBridge.tap(pt.x, pt.y)) return null
        EngineBridge.humanDelay(500, 800)

        // 2. 检查是否有「查看守军」按钮
        val scoutBtn = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.SCOUT_DEFENDERS)
        if (!scoutBtn.clicked) {
            // 没有查看守军按钮（可能是我方地块/无守军），点空白处收起轮盘
            closePopupOrWheel()
            return null
        }

        EngineBridge.humanDelay(900, 1400)

        // 3. 守军面板已打开，抓屏分析守军
        val frame = EngineBridge.captureFrame()
        var level = 7 // 默认中位数
        var heroes = emptyList<String>()
        var analysis: SlgRagEngine.DefenderAnalysis? = null

        if (frame != null) {
            try {
                // OCR 提取全屏文字与地块等级
                val ocr = OcrManager.detectRoi(frame)
                val fullText = ocr?.strRes ?: ""
                level = extractLandLevel(fullText, config.targetMinLevel)

                // 提取守军武将：双通道（头像模板比对 + OCR 名字提取）
                val templateHits = DefenderTemplateClassifier.classify(frame)
                val nameRoi = Rect(
                    (frame.width * 0.25f).toInt(),
                    (frame.height * 0.20f).toInt(),
                    (frame.width * 0.85f).toInt(),
                    (frame.height * 0.75f).toInt()
                )
                val ocrHits = EngineBridge.collectDefenderHeroes(frame, nameRoi)
                heroes = (templateHits + ocrHits).distinct()

                val queryText = if (heroes.isNotEmpty()) heroes.joinToString(" ") else fullText
                analysis = SlgRagEngine.queryLandDefender(level, queryText)
            } finally {
                frame.recycle()
            }
        }

        // 4. 关闭守军面板
        closePopupOrWheel()

        if (analysis == null) {
            analysis = SlgRagEngine.queryLandDefender(level, heroes.joinToString(" "))
        }

        // 转换为世界坐标（如果已标定）
        val worldCoord = if (MapProjection.isCalibrated) {
            MapProjection.screenToWorld(pt.x, pt.y)
        } else null

        return TileRadarItem(
            canvasCoord = pt,
            worldCoord = worldCoord,
            level = level,
            defenderHeroes = heroes,
            rating = analysis.rating,
            isSafe = analysis.isSafeToHit,
            advice = analysis.rawAdvice
        )
    }

    private suspend fun closePopupOrWheel() {
        val cancelOutcome = EngineBridge.clickButtonDiagnosed(StzbUiMatcher.ButtonType.CANCEL)
        if (!cancelOutcome.clicked) {
            // 点击地图空白处安全关闭
            val blank = UiAnchors.point(UiAnchors.Key.MAP_BLANK)
            EngineBridge.tap(blank.x, blank.y)
        }
    }

    /**
     * 从 OCR 文本中提取土地等级 (Lv.6 ~ Lv.9)
     */
    fun extractLandLevel(text: String, fallbackLevel: Int): Int {
        val rx = Regex("""(?:Lv\.?|土地|等级|级)?\s*([5-9])\s*(?:级|地)?""")
        val m = rx.find(text)
        if (m != null) {
            val num = m.groupValues[1].toIntOrNull()
            if (num != null && num in 5..9) return num
        }
        for (lvl in listOf(9, 8, 7, 6, 5)) {
            if (text.contains("${lvl}级") || text.contains("Lv$lvl") || text.contains("Lv.$lvl")) {
                return lvl
            }
        }
        return fallbackLevel
    }

    /**
     * 生成中心周边的同心圆/网格测试点
     */
    fun generateGridPoints(center: PointF, radiusTiles: Int, maxCount: Int): List<PointF> {
        val points = mutableListOf<PointF>()
        // 游戏 720p 视角下单格菱形土地跨度约为宽 120px，高 65px
        val stepX = 110f
        val stepY = 60f

        for (r in 1..radiusTiles) {
            for (dx in -r..r) {
                for (dy in -r..r) {
                    if (Math.abs(dx) + Math.abs(dy) == r) {
                        val px = center.x + dx * stepX
                        val py = center.y + dy * stepY
                        // 确保在可视区安全内
                        if (px in 150f..1150f && py in 120f..620f) {
                            points.add(PointF(px, py))
                            if (points.size >= maxCount) return points
                        }
                    }
                }
            }
        }
        return points
    }

    private suspend fun resolveCenterPoint(config: RadarConfig): PointF? {
        if (!config.bookmarkName.isNullOrBlank()) {
            when (MapNavigator.jumpByBookmark(config.bookmarkName)) {
                is MapNavigator.Result.Reached -> return MapProjection.viewportCenterCanvas()
                else -> Log.w(TAG, "书签未能对准，尝试坐标")
            }
        }
        if (config.centerWorldCoord != null && MapProjection.isCalibrated) {
            val (wx, wy) = config.centerWorldCoord
            when (MapNavigator.centerOn(wx, wy)) {
                is MapNavigator.Result.Reached -> return MapProjection.viewportCenterCanvas()
                else -> Log.w(TAG, "世界坐标未能对准，回退屏幕坐标")
            }
        }
        return config.centerCoord
    }

    private fun notifyStatus(status: TacticalState.Status, detail: String) {
        listener.onStatusChanged(TacticalState.TaskType.SOFT_TILE_RADAR, status, detail)
    }

    private fun logTactic(msg: String) {
        listener.onLogEmitted(
            TacticalState.TacticalLog(
                taskType = TacticalState.TaskType.SOFT_TILE_RADAR,
                level = "TACTIC",
                message = msg
            )
        )
    }

    companion object {
        private const val TAG = "SoftTileRadarFlow"
    }
}
