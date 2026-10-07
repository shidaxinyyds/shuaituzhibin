package com.stzb.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.stzb.assistant.antiban.KineticTouchEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 商业级拟人化无障碍触控通道 (AutoTouchService)
 * 特性：
 *   1. 统一接收自适应 720p 归一化虚拟坐标，内部通过 CoordinateTransformer 自动映射为物理像素；
 *   2. 集成 KineticTouchEngine 动力学惯性过冲贝塞尔曲线滑动，去除直线机械特征；
 *   3. 2D 双变量高斯径向离散抖动 + 符合指腹接触弹性物理学的接触耗时 (70~175ms)；
 *   4. 支持协程非阻塞挂起等待手势派发完成 (Callback Confirmation)。
 */
class AutoTouchService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoTouchService"
        var instance: AutoTouchService? = null
            private set

        val isConnected: Boolean
            get() = instance != null
    }

    /**
     * 最近一次观测到的“位于前台的包名”，以及看到它的时刻。
     *
     * @Volatile：写发生在无障碍事件回调线程，读发生在战术流水线所在的协程线程。
     */
    @Volatile
    var foregroundPackage: String? = null
        private set

    @Volatile
    var foregroundEpochMs: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "率土管家无障碍拟人触控通道已正式激活就绪。")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.w(TAG, "无障碍触控通道已断开。")
    }

    /**
     * 只拿前台归属这一件事。
     *
     * 这个回调过去是空的（`{}`），即服务明明声明了 `typeAllMask` 与
     * `canRetrieveWindowContent`，却一个事件也没用过。现在把窗口切换事件用起来：
     * 它恰好是“这一次盲点到底点在哪个应用上”唯一可靠的现场证据。
     *
     * 只处理 TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_ACTIVE：其余事件（内容变化、
     * 滚动、获得焦点……）量极大且不回答“谁是前台”，在其中取值会得到错误的包名。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_ACTIVE
        ) {
            return
        }
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        foregroundPackage = pkg
        foregroundEpochMs = System.currentTimeMillis()
    }

    override fun onInterrupt() {}

    /**
     * 拟人化单点点击 (传入自适应虚拟坐标)
     */
    suspend fun clickVirtual(
        virtualX: Float,
        virtualY: Float,
        durationMs: Long = KineticTouchEngine.generateContactDuration()
    ): Boolean = withContext(Dispatchers.Default) {
        // 1. 转换为真实屏幕物理像素
        val realPoint = CoordinateTransformer.toReal(virtualX, virtualY)

        // 2. 叠加 2D 双变量高斯离散坐标微抖动 (精准控制在 1.0px 以内)
        val jittered = KineticTouchEngine.generateJitteredPoint(realPoint.x, realPoint.y, 1.0f)

        val path = Path().apply {
            moveTo(jittered.x, jittered.y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGestureAsync(gesture)
    }

    /**
     * 拟人化平滑贝塞尔滑动 (拖动大地图或翻找目标)
     * 传入起止自适应虚拟坐标。
     *
     * ## 为什么不是“一条 Path 一个 stroke”（这是真实缺陷的修复）
     * `StrokeDescription(path, 0, duration)` 对 Path 是**弧长均匀 + 时间线性**
     * 采样的。旧实现虽然轨迹是弯的，但**整条滑动是恒速**的——真实手指
     * 绝对是起按慢、中段最快、入位减速。恒速本身就是极易提取的机器特征
     * （对采样点做一阶差分就能看出来）。
     *
     * 现在改成 [KineticTouchEngine.planInertialSwipe] 规划出的**多段连续 stroke**
     * （首段 `willContinue=true`，其余 `continueStroke` 链式续笔）：对手指来说
     * 仍是一次不抬指的连续滑动，但沿程速度变成了钟形剖面，且总时长严格
     * 等于调用方给的 `durationMs`（不会因子段划分而漂）。
     *
     * ## fail-closed 回退
     * 短距离（<24px）不值得拆段；规划/构造异常、或系统**根本没有开始**这条
     * 手势时，退回旧的单条 stroke 通道。“拆段”失败不应该让整个拖动失效。
     */
    suspend fun swipeVirtual(
        vStartX: Float, vStartY: Float,
        vEndX: Float, vEndY: Float,
        durationMs: Long = Random.nextLong(380, 580)
    ): Boolean = withContext(Dispatchers.Default) {
        val p0 = CoordinateTransformer.toReal(vStartX, vStartY)
        val p3 = CoordinateTransformer.toReal(vEndX, vEndY)
        val totalMs = durationMs.coerceAtLeast(1L)

        val chained = buildVariableSpeedSwipe(p0, p3, totalMs)
        if (chained == null) {
            // 回退：旧的单条贝塞尔路径（弯但恒速），至少拖动本身仍然可靠
            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        BezierTrajectory.createHumanPath(p0, p3), 0L, totalMs
                    )
                )
                .build()
            return@withContext dispatchGestureAsync(gesture)
        }

        return@withContext when (dispatchOutcome(chained)) {
            DispatchResult.COMPLETED -> true
            DispatchResult.CANCELLED -> false
            DispatchResult.NOT_STARTED -> {
                // 系统连开始都没开始（部分 OEM ROM 对 willContinue 支持不全），
                // 此时重试单条 stroke 是安全的：上一手势从未注入过任何事件。
                Log.w(TAG, "多段变速手势未被系统接受，回退单条恒速滑动。")
                val legacy = GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
                            BezierTrajectory.createHumanPath(p0, p3), 0L, totalMs
                        )
                    )
                    .build()
                dispatchGestureAsync(legacy)
            }
        }
    }

    /**
     * 把一次滑动编成多段连续 stroke。不可拆段或构造失败时返回 null。
     *
     * 注意两个系统硬约束，它们都是会抛异常的：
     *   1. 续笔的 Path **必须**从上一段的终点起笔（浮点严格相等），否则
     *      `continueStroke` 直接抛 IllegalArgumentException——[TrajectoryPlanner]
     *      的重采样让相邻段**共享同一个边界点对象值**，因此天然成立；
     *   2. 只有被标 `willContinue=true` 的段才能被续笔，且**最后一段必须
     *      是 false**——否则手指永远不抬起，手势挂住不结束。
     */
    private fun buildVariableSpeedSwipe(
        start: PointF,
        end: PointF,
        totalMs: Long
    ): GestureDescription? {
        val bounds = com.stzb.assistant.antiban.TrajectoryPlanner.Bounds(
            0f, 0f,
            CoordinateTransformer.physicalWidth.toFloat(),
            CoordinateTransformer.physicalHeight.toFloat()
        )

        val plan = try {
            KineticTouchEngine.planInertialSwipe(start, end, totalMs, bounds)
        } catch (t: Throwable) {
            Log.w(TAG, "变速轨迹规划异常，回退单条滑动: ${t.javaClass.simpleName} ${t.message}")
            return null
        }
        if (plan.size < 2) return null  // 短滑/退化：不值得拆段

        return try {
            val builder = GestureDescription.Builder()
            var previous: GestureDescription.StrokeDescription? = null
            // StrokeDescription 不对外暴露自己的时长与结束时刻（Android 没给 getter），
            // 所以续笔的 startDelay 只能由我们自己维护一条时间游标 cursorMs。
            var cursorMs = 0L
            for ((idx, seg) in plan.withIndex()) {
                if (seg.points.size < 2) {
                    Log.w(TAG, "第 $idx 段采样点不足 2 个，放弃多段手势。")
                    return null
                }
                val path = KineticTouchEngine.toPath(seg.points)
                val duration = seg.durationMs.coerceAtLeast(1L)
                val isLast = idx == plan.size - 1
                val last = previous
                // 规划结果本应无缝，仍兜一层：负缝隙（段与段重叠）会被夹成 0，
                // 保证交给系统的时间轴单调递增，绝不出现负的续笔延迟。
                val gap = if (last == null) 0L else (seg.startMs - cursorMs).coerceAtLeast(0L)
                val stroke = if (last == null) {
                    // 首段：从 0 时刻起笔，后面一定有人续
                    GestureDescription.StrokeDescription(path, 0L, duration, true)
                } else {
                    // 续笔：startDelay = 与上一段结束的间隔（规划结果恒为 0）
                    last.continueStroke(path, gap, duration, isLast.not())
                }
                builder.addStroke(stroke)
                previous = stroke
                cursorMs += gap + duration
            }
            builder.build()
        } catch (t: Throwable) {
            // 包括 IllegalArgumentException（端点不连续/时长非法）：一律回退旧路径
            Log.w(TAG, "多段手势构造失败，回退单条滑动: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    /**
     * 拟人化双击 (间隔 90~150ms，模拟快速连点)
     */
    suspend fun doubleClickVirtual(virtualX: Float, virtualY: Float): Boolean {
        val first = clickVirtual(virtualX, virtualY)
        if (!first) return false
        delay(Random.nextLong(90, 150))
        return clickVirtual(virtualX, virtualY)
    }

    /**
     * 拟人化双指捏合缩放 (拖动大地图缩放级别)
     * 传入焦点中心自适应虚坐标与缩放倍率 (scale>1 放大/两指外开, scale<1 缩小/两指内收)。
     *
     * 实现：一个 GestureDescription 内放**两条 StrokeDescription**，共享同一 startTime/duration，
     * 系统会当作两指同时动作→形成捏合。单靠一条 swipe 无法缩放。
     */
    suspend fun pinchVirtual(
        vCenterX: Float,
        vCenterY: Float,
        scale: Float,
        durationMs: Long = 500L
    ): Boolean = withContext(Dispatchers.Default) {
        if (!scale.isFinite() || scale <= 0f) return@withContext false
        val center = CoordinateTransformer.toReal(vCenterX, vCenterY)
        val maxW = CoordinateTransformer.physicalWidth
        val maxH = CoordinateTransformer.physicalHeight

        // 基础半距：取屏短边的一成八，保证两指都在地图区内且不会贴边被系统当边缘手热。
        val baseHalf = (minOf(maxW, maxH) * 0.18f).coerceAtLeast(60f)
        val toHalf = (baseHalf * scale).coerceAtLeast(20f)

        val cy = center.y.coerceIn(maxH * 0.2f, maxH * 0.8f)
        // 限在屏内，避免越界坐标被系统丢弃
        val lx0 = (center.x - baseHalf).coerceIn(1f, maxW - 1f)
        val lx1 = (center.x - toHalf).coerceIn(1f, maxW - 1f)
        val rx0 = (center.x + baseHalf).coerceIn(1f, maxW - 1f)
        val rx1 = (center.x + toHalf).coerceIn(1f, maxW - 1f)

        val leftPath = Path().apply { moveTo(lx0, cy); lineTo(lx1, cy) }
        val rightPath = Path().apply { moveTo(rx0, cy); lineTo(rx1, cy) }
        val leftStroke = GestureDescription.StrokeDescription(leftPath, 0, durationMs)
        val rightStroke = GestureDescription.StrokeDescription(rightPath, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(leftStroke)
            .addStroke(rightStroke)
            .build()

        dispatchGestureAsync(gesture)
    }

    /**
     * 异步派发手势并等待系统回调确认
     */
    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        return dispatchOutcome(gesture) == DispatchResult.COMPLETED
    }

    /**
     * 派发结果的**三态**版本。区分“系统根本没接受”与“接受了但中途被取消”
     * 是必要的：前者重试是安全的（一个事件都没注入过），后者重试会变成
     * “拖了两下”，对地图来说就是双倍位移。
     */
    private suspend fun dispatchOutcome(gesture: GestureDescription): DispatchResult {
        val deferred = CompletableDeferred<DispatchResult>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(DispatchResult.COMPLETED)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "手势派发被系统中断或被用户按压拦截。")
                deferred.complete(DispatchResult.CANCELLED)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        if (!dispatched) {
            Log.e(TAG, "系统拒绝派发手势，请检查当前是否有锁屏或权限受限。")
            return DispatchResult.NOT_STARTED
        }
        return deferred.await()
    }

    private enum class DispatchResult { COMPLETED, CANCELLED, NOT_STARTED }
}
