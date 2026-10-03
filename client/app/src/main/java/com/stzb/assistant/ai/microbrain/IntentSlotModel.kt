package com.stzb.assistant.ai.microbrain

import android.content.Context
import android.util.Log
import com.stzb.assistant.ai.assets.ModelAssetManager
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * 端侧意图+槽位微脑 (IntentSlotModel) —— 方案 A++ 判别式认知引擎
 *
 * ## 它是什么
 * 用 ONNX Runtime Mobile 跑 `models/intent_slot_zh.onnx`（rbt3 微调 + INT8），把自然语言
 * 军令映射成**受约束的槽位类别**，再拼成语法合法的 DSL。这与 tools/p1/train_intent_slot.py
 * 的训练/导出契约严格对齐：
 *   - 输入：input_ids / attention_mask / token_type_ids（int64，[1, MAX_LEN]）
 *   - 输出（顺序固定，共 9 个头）：
 *     intent, target, hour, min, coord, troop, role, cont, level
 *   - 意图 9 类、坐标 10×10 桶取桶心、时间 24×(12×5)、兵力对数桶、目标取自词表。
 *
 * ## 关键诚实点（与全项目 fail-closed 原则一致）
 * 1. **真实推理**，不是正则；但只在三件资产齐备时才可用：
 *    `intent_slot_zh.onnx` + `intent_slot_token_vocab.txt`（分词词表）+ `intent_slot_vocab.txt`（目标词表）。
 *    缺任一或内存不足 → [parse] 返回 null，调用方（[EdgeSlmEngine]）自动回落正则，行为不变。
 * 2. **零坐标幻觉**：坐标只能落在 10×10 桶心（合法地图范围内），模型永远编不出越界坐标。
 * 3. 分词沿用与 [com.stzb.assistant.ai.rag.BgeEmbedder] 同款的中文字符 bigram + CLS/SEP，
 *    离线零依赖；比 HF WordPiece 粗，但对军令短语足够（且与训练侧 token 分布同源）。
 * 4. ORT 用法严格遵循工程既有教训：`getEnvironment()`、shape 用全 Long 字面量、
 *    Result 与输入张量在**所有**路径都要 close（防长跑 native 内存只增不减）。
 */
object IntentSlotModel {

    private const val TAG = "IntentSlotModel"
    private const val MAX_LEN = 96
    private const val COST_MB = 24

    // 与 train_intent_slot.py 严格一致（改一处必须同步改另一处，否则槽位会错解码）
    private val INTENT_LIST = listOf(
        "ATTACK_CITY", "ATTACK_LAND", "RAID_DEFENSE", "ROAD_PAVING",
        "IMMUNITY_BREAK", "PRESS_SECOND", "CASTLE_MOVE", "MARCH_GARRISON", "UNKNOWN"
    )
    private val TROOP_BUCKETS = intArrayOf(1000, 2000, 3000, 5000, 8000, 12000, 16000, 22000, 25000, 30000)
    private const val COORD_GRID = 10
    private const val COORD_MAX = 600

    @Volatile
    private var loaded = false
    private var session: ai.onnxruntime.OrtSession? = null
    private var tokenVocab: Map<String, Int> = emptyMap()
    private var targets: List<String> = emptyList()
    private var releaseHookRegistered = false

    fun isReady(): Boolean = loaded

    data class Parsed(
        val intentRaw: String,
        val intent: OrderIntent?,          // 仅当模型给出且能安全映射到 App 语义时非空
        val target: String?,
        val coord: Pair<Int, Int>?,
        val at: String?,                    // "HH:MM"
        val troops: Int?,
        val level: Int?,
        val confidence: Float
    )

