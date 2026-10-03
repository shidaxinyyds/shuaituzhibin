package com.stzb.assistant.runtime

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃兜底与诊断快照 (CrashGuard)
 *
 * ## 为什么商业化交付必须有它
 * 本项目是**常驻在游戏之上的自动化进程**，崩溃意味着：
 *   * 挂机中的压秒/夜战防护**静默消失**（用户以为还在守护）；
 *   * 无障碍服务可能仍处于连接态但控制器已死，用户完全无感知。
 *
 * 此前工程里**没有任何** `UncaughtExceptionHandler`：任何一处未捕获异常都会让进程直接死掉，
 * 除了一条 logcat 之外不留任何线索，用户只会反馈"不知道为什么就不动了"。
 *
 * ## 它做什么（以及刻意不做什么）
 *   * 记录崩溃时间、线程、异常、**本包栈帧**（过滤掉框架噪声，便于定位）；
 *   * 附带一份运行环境快照（资源水位 / 感知与触控通道 / 视觉能力），
 *     因为"是不是因为内存被杀""OCR 到底起来没有"永远是第一追问；
 *   * 落盘到 `filesDir/crash_last.txt`，并累计崩溃次数；
 *   * **照常调用原 handler**，不吞异常、不"假装没崩"——
 *     让进程按系统预期终止，避免带着损坏状态继续跑造成更糟后果。
 *
 * 刻意不做：不上报任何数据到网络（本项目定位 100% 端侧离线）。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val PREFS = "stzb_crash_guard"
    private const val KEY_COUNT = "crash_count"
    private const val KEY_LAST_AT = "crash_last_at"
    private const val SNAPSHOT_FILE = "crash_last.txt"
    /** 只保留属于本工程的栈帧，避免 20 行框架噪声盖住真正的问题。 */
    private const val OUR_PACKAGE = "com.stzb.assistant"

    @Volatile
    private var installed = false

    /** 本次进程内是否已发生过未捕获异常（供 UI 提示"上次异常退出"）。 */
    @Volatile
    var crashedInThisProcess: Boolean = false
        private set

    fun install(app: Application) {
        if (installed) return
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 兜住一切：兜底逻辑自己再抛异常会导致无限递归
            try {
                crashedInThisProcess = true
                val snapshot = buildSnapshot(thread, throwable)
                persist(app, snapshot)
                Log.e(TAG, "未捕获异常已记录：\n$snapshot")
            } catch (e: Throwable) {
                Log.e(TAG, "崩溃兜底自身异常: ${e.message}")
            } finally {
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (ignored: Throwable) {
                    // 原 handler 也可能失败，此时让进程自然结束即可
                }
            }
        }
        Log.i(TAG, "崩溃兜底已安装（累计崩溃次数: ${crashCount(app)}）。")
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 累计崩溃次数（跨进程持久）。 */
    fun crashCount(context: Context): Int = prefs(context).getInt(KEY_COUNT, 0)

    /** 上次崩溃时间（毫秒）；0 表示从未崩溃。 */
    fun lastCrashAt(context: Context): Long = prefs(context).getLong(KEY_LAST_AT, 0L)

    /** 读取上次崩溃的快照文本；没有则返回 null。 */
    fun readSnapshot(context: Context): String? {
        return try {
            val f = File(context.filesDir, SNAPSHOT_FILE)
            if (!f.exists()) null else f.readText()
        } catch (e: Exception) {
            null
        }
    }

    /** 清除崩溃痕迹（用户在设置里"我已处理"后调用）。 */
    fun clear(context: Context) {
        try {
            File(context.filesDir, SNAPSHOT_FILE).delete()
            prefs(context).edit().remove(KEY_COUNT).remove(KEY_LAST_AT).apply()
        } catch (e: Exception) {
            Log.w(TAG, "清除崩溃记录失败: ${e.message}")
        }
    }

    private fun persist(context: Context, snapshot: String) {
        try {
            File(context.filesDir, SNAPSHOT_FILE).writeText(snapshot)
            val p = prefs(context)
            p.edit()
                .putInt(KEY_COUNT, p.getInt(KEY_COUNT, 0) + 1)
                .putLong(KEY_LAST_AT, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "写入崩溃快照失败: ${e.message}")
        }
    }

    private fun buildSnapshot(thread: Thread, throwable: Throwable): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            append("==== 率土全能管家 · 崩溃诊断快照 ====\n")
            append("时间    : ").append(time).append("\n")
            append("线程    : ").append(thread.name).append(" (id=").append(thread.id).append(")\n")
            append("异常    : ").append(throwable.javaClass.name)
            throwable.message?.let { append(" — ").append(it) }
            append("\n\n")

            append("── 本工程栈帧 ──\n")
            val frames = throwable.stackTrace.filter { it.className.startsWith(OUR_PACKAGE) }
            if (frames.isEmpty()) {
                append("（栈中没有本工程帧；可能发生在框架回调里，下面给出前 12 帧原文）\n")
                throwable.stackTrace.take(12).forEach { append("    at ").append(it).append("\n") }
            } else {
                frames.take(20).forEach { append("    at ").append(it).append("\n") }
            }
            append("\n")

            append("── 运行环境 ──\n")
            append("  ").append(ResourceGuard.describe()).append("\n")
            append("  ").append(VisionRuntime.describe()).append("\n")
            append("  屏幕捕获=").append(com.stzb.assistant.service.EngineBridge.isCaptureReady)
            append(" · 触控通道=").append(com.stzb.assistant.service.EngineBridge.isTouchReady)
            append(" · OCR可用=").append(com.stzb.assistant.ocr.OcrManager.isEngineAvailable)
            com.stzb.assistant.ocr.OcrManager.unavailableReason?.let {
                append("（").append(it).append("）")
            }
            append("\n")

            append("\n提示：把这份快照连同 logcat 一起反馈，可直接定位到具体栈帧。\n")
        }
    }
}
