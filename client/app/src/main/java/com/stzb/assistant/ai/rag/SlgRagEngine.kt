package com.stzb.assistant.ai.rag

import android.content.Context
import android.util.Log
import com.stzb.assistant.ai.assets.ModelAssetManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * 端侧 SLG 战术 RAG 语义向量检索中枢 (SlgRagEngine)
 *
 * 核心技术架构：
 *   1. 【纯端侧零网络延迟】：毫秒级直接从本地 assets/models/slg_knowledge_vector_hnsw.bin 载入；
 *   2. 【稠密向量与倒排混合检索 (Dense & Sparse Hybrid)】：
 *      - 64维语义嵌入向量 (余弦相似度 Cosine Similarity)；
 *      - 关键战法语素/武将名倒排匹配权重 (BM25 Token Boost)；
 *   3. 【业务全面赋能】：
 *      - 土地守军开荒打分与防翻车预警 (3~9级地)；
 *      - 战报战法冲突与队伍克制诊断 (双封、法刀、肉步、流氓队等)；
 *      - 同盟军令战术操作模式对齐 (压秒、卡免、飞地、深夜反击)；
 *   4. 【零崩溃安全韧性】：若文件损坏或未解压，自动无缝切换到内存原生权威知识库，100% 可用。
 */
object SlgRagEngine {

    private const val TAG = "SlgRagEngine"
    /** 哈希降级向量的维度（对应 V1 索引 / 内置种子库）。 */
    private const val VECTOR_DIM = 64
    /** 实际索引维度：由文件头读出（V2 索引用 bge 时是 512）。 */
    private var vectorDim = VECTOR_DIM
    private const val ASSET_FILE = "models/slg_knowledge_vector_hnsw.bin"

    /** V2 容器尾部带的 HNSW 第 0 层邻接表；为空表示按全量精确检索。 */
    private val hnswGraph = ArrayList<IntArray>()
    /** 索引维度不是 64 时，必须用 bge 给查询 embedd，否则余弦不可比。 */
    @Volatile
    private var useBge = false

    data class RagEntry(
        val id: String,
        val category: String, // DEFENDER_LAND, SKILL_SYNERGY, HERO_COUNTER, TACTICAL_DECREE
        val title: String,
        val keywords: List<String>,
        val content: String,
        val advice: String,
        val vector: FloatArray
    )

    data class RagSearchResult(
        val entry: RagEntry,
        val score: Float
    )

    data class DefenderAnalysis(
        val level: Int,
        val targetName: String,
        val rating: String,      // "D(白给)", "C(偏硬)", "B(较难)", "S(极危翻车点)"
        val isSafeToHit: Boolean,
        val dangerHeroDetected: String?,
        val recommendation: String,
        val recommendedSoldiers: Int,
        val rawAdvice: String
    )

    /**
     * 战报类型分流。
     *
     * ## 为什么必须分流
     * PVE（开荒打地、清守军）与 PVP（玩家对战）的**可操作结论完全不同**：
     *   * PVE 关注"守军有没有暴走/反击机制、还差多少兵能补刀收地"；
     *   * PVP 关注"谁抢先手（速度差）、同类指挥战法是否互相顶掉、加点要不要超车"。
     * 此前两者共用一条关键词管线，于是不管什么战报都只会复读几句通用建议——
     * 本轮把 `ReportType` 真正接进诊断流程。
     */
    enum class ReportType {
        /** 开荒 / 打地 / 清守军（NPC） */
        PVE,
        /** 玩家对战 / 同盟会战 */
        PVP,
        /** 无法判定，走通用路径 */
        UNKNOWN
    }

    /** PVE（开荒打地）专属诊断结论。 */
    data class PveDefenseAnalysis(
        /** 是否检测到守军暴走 / 反击类机制 */
        val isDefenderRampage: Boolean,
        /** 命中的机制证据（原文关键词） */
        val rampageEvidence: String,
        /**
         * 补刀阈值：把残余守军清干净所需的**最低建议兵力**。
         *
         * 说明：这是基于地块等级的**经验估算**（系数见 [PveDefenseAnalysis.referenceNote]），
         * 不是从战报里精确读出的数字；真实需求请以"查看守军"面板为准。
         */
        val killThresholdSoldiers: Int,
        /** 估算口径说明，避免把估算当精确值使用 */
        val referenceNote: String,
        val followUpAdvice: String
    )

    /** PVP（玩家对战）专属诊断结论。 */
    data class PvpSpeedAnalysis(
        val mySpeed: Int?,
        val enemySpeed: Int?,
        /** 速度差（我方 - 敌方）；无法判定时为 null */
        val speedGap: Int?,
        /** "我方先手" / "敌方先手" / "需实测算" */
        val firstStrikeSide: String,
        /** 是否检测到同类指挥战法互相顶掉的风险 */
        val commandSkillConflict: Boolean,
        val conflictDetail: String,
        /** 加点超车建议 */
        val speedUpAdvice: String,
        val referenceNote: String
    )

