package com.stzb.assistant.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.util.regex.Pattern

/**
 * 土地免战罩、倒计时、土地等级与要塞施工状态感知器 (TileStatusDetector)
 * 
 * 核心痛点解决：
 *   1. 土地免战倒计时毫秒级读取，支持极限压秒破免 (00:00:01 触敌) 与极限接力卡免；
 *   2. OpenCV HSV 颜色空间提取金色/黄色【免战光罩】几何轮廓；
 *   3. 极速局部 ROI 剪裁 + RapidOCR 高速抽取数字倒计时 (MM:SS 或 HH:MM:SS)；
 *   4. 土地等级 (Lv.1 ~ Lv.10) 与资源属性 (木铁石粮) 语义解析；
 *   5. 要塞施工状态感知 (区分“施工中”基座与“已建成”可调兵要塞)。
 */
object TileStatusDetector {

    private const val TAG = "TileStatusDetector"

    data class ImmunityStatus(
        val isImmune: Boolean,
        val remainingSeconds: Long,
        val unlockTimestampMs: Long,  // 毫秒级免战解除时间戳 (System.currentTimeMillis() + remainingSeconds * 1000)
        val shieldCenter: PointF?
    )

    enum class ResourceType {
        WOOD, IRON, STONE, GRAIN, UNKNOWN
    }

    data class LandTileDetail(
        val level: Int,                  // 土地等级 (1 ~ 10, 未识别为 0)
        val resourceType: ResourceType,  // 资源类型
        val isImmune: Boolean,           // 是否免战中
        val immunityRemainingSec: Long,  // 剩余免战秒数
        val isFortress: Boolean,         // 是否为要塞
        val isFortressBuilding: Boolean  // 是否为施工中要塞
    )

    // 金色免战罩在 HSV 色彩空间的阈值
    // H: 18 ~ 38 (纯正金黄色/浅黄光晕)
    // S: 100 ~ 255 (高饱和度，隔离普通暗黄土地)
    // V: 120 ~ 255 (高亮度)
    private val LOWER_GOLDEN = Scalar(18.0, 100.0, 120.0)
    private val UPPER_GOLDEN = Scalar(38.0, 255.0, 255.0)

    // 敌对占领地（红地）在 HSV 中的红色双区间：红跨色调 0°，必须分两段（同 RaidRadarDetector）。
    // S/V 取中段偏保守：宁可漏报几块，也不把行军红线/告警光晕误判成“一整格敌占地”。
    private val LOWER_RED_1 = Scalar(0.0, 110.0, 70.0)
    private val UPPER_RED_1 = Scalar(10.0, 255.0, 255.0)
    private val LOWER_RED_2 = Scalar(156.0, 110.0, 70.0)
    private val UPPER_RED_2 = Scalar(180.0, 255.0, 255.0)

    /** 构成“一整格敌对红地”的像素面积下限（720p 基准，低于此视为碎片/细线）。 */
    private const val MIN_ENEMY_TILE_AREA = 500.0
    private const val MAX_ENEMY_TILE_AREA = 40000.0

    private val PATTERN_LEVEL = Pattern.compile("(?:LV|Lv|lv|等级|级)[\\s.:：]*([1-9]|10)")

