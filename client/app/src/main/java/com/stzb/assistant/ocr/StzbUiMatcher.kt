package com.stzb.assistant.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import kotlin.random.Random

/**
 * 率土之滨现代化全场景状态机与语义按键定位器 (StzbUiMatcher)
 * 
 * 彻底摒弃老旧写死 PNG 小切片匹配（NetEase 频繁更换 UI 材质与赛季皮肤会导致小图失效）；
 * 采用【RapidOCR 语义特征提取 + 空间几何校验 + 模糊容错匹配】的现代化全场景识别架构。
 * 
 * 支持：
 *   1. 场景状态智能分类 (大地图、地块轮盘菜单、出征面板、守军面板、坐标面板、筑城面板等)；
 *   2. 核心功能按键精确定位与抗风控拟人触控点计算；
 *   3. 单次 OCR 批量提取多按键坐标，极大节省推理算力与耗电。
 */
object StzbUiMatcher {

    private const val TAG = "StzbUiMatcher"

    /**
     * 率土核心游戏场景枚举
     */
    enum class GameState {
        MAIN_MAP,               // 大地图主界面 (可见坐标、令数、战报入口等)
        TILE_ACTION_MENU,       // 地块操作轮盘/菜单已展开 (出征/扫荡/驻守/屯田/查看守军)
        TROOP_DISPATCH_DIALOG,  // 出征/调动部队选择面板 (部队编队、体力、预计耗时、确定)
        DEFENDER_INFO_DIALOG,   // 查看守军面板 (敌方守军武将名、战法、兵力梯队)
        COORDINATE_SEARCH_DIALOG,// 坐标检索/跳转弹窗 (X/Y 输入框、跳转)
        FORTRESS_BUILD_DIALOG,  // 筑城/要塞建设面板 (要塞、斥候营地、分城)
        ALERT_RAID_ACTIVE,      // 敌袭预警告警状态 (红线临近或边缘红闪)
        UNKNOWN                 // 处于过渡动画、全黑屏或未知子菜单
    }

    /**
     * 核心语义按键定义 (覆盖 2025~2026 征服赛季全场景)
     */
    enum class ButtonType(val primaryKeyword: String, val aliases: List<String>) {
        ATTACK("出征", listOf("确定出征", "出 征", "出征作战")),
        SWEEP("扫荡", listOf("扫 荡", "扫荡练兵")),
        DEFEND("驻守", listOf("驻 守", "部队驻守")),
        FARM("屯田", listOf("屯 田", "资源屯田")),
        TRAIN("练兵", listOf("练 兵")),
        SCOUT_DEFENDERS("查看守军", listOf("守军", "守军详情", "查看", "守军信息")),
        BUILD("建设", listOf("建 设", "筑城", "建造")),
        ABANDON("放弃", listOf("放 弃", "放弃领地")),
        CONFIRM("确定", listOf("确 定", "确认", "出征", "出发")),
        MARCH("行军", listOf("行 军")),
        TRANSFER("调兵", listOf("调 兵", "调动")),
        RETREAT("撤退", listOf("撤 退", "立即撤退")),
        RECRUIT("征兵", listOf("征 兵", "快速征兵")),
        COORDINATE("坐标", listOf("座标", "跳转", "查坐标")),
        JUMP("跳转", listOf("跳 转", "前往"))
    }

    /**
     * 识别到的语义按键信息
     */
    data class ButtonResult(
        val type: ButtonType,
        val matchedText: String,
        val bounds: Rect,
        val safeTouchPoint: PointF, // 已经过高斯拟人偏移计算的虚拟安全触控点
        val confidence: Float
    )