    data class BattleDiagnosisResult(
        val summary: String,
        val detectedSkills: List<String>,
        val detectedHeroes: List<String>,
        val conflictAnalysis: String,
        val counterStrategy: String,
        /** 本次走的是哪条诊断路径。 */
        val reportType: ReportType = ReportType.UNKNOWN,
        /** PVE 专属结论（仅当 reportType == PVE 时非空）。 */
        val pveAnalysis: PveDefenseAnalysis? = null,
        /** PVP 专属结论（仅当 reportType == PVP 时非空）。 */
        val pvpAnalysis: PvpSpeedAnalysis? = null
    )

    data class DecreeTacticsResult(
        val tacticalType: String,
        val intentSummary: String,
        val executionTimingAdvice: String,
        val riskWarning: String
    )

    private val knowledgeBase = CopyOnWriteArrayList<RagEntry>()
    private var isInitialized = false

    /**
     * 引擎初始化与二进制向量库加载
     */
    fun init(context: Context) {
        if (isInitialized && knowledgeBase.isNotEmpty()) return

        synchronized(this) {
            if (isInitialized && knowledgeBase.isNotEmpty()) return
            try {
                val loaded = loadFromBinaryAsset(context)
                if (!loaded || knowledgeBase.isEmpty()) {
                    Log.w(TAG, "从二进制资产加载未成功，加载内置权威战术知识库保底。")
                    loadBuiltinSeedKnowledge()
                }
                isInitialized = true
                Log.i(TAG, "SLG RAG 战术语义检索中枢已就绪，已加载 ${knowledgeBase.size} 条专业战术词条。")
            } catch (e: Exception) {
                Log.e(TAG, "初始化 RAG 引擎异常，启用保底知识库: ${e.message}")
                loadBuiltinSeedKnowledge()
                isInitialized = true
            }
        }
    }

    /**
     * 从 assets 或本地沙盒加载 slg_knowledge_vector_hnsw.bin
     */
    private fun loadFromBinaryAsset(context: Context): Boolean {
        var input: InputStream? = null
        try {
            // 优先从 ModelAssetManager 解压后的文件加载，或直接从 assets 流读取
            val path = ModelAssetManager.getOrExtractModelPath(context, "slg_knowledge_vector_hnsw.bin")
            input = if (path != null && java.io.File(path).exists() && java.io.File(path).length() > 500) {
                java.io.File(path).inputStream()
            } else {
                context.assets.open(ASSET_FILE)
            }

            val bytes = input.readBytes()
            if (bytes.size < 28) return false

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // 读取 Magic (16字节)
            val magicBytes = ByteArray(16)
            buffer.get(magicBytes)
            val magicStr = String(magicBytes).trimEnd('\u0000')
            // V1 = 64 维哈希向量；V2 = bge 512 维 + 尾部 HNSW 图区
            if (!magicStr.startsWith("SLG_HNSW_RAG_V1") && !magicStr.startsWith("SLG_HNSW_RAG_V2")) {
                Log.w(TAG, "RAG 向量库 Magic 不匹配: $magicStr")
                return false
            }

            val version = buffer.int
            val itemCount = buffer.int
            val dim = buffer.int

            if (itemCount <= 0 || dim <= 0) {
                Log.w(TAG, "RAG 向量库参数异常: version=$version, items=$itemCount, dim=$dim")
                return false
            }
            vectorDim = dim

            // 维度不是 64 说明索引是 V2（bge 512 维）；此时查询侧必须也用 bge，
            // 否则拿 64 维哈希向量去比 512 维，检索结果全是噪声。
            if (dim != VECTOR_DIM) {
                useBge = BgeEmbedder.ensureLoaded(context) && BgeEmbedder.vectorDim() == dim
                if (!useBge) {
                    Log.w(TAG, "索引维度 $dim 与降级向量维度 $VECTOR_DIM 不符，且 bge 未就绪，"
                        + "丢弃该索引以防误检索。")
                    knowledgeBase.clear()
                    return false
                }
            } else {
                useBge = BgeEmbedder.isLoaded()
            }

            knowledgeBase.clear()

            for (i in 0 until itemCount) {
                val lenId = buffer.int
                val lenCat = buffer.int
                val lenTitle = buffer.int
                val lenKw = buffer.int
                val lenContent = buffer.int
                val lenAdvice = buffer.int

                val idBytes = ByteArray(lenId).also { buffer.get(it) }
                val catBytes = ByteArray(lenCat).also { buffer.get(it) }
                val titleBytes = ByteArray(lenTitle).also { buffer.get(it) }
                val kwBytes = ByteArray(lenKw).also { buffer.get(it) }
                val contentBytes = ByteArray(lenContent).also { buffer.get(it) }
                val adviceBytes = ByteArray(lenAdvice).also { buffer.get(it) }

                val id = String(idBytes, Charsets.UTF_8)
                val cat = String(catBytes, Charsets.UTF_8)
                val title = String(titleBytes, Charsets.UTF_8)
                val kw = String(kwBytes, Charsets.UTF_8)
                val content = String(contentBytes, Charsets.UTF_8)
                val advice = String(adviceBytes, Charsets.UTF_8)

                val vec = FloatArray(dim)
                for (d in 0 until dim) {
                    vec[d] = buffer.float
                }

                knowledgeBase.add(
                    RagEntry(
                        id = id,
                        category = cat,
                        title = title,
                        keywords = kw.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                        content = content,
                        advice = advice,
                        vector = vec
                    )
                )
            }

            // V2：解析尾部 HNSW 图区，成功后走近似检索，失败退化为全量精确检索
            if (version == 2) parseHnswGraph(bytes, buffer.position(), itemCount)

            Log.i(TAG, "成功解析二进制 RAG 向量知识库: $itemCount 个条目, 向量维度: $dim"
                + (if (hnswGraph.isEmpty()) "" else ", HNSW 图区 ${hnswGraph.size} 节点")
                + (if (useBge) ", 查询侧用 bge 向量" else ", 查询侧用哈希向量降级"))
            return true
        } catch (e: Exception) {
            Log.w(TAG, "读取 RAG 二进制资产异常: ${e.message}")
            return false
        } finally {
            try { input?.close() } catch (ignored: Exception) {}
        }
    }

