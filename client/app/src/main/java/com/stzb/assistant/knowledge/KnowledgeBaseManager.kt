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
                // 两道闸，缺一不可：
                //   1. 缓存内容必须真的可用（防止"空壳缓存"永久顶替内置版本）；
                //   2. 版本比较必须走**数值**比较（compareVersion），
                //      直接写 `>=` 是字典序，"9" 会被认为大于 "10"。
                val (usable, reason) = cachedProfile.validateFor(gameId)
                if (!usable) {
                    Log.w(TAG, "本地缓存的知识库不可用（$reason），已忽略并降级到内置版本。")
                } else if (cachedProfile.compareVersion(baseProfile.profileVersion) >= 0) {
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

    /**
     * 取某个游戏**当前生效**的知识库版本号。
     *
     * 不能直接拿 [activeProfile] 的版本去比：用户可能正在检查"另一个游戏"的更新，
     * 那样就会拿 A 游戏的版本去和 B 游戏的云端版本比较。
     */
    private fun currentVersionFor(context: Context, gameId: String): String {
        if (activeProfile.gameId == gameId) return activeProfile.profileVersion
        val cacheFile = getProfileCacheFile(context, gameId)
        if (cacheFile.exists()) {
            try {
                return GameProfile.fromJson(cacheFile.readText(Charsets.UTF_8)).profileVersion
            } catch (e: Exception) {
                Log.w(TAG, "读取 [$gameId] 缓存版本失败，回退到内置版本: ${e.message}")
            }
        }
        return builtInProfiles[gameId]?.profileVersion ?: ""
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
            val respCode: Int
            val respStr: String
            try {
                respCode = response.code
                respStr = response.body?.string() ?: ""
            } finally {
                // 显式关闭：非 2xx 时原实现直接 return，响应体不会被消费也不会被关闭，
                // 连接会一直挂到超时，长时间挂机时会累积。
                response.close()
            }

            if (respCode !in 200..299) {
                return@withContext Result.failure(Exception("云端服务器响应异常 ($respCode)"))
            }

            val respJson = JSONObject(respStr)
            val ok = respJson.optBoolean("ok", false)
            if (!ok) {
                val err = respJson.optString("error", "未知错误")
                return@withContext Result.failure(Exception(err))
            }

            val hasNewVersion = respJson.optBoolean("has_new_version", false)
            val localVersion = currentVersionFor(context, gameId)
            val remoteVersion = respJson.optString("latest_version", localVersion)

            if (!hasNewVersion || !respJson.has("profile_json")) {
                return@withContext Result.success(
                    UpdateResult(false, localVersion, remoteVersion, "已是最新版本 ($localVersion)")
                )
            }

            val profileJsonObj = respJson.getJSONObject("profile_json")
            val newProfile = GameProfile.fromJson(profileJsonObj.toString())

            // ---------- 闸 1：版本必须**严格更新** ----------
            // 不能只信服务端的 has_new_version 标志。服务端配置错误（或缓存错乱）时
            // 会把旧版本发下来，直接采用并写盘就等于**主动降级**，
            // 而且降级后的缓存还会在下次冷启动继续生效。
            val cmp = newProfile.compareVersion(localVersion)
            if (cmp <= 0) {
                Log.w(TAG, "云端知识库版本 (${newProfile.profileVersion}) 不高于本地 ($localVersion)，已忽略。")
                return@withContext Result.success(
                    UpdateResult(
                        false, localVersion, remoteVersion,
                        "云端版本 (${newProfile.profileVersion}) 不高于当前版本，已忽略"
                    )
                )
            }

            // ---------- 闸 2：内容必须真的可用 ----------
            // 空壳配置一旦写盘，下次冷启动还会继续用它，用户会静默失去全部战术能力。
            val (usable, reason) = newProfile.validateFor(gameId)
            if (!usable) {
                Log.e(TAG, "云端知识库校验未通过：$reason。已拒绝采用，本地配置未改动。")
                return@withContext Result.failure(
                    Exception("云端知识库校验未通过（$reason），已拒绝采用，本地配置未改动")
                )
            }

            // ---------- 原子写 ----------
            // 原先直接就地 writeText：一旦进程被杀或磁盘写满，本地缓存会被截断成半截 JSON。
            // 虽然下次启动会降级到内置版本（不会崩），但这次"已生效"的更新会凭空消失，
            // 用户只会觉得"更新了但没变化"。先写 .tmp 再改名可以避免这个中间态。
            val cacheFile = getProfileCacheFile(context, gameId)
            val tmpFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmpFile.writeText(profileJsonObj.toString(), Charsets.UTF_8)
            if (!tmpFile.renameTo(cacheFile)) {
                tmpFile.delete()
                Log.e(TAG, "知识库写入本地缓存失败（改名失败），本地配置未改动。")
                return@withContext Result.failure(
                    Exception("知识库写入本地缓存失败，本地配置未改动")
                )
            }

            // 热刷新内存：只有"正在使用这个游戏"时才切换激活档
            if (activeProfile.gameId == gameId) {
                activeProfile = newProfile
                listeners.forEach { it.onProfileChanged(activeProfile) }
            }

            Log.i(TAG, "🎉 知识库热更新成功！$localVersion → ${newProfile.profileVersion}")
            Result.success(
                UpdateResult(
                    true, localVersion, remoteVersion,
                    "知识库已热更新至: ${newProfile.profileVersion}"
                )
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

        // 只有"重置的正是当前激活的游戏"时才重新装载并广播。
        //
        // 原实现无条件调用 loadProfileForGame()，而它会直接给 activeProfile 赋值——
        // 于是"重置另一个游戏的知识库"会把**当前激活的游戏悄悄换掉**，
        // 而 SharedPreferences 里保存的 active_game_id 还是旧值，状态就此不一致，
        // 下次冷启动又变回来，表现为"切来切去搞不清在用哪套"。
        if (activeProfile.gameId != gameId) {
            Log.i(TAG, "已清除 [$gameId] 的本地知识库缓存；它不是当前激活游戏，因此不做切换。")
            return
        }

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
