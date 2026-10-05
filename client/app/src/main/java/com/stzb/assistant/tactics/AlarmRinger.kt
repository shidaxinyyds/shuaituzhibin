package com.stzb.assistant.tactics

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * 战术报警与设备唤醒器 (AlarmRinger)
 * 
 * 核心痛点解决：
 *   1. 凌晨 3:00~5:00 突发敌袭偷家时，高分贝铃声 + 持续震动强力唤醒玩家；
 *   2. 获取 PARTIAL_WAKE_LOCK 保持 CPU 清醒，确保手机休眠时铃声/震动仍能持续
 *      （真正点亮屏幕需全屏通知意图，本处不伪造“亮屏”能力）。
 */
object AlarmRinger {

    private const val TAG = "AlarmRinger"
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRinging = false

    /**
     * 触发高分贝报警与持续震动
     */
    fun startAlarm(context: Context) {
        if (isRinging) return
        isRinging = true
        Log.w(TAG, "🚨 触发夜战敌袭紧急警报！正在唤醒设备与拉响警报...")

        try {
            // 1. 保持 CPU 清醒（PARTIAL_WAKE_LOCK 未废弃且在各版本都有效）。
            //    ⚠️ 原先用 SCREEN_BRIGHT_WAKE_LOCK|ACQUIRE_CAUSES_WAKEUP：该级别自 API 19 起废弃、
            //    在现代 Android 上**根本点不亮屏幕**，只是“看起来在亮屏”。真要在息屏时唤醒玩家，
            //    靠的是下面的 USAGE_ALARM 铃声 + 持续震动；亮屏需全屏通知意图（另行实现）。
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StzbAssistant:RaidAlarmWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L) // 最长保持 10 分钟
            }

            // 2. 播放系统最高优先级警报铃声
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            ringtone = RingtoneManager.getRingtone(context, alarmUri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }

            // 3. 强力交替节奏震动 (0ms 开始, 震动 800ms, 静止 200ms, 震动 800ms...)
            val pattern = longArrayOf(0, 800, 200, 800, 200, 1200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "拉响警报异常: ${e.message}", e)
        }
    }

    /**
     * 停止报警与释放唤醒锁
     */
    fun stopAlarm(context: Context) {
        if (!isRinging) return
        isRinging = false
        Log.i(TAG, "⏹️ 警报已手动解除。")

        try {
            ringtone?.stop()
            ringtone = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.cancel()
            }

            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "停止警报异常: ${e.message}", e)
        }
    }

    /**
     * 触发一次性紧急提示（用于练级熔断等需即时唤醒玩家的场景）。
     *
     * 与 [startAlarm] 的区别：本方法**不进入持续鸣叫状态机**（不改动 isRinging、
     * 不占用/申请 WakeLock），只给一段有界（约 3 秒后自动停止）的提示音 + 单次震动，
     * 避免误触发 10 分钟不解除的敌袭警报。
     */
    fun triggerEmergencyAlarm(context: Context, message: String) {
        Log.w(TAG, "🚨 触发一次性紧急提示: $message")
        try {
            // 1. 一次性强震动（createWaveform repeat=-1，播一遍即止）
            val pattern = longArrayOf(0, 500, 250, 500, 250, 900)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(pattern, -1)
                }
            }

            // 2. 有界提示音：USAGE_ALARM 穿透勿扰，播约 3 秒后主动停止，不循环、不影响敌袭鸣叫状态机
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val tone = uri?.let { RingtoneManager.getRingtone(context, it) }?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                play()
            }
            if (tone != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { tone.stop() }
                }, 3000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "紧急提示异常: ${e.message}", e)
        }
    }
}
