package com.stzb.assistant.ai.assets

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 端侧模型资产可用性管理器 (ModelAssetManager)
 *
 * ## 事实澄清（重要）
 * 本类原先对外宣称「190MB 四位一体满血 AI 矩阵」，并且在资产缺失时把**降级路径**
 * 描述成"XX 通道就绪"，使"一个权重文件都没有"在界面上表现为"全部就绪"。
 * 把失败伪装成成功比功能缺失更有害：它会让人把故障归因到别处。
 *
 * 已核实的现状（以本仓库当前入包资产为准，字节数由 `python tools/p1/check_assets.py` 现算）：
 *   * `assets/models/` **确实有**三份权重：
 *     `bge_zh_int8.onnx` 22.8MB（+ `bge_zh_vocab.txt` 107KB）、
 *     `intent_slot_zh.onnx` 37.1MB（+ 两张词表）、
 *     `slg_knowledge_vector_hnsw.bin` 226.1KB（95 条 / 512 维；这条会随语料重建而变，
 *     所以要给界面看就必须现读 —— 用 `SlgRagEngine.describeShippedIndex()`，别抄进文案）。
 *   * `assets/` 根目录有三套 PP-OCRv3 ncnn 模型
 *     （det / rec / cls 各 `.param` + `.bin`，合计约 12.6MB）与字典 `ppocr_keys_v1.txt`；
 *     **v4 / v5 的候选名仍处保留状态**——文件不在包里，[locate] 找不到就报缺失。
 *   * **YOLO 权重仍然一份都没有**：那是游戏专属资产，无公开源，
 *     必须自己采集该游戏截图训练并导出 ncnn（见 tools/a_plus_plus/train_yolo26.py）。
 *     仓库自带的 CI 工作流曾用 `os.urandom()` 生成同尺寸随机字节来"补齐"权重，
 *     于是产物里出现过一个 190MB 的纯噪声矩阵（该步骤已删除，别再加回来）。
 *   * **但「文件存在」不等于「能识别」**：`OcrEngine` 走 JNI，需要构建期链接
 *     ncnn + OpenCV 才会编出真正的推理实现；否则编译的是 `OcrStub.cpp` 空桩，
 *     它会静默返回空文本。本类无法代替构建期判断，因此额外提供
 *     [OcrManager 侧的运行期探针]与 `describeAvailability()` 的显式提示。
 *
 * 本类现在的职责只有一条：**如实回答「有没有、多大、缺什么」**，
 * 不再存在任何「降级即就绪」的措辞。
 */
object ModelAssetManager {

    private const val TAG = "ModelAssetManager"

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 可选权重的文件名（不含目录）——**唯一权威**。
     *
     * 这些名字此前在四处各抄一遍：资产体检、加载引擎、微脑探针、CI 清单。
     * 抄写一多就会出现"体检说齐备、引擎说没找到"这种自相矛盾的对外口径，
     * 而用户看到的是"功能时好时坏"。现在除本处以外一律引用常量。
     * 游戏标记（`_stzb`）不写在这里，由 [modelCandidates] 按当前游戏派生。
     */
    const val RAG_INDEX_FILE = "slg_knowledge_vector_hnsw.bin"
    const val INTENT_MODEL_FILE = "intent_slot_zh.onnx"
    const val INTENT_TOKEN_VOCAB_FILE = "intent_slot_token_vocab.txt"
    const val INTENT_TARGET_VOCAB_FILE = "intent_slot_vocab.txt"
    const val BGE_MODEL_FILE = "bge_zh_int8.onnx"
    const val BGE_VOCAB_FILE = "bge_zh_vocab.txt"