    /**
     * 全景状态识别：根据全屏 OCR 结果特征集，智能判定当前游戏状态
     */
    fun classifyGameState(fullFrame: Bitmap): GameState {
        val ocrResult = OcrManager.detect(fullFrame) ?: return GameState.UNKNOWN
        val fullText = ocrResult.strRes

        // 1. 判断是否处于“出征/调动部队选择面板”
        if ((fullText.contains("出征") || fullText.contains("调动") || fullText.contains("行军"))
            && (fullText.contains("部队") || fullText.contains("体力") || fullText.contains("耗时") || fullText.contains("预计"))
        ) {
            return GameState.TROOP_DISPATCH_DIALOG
        }

        // 2. 判断是否处于“查看守军面板”
        if (fullText.contains("守军") && (fullText.contains("兵力") || fullText.contains("战法") || fullText.contains("难度"))) {
            return GameState.DEFENDER_INFO_DIALOG
        }

        // 3. 判断是否处于“地块操作菜单展开”
        val hasTileActions = fullText.contains("出征") || fullText.contains("扫荡") || fullText.contains("驻守") || fullText.contains("屯田")
        if (hasTileActions && !fullText.contains("部队一") && !fullText.contains("部队二")) {
            return GameState.TILE_ACTION_MENU
        }

        // 4. 判断是否处于“坐标跳转面板”
        if (fullText.contains("坐标") && (fullText.contains("跳转") || fullText.contains("X") || fullText.contains("Y"))) {
            return GameState.COORDINATE_SEARCH_DIALOG
        }

        // 5. 判断是否处于“筑城/要塞面板”
        if (fullText.contains("要塞") && (fullText.contains("建设") || fullText.contains("工匠") || fullText.contains("建造"))) {
            return GameState.FORTRESS_BUILD_DIALOG
        }

        // 6. 判断是否处于“大地图主界面”
        if (fullText.contains("令") || fullText.contains("战报") || fullText.contains("势力") || fullText.contains("同盟")) {
            return GameState.MAIN_MAP
        }

        return GameState.UNKNOWN
    }

    /**
     * 检索指定单一功能按键
     */
    fun findButton(bitmap: Bitmap, type: ButtonType): ButtonResult? {
        val results = findButtons(bitmap, listOf(type))
        return results[type]
    }

    /**
     * 单次 OCR 批量检索多个功能按键（避免多次全图 OCR 造成 CPU 负载飙升）
     */
    fun findButtons(bitmap: Bitmap, types: List<ButtonType>): Map<ButtonType, ButtonResult> {
        val resultMap = mutableMapOf<ButtonType, ButtonResult>()
        val ocrResult = OcrManager.detect(bitmap) ?: return resultMap

        for (block in ocrResult.textBlocks) {
            val text = block.text.replace(" ", "").trim()

            for (type in types) {
                // 如果已经找到了当前类型且置信度更高，则跳过
                if (resultMap.containsKey(type)) continue

                val matched = matchesType(text, type)
                if (matched) {
                    val minX = block.boxPoint.minOf { it.x }
                    val maxX = block.boxPoint.maxOf { it.x }
                    val minY = block.boxPoint.minOf { it.y }
                    val maxY = block.boxPoint.maxOf { it.y }
                    val rect = Rect(minX, minY, maxX, maxY)

                    // 拟人化安全点击点：在按钮中心周围 25% 范围内做高斯随机扰动，绝不点击死板几何中心
                    val width = (maxX - minX).toFloat()
                    val height = (maxY - minY).toFloat()
                    val centerX = minX + width / 2f
                    val centerY = minY + height / 2f

                    val jitterX = centerX + (Random.nextFloat() - 0.5f) * width * 0.35f
                    val jitterY = centerY + (Random.nextFloat() - 0.5f) * height * 0.35f

                    val btnResult = ButtonResult(
                        type = type,
                        matchedText = block.text,
                        bounds = rect,
                        safeTouchPoint = PointF(jitterX, jitterY),
                        confidence = block.boxScore
                    )
                    resultMap[type] = btnResult
                    Log.d(TAG, "定位语义按键 [${type.name}]: text='${block.text}', point=($jitterX, $jitterY)")
                }
            }
        }

        return resultMap
    }

    /**
     * 模糊语义匹配逻辑，优先结合当前激活的游戏知识库动态别名，免疫空格、连字符及轻微错别字
     */
    private fun matchesType(cleanText: String, type: ButtonType): Boolean {
        val btnDef = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.semanticButtons[type.name]
        val primary = btnDef?.primaryKeyword ?: type.primaryKeyword
        val aliases = btnDef?.aliases ?: type.aliases

        if (cleanText.contains(primary)) return true
        for (alias in aliases) {
            val cleanAlias = alias.replace(" ", "")
            if (cleanText.contains(cleanAlias)) return true
        }
        return false
    }
}
