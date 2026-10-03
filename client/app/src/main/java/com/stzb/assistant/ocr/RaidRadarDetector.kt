package com.stzb.assistant.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 深夜敌袭雷达红线检测、主城警戒圈判定与源头回溯器 (RaidRadarDetector)
 * 
 * 核心痛点解决：
 *   1. 凌晨 3:00~5:00 敌盟夜战偷家全天候自动化巡检；
 *   2. OpenCV HSV 颜色空间双区间提取高饱和敌袭纯红行军线（隔离友军蓝绿箭头）；
 *   3. 霍夫概率线段变换 (HoughLinesP) 提取行军轨迹向量与起止端点；
 *   4. 屏幕边缘红光闪烁告警感知 (Screen Edge Border Alert)；
 *   5. 【2026 核心升级】：主界面顶部【原生受袭预警红标 (Top Alert Badge)】与倒计时提取；
 *   6. 【主城 2 格警戒圈判定 (5x5 核心威胁区)】：切比雪夫距离与投影几何过滤，杜绝虚假远距报警；
 *   7. 【决策 C 支撑】：源头要塞与跳板地回溯定位 (Origin Backtracer)，支持反击拆除敌军跳板。
 */
object RaidRadarDetector {

    private const val TAG = "RaidRadarDetector"

    enum class ThreatLevel {
        NONE,       // 安全，无任何敌袭红线
        WARNING,    // 远距离观测到红线行军轨迹（主城 2 格警戒圈外）
        CRITICAL    // 极度危险：红线直接突入主城 2 格警戒圈，或顶部出现受袭预警红标，或屏幕边缘红光剧烈闪烁
    }

    data class MarchVector(
        val startPoint: PointF,     // 轨迹端点 A
        val endPoint: PointF,       // 轨迹端点 B
        val length: Float,          // 线段像素长度
        val angleDeg: Float         // 倾斜角度 (-180 ~ 180)
    )

    data class RaidReport(
        val hasThreat: Boolean,
        val threatLevel: ThreatLevel,
        val isScreenEdgeAlert: Boolean,
        val detectedVectors: List<MarchVector>,
        val enemyOriginPoint: PointF?,  // 敌军出征源头（要塞/跳板地，用于决策 C 反击拆除）
        val playerTargetPoint: PointF?, // 受威胁的己方目标点（要塞/主城）
        val timestampMs: Long,
        val isTopAlertActive: Boolean = false,          // 顶部原生受袭/被攻击预警红标是否触发
        val isWithinAlertCircle: Boolean = false,      // 威胁终点是否进入主城 2 格核心警戒圈
        val remainingCountdownSeconds: Int? = null,    // 顶部预警倒计时剩余秒数 (若识别出)
        val targetWorldCoord: Pair<Int, Int>? = null   // 受威胁目标的世界坐标 (若已标定)
    )

    // HSV 纯红阈值范围 (由于红色在 HSV 环上跨越 0 度，需分段提取双掩膜)
    // Range 1: 0 ~ 10
    private val LOWER_RED_1 = Scalar(0.0, 120.0, 100.0)
    private val UPPER_RED_1 = Scalar(10.0, 255.0, 255.0)
    // Range 2: 160 ~ 180
    private val LOWER_RED_2 = Scalar(160.0, 120.0, 100.0)
    private val UPPER_RED_2 = Scalar(180.0, 255.0, 255.0)

    /**
     * 全图敌袭雷达扫描
     * @param frame 当前 720p 虚拟画布截图
     * @param baseAnchor 己方基地参考锚点（默认画面中心，若已知主城位置可传入精确点）
     * @param baseWorldCoord 己方主城世界坐标 (如 Pair(550, 480))
     * @param alertRadiusTiles 警戒圈格数 (默认 2 格，5x5 威胁区)
     */
    fun scanRaidThreats(
        frame: Bitmap,
        baseAnchor: PointF? = null,
        baseWorldCoord: Pair<Int, Int>? = null,
        alertRadiusTiles: Int = 2
    ): RaidReport {
        val now = System.currentTimeMillis()
        val srcMat = Mat()
        val hsvMat = Mat()
        val mask1 = Mat()
        val mask2 = Mat()
        val redMask = Mat()
        val linesMat = Mat()

        try {
            Utils.bitmapToMat(frame, srcMat)
            Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // 1. 边缘红光闪烁告警检测
            val isEdgeAlert = detectScreenEdgeAlert(hsvMat)

            // 2. 顶部原生受袭/被攻击预警红标与倒计时检测
            val (isTopAlert, countdownSecs) = detectTopAlertBadge(hsvMat, frame)

            // 3. 红色行军轨迹双掩膜提取
            Core.inRange(hsvMat, LOWER_RED_1, UPPER_RED_1, mask1)
            Core.inRange(hsvMat, LOWER_RED_2, UPPER_RED_2, mask2)
            Core.bitwise_or(mask1, mask2, redMask)

            // 4. 形态学滤波：过滤微小杂点，并连接行军箭头虚线
            val kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_OPEN, kOpen)
            val kClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_CLOSE, kClose)
            kOpen.release()
            kClose.release()