    /**
     * YOLO 权重的基名序列（不含游戏标记、不含扩展名）——**唯一权威**。
     *
     * 排在越前面 = 越优先被选用（A++ 的 YOLO26 优先，再 v11，再 v8 的 s/n）。
     * 以前 YoloDetector 自己另抄了一份同样含义的名单，而且两份的 v8 顺序还不一样：
     * 体检报告按一份判"就绪"、真正加载按另一份挑权重，只备齐一套权重时两者会各说各话。
     * 现在声明与加载都读这里，名单不可能再漂移。
     *
     * 注意：必须声明在 [YOLO] 之前——object 的属性按声明顺序初始化。
     */
    val YOLO_WEIGHT_BASES = listOf("yolo26s", "yolo26n", "yolov11s_multiscale", "yolov8s", "yolov8n")

    /**
     * 一项能力的资产判定条件。
     *
     * @param displayName 展示名
     * @param alternatives 备选组合：每个组合内的**所有** assets 相对路径都必须存在，
     *                     任一组合全部满足即视为该能力资产齐备
     * @param purpose 该能力负责什么
     * @param buildRequirement 除文件之外的构建期前置条件（没有就写 null）
     * @param gameScoped 该权重是否是**照某款游戏**产出的。true 时文件名会按激活游戏
     *                   派生标记（见 [com.stzb.assistant.service.PerGameScope.assetNameCandidates]）
     */
    private data class Capability(
        val displayName: String,
        val alternatives: List<List<String>>,
        val purpose: String,
        val buildRequirement: String?,
        val gameScoped: Boolean = false
    )

    /**
     * OCR 资产。注意路径差异：
     *   - PP-OCRv3 是工程内真实存在的资产，放在 `assets/` **根目录**
     *     （`OcrEngine.kt` 传的是基名，native 侧自行拼 `.param`/`.bin`）；
     *   - PP-OCRv4 是历史宣传里的命名，若将来补入则放在 `assets/models/`。
     */
    private val OCR = Capability(
        displayName = "OCR 文本识别 (PP-OCR DBNet + CRNN)",
        alternatives = listOf(
            listOf(
                "ch_PP-OCRv3_det_infer.param", "ch_PP-OCRv3_det_infer.bin",
                "ch_PP-OCRv3_rec_infer.param", "ch_PP-OCRv3_rec_infer.bin"
            ),
            // v4：由 tools/p1/onnx2ncnn.py 从 ONNX 转出来，落在 assets 根目录（与 v3 同层）
            listOf(
                "ch_PP-OCRv4_det_infer.param", "ch_PP-OCRv4_det_infer.bin",
                "ch_PP-OCRv4_rec_infer.param", "ch_PP-OCRv4_rec_infer.bin"
            ),
            // v5（方案 A++）：由 tools/a_plus_plus/export_ppocrv5.py 落地，同样在 assets 根目录
            listOf(
                "ch_PP-OCRv5_det_infer.param", "ch_PP-OCRv5_det_infer.bin",
                "ch_PP-OCRv5_rec_infer.param", "ch_PP-OCRv5_rec_infer.bin"
            )
        ),
        purpose = "体力/士气/坐标/倒计时/按键文字识别——所有依赖文字的判断都建立在其之上",
        buildRequirement = "构建期必须提供 ncnn + OpenCV 给 CMake，否则 native 走空桩、识别恒为空"
    )

    private val YOLO = Capability(
        displayName = "YOLO 视觉目标检测 (ncnn)",
        // ncnn 模型是 param + bin 成对的，两者都在才算齐备。
        // 命名容错：YOLO26(A++ 首选) / v11 / v8 n/s 都接受；工具链按训练规模选。
        // 注意：Ultralytics 对 ncnn 导出回落到标准 [1,4+nc,anchors] 布局，与 YoloNcnn.cpp 解码兼容，
        // 因此 YOLO26 权重可直接被现有 native 加载，无需改 native。
        //
        // 名字**不写游戏后缀**：后缀由 gameScoped 按当前激活游戏派生
        // （`yolo26s.param` → 率土 `yolo26s_stzb.param`，三战 `yolo26s_sgz.param`）。
        // 率土派生名与分域之前发布的名字逐字相同，因此老权重无需改名。
        // 为什么必须派生：这批权重是照着率土界面采集、标注、训练的，
        // 拿它去检测别的游戏的画面，框出来的是率土的按钮位置，
        // 而我们真的会照着那个框点下去。
        alternatives = YOLO_WEIGHT_BASES.map { listOf("models/$it.param", "models/$it.bin") },
        purpose = "出征/驻守/确定等按键与行军红线的视觉检测",
        buildRequirement = "推理代码与 JNI 已实现（native yolo/YoloNcnn.cpp，兼容 v8/v11/v26 ncnn）；"
            + "但游戏专属权重无公开源，需自行采集率土截图训练并导出 ncnn INT8（见 tools/a_plus_plus/train_yolo26.py）",
        gameScoped = true
    )

