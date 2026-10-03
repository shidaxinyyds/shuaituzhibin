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

    @Volatile
    private var loaded = false

    private var session: ai.onnxruntime.OrtSession? = null
    private var vocab: Map<String, Int> = emptyMap()
    private var dim = 0

    fun isLoaded(): Boolean = loaded

    fun vectorDim(): Int = dim

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

        return try {
            vocab = readVocab(vocabPath)
            val env = ai.onnxruntime.OrtEnvironment.getEnv()
            session = env.createSession(modelPath, ai.onnxruntime.OrtSession.SessionOptions())
            dim = detectDim()
            if (dim <= 0) {
                Log.w(TAG, "无法从 ONNX 输出推断向量维度，放弃加载。")
                return false
            }
            loaded = true
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
        val shape = out[0].shape
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
            val pair = chars.substring(i, i + 2)
            if (i + 1 < chars.length && vocab.containsKey(pair)) {
                push(pair)
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
        return try {
            val (ids, mask) = tokenIds(text)
            val env = ai.onnxruntime.OrtEnvironment.getEnv()
            val inputs = HashMap<String, ai.onnxruntime.OnnxTensor>()
            inputs["input_ids"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, LongBuffer.wrap(ids), longArrayOf(1, MAX_LEN))
            inputs["attention_mask"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, LongBuffer.wrap(mask), longArrayOf(1, MAX_LEN))
            inputs["token_type_ids"] = ai.onnxruntime.OnnxTensor.createTensor(
                env, LongBuffer.wrap(LongArray(MAX_LEN)), longArrayOf(1, MAX_LEN))

            val out = s.run(inputs)
            inputs.values.forEach { it.close() }

            val tensor = out[0] as? ai.onnxruntime.OnnxTensor ?: return null
            val info = tensor.info as ai.onnxruntime.TensorInfo
            val buf: FloatBuffer = tensor.floatBuffer
            val shape = info.shape
            if (shape.size != 3) {
                // 直接给出 [1, hidden] 的情况
                val vec = FloatArray(shape[1].toInt())
                buf.get(vec)
                return normalize(vec)
            }
            val hidden = shape[2].toInt()
            val seq = shape[1].toInt()
            val acc = FloatArray(hidden)
            var count = 0
            for (t in 0 until seq) {
                val keep = mask[t] == 1L
                if (!keep) continue
                for (h in 0 until hidden) {
                    val v = buf[t * hidden + h]
                    acc[h] += v
                }
                count++
            }
            val denom = if (count == 0) 1f else count.toFloat()
            val pooled = FloatArray(hidden) { acc[it] / denom }
            out.forEach { it.close() }
            normalize(pooled)
        } catch (e: Throwable) {
            Log.w(TAG, "bge 向量化失败（③ 降级）: ${e.message}")
            null
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