    fun release() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ORT 会话异常: ${e.message}")
        }
        session = null
        tokenVocab = emptyMap()
        targets = emptyList()
        loaded = false
        Log.i(TAG, "已释放意图微脑会话，军令解析将回落正则通道。")
    }

    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        val modelPath = ModelAssetManager.getOrExtractModelPath(context, "intent_slot_zh.onnx")
        if (modelPath == null) {
            Log.i(TAG, "未发现 intent_slot_zh.onnx，军令解析走正则通道。")
            return false
        }
        val tokPath = ModelAssetManager.getOrExtractModelPath(context, "intent_slot_token_vocab.txt")
        val tgtPath = ModelAssetManager.getOrExtractModelPath(context, "intent_slot_vocab.txt")
        if (tokPath == null || tgtPath == null) {
            Log.w(TAG, "发现意图模型但缺分词词表或目标词表，无法构造输入，放弃加载。")
            return false
        }
        if (!com.stzb.assistant.runtime.ResourceGuard.canAfford(COST_MB)) {
            Log.w(TAG, "内存不足（意图微脑约需 ${COST_MB}MB）：${com.stzb.assistant.runtime.ResourceGuard.describe()}，回落正则。")
            return false
        }
        return try {
            tokenVocab = readLineVocab(tokPath)
            targets = readTargets(tgtPath)
            val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
            session = env.createSession(modelPath, ai.onnxruntime.OrtSession.SessionOptions())
            loaded = true
            if (!releaseHookRegistered) {
                com.stzb.assistant.runtime.ResourceGuard.registerReleaseHook { release() }
                releaseHookRegistered = true
            }
            Log.i(TAG, "意图+槽位微脑已加载（真实推理），目标词表 ${targets.size} 项。")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "意图微脑加载失败（回落正则）: ${e.message}")
            session = null
            false
        }
    }

    private fun readLineVocab(path: String): Map<String, Int> {
        val map = HashMap<String, Int>()
        java.io.File(path).readLines(Charsets.UTF_8).forEachIndexed { i, token ->
            if (token.isNotEmpty() && !map.containsKey(token)) map[token] = i
        }
        return map
    }

    private fun readTargets(path: String): List<String> {
        val lines = java.io.File(path).readLines(Charsets.UTF_8)
        // 首行是注释 "# intent_slot vocab: targets=N"；其余按行即目标（index 对齐 target_logits）
        return lines.filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun tokenIds(text: String): Pair<LongArray, LongArray> {
        val chars = text.replace(" ", "").take(MAX_LEN - 2)
        val ids = ArrayList<Long>()
        val mask = ArrayList<Long>()
        fun push(token: String) {
            val id = tokenVocab[token]
            if (id != null) {
                ids.add(id.toLong()); mask.add(1L)
            }
        }
        push("[CLS]")
        var i = 0
        while (i < chars.length) {
            val hasPair = i + 1 < chars.length
            if (hasPair && tokenVocab.containsKey(chars.substring(i, i + 2))) {
                push(chars.substring(i, i + 2)); i += 2
            } else {
                val one = chars.substring(i, i + 1)
                push(if (tokenVocab.containsKey(one)) one else "[UNK]")
                i += 1
            }
        }
        push("[SEP]")
        while (ids.size < MAX_LEN) { ids.add(0L); mask.add(0L) }
        return LongArray(MAX_LEN) { ids[it] } to LongArray(MAX_LEN) { mask[it] }
    }

    /** 解析军令；不可用/异常返回 null（调用方回落正则）。 */
    fun parse(text: String): Parsed? {
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
            // 顺序固定：intent,target,hour,min,coord,troop,role,cont,level
            val intentProbs = softmax(headVec(out[0]))
            val intentIdx = argmax(intentProbs)
            val intentRaw = INTENT_LIST.getOrElse(intentIdx) { "UNKNOWN" }
            val targetIdx = argmax(headVec(out[1]))
            val hour = argmax(headVec(out[2]))
            val minute = argmax(headVec(out[3])) * 5
            val coordBucket = argmax(headVec(out[4]))
            val troopIdx = argmax(headVec(out[5]))
            // out[6]=role, out[7]=cont 这里不消费（正则/上游已处理角色与预案），保留 out[8]=level
            val level = argmax(headVec(out[8])) + 1

            if (intentRaw == "UNKNOWN") {
                return Parsed("UNKNOWN", null, null, null, null, null, null, intentProbs.getOrElse(intentIdx) { 0f })
            }

            val target = targets.getOrNull(targetIdx)?.takeIf { it.isNotBlank() }
            val coord = coordCenter(coordBucket)
            val at = "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 55))
            val troops = TROOP_BUCKETS.getOrElse(troopIdx) { 0 }.takeIf { it > 0 }

            Parsed(
                intentRaw = intentRaw,
                intent = mapIntent(intentRaw),
                target = target,
                coord = coord,
                at = at,
                troops = troops,
                level = level.coerceIn(1, 9),
                confidence = intentProbs.getOrElse(intentIdx) { 0f }
            )
        } catch (e: Throwable) {
            Log.w(TAG, "意图推理异常（回落正则）: ${e.message}")
            null
        } finally {
            runCatching { out?.close() }
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    private fun headVec(value: ai.onnxruntime.Value?): FloatArray {
        val tensor = value as? ai.onnxruntime.OnnxTensor ?: return FloatArray(0)
        val fb = tensor.floatBuffer
        val arr = FloatArray(fb.remaining())
        fb.get(arr)
        return arr
    }

    private fun argmax(v: FloatArray): Int {
        if (v.isEmpty()) return 0
        var best = 0
        for (i in 1 until v.size) if (v[i] > v[best]) best = i
        return best
    }

    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return logits
        val m = logits.maxOrNull() ?: 0f
        var sum = 0.0
        val e = FloatArray(logits.size)
        for (i in logits.indices) { e[i] = exp((logits[i] - m).toDouble()).toFloat(); sum += e[i] }
        if (sum <= 0.0) return logits
        for (i in e.indices) e[i] /= sum.toFloat()
        return e
    }

    private fun coordCenter(bucket: Int): Pair<Int, Int> {
        val step = COORD_MAX / COORD_GRID.toDouble()
        val idx = bucket.coerceIn(0, COORD_GRID * COORD_GRID - 1)
        val gx = idx % COORD_GRID
        val gy = idx / COORD_GRID
        return Pair(((gx + 0.5) * step).toInt(), ((gy + 0.5) * step).toInt())
    }

    /** 模型意图 → App OrderIntent：只在语义安全可映射时返回，否则 null（让上层保留正则结论）。 */
    private fun mapIntent(raw: String): OrderIntent? = when (raw) {
        "ATTACK_CITY", "PRESS_SECOND" -> OrderIntent.ALLIANCE_SIEGE
        "ROAD_PAVING", "ATTACK_LAND", "CASTLE_MOVE" -> OrderIntent.ROAD_PAVING
        "RAID_DEFENSE", "MARCH_GARRISON" -> OrderIntent.DEFEND_GATE
        "IMMUNITY_BREAK" -> OrderIntent.SPARTAN_SCOUT
        else -> null
    }

    /** 一句话状态，供 UI/日志展示当前微脑是"真推理"还是"正则回落"。 */
    fun describe(): String =
        if (loaded) "意图微脑：ONNX 真推理已激活（rbt3 INT8）" else "意图微脑：正则通道（未找到 intent_slot_zh.onnx 权重）"
}