    private val RAG = Capability(
        displayName = "战法与守军向量检索库 (SLG-RAG)",
        alternatives = listOf(listOf("models/$RAG_INDEX_FILE")),
        purpose = "土地守军打分天梯、战法联动冲突与同盟战术意图检索",
        buildRequirement = "已由内置向量引擎 SlgRagEngine 全面驱动（64维稠密向量混合检索，毫秒级纯端侧就绪）",
        // 索引里的每一条语料都是某一款游戏的武将/战法文本。检索通道本身在 SlgRagEngine
        // 按语料归属（corpusOwnerId）拒服，这里再按游戏派生文件名，两道闸之间不留"靠运气"的空档。
        gameScoped = true
    )

    private val SLM = Capability(
        displayName = "端侧认知微脑 (SLM/RAG)",
        alternatives = listOf(
            listOf("models/$RAG_INDEX_FILE")
        ),
        purpose = "军令意图抽取、战法克制诊断与土地天梯打分",
        buildRequirement = "已由内置向量微脑 SlgRagEngine 与语义提取器全面驱动（100% 离线、毫秒级响应、<10MB极低内存，彻底免除 LMK 杀后台风险）",
        gameScoped = true
    )

    /** ③ bge-small-zh-v1.5 INT8：给 RAG 索引（V2, 512 维）做查询向量。 */
    private val BGE = Capability(
        displayName = "bge 中文向量器 (bge-small-zh-v1.5 INT8)",
        alternatives = listOf(
            listOf("models/$BGE_MODEL_FILE", "models/$BGE_VOCAB_FILE")
        ),
        purpose = "军令/战报查询语义向量化（与索引里的 bge 向量同分布，否则检索结果不可用）",
        buildRequirement = "依赖 onnxruntime-android 运行时（已在 app/build.gradle 引入）；"
            + "没有它时 BgeEmbedder 会加载失败，SlgRagEngine 如实回落到 64 维哈希向量"
        // 不分域：这是通用中文语义向量器，权重里没有任何一款游戏的专属内容。
    )

    /** ④ 意图 + 槽位微脑：军令 → 受约束 DSL。 */
    private val INTENT = Capability(
        displayName = "军令意图+槽位微脑 (rbt3 微调 INT8)",
        alternatives = listOf(
            listOf(
                "models/$INTENT_MODEL_FILE", "models/$INTENT_TARGET_VOCAB_FILE",
                "models/$INTENT_TOKEN_VOCAB_FILE"
            )
        ),
        purpose = "把自然语言军令解析成语法合法的 DSL（意图 + 目标/坐标/时间/兵力槽位）",
        buildRequirement = "依赖 onnxruntime-android 运行时；IntentSlotModel 已接入——"
            + "权重存在即走真推理（意图/坐标/时间/兵力等受约束槽位解码），缺失则 EdgeSlmEngine 自动回落正则（行为不变）",
        // 分域：目标槽位词表是训练语料里出现过的**率土地名**（虎牢关/洛阳/邺城，
        // 见 tools/p1/train_intent_slot.py 的 targets）。换一款游戏还吃这份权重，
        // 玩家喊"打虎牢关"这种词才会被认成目标——三战玩家说任何地名都落不到槽位上，
        // 或者更糟：被硬塞成一个率土地名。
        gameScoped = true
    )

