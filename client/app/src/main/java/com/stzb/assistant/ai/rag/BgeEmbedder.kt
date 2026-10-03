package com.stzb.assistant.ai.rag

import android.content.Context
import android.util.Log
import com.stzb.assistant.ai.assets.ModelAssetManager
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * 端侧 bge 中文向量器 (BgeEmbedder)
 *
 * ## 事实说明
 * 索引（③）一旦换成 `build_rag_index.py` 用 bge-small-zh-v1.5 跑出来的 512 维向量，
 * 端侧**必须**用同一个模型给查询文本 embedd，否则余弦检索出来的相似度是错的。
 * 这里就是那个 embedder：用 ONNX Runtime Mobile 跑 `models/bge_zh_int8.onnx`。
 *
 * 两点必须如实写明：
 * 1. 本类是**真实推理**，不是正则/哈希；但它只在资产（`bge_zh_int8.onnx` +
 *    `bge_zh_vocab.txt`）存在时才可用。资产缺失时 [embed] 返回 null，
 *    调用方（[SlgRagEngine]）会退回 64 维哈希向量路径，并如实打日志。
 * 2. 构造输入用的分词是**字符二元组（bigram）+ CLS/SEP** 的极简 BERT 分词，
 *    与 `tools/p1/build_rag_index.py` 的 `simple_bert_ids` 对齐。
 *    它比 HuggingFace 的 WordPiece 粗，但对中文短查询/军令短语足够，
 *    并且**离线零依赖**（只需词表文件）。
 */

object BgeEmbedder {

    private const val TAG = "BgeEmbedder"
    private const val MAX_LEN = 64

    /**
     * 加载 bge（ORT 会话 + 词表）的预计内存开销（MB）。
     *
     * bge-small-zh INT8 权重约 25~30MB，加上 ORT 运行时开销按 40MB 估，
     * 交给 [com.stzb.assistant.runtime.ResourceGuard] 做准入判断。
     */
    private const val BGE_COST_MB = 40

    @Volatile
    private var loaded = false

    private var session: ai.onnxruntime.OrtSession? = null
    private var vocab: Map<String, Int> = emptyMap()
    private var dim = 0

    private var releaseHookRegistered = false

    fun isLoaded(): Boolean = loaded

    fun vectorDim(): Int = dim

