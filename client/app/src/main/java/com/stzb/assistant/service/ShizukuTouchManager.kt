package com.stzb.assistant.service

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 免 Root 底层 ADB 触控管理器
 * 作用：利用 Shizuku 的系统级 Binder 服务直接向底层派发 input tap / input swipe，
 * 彻底攻克小米 HyperOS、华为鸿蒙、OV 等系统强制查杀无障碍服务（AccessibilityService）的痛点，
 * 与无障碍构成“双通道容灾互备”。
 */
object ShizukuTouchManager {

    private const val TAG = "ShizukuTouch"
    const val REQUEST_CODE_SHIZUKU = 5001

    /**
     * 检查 Shizuku 服务端是否已在运行
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 检查是否已获得 Shizuku 授权
     */
    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 请求 Shizuku 授权弹窗
     */
    fun requestPermission(activity: Activity) {
        if (isShizukuAvailable() && !hasPermission()) {
            try {
                Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            } catch (e: Throwable) {
                Log.e(TAG, "请求 Shizuku 授权异常: ${e.message}")
            }
        }
    }

    private fun executeShellCommand(cmd: String): Boolean {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as java.lang.Process
            process.waitFor() == 0
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 底层执行指令异常: ${e.message}")
            false
        }
    }

    /**
     * 通过 Shizuku 执行底层的物理像素点击
     */
    suspend fun clickReal(realX: Float, realY: Float): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        executeShellCommand("input tap ${realX.toInt()} ${realY.toInt()}")
    }

    /**
     * 通过 Shizuku 执行底层的物理像素滑动
     */
    suspend fun swipeReal(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        executeShellCommand("input swipe ${x1.toInt()} ${y1.toInt()} ${x2.toInt()} ${y2.toInt()} $durationMs")
    }
}
