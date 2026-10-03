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
import org.opencv.core.Scalar
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

    /**
     * 最低"区分度"：最佳分数减去**不重叠**的次佳分数。
     *
     * ## 为什么必须加这道判据
     * 原实现只判断 `maxVal >= threshold`。但游戏里常有**重复美术**
     * （多个相同图标、多个"确定"），此时两处的相关系数会**一样高**——
     * 只看最高分就会自信地报"找到了"，而调用方可能点到错的那个。
     *
     * 离线实测（`tools/validate_template_matcher.py`，在真机截图上跑同一套数学）：
     *   * 唯一目标：最佳 1.0000 / 次高 0.4437 → 区分度 0.5563，可安全采用；
     *   * 把同一图案贴两次：最佳 1.0000 / 次高 **1.0000** → 区分度 **0**，
     *     此时"命中"是不可信的，必须拒绝。
     * 0.04 这个阈值取在"重复图案(≈0)"与"唯一目标(≈0.55)"之间，留足余量。
     */
    private const val MIN_DISTINCTIVENESS = 0.04f
    private var isInitialized = false

    // 红色徽标（新邮件红点/未读角标）的 HSV 双区间：红跨色调 0°，必须分两段。
    private val LOWER_RED_1 = Scalar(0.0, 100.0, 90.0)
    private val UPPER_RED_1 = Scalar(10.0, 255.0, 255.0)
    private val LOWER_RED_2 = Scalar(156.0, 100.0, 90.0)
    private val UPPER_RED_2 = Scalar(180.0, 255.0, 255.0)

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
     * OpenCV 原生库是否可用。
     *
     * 调用方据此决定"用 OpenCV 匹配"还是"退回纯 Java 匹配"
     * （`OpenCVLoader.initDebug()` 在某些 ABI/打包方式下会失败）。
     */
    val isAvailable: Boolean get() = isInitialized

    /**
     * 从**任意 Bitmap** 载入模板至内存缓存。
     *
     * 原实现只能从 `assets/templates/` 载入，因此运行期登记/学习的模板
     * （存在 `filesDir/button_templates/`）无法进入它的缓存，
     * 这个匹配引擎也就一直没人用得上。补齐这个入口才能把它接进按键定位。
     */
    fun loadTemplateFromBitmap(keyName: String, bitmap: Bitmap): Boolean {
        return try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
            templateCache[keyName]?.release()
            templateCache[keyName] = mat
            true
        } catch (e: Throwable) {
            Log.w(TAG, "从 Bitmap 载入模板 [$keyName] 失败: ${e.message}")
            false
        }
    }

    /** 某个模板是否已在缓存里。 */
    fun hasTemplate(keyName: String): Boolean = templateCache.containsKey(keyName)

    /** 释放某个模板缓存（模板被重新登记后需要先失效）。 */
    fun invalidateTemplate(keyName: String) {
        templateCache.remove(keyName)?.release()
    }

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
        var stream: InputStream? = null
        var bmp: Bitmap? = null
        return try {
            stream = context.assets.open(assetPath)
            val decoded = BitmapFactory.decodeStream(stream)
            if (decoded == null) {
                Log.w(TAG, "模板 [$keyName] 解码失败（资产可能损坏）")
                return false
            }
            bmp = decoded
            val m = Mat()
            try {
                Utils.bitmapToMat(decoded, m)
                Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2BGR)
                templateCache[keyName] = m
            } catch (e: Exception) {
                m.release()
                throw e
            }
            Log.d(TAG, "模板 [$keyName] 载入成功 (${m.cols()}x${m.rows()})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "加载模板 [$keyName] 失败: ${e.message}")
            false
        } finally {
            bmp?.recycle()
            stream?.close()   // ⚠️ 关键修复：input 此前从不 close，反复加载会泄漏文件句柄
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
        var resultMat: Mat? = null
        return try {
            Utils.bitmapToMat(srcBitmap, srcMat)
            Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2BGR)

            if (srcMat.cols() < tplMat.cols() || srcMat.rows() < tplMat.rows()) {
                return MatchResult(false, 0f, 0f, 0f, Rect())
            }

            val rm = Mat()
            resultMat = rm
            Imgproc.matchTemplate(srcMat, tplMat, rm, Imgproc.TM_CCOEFF_NORMED)

            val mmr = Core.minMaxLoc(rm)
            val maxVal = mmr.maxVal.toFloat()
            val matchLoc: Point = mmr.maxLoc
            val runnerUp = peakDistinctiveness(rm, matchLoc, tplMat.cols(), tplMat.rows())

            // 两道判据缺一不可：分数够高，且**不与最佳位置重叠**的次高分明显更低。
            // 只满足第一条时画面里很可能有重复图案，此时拒绝比猜一个安全。
            if (maxVal >= threshold && (maxVal - runnerUp) >= MIN_DISTINCTIVENESS) {
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
                if (maxVal >= threshold) {
                    Log.w(
                        TAG,
                        "模板 [$templateName] 分数 $maxVal 达标，但区分度不足" +
                            "（次高 $runnerUp，需 ≥ $MIN_DISTINCTIVENESS）：画面里可能有重复图案，" +
                            "已拒绝以免点错目标。"
                    )
                }
                MatchResult(false, 0f, 0f, maxVal, Rect())
            }
        } finally {
            // ⚠️ 关键修复：Mat 释放置于 finally，任何匹配/峰区分异常路径都不再泄漏。
            resultMat?.release()
            srcMat.release()
        }
    }

    /**
     * 红色连通域质心定位：把“某个区域里有没有红点/红角标”从整块 ROI 粗判，
     * 升级为**逐个真实小红簇**的质心 + 外接框。
     *
     * 专治 mail_alert 这类“小红色徽标”——它不是可模板匹配的固定图标，而是
     * 叠加在按钮上的红点，靠颜色定义、位置随角标出现与否变化。做法：
     * HSV 双区间红掩膜（红跨 0°）→ connectedComponentsWithStats 逐簇 →
     * 按面积带 + 近圆度过滤出“角标尺寸”的红块 → 返回质心/外接框/置信度。
     *
     * 置信度用“圆度”（短边/长边，正圆≈１）表示：红角标接近圆形，细长红条圆度低→低置信。
     * 面积/圆度阈值待真机标定；本机不可验证，务必结合真机日志回溯。
     *
     * @param roi 只在该屏幕矩形内找（全画面绝对坐标）；null=全图
     * @return 红簇列表（全画面绝对坐标，按面积从大到小）；无则空列表
     */
    fun findRedBadgeClusters(
        srcBitmap: Bitmap,
        roi: Rect? = null,
        minArea: Double = 25.0,
        maxArea: Double = 2500.0
    ): List<MatchResult> {
        if (!isInitialized) return emptyList()
        val full = Mat()
        val bgr = Mat()
        val hsv = Mat()
        val mask = Mat()
        val mask2 = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        var sub: Mat? = null
        val results = ArrayList<MatchResult>()
        try {
            Utils.bitmapToMat(srcBitmap, full)
            Imgproc.cvtColor(full, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)

            val x0 = if (roi != null) maxOf(0, roi.left) else 0
            val y0 = if (roi != null) maxOf(0, roi.top) else 0
            sub = if (roi != null) {
                val x1 = minOf(hsv.cols(), roi.right)
                val y1 = minOf(hsv.rows(), roi.bottom)
                if (x1 - x0 < 2 || y1 - y0 < 2) return emptyList()
                Mat(hsv, org.opencv.core.Rect(x0, y0, x1 - x0, y1 - y0))
            } else {
                hsv
            }

            Core.inRange(sub!!, LOWER_RED_1, UPPER_RED_1, mask)
            Core.inRange(sub!!, LOWER_RED_2, UPPER_RED_2, mask2)
            Core.bitwise_or(mask, mask2, mask)

            val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            for (i in 1 until n) {   // label 0 是背景，跳过
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                if (area !in minArea..maxArea) continue
                val left = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val top = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (w <= 0 || h <= 0) continue
                // 近圆度：角标接近正圆→高；细长红条→低
                val roundness = minOf(w, h).toDouble() / maxOf(w, h).toDouble()
                if (roundness < 0.35) continue
                val cx = centroids.get(i, 0)[0] + x0
                val cy = centroids.get(i, 1)[0] + y0
                results.add(
                    MatchResult(
                        isFound = true,
                        centerX = cx.toFloat(),
                        centerY = cy.toFloat(),
                        score = roundness.toFloat(),
                        rect = Rect(left + x0, top + y0, left + x0 + w, top + y0 + h)
                    )
                )
            }
            results.sortByDescending { (it.rect.width() * it.rect.height()).toDouble() }
            return results
        } catch (e: Throwable) {
            Log.w(TAG, "红簇连通域定位失败: ${e.message}")
            return emptyList()
        } finally {
            // sub 为 roi==null 时就是 hsv 本身，不能重复释放；只在它是独立视图时释放
            if (roi != null) sub?.release()
            full.release()
            bgr.release()
            hsv.release()
            mask.release()
            mask2.release()
            labels.release()
            stats.release()
            centroids.release()
        }
    }

    /**
     * 最佳位置与"**不重叠**的次佳位置"之间的分数差。
     *
     * 做法：把最佳位置及其模板大小的邻域用 -1 填掉（TM_CCOEFF_NORMED 的取值范围是
     * [-1, 1]，填 -1 不会成为次高），再取剩余区域的最大值。
     * 用"不重叠"而不是"全部次大"，是为了避免把同一个目标的相邻位置当成竞争者——
     * 那会让一个真实且唯一的目标也被误判为"区分度不足"。
     */
    private fun peakDistinctiveness(
        resultMat: Mat,
        matchLoc: Point,
        tplW: Int,
        tplH: Int
    ): Float {
        val cx = matchLoc.x.toInt()
        val cy = matchLoc.y.toInt()
        val x0 = maxOf(0, cx - tplW + 1)
        val y0 = maxOf(0, cy - tplH + 1)
        val x1 = minOf(resultMat.cols(), cx + tplW)
        val y1 = minOf(resultMat.rows(), cy + tplH)
        if (x0 >= x1 || y0 >= y1) return -1f

        val masked = resultMat.clone()
        return try {
            Imgproc.rectangle(
                masked,
                Point(x0.toDouble(), y0.toDouble()),
                Point((x1 - 1).toDouble(), (y1 - 1).toDouble()),
                // 用 4 参构造而不是 `Scalar(-1.0)`：
                // OpenCV 的 Java 侧各版本对单参/varargs 构造的支持不一致，
                // 而 4 参构造是本工程已在用的形式（见 RaidRadarDetector），无歧义。
                Scalar(-1.0, -1.0, -1.0, -1.0),
                -1
            )
            Core.minMaxLoc(masked).maxVal.toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "计算区分度失败: ${e.message}")
            -1f
        } finally {
            masked.release()
        }
    }

    /**
     * 多尺度图配入口已移除：本引擎现只对外提供 [match]（单尺度 + 区分度判据）。
     * 历史上 `matchMultiScale` 想靠 0.92x~1.08x 金字塔抹平机型 DPI 缩放，但：
     *   1. 全工程**无任何调用方**（按“严禁死代码”必须清除）；
     *   2. 分辨率/缩放差异已由 [com.stzb.assistant.service.SceneFingerprint] 标定 +
     *      [StzbUiMatcher] 语义 OCR 定位从根上解决，模板金字塔既多余又慢。
     * 若将来确需跨尺度模板匹配，应重新按真实调用方设计，而非保留无人问津的死 API。
     */
}
