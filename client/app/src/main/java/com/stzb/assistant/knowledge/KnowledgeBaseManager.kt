package com.stzb.assistant.knowledge

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 跨游戏知识库总控管理器 (KnowledgeBaseManager)
 * 
 * 核心职责：
 *   1. 【多游戏即插即用】：原生搭载《率土之滨》与《三国志·战略版》知识库，秒级无缝热切换；
 *   2. 【脱机离线内置保障】：断网脱机时自动使用官方内置 Profile，零网络死锁；
 *   3. 【云端静默热更新】：支持从 Supabase 云端拉取最新守军打分、按键别名与战术数值，无需重新发布 APK；
 *   4. 【本地分级缓存管理】：从云端拉取的新配置自动持久化到本地沙盒，下次冷启动毫秒级秒开。
 */
object KnowledgeBaseManager {

    private const val TAG = "KnowledgeBaseManager"
    private const val PREFS_NAME = "stzb_knowledge_prefs"
    private const val KEY_ACTIVE_GAME_ID = "active_game_id"

    // 内置官方知识库注册表
    private val builtInProfiles: Map<String, GameProfile> = mapOf(
        "stzb" to StzbKnowledgeBase.instance,
        "sgz" to SgzKnowledgeBase.instance
    )

    // 运行时当前激活的知识库
    @Volatile
    var activeProfile: GameProfile = StzbKnowledgeBase.instance
        private set

    // 变更监听器
    interface ProfileChangeListener {
        fun onProfileChanged(newProfile: GameProfile)
    }

    private val listeners = CopyOnWriteArrayList<ProfileChangeListener>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * 引擎初始化：加载用户上次选中的游戏知识库，并优先读取本地缓存的热更新版本
     */
    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedGameId = prefs.getString(KEY_ACTIVE_GAME_ID, "stzb") ?: "stzb"
        loadProfileForGame(context, savedGameId)
        Log.i(TAG, "知识库总控就绪，当前激活游戏: [${activeProfile.gameName}] (版本: ${activeProfile.profileVersion})")
    }

    fun registerListener(listener: ProfileChangeListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun unregisterListener(listener: ProfileChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 切换当前激活的游戏知识库 (例如从率土切换到三战)
     */
    fun switchGame(context: Context, gameId: String): Boolean {
        val success = loadProfileForGame(context, gameId)
        if (success) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_ACTIVE_GAME_ID, gameId).apply()
            listeners.forEach { it.onProfileChanged(activeProfile) }
            Log.i(TAG, "已成功切换游戏知识库为: ${activeProfile.gameName}")
        }
        return success
    }

    /**
     * 获取所有支持的游戏列表
     */
    fun getSupportedGames(): List<Pair<String, String>> {
        return builtInProfiles.map { (id, profile) -> Pair(id, profile.gameName) }
    }

    private fun loadProfileForGame(context: Context, gameId: String): Boolean {
        val baseProfile = builtInProfiles[gameId] ?: return false

        // 尝试从本地沙盒缓存载入云端更新过的知识库
        val cacheFile = getProfileCacheFile(context, gameId)
        if (cacheFile.exists()) {
            try {
                val jsonStr = cacheFile.readText(Charsets.UTF_8)
                val cachedProfile = GameProfile.fromJson(jsonStr)
                // 仅当缓存版本号大于等于内置版本时采用
                if (cachedProfile.profileVersion >= baseProfile.profileVersion) {
                    activeProfile = cachedProfile
                    Log.i(TAG, "已从本地沙盒加载热更新知识库: ${cachedProfile.gameId} (版本: ${cachedProfile.profileVersion})")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析本地知识库缓存失败，将平滑降级至内置版本: ${e.message}")
            }
        }

        activeProfile = baseProfile
        return true
    }

    data class UpdateResult(
        val isUpdated: Boolean,
        val currentVersion: String,
        val remoteVersion: String,
        val message: String
    )

    /**
     * 从 Supabase 云端拉取知识库最新热更新
     */
    suspend fun checkCloudUpdate(
        context: Context,
        gameId: String = activeProfile.gameId,
        endpointUrl: String = com.stzb.assistant.license.LicenseManager.supabaseEndpointUrl,
        anonKey: String = com.stzb.assistant.license.LicenseManager.supabaseAnonKey
    ): Result<UpdateResult> = withContext(Dispatchers.IO) {
        Log.i(TAG, "正在检查游戏 [$gameId] 的云端知识库热更...")

        try {
            val reqJson = JSONObject().apply {
                put("action", "get_profile")
                put("game_id", gameId)
                put("current_version", activeProfile.profileVersion)
            }

            val body = reqJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(endpointUrl)
                .addHeader("apikey", anonKey)
                .addHeader("Authorization", "Bearer $anonKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val respStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("云端服务器响应异常 (${response.code})"))
            }

            val respJson = JSONObject(respStr)
            val ok = respJson.optBoolean("ok", false)
            if (!ok) {
                val err = respJson.optString("error", "未知错误")
                return@withContext Result.failure(Exception(err))
            }

            val hasNewVersion = respJson.optBoolean("has_new_version", false)
            val remoteVersion = respJson.optString("latest_version", activeProfile.profileVersion)

            if (!hasNewVersion || !respJson.has("profile_json")) {
                return@withContext Result.success(
                    UpdateResult(false, activeProfile.profileVersion, remoteVersion, "已是最新版本 (${activeProfile.profileVersion})")
                )
            }

            val profileJsonObj = respJson.getJSONObject("profile_json")
            val newProfile = GameProfile.fromJson(profileJsonObj.toString())

            // 写入本地沙盒持久化
            val cacheFile = getProfileCacheFile(context, gameId)
            cacheFile.writeText(profileJsonObj.toString(), Charsets.UTF_8)

            // 热刷新内存
            if (activeProfile.gameId == gameId) {
                activeProfile = newProfile
                listeners.forEach { it.onProfileChanged(activeProfile) }
            }

            Log.i(TAG, "🎉 知识库热更新成功！新版本: ${newProfile.profileVersion}")
            Result.success(
                UpdateResult(true, activeProfile.profileVersion, remoteVersion, "知识库已热更新至: ${newProfile.profileVersion}")
            )

        } catch (e: Exception) {
            Log.e(TAG, "检查知识库云端更新失败: ${e.message}")
            Result.failure(Exception("网络异常，无法连接云端知识库服务"))
        }
    }

    /**
     * 重置恢复为官方内置默认知识库 (清除本地热更新缓存)
     */
    fun resetToBuiltIn(context: Context, gameId: String = activeProfile.gameId) {
        val cacheFile = getProfileCacheFile(context, gameId)
        if (cacheFile.exists()) cacheFile.delete()
        loadProfileForGame(context, gameId)
        listeners.forEach { it.onProfileChanged(activeProfile) }
        Log.i(TAG, "已重置 [$gameId] 知识库为官方内置初始版本。")
    }

    private fun getProfileCacheFile(context: Context, gameId: String): File {
        val dir = File(context.filesDir, "profiles")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${gameId}_profile.json")
    }
}
