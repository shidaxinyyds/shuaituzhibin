package com.stzb.assistant.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.InputStream

/**
 * OpenCV 视觉辅助与动态多尺度匹配引擎 (OpenCvMatcher)
 * 
 * 现代化演进说明：
 *   1. 彻底弃用老旧硬编码模板依赖 (attack_template/defense_template 等旧图标已随网易大版本废弃)；
 *   2. 主交互优先走【StzbUiMatcher 语义 OCR 定位】，OpenCV 则作为特征颜色/线条/多尺度图形的强力支撑；
 *   3. 动态扫描加载 assets/templates 目录下的任意新模板，不再写死文件名；
 *   4. 支持 0.85x ~ 1.15x 多尺度金字塔搜索，消除机型 DPI 差异带来的图标缩放误差。
 */
object OpenCvMatcher {

    private const val TAG = "OpenCvMatcher"
    private var isInitialized = false

    // 动态缓存加载的模板 Mat
    private val templateCache = HashMap<String, Mat>()

    data class MatchResult(
        val isFound: Boolean,
        val centerX: Float,
        val centerY: Float,
        val score: Float,
        val rect: Rect
    )

    /**
     * 初始化 OpenCV 原生库
     */
    fun init(context: Context): Boolean {
        if (isInitialized) return true
        return try {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV 原生库加载失败！")
                false
            } else {
                isInitialized = true
                Log.i(TAG, "OpenCV 引擎就绪，正在动态扫描模板资产...")
                preloadAvailableTemplates(context)
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "OpenCV 初始化异常: ${e.message}")
            false
        }
    }

    /**
     * 动态加载 assets/templates 下存在的全部图片文件，不硬编码任何具体文件名
     */
    private fun preloadAvailableTemplates(context: Context) {
        try {
            val list = context.assets.list("templates") ?: return
            for (filename in list) {
                if (filename.endsWith(".png", ignoreCase = true) || filename.endsWith(".jpg", ignoreCase = true)) {
                    loadTemplateFromAsset(context, "templates/$filename", filename)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "扫描模板资产异常: ${e.message}")
        }
    }

    /**
     * 加载单个 Asset 模板至内存
     */
    fun loadTemplateFromAsset(context: Context, assetPath: String, keyName: String): Boolean {
        return try {
            val input: InputStream = context.assets.open(assetPath)
            val bmp = BitmapFactory.decodeStream(input)
            val mat = Mat()
            Utils.bitmapToMat(bmp, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
            templateCache[keyName] = mat
            bmp.recycle()
            Log.d(TAG, "模板 [$keyName] 载入成功 (${mat.cols()}x${mat.rows()})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "加载模板 [$keyName] 失败: ${e.message}")
            false
        }
    }

    /**
     * 单模板标准归一化相关系数匹配 (TM_CCOEFF_NORMED)
     */
    fun match(
        srcBitmap: Bitmap,
        templateName: String,
        threshold: Float = 0.75f
    ): MatchResult {
        val tplMat = templateCache[templateName] ?: return MatchResult(false, 0f, 0f, 0f, Rect())

        val srcMat = Mat()
        Utils.bitmapToMat(srcBitmap, srcMat)
        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2BGR)

        if (srcMat.cols() < tplMat.cols() || srcMat.rows() < tplMat.rows()) {
            srcMat.release()
            return MatchResult(false, 0f, 0f, 0f, Rect())
        }

        val resultMat = Mat()
        Imgproc.matchTemplate(srcMat, tplMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

        val mmr = Core.minMaxLoc(resultMat)
        val maxVal = mmr.maxVal.toFloat()
        val matchLoc: Point = mmr.maxLoc

        srcMat.release()
        resultMat.release()

        return if (maxVal >= threshold) {
            val cx = (matchLoc.x + tplMat.cols() / 2f).toFloat()
            val cy = (matchLoc.y + tplMat.rows() / 2f).toFloat()
            val rect = Rect(
                matchLoc.x.toInt(),
                matchLoc.y.toInt(),
                (matchLoc.x + tplMat.cols()).toInt(),
                (matchLoc.y + tplMat.rows()).toInt()
            )
            MatchResult(true, cx, cy, maxVal, rect)
        } else {
            MatchResult(false, 0f, 0f, maxVal, Rect())
        }
    }

    /**
     * 多尺度金字塔匹配 (应对不同手机 DPI 造成的图形缩放)
     */
    fun matchMultiScale(
        srcBitmap: Bitmap,
        templateName: String,
        threshold: Float = 0.75f
    ): MatchResult {
        val tplMat = templateCache[templateName] ?: return MatchResult(false, 0f, 0f, 0f, Rect())
        val scales = floatArrayOf(1.0f, 0.92f, 1.08f)
        var bestResult = MatchResult(false, 0f, 0f, 0f, Rect())

        for (s in scales) {
            val scaledTpl = Mat()
            if (s == 1.0f) {
                tplMat.copyTo(scaledTpl)
            } else {
                Imgproc.resize(tplMat, scaledTpl, Size(tplMat.cols() * s.toDouble(), tplMat.rows() * s.toDouble()))
            }

            val srcMat = Mat()
            Utils.bitmapToMat(srcBitmap, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2BGR)

            if (srcMat.cols() >= scaledTpl.cols() && srcMat.rows() >= scaledTpl.rows()) {
                val resultMat = Mat()
                Imgproc.matchTemplate(srcMat, scaledTpl, resultMat, Imgproc.TM_CCOEFF_NORMED)
                val mmr = Core.minMaxLoc(resultMat)
                val score = mmr.maxVal.toFloat()

                if (score > bestResult.score) {
                    val cx = (mmr.maxLoc.x + scaledTpl.cols() / 2f).toFloat()
                    val cy = (mmr.maxLoc.y + scaledTpl.rows() / 2f).toFloat()
                    val rect = Rect(
                        mmr.maxLoc.x.toInt(),
                        mmr.maxLoc.y.toInt(),
                        (mmr.maxLoc.x + scaledTpl.cols()).toInt(),
                        (mmr.maxLoc.y + scaledTpl.rows()).toInt()
                    )
                    bestResult = MatchResult(score >= threshold, cx, cy, score, rect)
                }
                resultMat.release()
            }
            srcMat.release()
            scaledTpl.release()

            if (bestResult.isFound && bestResult.score > 0.88f) break
        }
        return bestResult
    }
}
