package com.stzb.assistant.ai.rag

import android.content.Context
import android.util.Log
import com.stzb.assistant.ai.assets.ModelAssetManager
import com.stzb.assistant.knowledge.KnowledgeBaseManager
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * 端侧 SLG 战术 RAG 语义向量检索中枢 (SlgRagEngine)
 *
 * 核心技术架构（按当前真实实现写，不写愿景）：
 *   1. 【纯端侧零网络】：从本地 assets/models/slg_knowledge_vector_hnsw.bin 一次性载入内存；
 *   2. 【确定性优先 + 向量兜底的混合检索】：
 *      - 通道 A：关键词/标题在查询里**原样出现**即精确命中，命中即独占结果；
 *      - 通道 B：A 一条未命中时才用 dense 余弦，并卡 [MIN_DENSE_SIMILARITY] 下限，
 *        低于下限返回空（fail-closed，由调用方走"无参考条目"分支）；
 *      - 向量维度**由文件头决定**：V2 = bge-small-zh INT8 的 512 维（真语义），
 *        V1 / 内置种子 = 64 维确定性哈希（只当骨架，没有语义能力）；
 *        查询侧由 [BgeEmbedder] 用同一个模型 embedd，维度不匹配时如实丢索引回落。
 *   3. 【业务全面赋能】：土地守军开荒打分与防翻车预警（3~9 级地）、战报战法冲突与
 *      队伍克制诊断、同盟军令战术操作模式对齐（压秒、卡免、飞地、深夜反击）。
 *
 * ## 检索是"全量精确扫"，不是 ANN（这是决策，不是没做完）
 * 容器尾部带 HNSW 第 0 层图区，本引擎**不读**。理由与阈值由
 * `python tools/rag_bench.py` 现算，结论文档是 `tools/RAG_INDEX_MIGRATION_ASSESSMENT.md`；
 * 一句话：真实候选池只有 11 条（调用一律带 category），扫描乘加只占一次查询侧
 * bge embedd 的 0.03%，图索引省不到时间，只会引入召回损失。
 * 那份文档同时给出了**什么时候必须回头改**的可量化阈值，
 * 以及为什么 sqlite-vec 在这个规模上是净亏（Android 要自带 SQLite 才能加载扩展）。
 *
 * ## 唯一权威
 * 守将名单与兵力数字一律取自 `KnowledgeBaseManager.activeProfile`（可云热更）。
 * 除下面点名的克制链文案之外，本文件**不再持有任何游戏专属名单**：战报分流用的语汇组
 * （[VOCAB_*] 五个组）、武将基础速度（`heroBaseSpeed`）、守军机制解读
 * （`pveMechanicNotes`）全部按组 ID 向知识包取，缺配就如实不判定（见 [vocab]）。
 * 仅剩的率土专属内容是 [SEED_CORPUS_GAME_ID] 名下的三条战法克制链文案
 * （普攻封锁 / 主动压制 / 强控失控，写在 diagnoseBattleReport 里）：
 * 它们以**率土的具体战法名**为触发条件，别的游戏命中不了这些名字，因此天然自锁；
 * 外层再加 [corpusAppliesToActiveGame] 拦一道，语料不属当前游戏时整段不执行。
 * 它们是**语料内容**而不是判定字典，正确归宿是该游戏的语料资产，
 * 这条残留由闸门 tools/check_knowledge_base.py 与 tools/check_multi_game_readiness.py 共同盯着。
 */
object SlgRagEngine {

    private const val TAG = "SlgRagEngine"
    /** 哈希降级向量的维度（对应 V1 索引 / 内置种子库）。 */
    private const val VECTOR_DIM = 64
    /** 实际索引维度：由文件头读出（V2 索引用 bge 时是 512）。 */
    private var vectorDim = VECTOR_DIM
    private const val ASSET_DIR = "models"
    /** 索引文件裸名（唯一权威在 [ModelAssetManager.RAG_INDEX_FILE]，这里只引用不另抄）。 */
    private val INDEX_FILE_NAME = ModelAssetManager.RAG_INDEX_FILE

    /** 索引维度不是 64 时，必须用 bge 给查询 embedd，否则余弦不可比。 */
    @Volatile
    private var useBge = false

    /**
     * 语料/种子的归属游戏（P7 取证实测出来的口子）。
     *
     * V2 容器与 [seedEntries] 讲的全是率土之滨的人与事（郭嘉、战必断金、压秒触城……），
     * 而容器格式里没有任何字段标注"这套语料属于哪款游戏"，`search()` 也就没有
     * 按游戏过滤的机制。后果不是"检索不到"，而是**检索得到**：
     * 切到《三战》后军师会把率土的武将与战法当成三战的答案念给玩家，
     * 语气确定、内容错游戏——比返回空危险，因为它看起来完全在工作。
     *
     * 所以内存里那份语料**必须带主人**（见 [corpusOwnerId]）：不属于当前游戏时整体**拒服**
     * （返回空 + 一条 W 日志），由调用方走"无参考条目"分支；
     * 兵力/名单一类结论不受影响，它们本来就走 [KnowledgeBaseManager]。
     */
    const val SEED_CORPUS_GAME_ID = "stzb"

    /**
     * 当前内存里这份语料属于哪款游戏。
     *
     * 载入索引文件时置为该文件所对应的游戏（索引名本身带游戏标记，见 [assetFileCandidates]），
     * 回落到内置种子时置为 [SEED_CORPUS_GAME_ID]。
     * 之所以不能写死一个常量当权威：换一款游戏、装上它自己的语料资产之后，
     * 硬闸会把这份**本来就该服**的语料也一起拒掉，表现成"接了知识库还是没有军师文案"，
     * 而真相只是那道写死的判断没跟着数据走。
     */
    @Volatile
    var corpusOwnerId: String = SEED_CORPUS_GAME_ID
        private set

    /** 已经为哪个游戏打过"语料拒服"日志，避免每次检索刷一条。 */
    @Volatile
    private var warnedCorpusGameId: String? = null

    private fun corpusAppliesToActiveGame(): Boolean {
        val active = KnowledgeBaseManager.activeProfile.gameId
        if (active == corpusOwnerId) return true
        if (warnedCorpusGameId != active) {
            warnedCorpusGameId = active
            Log.w(TAG, "端侧语料属于 [$corpusOwnerId]，当前激活游戏是 [$active]：语料检索整体停用（返回空），" +
                "否则会把一款游戏的武将与战法当成另一款游戏的答案念给玩家。" +
                "要恢复本游戏的语义检索，请为该游戏准备它自己的语料与向量资产。")
        }
        return false
    }

