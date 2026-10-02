package com.stzb.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.stzb.assistant.App
import com.stzb.assistant.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 720p 屏幕捕获前台服务与变动感知帧调度器 (Frame Governor)
 * 职责：
 *   1. 统一 1280x720 归一化画布投影，降低 60% 算力与发热；
 *   2. Android 14+ 前台服务合规生命周期绑定；
 *   3. 内置 FrameGovernor 感知哈希差分，实现 0.5 FPS 极省电巡航。
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val STANDARD_WIDTH = 1280
        const val STANDARD_HEIGHT = 720
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_CAPTURE = "com.stzb.assistant.action.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.stzb.assistant.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var instance: ScreenCaptureService? = null
            private set

        val isCapturing = AtomicBoolean(false)
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null

    // 这两个字段会被主线程的显示变化回调替换/关闭，而 captureCurrentFrame 可能在任何线程调用，
    // 因此必须 volatile：否则读线程可能拿到已关闭的实例，acquireLatestImage() 会抛异常。
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    // 旋转 / 分屏导致显示尺寸变化时，需要在不销毁 MediaProjection 的前提下调整捕获面。
    //
    // 注意：这里**刻意不再保存** resultCode/resultData。
    // 原实现存了它们却从未读取（只写不读的死状态），看起来像"为重建会话做好了准备"，
    // 实际上 Android 14(API 34) 起一个 MediaProjection 实例只能用于调用一次
    // createVirtualDisplay()，重用旧 resultData 新建实例这条路本身就是不被支持的。
    // 正确的做法是原地 resize + setSurface（见 tryResizeInPlace），因此这两个字段没有存在意义。
    private var displayListener: DisplayManager.DisplayListener? = null

    /**
     * 显示变化回调与重建重试统一走主线程。
     *
     * 原实现 `registerDisplayListener(listener, null)` 用"调用线程的 Looper"——
     * 若注册发生在没有 Looper 的线程上会抛 IllegalStateException，
     * 而那里被 try/catch 吞掉了，表现为**旋转处理静默失效**：
     * 用户一转屏，坐标就与显示空间永久失配，日志里却看不出任何异常。
     * 显式指定主线程 Looper 可以彻底消除这个歧义。
     */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 当前捕获面**是按哪一组几何建出来的**。
     *
     * 用途是过滤 `onDisplayChanged` 的冗余回调：这个回调表示"逻辑显示的属性发生了变化"，
     * **并不只**在几何变化时触发（刷新率/显示模式变化也会触发），旋转过程中还会连发数次。
     * 原实现每收到一次就无条件拆掉重建，既浪费又会在重建的窗口期暴露瞬时失败。
     * 记下指纹后，只有几何真的变了才重建。
     */
    private var surfaceGeometryFingerprint = ""

    // 重建失败的重试参数：转屏瞬间的重建失败往往是瞬时的，
    // 而原实现一次失败就 stopCapture()，等于"转一下屏就再也识别不了，还得重新授权"。
    private val maxRebuildAttempts = 2
    private val rebuildRetryDelayMs = 600L

    // 说明：这里原先有 captureWidth / captureHeight 两个字段，用于记录 ImageReader 的尺寸。
    // 但 captureCurrentFrame() 已经改成"以帧自身的尺寸为准"（几何可能在解析途中变化，
    // 任何静态尺寸都会算错 rowPadding），于是这两个字段全工程只剩赋值、没有任何读取。
    // 已按"死状态必须删掉"的标准移除，避免它们被误当成可信的当前尺寸。

    // 变动感知调度器状态
    private var lastFrameHash: Long = 0L
    private val motionThreshold = 5 // 感知哈希汉明距离阈值

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startForegroundNotification()
        Log.i(TAG, "ScreenCaptureService 已创建并绑定前台通知。")

        // 让定时任务调度器随捕获通道一起常驻启动。
        // 原实现只在悬浮窗创建时 init，于是"没点开悬浮胶囊 → 定时任务永不触发"，
        // 而定时的价值恰恰在于无人值守时执行。init() 是幂等的，重复调用安全。
        try {
            com.stzb.assistant.tactics.ScheduledTaskManager.init(
                this,
                com.stzb.assistant.tactics.TacticalPipeline.getInstance(this)
            )
        } catch (e: Exception) {
            Log.w(TAG, "启动定时任务调度器失败: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    initMediaProjection(resultCode, resultData)
                } else {
                    Log.e(TAG, "启动捕获失败: 缺少有效的授权凭据。")
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        instance = null
        Log.i(TAG, "ScreenCaptureService 已安全退出。")
    }

    private fun startForegroundNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle("率土全能管家正在护航")
            .setContentText("720p 视觉感知通道与敌袭红线雷达运行中")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, resultData: Intent) {
        if (isCapturing.get()) {
            Log.w(TAG, "MediaProjection 已在运行中，无需重复初始化。")
            return
        }

        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection 会话被系统挂断或用户手动停止。")
                    stopCapture()
                }
            }, null)

            if (!createCaptureSurface()) {
                Log.e(TAG, "创建捕获面失败，停止捕获。")
                stopCapture()
                return
            }

            registerDisplayListener()
            isCapturing.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "初始化 MediaProjection 异常: ${e.message}", e)
            stopCapture()
        }
    }

    /**
     * 创建（或按当前几何重建）VirtualDisplay + ImageReader。
     *
     * 尺寸完全取自 [CoordinateTransformer] 的设计画布，保证「捕获位图像素空间」
     * 与「坐标映射中枢」用的始终是同一组数值——这是 OCR/模板结果能直接当作
     * 虚拟坐标使用的前提。
     */
    private fun createCaptureSurface(): Boolean {
        val projection = mediaProjection ?: return false
        return try {
            CoordinateTransformer.refreshMetrics()
            CoordinateTransformer.logSelfTest()

            val vWidth = CoordinateTransformer.virtualWidth.toInt()
            val vHeight = CoordinateTransformer.virtualHeight.toInt()
            if (vWidth <= 0 || vHeight <= 0) {
                Log.e(TAG, "设计画布尺寸非法: ${vWidth}x$vHeight")
                return false
            }
            val densityDpi = resources.displayMetrics.densityDpi

            // 双缓冲 (maxImages = 2) 防止图像撕裂
            imageReader = ImageReader.newInstance(vWidth, vHeight, PixelFormat.RGBA_8888, 2)

            virtualDisplay = projection.createVirtualDisplay(
                "StzbDynamicVirtualDisplay",
                vWidth,
                vHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            surfaceGeometryFingerprint = currentGeometryFingerprint(vWidth, vHeight)
            Log.i(
                TAG,
                "捕获面 ${vWidth}x$vHeight 创建成功 " +                    "(物理分辨率 ${CoordinateTransformer.physicalWidth}x${CoordinateTransformer.physicalHeight}, dpi=$densityDpi)"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建捕获面异常: ${e.message}", e)
            false
        }
    }

    /**
     * 监听显示几何变化（旋转、分屏、折叠屏展开），按新几何重建捕获面。
     *
     * 旧实现只在启动时算一次尺寸，`virtualHeight` 还是 val，
     * 于是设备一转屏，坐标映射就与真实显示空间永久失配，且没有任何自愈路径，
     * 表现为「转屏后全部点不准」。
     */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        if (dm == null) {
            Log.e(TAG, "无法取得 DisplayManager：旋转/分辨率变化将不会被自动处理，转屏后坐标会失配。")
            return
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (!isCapturing.get()) return
                // 只在**几何真的变了**的时候重建。
                // onDisplayChanged 表示"逻辑显示的属性变了"，并不只在几何变化时触发
                // （刷新率/显示模式变化也会触发），旋转过程中还会连发数次。
                if (handlePossibleGeometryChange()) {
                    Log.i(TAG, "显示几何变化 (displayId=$displayId)，按新尺寸重建捕获面。")
                    rebuildCaptureSurface()
                } else {
                    Log.d(TAG, "显示属性回调 (displayId=$displayId)，几何未变，忽略。")
                }
            }
        }
        try {
            // 显式用主线程 Looper：原实现传 null（=调用线程的 Looper），
            // 在没有 Looper 的线程上注册会抛异常并被吞掉，表现为旋转处理静默失效。
            dm.registerDisplayListener(listener, mainHandler)
            displayListener = listener
        } catch (e: Exception) {
            // 不静默：缺了它转屏后坐标会永久失配，属于必须让用户知道的问题。
            Log.e(TAG, "注册显示变化监听失败，转屏后坐标可能失配: ${e.message}", e)
        }
    }

    /**
     * 当前几何指纹：物理分辨率 + 旋转角 + 设计画布尺寸。
     *
     * 物理分辨率决定缩放、旋转角决定方向、画布尺寸决定位图空间，任一变化都必须重建。
     */
    private fun currentGeometryFingerprint(vWidth: Int, vHeight: Int): String =
        "${CoordinateTransformer.physicalWidth}x${CoordinateTransformer.physicalHeight}" +
            "@${displayRotation()}#${vWidth}x$vHeight"

    /** 默认显示的旋转角（0/90/180/270）；取不到时返回 -1。 */
    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: -1
    } catch (e: Exception) {
        -1
    }

    /** 刷新几何基准，并判断它是否与"当前捕获面是按哪组几何建的"不同。 */
    private fun handlePossibleGeometryChange(): Boolean {
        CoordinateTransformer.refreshMetrics()
        val vW = CoordinateTransformer.virtualWidth.toInt()
        val vH = CoordinateTransformer.virtualHeight.toInt()
        if (vW <= 0 || vH <= 0) return false
        return currentGeometryFingerprint(vW, vH) != surfaceGeometryFingerprint
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.unregisterDisplayListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "注销显示变化监听失败: ${e.message}")
        }
        displayListener = null
    }

    /**
     * 按新的几何调整捕获面。
     *
     * ## ⚠️ 为什么不能"重新 createVirtualDisplay"
     * Android 14(API 34) 起，**一个 MediaProjection 实例只能用于调用一次
     * createVirtualDisplay()**，再次调用会抛 IllegalStateException。
     * 因此"转屏时用同一个 MediaProjection 重新 createVirtualDisplay"这条老路
     * 在 Android 14+ 上**必然失败**——而它正是本函数原来的实现。
     *
     * 官方支持的改变几何方式是 [VirtualDisplay.resize] + [VirtualDisplay.setSurface]，
     * 两者在 API 20+ 就可用。因此现在优先走原地调整，
     * 只有在原地调整不可用时（极老的设备或异常状态）才退回"销毁重建"这条兜底路径。
     *
     * 失败时**不再一次就停**：转屏瞬间的失败往往是瞬时的，
     * 而原实现一次失败就 stopCapture()，用户看到的是
     * "转一下屏就再也识别不了了，还得重新授权屏幕捕获"。
     */
    private fun rebuildCaptureSurface(attempt: Int = 1) {
        if (mediaProjection == null) return

        if (tryResizeInPlace()) {
            if (attempt > 1) Log.i(TAG, "捕获面在第 $attempt 次尝试时调整成功。")
            return
        }

        // 兜底：原地调整不可用时才销毁重建（Android 14+ 上此举可能失败，因此只是兜底）
        releaseCaptureSurface()
        if (createCaptureSurface()) {
            if (attempt > 1) Log.i(TAG, "捕获面在第 $attempt 次尝试时重建成功（兜底路径）。")
            return
        }

        if (attempt < maxRebuildAttempts) {
            Log.w(TAG, "调整捕获面失败（第 $attempt/$maxRebuildAttempts 次），${rebuildRetryDelayMs}ms 后重试。")
            mainHandler.postDelayed({ rebuildCaptureSurface(attempt + 1) }, rebuildRetryDelayMs)
        } else {
            Log.e(
                TAG,
                "调整捕获面连续失败 $maxRebuildAttempts 次，已停止捕获以避免继续使用错位坐标。" +
                    "请重新授权屏幕捕获。"
            )
            stopCapture()
        }
    }

    /**
     * 原地调整几何：新建 ImageReader，再 resize + setSurface。
     * **不**重新调用 createVirtualDisplay —— 这正是 Android 14 所要求的。
     */
    private fun tryResizeInPlace(): Boolean {
        val vd = virtualDisplay ?: return false
        return try {
            CoordinateTransformer.refreshMetrics()
            val vWidth = CoordinateTransformer.virtualWidth.toInt()
            val vHeight = CoordinateTransformer.virtualHeight.toInt()
            if (vWidth <= 0 || vHeight <= 0) {
                Log.e(TAG, "调整捕获面时得到非法画布尺寸: ${vWidth}x$vHeight")
                return false
            }

            val oldReader = imageReader
            val newReader = ImageReader.newInstance(vWidth, vHeight, PixelFormat.RGBA_8888, 2)

            vd.resize(vWidth, vHeight, resources.displayMetrics.densityDpi)
            vd.surface = newReader.surface

            // 先换上新 reader 再关旧的：顺序反了会让解析线程拿到已关闭的实例。
            imageReader = newReader
            try {
                oldReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "关闭旧 ImageReader 异常: ${e.message}")
            }

            surfaceGeometryFingerprint = currentGeometryFingerprint(vWidth, vHeight)
            Log.i(TAG, "捕获面已原地调整为 ${vWidth}x$vHeight（未重建 MediaProjection 会话）。")
            true
        } catch (e: Exception) {
            Log.w(TAG, "原地调整捕获面几何失败: ${e.message}")
            false
        }
    }

    private fun releaseCaptureSurface() {
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.w(TAG, "释放 VirtualDisplay 异常: ${e.message}")
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭 ImageReader 异常: ${e.message}")
        }
        imageReader = null
        // 一并清掉几何指纹：这样"释放后几何又变了"一定能被下一次回调检测出来。
        surfaceGeometryFingerprint = ""
    }

    private fun stopCapture() {
        isCapturing.set(false)
        unregisterDisplayListener()
        releaseCaptureSurface()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止 MediaProjection 异常: ${e.message}")
        }
        mediaProjection = null
        Log.i(TAG, "屏幕捕获资源已完全释放。")
    }

    /**
     * 捕获当前 1280x720 单帧高清位图 (线程安全)
     */
    fun captureCurrentFrame(): Bitmap? {
        if (!isCapturing.get()) return null
        // 先取本地快照：几何调整会在主线程关闭并替换 ImageReader，
        // 直接读字段可能拿到"刚好被关掉"的那个实例。
        val reader = imageReader ?: return null

        return try {
            // acquireLatestImage 必须在 try **之内**。
            // 原实现把它放在 try 之外：几何调整（转屏）恰好关闭了 reader 时，
            // 它会抛出 IllegalStateException 并直接冒泡给调用方，也就是崩溃。
            val image: Image = reader.acquireLatestImage() ?: return null
            try {
                // 以帧自身的尺寸为准：几何在解析途中发生变化时，
                // 任何静态尺寸都会算错 rowPadding，产出错位/撕裂的位图。
                val vWidth = image.width
                val vHeight = image.height
                if (vWidth <= 0 || vHeight <= 0) {
                    Log.w(TAG, "丢弃非法尺寸的帧: ${vWidth}x$vHeight")
                    return null
                }
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * vWidth

                val rawBitmap = Bitmap.createBitmap(
                    vWidth + rowPadding / pixelStride,
                    vHeight,
                    Bitmap.Config.ARGB_8888
                )
                rawBitmap.copyPixelsFromBuffer(buffer)

                if (rowPadding == 0) {
                    rawBitmap
                } else {
                    val cleanBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, vWidth, vHeight)
                    rawBitmap.recycle()
                    cleanBitmap
                }
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            // 这是**可恢复的瞬时情况**（正逢几何调整），不是致命错误。
            Log.w(TAG, "获取/解析单帧失败（可能正逢几何调整）: ${e.message}")
            null
        }
    }

    /**
     * 计算图像的 8x8 均值哈希（纯函数，不维护任何状态）。
     *
     * 供"点击后是否生效"这类**局部判断**使用：调用方自己取点击前后的两次哈希再比较，
     * 不会与 [isScreenChanged] 的内部状态互相干扰。
     */
    fun averageHash(bitmap: Bitmap): Long = computeAverageHash(bitmap)

    /**
     * 变动感知哈希比对 (Frame Governor)
     * 计算输入图像 8x8 感知哈希，判断与前一帧是否有实质性变动
     * 返回：true 表示画面发生显著变化（需要执行决策）；false 表示静止巡航（可休眠）
     */
    fun isScreenChanged(frame: Bitmap): Boolean {
        val currentHash = computeAverageHash(frame)
        if (lastFrameHash == 0L) {
            lastFrameHash = currentHash
            return true
        }

        // 计算汉明距离
        val diff = java.lang.Long.bitCount(currentHash xor lastFrameHash)
        lastFrameHash = currentHash
        return diff >= motionThreshold
    }

    private fun computeAverageHash(src: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(src, 8, 8, false)
        var total = 0
        val pixels = IntArray(64)
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val pixel = scaled.getPixel(x, y)
                // 灰度化：0.299R + 0.587G + 0.114B
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                pixels[y * 8 + x] = gray
                total += gray
            }
        }
        val avg = total / 64
        var hash = 0L
        for (i in 0 until 64) {
            if (pixels[i] >= avg) {
                hash = hash or (1L shl i)
            }
        }
        scaled.recycle()
        return hash
    }
}
