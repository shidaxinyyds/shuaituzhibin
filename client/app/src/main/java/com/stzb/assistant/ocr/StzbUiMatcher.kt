package com.stzb.assistant.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.random.Random

/**
 * 率土之滨全场景状态机与语义按键定位器 (StzbUiMatcher)
 *
 * ## 按键定位有两条互补的通道（不是一条）
 *   * **通道 A：OCR 语义识别**——认的是"出征/扫荡/驻守"这些中文字，
 *     所以官方换按钮皮肤、改边框花纹时它照样找得到；代价是需要 native OCR 引擎。
 *   * **通道 B：模板匹配**——认的是"这一小片长什么样"（见
 *     [com.stzb.assistant.service.ButtonTemplateStore]），不需要 OCR、不需要模型；
 *     代价是改了美术/换了皮肤就要重新学（所以命中后会自愈刷新，见
 *     [refreshTemplateIfConfident]）。
 *
 * 默认构建里 native OCR 是空桩（ncnn 未参与编译），此时**通道 B 是唯一还能工作的定位手段**；
 * 反过来 OCR 装上之后，通道 A 命中也会顺手把模板学下来，让"OCR 哪天不可用"不至于全盘失效。
 * 早期版本这里写着"彻底摒弃 PNG 小切片匹配"，那是只在有 OCR 的前提下才成立的结论，
 * 现已按实际行为改写——留着那句话会让人以为模板通道是历史包袱。
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
        CANCEL("取消", listOf("取 消", "关闭", "再想想", "放弃操作")),
        MARCH("行军", listOf("行 军")),
        TRANSFER("调兵", listOf("调 兵", "调动")),
        RETREAT("撤退", listOf("撤 退", "立即撤退")),
        RECRUIT("征兵", listOf("征 兵", "快速征兵")),
        COORDINATE("坐标", listOf("座标", "跳转", "查坐标")),
        JUMP("跳转", listOf("跳 转", "前往")),
        TAX("税收", listOf("征税", "强征", "征 税", "税额", "课税")),
        UPGRADE("升级", listOf("升 级", "扩建", "建筑升级", "即刻升级")),
        FORGE("锻造", listOf("打铁", "工匠", "宝物", "打造", "精炼", "寻访"))
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
    /**
     * 全景状态识别（带健康度统计）。
     *
     * 包一层的原因：本函数有多个 return 点，逐个加统计既啰嗦又容易漏。
     * 包一层之后只有**一个**统计点，将来新增分支也不会漏掉。
     */
    fun classifyGameState(fullFrame: Bitmap): GameState {
        val state = classifyGameStateInternal(fullFrame)
        RecognitionHealth.recordScene(state)
        return state
    }

    private fun classifyGameStateInternal(fullFrame: Bitmap): GameState {
        val ocrResult = OcrManager.detect(fullFrame)

        // OCR 不可用、或这一帧没读出任何文字时，退一步用**场景指纹**判断是否在大地图。
        // 底部功能栏的 5 张卡片是固定游戏美术，对它做指纹比对不需要 OCR、也不需要训练。
        //
        // 这一步的价值非常具体：原先 OCR 失效时本函数恒返回 UNKNOWN，
        // 于是 WatchdogRecovery 会在"本来就在大地图"的情况下仍然反复盲点地图空白区
        // 试图"自愈回大地图"——那正是用户能直接感知到的"乱点"。
        //
        // 注意边界：指纹只能回答"像不像大地图"，无法区分各类弹窗，
        // 因此这里只可能返回 MAIN_MAP，其余一律交回 UNKNOWN，绝不冒充其它场景。
        if (ocrResult == null || ocrResult.strRes.isBlank()) {
            // 记 0：这一次识别确实一个字都没读到。场景判定是挂机时最频繁的识别调用，
            // 把它计入统计，"整帧无文字"的比例才真正反映识别在不在退化。
            RecognitionHealth.recordTextFrame(if (ocrResult == null) 0 else ocrResult.textBlocks.size)
            val fpMatch = com.stzb.assistant.service.SceneFingerprint.match(fullFrame)
            if (fpMatch != null && com.stzb.assistant.service.SceneFingerprint.isMainMap(fpMatch)) {
                Log.i(TAG, "场景指纹判定为大地图（未使用 OCR）: ${fpMatch.describe()}")
                return GameState.MAIN_MAP
            }
            return GameState.UNKNOWN
        }

        RecognitionHealth.recordTextFrame(ocrResult.textBlocks.size)
        val fullText = ocrResult.strRes

        // 1. 判断是否处于“出征/调动部队选择面板”
        if (containsAny(fullText, sceneWords(SCENE_DISPATCH_ACTION, listOf("出征", "调动", "行军")))
            && containsAny(fullText, sceneWords(SCENE_DISPATCH_TROOP, listOf("部队", "体力", "耗时", "预计")))
        ) {
            return GameState.TROOP_DISPATCH_DIALOG
        }

        // 2. 判断是否处于“查看守军面板”
        if (containsAny(fullText, sceneWords(SCENE_DEFENDER_TITLE, listOf("守军")))
            && containsAny(fullText, sceneWords(SCENE_DEFENDER_DETAIL, listOf("兵力", "战法", "难度")))
        ) {
            return GameState.DEFENDER_INFO_DIALOG
        }

        // 3. 判断是否处于“地块操作菜单展开” (必须至少出现2个动作关键字，防止大地图常驻顶部“出征”顶栏按钮误判)
        //    动作词直接取**当前知识库这五个语义键的主词**，不再抄一份率土词表：
        //    语义键本来就是"这个游戏的这个动作叫什么"的唯一权威，抄两份迟早对不上。
        val actionKeywords = MENU_ACTION_BUTTONS.flatMap { sceneWordsForButton(it) }
        val matchCount = actionKeywords.count { fullText.contains(it) }
        if (matchCount >= 2 &&
            !containsAny(fullText, sceneWords(SCENE_TROOP_PANEL, listOf("部队一", "部队二")))
        ) {
            return GameState.TILE_ACTION_MENU
        }

        // 4. 判断是否处于“坐标跳转面板”
        if (containsAny(fullText, sceneWords(SCENE_COORD_TITLE, listOf("坐标")))
            && containsAny(fullText, sceneWords(SCENE_COORD_ACTION, listOf("跳转", "X", "Y")))
        ) {
            return GameState.COORDINATE_SEARCH_DIALOG
        }

        // 5. 判断是否处于“筑城/要塞面板”
        if (containsAny(fullText, sceneWords(SCENE_FORTRESS_TITLE, listOf("要塞")))
            && containsAny(fullText, sceneWords(SCENE_FORTRESS_ACTION, listOf("建设", "工匠", "建造")))
        ) {
            return GameState.FORTRESS_BUILD_DIALOG
        }

        // 6. 判断是否处于“大地图主界面”
        if (containsAny(fullText, sceneWords(SCENE_MAIN_MAP_HUD, listOf("令", "战报", "势力", "同盟")))) {
            return GameState.MAIN_MAP
        }

        return GameState.UNKNOWN
    }

    // ==========================================================
    // 场景判定词表（率土默认值留在代码里，任何游戏都能用知识包覆盖）
    // ==========================================================

    // 场景判定的词组 ID（知识包 scene_keywords 的键）。
    // 值是"出现其中任意一个就算命中这一组"，组与组之间是 AND，
    // 组合关系属于引擎的场景模型（SLG 通用），词本身属于游戏（每游戏不同）。
    const val SCENE_DISPATCH_ACTION = "DISPATCH_ACTION"
    const val SCENE_DISPATCH_TROOP = "DISPATCH_TROOP"
    const val SCENE_DEFENDER_TITLE = "DEFENDER_TITLE"
    const val SCENE_DEFENDER_DETAIL = "DEFENDER_DETAIL"
    const val SCENE_TROOP_PANEL = "TROOP_PANEL"
    const val SCENE_COORD_TITLE = "COORD_TITLE"
    const val SCENE_COORD_ACTION = "COORD_ACTION"
    const val SCENE_FORTRESS_TITLE = "FORTRESS_TITLE"
    const val SCENE_FORTRESS_ACTION = "FORTRESS_ACTION"
    const val SCENE_MAIN_MAP_HUD = "MAIN_MAP_HUD"

    /** 用于"地块操作菜单"判定的五个动作语义键（与底栏动作一一对应）。 */
    private val MENU_ACTION_BUTTONS = listOf(
        ButtonType.ATTACK, ButtonType.SWEEP, ButtonType.DEFEND, ButtonType.FARM, ButtonType.TRAIN
    )

    /** 每个"游戏 + 词组"只告警一次"正在用率土默认词表"，避免每帧刷日志。 */
    private val defaultedVocabWarned = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun containsAny(text: String, words: List<String>): Boolean = words.any { text.contains(it) }

    /**
     * 取某个场景词组：知识包给了就用知识包的，没给就回落内置率土词表。
     *
     * 回落时**必须响**（每个游戏+词组只响一次）：非率土游戏没配词表却按率土文案判场景，
     * 判出来的状态是猜的——而挂机链路真的会按这个猜测去点。
     */
    private fun sceneWords(groupId: String, stzbDefault: List<String>): List<String> {
        val prof = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile
        prof.sceneKeywords[groupId]?.filter { it.isNotBlank() }?.let { if (it.isNotEmpty()) return it }
        val mark = "${prof.gameId}/$groupId"
        if (defaultedVocabWarned.putIfAbsent(mark, true) == null) {
            Log.w(TAG, "场景词组 [$groupId] 在 [${prof.gameName}] 的知识包里没有配置，" +
                "正在用内置率土词表 $stzbDefault 判定；该游戏界面文案若不同，场景判定会失真。" +
                "请在知识包 scene_keywords 里补这个组。")
        }
        return stzbDefault
    }

    /** 语义按键在当前知识包里的全部叫法（主词 + 别名），没配则回落枚举里的率土默认词。 */
    private fun sceneWordsForButton(type: ButtonType): List<String> {
        val btnDef = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.semanticButtons[type.name]
        val words = listOfNotNull(btnDef?.primaryKeyword?.takeIf { it.isNotBlank() }) +
            (btnDef?.aliases ?: emptyList())
        return if (words.isNotEmpty()) words else listOf(type.primaryKeyword) + type.aliases
    }

    /**
     * 从游戏大地图右上角提取当前镜头所对准的世界沙盘坐标 (如 "(229, 131)")
     */
    fun extractGameWorldCenterCoord(fullFrame: Bitmap): Pair<Int, Int>? {
        val ocrResult = OcrManager.detect(fullFrame) ?: return null
        val p = java.util.regex.Pattern.compile("(?:[\\(（\\[])?\\s*(\\d{2,4})\\s*[,，\\s]\\s*(\\d{2,4})\\s*(?:[\\)）\\]])?")
        for (block in ocrResult.textBlocks) {
            val m = p.matcher(block.text)
            if (m.find()) {
                val x = m.group(1)?.toIntOrNull() ?: continue
                val y = m.group(2)?.toIntOrNull() ?: continue
                // 坐标界按**当前游戏**的大地图尺寸判（知识库 map_coord_max），不写死率土的 1500
                if (com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules
                        .isValidWorldCoord(x, y)
                ) {
                    return Pair(x, y)
                }
            }
        }
        return null
    }

    /**
     * 检索指定单一功能按键
     */
    fun findButton(bitmap: Bitmap, type: ButtonType): ButtonResult? =
        findButtons(bitmap, listOf(type))[type]

    /**
     * 单次 OCR 批量检索多个功能按键（避免多次全图 OCR 造成 CPU 负载飙升）
     */
    fun findButtons(bitmap: Bitmap, types: List<ButtonType>): Map<ButtonType, ButtonResult> {
        val ocrResult = OcrManager.detect(bitmap) ?: return emptyMap()
        return matchButtons(ocrResult.textBlocks, types)
    }

    /**
     * 在已识别出的文本块里匹配按键。
     *
     * 单独抽出来是为了让"带原因诊断"的查找**只跑一次 OCR**——
     * OCR 是全流程里最贵的一步，不能为了拿到失败原因就再识别一遍。
     */
    private fun matchButtons(
        textBlocks: List<com.benjaminwan.ocrlibrary.TextBlock>,
        types: List<ButtonType>
    ): Map<ButtonType, ButtonResult> {
        val resultMap = mutableMapOf<ButtonType, ButtonResult>()
        val bestQuality = mutableMapOf<ButtonType, Int>()
        val candidates = mutableMapOf<ButtonType, MutableList<String>>()

        for (block in textBlocks) {
            val text = block.text.replace(" ", "").trim()

            for (type in types) {
                val quality = matchQuality(text, type)
                if (quality == 0) continue
                candidates.getOrPut(type) { mutableListOf() }.add(block.text)

                // 只在**质量更高**时替换。
                //
                // 原先这里写的是 `if (resultMap.containsKey(type)) continue`，
                // 而它上面的注释却写着"如果已经找到了当前类型**且置信度更高**，则跳过"
                // ——**那个置信度比较从来没有被实现过**。实际行为是"谁先被 OCR 读到就取谁"。
                //
                // 这会造成真实的点错：按键文案在画面上重复出现很常见，
                // 例如列表里的「查看」与按钮上的「查看守军」、
                // 面板上的「确定」与二次确认框上的「确定」。
                if (quality <= (bestQuality[type] ?: 0)) continue

                val minX = block.boxPoint.minOf { it.x }
                val maxX = block.boxPoint.maxOf { it.x }
                val minY = block.boxPoint.minOf { it.y }
                val maxY = block.boxPoint.maxOf { it.y }
                val rect = Rect(minX, minY, maxX, maxY)

                // 拟人化安全点击点：严控高斯抖动在 ±8% (±2dp以内)，严格保证命中按钮有效响应区
                val width = (maxX - minX).toFloat()
                val height = (maxY - minY).toFloat()
                val centerX = minX + width / 2f
                val centerY = minY + height / 2f

                val jitterX = centerX + (Random.nextFloat() - 0.5f) * width * 0.08f
                val jitterY = centerY + (Random.nextFloat() - 0.5f) * height * 0.08f

                val btnResult = ButtonResult(
                    type = type,
                    matchedText = block.text,
                    bounds = rect,
                    safeTouchPoint = PointF(jitterX, jitterY),
                    confidence = block.boxScore
                )
                resultMap[type] = btnResult
                bestQuality[type] = quality
                Log.d(
                    TAG,
                    "定位语义按键 [${type.name}]: text='${block.text}', " +
                        "point=($jitterX, $jitterY), 匹配质量=$quality"
                )
            }
        }

        // 有多个候选时把"到底选了哪一个"写进日志——
        // 这类选择无法在本机验证，只能靠真机日志回溯。
        for ((type, list) in candidates) {
            if (list.size > 1 && resultMap.containsKey(type)) {
                Log.w(
                    TAG,
                    "按键 [${type.name}] 在画面中有 ${list.size} 个候选文案" +
                        "（${list.joinToString(" / ")}），已按「完全一致优先、其次取先出现」选中: " +
                        "'${resultMap.getValue(type).matchedText}'"
                )
            }
        }

        return resultMap
    }

    /**
     * 文案与目标按键的**匹配质量**（0 表示不匹配）。
     *
     * 为什么需要"质量"而不是简单的匹配/不匹配：
     * 匹配本身是**子串包含**，而有的别名非常泛——例如
     * [ButtonType.SCOUT_DEFENDERS] 的别名含「查看」、[ButtonType.CONFIRM] 的别名含「出征」。
     * 若只判"匹配"，列表里的「查看详情」就可能抢在按钮「查看守军」之前被选中。
     *
     * 分级：3 = 与 primaryKeyword 完全一致（最具体的信号）；
     *       2 = 与某个 alias 完全一致；
     *       1 = 仅包含 primaryKeyword 或某个 alias。
     */
    private fun matchQuality(cleanText: String, type: ButtonType): Int {
        val btnDef = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.semanticButtons[type.name]
        val primary = (btnDef?.primaryKeyword ?: type.primaryKeyword).replace(" ", "")
        val aliases = (btnDef?.aliases ?: type.aliases).map { it.replace(" ", "") }

        if (cleanText == primary) return 3
        if (aliases.any { cleanText == it }) return 2
        if (cleanText.contains(primary)) return 1
        if (aliases.any { cleanText.contains(it) }) return 1
        return 0
    }

    // ==========================================================
    // 带原因的按键查找
    // ==========================================================

    /**
     * 按键定位失败的原因。
     *
     * 为什么要把它们分开：原先 `clickButton` 失败只返回 `false`，
     * 调用方只知道"没点到"，却分不清下面三种情况——而它们的处置方式完全不同：
     *
     * | 原因 | 含义 | 该怎么办 |
     * |---|---|---|
     * | [OCR_UNAVAILABLE] | 引擎根本没编译进来 / 初始化失败 | 需要带 ncnn 的构建，改代码没用 |
     * | [NO_TEXT] | 引擎可用，但这一帧一个字都没读到 | 可能画面在过渡/黑屏，或识别区域需标定 |
     * | [NO_MATCH] | 读到了文字，但没有匹配上目标按键 | 可能不在该界面，或游戏改了按键文案 |
     */
    enum class ButtonLookupFailure(val desc: String) {
        OCR_UNAVAILABLE("OCR 引擎不可用（构建期缺 ncnn/OpenCV 或初始化失败），无法识别任何文字"),
        NO_TEXT("画面中未识别到任何文字（可能处于过渡动画/黑屏，或识别区域需要标定）"),
        NO_MATCH("识别到文字但未匹配上目标按键（可能当前不在该界面，或游戏改动了按键文案）"),
        /**
         * 按键**已经定位到**，但手势没派发出去。
         *
         * 与 [NO_MATCH] 严格区分：NO_MATCH 是"没找到按钮"，这个是"找到了但点不出去"。
         * 混为一谈会误导排查方向——一个要查关键词/场景，另一个要查触控通道。
         * （这正是我上一轮犯的错：把派发失败也写成了 NO_MATCH。）
         */
        TAP_DISPATCH_FAILED("已定位到按键，但手势派发失败（触控通道可能被中断）")
    }

    data class ButtonLookup(
        val button: ButtonResult?,
        val failure: ButtonLookupFailure?,
        /** 本次识别到的文本块数量，便于判断"读到了多少字"。 */
        val scannedTextCount: Int
    )

    /**
     * 查找按键，并在失败时给出**可区分的原因**。
     * 与 [findButton] 使用完全相同的匹配逻辑与触控点计算，只是多带一个诊断结果。
     */
    fun findButtonWithReason(
        bitmap: Bitmap,
        type: ButtonType,
        /** 是否计入识别健康度统计。界面上的"试匹配"应当传 false，避免污染挂机期间的统计。 */
        record: Boolean = true
    ): ButtonLookup {
        val lookup = lookupButton(bitmap, type)
        if (record) {
            RecognitionHealth.recordButton(
                type,
                lookup.button != null,
                lookup.failure?.desc,
                lookup.scannedTextCount
            )
        }
        return lookup
    }

    /** 实际查找逻辑；统计由 [findButtonWithReason] 统一记录，避免多个 return 点漏记。 */
    private fun lookupButton(bitmap: Bitmap, type: ButtonType): ButtonLookup {
        // ---- 通道 A：OCR（能适应改文案，但引擎不可用时整条失效）----
        val ocrReason: ButtonLookupFailure?
        val scanned: Int
        if (!OcrManager.isEngineAvailable) {
            ocrReason = ButtonLookupFailure.OCR_UNAVAILABLE
            scanned = 0
        } else {
            val ocrResult = OcrManager.detect(bitmap)
            if (ocrResult == null) {
                ocrReason = ButtonLookupFailure.OCR_UNAVAILABLE
                scanned = 0
            } else {
                val blocks = ocrResult.textBlocks
                scanned = blocks.size
                if (blocks.isEmpty()) {
                    ocrReason = ButtonLookupFailure.NO_TEXT
                } else {
                    val hit = matchButtons(blocks, listOf(type))[type]
                    if (hit != null) {
                        // 随用随学：OCR 成功定位过一次，就把这一小片登记成模板。
                        // 这样等哪天 OCR 不可用了，同一个按键仍然点得到。
                        // 失败不影响本次结果，因此只记日志。
                        learnTemplateIfPossible(bitmap, type, hit.bounds)
                        return ButtonLookup(hit, null, blocks.size)
                    }
                    ocrReason = ButtonLookupFailure.NO_MATCH
                }
            }
        }

        // ---- 通道 B：模板匹配（不依赖 OCR，但需要事先登记过模板）----
        // 两条路互补：OCR 适应"改了文案"，模板适应"读不出字"。
        // 默认构建里 OCR 是空桩，因此这条通道是**唯一还能工作的定位手段**。
        val viaTemplate = matchButtonByTemplate(bitmap, type)
        if (viaTemplate != null) {
            return ButtonLookup(viaTemplate, null, scanned)
        }

        return ButtonLookup(null, ocrReason, scanned)
    }

    /**
     * 用模板库定位按键。
     *
     * 优先走 **OpenCV 的 `TM_CCOEFF_NORMED`**（原生实现，快且经过充分验证）；
     * 仅当 OpenCV 原生库不可用时，才退回纯 Java 的 [com.stzb.assistant.service.TemplateMatcher]
     * ——两者数学上是同一件事（归一化互相关），后者存在的唯一理由是"没有 OpenCV 也要能用"。
     * 两条通道都带**区分度判据**：宁可返回 null 也不猜，因为猜错就是点错目标。
     */
    private fun matchButtonByTemplate(bitmap: Bitmap, type: ButtonType): ButtonResult? {
        val store = com.stzb.assistant.service.ButtonTemplateStore
        if (!store.has(type)) return null
        val tpl = store.load(type) ?: return null

        return try {
            val cv = com.stzb.assistant.ocr.OpenCvMatcher
            if (cv.isAvailable) {
                val key = "BTN_${type.name}"
                // 每次都重新载入：模板可能刚被重新登记，缓存里那份就过期了。
                // 一张 180x80 的小图，代价可以忽略。
                cv.invalidateTemplate(key)
                if (cv.loadTemplateFromBitmap(key, tpl)) {
                    val r = cv.match(bitmap, key)
                    if (r.isFound) {
                        val cx = r.centerX.toInt()
                        val cy = r.centerY.toInt()
                        Log.i(
                            TAG,
                            "OpenCV 模板匹配定位按键 [${type.name}]：" +
                                "($cx, $cy) 分数=${"%.3f".format(r.score)}"
                        )
                        refreshTemplateIfConfident(bitmap, type, cx, cy, r.score.toDouble())
                        return buildTemplateResult(type, cx, cy, r.score.toDouble())
                    }
                }
            }

            // 兜底：OpenCV 不可用或未命中。**必须全图搜索**：这里曾限定画面下 2/3，
            // 前提是"功能按键都在下方操作区"——但地块动作轮盘是跟着被点的地块浮动的，
            // 真机截图实测键位能出现在画布上半部（扫荡中心 y≈201），区域限制会让
            // 随包种子在那条路上一律搜不到。误命中的防护交给阈值与区分度判据
            // （种子验收的负例检验同样是在整帧上跑的，两侧口径一致），代价是全图 ZNCC 多算一些。
            val m = com.stzb.assistant.service.TemplateMatcher.search(
                frame = bitmap,
                template = tpl
            ) ?: return null
            Log.i(TAG, "纯 Java 模板匹配定位按键 [${type.name}]：${m.describe()}")
            refreshTemplateIfConfident(bitmap, type, m.centerX, m.centerY, m.score)
            buildTemplateResult(type, m.centerX, m.centerY, m.score)
        } catch (e: Exception) {
            Log.w(TAG, "模板匹配定位按键 [${type.name}] 异常: ${e.message}")
            null
        }
        // 注意：**这里绝不 recycle(tpl)**。
        //
        // 原来这段写在 finally 里，而 [com.stzb.assistant.service.ButtonTemplateStore.load]
        // 返回的是**内存缓存里那一份**。于是第一次用模板成功定位之后，缓存里就躺着一张
        // 已回收的位图：之后每次匹配都抛 "Canvas: trying to use a recycled bitmap"，
        // 被上面的 catch 咽掉，表现成"这个按键登记过、也命中过一次，然后就再也点不到了"。
        // 模板的生命周期归模板库管（一共十几张 180x80 的小图，交给 GC 即可），
        // 借用方不许释放。
    }

    /**
     * 模板命中的自愈刷新门槛。
     *
     * 两条命中线分别是 0.75（OpenCV，见 `OpenCvMatcher.match` 默认值）与
     * 0.85（纯 Java，见 `TemplateMatcher.search` 默认值）。刷新会**覆盖**正在工作的模板，
     * 所以必须站在远高于两条线的位置：0.92。低于它时宁可继续用旧模板，也不拿一次
     * 将信将疑的命中去改写它。
     */
    private const val TEMPLATE_REFRESH_MIN_SCORE = 0.92

    /** 自检线：候选模板回搜自己来源的那一帧，必须达到的分数。 */
    private const val TEMPLATE_SELF_CHECK_MIN_SCORE = 0.95

    /** 自检允许的落点偏差（像素）。超出即认为"这张图在帧里不唯一"，拒绝覆盖。 */
    private const val TEMPLATE_SELF_CHECK_POS_TOLERANCE_PX = 8

    /** 同一个按键两次刷新之间的最小间隔：写盘 + 一次全图自检都不该每帧付。 */
    private const val TEMPLATE_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

    private val lastTemplateRefreshAt = HashMap<String, Long>()

    /**
     * 模板匹配成功且分数很高时，把这一帧上的那一小片**重新登记**回去。
     *
     * ## 为什么需要
     * 模板是某个时刻从某台机器上裁下来的像素。官方改了按钮描边、换了赛季色调之后，
     * 旧模板的分数会慢慢往下掉；如果模板永远不许变，用户就得每隔几个月重新指一遍。
     * 更关键的是**随包种子**：那是别人机器上的像素，第一次在用户机上高分命中时，
     * 正好是把它换成本机像素的时机。
     *
     * ## 为什么不能"命中就写"
     * 自动写坏一次，用户没有像手动登记那样的"当场看出来、当场重来"的机会，
     * 表现会是"用着用着某个按键开始点偏"。所以加了三道闸：
     *   1. 分数必须 ≥ [TEMPLATE_REFRESH_MIN_SCORE]（远高于两条命中线）；
     *   2. 每个按键 [TEMPLATE_REFRESH_INTERVAL_MS] 内最多刷一次；
     *   3. **自检**：拿候选图回搜它来源的那一帧，必须命中回原位置（±
     *      [TEMPLATE_SELF_CHECK_POS_TOLERANCE_PX]px）且分数 ≥ [TEMPLATE_SELF_CHECK_MIN_SCORE]。
     *      通不过就说明这张图在画面里不唯一或不够清楚，覆盖旧模板只会更糟。
     *      自检刻意走纯 Java 的 [com.stzb.assistant.service.TemplateMatcher]：
     *      它比 OpenCV 那条更严（全图搜、自带区分度判据），严的当闸门比较宽的使用者更安全。
     *
     * 任何失败都只记日志：本次定位结果不受影响（旧模板照样能用）。
     */
    private fun refreshTemplateIfConfident(
        frame: Bitmap,
        type: ButtonType,
        centerX: Int,
        centerY: Int,
        score: Double
    ) {
        if (score < TEMPLATE_REFRESH_MIN_SCORE) return
        val now = SystemClock.elapsedRealtime()
        synchronized(lastTemplateRefreshAt) {
            val prev = lastTemplateRefreshAt[type.name] ?: 0L
            if (now - prev < TEMPLATE_REFRESH_INTERVAL_MS) return
            // 先占坑再自检：自检失败也不该下一帧立刻重试，否则闸门 2 形同虚设。
            lastTemplateRefreshAt[type.name] = now
        }
        val store = com.stzb.assistant.service.ButtonTemplateStore
        try {
            val crop = store.cropCandidate(frame, centerX, centerY) ?: return
            val check = com.stzb.assistant.service.TemplateMatcher.search(
                frame = frame,
                template = crop,
                threshold = TEMPLATE_SELF_CHECK_MIN_SCORE
            )
            if (check == null ||
                abs(check.centerX - centerX) > TEMPLATE_SELF_CHECK_POS_TOLERANCE_PX ||
                abs(check.centerY - centerY) > TEMPLATE_SELF_CHECK_POS_TOLERANCE_PX
            ) {
                Log.i(
                    TAG,
                    "模板自愈被拒（自检未通过）[${type.name}]：命中 ($centerX, $centerY) " +
                        "分数=${"%.3f".format(score)}，自检=" +
                        (check?.let { "(${it.centerX}, ${it.centerY}) ${"%.3f".format(it.score)}" } ?: "无命中")
                )
                return
            }
            store.saveBitmap(
                type = type,
                crop = crop,
                note = "自愈刷新，命中 ${"%.3f".format(score)} / 自检 ${"%.3f".format(check.score)}"
            )
        } catch (e: Exception) {
            Log.d(TAG, "模板自愈跳过 [${type.name}]: ${e.message}")
        }
    }

    private fun buildTemplateResult(
        type: ButtonType,
        centerX: Int,
        centerY: Int,
        score: Double
    ): ButtonResult {
        val hw = com.stzb.assistant.service.ButtonTemplateStore.CROP_HALF_WIDTH
        val hh = com.stzb.assistant.service.ButtonTemplateStore.CROP_HALF_HEIGHT
        return ButtonResult(
            type = type,
            matchedText = "(模板匹配)",
            bounds = Rect(centerX - hw, centerY - hh, centerX + hw, centerY + hh),
            safeTouchPoint = PointF(centerX.toFloat(), centerY.toFloat()),
            confidence = score.toFloat()
        )
    }

    /** OCR 命中后顺带学习模板；任何失败都只记日志，不影响定位结果。 */
    private fun learnTemplateIfPossible(bitmap: Bitmap, type: ButtonType, bounds: Rect) {
        try {
            // 判据是"本机有没有这一张"，不是"有没有任何一张"：
            // 只写着 has(type) 的话，随包种子会把这个位置永久占住，OCR 明明看得见按钮
            // 却再也不去学本机自己那份像素 —— 而种子恰恰是最该被本机版本替换掉的。
            val store = com.stzb.assistant.service.ButtonTemplateStore
            if (store.sourceOf(type) == com.stzb.assistant.service.ButtonTemplateStore.Source.USER) return
            store.saveFromFrame(
                type = type,
                frame = bitmap,
                centerX = (bounds.left + bounds.right) / 2,
                centerY = (bounds.top + bounds.bottom) / 2
            )
        } catch (e: Exception) {
            Log.d(TAG, "学习按键模板 [${type.name}] 跳过: ${e.message}")
        }
    }

    /**
     * 模糊语义匹配逻辑，优先结合当前激活的游戏知识库动态别名，免疫空格、连字符及轻微错别字
     */
    // 说明：原先这里有一个 matchesType()，只做"匹配/不匹配"的二分判断。
    // 改为按匹配质量择优后它已无调用方，按本项目"死代码要删"的标准移除——
    // 留着它会让人以为"择优"这件事还有第二个实现。
}
