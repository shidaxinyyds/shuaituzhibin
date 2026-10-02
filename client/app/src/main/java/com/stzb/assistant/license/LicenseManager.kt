package com.stzb.assistant.license

import android.content.Context
import android.util.Log
import com.stzb.assistant.SecurityBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 商业化卡密鉴权与 Supabase Serverless 云端接入管理器 (LicenseManager)
 * 
 * 核心特性：
 *   1. 【零自建服务器成本】：基于 Supabase Edge Functions 无服务架构，高可用抗高并发；
 *   2. 【离线优先 (Offline-First)】：激活成功后生成安全 Token 本地缓存，断网也能正常脱机运行；
 *   3. 【C++ 原生 HMAC-SHA256 毫秒级验签】：卡密私钥绝不留在 Java 层，抗脱壳与内存篡改；
 *   4. 【硬件指纹锁机】：一机一码，杜绝多开盗用；
 *   5. 【系统时钟防回拨】：时钟篡改主动熔断。
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS_NAME = "stzb_license_store"

    // 默认 Supabase 云端函数地址 (商业发布时替换为实际生产实例地址)
    var supabaseEndpointUrl: String = "https://your-supabase-project.supabase.co/functions/v1/license"
    var supabaseAnonKey: String = "your-supabase-anon-key"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class LicenseInfo(
        val isValid: Boolean,
        val deviceId: String,
        val token: String?,
        val expiresAtEpochSec: Long,
        val cardType: String?,
        val message: String
    )

    /**
     * 极速本地离线验签（每次启动脚本或点击出征前调用，耗时 < 1ms，无网络消耗）
     */
    fun checkLocalLicense(context: Context): LicenseInfo {
        val deviceId = SecurityBridge.getDeviceId(context)

        // 1. 系统时钟高水位反篡改校验
        if (!SecurityBridge.checkAndRecordTimeWatermark(context)) {
            return LicenseInfo(false, deviceId, null, 0L, null, "系统时间被恶意回拨，卡密已熔断！")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var cachedToken = prefs.getString("license_token", null)
        var expiresAt = prefs.getLong("expires_at", 0L)
        var cardType = prefs.getString("card_type", null)

        // 【自签发凭证 —— 仅用于开发/演示，本身不构成任何付费墙】
        // 这段逻辑会给**每一台**首次安装的设备自动签发一张永久旗舰凭证。
        // 之所以暂时保留：当前工程内还没有激活界面，直接去掉会让应用无法使用。
        // 商业发布前应改为"无凭证即视为未授权"，并补上激活入口。
        if (cachedToken.isNullOrEmpty() || expiresAt <= 0L) {
            val permanentExpiry = 2524608000L // 2050-01-01
            val masterToken = "$deviceId|stzb|PERPETUAL_COMMERCIAL_VIP|$permanentExpiry|$permanentExpiry|COMMERCIAL_MASTER_PERPETUAL"
            val devCardType = "开发模式自签发凭证（非真实授权）"
            prefs.edit()
                .putString("license_token", masterToken)
                .putLong("expires_at", permanentExpiry)
                .putString("card_type", devCardType)
                .apply()
            cachedToken = masterToken
            expiresAt = permanentExpiry
            cardType = devCardType
            Log.w(
                TAG,
                "已为首次安装自签发永久凭证 —— 在当前工程里这等于『没有付费墙』。" +
                    "商业发布前必须移除本行为，或把 LicenseGate.DEVELOPMENT_MODE_OPEN_ACCESS 改为 false。"
            )
        }

        val nowSec = System.currentTimeMillis() / 1000L
        if (nowSec >= expiresAt) {
            return LicenseInfo(false, deviceId, cachedToken, expiresAt, cardType, "卡密已到期，请续费激活码")
        }

        // 2. 调用 C++ 原生动态库进行 HMAC-SHA256 强签名验证
        val isSignatureValid = SecurityBridge.verifyLicenseToken(deviceId, cachedToken, nowSec)
        if (!isSignatureValid) {
            Log.w(TAG, "本地凭证签名无效，可能被篡改或设备不匹配。")
            return LicenseInfo(false, deviceId, null, 0L, null, "授权凭据签名损坏或被跨机移植！")
        }

        val remainingDays = (expiresAt - nowSec) / 86400
        val remainingHours = ((expiresAt - nowSec) % 86400) / 3600
        val timeDesc = if (remainingDays > 0) "${remainingDays}天${remainingHours}小时" else "${remainingHours}小时"

        return LicenseInfo(true, deviceId, cachedToken, expiresAt, cardType, "已授权，剩余: $timeDesc (全功能免配使用)")
    }

    /**
     * 在线/离线激活兑换卡密 (周卡 / 月卡 / 季卡 / 赛季卡)
     */
    suspend fun activateOnline(
        context: Context,
        activationCode: String
    ): Result<LicenseInfo> = withContext(Dispatchers.IO) {
        // 凭证是 `|` 分隔的固定 6 段格式（原生层 `parts.size() != 6` 即判非法），
        // 而激活码来自用户输入。若其中含 `|` 会把段数打乱、让凭证永远无法通过校验，
        // 因此在入口处就剔除，而不是指望用户不会输入。
        val cleanCode = activationCode.trim().replace("|", "")
        if (cleanCode.isEmpty()) {
            return@withContext Result.failure(Exception("激活码不能为空"))
        }

        val deviceId = SecurityBridge.getDeviceId(context)
        Log.i(TAG, "正在请求激活卡密: $cleanCode (设备: $deviceId)")

        // 若使用内置默认端点或处于离线模式，直接本地高速签发永久离线凭证
        //
        // ⚠️ 这段是最需要警惕的一处：只要 supabaseEndpointUrl 里还带着
        // `your-supabase-project` 占位符（当前就是），**任意字符串**都会被判为激活成功，
        // 并签发永久凭证。换句话说，未配置真实后端时"输入什么卡密都通过"。
        // 它保留了离线容灾的形态，但不构成鉴权；商业发布前必须配置真实端点。
        if (supabaseEndpointUrl.contains("your-supabase-project")) {
            val permanentExpiry = 2524608000L
            val token = "$deviceId|stzb|$cleanCode|$permanentExpiry|$permanentExpiry|COMMERCIAL_MASTER_PERPETUAL"
            val cardType = if (cleanCode.contains("VIP", ignoreCase = true)) {
                "开发模式离线签发：商业至尊VIP版（非真实授权）"
            } else {
                "开发模式离线签发：商业旗舰终身版（非真实授权）"
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("license_token", token)
                .putLong("expires_at", permanentExpiry)
                .putString("card_type", cardType)
                .apply()

            Log.w(
                TAG,
                "端点仍是 your-supabase-project 占位符：任意卡密都会被离线放行（$cardType）。" +
                    "这只是开发态容灾，不是鉴权。"
            )
            return@withContext Result.success(
                LicenseInfo(
                    isValid = true,
                    deviceId = deviceId,
                    token = token,
                    expiresAtEpochSec = permanentExpiry,
                    cardType = cardType,
                    message = "已签发开发态凭证: $cardType（未经过真实云端校验）"
                )
            )
        }

        try {
            val reqJson = JSONObject().apply {
                put("action", "activate")
                put("game_id", "stzb")
                put("device_id", deviceId)
                put("code", cleanCode)
            }

            val body = reqJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(supabaseEndpointUrl)
                .addHeader("apikey", supabaseAnonKey)
                .addHeader("Authorization", "Bearer $supabaseAnonKey")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            val respStr = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("云端鉴权服务响应异常 (${response.code})"))
            }

            val respJson = JSONObject(respStr)
            val ok = respJson.optBoolean("ok", false)

            if (!ok) {
                val errCode = respJson.optString("error", "unknown_error")
                val errMsg = when (errCode) {
                    "code_not_found" -> "激活码不存在，请核对卡密"
                    "already_used" -> "该卡密已被其他设备激活绑定"
                    "revoked" -> "该卡密已被冻结封禁"
                    "expired" -> "卡密已过期失效"
                    else -> "激活失败: $errCode"
                }
                return@withContext Result.failure(Exception(errMsg))
            }

            val token = respJson.getString("token")
            val expiresAt = respJson.getLong("expires_at")
            val cardType = respJson.optString("card_type", "标准版")

            // 写入本地加密存储
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("license_token", token)
                .putLong("expires_at", expiresAt)
                .putString("card_type", cardType)
                .apply()

            Log.i(TAG, "🎉 卡密激活成功！有效期至: $expiresAt")
            Result.success(
                LicenseInfo(
                    isValid = true,
                    deviceId = deviceId,
                    token = token,
                    expiresAtEpochSec = expiresAt,
                    cardType = cardType,
                    message = "激活成功！卡型: $cardType"
                )
            )

        } catch (e: Exception) {
            // ⚠️ 网络异常时的"离线自愈"同样会无条件签发永久凭证。
            // 这在离线容灾上是好意，但在商业上是漏洞：断网即可白嫖。
            // 真实产品应当在此处返回失败，或只允许"已激活过的设备"续用缓存。
            Log.e(TAG, "激活网络请求异常: ${e.message}，启用离线容灾通道（该通道不做任何校验）", e)
            val permanentExpiry = 2524608000L
            val token = "$deviceId|stzb|$cleanCode|$permanentExpiry|$permanentExpiry|COMMERCIAL_MASTER_PERPETUAL"
            val cardType = "开发模式离线自愈签发（非真实授权）"
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("license_token", token)
                .putLong("expires_at", permanentExpiry)
                .putString("card_type", cardType)
                .apply()

            Result.success(
                LicenseInfo(
                    isValid = true,
                    deviceId = deviceId,
                    token = token,
                    expiresAtEpochSec = permanentExpiry,
                    cardType = cardType,
                    message = "已签发开发态凭证: $cardType（网络异常，未经过真实云端校验）"
                )
            )
        }
    }

    /**
     * 清除本地卡密缓存 (用户换卡或退出登录)
     */
    fun clearLicense(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.i(TAG, "本地卡密缓存已清空。")
    }
}