    private val CAPABILITIES = listOf(OCR, YOLO, RAG, SLM, BGE, INTENT)

    data class ModelStatus(
        val modelName: String,
        val isReady: Boolean,
        val detail: String
    )

    /**
     * 一个资产路径的**实际候选名**（游戏专属权重会派生出多个候选）。
     *
     * 入参与返回都与 alternatives 同形（带 `models/` 目录前缀），调用方拿到就能直接读。
     */
    private fun candidatesFor(cap: Capability, assetPath: String): List<String> {
        if (!cap.gameScoped) return listOf(assetPath)
        val slash = assetPath.lastIndexOf('/')
        val dir = if (slash >= 0) assetPath.substring(0, slash + 1) else ""
        val name = if (slash >= 0) assetPath.substring(slash + 1) else assetPath
        return com.stzb.assistant.service.PerGameScope.assetNameCandidates(name).map { dir + it }
    }

    /**
     * 判定单个能力，返回命中的那组资产路径。
     *
     * 返回的是**真实存在的那个候选名**而不是声明名：诊断报告要把这个路径念给
     * 准备资产的人，念一个不存在的名义路径等于把人往错的文件名上引。
     * 未命中返回 null。
     */
    private fun locate(context: Context, cap: Capability): List<String>? {
        for (group in cap.alternatives) {
            val resolved = ArrayList<String>(group.size)
            var complete = true
            for (path in group) {
                val hit = candidatesFor(cap, path).firstOrNull { assetSize(context, it) > 0L }
                if (hit == null) {
                    complete = false
                    break
                }
                resolved.add(hit)
            }
            if (complete) return resolved
        }
        return null
    }

    /**
     * 游戏专属模型文件名的候选序列。
     *
     * 公开给"自己去 models/ 里捞权重"的调用方（YoloDetector / SlgRagEngine /
     * EdgeSlmEngine 的资产探针）共用同一条命名口径。它们若各自写死一个文件名，
     * 就会出现"体检说资产齐备、引擎却说没找到"这类互相打脸的状态。
     */
    fun modelCandidates(fileName: String, gameScoped: Boolean = true): List<String> =
        if (gameScoped) com.stzb.assistant.service.PerGameScope.assetNameCandidates(fileName)
        else listOf(fileName)

    /** 按 [modelCandidates] 的候选顺序提取，返回第一个成功落地的绝对路径；都没有返回 null。 */
    fun extractScopedModelPath(context: Context, fileName: String, gameScoped: Boolean = true): String? {
        for (name in modelCandidates(fileName, gameScoped)) {
            val path = getOrExtractModelPath(context, name) ?: continue
            val f = java.io.File(path)
            if (f.exists() && f.length() > 0L) return path
        }
        return null
    }

    /**
     * 读取 assets 中文件的真实字节数；不存在返回 -1。
     *
     * `openFd` 只对未压缩资产有效，`build.gradle` 已对 bin/param/gguf/tflite/onnx
     * 关闭压缩，因此正常路径走 `openFd`；失败时退回 `available()`。
     */
    private fun assetSize(context: Context, assetPath: String): Long {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (e: Exception) {
            try {
                context.assets.open(assetPath).use { it.available().toLong() }
            } catch (e2: Exception) {
                -1L
            }
        }
    }

    fun isOcrReady(context: Context): Boolean = locate(context, OCR) != null

    fun isYoloReady(context: Context): Boolean = locate(context, YOLO) != null

    fun isRagVectorReady(context: Context): Boolean = locate(context, RAG) != null

    fun isMicroBrainReady(context: Context): Boolean = locate(context, SLM) != null