    /**
     * 释放 ORT 会话与词表（系统回收内存时由 ResourceGuard 回调）。
     *
     * 释放后 [ensureLoaded] 会在下次调用时重新按资源水位判断能否加载；
     * 检索侧会自动退回 64 维哈希向量路径，**不会**因为释放而抛异常或返回空结果。
     */
    fun release() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ORT 会话异常: ${e.message}")
        }
        session = null
        vocab = emptyMap()
        loaded = false
        Log.i(TAG, "已释放 bge 会话以回收内存，检索将退回 64 维哈希向量路径。")
    }

    /** 载入资产；成功返回 true（只代表"推理会话建起来了"，不代表检索结果一定好）。 */
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true

        val modelPath = ModelAssetManager.getOrExtractModelPath(context, "bge_zh_int8.onnx")
            ?: run {
                Log.i(TAG, "未发现 bge_zh_int8.onnx，③ 走 64 维哈希向量降级路径。")
                return false
            }
        val vocabPath = ModelAssetManager.getOrExtractModelPath(context, "bge_zh_vocab.txt")
        if (vocabPath == null) {
            Log.w(TAG, "发现 bge 模型但没有词表，无法构造输入，放弃加载。")
            return false
        }

        // 资源准入闸门：ORT 会话 + 词表常驻内存约数十 MB。
        // 没有余量时**明确放弃**并回退 64 维哈希路径，而不是硬加载把进程顶到 LMK 边缘——
        // 一次检索质量下降，远好过常驻进程被系统杀掉导致压秒/夜战防护整体消失。
        if (!com.stzb.assistant.runtime.ResourceGuard.canAfford(BGE_COST_MB)) {
            Log.w(
                TAG,
                "系统可用内存不足（bge 约需 ${BGE_COST_MB}MB）：${com.stzb.assistant.runtime.ResourceGuard.describe()}。" +
                    "放弃加载 bge，③ 降级为 64 维哈希向量。"
            )
            return false
        }

        return try {
            vocab = readVocab(vocabPath)
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            session = env.createSession(modelPath, ai.onnxruntime.OrtSession.SessionOptions())
            dim = detectDim()
            if (dim <= 0) {
                Log.w(TAG, "无法从 ONNX 输出推断向量维度，放弃加载。")
                return false
            }
            loaded = true
            // 系统开始回收内存时主动释放 ORT 会话（本进程是常驻进程，被 LMK 杀掉代价太大）
            if (!releaseHookRegistered) {
                com.stzb.assistant.runtime.ResourceGuard.registerReleaseHook { release() }
                releaseHookRegistered = true
            }
            Log.i(TAG, "bge 端侧向量器已加载，向量维度 $dim。")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "bge 端侧向量器加载失败（③ 降级为哈希向量）: ${e.message}")
            session = null
            vocab = emptyMap()
            false
        }
    }

    private fun readVocab(path: String): Map<String, Int> {
        val map = HashMap<String, Int>()
        java.io.File(path).readLines(Charsets.UTF_8).forEachIndexed { i, token ->
            if (token.isNotEmpty() && !map.containsKey(token)) map[token] = i
        }
        return map
    }

    private fun detectDim(): Int {
        val s = session ?: return 0
        val out = s.outputInfo
        if (out.isEmpty()) return 0
        val nodeInfo = out.values.firstOrNull() ?: return 0
        val tensorInfo = nodeInfo.getInfo() as? ai.onnxruntime.TensorInfo ?: return 0
        val shape = tensorInfo.getShape()
        // last_hidden_state: [1, seq, hidden]
        return if (shape.size == 3) shape[2].toInt() else 0
    }

    private fun tokenIds(text: String): Pair<LongArray, LongArray> {
        val chars = text.replace(" ", "").take(MAX_LEN - 2)
        val ids = ArrayList<Long>()
        val mask = ArrayList<Long>()
        fun push(token: String) {
            val id = vocab[token]
            if (id != null) {
                ids.add(id.toLong())
                mask.add(1L)
            }
        }
        push("[CLS]")
        var i = 0
        while (i < chars.length) {
            // 先判边界再取二元组：原实现先 substring(i, i+2)，奇数长度文本的
            // 最后一个字符会越界抛异常，并被 embed 的 catch(Throwable) 吞成 null（静默降级）。
            val hasPair = i + 1 < chars.length
            if (hasPair && vocab.containsKey(chars.substring(i, i + 2))) {
                push(chars.substring(i, i + 2))
                i += 2
            } else {
                push(chars.substring(i, i + 1))
                i += 1
            }
        }
        push("[SEP]")
        while (ids.size < MAX_LEN) {
            ids.add(0L)
            mask.add(0L)
        }
        return LongArray(MAX_LEN) { ids[it] } to LongArray(MAX_LEN) { mask[it] }
    }

    /** 对查询做 mean pooling + L2 归一化；失败返回 null。 */
    fun embed(text: String): FloatArray? {
        val s = session ?: return null
        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val inputs = HashMap<String, ai.onnxruntime.OnnxTensor>()
        var out: ai.onnxruntime.OrtSession.Result? = null
        return try {
            val (ids, mask) = tokenIds(text)
            inputs["input_ids"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, LongBuffer.wrap(ids), longArrayOf(1L, MAX_LEN.toLong()))
            inputs["attention_mask"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, LongBuffer.wrap(mask), longArrayOf(1L, MAX_LEN.toLong()))
            inputs["token_type_ids"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(MAX_LEN)), longArrayOf(1L, MAX_LEN.toLong()))

            out = s.run(inputs)
            val tensor = out[0] as? ai.onnxruntime.OnnxTensor ?: return null
            val info = tensor.getInfo() as? ai.onnxruntime.TensorInfo ?: return null
            val buf: FloatBuffer = tensor.floatBuffer
            val shape = info.getShape()
            if (shape.size != 3) {
                // 直接给出 [1, hidden] 的情况
                val vec = FloatArray(shape[1].toInt())
                buf.get(vec)
                normalize(vec)
            } else {
                val hidden = shape[2].toInt()
                val seq = shape[1].toInt()
                val acc = FloatArray(hidden)
                var count = 0
                for (t in 0 until seq) {
                    if (mask[t] != 1L) continue
                    for (h in 0 until hidden) acc[h] += buf[t * hidden + h]
                    count++
                }
                val denom = if (count == 0) 1f else count.toFloat()
                normalize(FloatArray(hidden) { acc[it] / denom })
            }
        } catch (e: Throwable) {
            Log.w(TAG, "bge 向量化失败（③ 降级）: ${e.message}")
            null
        } finally {
            // ⚠️ 关键修复：输出张量与输入张量必须在**所有**路径（含 tensor/info 为 null 的
            // early-return、shape!=3 分支、异常）释放，否则长跑 native 内存只增不减。
            // OrtSession.Result 本身是 AutoCloseable（它同时是 Iterable<Entry>，
            // 故不能对它的 Entry 调 close——Entry 没有 close）。直接关 Result 才会释放其持有的
            // 全部输出 OnnxValue 的 native 内存。
            runCatching { out?.close() }
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val n = kotlin.math.sqrt(sum)
        if (n > 1e-6f) for (i in v.indices) v[i] /= n
        return v
    }
}