    // ---------------------------------------------------------------
    // 检索通道 ↔ 语料 category 的**唯一**对照表
    // ---------------------------------------------------------------
    //
    // 这里曾经有过一个看不见的大坑：调用侧查的是 `DEFENDER_LAND` / `SKILL_SYNERGY` /
    // `HERO_COUNTER` / `TACTICAL_DECREE`，而真正入包的 V2 语料用的却是
    // `DEFENDER_SAFE` / `DEFENDER_MODERATE` / `DEFENDER_HARD` / `DEFENDER_AVOID` / `LAND_SIEGE`
    // ——**一个都不重合**。`search()` 的候选池按 category 过滤，于是每条生产检索都拿到
    // 空列表：不报错、不降级，只是"军师永远没有参考条目、雷达永远没有语料文案"。
    // 整条 RAG 通道在真机上是**空转**的，而它在开发机上看起来完全正常
    // （二进制资产加载失败时会回落种子库，种子库恰好用的是旧名）。
    //
    // 所以现在把"通道 → 允许哪些 category"收成一张明表，由 search() 统一解释；
    // 闸门 `python tools/rag_bench.py --contract` 会拿真实 .bin 里的 category 集合来对账：
    // 任何通道解析到 0 条、或某批语料不属于任何通道，当场构建失败。
    const val CHANNEL_DEFENDER = "DEFENDER_LAND"
    const val CHANNEL_SKILL = "SKILL_SYNERGY"
    const val CHANNEL_DECREE = "TACTICAL_DECREE"

    /** 通道名 → 该通道允许检索的语料 category 集合（含历史别名，向后兼容旧 V1 容器）。 */
    val SEARCH_CHANNELS: Map<String, Set<String>> = mapOf(
        CHANNEL_DEFENDER to setOf(
            "DEFENDER_LAND", "DEFENDER_SAFE", "DEFENDER_MODERATE",
            "DEFENDER_HARD", "DEFENDER_AVOID", "LAND_SIEGE"
        ),
        // 战法协同与武将克制在语料里是同一批文案，合成一个通道；
        // 拆成两个只会让 diagnoseBattleReport 拿到两份重复结果。
        CHANNEL_SKILL to setOf("SKILL_SYNERGY", "HERO_COUNTER"),
        CHANNEL_DECREE to setOf("TACTICAL_DECREE")
    )