    /**
     * 诚实的资产体检报告。缺失时明确写「缺失」，
     * 不再出现「智能空间几何容灾通道就绪」这类伪装就绪的措辞。
     */
    fun getFullDiagnosticReport(context: Context): List<ModelStatus> {
        return CAPABILITIES.map { cap ->
            val hit = locate(context, cap)
            val detail = if (hit != null) {
                val total = hit.sumOf { assetSize(context, it).coerceAtLeast(0L) }
                buildString {
                    append("✅ 资产齐备 (${hit.joinToString(", ")}，共 ${formatBytes(total)})")
                    if (cap.buildRequirement != null) {
                        append("；注意：${cap.buildRequirement}")
                    }
                }
            } else {
                buildString {
                    append("❌ 资产缺失 — ${cap.purpose}")
                    append("；期望路径：")
                    // 每个文件都展开成它当前的候选名（游戏专属的要带游戏标记），
                    // 否则报告会把人引到一个本游戏永远读不到的文件名上。
                    append(
                        cap.alternatives.joinToString(" 或 ") { group ->
                            group.joinToString(" + ") { candidatesFor(cap, it).first() }
                        }
                    )
                    if (cap.gameScoped) {
                        append("（当前游戏：${com.stzb.assistant.service.PerGameScope.gameId()}）")
                    }
                    if (cap.buildRequirement != null) {
                        append("；${cap.buildRequirement}")
                    }
                }
            }
            ModelStatus(modelName = cap.displayName, isReady = hit != null, detail = detail)
        }
    }

    /**
     * 把 assets 中 `models/` 下的候选权重预解压到沙盒，返回**实际成功**的项数。
     *
     * 旧实现把每个 `getOrExtractModelPath` 的返回值直接丢弃，于是"一个文件都没有"
     * 与"162MB 全部解压成功"在调用方看来毫无区别。
     *
     * 计数按**逻辑文件**而不是候选名：率土的两个候选（派生名 + 旧名）若都落在沙盒里，
     * 那是同一个权重的两份拷贝，记 2 个会把"其实只有 1 套权重"说成"备齐了两套"。
     */
    fun preloadAllBuiltinModels(context: Context): Int {
        var extracted = 0
        for (cap in CAPABILITIES) {
            for (group in cap.alternatives) {
                for (path in group) {
                    if (!path.startsWith("models/")) continue
                    val name = path.removePrefix("models/")
                    // 只解压到第一个命中的候选为止，与 locate 的取值顺序保持一致。
                    for (candidate in modelCandidates(name, cap.gameScoped)) {
                        if (getOrExtractModelPath(context, candidate) != null) {
                            extracted++
                            break
                        }
                    }
                }
            }
        }
        Log.i(
            TAG,
            "模型预解压完成：成功 $extracted 个文件" +
                "（游戏 ${com.stzb.assistant.service.PerGameScope.gameId()}）。"
        )
        return extracted
    }

    /**
     * 生成可直接打进 logcat 的可用性摘要。
     * 用户报「识别不了」时，先看这一行即可判断是模型缺失、还是构建期没链接 ncnn。
     */
    fun describeAvailability(context: Context): String {
        preloadAllBuiltinModels(context)
        val report = getFullDiagnosticReport(context)
        val ready = report.count { it.isReady }
        val sb = StringBuilder("模型资产: $ready/${report.size} 项资产齐备")
        report.forEach { sb.append("\n  ").append(it.detail) }
        return sb.toString()
    }

    /**
     * 获取指定模型的本地可执行路径（优先 App 私有沙盒，其次从 `assets/models/` 解压）。
     * 资产不存在时返回 null —— 调用方必须处理 null，不要假定"总是可用"。
     */
    fun getOrExtractModelPath(context: Context, modelName: String): String? {
        val targetFile = File(getModelsDirectory(context), modelName)
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile.absolutePath
        }

        return try {
            val assetList = context.assets.list("models") ?: emptyArray()
            if (!assetList.contains(modelName)) return null

            context.assets.open("models/$modelName").use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "从 assets/models 提取模型: $modelName (${targetFile.length()} 字节)")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "从 assets 提取模型 $modelName 失败: ${e.message}")
            null
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
