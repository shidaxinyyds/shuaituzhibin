package com.stzb.assistant.tactics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启重建接收器 (BootCompletedReceiver)
 *
 * 唯一职责：系统重启后把持久化的定时计划重新登记回硬件 RTC 闹钟。
 *
 * 为什么单独成类而不复用 [ScheduledTaskManager.ScheduledTaskAlarmReceiver]：
 *  - 后者是**无 intent-filter** 的闹钟回调接收器（靠 PendingIntent 定向广播触发），
 *    若要它兼收 BOOT_COMPLETED，就得给它加 `<intent-filter>` 并 exported=true，
 *    等于把闹钟回调通道也暴露给系统开机广播，职责混淆、攻击面变大；
 *  - 拆开后各管一件事，清单声明清晰，最小权限。
 *
 * 注意：这里**不**尝试拉起无障碍/屏幕捕获/悬浮窗（那些是需用户授权的重型服务，
 * 无法也不应在开机后台自动强启）；只恢复闹钟登记，保证定时链不因重启而断。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "🔌 收到系统开机广播，开始重建离线战术定时管家的硬件 RTC 闹钟...")
        // 同步执行即可：restoreAlarmsAfterBoot 只做 SharedPreferences 读取 + AlarmManager 登记，
        // 均为轻量内存/IPC 操作，远在接收器 ~10s 窗口内完成，无需 goAsync。
        try {
            ScheduledTaskManager.restoreAlarmsAfterBoot(context)
        } catch (e: Exception) {
            Log.e(TAG, "开机重建 RTC 闹钟失败: ${e.message}", e)
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
