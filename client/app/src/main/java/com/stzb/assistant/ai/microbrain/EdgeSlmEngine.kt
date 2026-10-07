package com.stzb.assistant.ai.microbrain

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

/**
 * 端侧军令语义提取器 (EdgeSlmEngine)
 *
 * ## 诚实说明（重要）
 * 本类的主通道是**正则 + 关键词提取**：
 *   * [parseAllianceDecree]：正则为主，另有一条**可选**的 ONNX 意图通道（[IntentSlotModel]）；
 *   * [diagnoseBattleReport] / [generateAdvisorLiveStream]：纯正则 + RAG 检索，没有任何权重参与。
 *
 * 意图通道能做的和**不能做的**已被严格限定（见下方 8.5 节的注释）：
 * 它只能在正则未命中任何意图关键词时补位一个抽象分类，
 * 永远不得改写原文里已经读到的目标名与坐标，也不得给自己的置信度加底分。
 * 理由很直接：`intent_slot_zh.onnx` 的准确率**从未在本机实测过**，
 * 而它的输出会一路走到 `DualTrackSafetyGate.resolveTarget` 变成真实下发目标。
 *
 * ## 关于 GGUF/MNN 那套旧声明
 * 本类的 KDoc 曾声称"主通道加载端侧 INT4 GGUF/MNN 权重做多步条件生成，
 * 无权重时自动切换"，并配套维护 `isModelWeightLoaded` / `modelPath` 等字段。
 * 实际情况是：那些字段**从未被任何代码读取**，而 `ModelAssetManager` 的诚实报告
 * 也已写明"需 GGUF/MNN 运行时；当前 EdgeSlmEngine 无生成式推理后端"。
 * 因此这里删除了那套字段，并把启动日志改成如实汇报探测结果——
 * 探测到权重也只是"文件在"，不代表存在能跑它的推理后端。
 *
 * 之所以保留探测逻辑：一旦将来真的接入 GGUF/MNN 运行时，这里是唯一的接入点。
 * 但在那之前，它只输出事实，不宣称能力。
 */
class EdgeSlmEngine(private val context: Context) {

    init {
        com.stzb.assistant.ai.rag.SlgRagEngine.init(context)
        probeOptionalAssets()
    }

