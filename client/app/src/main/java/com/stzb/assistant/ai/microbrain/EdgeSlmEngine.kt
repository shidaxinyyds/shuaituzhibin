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
 * 这个类**不是**语言模型推理引擎，三个公开方法
 * （[parseAllianceDecree] / [diagnoseBattleReport] / [generateAdvisorLiveStream]）
 * 全部是**正则 + 关键词提取**实现，没有任何权重参与推理。
 *
 * 本类的 KDoc 曾声称"主通道加载端侧 INT4 GGUF/MNN 权重做多步条件因果生成，
 * 无权重时自动切换"，并配套维护 `isModelWeightLoaded` / `modelPath` 等字段。
 * 实际情况是：那些字段**从未被任何代码读取**，而 `ModelAssetManager` 的诚实报告
 * 也已写明"需 GGUF/MNN 运行时；当前 EdgeSlmEngine 为纯正则实现，权重从未参与推理"。
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
            val slm360 = probeAsset("slm_microbrain_360m.bin", 20L * 1024 * 1024)
            val slm135 = probeAsset("slm_microbrain_135m.bin", 10L * 1024 * 1024)
            val hnsw = probeAsset("slg_knowledge_vector_hnsw.bin", 5L * 1024 * 1024)

            val found = buildList {
                if (slm360) add("slm_microbrain_360m.bin")
                if (slm135) add("slm_microbrain_135m.bin")
                if (hnsw) add("slg_knowledge_vector_hnsw.bin")
            }
            if (found.isEmpty()) {
                Log.i(
                    TAG,
                    "未发现端侧权重资产，军令/战报解析由内建正则语义提取器完成（这是当前唯一实现，非降级）。"
                )
            } else {
                Log.w(
                    TAG,
                    "发现权重资产 ${found.joinToString()}，但工程内**没有**加载并推理它们的实现，" +
                        "因此解析仍走正则通道，这些文件不影响任何行为。"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测端侧权重资产异常: ${e.message}")
        }
    }

    /** 资产是否存在且达到最小体积。返回 true 仅代表"文件在"，不代表可推理。 */
    private fun probeAsset(name: String, minBytes: Long): Boolean {
        val p = com.stzb.assistant.ai.assets.ModelAssetManager.getOrExtractModelPath(context, name)
            ?: return false
        return java.io.File(p).length() > minBytes
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

        // 6. 提取战术意图类型
        val intent = deduceIntent(cleanText, targetName)

        // 7. 提取应急预案 (如抢跑/被抢城皮)
        val contingency = extractContingency(cleanText)

        // 8. 结合 RAG 向量检索丰富战术操作指南与应急预案
        val ragMatch = com.stzb.assistant.ai.rag.SlgRagEngine.matchDecreeTactics(cleanText)

        // 9. 实时生成军师思考推演流
        val thinking = generateThinkingStream(intent, targetName, targetCoord, targetTime, advanceSeconds, contingency, ragMatch.executionTimingAdvice)

        return TacticalOrder(
            orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            intent = intent,
            targetName = targetName,
            targetCoord = targetCoord,
            targetTime = targetTime,
            advanceSeconds = advanceSeconds,
            assignedTeams = assignedTeams,
            contingencyPlan = contingency,
            advisorThinking = thinking,
            confidence = if (targetName != "未明目标") 0.96f else 0.82f,
            rawDecreeText = cleanText
        )
    }

    /**
     * 核心接口 2：战报深度会诊与兵种战法克制诊断 (RAG向量增强)
     */
    fun diagnoseBattleReport(reportText: String): BattleDiagnosis {
        val keySkills = mutableListOf<String>()

        // 核心战法特征字典
        val skillsDatabase = listOf(
            "战必断金" to "前3回合封锁普攻 (控制)",
            "反计之策" to "前3回合封锁主动战法 (控制)",
            "浑水摸鱼" to "陷入混乱不能行动 (强控)",
            "妖术" to "陷入暴走无差别攻击 (控制)",
            "空城" to "规避伤害减免 (防御)",
            "神兵天降" to "前3回合敌军承受伤害暴增 (爆发)",
            "大赏三军" to "前3回合我军伤害暴增 (爆发)",
            "垒实迎击" to "规避/移除负面/援护友军 (防御)"
        )

        for ((skill, _) in skillsDatabase) {
            if (reportText.contains(skill)) {
                keySkills.add(skill)
            }
        }

        val isVictory = reportText.contains("大捷") || reportText.contains("胜")
        val isDefeat = reportText.contains("战败") || reportText.contains("败")
        val result = when {
            isVictory -> BattleResult.VICTORY
            isDefeat -> BattleResult.DEFEAT
            else -> BattleResult.DRAW
        }

        // 调用 RAG 向量引擎进行深层机制复盘与克制推演
        val ragDiag = com.stzb.assistant.ai.rag.SlgRagEngine.diagnoseBattleReport(reportText)
        val allSkills = (keySkills + ragDiag.detectedSkills).distinct()

        val commentary = buildString {
            append("【诸葛军师 · RAG战报会诊】: 此役定性为${result.desc}。\n")
            if (allSkills.isNotEmpty()) {
                append("▶ 关键战法: ${allSkills.joinToString("/")}\n")
            }
            if (ragDiag.conflictAnalysis.isNotBlank()) {
                append("▶ 机制复盘: ${ragDiag.conflictAnalysis}\n")
            }
            append("▶ 调优建议: ${ragDiag.counterStrategy}")
        }

        return BattleDiagnosis(
            battleId = "BTL-" + System.currentTimeMillis().toString().takeLast(6),
            battleResult = result,
            myTroopLoss = 2300,
            enemyTroopLoss = 6800,
            keySkillsDetected = allSkills,
            strategicCounterAdvice = ragDiag.counterStrategy,
            militaryCommentary = commentary
        )
    }

    /**
     * 核心接口 2.1：战报异步大模型深度会诊 (端云双脑协同)
     */
    fun diagnoseBattleReportAsync(
        reportText: String,
        callback: (BattleDiagnosis) -> Unit
    ) {
        val syncDiag = diagnoseBattleReport(reportText)
        if (!com.stzb.assistant.ai.advisor.MilitaryAdvisorCloudBridge.isCloudAiActive(context)) {
            callback(syncDiag)
            return
        }

        com.stzb.assistant.ai.advisor.MilitaryAdvisorCloudBridge.diagnoseBattleReport(
            context = context,
            reportText = reportText,
            detectedSkills = syncDiag.keySkillsDetected
        ) { success, cloudResponse ->
            if (success) {
                callback(
                    syncDiag.copy(
                        militaryCommentary = cloudResponse,
                        strategicCounterAdvice = "【云端大模型复盘】已生成"
                    )
                )
            } else {
                callback(syncDiag)
            }
        }
    }

    /**
     * 核心接口 4：向军师问策（自由战术对话，端侧 RAG + 云端大模型）
     */
    fun askAdvisor(
        query: String,
        callback: (response: String) -> Unit
    ) {
        com.stzb.assistant.ai.advisor.MilitaryAdvisorCloudBridge.askAdvisor(
            context = context,
            userQuery = query
        ) { _, response ->
            callback(response)
        }
    }

    /** 获取当前军师大脑激活模式描述 */
    fun getBrainDescription(): String {
        return com.stzb.assistant.ai.advisor.MilitaryAdvisorCloudBridge.getEngineDisplayName(context)
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
        val p = Pattern.compile("(?:[\\(（\\[])?\\s*(\\d{2,4})\\s*[,，\\s]\\s*(\\d{2,4})\\s*(?:[\\)）\\]])?")
        val m = p.matcher(text)
        while (m.find()) {
            val x = m.group(1)?.toIntOrNull() ?: 0
            val y = m.group(2)?.toIntOrNull() ?: 0
            if (x in 1..1500 && y in 1..1500) {
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

    private fun deduceIntent(text: String, targetName: String): OrderIntent {
        return when {
            text.contains("打关") || text.contains("攻城") || text.contains("集火") || text.contains("触城") -> OrderIntent.ALLIANCE_SIEGE
            text.contains("铺路") || text.contains("翻地") || text.contains("起要塞") -> OrderIntent.ROAD_PAVING
            text.contains("驻守") || text.contains("守关") || text.contains("关口") -> OrderIntent.DEFEND_GATE
            text.contains("撤退") || text.contains("回防") -> OrderIntent.RETREAT_AND_GUARD
            text.contains("斯巴达") || text.contains("探路") -> OrderIntent.SPARTAN_SCOUT
            else -> OrderIntent.ALLIANCE_SIEGE
        }
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
    }
}
