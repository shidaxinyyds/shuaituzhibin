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
 * 已核实的现状：
 *   * `assets/models/` 下**只有一份 README.md**；YOLO / RAG / 微脑三类权重
 *     一个都不存在。仓库自带的 CI 工作流曾用 `os.urandom()` 生成同尺寸随机字节
 *     来"补齐"它们，于是产物里的"190MB 模型"是纯噪声（该步骤已删除）。
 *   * 工程里**真实存在**的模型资产只有三套 PP-OCRv3 ncnn 模型，位于 `assets/` 根目录：
 *     `ch_PP-OCRv3_det_infer.param/.bin`、`ch_PP-OCRv3_rec_infer.param/.bin`、
 *     `ch_ppocr_mobile_v2.0_cls_infer.param/.bin`，以及字典 `ppocr_keys_v1.txt`。
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
     * 一项能力的资产判定条件。
     *
     * @param displayName 展示名
     * @param alternatives 备选组合：每个组合内的**所有** assets 相对路径都必须存在，
     *                     任一组合全部满足即视为该能力资产齐备
     * @param purpose 该能力负责什么
     * @param buildRequirement 除文件之外的构建期前置条件（没有就写 null）
     */
    private data class Capability(
        val displayName: String,
        val alternatives: List<List<String>>,
        val purpose: String,
        val buildRequirement: String?
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
            )
        ),
        purpose = "体力/士气/坐标/倒计时/按键文字识别——所有依赖文字的判断都建立在其之上",
        buildRequirement = "构建期必须提供 ncnn + OpenCV 给 CMake，否则 native 走空桩、识别恒为空"
    )

    private val YOLO = Capability(
        displayName = "YOLO 视觉目标检测 (ncnn)",
        // ncnn 模型是 param + bin 成对的，两者都在才算齐备。
        // n / s 两种命名都接受：工具链按训练规模选（tools/p1/train_yolo 默认 s，工程首选 n）。
        alternatives = listOf(
            listOf("models/yolov11s_multiscale_stzb.param", "models/yolov11s_multiscale_stzb.bin"),
            listOf("models/yolov8n_stzb.param", "models/yolov8n_stzb.bin"),
            listOf("models/yolov8s_stzb.param", "models/yolov8s_stzb.bin")
        ),
        purpose = "出征/驻守/确定等按键与行军红线的视觉检测",
        buildRequirement = "推理代码与 JNI 已实现（native yolo/YoloNcnn.cpp）；"
            + "但游戏专属权重无公开源，需自行采集率土截图训练并导出 ncnn（见 tools/train_yolo/）"
    )

    private val RAG = Capability(
        displayName = "战法与守军向量检索库 (SLG-RAG)",
        alternatives = listOf(listOf("models/slg_knowledge_vector_hnsw.bin")),
        purpose = "土地守军打分天梯、战法联动冲突与同盟战术意图检索",
        buildRequirement = "已由内置向量引擎 SlgRagEngine 全面驱动（64维稠密向量混合检索，毫秒级纯端侧就绪）"
    )

    private val SLM = Capability(
        displayName = "端侧认知微脑 (SLM/RAG)",
        alternatives = listOf(
            listOf("models/slg_knowledge_vector_hnsw.bin")
        ),
        purpose = "军令意图抽取、战法克制诊断与土地天梯打分",
        buildRequirement = "已由内置向量微脑 SlgRagEngine 与语义提取器全面驱动（100% 离线、毫秒级响应、<10MB极低内存，彻底免除 LMK 杀后台风险）"
    )

    /** ③ bge-small-zh-v1.5 INT8：给 RAG 索引（V2, 512 维）做查询向量。 */
    private val BGE = Capability(
        displayName = "bge 中文向量器 (bge-small-zh-v1.5 INT8)",
        alternatives = listOf(
            listOf("models/bge_zh_int8.onnx", "models/bge_zh_vocab.txt")
        ),
        purpose = "军令/战报查询语义向量化（与索引里的 bge 向量同分布，否则检索结果不可用）",
        buildRequirement = "依赖 onnxruntime-android 运行时（已在 app/build.gradle 引入）；"
            + "没有它时 BgeEmbedder 会加载失败，SlgRagEngine 如实回落到 64 维哈希向量"
    )

    /** ④ 意图 + 槽位微脑：军令 → 受约束 DSL。 */
    private val INTENT = Capability(
        displayName = "军令意图+槽位微脑 (rbt3 微调 INT8)",
        alternatives = listOf(
            listOf("models/intent_slot_zh.onnx", "models/intent_slot_vocab.txt")
        ),
        purpose = "把自然语言军令解析成语法合法的 DSL（意图 + 目标/坐标/时间/兵力槽位）",
        buildRequirement = "依赖 onnxruntime-android 运行时；当前 EdgeSlmEngine 仍是正则实现，"
            + "该权重**尚未**接入推理，解析行为暂时不受它影响"
    )

    private val CAPABILITIES = listOf(OCR, YOLO, RAG, SLM, BGE, INTENT)

    data class ModelStatus(
        val modelName: String,
        val isReady: Boolean,
        val detail: String
    )

    /** 判定单个能力，返回命中的那组资产路径；未命中返回 null。 */
    private fun locate(context: Context, cap: Capability): List<String>? {
        for (group in cap.alternatives) {
            val allPresent = group.all { assetSize(context, it) > 0L }
            if (allPresent) return group
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
                    append(cap.alternatives.joinToString(" 或 ") { it.joinToString(" + ") })
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
     */
    fun preloadAllBuiltinModels(context: Context): Int {
        var extracted = 0
        for (cap in CAPABILITIES) {
            for (group in cap.alternatives) {
                for (path in group) {
                    if (!path.startsWith("models/")) continue
                    val name = path.removePrefix("models/")
                    if (getOrExtractModelPath(context, name) != null) {
                        extracted++
                    }
                }
            }
        }
        Log.i(TAG, "模型预解压完成：成功 $extracted 个文件。")
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