            // 5. 概率霍夫线变换提取红线段
            // 参数针对 720p 率土行军线深度调优：最小线长 50px，最大间隙 25px
            Imgproc.HoughLinesP(redMask, linesMat, 1.0, Math.PI / 180.0, 35, 50.0, 25.0)

            val rawVectors = mutableListOf<MarchVector>()
            val numLines = linesMat.rows()

            for (i in 0 until numLines) {
                val line = linesMat.get(i, 0) ?: continue
                val x1 = line[0].toFloat()
                val y1 = line[1].toFloat()
                val x2 = line[2].toFloat()
                val y2 = line[3].toFloat()

                val length = hypot(x2 - x1, y2 - y1)
                val angleDeg = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()

                rawVectors.add(
                    MarchVector(
                        startPoint = PointF(x1, y1),
                        endPoint = PointF(x2, y2),
                        length = length,
                        angleDeg = angleDeg
                    )
                )
            }

            // 6. 聚类合并相近的线段
            val clusteredVectors = clusterMarchLines(rawVectors)

            if (clusteredVectors.isEmpty() && !isEdgeAlert && !isTopAlert) {
                return RaidReport(
                    hasThreat = false,
                    threatLevel = ThreatLevel.NONE,
                    isScreenEdgeAlert = false,
                    detectedVectors = emptyList(),
                    enemyOriginPoint = null,
                    playerTargetPoint = null,
                    timestampMs = now,
                    isTopAlertActive = false,
                    isWithinAlertCircle = false,
                    remainingCountdownSeconds = null,
                    targetWorldCoord = null
                )
            }

            // 7. 源头要塞回溯分析 (决策 C 关键支撑)
            val anchor = baseAnchor ?: PointF(frame.width / 2f, frame.height / 2f)
            val (enemyOrigin, playerTarget) = traceAttackOriginAndTarget(clusteredVectors, anchor)

            // 8. 主城 2 格警戒圈判定 (5x5 核心威胁区)
            val baseCoord = baseWorldCoord ?: com.stzb.assistant.service.MapProjection.baseWorld
            var isWithinCircle = false
            var targetWorld: Pair<Int, Int>? = null

            if (playerTarget != null) {
                if (baseCoord != null && com.stzb.assistant.service.MapProjection.isCalibrated) {
                    targetWorld = com.stzb.assistant.service.MapProjection.screenToWorld(playerTarget.x, playerTarget.y)
                    if (targetWorld != null) {
                        val chebyshevDist = maxOf(
                            abs(targetWorld.first - baseCoord.first),
                            abs(targetWorld.second - baseCoord.second)
                        )
                        isWithinCircle = chebyshevDist <= alertRadiusTiles
                    }
                }
                if (!isWithinCircle) {
                    // 屏幕像素距离兜底：720p 虚拟画布下每格约 120~140px，2 格警戒圈取 280px
                    val distPx = hypot(playerTarget.x - anchor.x, playerTarget.y - anchor.y)
                    isWithinCircle = distPx <= (alertRadiusTiles * 140f)
                }
            } else if (isTopAlert) {
                // 顶部原生受袭红标触发，官方确定的本土受袭事件
                isWithinCircle = true
            }

            // 9. 综合判定威胁等级
            val hasAnyMarchThreat = clusteredVectors.isNotEmpty()
            val isCritical = isTopAlert || isEdgeAlert || (hasAnyMarchThreat && isWithinCircle)
            val threatLevel = when {
                isCritical -> ThreatLevel.CRITICAL
                hasAnyMarchThreat -> ThreatLevel.WARNING
                else -> ThreatLevel.NONE
            }
            val hasThreat = threatLevel != ThreatLevel.NONE