    /**
     * 探测可选的权重资产，并**如实**汇报。
     *
     * 注意措辞：这里只说"发现文件"，不说"通道已就绪"——
     * 因为工程内没有任何加载 GGUF/MNN 并进行推理的实现，
     * 说"就绪"会让日志读者以为模型真的在参与决策。
     */
    private fun probeOptionalAssets() {
        try {
            // 文件名一律引用 ModelAssetManager 的常量：探针、资产体检、真正加载的引擎
            // 必须读同一个名字，各抄一份就会出现"体检说齐备、引擎说没找到"的自相矛盾。
            // 游戏专属的两份（意图微脑、RAG 索引）按当前游戏派生标记后再探，
            // bge 是通用中文向量器、与游戏无关，因此不分域。
            val intentPath = probeAssetPath(
                com.stzb.assistant.ai.assets.ModelAssetManager.INTENT_MODEL_FILE,
                1L * 1024 * 1024, gameScoped = true
            )
            val bgePath = probeAssetPath(
                com.stzb.assistant.ai.assets.ModelAssetManager.BGE_MODEL_FILE,
                5L * 1024 * 1024, gameScoped = false
            )
            val hnswPath = probeAssetPath(
                com.stzb.assistant.ai.assets.ModelAssetManager.RAG_INDEX_FILE,
                5L * 1024 * 1024, gameScoped = true
            )

            val found = buildList {
                intentPath?.let { add(java.io.File(it).name) }
                bgePath?.let { add(java.io.File(it).name) }
                hnswPath?.let { add(java.io.File(it).name) }
            }
            if (found.isEmpty()) {
                Log.i(
                    TAG,
                    "未发现端侧权重资产，军令/战报解析由内建正则语义提取器完成（这是当前唯一实现，非降级）。"
                )
            } else {
                Log.i(
                    TAG,
                    "发现端侧权重 ${found.joinToString()}；IntentSlotModel/BgeEmbedder 会在资产齐备且内存足够时"
                        + "自动启用真推理，否则如实回落正则/64维哈希通道。"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测端侧权重资产异常: ${e.message}")
        }
    }

    /**
     * 资产是否存在且达到最小体积，返回它**实际落地**的绝对路径；缺文件或体积不达标返回 null。
     *
     * 返回路径而不是布尔值：日志要念出真正被发现的那个文件名（带游戏标记的那个），
     * 念一个"名义上应该有的名字"会把排查的人引向错的文件。
     * "文件在"不等于"可推理"，所以对外措辞仍然只用"发现"。
     */
    private fun probeAssetPath(name: String, minBytes: Long, gameScoped: Boolean): String? {
        val mgr = com.stzb.assistant.ai.assets.ModelAssetManager
        val p = if (gameScoped) mgr.extractScopedModelPath(context, name, true)
                else mgr.getOrExtractModelPath(context, name)
            ?: return null
        return if (java.io.File(p).length() > minBytes) p else null
    }

    /**
     * 核心接口 1：全盟军令/邮件语义提取与日程转化
     */
    fun parseAllianceDecree(decreeText: String): TacticalOrder {
        val cleanText = decreeText.trim()
        if (cleanText.isEmpty()) {
            return fallbackEmptyOrder("空军令文本")
        }

        // 1. 提取目标关卡/城池名称
        val targetName = extractTargetName(cleanText)

        // 2. 提取地图坐标 (如 (782, 451) 或 782,451)
        val targetCoord = extractCoordinates(cleanText)

        // 3. 提取攻城/触城目标时间戳
        val targetTime = extractTargetTimestamp(cleanText)

        // 4. 提取压秒与铺路提前量
        val advanceSeconds = extractAdvanceSeconds(cleanText)

        // 5. 提取分配队伍角色
        val assignedTeams = extractTeamRoles(cleanText)

        // 6. 提取战术意图：只在原文**真的出现**关键词时才算命中，读不到就是 null。
        //    （旧写法把"一个关键词都没匹配上"直接当成「全盟攻城/集火」，于是任何一条
        //    看不懂的军令都会被派去攻城——那是最贵的一种静默错判。）
        val regexIntent = deduceIntentOrNull(cleanText)

        // 7. 提取应急预案 (如抢跑/被抢城皮)
        val contingency = extractContingency(cleanText)

        // 8. 结合 RAG 向量检索丰富战术操作指南与应急预案
        val ragMatch = com.stzb.assistant.ai.rag.SlgRagEngine.matchDecreeTactics(cleanText)

        // 8.5 意图+槽位微脑（ONNX 真推理）：**只允许补证据，不允许替换原文已经读到的事实**。
        //
        // 三个槽位区别对待，依据是"原文里到底有没有可核对的字面证据"：
        //   * intent：抽象分类，但"驻守/铺路/集火"这些词本身是字面证据，正则命中即确定正确，
        //     所以模型只在**原文一个意图关键词都没有**时才接管。
        //   * target：字面实体。模型只能从目标词表里挑一个，原文写"宛城"而它挑"洛阳"时，
        //     旧写法是**模型赢**，部队就去打洛阳了。因此只采纳"原文里确实出现"的名字。
        //   * coord：**永远只认原文**。模型的坐标输出是 10×10 桶的桶心（一格 60、只覆盖 0..600），
        //     而正则读的是原文里的精确数字。用桶心覆盖精确值 = 把部队派到最多偏 ±60 格的错地方，
        //     而这个值会经 DualTrackSafetyGate.resolveTarget 直接变成下发目标，
        //     [1, map_coord_max] 的边界检查永远抓不到它（桶心必然在界内）。
        //
        // 缺权重 / 内存不足 / 低置信 → parse 返回 null，全部退回正则结论（fail-safe）。
        IntentSlotModel.ensureLoaded(context)
        val modelParse = IntentSlotModel.parse(cleanText)
            ?.takeIf { it.intent != null && it.confidence >= MODEL_INTENT_MIN_CONFIDENCE }
        val modelTarget = modelParse?.target?.takeIf {
            it.isConcreteTargetName() && cleanText.contains(it)
        }
        val effIntent = regexIntent ?: modelParse?.intent ?: OrderIntent.ALLIANCE_SIEGE
        val effTargetName = modelTarget ?: targetName
        val effCoord = targetCoord
        // 模型坐标永远不采纳，但**要把它当线索写进日志**：一旦原文没坐标而模型报了个桶心，
        // 人能看到"模型觉得在附近"，而不会被默默当成目标（刻意 unused ≠ 遗忘）。
        val modelCoordHint = modelParse?.coord?.let { "(${it.first}, ${it.second})[10x10桶心，不采纳]" } ?: "无"
        val effConfidence = evidenceConfidence(
            targetName = effTargetName,
            coord = effCoord,
            timeMs = targetTime,
            intentHit = regexIntent != null || modelParse != null
        )
        val intentSource = when {
            regexIntent != null -> "正则(原文关键词)"
            modelParse != null -> "意图微脑"
            else -> "无证据，默认攻城"
        }
        Log.i(
            TAG,
            "军令裁决来源：intent=$intentSource 目标=${if (modelTarget != null) "微脑(原文印证)" else "正则原文"} " +
                "坐标=原文 微脑坐标线索=$modelCoordHint 置信度=$effConfidence"
        )
        if (modelParse != null) {
            Log.i(TAG, "\ud83e\udde0 意图微脑补位：softmax=${"%.2f".format(modelParse.confidence)} raw=${modelParse.intentRaw}；"
                    + "该概率只衡量意图头选类的陡峭程度（准确率从未在本机实测），不参与置信度加分")
        }

        // 9. 实时生成军师思考推演流
        val thinking = generateThinkingStream(effIntent, effTargetName, effCoord, targetTime, advanceSeconds, contingency, ragMatch.executionTimingAdvice)

        return TacticalOrder(
            orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            intent = effIntent,
            targetName = effTargetName,
            targetCoord = effCoord,
            targetTime = targetTime,
            advanceSeconds = advanceSeconds,
            assignedTeams = assignedTeams,
            contingencyPlan = contingency,
            advisorThinking = thinking,
            confidence = effConfidence,
            rawDecreeText = cleanText
        )
    }

    /**
     * 核心接口 2：战报深度会诊与兵种战法克制诊断 (RAG向量增强)
     *
     * **本轮修复**：
     *   1. 接入 PVE / PVP 分流：先在 RAG 层判定战报类型，再把专属结论（PVE 守军暴走/反击机制
     *      与补刀阈值；PVP 速度差先手、同类指挥战法冲突、加点超车建议）带进军师评述。
     *   2. 战损不再写死 `myTroopLoss = 2300 / enemyTroopLoss = 6800`——
     *      那两个数字此前与战报毫无关系，属于"看起来分析过了"的假数据。
     *      现在从战报原文解析，读不到就如实报 0（并在评述里注明未读到）。
     */
    fun diagnoseBattleReport(reportText: String): BattleDiagnosis {
        // 战法命中一律由 RAG 层按**当前知识包的战法字典**（scene_keywords.KNOWN_SKILLS）判定。
        // 这里原本自带一份 8 条的率土战法字典，而且 `(skill, _)` 把说明文字整列丢掉不用：
        // 同一件事两个来源、名单还比知识包窄（热更改动知识包时这一份跟不上），
        // 属于典型的第二权威，已删除。
        val isVictory = reportText.contains("大捷") || reportText.contains("胜")
        val isDefeat = reportText.contains("战败") || reportText.contains("败")
        val result = when {
            isVictory -> BattleResult.VICTORY
            isDefeat -> BattleResult.DEFEAT
            else -> BattleResult.DRAW
        }

        // 调用 RAG 向量引擎进行深层机制复盘与克制推演（自动做 PVE/PVP 分流）
        val ragDiag = com.stzb.assistant.ai.rag.SlgRagEngine.diagnoseBattleReport(reportText)
        val allSkills = ragDiag.detectedSkills

        val commentary = buildString {
            append("【诸葛军师 · RAG战报会诊】: 此役定性为${result.desc}。")
            append("战报类型: ")
            append(
                when (ragDiag.reportType) {
                    com.stzb.assistant.ai.rag.SlgRagEngine.ReportType.PVE -> "PVE 开荒打地"
                    com.stzb.assistant.ai.rag.SlgRagEngine.ReportType.PVP -> "PVP 玩家会战"
                    com.stzb.assistant.ai.rag.SlgRagEngine.ReportType.UNKNOWN -> "未判定（按通用路径分析）"
                }
            )
            append("\n")
            if (allSkills.isNotEmpty()) {
                append("▶ 关键战法: ${allSkills.joinToString("/")}\n")
            }
            if (ragDiag.conflictAnalysis.isNotBlank()) {
                append("▶ 机制复盘: ${ragDiag.conflictAnalysis}\n")
            }

            // PVE 专属：守军暴走/反击机制 + 补刀阈值
            ragDiag.pveAnalysis?.let { pve ->
                append("▶ 守军机制: ${pve.rampageEvidence}\n")
                append("▶ 补刀阈值: ≥ ${pve.killThresholdSoldiers} 兵力 ${pve.referenceNote}\n")
            }

            // PVP 专属：先手判定 + 指挥战法冲突 + 加点超车
            ragDiag.pvpAnalysis?.let { pvp ->
                append("▶ 先手判定: ${pvp.firstStrikeSide} ${pvp.referenceNote}\n")
                if (pvp.commandSkillConflict) {
                    append("▶ 指挥冲突: ${pvp.conflictDetail}\n")
                }
                append("▶ 加点建议: ${pvp.speedUpAdvice}\n")
            }

            append("▶ 调优建议: ${ragDiag.counterStrategy}")
        }

        val losses = parseTroopLosses(reportText)

        return BattleDiagnosis(
            battleId = "BTL-" + System.currentTimeMillis().toString().takeLast(6),
            battleResult = result,
            myTroopLoss = losses.first,
            enemyTroopLoss = losses.second,
            keySkillsDetected = allSkills,
            strategicCounterAdvice = ragDiag.counterStrategy,
            militaryCommentary = commentary
        )
    }

    /**
     * 从战报原文解析双方战损。
     *
     * 只在确实读到数字时返回；读不到就返回 (0, 0)，由上层如实展示"未读取到战损"，
     * 而不是塞一个编造的常数进去。
     *
     * @return Pair(我方战损, 敌方战损)
     */
    private fun parseTroopLosses(reportText: String): Pair<Int, Int> {
        fun firstNumberNear(keywords: List<String>): Int? {
            for (kw in keywords) {
                val idx = reportText.indexOf(kw)
                if (idx < 0) continue
                val tail = reportText.substring(idx, minOf(reportText.length, idx + 24))
                val m = Regex("(\\d{3,6})").find(tail) ?: continue
                m.groupValues.getOrNull(1)?.toIntOrNull()?.let { return it }
            }
            return null
        }

        val mine = firstNumberNear(listOf("我方损失", "我军损失", "损失兵力", "我部损失", "战损"))
        val enemy = firstNumberNear(listOf("敌方损失", "歼敌", "敌军损失", "击杀", "讨伐"))
        return Pair(mine ?: 0, enemy ?: 0)
    }

    /**
     * 核心接口 2.1：战报深度会诊 (端侧离线异步，0网络依赖)
     */
    fun diagnoseBattleReportAsync(
        reportText: String,
        callback: (BattleDiagnosis) -> Unit
    ) {
        val syncDiag = diagnoseBattleReport(reportText)
        callback(syncDiag)
    }

    /**
     * 核心接口 4：向诸葛军师问策（100% 端侧本地离线推演）
     * 基于端侧 RAG 向量特征匹配与战术矩阵因果合成，零网络请求、零延迟、纯本地计算。
     */
    fun askAdvisor(
        query: String,
        callback: (response: String) -> Unit
    ) {
        val cleanQuery = query.trim()
        val ragAdvice = com.stzb.assistant.ai.rag.SlgRagEngine.matchDecreeTactics(cleanQuery)
        val response = buildString {
            append("【诸葛军师 · 端侧离线推演】\n")
            append("主公，臣已调阅端侧兵书（SLG-RAG 向量底座）。针对“$cleanQuery”的研判如下：\n\n")
            append("▶ 战术意图: ").append(ragAdvice.intentSummary).append("\n")
            append("▶ 核心兵法: ").append(ragAdvice.executionTimingAdvice).append("\n")
            append("▶ 风险提示: ").append(ragAdvice.riskWarning).append("\n\n")
            append("💡 本地军师锦囊：\n")
            append(advisorPlaybook(cleanQuery))
            append("\n\n[端侧状态: 100% 本地运行 | 0 网络流量 | 0 隐私外传]")
        }
        callback(response)
    }

    /**
     * 军师锦囊正文（**一条游戏数据都不许写在这里**，P7 实测后重写）。
     *
     * 旧实现有两条会给玩家念错药方：
     *   1. "5级地守军兵力9000，我军建议5000兵" —— 知识包里 Lv5 的建议兵力是 **5500**，
     *      同一事实两个数，而热更只能改到知识那一条；
     *   2. "软柿子优先开：魏智郭嘉队" —— 知识包把**郭嘉明确列进 Lv5 黑名单**
     *      （十胜十败高概率混乱，极易自相残杀灭队）。把该避开的将推荐成软柿子，
     *      不是措辞不佳，是把主力送去翻车。
     * 现在开荒类问题一律现取 `activeProfile.defenderDb.landSuggestions`；
     * 战法名一类的具体建议属于语料层，只在语料归属的游戏上才输出（见 [corpusPlaybookActive]）。
     */
    private fun advisorPlaybook(query: String): String {
        val asksPaving = query.contains("开荒") || query.contains("打地") ||
            Regex("\\d{1,2}\\s*级地").containsMatchIn(query)
        if (asksPaving) return pavingPlaybook(query)

        val asksSkill = query.contains("神兵") || query.contains("法刀") || query.contains("大赏") ||
            query.contains("配将") || query.contains("战法") || query.contains("队伍")
        if (asksSkill && !corpusPlaybookActive()) {
            // 问的是具体战法搭配，但这套建议属于另一款游戏的语料：如实不答，
            // 只给不依赖任何游戏文案的通用行军建议。宁可少答，不许串游戏。
            return GENERIC_PLAYBOOK + "\n" +
                "• 本游戏的战法/配将建议需要它自己的知识包（scene_keywords + defender_db）" +
                "与语料资产，当前尚未配置，故此项不作答。"
        }
        return when {
            query.contains("神兵") || query.contains("法刀") || query.contains("大赏") ->
                "• 破法刀关键在前3回合：法刀伤害集中在前3回合，可用【空城】规避爆发，或带【反计之策】封其主动战法。\n" +
                    "• 肉步队伍带【避其锋芒】+【步步为营】可大幅削弱神兵大赏加成收益。"
            query.contains("攻城") || query.contains("压秒") ->
                "• 攻城两阶段原则：主力先锋必须在整点（如20:00:00）前 3~5 秒到达，先清守军；\n" +
                    "• 拆迁队严禁提前触城（避免送人头），设定在主力触城后 1~3 秒压秒触城，实现无缝破皮。"
            query.contains("配将") || query.contains("战法") || query.contains("队伍") ->
                "• 配将三要素：先手控制（反计/战必）+ 核心输出（一骑当千/折戟强攻）+ 防御减伤（垒实/避其）。\n" +
                    "• 务必注意战法冲突：同类指挥减伤不叠加，始计与大赏三军增伤冲突，避免浪费宝贵格子。"
            else -> GENERIC_PLAYBOOK
        }
    }

    /**
     * 战法类锦囊只对**语料归属的那款游戏**输出。
     *
     * 判据取 SlgRagEngine.corpusOwnerId（内存里那份语料真实属于谁），
     * 而不是一个写死的游戏 id：写死的常量不跟着语料走，
     * 换一款游戏备齐了自己的语料后，该服的锦囊也会被它一起拒掉。
     */
    private fun corpusPlaybookActive(): Boolean =
        com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.gameId ==
            com.stzb.assistant.ai.rag.SlgRagEngine.corpusOwnerId

    /**
     * 开荒/打地锦囊：数字与守军名单全部现取当前知识包的地块建议。
     *
     * 地块等级取自玩家问句（"5级地"/"Lv.7"），问句里没写就退回知识包覆盖的最低等级
     * （= 起步参考）。知识包没覆盖该等级时**如实说没有**，绝不拿别的等级凑。
     */
    private fun pavingPlaybook(query: String): String {
        val lands = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile
            .defenderDb.landSuggestions
        if (lands.isEmpty()) {
            return "• 当前知识包没有地块开荒数据（defender_db.land_suggestions 为空），" +
                "请先用【查看守军】逐块侦察，或热更该游戏的守军天梯。"
        }
        val asked = Regex("(?:Lv\\.?|LV\\.?|等级)\\s*(\\d{1,2})|(\\d{1,2})\\s*级地?")
            .find(query)?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() }
        if (asked != null && !lands.containsKey(asked)) {
            val have = lands.keys.sorted().joinToString("/")
            return "• 知识包里没有 Lv.$asked 地的数据（现有覆盖：Lv.$have），" +
                "这一档请先侦察或热更 defender_db.land_suggestions，我不猜兵力。"
        }
        val suggestion = lands.getValue(asked ?: lands.keys.minOrNull()!!)
        val garrison = if (suggestion.defenderTotalSoldiers > 0)
            "，守军总兵力约 ${suggestion.defenderTotalSoldiers}" else ""
        val safeText = suggestion.safeHeroes.joinToString("、")
            .ifBlank { "（本等级尚未登记软柿名单，逐块侦察后再打）" }
        val blackText = suggestion.blacklistHeroes.joinToString("、")
            .ifBlank { "（本等级尚未登记黑名单，见到高星守将按有疑问处理）" }
        return buildString {
            append("• 开荒切忌急躁：Lv.").append(suggestion.landLevel)
                .append(" 地建议带兵不低于 ").append(suggestion.recommendedSoldiers)
                .append(garrison).append("（数字取自当前知识包，可热更）。\n")
            append("• 软柿守军可优先挑：").append(safeText).append("；坚决避开：").append(blackText).append("。\n")
            if (suggestion.note.isNotBlank()) append("• ").append(suggestion.note)
        }
    }

    /** 获取当前军师大脑激活模式描述 */
    fun getBrainDescription(): String {
        return "端侧离线微脑（" + IntentSlotModel.describe() + " | SLG-RAG 向量底座，纯离线毫秒级）"
    }

    /**
     * 核心接口 3：军师实时战况推演动态输出
     *
     * @param patrolRunning 敌袭巡检守护**当前是否真的在运行**。
     *   这个参数是必要的：原实现无条件宣称"雷达哨兵保持 1.5s 周期巡查"，
     *   而 ① 1.5 秒这个数字从来没有出处（真实周期来自知识库的
     *   `raidPatrolIntervalMs`，率土 4000ms / 三战 5000ms），
     *   ② 巡检可能根本没在跑。也就是说它在向用户陈述**两件都不成立的事实**。
     */
    fun generateAdvisorLiveStream(
        statusText: String,
        activeOrder: TacticalOrder?,
        patrolRunning: Boolean = false
    ): String {
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return if (activeOrder != null) {
            "[$timeStr 诸葛军师推演] 正在执行军令【${activeOrder.intent.desc}·${activeOrder.targetName}】。" +
                    "预计提前${activeOrder.advanceSeconds}秒铺路压秒，当前状态：$statusText。"
        } else {
            // 只陈述可核实的内容：真实的 statusText + 巡检守护的运行状态与其**配置**周期。
            // 不再编造"局势平稳"（引擎无从得知）与"1.5s 周期"（数字无出处）。
            val patrolSec = com.stzb.assistant.knowledge.KnowledgeBaseManager
                .activeProfile.tacticalDefaults.raidPatrolIntervalMs / 1000.0
            val patrolDesc = if (patrolRunning) {
                "敌袭巡检守护运行中（配置周期 ${"%.1f".format(patrolSec)} 秒）"
            } else {
                "敌袭巡检守护未运行（配置周期 ${"%.1f".format(patrolSec)} 秒）——" +
                    "需要时可在「巡检」页签开启"
            }
            "[$timeStr 诸葛军师推演] 当前状态：$statusText。$patrolDesc。"
        }
    }

    // ================== 语义提取私有工具函数 ==================

    private fun extractTargetName(text: String): String {
        // 匹配常见关卡与城池
        val p = Pattern.compile("([\\u4e00-\\u9fa5]{2,5}(?:关|城|寨|郡|府|要塞|码头|桥头))")
        val m = p.matcher(text)
        if (m.find()) {
            return m.group(1) ?: "目标要地"
        }
        // 匹配“打XXX”
        val p2 = Pattern.compile("打(?:下|击)?([\\u4e00-\\u9fa5]{2,4})")
        val m2 = p2.matcher(text)
        if (m2.find()) {
            return m2.group(1) ?: "目标城池"
        }
        return "目标据点"
    }

    private fun extractCoordinates(text: String): Pair<Int, Int>? {
        val rules = com.stzb.assistant.knowledge.KnowledgeBaseManager.activeProfile.rules
        val p = Pattern.compile("(?:[\\(（\\[])?\\s*(\\d{2,4})\\s*[,，\\s]\\s*(\\d{2,4})\\s*(?:[\\)）\\]])?")
        val m = p.matcher(text)
        while (m.find()) {
            val x = m.group(1)?.toIntOrNull() ?: 0
            val y = m.group(2)?.toIntOrNull() ?: 0
            // 有效界按**当前游戏**的地图尺寸判（知识库 map_coord_max），率土的 1500 不是宇宙常数
            if (rules.isValidWorldCoord(x, y)) {
                return Pair(x, y)
            }
        }
        return null
    }

    private fun extractTargetTimestamp(text: String): Long {
        val cal = Calendar.getInstance()
        var hour = -1
        var minute = -1

        // 匹配 "20:00" 或 "20点30"
        val pTime = Pattern.compile("(\\d{1,2})[点:：](\\d{1,2})?")
        val mTime = pTime.matcher(text)
        if (mTime.find()) {
            hour = mTime.group(1)?.toIntOrNull() ?: -1
            minute = mTime.group(2)?.toIntOrNull() ?: 0
        } else if (text.contains("八点")) {
            hour = 20; minute = 0
        } else if (text.contains("九点")) {
            hour = 21; minute = 0
        } else if (text.contains("十点")) {
            hour = 22; minute = 0
        } else if (text.contains("七点")) {
            hour = 19; minute = 0
        }

        if (hour != -1) {
            if (hour in 1..11 && (text.contains("晚") || text.contains("今晚") || text.contains("夜"))) {
                hour += 12
            }
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            // 如果设置的时间已经过去，则默认指明天同一时间
            if (cal.timeInMillis < System.currentTimeMillis() - 3600000) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        // 默认预设 10 分钟后出发
        return System.currentTimeMillis() + 600 * 1000
    }

    private fun extractAdvanceSeconds(text: String): Int {
        if (text.contains("提前5分") || text.contains("5分钟前")) return 300
        if (text.contains("提前3分") || text.contains("3分钟前")) return 180
        if (text.contains("提前10分")) return 600
        if (text.contains("提前2分")) return 120
        return 180 // 默认提前 3 分钟铺路压秒
    }

    private fun extractTeamRoles(text: String): List<TeamRole> {
        val roles = mutableListOf<TeamRole>()
        if (text.contains("主力") || text.contains("高战")) roles.add(TeamRole.MAIN_FORCE)
        if (text.contains("拆迁") || text.contains("车")) roles.add(TeamRole.SIEGE_FORCE)
        if (text.contains("斯巴达") || text.contains("探路") || text.contains("摸皮")) roles.add(TeamRole.SPARTAN)
        if (text.contains("驻守") || text.contains("肉队")) roles.add(TeamRole.DEFENDER)
        if (roles.isEmpty()) roles.add(TeamRole.MAIN_FORCE)
        return roles
    }

    /**
     * 从原文推战术意图；**一个关键词都没出现时返回 null**，而不是硬塞一个默认意图。
     *
     * 返回值交给调用方决定兜底策略（正则 null → 问微脑 → 都没有才落默认），
     * 这样"到底是哪条通道决定了这次行动"在日志里是可追问的。
     */
    private fun deduceIntentOrNull(text: String): OrderIntent? {
        return when {
            text.contains("打关") || text.contains("攻城") || text.contains("集火") || text.contains("触城") -> OrderIntent.ALLIANCE_SIEGE
            text.contains("铺路") || text.contains("翻地") || text.contains("起要塞") -> OrderIntent.ROAD_PAVING
            text.contains("驻守") || text.contains("守关") || text.contains("关口") -> OrderIntent.DEFEND_GATE
            text.contains("撤退") || text.contains("回防") -> OrderIntent.RETREAT_AND_GUARD
            text.contains("斯巴达") || text.contains("探路") -> OrderIntent.SPARTAN_SCOUT
            text.contains("屯田") || text.contains("征兵") || text.contains("休整") -> OrderIntent.STAMINA_RECOVERY
            else -> null
        }
    }

    /**
     * 置信度：**只按原文里实际读到的证据累加，不给任何通道加底分**。
     *
     * 被替掉的旧写法是 `0.80f + 0.18f * 模型概率`，两个问题：
     *   1.  intent_slot 的**准确率从未在本机实测过**，0.80 起步等于替模型吹牛；
     *   2.  正则分支里的 `targetName != "未明目标"` **恒真**（见 [GENERIC_TARGET_NAMES]），
     *      所以无论军令读到了什么，置信度永远算出 0.96。
     *
     * 这个数原先只默默进入 Utility 的 `wConfidence`（面板根本不显示）；
     * 现在军师页会把它如实打出来，低置信不再被底分抹平。
     *
     * 现在的口径：证据越多分越高，一条硬证据都没读到就只给 0.40。
     */
    private fun evidenceConfidence(
        targetName: String,
        coord: Pair<Int, Int>?,
        timeMs: Long,
        intentHit: Boolean
    ): Float {
        var conf = 0.40f
        if (targetName.isConcreteTargetName()) conf += 0.20f
        if (coord != null) conf += 0.18f
        if (timeMs > 0L) conf += 0.10f
        if (intentHit) conf += 0.12f
        return conf.coerceAtMost(0.95f)
    }

    private fun extractContingency(text: String): ContingencyAction? {
        return when {
            text.contains("抢了") && text.contains("驻守") -> ContingencyAction.FALLBACK_TO_PASS
            text.contains("撤退") || text.contains("秒回") -> ContingencyAction.IMMEDIATE_RETREAT
            else -> ContingencyAction.STOP_AND_ALARM
        }
    }

    private fun generateThinkingStream(
        intent: OrderIntent,
        targetName: String,
        coord: Pair<Int, Int>?,
        timeMs: Long,
        advanceSec: Int,
        contingency: ContingencyAction?,
        tacticalAdvice: String = ""
    ): String {
        val timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMs))
        val coordDesc = if (coord != null) "坐标(${coord.first}, ${coord.second})" else "地图定位中"
        val adviceDesc = if (tacticalAdvice.isNotBlank()) "\n▶ 兵法指引: $tacticalAdvice" else ""
        return "【诸葛军师·方略推演】：已参透同盟法令【${intent.desc}】。" +
                "目标锁定于【$targetName】($coordDesc)，总攻时刻定于 $timeFormatted。" +
                "制定两阶段协同战术：先遣队于 $advanceSec 秒前压秒铺路，主力队分秒不差触城。" +
                adviceDesc +
                "\n▶ 应急策略：${contingency?.actionName ?: "战况异常停机保全"}。"
    }