    data class RagEntry(
        val id: String,
        // 语料实际分类见 SEARCH_CHANNELS：入包的 V2 资产用 DEFENDER_SAFE/MODERATE/HARD/AVOID
        // 与 LAND_SIEGE；内置种子用 DEFENDER_LAND/SKILL_SYNERGY/TACTICAL_DECREE。
        // 调用侧一律传**通道名**，由 search() 展开成允许的 category 集合。
        val category: String,
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

    /** 切游戏钩子是否已注册（保证只注册一次）。 */
    @Volatile
    private var switchHookRegistered = false

    /**
     * 引擎初始化与二进制向量库加载
     */
    fun init(context: Context) {
        // 切游戏要换整份语料池：索引文件名本身就带游戏标记（见 [assetFileCandidates]），
        // 不重载的话内存里一直是**上一款游戏**载入的那份，
        // 而 corpusOwnerId 记的也是那一款 —— 新游戏即使已经备齐语料资产也永远读不到，
        // 表现成"接了知识库，军师还是没有参考条目"。
        if (!switchHookRegistered) {
            switchHookRegistered = true
            com.stzb.assistant.service.PerGameScope.reloadOnProfileSwitch { reloadForGame(context) }
        }
        if (isInitialized && knowledgeBase.isNotEmpty()) return

        synchronized(this) {
            if (isInitialized && knowledgeBase.isNotEmpty()) return
            try {
                val loaded = loadFromBinaryAsset(context)
                if (!loaded || knowledgeBase.isEmpty()) {
                    Log.w(TAG, "从二进制资产加载未成功，加载内置权威战术知识库保底。")
                    loadBuiltinSeedKnowledge()
                } else {
                    // 资产是半套这件事不会自己承认：某些检索通道在容器里一条语料都没有。
                    // 这里按通道自查补齐，缺到什么程度一律写进日志。
                    backfillMissingChannels()
                }
                isInitialized = true
                Log.i(TAG, "SLG RAG 战术语义检索中枢已就绪，已加载 ${knowledgeBase.size} 条专业战术词条，" +
                    "通道覆盖情况：${knowledgeBase.groupingBy { it.category }.eachCount()}")
            } catch (e: Exception) {
                Log.e(TAG, "初始化 RAG 引擎异常，启用保底知识库: ${e.message}")
                loadBuiltinSeedKnowledge()
                isInitialized = true
            }
        }
    }

    /**
     * 换游戏：作废当前语料池，按新游戏重新载入一遍。
     *
     * 维度、useBge、语料主人这些都必须跟着重置：上一款游戏的索引若是 bge 512 维，
     * 残留状态会让新游戏的 V1(64 维) 索引被当成维度不符而丢弃。
     */
    private fun reloadForGame(context: Context) {
        synchronized(this) {
            knowledgeBase.clear()
            isInitialized = false
            useBge = false
            vectorDim = VECTOR_DIM
            warnedCorpusGameId = null
        }
        Log.i(TAG, "切换到 [${com.stzb.assistant.service.PerGameScope.gameId()}]，RAG 语料池已作废并重新载入。")
        init(context)
    }

    /**
     * 索引文件在 assets 里的候选路径：先带当前游戏标记的名字，再（仅率土）旧的无标记名字。
     *
     * 与 [ModelAssetManager.modelCandidates] 同一条口径，因此"资产体检认为齐备"
     * 与"本引擎实际去读"永远是同一个文件名。
     */
    private fun assetFileCandidates(): List<String> =
        ModelAssetManager.modelCandidates(INDEX_FILE_NAME).map { "$ASSET_DIR/$it" }

    /** 依候选顺序打开 assets 里的索引流；全都打不开返回 null（调用方按"无索引"处理）。 */
    private fun openAssetStream(context: Context): InputStream? {
        for (candidate in assetFileCandidates()) {
            val stream = try {
                context.assets.open(candidate)
            } catch (e: Exception) {
                continue
            }
            Log.i(TAG, "RAG 索引取自资产 [$candidate]。")
            return stream
        }
        return null
    }

    /**
     * 只读容器头（28 字节）报告「已发布的语料资产」有多大、多少条、什么维度，**不载入内存**。
     *
     * 界面那段"军师大脑真实构成"必须调它，而不是把数字写进字符串：语料一改
     * （这份仓库就从 42 条涨到过 95 条），写死的数字立刻变成谎话，而用户会照着谎话
     * 去归因故障。本文件里另一处注释早就写下"每个数字都要能现场复现"的原则，
     * 那串 94.3KB / 42 条恰恰是违反它的例子 —— 现在把它接回地面上。
     * 读不到就如实报缺失，不猜、不复述旧数字。
     */
    fun describeShippedIndex(context: Context): String {
        for (candidate in assetFileCandidates()) {
            try {
                context.assets.open(candidate).use { input ->
                    val head = ByteArray(28)
                    var got = 0
                    while (got < 28) {
                        val n = input.read(head, got, 28 - got)
                        if (n <= 0) break
                        got += n
                    }
                    if (got < 28) return@use
                    val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                    val magic = String(ByteArray(16).also { buffer.get(it) }).trimEnd('\u0000')
                    if (!magic.startsWith("SLG_HNSW_RAG_V1") && !magic.startsWith("SLG_HNSW_RAG_V2")) return@use
                    val version = buffer.int
                    val items = buffer.int
                    val dim = buffer.int
                    if (items <= 0 || dim <= 0) return@use
                    val bytes = assetByteSize(context, candidate)
                    val sizeText = if (bytes >= 0) "%.1fKB".format(bytes / 1024.0) else "体积未知"
                    return "$sizeText / $items 条 / $dim 维（容器 V$version）"
                }
            } catch (e: Exception) {
                Log.w(TAG, "读 RAG 容器头失败 [$candidate]: ${e.message}")
            }
        }
        return "语料索引缺失（assets 下没有可读的 RAG 容器，检索只能回落内置种子）"
    }

    /** 资产字节数；`openFd` 对压缩资产不可用（返回 -1 或抛异常），退化到流的 available。 */
    private fun assetByteSize(context: Context, path: String): Long =
        try {
            context.assets.openFd(path).use { it.length }
        } catch (e: Exception) {
            try {
                context.assets.open(path).use { it.available().toLong() }
            } catch (e2: Exception) {
                -1L
            }
        }

    /**
     * 从 assets 或本地沙盒加载向量索引
     */
    private fun loadFromBinaryAsset(context: Context): Boolean {
        var input: InputStream? = null
        try {
            // 优先从 ModelAssetManager 解压后的文件加载，或直接从 assets 流读取
            val path = ModelAssetManager.extractScopedModelPath(context, INDEX_FILE_NAME)
            input = if (path != null && java.io.File(path).exists() && java.io.File(path).length() > 500) {
                java.io.File(path).inputStream()
            } else {
                openAssetStream(context) ?: return false
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

            // V2 容器尾部带 HNSW 第 0 层邻接图，但**本引擎不读它**（不是漏接，是有意的取舍）：
            // 生产调用一律带 category，真实候选池因此只有 11 条（最大的一类），
            // 一次检索 11×512 ≈ 5.6K 次乘加；而同一次查询光"把句子变成向量"要跑 bge INT8，
            // 量级 ~2×10^7 乘加 —— 扫描只占 0.03%，图索引省不到任何可感知的时间。
            // 数字由 python tools/rag_bench.py 从真实资产读出现算（PC 口径，端侧毫秒待真机校准）；
            // 为什么以前读过、现在为什么删掉，理由写在 search() 的候选池注释里；
            // 临界阈值与 sqlite-vec 的代价评估见 tools/RAG_INDEX_MIGRATION_ASSESSMENT.md。

            Log.i(TAG, "成功解析二进制 RAG 向量知识库: $itemCount 个条目, 向量维度: $dim"
                + (if (useBge) ", 查询侧用 bge 向量" else ", 查询侧用哈希向量降级")
                + "；检索方式=全量精确扫（未启用图索引）")
            // 语料的主人记为"我读这份文件时激活的游戏"：索引文件名本身就带游戏标记
            // （见 [assetFileCandidates]），所以这个值就是这份内容的真实归属，
            // 不再靠一个写死的常量去猜。写死常量的坏处是换游戏接上自己的语料之后，
            // 该服的语料也被一起拒掉，看起来像"知识库没生效"。
            corpusOwnerId = com.stzb.assistant.service.PerGameScope.gameId()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "读取 RAG 二进制资产异常: ${e.message}")
            return false
        } finally {
            try { input?.close() } catch (ignored: Exception) {}
        }
    }

    /**
     * 核心接口 1：混合语义检索（**精确通道优先，向量只做兜底**）
     *
     * 为什么不再用「cosine × 0.65 + 关键词 × 0.35」这种加权求和：
     *   加权和之下，一条语义相近但**根本没提这个武将**的词条，可以凭 cosine 优势
     *   把真正按名字精确命中的词条挤出前 3。而本函数的结论会一路流到
     *   `queryLandDefender` → 软柿雷达选地（SoftTileRadarFlow 直接 `filter { it.isSafe }`），
     *   排错序就等于给玩家念错药方。
     * 项目公理是「确定性优先，学习补多变」，检索同理，所以改成两段：
     *   * 通道 A（确定性）：关键词或标题在查询里**原样出现**即精确命中，
     *     只要 A 有命中，B 就不再参与；
     *   * 通道 B（兜底）：仅当 A 一条未命中时才用 dense 相似度，并且低于
     *     [MIN_DENSE_SIMILARITY] 的结果一律丢弃 —— **宁可返回空**，让调用方走
     *     「无参考条目」分支，也不把不相干词条的文案念给玩家。
     *
     * 返回空是有意的 fail-closed，不是失败：调用方（queryLandDefender /
     * matchDecreeTactics）都写了解释得通的兜底分支。
     */
    fun search(query: String, topK: Int = 3, category: String? = null): List<RagSearchResult> {
        if (knowledgeBase.isEmpty()) return emptyList()
        // 语料不属当前游戏时整体拒服（见 [corpusOwnerId]）：宁可没有参考条目，
        // 也不能把另一款游戏的武将与战法当成答案念给玩家。
        if (!corpusAppliesToActiveGame()) return emptyList()

        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val queryVec = computeTextEmbedding(cleanQuery)
        // 候选池：把**通道名**展开成它允许的 category 集合，再过滤；不传通道就是全量。
        //
        // 为什么必须展开而不是等值比较（真实事故）：入包的 V2 语料用的是
        // DEFENDER_SAFE/MODERATE/HARD/AVOID + LAND_SIEGE，而调用侧查的是通道名
        // DEFENDER_LAND/SKILL_SYNERGY/TACTICAL_DECREE —— 等值过滤下**每条生产检索都是空池**，
        // RAG 整层在真机上空转，而且只在"资产加载失败回落种子库"时才碰巧有结果。
        // 现在通道定义集中在 [SEARCH_CHANNELS]，一处解释，闸门按真实 .bin 对账。
        //
        // 这里曾有第三个分支：当 V2 容器带图区时，从全部节点起 BFS 收候选，
        // 再用 `seen.size > 4096` 截断。删除它的原因（P5 取证结论）：
        //   1. 它不是近似检索：从所有点出发的 BFS，候选集恒等于"全量扫"，
        //      却多跑一趟图遍历 + 一次 filterIndexed —— **比不用它更慢**；
        //   2. 那个 4096 截断是定时炸弹：语料一旦超过它，会按 BFS 到达顺序
        //      **静默丢掉一部分条目**，不报错、不降级，只是结果变少——
        //      而这个结果会直接决定雷达把哪块地划成"可打"；
        //   3. 实际永远走不到这个分支：工程内所有生产调用都带通道。
        // 真需要图索引时，要的是"固定入口点 + ef 贪心扩展"的真 HNSW，不是这种伪 ANN；
        // 临界条件与实测取证见 tools/RAG_INDEX_MIGRATION_ASSESSMENT.md。
        val wantedCats = if (category != null) SEARCH_CHANNELS[category] ?: setOf(category) else null
        if (category != null && SEARCH_CHANNELS[category] == null) {
            // 有人在调用侧新加了一个没进对照表的通道名：让它响在日志里，
            // 而不是"查了个不存在的类然后返回空"（那正是上面那个坑的复发形态）。
            Log.w(TAG, "检索通道 '$category' 不在 SEARCH_CHANNELS 对照表里，按字面 category 过滤。")
        }
        val pool = if (wantedCats != null) {
            knowledgeBase.filter { it.category in wantedCats }.toList()
        } else {
            knowledgeBase.toList()
        }
        if (pool.isEmpty()) {
            Log.w(TAG, "通道 '$category'（展开为 ${wantedCats ?: "全量"}）在当前语料里 0 条命中，" +
                "检索退化为空结果——检查资产是否重建、对照表是否漏了新 category。")
            return emptyList()
        }

        // 通道 A：精确命中。命中数作主分，cosine 只用来在「同样命中」的条目间定序。
        val exact = pool.mapNotNull { entry ->
            val kwHits = entry.keywords.count { cleanQuery.contains(it, ignoreCase = true) }
            val titleHits = if (cleanQuery.contains(entry.title, ignoreCase = true)) 1 else 0
            val hits = kwHits + titleHits
            if (hits == 0) null else RagSearchResult(
                entry,
                hits * EXACT_HIT_WEIGHT + computeCosineSimilarity(queryVec, entry.vector)
            )
        }.sortedByDescending { it.score }
        if (exact.isNotEmpty()) return exact.take(topK)

        // 通道 B：没有任何精确命中，才让向量相似度说话，并卡住下限。
        return pool.map { entry ->
            RagSearchResult(entry, computeCosineSimilarity(queryVec, entry.vector))
        }.filter { it.score >= MIN_DENSE_SIMILARITY }
            .sortedByDescending { it.score }
            .take(topK)
    }

    /**
     * 核心接口 2：土地守军开荒打分与翻车智能预警
     *
     * 唯一权威 = 当前激活知识库（KnowledgeBaseManager.activeProfile.defenderDb）。
     *
     * 为什么必须改掉这里的硬编码：本函数曾自带一套“危险武将名 / 软柿名 / 各等级兵力”，
     * 与内置库各说各话，造成两个玩家看得见的矛盾：
     *   1. 切到《三战》后，视觉通道按三战守将评估，本函数仍按率土的名单判——
     *      同一块地给出两个相反结论；
     *   2. 云端热更新只能改到知识库那一条，本函数的名单与兵力表永远是旧的；
     *   3. 真实数字冲突：Lv5 出兵量知识库 5500、本函数 5800。
     * 因此下面**不再出现任何武将名字**，兵力也只从知识库取。
     * （闸门 tools/check_knowledge_base.py 会持续对账，重新引入就报错。）
     */
    fun queryLandDefender(level: Int, queryText: String): DefenderAnalysis {
        val results = search(queryText, topK = 3, category = CHANNEL_DEFENDER)
        val best = results.firstOrNull()
        val db = KnowledgeBaseManager.activeProfile.defenderDb

        // 名单一律取自当前激活的游戏档（danger / hard / moderate / safe 四档）
        // moderate 档以前在知识库里存着但本路径从不读它，现在读：它代表“有微弱威胁、
        // 但打得住”（知识库给这一档的话术本身就是“难度偏低，推荐打”“可稳拿”）。
        val hitDanger = db.dangerHeroes.firstOrNull { queryText.contains(it.name) }
        val hitHard = db.hardHeroes.firstOrNull { queryText.contains(it.name) }
        val hitModerate = db.moderateHeroes.firstOrNull { queryText.contains(it.name) }
        val hitSafe = db.safeHeroes.firstOrNull { queryText.contains(it.name) }

        val land = db.landSuggestions[level]
        val minSoldiers = soldiersForLevel(level)

        // 【本函数不再采信语料文案】
        // 旧判定里有一条 `best.entry.advice.contains("白给")`：意思是「编译期向量资产里的
        // 一句文案，可以替玩家决定这块地能不能打」。而那份资产：
        //   1. 由 ModelAssetManager 从 assets 读出，**没有云端下发通道**，热更新根本到不了它；
        //   2. 不随游戏切换重建，切到《三战》后它讲的还是率土的阵容；
        //   3. 里面写的兵力数字与知识库谁新谁旧无从判断。
        // 现在「能不能打」只由两份**可热更**的 KB 读数决定，优先级与知识库自身的语义一致：
        //   1. 该等级黑名单：最具体的反向证据，压过一切；
        //   2. danger / hard：全局判定的硬危险，等级白名单救不回来；
        //   3. moderate + 该等级明确推荐：等级表比全局档位更具体，此时可打。
        //      知识库确实把徐晃/鲍信同时列进 moderate 档与 Lv5 推荐名单（Lv5 是开荒分水岭等级），
        //      若让全局 moderate 一刀切否决，雷达会跳过知识库亲自推荐去首开的那些地，
        //      等于把“5 级地该打谁”这条最关键的结论丢掉；
        //   4. 两条正向证据都没有（没撞中 safe 档、也没被该等级推荐）就是「没把握」，一律判不可打，
        //      雷达会跳过它。宁可少打一块地，也不会因为一句旧文案去撞人队。
        // 语料条目此后只提供文字建议（rawAdvice），不参与任何 isSafe 结论。
        val levelBlacklisted = land?.blacklistHeroes?.firstOrNull { queryText.contains(it) }
        val levelWhitelisted = land?.safeHeroes?.firstOrNull { queryText.contains(it) }
        val kbSaysSafe = levelBlacklisted == null && (hitSafe != null || levelWhitelisted != null)
        val noHardBlock = hitDanger == null && hitHard == null
        val moderateOk = hitModerate == null || (levelWhitelisted != null && levelBlacklisted == null)
        val isSafe = kbSaysSafe && noHardBlock && moderateOk

        val rating = when {
            hitDanger != null -> "S(极危翻车点)"
            hitHard != null -> "B(较难有损)"
            hitModerate != null && isSafe -> "C(中等但本等级推荐)"
            hitModerate != null -> "C-(中等需慎)"
            isSafe -> "D(软柿子稳开)"
            else -> "C(常规防守)"
        }

        // 旧实现在“担心但没撞名”的分支里直接把 hitDanger（此时为 null）拼进提示，
        // 玩家看到“检测到翻车守将 null”。现在按真实命中对象分组文案。
        val recommendation = when {
            hitDanger != null ->
                "⚠️ 警告：检测到高危守将 ${hitDanger.name}（${hitDanger.tag}），${hitDanger.counterTip}；" +
                    "建议换地或先把兵力堆到 ${minSoldiers} 以上再打。"
            hitHard != null ->
                "🟠 守将 ${hitHard.name}（${hitHard.tag}）属较难目标，战损偏高，" +
                    "建议补强到 ${minSoldiers} 以上或换块地。"
            hitModerate != null ->
                if (isSafe)
                    "🟢 守将 ${hitModerate.name}（${hitModerate.tag}）全局难度中等，" +
                    "但知识库在本等级把他列为可开目标：${hitModerate.counterTip}，" +
                    "按推荐出兵 ${minSoldiers}、满士气出征可打。"
                else
                    "🟡 守将 ${hitModerate.name}（${hitModerate.tag}）属中等难度：可打但不是优选目标，" +
                        "按知识库兵力 ${minSoldiers} 并先探后打；雷达会把它排在软柿之后。"
            isSafe -> "🟢 极佳开荒目标！战损可控，推荐满士气出征。"
            levelBlacklisted != null ->
                "⛔ 本等级知识库明确将 $levelBlacklisted 列为避开目标，即便其余守将看似可打也不建议首开。"
            else ->
                "🟡 未命中知识库任何守将档位（OCR 未读出守将名，或 KB 尚未收录该阵容）：" +
                    "按保守处理，不主动出征；补齐守将识别或热更新增条目后，本块地会自动重新入围。"
        }

        // 守军总兵力：取自知识库入库的官方读数（<=0 表示该等级尚无可靠数据，绝不编）
        val garrisonNote = land?.defenderTotalSoldiers?.takeIf { it > 0 }?.let {
            "守军总兵力约 $it"
        }
        val rawAdvice = listOfNotNull(
            // 语料文案先过一道“兵力数字脱敏”：
            // 那份向量资产是**编译期**打进 APK 的，ModelAssetManager 没有下发通道，
            // 云端热更新根本改不到它；而一块地该带多少兵是会随赛季变的。
            // 两个数字并存时，玩家看到的是“知识库 5500 + 旧文案 5800”这种自相矛盾的建议。
            // 所以这里只留定性结论，数字一律由下面的 KB 读数负责。
            best?.entry?.advice?.let { stripCorpusTroopNumbers(it) }
                ?: "建议带足兵力，先探后打，满士气出征。",
            land?.note?.takeIf { it.isNotBlank() },
            garrisonNote,
            // 脱敏后玩家不能只看到“以知识库为准”却不知道值是多少，所以把 KB 读数显式补回。
            if (minSoldiers > 0) "推荐出兵 $minSoldiers" else null
        ).joinToString("；")

        return DefenderAnalysis(
            level = level,
            targetName = queryText.take(15),
            rating = rating,
            isSafeToHit = isSafe,
            dangerHeroDetected = hitDanger?.let { "${it.name} (${it.tag})" },
            recommendation = recommendation,
            recommendedSoldiers = minSoldiers,
            rawAdvice = rawAdvice
        )
    }

    /**
     * 判定战报类型（PVE / PVP）。
     *
     * 判定依据是战报里出现的**阵营性语汇**，而不是猜。语汇本身来自当前知识包
     * （[VOCAB_REPORT_PVE] / [VOCAB_REPORT_PVP]，可云热更），代码里只保留"命中怎么算"：
     *   * PVE 语汇：率土配的是 守军 / 贼兵 / 贼寇 / 黄巾 / 野地 / 试炼 …
     *   * PVP 语汇：率土配的是 同盟 / 集结 / 会战 / 攻城 / 赛季战报 …
     *
     * 两类语汇都命中时按 **PVP 优先**（会战战报里也常提到"守军"字样，容易误判成 PVE），
     * 都不命中则返回 [ReportType.UNKNOWN]，由调用方决定是否要求用户手动指定。
     * 两组都没配置时同样返回 UNKNOWN —— 宁可不分类，也不拿另一款游戏的词来分类。
     */
    fun detectReportType(reportText: String): ReportType {
        val pveHit = vocab(VOCAB_REPORT_PVE).count { reportText.contains(it) }
        val pvpHit = vocab(VOCAB_REPORT_PVP).count { reportText.contains(it) }
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

        for (s in vocab(VOCAB_KNOWN_SKILLS)) {
            if (reportText.contains(s)) detectedSkills.add(s)
        }
        for (h in vocab(VOCAB_KNOWN_HEROES)) {
            if (reportText.contains(h)) detectedHeroes.add(h)
        }

        // 检索战法/阵容克制语料：**一个通道一次检索**。
        // 旧写法是 SKILL_SYNERGY 与 HERO_COUNTER 各查一次再相加——在语料里它们本就是同一批
        // 文案（见 [SEARCH_CHANNELS]），两次检索只会返回两份重复条目，白白挤掉 topK 名额。
        val ragResults = search(reportText, topK = 2, category = CHANNEL_SKILL)

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
        // 这几条写的是**率土之滨的具体战法名**，属于语料层的内容：
        // 外层按语料归属拦一道（见 [corpusAppliesToActiveGame]），内层还靠"战报里
        // 真的出现这些率土战法名"才触发——换一款游戏时这两个条件都不成立，
        // 于是绝不会把别游戏的战法名当成答案念给玩家。克制知识要跟着游戏走，
        // 得进该游戏自己的语料。
        if (corpusAppliesToActiveGame()) {
            if (detectedSkills.contains("战必断金") && (detectedHeroes.contains("马超") || detectedSkills.contains("先驱突击"))) {
                conflictSb.append("【普攻封锁】：敌方配置【战必断金】，马超/先驱突击前3回合无法造成有效普攻伤害，战力被腰斩！\n")
            }
            if (detectedSkills.contains("反计之策")) {
                conflictSb.append("【主动压制】：敌方【反计之策】压制首回合主动战法并大幅削弱前3回合爆发。\n")
            }
            if (detectedSkills.contains("浑水摸鱼") || detectedSkills.contains("妖术")) {
                conflictSb.append("【强控失控】：队伍缺乏解控战法，陷入混乱或暴走造成自相残杀。\n")
            }
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
            // 士气数值取当前知识库的上限，不写死率土的 120（三战是 100）。
            adviceSb.append(
                "常规对决，注意前 3 回合减伤配置，保持 ${KnowledgeBaseManager.activeProfile.rules.maxMorale} 满士气作战。"
            )
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
        // 机制字样（暴走/反击/…）与"读到这个词该怎么打"的解读，**成对来自当前知识包**
        // `pve_mechanic_notes`：留在引擎里的只有"读到机制词 = 本轮没清干净、需要补刀"这条通用逻辑。
        // 全部命中都列出来（旧写法只取第一条，同一条战报里既暴走又带反击时会被漏掉）。
        val notes = KnowledgeBaseManager.activeProfile.pveMechanicNotes
        val hits = notes.filterKeys { word -> word.isNotBlank() && reportText.contains(word) }
        val isRampage = hits.isNotEmpty()

        val level = LEVEL_PATTERN.find(reportText)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val baseSoldiers = soldiersForLevel(level)
        val killThreshold = (((baseSoldiers * PVE_KILL_THRESHOLD_RATIO) / 500 + 1) * 500).toInt()

        val advice = when {
            isRampage && level != null ->
                "建议：先补一刀 ≥ $killThreshold 兵力的队伍收地（当前判定 Lv.$level 守军未溃），" +
                    "补刀队带 1 个解控/减伤战法，避免被反击与暴走反复消耗。"
            isRampage ->
                "建议：守军存在暴走/反击机制，补刀请带 ≥ $killThreshold 兵力并配解控，" +
                    "或在【查看守军】面板确认具体守将后再打。"
            notes.isEmpty() ->
                "建议：本游戏知识包未登记守军机制词表（pve_mechanic_notes），" +
                    "机制层**无从判定**——这不等于「没有机制」，请以「查看守军」面板读数为准。"
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
            rampageEvidence = when {
                hits.isNotEmpty() -> hits.values.joinToString(" ")
                notes.isEmpty() -> "本游戏未配置守军机制词表，机制判定无从下手（热更 pve_mechanic_notes 后恢复）。"
                else -> "未命中知识包登记的任何守军机制字样。"
            },
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
     *   2. 我方/敌方武将的**基础速度参考表**（知识包 `hero_base_speed`，仅作战力排序参考，
     *      不含装备/加点/阵营加成，因此结论一律标注"需实测算"）；
     *      该游戏没有这张表时，两条来源都落空 → 如实输出"需实测算"。
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
        //    参考表来自当前知识包（hero_base_speed），不含装备/加点/阵营加成，
        //    因此只用来定序；该游戏没配这张表时一律退回"需实测算"，绝不借别游戏的数值猜。
        if (!fromText) {
            val speedTable = KnowledgeBaseManager.activeProfile.heroBaseSpeed
            val speeds = detectedHeroes.mapNotNull { speedTable[it] }
            if (speeds.size >= 2) {
                mySpeed = speeds.maxOrNull()
                enemySpeed = speeds.minOrNull()
            }
        }

        val gap = if (mySpeed != null && enemySpeed != null) mySpeed - enemySpeed else null
        val firstStrike = when {
            gap == null -> "需实测算"
            gap > 0 -> "我方先手（速度领先 $gap，约可抢先一轮）"
            gap < 0 -> "敌方先手（速度落后 ${-gap}，首回合会被压制）"
            else -> "速度持平（先手取决于装备与加点，需实测）"
        }

        // 同类指挥战法互相顶掉：指挥类增益同时存在 2 个以上时，后生效者会覆盖前者。
        // 哪些战法算"指挥增益"是游戏知识，读知识包的 [VOCAB_COMMAND_AMPLIFY_SKILLS]；
        // 该组没配置时**不谎报"未检测到"**，而是如实说明无从判定。
        val amplifyTable = vocab(VOCAB_COMMAND_AMPLIFY_SKILLS)
        val commandSkills = detectedSkills.filter { it in amplifyTable }
        val hasConflict = commandSkills.size >= 2
        val conflictDetail = when {
            hasConflict ->
                "检测到同类指挥增益战法 ${commandSkills.joinToString(" + ")} 同时出现：" +
                    "同类指挥增益在同一目标上会**互相覆盖**，实际只有后生效的那个完全生效，" +
                    "等于浪费了一个战法位。建议保留收益最高的一个，另一个换成解控/减伤。"
            amplifyTable.isEmpty() ->
                "本游戏知识包未配置指挥增益战法表（scene_keywords.${VOCAB_COMMAND_AMPLIFY_SKILLS}），" +
                    "同类顶掉**无从判定**——这不等于「没有冲突」，请热更该表后再采信此项。"
            else -> "未检测到同类指挥增益互相顶掉的情况。"
        }

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

    /**
     * 地块等级 → 推荐出兵量：**先查知识库**，查不到才用下面的兜底表。
     *
     * 兜底表只解决“KB 没覆盖该等级时不能返回 0”，不是第二套权威：
     * 它的数值与内置率土库**逐条对齐**，由 tools/check_knowledge_base.py
     * 的 rag-soldiers-conflict 规则持续对账，一旦漂移当场报错。
     * 历史教训：这里曾写 5800，而知识库写 5500，玩家看到的两个数字自相矛盾。
     */
    private fun soldiersForLevel(level: Int?): Int {
        val kb = KnowledgeBaseManager.activeProfile.defenderDb
            .landSuggestions[level ?: -1]?.recommendedSoldiers ?: 0
        if (kb > 0) return kb
        return when (level) {
            3 -> 1200
            4 -> 3000
            5 -> 5500
            6 -> 16000
            7 -> 22000
            8 -> 28000
            9 -> 34000
            10 -> 40000
            else -> 3500
        }
    }

    /**
     * 核心接口 4：同盟战术意图与压秒操作指南检索
     */
    fun matchDecreeTactics(decreeText: String): DecreeTacticsResult {
        val matches = search(decreeText, topK = 1, category = CHANNEL_DECREE)
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
     * 内置保底种子语料（资产完全加载失败时整份顶上）。
     *
     * 与 [backfillMissingChannels] 共用同一份 [seedEntries] 定义，避免"两处各抄一遍种子"。
     *
     * 种子讲的全是率土的人与事，所以拿它兜底时**语料主人必须跟着改成率土**：
     * 否则会出现"当前游戏是 B、池子里装的却是 A 的种子、主人还记着 B"，
     * 检索照常放行，军师把 A 的武将名单当 B 的答案念给玩家。
     */
    private fun loadBuiltinSeedKnowledge() {
        if (knowledgeBase.isNotEmpty()) return
        knowledgeBase.addAll(seedEntries())
        corpusOwnerId = SEED_CORPUS_GAME_ID
    }

    /**
     * 种子语料的唯一构造入口。
     *
     * 向量在这里**现算**（[computeTextEmbedding]），所以维度永远跟当前已加载索引一致：
     * V2 加载后 useBge=true 就走 bge 出 512 维；bge 临时不可用时拿到的是同维零向量，
     * 余弦恒 0、只会退化成"无语义信号"，绝不会拿 64 维去和 512 维比前 64 维——
     * 那才是真正危险的"看起来在工作"。
     */
    private fun seedEntries(): List<RagEntry> {
        val seedData = listOf(
            RagEntry(
                "SEED-01", "DEFENDER_LAND", "4/5级地软柿子守军推荐",
                listOf("邓茂", "田续", "审配", "李典", "徐晃", "鲍信", "软柿子"),
                "4/5级地首开核心目标：审配防御极低、李典仅微弱辅助、徐晃慢热。无任何硬控与高额谋略爆发，战损极低。",
                "🟢 推荐首开！主力兵力以知识库为准，满士气即可稳下。",
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

        return seedData
    }

    /**
     * 资产加载成功后，把**资产里完全没有覆盖**的检索通道用种子补上。
     *
     * 为什么需要这一步（真实缺陷，不是防御性冗余）：入包的 V2 容器只有
     * DEFENDER_SAFE/MODERATE/HARD/AVOID 与 LAND_SIEGE，即战法克制（[CHANNEL_SKILL]）与
     * 军令战术（[CHANNEL_DECREE]）两类语料从未进过容器。旧逻辑只在"资产加载失败"时才
     * 加载种子，于是这两个通道在真机上永远是空的：
     * 战报诊断的"战法冲突"一段与军师页的战术指南一段，看起来是偶尔没命中，
     * 实际是**结构性没有语料**。现在按通道自查，缺就补，并如实打日志。
     *
     * 只补维度可比的条目（[RagEntry.vector] 长度 == 当前 vectorDim）：
     * 维度不同的向量放进同一个池，`computeCosineSimilarity` 会按 min(len) 截断比较，
     * 得到的是无意义相似度——那比返回空更危险。
     */
    private fun backfillMissingChannels() {
        if (knowledgeBase.isEmpty()) return
        // 种子是率土的语料。**只有当池子本身就属于率土时才允许补进去**：
        // 别的游戏的索引缺某个通道时，拿率土种子去填，等于往 B 游戏的语料池里
        // 掺进 A 游戏的人名战名，而 corpusOwnerId 记的是 B、检索照常放行。
        // 宁可"这个通道没有参考条目"，也不能有"看起来在工作、内容却来自另一款游戏"的参考条目。
        if (corpusOwnerId != SEED_CORPUS_GAME_ID) {
            Log.w(TAG, "语料池属于 [$corpusOwnerId]，内置种子属于 [$SEED_CORPUS_GAME_ID]：" +
                "跳过按通道补齐，缺失的检索通道如实留空。")
            return
        }
        // 种子向量要现算（5 次 embedd 不便宜），所以只在真的缺通道时才构造一次。
        var seeds: List<RagEntry>? = null
        val added = ArrayList<RagEntry>()
        val skipped = ArrayList<String>()
        for ((channel, cats) in SEARCH_CHANNELS) {
            if (knowledgeBase.any { it.category in cats }) continue
            val fill = (seeds ?: seedEntries().also { seeds = it }).filter { it.category in cats }
            if (fill.isEmpty()) {
                skipped.add("$channel(种子也没有该类语料)")
                continue
            }
            val usable = fill.filter { it.vector.size == vectorDim }
            if (usable.isEmpty()) {
                skipped.add("$channel(种子向量维度 ${fill.first().vector.size} 与索引 $vectorDim 不可比)")
                continue
            }
            added.addAll(usable)
        }
        if (added.isNotEmpty()) {
            knowledgeBase.addAll(added)
            Log.i(TAG, "按通道补齐种子语料 ${added.size} 条：${added.map { it.id }}；" +
                "现有词条共 ${knowledgeBase.size} 条。")
        }
        if (skipped.isNotEmpty()) {
            Log.w(TAG, "以下检索通道仍无语料可用，相关结论会走无参考条目分支：$skipped")
        }
    }

    // ==========================================================
    // 战报分流：语汇表 / 战法武将字典 / 速度参考表
    // ==========================================================
    //
    // 这一节曾经是 P7 实测里最硬的一处例外：六张**率土专属**的表写死在引擎里
    // （守军语汇、战法名、武将名、武将基础速度），而本文件的类注释同时写着
    // "本文件里不出现任何武将名"——注释是假的，表还是第二权威。
    // 现在全部改成**按组 ID 向当前知识包取词**，引擎只保留"这些组怎么组合判定"的逻辑：
    //   * 词、名单、速度值 → `GameProfile.sceneKeywords` / `GameProfile.heroBaseSpeed`（可云热更）；
    //   * 缺配 → 该组解析为空，对应判定**如实不输出**（不打别的游戏的字、不猜先后手）。
    // 这里刻意**不留率土默认值**（与 StzbUiMatcher 的 SCENE_* 相反）：
    // 场景判定失败会让整条自愈链路罢工，需要一个"至少还能跑"的底；
    // 而把率土战法名当成另一款游戏战报的结论念给玩家，是实打实的错误输出，
    // 比"这次没有战法分析"危险得多。

    /** 战报 PVE（开荒打地）语汇组。 */
    const val VOCAB_REPORT_PVE = "REPORT_PVE"
    /**
     * 战报 PVP（玩家对战）语汇组。
     *
     * 注意"集结/会战/攻城"这类词在同盟内也会出现，因此本组只用于**判类型**，
     * 不会据此产生任何自动点击。
     */
    const val VOCAB_REPORT_PVP = "REPORT_PVP"
    /** 已收录战法名组（战报关键词命中与冲突判定的字典）。 */
    const val VOCAB_KNOWN_SKILLS = "KNOWN_SKILLS"
    /** 已收录武将名组（战报关键词命中的字典）。 */
    const val VOCAB_KNOWN_HEROES = "KNOWN_HEROES"
    /**
     * 指挥类**增益**战法组。
     *
     * 同类指挥增益施加在同一目标上会互相覆盖，因此同时出现 2 个以上即判为"顶掉浪费"。
     * 减益类指挥（如战必断金/反计之策）不该进这一组——它们作用对象不同，不构成互斥。
     */
    const val VOCAB_COMMAND_AMPLIFY_SKILLS = "COMMAND_AMPLIFY_SKILLS"

    /** 已经为哪个"游戏 + 词组"打过缺配日志，避免每次战报都刷一条。 */
    private val warnedVocabGroups = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 取当前知识包里某一组语汇；缺配即返回空表并打一条 W 日志（每游戏每组一次）。
     *
     * 返回空**不是**降级到内置率土词表，而是让调用侧的判定自然不成立：
     * 词表没配 = 这款游戏里没有这些名字，硬套只会产出错游戏的答案。
     */
    private fun vocab(groupId: String): List<String> {
        val prof = KnowledgeBaseManager.activeProfile
        val words = prof.sceneKeywords[groupId]?.filter { it.isNotBlank() }
        if (words != null && words.isNotEmpty()) return words
        val mark = "${prof.gameId}/$groupId"
        // ConcurrentHashMap 没有 add()：只有 putIfAbsent。误用 Set 的 API 编不过（CI 实测）。
        if (warnedVocabGroups.putIfAbsent(mark, true) == null) {
            Log.w(TAG, "语汇组 [$groupId] 在 [${prof.gameName}] 的知识包里没有配置，" +
                "依赖它的战报判定（类型分流 / 战法命中 / 先手推算）会直接跳过，不会套用其它游戏的词表。" +
                "要恢复这项分析，请热更该游戏的 scene_keywords.$groupId。")
        }
        return emptyList()
    }

    /** 从战报文本里找 "Lv.7" / "7级地" 这类地级。 */
    private val LEVEL_PATTERN = Regex("(?:Lv\\.?|LV\\.?|等级)\\s*(\\d{1,2})")

    /** 从战报文本里找 "速度 123" / "速度:123" 这类速度读数。 */
    private val SPEED_PATTERN = Regex("速度\\s*[:：]?\\s*(\\d{2,3})")

    /**
     * PVE 补刀阈值折算系数（残余守军占比的经验值，可调）。
     *
     * 之所以留成常量而不是写死在公式里：不同赛季守军强度会变，
     * 调它可以整体缩放补刀建议，而不必改逻辑。
     */
    private const val PVE_KILL_THRESHOLD_RATIO = 0.35f

    /**
     * 语料文案里的“兵力类数字”。
     *
     * 只抓 3～6 位、且前或后紧邻兵力语汇的数字，故意做得很窄：
     * “前 3 回合”“士气 100”“战损 30 兵”这类不在拦截范围，宁可漏也不把
     * 无关数字抓花。要扩语汇时只改这个表，不改逻辑。
     */
    private val CORPUS_TROOP_CONTEXT = listOf("兵力", "出兵", "点兵", "带兵", "主力", "守军", "战损")

    private val DIGITS_RX = Regex("\\d+")

    /**
     * 把语料文案里的兵力数字换成“以知识库为准”。
     *
     * 为什么不在这里直接回填 KB 的值：同一个数字在语料里可能指不同的量
     * （“主力约 16000”是我方，“双队合计约 42000 兵力”是守军），拿 KB 的推荐出兵去
     * 顶守军数只会造出一个新的错。所以只**抹掉数字**，真实数值由 rawAdvice 里的
     * KB 分项供给。
     */
    private fun stripCorpusTroopNumbers(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for (ctx in CORPUS_TROOP_CONTEXT) {
            out = Regex("$ctx[^0-9。\\n]{0,8}?\\d{3,6}(?:[~～-]\\d{2,6})?[+]?")
                .replace(out) { it.value.replace(DIGITS_RX, "以知识库为准") }
        }
        // “约 42000 兵力”这类数字在前的写法
        out = Regex("\\d{3,6}(?:[~～-]\\d{2,6})?[+]?\\s*(?:合计)?(?:兵|兵力|守军)")
            .replace(out) { it.value.replace(DIGITS_RX, "以知识库为准") }
        // “一队 6000 左右平手、二队 2500 左右补刀”：语汇词（守军）离数字太远，
        // 中间隔着“（官方建议：一队”，超出上面那条 0~8 字符的窗口，两条正则都抓不住。
        // 而这句话说的正是**我方出兵量**，与知识包的 recommended_soldiers 是同一个量，
        // 念出来就是两条互相矛盾的建议（实测 5 级地：KB 写 5500，语料写 6000）。
        // “左右”这个后缀足够特定：它不会抓到“前 3 回合”“士气 100”这类无关数字。
        out = Regex("\\d{3,6}\\s*左\\s*右")
            .replace(out) { it.value.replace(DIGITS_RX, "以知识库为准") }
        // 反复替换会留下“以知识库为准以知识库为准”与区间写法“以知识库为准~以知识库为准”，
        // 这些都是要念给玩家听的话，收敛成一份。
        return out
            .replace("以知识库为准以知识库为准", "以知识库为准")
            .replace("以知识库为准~以知识库为准", "以知识库为准")
            .replace("以知识库为准～以知识库为准", "以知识库为准")
            .replace("以知识库为准-以知识库为准", "以知识库为准")
    }

    /**
     * 精确命中的计分权重（检索通道 A）。
     *
     * 取 1.0 是有意跟 cosine 量级拉开：余弦相似度永远不超过 1.0，而命中数从 1.0 起跳，
     * 所以“多命中一个关键词”的条目不可能被“语义更像但没提到”的条目反超。
     */
    private const val EXACT_HIT_WEIGHT = 1.0f

    /**
     * 向量兜底通道的相似度下限：低于此一律当“没找到参考条目”。
     *
     * 为什么不归零：本端查询向量与入库向量都是**哈希/量化后的粗粒度表达**，
     * 不相干条目也能蹭到 0.2～0.3。把这个值当成 0，就等于把不相干词条的文案
     * 当专业建议念给玩家（念错药方比不念更糟）。
     * 具体数字是保守估计，**待真机用实际语料标定**（跟 TILE_OCCUPY_* 同一张待校准清单）；
     * 它的方向是 fail-closed：调高只会让召回更少、不会多信一条。
     */
    private const val MIN_DENSE_SIMILARITY = 0.35f
}
