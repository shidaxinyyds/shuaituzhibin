package com.stzb.assistant

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/**
 * 原生 C++ 底层安全与卡密校验桥接器 (SecurityBridge)
 */
object SecurityBridge {

    private const val TAG = "SecurityBridge"
    private const val PREFS_NAME = "stzb_security_prefs"
    private const val KEY_WATERMARK = "clock_high_watermark"

    init {
        try {
            System.loadLibrary("RapidOcr")
            Log.d(TAG, "libRapidOcr 原生安全模块加载成功。")
        } catch (e: Throwable) {
            Log.e(TAG, "加载原生库失败: ${e.message}")
        }
    }

    /**
     * 调用 C++ 原生层 HMAC-SHA256 算法校验授权凭证
     * 0 网络请求，0 暴露私钥，毫秒级本地完成
     */
    external fun verifyLicenseToken(
        deviceId: String,
        token: String,
        currentTimestamp: Long
    ): Boolean

    /**
     * 生成全设备唯一 SHA-256 硬件指纹 (Device ID)
     */
    fun getDeviceId(context: Context): String {
        return try {
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: "unknown_android_id"

            val rawFingerprint = "${Build.BRAND}|${Build.MANUFACTURER}|${Build.MODEL}|${Build.HARDWARE}|$androidId"
            sha256Hex(rawFingerprint)
        } catch (e: Exception) {
            Log.e(TAG, "生成设备指纹异常: ${e.message}")
            "0000000000000000000000000000000000000000000000000000000000000000"
        }
    }

    /**
     * 单调时钟与时间高水位防回拨校验 (防止用户修改系统日期白嫖卡密)
     */
    fun checkAndRecordTimeWatermark(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSeen = prefs.getLong(KEY_WATERMARK, 0L)
        val nowSec = System.currentTimeMillis() / 1000L

        // 如果当前时间比上次记录的高水位时间倒退超过 5 分钟，判定为恶意篡改系统时间
        if (lastSeen > 0 && nowSec < (lastSeen - 300)) {
            Log.e(TAG, "🚨 检测到系统时间被恶意回拨！历史高水位: $lastSeen, 当前时间: $nowSec")
            return false
        }

        if (nowSec > lastSeen) {
            prefs.edit().putLong(KEY_WATERMARK, nowSec).apply()
        }
        return true
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