            if (hasThreat) {
                Log.w(
                    TAG,
                    "【夜战天眼雷达告警】等级: $threatLevel, 顶部预警: $isTopAlert (倒计时: ${countdownSecs ?: -1}s), 2格警戒圈: $isWithinCircle, 边缘红闪: $isEdgeAlert, 红线数: ${clusteredVectors.size}, 目标: $playerTarget, 敌源: $enemyOrigin"
                )
            }

            return RaidReport(
                hasThreat = hasThreat,
                threatLevel = threatLevel,
                isScreenEdgeAlert = isEdgeAlert,
                detectedVectors = clusteredVectors,
                enemyOriginPoint = enemyOrigin,
                playerTargetPoint = playerTarget,
                timestampMs = now,
                isTopAlertActive = isTopAlert,
                isWithinAlertCircle = isWithinCircle,
                remainingCountdownSeconds = countdownSecs,
                targetWorldCoord = targetWorld
            )

        } catch (e: Exception) {
            Log.e(TAG, "敌袭雷达图像处理异常: ${e.message}", e)
            return RaidReport(
                hasThreat = false,
                threatLevel = ThreatLevel.NONE,
                isScreenEdgeAlert = false,
                detectedVectors = emptyList(),
                enemyOriginPoint = null,
                playerTargetPoint = null,
                timestampMs = now,
                isTopAlertActive = false,
                isWithinAlertCircle = false,
                remainingCountdownSeconds = null,
                targetWorldCoord = null
            )
        } finally {
            // 严控显存与 Native 内存回收，杜绝后台常驻服务 OOM
            srcMat.release()
            hsvMat.release()
            mask1.release()
            mask2.release()
            redMask.release()
            linesMat.release()
        }
    }

    /**
     * 检测主界面顶部的【受袭/被攻击预警红标 (Top Alert Badge)】与倒计时
     * @param hsvMat 已经转换好的 HSV 图像
     * @param fullFrame 原始 Bitmap，用于必要时从顶部切片提取文字倒计时
     */
    private fun detectTopAlertBadge(
        hsvMat: Mat,
        fullFrame: Bitmap
    ): Pair<Boolean, Int?> {
        val width = hsvMat.cols()
        val height = hsvMat.rows()
        if (width <= 0 || height <= 0) return Pair(false, null)

        val topHeight = (height * 0.20f).toInt().coerceAtLeast(1)
        val topHsv = hsvMat.submat(0, topHeight, 0, width)
        val mask1 = Mat()
        val mask2 = Mat()
        val redMask = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        var hasRedBadge = false
        var countdownSeconds: Int? = null

        try {
            Core.inRange(topHsv, LOWER_RED_1, UPPER_RED_1, mask1)
            Core.inRange(topHsv, LOWER_RED_2, UPPER_RED_2, mask2)
            Core.bitwise_or(mask1, mask2, redMask)

            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_OPEN, k)
            k.release()

            Imgproc.findContours(redMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            for (c in contours) {
                val area = Imgproc.contourArea(c)
                val rect = Imgproc.boundingRect(c)
                if (area >= 80.0 && rect.width in 12..320 && rect.height in 12..120) {
                    hasRedBadge = true
                    break
                }
            }

            if (hasRedBadge && OcrManager.isEngineAvailable) {
                val topCrop = Bitmap.createBitmap(fullFrame, 0, 0, width, topHeight)
                val ocrResult = OcrManager.detect(topCrop)
                topCrop.recycle()

                if (ocrResult != null) {
                    val text = ocrResult.strRes
                    if (text.contains("受袭") || text.contains("被攻击") || text.contains("敌袭") || text.contains("警报")) {
                        hasRedBadge = true
                    }
                    val timePattern = java.util.regex.Pattern.compile("(?:(\\d{1,2})[:：])?(\\d{1,2})(?:秒)?")
                    for (block in ocrResult.textBlocks) {
                        val m = timePattern.matcher(block.text)
                        if (m.find()) {
                            val minStr = m.group(1)
                            val secStr = m.group(2)
                            val mins = minStr?.toIntOrNull() ?: 0
                            val secs = secStr?.toIntOrNull() ?: 0
                            val totalSec = mins * 60 + secs
                            if (totalSec in 1..1800) {
                                countdownSeconds = totalSec
                                break
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "顶部受袭预警红标检测异常: ${e.message}")
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
            mask1.release()
            mask2.release()
            redMask.release()
            topHsv.release()
        }

        return Pair(hasRedBadge, countdownSeconds)
    }

    /**
     * 屏幕四周边缘红光闪烁感知 (游戏被夜袭时，屏幕边框会剧烈泛起红色呼吸警报光晕)
     */
    private fun detectScreenEdgeAlert(hsvMat: Mat): Boolean {
        val width = hsvMat.cols()
        val height = hsvMat.rows()
        if (width <= 0 || height <= 0) return false

        val borderWidth = 12 // 提取外围 12px 边缘环带
        val topStrip = hsvMat.submat(0, borderWidth, 0, width)
        val bottomStrip = hsvMat.submat(height - borderWidth, height, 0, width)
        val leftStrip = hsvMat.submat(0, height, 0, borderWidth)
        val rightStrip = hsvMat.submat(0, height, width - borderWidth, width)

        val isAlert = checkStripRedDensity(topStrip) ||
                checkStripRedDensity(bottomStrip) ||
                checkStripRedDensity(leftStrip) ||
                checkStripRedDensity(rightStrip)

        topStrip.release()
        bottomStrip.release()
        leftStrip.release()
        rightStrip.release()

        return isAlert
    }

    /**
     * 校验局部边缘带的高饱和纯红像素占比
     */
    private fun checkStripRedDensity(stripHsv: Mat): Boolean {
        val mask1 = Mat()
        val mask2 = Mat()
        val combined = Mat()

        try {
            Core.inRange(stripHsv, LOWER_RED_1, UPPER_RED_1, mask1)
            Core.inRange(stripHsv, LOWER_RED_2, UPPER_RED_2, mask2)
            Core.bitwise_or(mask1, mask2, combined)

            val redPixelCount = Core.countNonZero(combined)
            val totalPixels = stripHsv.cols() * stripHsv.rows()
            val ratio = redPixelCount.toFloat() / totalPixels.toFloat()

            // 边缘红色像素占比 > 8% 视为触发敌袭红色呼吸灯
            return ratio > 0.08f
        } finally {
            mask1.release()
            mask2.release()
            combined.release()
        }
    }

    /**
     * 合并同向、共线的碎片化线段
     */
    private fun clusterMarchLines(lines: List<MarchVector>): List<MarchVector> {
        if (lines.isEmpty()) return emptyList()

        val results = mutableListOf<MarchVector>()
        val visited = BooleanArray(lines.size)

        for (i in lines.indices) {
            if (visited[i]) continue
            visited[i] = true
            var cur = lines[i]

            for (j in (i + 1) until lines.size) {
                if (visited[j]) continue
                val other = lines[j]

                // 若角度极度接近 (差值 < 12度) 且端点距离在 40px 内，判定为同一行军轨迹
                val angleDiff = Math.abs(cur.angleDeg - other.angleDeg)
                val dist = hypot(cur.endPoint.x - other.startPoint.x, cur.endPoint.y - other.startPoint.y)

                if (angleDiff < 12f && dist < 40f) {
                    visited[j] = true
                    // 拼接扩展
                    val newLength = cur.length + other.length
                    cur = MarchVector(cur.startPoint, other.endPoint, newLength, cur.angleDeg)
                }
            }
            if (cur.length >= 45f) { // 过滤过短线段
                results.add(cur)
            }
        }
        return results
    }

    /**
     * 源头要塞与目标点回溯算法
     * 距离己方基地更近的端点为被攻击目标点 (Target)；
     * 距离更远的端点为敌军进攻源头 (Origin，即跳板要塞或链接地)。
     */
    private fun traceAttackOriginAndTarget(
        vectors: List<MarchVector>,
        baseAnchor: PointF
    ): Pair<PointF?, PointF?> {
        if (vectors.isEmpty()) return Pair(null, null)

        // 选取长度最长的一条主威胁红线进行剖析
        val mainLine = vectors.maxByOrNull { it.length } ?: return Pair(null, null)

        val distStart = hypot(mainLine.startPoint.x - baseAnchor.x, mainLine.startPoint.y - baseAnchor.y)
        val distEnd = hypot(mainLine.endPoint.x - baseAnchor.x, mainLine.endPoint.y - baseAnchor.y)

        return if (distStart < distEnd) {
            // startPoint 靠近己方基地 => target 是 startPoint，origin 是 endPoint
            Pair(mainLine.endPoint, mainLine.startPoint)
        } else {
            // endPoint 靠近己方基地 => target 是 endPoint，origin 是 startPoint
            Pair(mainLine.startPoint, mainLine.endPoint)
        }
    }
}
