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
 *   2. 自动获取 PowerManager 唤醒锁，保持屏幕点亮，避免手机休眠冻结无障碍与截屏通道。
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
            // 1. 点亮屏幕并保持常亮
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "StzbAssistant:RaidAlarmWakeLock"
            ).apply {
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
}
