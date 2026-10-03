package com.stzb.assistant.runtime

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log

/**
 * 系统资源余量守卫 (ResourceGuard)
 *
 * ## 为什么必须有它
 * 本项目要在**游戏运行的同时**常驻：屏幕捕获 + OCR 推理 + 可能的 YOLO / ONNX Runtime。
 * 这些模型的加载是"一次性吃掉十几到几十 MB"的动作，而 Android 在低内存时会
 * **先杀后台进程（LMK）**——对本项目来说，被杀的不是"后台"，而是正在执行的
 * 压秒/夜战防护。此前工程里**没有任何**资源水位判断：
 * `YoloDetector` 的构造函数会直接 `initModel()` 加载 ncnn 权重，
 * `BgeEmbedder.ensureLoaded()` 也会直接建 ORT Session，谁调用谁就吃内存，没人管余量。
 *
 * ## 它做什么
 *   1. 提供统一的水位读数（可用内存 / 是否低内存 / Java 堆占用）；
 *   2. **加载前置闸门**：`canAfford(costMb)` 判断"加载这个模型后是否仍留有安全余量"，
 *      没有余量就拒绝加载并说清原因，由调用方诚实降级；
 *   3. 监听系统 `onTrimMemory`，在系统开始回收时主动通知各缓存释放（onLowMemory 回调）。
 *
 * ## 刻意不做的事
 * 不做"内存不够就杀掉游戏/停止一切"这类激进动作——那会造成更糟的后果
 * （压秒到一半被自己停掉）。本类只做**准入判断**与**可观测性**。
 */
object ResourceGuard {

    private const val TAG = "ResourceGuard"

    /**
     * 加载重模型后仍需保留的**安全余量**（MB）。
     *
     * 取值依据：游戏本体在 16GB 机型上常驻约 1.5~2GB，系统 LMK 通常从
     * 剩余几百 MB 开始回收；120MB 足以让"再加载一个模型"不会成为压垮骆驼的最后一根稻草。
     */
    private const val SAFE_RESERVE_MB = 120L

    /** 可用内存低于该值即视为"系统已在承压"，此时不再加载任何重资产。 */
    private const val LOW_MEMORY_MB = 220L

    private var attached = false
    private var appContext: Context? = null

    @Volatile
    private var availMemMb: Long = -1L

    @Volatile
    private var totalMemMb: Long = -1L

    @Volatile
    private var isLowRamDevice: Boolean = false

    @Volatile
    private var lastTrimLevel: Int = Int.MIN_VALUE

    /** 需要在系统回收内存时被通知的对象（各模型缓存注册进来）。 */
    private val releaseHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun attach(context: Context) {
        if (attached) return
        appContext = context.applicationContext
        attached = true
        refresh()
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                Log.w(TAG, "系统 onLowMemory：触发全部缓存释放。")
                notifyRelease()
            }

            override fun onTrimMemory(level: Int) {
                lastTrimLevel = level
                refresh()
                // TRIM_MEMORY_RUNNING_CRITICAL(15) / COMPLETE(80) 及以上说明已在回收边缘
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                    Log.w(TAG, "系统 onTrimMemory(level=$level)：释放可重建的模型缓存。")
                    notifyRelease()
                }
            }
        })
        Log.i(TAG, "资源守卫已挂载。${describe()}")
    }

    /** 注册一个"系统回收内存时要执行"的释放动作。 */
    fun registerReleaseHook(hook: () -> Unit) {
        if (!releaseHooks.contains(hook)) releaseHooks.add(hook)
    }

    fun unregisterReleaseHook(hook: () -> Unit) {
        releaseHooks.remove(hook)
    }

    private fun notifyRelease() {
        for (h in releaseHooks) {
            try {
                h.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "释放钩子执行异常: ${e.message}")
            }
        }
    }

    /** 重新采样一次系统内存水位（每次加载模型前都应调用）。 */
    fun refresh() {
        val ctx = appContext
        if (ctx == null) {
            // 还没 attach：退化为 JVM 堆读数，至少不返回"一切正常"的假象
            val rt = Runtime.getRuntime()
            totalMemMb = rt.maxMemory() / (1024 * 1024)
            availMemMb = (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024)
            return
        }
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            if (am != null) {
                availMemMb = mi.availMem / (1024 * 1024)
                totalMemMb = mi.totalMem / (1024 * 1024)
                isLowRamDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    mi.lowMemory || (am.isLowRamDevice)
                } else {
                    mi.lowMemory
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取系统内存信息失败: ${e.message}")
        }
    }

    /** 系统可用内存（MB）；-1 表示读不到。 */
    fun availableMb(): Long {
        if (availMemMb < 0) refresh()
        return availMemMb
    }

    /** 系统总内存（MB）；-1 表示读不到。 */
    fun totalMb(): Long = totalMemMb

    /** 当前是否处于低内存状态（含系统自报 lowMemory）。 */
    fun isLowMemory(): Boolean {
        val avail = availableMb()
        return (avail > 0 && avail < LOW_MEMORY_MB) || isLowRamDevice
    }

    /**
     * 判断"再加载一个约 costMb 的模型后，是否仍留有安全余量"。
     *
     * @param costMb 该模型加载后预计占用的内存（MB），由调用方按模型规模给出
     * @return true 表示**允许加载**；false 表示应当诚实降级
     */
    fun canAfford(costMb: Int): Boolean {
        refresh()
        val avail = availMemMb
        if (avail <= 0L) {
            // 读不到水位时不放行：宁可不加载，也不能在未知水位下把进程推到 LMK 边缘
            Log.w(TAG, "无法读取可用内存，按'不允许加载重资产'处理（保守）。")
            return false
        }
        if (isLowMemory()) {
            Log.w(TAG, "系统可用内存 ${avail}MB 已进入低内存区间，拒绝加载 ${costMb}MB 的模型。")
            return false
        }
        val left = avail - costMb
        val ok = left >= SAFE_RESERVE_MB
        if (!ok) {
            Log.w(
                TAG,
                "加载 ${costMb}MB 模型后仅剩 ${left}MB，低于安全余量 ${SAFE_RESERVE_MB}MB，拒绝加载。"
            )
        }
        return ok
    }

    /** Java 堆占用率（0~100），用于观察是否被大量 Bitmap 顶住。 */
    fun heapUsedPercent(): Int {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory()
        val used = rt.totalMemory() - rt.freeMemory()
        if (max <= 0L) return -1
        return ((used * 100) / max).toInt().coerceIn(0, 100)
    }

    /** 一行式状态，直接进 logcat / 诊断报告。 */
    fun describe(): String {
        val avail = availableMb()
        return buildString {
            append("资源水位: 可用 ").append(avail).append("MB / 总 ").append(totalMb()).append("MB")
            append(" · 堆占用 ").append(heapUsedPercent()).append("%")
            append(" · 低内存=").append(isLowMemory())
            if (lastTrimLevel != Int.MIN_VALUE) append(" · 最近trim=").append(lastTrimLevel)
        }
    }
}