    /**
     * 检测画面指定区域或中心地块的免战光罩与倒计时
     * @param fullFrame 720p 完整画面或地块周围截取区域
     * @param searchArea 预期的地块搜索矩形 (可选，若不传则全图检索)
     */
    fun detectTileImmunity(fullFrame: Bitmap, searchArea: Rect? = null): ImmunityStatus {
        val now = System.currentTimeMillis()

        // 裁剪搜索区域以提高效率
        val workingBitmap = if (searchArea != null) {
            val left = maxOf(0, searchArea.left)
            val top = maxOf(0, searchArea.top)
            val w = minOf(fullFrame.width - left, searchArea.width())
            val h = minOf(fullFrame.height - top, searchArea.height())
            if (w <= 0 || h <= 0) return ImmunityStatus(false, 0L, 0L, null)
            Bitmap.createBitmap(fullFrame, left, top, w, h)
        } else {
            fullFrame
        }

        val srcMat = Mat()
        val hsvMat = Mat()
        val goldMask = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        try {
            Utils.bitmapToMat(workingBitmap, srcMat)
            Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // 1. 提取金色免战罩高光掩膜
            Core.inRange(hsvMat, LOWER_GOLDEN, UPPER_GOLDEN, goldMask)

            // 形态学闭运算连接光罩外轮廓
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(goldMask, goldMask, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            // 2. 查找光罩外接轮廓
            Imgproc.findContours(goldMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // 筛选符合地块光罩尺寸的轮廓 (宽 40~200px, 高 30~150px)
            var bestShieldRect: Rect? = null
            var maxArea = 0.0

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area in 800.0..30000.0) {
                    val r = Imgproc.boundingRect(c)
                    val aspect = r.width.toFloat() / r.height.toFloat()
                    // 光罩呈扁圆或半球弧形，长宽比一般在 0.8 ~ 2.2
                    if (aspect in 0.7f..2.5f && area > maxArea) {
                        maxArea = area
                        val offsetX = searchArea?.left ?: 0
                        val offsetY = searchArea?.top ?: 0
                        bestShieldRect = Rect(
                            r.x + offsetX,
                            r.y + offsetY,
                            r.x + r.width + offsetX,
                            r.y + r.height + offsetY
                        )
                    }
                }
            }

            // 若未检测到金色光罩轮廓，说明非免战土地
            if (bestShieldRect == null) {
                return ImmunityStatus(false, 0L, 0L, null)
            }

            val centerF = PointF(
                bestShieldRect.exactCenterX(),
                bestShieldRect.exactCenterY()
            )

            // 3. 在光罩正上方及中心 80px 高度范围内，提取倒计时文字 ROI
            val timerLeft = maxOf(0, (centerF.x - 70).toInt())
            val timerRight = minOf(fullFrame.width, (centerF.x + 70).toInt())
            val timerTop = maxOf(0, (bestShieldRect.top - 45))
            val timerBottom = minOf(fullFrame.height, bestShieldRect.bottom + 20)

            val timerWidth = timerRight - timerLeft
            val timerHeight = timerBottom - timerTop

            if (timerWidth > 10 && timerHeight > 10) {
                val timerBmp = Bitmap.createBitmap(fullFrame, timerLeft, timerTop, timerWidth, timerHeight)
                val remainingSec = OcrManager.parseCountdownSeconds(timerBmp) ?: 0L
                timerBmp.recycle()

                val unlockTime = if (remainingSec > 0) now + remainingSec * 1000L else 0L

                Log.i(TAG, "【免战感知】发现地块免战罩！剩余: ${remainingSec}s, 预期破免时间戳: $unlockTime")
                return ImmunityStatus(true, remainingSec, unlockTime, centerF)
            }

            return ImmunityStatus(true, 0L, 0L, centerF)

        } catch (e: Exception) {
            Log.e(TAG, "检测免战光罩异常: ${e.message}", e)
            return ImmunityStatus(false, 0L, 0L, null)
        } finally {
            srcMat.release()
            hsvMat.release()
            goldMask.release()
            hierarchy.release()
            contours.forEach { it.release() }
            if (searchArea != null && workingBitmap != fullFrame) {
                workingBitmap.recycle()
            }
        }
    }

    /**
     * 敌对占领地（红地）分割：在整图或指定区域里找出被敌方占领的地块。
     *
     * 做法：等距地块是一格**实心红色菱形**，故先用 HSV 双区间红掩膜（红跨色调 0°，
     * 必须分两段，与免战罩同源的光罩提取一致的骨架）提取红色 → 形态学开+闭去碎点并填洞
     * → 外轮廓 → 再按**面积带 + 长宽比 + 实心度**三重过滤，把行军红线、红色告警
     * 光晕这类“细/空”的红排除掉，只留“一整格红地”。
     *
     * 诚实边界：率土红地与黄土地在低饱和/强光照下会偏色，阈值取中段偏保守；
     * 面积/长宽/实心度阈值待真机不同分辨率标定。误判为敌占格会触发错误避让/攻击，
     * 因此这里坚持“宁可漏报、不可误报”。
     *
     * @param fullFrame 720p 完整画面或地块周围截取区域
     * @param searchArea 预期搜索矩形（可选，不传则全图检索）
     * @return 红地外接矩形列表（全画面绝对坐标，按面积从大到小）；无则空列表
     */
    fun detectEnemyTiles(fullFrame: Bitmap, searchArea: Rect? = null): List<Rect> {
        val workingBitmap = if (searchArea != null) {
            val left = maxOf(0, searchArea.left)
            val top = maxOf(0, searchArea.top)
            val w = minOf(fullFrame.width - left, searchArea.width())
            val h = minOf(fullFrame.height - top, searchArea.height())
            if (w <= 0 || h <= 0) return emptyList()
            Bitmap.createBitmap(fullFrame, left, top, w, h)
        } else {
            fullFrame
        }
        val offsetX = searchArea?.left ?: 0
        val offsetY = searchArea?.top ?: 0

        val srcMat = Mat()
        val hsvMat = Mat()
        val redMask = Mat()
        val redMask2 = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        val results = ArrayList<Rect>()

        try {
            Utils.bitmapToMat(workingBitmap, srcMat)
            Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // 1. 红掩膜双区间并集（红跨 0°，单区间会漏掉一半色相）
            Core.inRange(hsvMat, LOWER_RED_1, UPPER_RED_1, redMask)
            Core.inRange(hsvMat, LOWER_RED_2, UPPER_RED_2, redMask2)
            Core.bitwise_or(redMask, redMask2, redMask)

            // 2. 开运算去孤立红点，闭运算填平菱形内部空洞
            val kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
            val kClose = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_OPEN, kOpen)
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_CLOSE, kClose)
            kOpen.release()
            kClose.release()

