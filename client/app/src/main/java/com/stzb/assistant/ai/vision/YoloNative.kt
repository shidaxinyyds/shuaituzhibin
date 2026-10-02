package com.stzb.assistant.ai.vision

import android.graphics.Bitmap
import android.util.Log

/**
 * 真实 ncnn YOLO 推理的 Kotlin 桥接（对应 native `yolo/YoloNcnn.cpp`）。
 *
 * ## 为什么处处 try/catch UnsatisfiedLinkError
 * JNI 符号 `nativeInit` / `nativeDetect` 只有在构建期 CMake 找到 ncnn+OpenCV、
 * 把 `YoloNcnn.cpp` 编进 `libRapidOcr.so` 时才存在。默认的空桩构建里没有这些符号，
 * 直接调用会抛 [UnsatisfiedLinkError]。这里把它吞掉并返回"不可用"，
 * 让上层 [YoloDetector] 诚实回退到几何色度检测——**而不是伪装成检测成功**。
 *
 * 这与本项目一贯的"响亮失败"原则一致：能力不存在时要能被看出来。
 */
object YoloNative {

    private const val TAG = "YoloNative"
    private const val LIB_NAME = "RapidOcr"

    @Volatile
    private var libLoaded = false

    @Volatile
    var isReady = false
        private set

    private external fun nativeInit(
        paramPath: String,
        binPath: String,
        inputSize: Int,
        numThreads: Int
    ): Boolean

    /** 返回扁平数组，每 6 个 float 一个检测框：[cls, x1, y1, x2, y2, score]（原图像素坐标）。 */
    private external fun nativeDetect(bitmap: Bitmap, conf: Float, nms: Float): FloatArray

    private fun ensureLib(): Boolean {
        if (libLoaded) return true
        return try {
            System.loadLibrary(LIB_NAME)
            libLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "lib$LIB_NAME 加载失败，YOLO 原生通道不可用: ${e.message}")
            false
        } catch (e: Throwable) {
            Log.e(TAG, "加载 YOLO 原生库异常: ${e.message}")
            false
        }
    }

    /**
     * 加载 ncnn 权重（param + bin 必须成对）。
     * @return true 仅当库可用且权重真的加载成功；任何失败都返回 false 并如实记日志。
     */
    fun load(paramPath: String, binPath: String, inputSize: Int = 640, numThreads: Int = 4): Boolean {
        if (!ensureLib()) {
            isReady = false
            return false
        }
        return try {
            isReady = nativeInit(paramPath, binPath, inputSize, numThreads)
            if (!isReady) Log.w(TAG, "nativeInit 返回 false：权重文件损坏或维度不匹配")
            isReady
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "本包未编译 YOLO 原生实现（OCR 空桩构建），回退几何检测: ${e.message}")
            isReady = false
            false
        } catch (e: Throwable) {
            Log.e(TAG, "YOLO 原生初始化异常: ${e.message}")
            isReady = false
            false
        }
    }

    /**
     * 执行一次检测。未就绪时返回空数组（调用方据此回退）。
     */
    fun detect(bitmap: Bitmap, conf: Float, nms: Float): FloatArray {
        if (!isReady) return FloatArray(0)
        return try {
            nativeDetect(bitmap, conf, nms) ?: FloatArray(0)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "nativeDetect 不可用，标记 YOLO 失效并回退: ${e.message}")
            isReady = false
            FloatArray(0)
        } catch (e: Throwable) {
            Log.e(TAG, "YOLO 推理异常: ${e.message}")
            FloatArray(0)
        }
    }
}