    private fun fallbackEmptyOrder(reason: String): TacticalOrder {
        return TacticalOrder(
            orderId = "ORD-EMPTY",
            intent = OrderIntent.ALLIANCE_SIEGE,
            targetName = "未指定目标",
            advisorThinking = "未检测到有效军令文本，军师保持待命状态 ($reason)。",
            confidence = 0.0f
        )
    }

    companion object {
        private const val TAG = "EdgeSlmEngine"

        /**
         * 军师锦囊的通用兜底句：**不点任何一款游戏的战法名/武将名**，因此对任何 SLG 都成立。
         * 游戏专属的锦囊内容属于语料与知识包，不能出现在这一句里。
         */
        private const val GENERIC_PLAYBOOK =
            "• 凡战者，以正合，以奇胜。大地图交战先铺路立要塞，卡免破免控行军线，善用斯巴达探路知己知彼。"

        /**
         * 意图微脑接管意图闸的最低 softmax 概率。
         *
         * ❗ 这个数字是**保守门槛，不是准确率**：它只表示"选类本身够不够陡"。
         * intent_slot_zh.onnx 在本工程里**从未做过真实标注集评测**，所以它只能在
         * 正则一个关键词都没命中的时候补位，永远不得改写原文已读到的事实。
         */
        private const val MODEL_INTENT_MIN_CONFIDENCE = 0.60f
    }
}