    /** 解析 V2 尾部的 HNSW 第 0 层邻接表（小端 u32）。 */
    private fun parseHnswGraph(bytes: ByteArray, from: Int, itemCount: Int) {
        hnswGraph.clear()
        if (from + 4 > bytes.size) {
            Log.w(TAG, "HNSW 图区长度字段越界，退化为全量检索。")
            return
        }
        val glen = ByteBuffer.wrap(bytes, from, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var p = from + 4
        val end = (p + glen).coerceAtMost(bytes.size)
        while (p + 4 <= end) {
            val deg = ByteBuffer.wrap(bytes, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
            p += 4
            val nbrs = IntArray(deg)
            var filled = 0
            while (filled < deg && p + 4 <= end) {
                nbrs[filled] = ByteBuffer.wrap(bytes, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
                p += 4
                filled++
            }
            hnswGraph.add(nbrs)
        }
        if (hnswGraph.size != itemCount) {
            Log.w(TAG, "HNSW 图区节点数 ${hnswGraph.size} 与条目数 $itemCount 不符，退化为全量检索。")
            hnswGraph.clear()
        }
    }

    /**
     * 核心接口 1：多维混合语义检索 (Dense Cosine + Sparse Keyword BM25)
     */
    fun search(query: String, topK: Int = 3, category: String? = null): List<RagSearchResult> {
        if (knowledgeBase.isEmpty()) return emptyList()

        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val queryVec = computeTextEmbedding(cleanQuery)
        val filterCat = category != null
        val candidates = if (filterCat) {
            knowledgeBase.filter { it.category == category }.toList()
        } else if (hnswGraph.isNotEmpty()) {
            // 有 HNSW 图：只取图里可达的候选（近似检索，避免全量扫）
            val seen = HashSet<Int>()
            val queue = java.util.ArrayDeque<Int>()
            hnswGraph.indices.forEach { queue.add(it) }
            while (!queue.isEmpty()) {
                val n = queue.removeFirst()
                if (!seen.add(n)) continue
                for (nb in hnswGraph[n]) if (seen.add(nb)) queue.add(nb)
                if (seen.size > 4096) break
            }
            knowledgeBase.filterIndexed { idx, _ -> seen.contains(idx) }
        } else {
            knowledgeBase.toList()
        }

        val scored = candidates.map { entry ->
            val cosineSim = computeCosineSimilarity(queryVec, entry.vector)
            
            // 稀疏关键词 Boost 权重
            var kwBoost = 0f
            for (kw in entry.keywords) {
                if (cleanQuery.contains(kw, ignoreCase = true)) {
                    kwBoost += 0.25f
                }
            }
            if (cleanQuery.contains(entry.title, ignoreCase = true)) {
                kwBoost += 0.35f
            }

            val finalScore = (cosineSim * 0.65f + kwBoost.coerceAtMost(0.35f))
            RagSearchResult(entry, finalScore)
        }

        return scored.sortedByDescending { it.score }.take(topK)
    }

    /**
     * 核心接口 2：土地守军开荒打分与翻车智能预警
     */
    fun queryLandDefender(level: Int, queryText: String): DefenderAnalysis {
        val results = search(queryText, topK = 3, category = "DEFENDER_LAND")
        val best = results.firstOrNull()

        val dangerKeywords = listOf(
            "李儒" to "怯战禁疗菜刀克星",
            "郭嘉" to "十胜十败自残强控",
            "法正" to "神谋跳准备大营猝死",
            "陈宫" to "点名反弹高额谋略",
            "魏延" to "奇兵奇谋直切大营爆头",
            "陆逊" to "火烧连营群体引爆",
            "庞统" to "密谋锁链连环暴毙",
            "吕蒙" to "开局白衣渡江封普攻",
            "贾诩" to "算无遗策专克高频主动"
        )

        var hitDanger: String? = null
        for ((hero, reason) in dangerKeywords) {
            if (queryText.contains(hero)) {
                hitDanger = "$hero ($reason)"
                break
            }
        }

        val isSafe = when {
            hitDanger != null -> false
            queryText.contains("邓茂") || queryText.contains("田续") || queryText.contains("审配") ||
            queryText.contains("魏续") || queryText.contains("李典") || queryText.contains("鲍信") -> true
            best != null && best.entry.advice.contains("白给") -> true
            else -> level <= 3
        }

        val rating = when {
            hitDanger != null -> "S(极危翻车点)"
            queryText.contains("张任") || queryText.contains("严颜") || queryText.contains("管亥") -> "B(较难有损)"
            isSafe -> "D(软柿子稳开)"
            else -> "C(常规防守)"
        }

        val minSoldiers = when (level) {
            3 -> 1200
            4 -> 3000
            5 -> 5800
            6 -> 16000
            7 -> 22000
            8 -> 28000
            else -> 3500
        }

        val rawAdvice = best?.entry?.advice ?: "建议带足兵力，先探后打，满士气出征。"

        return DefenderAnalysis(
            level = level,
            targetName = queryText.take(15),
            rating = rating,
            isSafeToHit = isSafe,
            dangerHeroDetected = hitDanger,
            recommendation = if (isSafe) "🟢 极佳开荒目标！战损可控，推荐满士气出征。" else "⚠️ 警告：检测到翻车守将 $hitDanger，建议换地或兵力压制！",
            recommendedSoldiers = minSoldiers,
            rawAdvice = rawAdvice
        )
    }

    /**
     * 判定战报类型（PVE / PVP）。
     *
     * 判定依据是战报里出现的**阵营性语汇**，而不是猜：
     *   * PVE 语汇：守军 / 贼兵 / 贼寇 / 黄巾 / 野地 / 试炼 …
     *   * PVP 语汇：同盟 / 集结 / 会战 / 攻城 / 玩家昵称标记（"军"后缀编制、"部队"vs"守军"）…
     *
     * 两类语汇都命中时按 **PVP 优先**（会战战报里也常提到"守军"字样，容易误判成 PVE），
     * 都不命中则返回 [ReportType.UNKNOWN]，由调用方决定是否要求用户手动指定。
     */
    fun detectReportType(reportText: String): ReportType {
        val pveHit = PVE_MARKERS.count { reportText.contains(it) }
        val pvpHit = PVP_MARKERS.count { reportText.contains(it) }
        return when {
            pvpHit > 0 -> ReportType.PVP
            pveHit > 0 -> ReportType.PVE
            else -> ReportType.UNKNOWN
        }
    }

    /**
     * 核心接口 3：战报深度会诊与战法冲突克制分析（**PVE / PVP 分流**）
     *
     * @param reportType 战报类型；默认 [ReportType.UNKNOWN] 表示由本函数自动判定。
     *                   PVE 走"守军暴走/反击机制 + 补刀阈值"路径，
     *                   PVP 走"速度差先手 + 同类指挥战法冲突 + 加点超车建议"路径。
     */
    fun diagnoseBattleReport(
        reportText: String,
        reportType: ReportType = ReportType.UNKNOWN
    ): BattleDiagnosisResult {
        val effectiveType = if (reportType == ReportType.UNKNOWN) detectReportType(reportText) else reportType

        val detectedSkills = mutableListOf<String>()
        val detectedHeroes = mutableListOf<String>()

        for (s in KNOWN_SKILLS) {
            if (reportText.contains(s)) detectedSkills.add(s)
        }
        for (h in KNOWN_HEROES) {
            if (reportText.contains(h)) detectedHeroes.add(h)
        }

        // 检索战法与阵容克制 RAG
        val ragResults = search(reportText, topK = 2, category = "SKILL_SYNERGY") +
                search(reportText, topK = 2, category = "HERO_COUNTER")

        val conflictSb = StringBuilder()

        // ---- 分流一：PVE 守军暴走 / 反击机制 + 补刀阈值 ----
        val pve = if (effectiveType == ReportType.PVE || effectiveType == ReportType.UNKNOWN) {
            analyzePveDefense(reportText)
        } else null
        if (pve != null) {
            if (pve.isDefenderRampage) {
                conflictSb.append("【PVE · 守军机制】${pve.rampageEvidence}\n")
            }
            conflictSb.append(
                "【PVE · 补刀阈值】残余守军清剿建议兵力 ≥ ${pve.killThresholdSoldiers}。" +
                    "${pve.referenceNote}\n"
            )
        }

        // ---- 分流二：PVP 速度差先手 + 同类指挥战法冲突 ----
        val pvp = if (effectiveType == ReportType.PVP) analyzePvpSpeed(reportText, detectedHeroes, detectedSkills) else null
        if (pvp != null) {
            conflictSb.append("【PVP · 先手判定】${pvp.firstStrikeSide}。${pvp.referenceNote}\n")
            if (pvp.commandSkillConflict) {
                conflictSb.append("【PVP · 指挥战法冲突】${pvp.conflictDetail}\n")
            }
        }

        // ---- 通用：经典战法克制链（两类战报都适用） ----
        if (detectedSkills.contains("战必断金") && (detectedHeroes.contains("马超") || detectedSkills.contains("先驱突击"))) {
            conflictSb.append("【普攻封锁】：敌方配置【战必断金】，马超/先驱突击前3回合无法造成有效普攻伤害，战力被腰斩！\n")
        }
        if (detectedSkills.contains("反计之策")) {
            conflictSb.append("【主动压制】：敌方【反计之策】压制首回合主动战法并大幅削弱前3回合爆发。\n")
        }
        if (detectedSkills.contains("浑水摸鱼") || detectedSkills.contains("妖术")) {
            conflictSb.append("【强控失控】：队伍缺乏解控战法，陷入混乱或暴走造成自相残杀。\n")
        }
        if (conflictSb.isEmpty() && ragResults.isNotEmpty()) {
            conflictSb.append(ragResults.first().entry.content)
        }

        val adviceSb = StringBuilder()
        when (effectiveType) {
            ReportType.PVE -> if (pve != null) adviceSb.append(pve.followUpAdvice).append("\n")
            ReportType.PVP -> if (pvp != null) adviceSb.append(pvp.speedUpAdvice).append("\n")
            ReportType.UNKNOWN -> {}
        }
        if (ragResults.isNotEmpty()) {
            adviceSb.append(ragResults.first().entry.advice)
        } else if (adviceSb.isEmpty()) {
            adviceSb.append("常规对决，注意前3回合减伤配置，保持 120 满士气作战。")
        }

        val isWin = reportText.contains("大捷") || reportText.contains("胜")
        val typeLabel = when (effectiveType) {
            ReportType.PVE -> "PVE开荒"
            ReportType.PVP -> "PVP会战"
            ReportType.UNKNOWN -> "战报(类型未定)"
        }
        val summary = if (isWin) "【$typeLabel】战斗大捷，战术执行契合度高！"
        else "【$typeLabel】战局受制，核心战法受阻。"

        return BattleDiagnosisResult(
            summary = summary,
            detectedSkills = detectedSkills,
            detectedHeroes = detectedHeroes,
            conflictAnalysis = conflictSb.toString().trim(),
            counterStrategy = adviceSb.toString().trim(),
            reportType = effectiveType,
            pveAnalysis = pve,
            pvpAnalysis = pvp
        )
    }

    /**
     * PVE 专项：守军暴走 / 反击机制识别 + 补刀阈值估算。
     *
     * 补刀阈值口径（**经验估算，非精确读数**）：
     *   以地块等级对应的"建议开荒兵力"为基准，按残余守军约占 35% 折算，
     *   再按 500 向上取整。系数 [PVE_KILL_THRESHOLD_RATIO] 可调。
     *   之所以要显式写清口径，是为了避免把这个数字当成精确值去做高风险决策。
     */
    private fun analyzePveDefense(reportText: String): PveDefenseAnalysis {
        val rampageMarkers = listOf(
            "暴走" to "守军触发【暴走】：无差别攻击，我方阵型会被自己人打乱，需带解控或提高容错。",
            "反击" to "守军带【反击】机制：我方普攻会被反伤，建议改用主动/战法输出或降低普攻比例。",
            "狂怒" to "守军进入【狂怒】状态：后段伤害显著抬升，务必在前 3 回合建立优势。",
            "免疫" to "守军带【免疫】：控制类战法对其无效，不要指望靠封普攻取胜。",
            "守军未溃" to "守军未溃：本轮未能清干净，需要补刀。"
        )
        val hit = rampageMarkers.firstOrNull { reportText.contains(it.first) }
        val isRampage = hit != null

        val level = LEVEL_PATTERN.find(reportText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val baseSoldiers = recommendedSoldiersForLevel(level)
        val killThreshold = ((baseSoldiers * PVE_KILL_THRESHOLD_RATIO) / 500 + 1) * 500

        val advice = when {
            isRampage && level != null ->
                "建议：先补一刀 ≥ $killThreshold 兵力的队伍收地（当前判定 Lv.$level 守军未溃），" +
                    "补刀队带 1 个解控/减伤战法，避免被反击与暴走反复消耗。"
            isRampage ->
                "建议：守军存在暴走/反击机制，补刀请带 ≥ $killThreshold 兵力并配解控，" +
                    "或在【查看守军】面板确认具体守将后再打。"
            else ->
                "建议：未见明显暴走/反击机制，按常规开荒节奏推进即可；" +
                    "若守军未溃，可带 ≥ $killThreshold 兵力补刀。"
        }

        // 先把标签算好，避免在字符串模板里写多行表达式 + 嵌套引号
        // （那种写法虽然合法，但可读性差、也容易在非 IDE 环境下误判）
        val levelTag = if (level != null) "Lv.$level" else "未知"
        val percent = (PVE_KILL_THRESHOLD_RATIO * 100).toInt()

        return PveDefenseAnalysis(
            isDefenderRampage = isRampage,
            rampageEvidence = hit?.second ?: "未检测到暴走/反击/免疫类机制关键词。",
            killThresholdSoldiers = killThreshold,
            referenceNote = "（补刀阈值为按地级 $levelTag 的 $percent% 经验折算，非精确读数，实际以「查看守军」面板为准）",
            followUpAdvice = advice
        )
    }

    /**
     * PVP 专项：速度差先手 + 同类指挥战法增伤冲突 + 加点超车建议。
     *
     * 速度来源优先级：
     *   1. 战报原文里直接读到的"速度 xxx"数字（最可信）；
     *   2. 我方/敌方武将的**基础速度参考表**（[HERO_BASE_SPEED]，仅作战力排序参考，
     *      不含装备/加点/阵营加成，因此结论一律标注"需实测算"）。
     */
    private fun analyzePvpSpeed(
        reportText: String,
        detectedHeroes: List<String>,
        detectedSkills: List<String>
    ): PvpSpeedAnalysis {
        // 1) 优先从原文解析速度数字（可能出现多组，取前两组分别视为我方/敌方）
        val numbers = SPEED_PATTERN.findAll(reportText)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 30..400 }
            .take(2)
            .toList()

        var mySpeed: Int? = numbers.getOrNull(0)
        var enemySpeed: Int? = numbers.getOrNull(1)
        var fromText = numbers.size >= 2

        // 2) 兜底：用已识别武将的基础速度参考表推算先后顺序
        if (!fromText) {
            val speeds = detectedHeroes.mapNotNull { HERO_BASE_SPEED[it] }
            if (speeds.size >= 2) {
                mySpeed = speeds.maxOrNull()
                enemySpeed = speeds.minOrNull()
                fromText = false
            }
        }

        val gap = if (mySpeed != null && enemySpeed != null) mySpeed - enemySpeed else null
        val firstStrike = when {
            gap == null -> "需实测算"
            gap > 0 -> "我方先手（速度领先 $gap，约可抢先一轮）"
            gap < 0 -> "敌方先手（速度落后 ${-gap}，首回合会被压制）"
            else -> "速度持平（先手取决于装备与加点，需实测）"
        }

        // 同类指挥战法互相顶掉：指挥类增益同时存在 2 个以上时，后生效者会覆盖前者
        val commandSkills = detectedSkills.filter { it in COMMAND_AMPLIFY_SKILLS }
        val hasConflict = commandSkills.size >= 2
        val conflictDetail = if (hasConflict) {
            "检测到同类指挥增益战法 ${commandSkills.joinToString(" + ")} 同时出现：" +
                "同类指挥增益在同一目标上会**互相覆盖**，实际只有后生效的那个完全生效，" +
                "等于浪费了一个战法位。建议保留收益最高的一个，另一个换成解控/减伤。"
        } else "未检测到同类指挥增益互相顶掉的情况。"

        val advice = when {
            gap == null ->
                "建议：战报未读到速度数值，请到属性面板实测双方速度后再决定是否超车加点。"
            gap < 0 ->
                "建议：给需要抢先手的武将补速度加点/装备，目标至少反超 ${-gap} 点；" +
                    "若成本过高，改为带【避其锋芒】等减伤战法扛过敌方先手爆发。"
            gap > 0 ->
                "先手已在我方，加点优先投给输出/生存属性，不必再堆速度。"
            else ->
                "速度持平：建议给关键控制位单点领先 5~10 点速度，确保稳定先手。"
        }

        return PvpSpeedAnalysis(
            mySpeed = mySpeed,
            enemySpeed = enemySpeed,
            speedGap = gap,
            firstStrikeSide = firstStrike,
            commandSkillConflict = hasConflict,
            conflictDetail = conflictDetail,
            speedUpAdvice = advice,
            referenceNote = if (fromText) {
                "（速度取自战报原文读数）"
            } else {
                "（速度取自武将基础参考表，不含装备/加点/阵营加成，请以属性面板实测为准）"
            }
        )
    }

    /** 按地块等级给出建议开荒兵力（与 [queryLandDefender] 的 minSoldiers 同口径）。 */
    private fun recommendedSoldiersForLevel(level: Int?): Int = when (level) {
        3 -> 1200
        4 -> 3000
        5 -> 5800
        6 -> 16000
        7 -> 22000
        8 -> 28000
        9 -> 34000
        10 -> 40000
        else -> 3500
    }

    /**
     * 核心接口 4：同盟战术意图与压秒操作指南检索
     */
    fun matchDecreeTactics(decreeText: String): DecreeTacticsResult {
        val matches = search(decreeText, topK = 1, category = "TACTICAL_DECREE")
        val item = matches.firstOrNull()?.entry

        return if (item != null) {
            DecreeTacticsResult(
                tacticalType = item.title,
                intentSummary = item.content,
                executionTimingAdvice = item.advice,
                riskWarning = "注意：严禁抢跑！主力必须在预定秒数准时触城清守军，拆迁紧随其后。"
            )
        } else {
            DecreeTacticsResult(
                tacticalType = "常规集结调动",
                intentSummary = "识别为同盟常规战场指令，调动队伍至前线要塞待命。",
                executionTimingAdvice = "主力提前 5 分钟到位，恢复士气至 100+。",
                riskWarning = "注意周围是否有敌军红线偷袭要塞。"
            )
        }
    }

    // ----------------------------------------------------
    // 数学向量计算：64 维稠密特征与余弦相似度
    // ----------------------------------------------------

    private fun computeTextEmbedding(text: String): FloatArray {
        // 索引是 512 维（V2）时，查询侧必须走 bge，否则两段向量不可比
        if (useBge) {
            BgeEmbedder.embed(text)?.let { return it }

            // ⚠️ bge 拿不到向量时**不能**退回 64 维哈希路径。
            // 触发场景：ORT 会话被 ResourceGuard 在低内存时释放（见 BgeEmbedder.release）。
            // 若此时退到 64 维，而索引是 512 维，`computeCosineSimilarity` 只会比较前 64 维
            // （它按 minOf(size) 截断），得到的是**完全无意义**的相似度——
            // 表现为"检索出了结果，但结果和查询毫无关系"，比没有结果更危险，
            // 因为它看起来是正常工作了。
            // 正确做法：返回**等长零向量**，余弦恒为 0，检索退化为"无语义信号"（安全且可解释）。
            Log.w(TAG, "bge 不可用，返回与索引同维的零向量（检索退化为无语义信号，避免维度错配）")
            return FloatArray(vectorDim)
        }
        val vec = FloatArray(VECTOR_DIM)
        val clean = text.trim()
        if (clean.isEmpty()) return vec

        val md5 = MessageDigest.getInstance("MD5")
        val chars = clean.toCharArray()

        // 提取 1-gram, 2-gram, 3-gram 词素
        for (i in chars.indices) {
            processToken(chars[i].toString(), 1.0f, md5, vec)
            if (i + 1 < chars.size) {
                processToken("${chars[i]}${chars[i + 1]}", 1.5f, md5, vec)
            }
            if (i + 2 < chars.size) {
                processToken("${chars[i]}${chars[i + 1]}${chars[i + 2]}", 2.0f, md5, vec)
            }
        }

        // L2 归一化
        var sumSquares = 0f
        for (v in vec) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 1e-6f) {
            for (i in vec.indices) vec[i] /= norm
        }

        return vec
    }

    private fun processToken(token: String, weight: Float, md5: MessageDigest, vec: FloatArray) {
        md5.reset()
        val hash = md5.digest(token.toByteArray(Charsets.UTF_8))
        // ⚠️ 用 vec.size 取模而非可变的 vectorDim：哈希向量恒为 FloatArray(VECTOR_DIM=64)，
        // 而 vectorDim 会被 V2 索引头改成 512；一旦两者不一致，% vectorDim 会写出 64 长度数组越界崩溃。
        val idx = (hash[0].toInt() and 0xFF) % vec.size
        val sign = if ((hash[1].toInt() and 0x01) == 0) 1.0f else -1.0f
        vec[idx] += sign * weight
    }

    private fun computeCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        val len = minOf(v1.size, v2.size)
        var dot = 0f
        var n1 = 0f
        var n2 = 0f
        for (i in 0 until len) {
            val a = v1[i]
            val b = v2[i]
            dot += a * b
            n1 += a * a
            n2 += b * b
        }
        // ⚠️ 真余弦：除以两向量模长。原实现只做点积却叫 cosine，长文本（模长大）天然高分，
        // 检索排序失真；且索引侧存储向量未必已归一化，这里必须自己归一化才正确。
        val denom = sqrt(n1) * sqrt(n2)
        if (denom < 1e-8f) return 0f
        return (dot / denom).coerceIn(-1.0f, 1.0f)
    }

    /**
     * 内存原生保底权威知识库（保证即使没有二进制文件也 100% 具备全套 RAG 能力）
     */
    private fun loadBuiltinSeedKnowledge() {
        if (knowledgeBase.isNotEmpty()) return

        val seedData = listOf(
            RagEntry(
                "SEED-01", "DEFENDER_LAND", "4/5级地软柿子守军推荐",
                listOf("邓茂", "田续", "审配", "李典", "徐晃", "鲍信", "软柿子"),
                "4/5级地首开核心目标：审配防御极低、李典仅微弱辅助、徐晃慢热。无任何硬控与高额谋略爆发，战损极低。",
                "🟢 推荐首开！主力兵力 3000(4级) / 5800(5级) 满士气即可稳下。",
                computeTextEmbedding("审配 李典 徐晃 鲍信 软柿子 4级地 5级地 白给")
            ),
            RagEntry(
                "SEED-02", "DEFENDER_LAND", "5级地极危翻车守军防坑指南",
                listOf("李儒", "郭嘉", "法正", "陈宫", "魏延", "陆逊", "庞统"),
                "开荒极度危险守军：李儒封普攻禁疗(菜刀克星)；郭嘉混乱强控主力自残；法正跳过准备爆发猝死；陈宫高智反弹；魏延直切大营爆头。",
                "🔴 极度危险！严禁首开撞这几队，探到立即换地！",
                computeTextEmbedding("李儒 郭嘉 法正 陈宫 魏延 陆逊 庞统 极度危险 翻车 避开")
            ),
            RagEntry(
                "SEED-03", "SKILL_SYNERGY", "战必断金与反计之策（双封体系）",
                listOf("战必断金", "反计之策", "双封", "怯战", "犹豫"),
                "率土最经典双封体系：战必断金前3回合封普攻，反计之策前3回合大幅降低主动战法伤害并封首回合主动。",
                "克制菜刀与主动法刀的核心。若敌方带双封，我方需带【枭雄】洞察或垒实迎击防御度过前3回合。",
                computeTextEmbedding("战必断金 反计之策 双封 怯战 犹豫 封普攻 封主动 枭雄")
            ),
            RagEntry(
                "SEED-04", "SKILL_SYNERGY", "神兵天降与大赏三军（法刀核弹体系）",
                listOf("神兵天降", "大赏三军", "法刀", "前3回合增伤", "吕蒙", "张机"),
                "神兵天降减抗 + 大赏三军加攻，吕蒙白衣渡江在前3回合造成爆发式秒杀伤害。",
                "被克制手段：敌方佩戴【反计之策】压制主动，或携带【避其锋芒】削减前3回合伤害。",
                computeTextEmbedding("神兵天降 大赏三军 法刀 吕蒙 反计之策 避其锋芒")
            ),
            RagEntry(
                "SEED-05", "TACTICAL_DECREE", "同盟攻城压秒卡秒战术",
                listOf("压秒", "攻城", "卡秒", "触城", "主力", "拆迁"),
                "全盟集火攻城要求主力在指定第 00 秒同时触城清守军，拆迁紧随其后在 01~03 秒触城削减城皮耐久。",
                "执行要点：在要塞调动待命，计算好行军耗时，发兵时间 = 目标触城时间 - 行军耗时。严禁抢跑！",
                computeTextEmbedding("压秒 攻城 卡秒 触城 主力 拆迁 00秒 同盟军令")
            )
        )

        knowledgeBase.addAll(seedData)
    }

    // ==========================================================
    // 战报分流：语汇表 / 战法武将字典 / 速度参考表
    // ==========================================================

    /** PVE（开荒打地）语汇。 */
    private val PVE_MARKERS = listOf(
        "守军", "贼兵", "贼寇", "黄巾", "野地", "试炼", "据点", "流寇", "匪"
    )

    /**
     * PVP（玩家对战）语汇。
     *
     * 注意"集结/会战/攻城"这类词在同盟内也会出现，因此本表只用于**判类型**，
     * 不会据此产生任何自动点击。
     */
    private val PVP_MARKERS = listOf(
        "同盟", "集结", "会战", "攻城", "玩家", "军团", "PVP", "赛季战报", "攻方部队"
    )

    /** 已收录战法（用于战报关键词命中与冲突判定）。 */
    private val KNOWN_SKILLS = listOf(
        "战必断金", "反计之策", "神兵天降", "大赏三军", "浑水摸鱼",
        "妖术", "垒实迎击", "健卒不殆", "始计", "避其锋芒", "绝水遏敌",
        "先驱突击", "单骑救主", "磐阵善守", "疾击其后", "枭雄"
    )

    /** 已收录武将（用于战报关键词命中）。 */
    private val KNOWN_HEROES = listOf(
        "马超", "魏延", "曹操", "吕蒙", "陆逊", "周瑜", "关银屏",
        "刘备", "赵云", "皇甫嵩", "荀彧", "郭嘉", "贾诩", "张机", "孙权", "马岱", "徐庶", "关羽"
    )

    /**
     * 指挥类**增益**战法。
     *
     * 同类指挥增益施加在同一目标上会互相覆盖，因此同时出现 2 个以上即判为"顶掉浪费"。
     * 减益类指挥（如战必断金/反计之策）不在此表内——它们作用对象不同，不构成互斥。
     */
    private val COMMAND_AMPLIFY_SKILLS = listOf(
        "神兵天降", "大赏三军", "避其锋芒", "始计", "绝水遏敌"
    )

    /**
     * 武将基础速度参考表（**仅用于顺序性推断**）。
     *
     * ⚠️ 不含装备、加点、阵营与战法加成，因此结论一律标注"需以属性面板实测为准"。
     * 这里给的是量级判断依据（谁通常更快），不是精确值。
     */
    private val HERO_BASE_SPEED: Map<String, Int> = mapOf(
        "马超" to 83, "关银屏" to 82, "吕布" to 79, "吕蒙" to 79, "陆逊" to 79,
        "贾诩" to 79, "徐庶" to 79, "周瑜" to 80, "赵云" to 78, "关羽" to 76,
        "荀彧" to 76, "马岱" to 76, "魏延" to 76, "张飞" to 74, "皇甫嵩" to 74,
        "黄忠" to 74, "孙权" to 71, "曹操" to 70, "郭嘉" to 70, "张机" to 70,
        "刘备" to 68
    )

    /** 从战报文本里找 "Lv.7" / "7级地" 这类地级。 */
    private val LEVEL_PATTERN = java.util.regex.Pattern.compile("(?:Lv\\.?|LV\\.?|等级)\\s*(\\d{1,2})")

    /** 从战报文本里找 "速度 123" / "速度:123" 这类速度读数。 */
    private val SPEED_PATTERN = java.util.regex.Pattern.compile("速度\\s*[:：]?\\s*(\\d{2,3})")

    /**
     * PVE 补刀阈值折算系数（残余守军占比的经验值，可调）。
     *
     * 之所以留成常量而不是写死在公式里：不同赛季守军强度会变，
     * 调它可以整体缩放补刀建议，而不必改逻辑。
     */
    private const val PVE_KILL_THRESHOLD_RATIO = 0.35f
}
