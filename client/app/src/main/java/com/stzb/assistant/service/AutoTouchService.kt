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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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

        // 2. 叠加 2D 双变量高斯离散坐标微抖动
        val jittered = KineticTouchEngine.generateJitteredPoint(realPoint.x, realPoint.y, 6f)

        val path = Path().apply {
            moveTo(jittered.x, jittered.y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGestureAsync(gesture)
    }

    /**
     * 拟人化平滑贝塞尔滑动 (拖动大地图或翻找目标)
     * 传入起止自适应虚拟坐标
     */
    suspend fun swipeVirtual(
        vStartX: Float, vStartY: Float,
        vEndX: Float, vEndY: Float,
        durationMs: Long = Random.nextLong(380, 580)
    ): Boolean = withContext(Dispatchers.Default) {
        val p0 = CoordinateTransformer.toReal(vStartX, vStartY)
        val p3 = CoordinateTransformer.toReal(vEndX, vEndY)

        // 生成带惯性过冲与微回弹的平滑三次贝塞尔路径
        val path = BezierTrajectory.createHumanPath(p0, p3)
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGestureAsync(gesture)
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
     * 异步派发手势并等待系统回调确认
     */
    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "手势派发被系统中断或被用户按压拦截。")
                deferred.complete(false)
            }
        }

        val dispatched = dispatchGesture(gesture, callback, null)
        return if (!dispatched) {
            Log.e(TAG, "系统拒绝派发手势，请检查当前是否有锁屏或权限受限。")
            false
        } else {
            deferred.await()
        }
    }
}
