package com.stzb.assistant.ai.advisor

import android.content.Context
import android.util.Log
import com.stzb.assistant.ai.rag.SlgRagEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 诸葛军师云端大模型桥接中枢 (MilitaryAdvisorCloudBridge)
 *
 * ## 商业化双脑架构说明
 * 解决移动端 SLG 辅助在运行大型 3D 游戏（率土之滨）时，无法在后台常驻运行
 * 500MB~1GB 离线大语言模型（会导致 Android 系统 LowMemoryKiller 强杀进程）的核心痛点：
 *
 * 1. 【端侧 RAG 向量底座】：本地高速检索 100% 精确的武将三围、战法克制、土地守军评分与坐标时间，零延迟、零幻觉。
 * 2. 【云端 LLM 大脑】：对接 DeepSeek-V3/R1、阿里通义千问 Qwen 或 OpenAI 兼容格式大模型。
 *    将端侧 RAG 检索到的游戏事实注入 Prompt，让大模型输出文采飞扬、策略深邃的军师推演、战报会诊与实时问策。
 * 3. 【无缝自动降级】：网络异常或未配置 API Key 时，自动回退到本地 RAG 规则叙事，确保绝对不崩溃、不卡顿。
 */
object MilitaryAdvisorCloudBridge {

    private const val TAG = "MilitaryAdvisorBridge"
    private const val PREFS_NAME = "stzb_military_advisor_prefs"
    private const val KEY_ENABLED = "cloud_ai_enabled"
    private const val KEY_ENDPOINT = "api_endpoint"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model_name"

    // 默认预设端点（支持 DeepSeek、通义千问、硅基流动等 OpenAI 兼容接口）
    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"
    const val DEFAULT_MODEL = "deepseek-chat"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    data class AdvisorConfig(
        val enabled: Boolean,
        val endpoint: String,
        val apiKey: String,
        val model: String
    )