            // 3. 外轮廓 + 三重几何过滤，只保留“一整格红地”
            Imgproc.findContours(redMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area !in MIN_ENEMY_TILE_AREA..MAX_ENEMY_TILE_AREA) continue
                val r = Imgproc.boundingRect(c)
                // 太小的碎点、太细的红线直接排除
                if (r.width < 30 || r.height < 15) continue
                val aspect = r.width.toFloat() / r.height.toFloat()
                // 等距地块外接框约为 2:1 的扁菱形；太方/太长都排除
                if (aspect !in 1.1f..3.0f) continue
                // 实心度：菱形填充率≈0.5，细长红线远低于此，用来剔除线状红
                val solidity = area / (r.width.toDouble() * r.height.toDouble())
                if (solidity < 0.45) continue
                results.add(
                    Rect(
                        r.x + offsetX,
                        r.y + offsetY,
                        r.x + r.width + offsetX,
                        r.y + r.height + offsetY
                    )
                )
            }
            results.sortByDescending { it.width().toLong() * it.height().toLong() }
            Log.i(TAG, "【敌占红地】本帧检出 ${results.size} 块")
            return results
        } catch (e: Exception) {
            Log.e(TAG, "敌占红地分割异常: ${e.message}", e)
            return emptyList()
        } finally {
            srcMat.release()
            hsvMat.release()
            redMask.release()
            redMask2.release()
            hierarchy.release()
            contours.forEach { it.release() }
            if (searchArea != null && workingBitmap != fullFrame) {
                workingBitmap.recycle()
            }
        }
    }

    /**
     * 土地信息面板解析 (点击土地后弹出的详情条/顶部地块标签)
     */
    fun parseTileDetail(roiBitmap: Bitmap): LandTileDetail {
        val ocr = OcrManager.detectRoi(roiBitmap)
        val text = ocr?.strRes ?: ""

        // 解析等级
        var level = 0
        val matcher = PATTERN_LEVEL.matcher(text)
        if (matcher.find()) {
            level = matcher.group(1)?.toIntOrNull() ?: 0
        }

        // 解析资源类型
        val resType = when {
            text.contains("木") || text.contains("林") -> ResourceType.WOOD
            text.contains("铁") || text.contains("矿") -> ResourceType.IRON
            text.contains("石") || text.contains("采石") -> ResourceType.STONE
            text.contains("粮") || text.contains("田") -> ResourceType.GRAIN
            else -> ResourceType.UNKNOWN
        }

        // 判断是否为要塞及施工状态
        val isFortress = text.contains("要塞") || text.contains("工营") || text.contains("营地")
        val isBuilding = isFortress && (text.contains("施工") || text.contains("建造中") || text.contains("完成时间"))

        // 判断是否含有免战倒计时
        val isImmune = text.contains("免战") || text.contains("免")
        val remainingSec = if (isImmune) {
            OcrManager.parseCountdownSeconds(roiBitmap) ?: 0L
        } else 0L

        return LandTileDetail(
            level = level,
            resourceType = resType,
            isImmune = isImmune,
            immunityRemainingSec = remainingSec,
            isFortress = isFortress,
            isFortressBuilding = isBuilding
        )
    }
}