    fun loadConfig(context: Context): AdvisorConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return AdvisorConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            endpoint = prefs.getString(KEY_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
            apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        )
    }

    fun saveConfig(
        context: Context,
        enabled: Boolean,
        endpoint: String,
        apiKey: String,
        model: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_ENDPOINT, endpoint.trim())
            .putString(KEY_API_KEY, apiKey.trim())
            .putString(KEY_MODEL, model.trim())
            .apply()
    }

    fun isCloudAiActive(context: Context): Boolean {
        val config = loadConfig(context)
        return config.enabled && config.apiKey.isNotBlank()
    }

    fun getEngineDisplayName(context: Context): String {
        val config = loadConfig(context)
        return if (config.enabled && config.apiKey.isNotBlank()) {
            "端云双脑 (${config.model} 大模型接入)"
        } else {
            "端侧 RAG 向量底座 (100% 离线模式)"
        }
    }

    /**
     * 核心接口 1：问策诸葛军师（自由对话与战术咨询）
     */
    fun askAdvisor(
        context: Context,
        userQuery: String,
        callback: (success: Boolean, response: String) -> Unit
    ) {
        val config = loadConfig(context)
        if (!config.enabled || config.apiKey.isBlank()) {
            // 离线状态：利用端侧 RAG 知识库匹配回答
            val ragAdvice = SlgRagEngine.matchDecreeTactics(userQuery)
            val fallbackResponse = buildString {
                append("【诸葛军师 · 端侧离线推演】\n")
                append("主公，当前处于端侧离线模式。关于“$userQuery”的战术分析如下：\n\n")
                append("▶ 兵法建议: ").append(ragAdvice.executionTimingAdvice).append("\n")
                append("▶ 阵型协同: ").append(ragAdvice.teamRoleRequirement).append("\n")
                append("▶ 应急机变: ").append(ragAdvice.contingencyPlan).append("\n\n")
                append("💡 提示：在主界面开启「云端大模型大脑」并填入 API Key，可解锁 DeepSeek/千问 深度自然语言推演与实时对话。")
            }
            callback(true, fallbackResponse)
            return
        }

        // 云端大模型请求：注入 RAG 检索上下文与诸葛孔明角色设定
        scope.launch {
            try {
                // 1. 端侧 RAG 检索最贴近的战术条目
                val ragData = SlgRagEngine.matchDecreeTactics(userQuery)
                val systemPrompt = buildSystemPrompt(
                    extraContext = "端侧 RAG 检索关联事实：【战术指引】${ragData.executionTimingAdvice}；【队伍角色】${ragData.teamRoleRequirement}；【应急机变】${ragData.contingencyPlan}"
                )

                val reply = requestChatCompletion(
                    endpoint = config.endpoint,
                    apiKey = config.apiKey,
                    model = config.model,
                    systemPrompt = systemPrompt,
                    userMessage = userQuery
                )

                withContext(Dispatchers.Main) {
                    callback(true, reply)
                }
            } catch (e: Exception) {
                Log.e(TAG, "云端军师请求异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    // 异常时优雅降级
                    val ragAdvice = SlgRagEngine.matchDecreeTactics(userQuery)
                    callback(
                        false,
                        "【诸葛军师 · 云端连接受阻，已降级端侧 RAG】\n" +
                                "网络或密钥异常（${e.message}）。转由端侧底座推演：\n" +
                                "▶ 建议: ${ragAdvice.executionTimingAdvice}\n" +
                                "▶ 队伍: ${ragAdvice.teamRoleRequirement}"
                    )
                }
            }
        }
    }

    /**
     * 核心接口 2：战报深度云端会诊 (结合 RAG 向量特征 + 大模型博弈分析)
     */
    fun diagnoseBattleReport(
        context: Context,
        reportText: String,
        detectedSkills: List<String>,
        callback: (success: Boolean, response: String) -> Unit
    ) {
        val config = loadConfig(context)
        val ragDiag = SlgRagEngine.diagnoseBattleReport(reportText)

        if (!config.enabled || config.apiKey.isBlank()) {
            // 离线模式：直接输出 RAG 结构化会诊
            val offlineResponse = buildString {
                append("【诸葛军师 · RAG战报会诊 (离线底座)】\n")
                if (detectedSkills.isNotEmpty()) {
                    append("▶ 检出关键战法: ").append(detectedSkills.joinToString("、")).append("\n")
                }
                append("▶ 机制复盘: ").append(ragDiag.conflictAnalysis).append("\n")
                append("▶ 阵型调优: ").append(ragDiag.counterStrategy).append("\n\n")
                append("💡 提示：开启云端大模型可获得万字战报逐回合对弈复盘与变阵方案。")
            }
            callback(true, offlineResponse)
            return
        }

        // 联网调用 DeepSeek / Qwen 深度会诊
        scope.launch {
            try {
                val promptContext = buildString {
                    append("【战报原文识别截取】:\n").append(reportText.take(500)).append("\n\n")
                    append("【端侧 RAG 神经检索事实】:\n")
                    append("- 检出关键战法: ").append(detectedSkills.joinToString("、")).append("\n")
                    append("- 战法机制解析: ").append(ragDiag.conflictAnalysis).append("\n")
                    append("- 建议克制策略: ").append(ragDiag.counterStrategy).append("\n")
                }

                val userMsg = "请根据上述战报内容与 RAG 检索数据，以诸葛军师的身份进行战报深度会诊：\n" +
                        "1. 定性胜负关键点与转折回合\n" +
                        "2. 深度分析双方战法与武将克制关系（特别是封锁、爆发或减伤机制）\n" +
                        "3. 给出针对性的调优变阵建议（如战法替换、速度加点或替补武将）"

                val systemPrompt = buildSystemPrompt(promptContext)

                val reply = requestChatCompletion(
                    endpoint = config.endpoint,
                    apiKey = config.apiKey,
                    model = config.model,
                    systemPrompt = systemPrompt,
                    userMessage = userMsg
                )

                withContext(Dispatchers.Main) {
                    callback(true, reply)
                }
            } catch (e: Exception) {
                Log.e(TAG, "云端战报会诊失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback(
                        false,
                        "【诸葛军师 · 云端请求超时，转端侧 RAG 复盘】\n" +
                                "▶ 机制解析: ${ragDiag.conflictAnalysis}\n" +
                                "▶ 变阵指引: ${ragDiag.counterStrategy}"
                    )
                }
            }
        }
    }

    /**
     * 核心接口 3：军令/全盟邮件深度战略解读
     */
    fun interpretAllianceDecree(
        context: Context,
        decreeText: String,
        parsedTargetName: String,
        parsedCoord: Pair<Int, Int>?,
        parsedTimeMs: Long,
        callback: (success: Boolean, response: String) -> Unit
    ) {
        val config = loadConfig(context)
        if (!config.enabled || config.apiKey.isBlank()) {
            return
        }

        scope.launch {
            try {
                val coordStr = if (parsedCoord != null) "(${parsedCoord.first}, ${parsedCoord.second})" else "未明"
                val systemPrompt = buildSystemPrompt(
                    "同盟军令基础解析结果：目标【$parsedTargetName】，坐标【$coordStr】。"
                )
                val userMsg = "这是全盟军令原文：\n「$decreeText」\n请以诸葛军师的谋略视角，提炼全盟核心战术意图、高战与拆迁分工要诀，并给出压秒铺路锦囊。"

                val reply = requestChatCompletion(
                    endpoint = config.endpoint,
                    apiKey = config.apiKey,
                    model = config.model,
                    systemPrompt = systemPrompt,
                    userMessage = userMsg
                )

                withContext(Dispatchers.Main) {
                    callback(true, reply)
                }
            } catch (e: Exception) {
                Log.w(TAG, "解读军令失败: ${e.message}")
            }
        }
    }

    /**
     * 构建诸葛军师系统提示词 (Prompt Engineering)
     */
    private fun buildSystemPrompt(extraContext: String = ""): String {
        return buildString {
            append("你是《率土之滨》智能军师——诸葛孔明。")
            append("你精通三国战法兵机，深谙网易《率土之滨》全赛季战斗机制（包括流氓队、法刀、蜀步、肉步、砍王、魏智等主流阵容，神兵大赏爆发、反计战必双封、垒实健卒减伤防御体系，以及大地图卡免、破免、压秒铺路、远射集火与守军难度分级）。\n")
            append("【回答准则】：\n")
            append("1. 身份定位：羽扇纶巾，运筹帷幄。言谈兼具三国军事家风骨与当代率土顶级玩家的实战黑话，语气沉稳自信，条理清晰。\n")
            append("2. 切中要害：杜绝套话空话，直接指出队伍胜负手、核心战法冲突、速度抢先手与阵型痛点。\n")
            append("3. 严格遵循真实游戏事实：不得凭空捏造不存在的武将技能或胡乱编造地图坐标。\n")
            if (extraContext.isNotBlank()) {
                append("【当前战局端侧事实与RAG知识参考】：\n")
                append(extraContext).append("\n")
            }
        }
    }

    /**
     * 发送标准 OpenAI 兼容的 Chat Completions 请求
     */
    private suspend fun requestChatCompletion(
        endpoint: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val root = JSONObject().apply {
            put("model", model)
            put("temperature", 0.6)
            put("max_tokens", 800)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            }
            put("messages", messages)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = root.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}: $responseBody")
        }

        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw IllegalStateException("返回格式异常: 无 choices 节点")
        }

        val firstChoice = choices.getJSONObject(0)
        val message = firstChoice.optJSONObject("message")
        return@withContext message?.optString("content")?.trim() ?: "军师推演未获文字回应"
    }
}
