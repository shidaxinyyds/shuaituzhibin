package com.stzb.assistant.ocr

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 深夜敌袭雷达红线检测与源头要塞反击回溯器 (RaidRadarDetector)
 * 
 * 核心痛点解决：
 *   1. 凌晨 3:00~5:00 敌盟夜战偷家全天候自动化巡检；
 *   2. OpenCV HSV 颜色空间双区间提取高饱和敌袭纯红行军线（隔离友军蓝绿箭头）；
 *   3. 霍夫概率线段变换 (HoughLinesP) 提取行军轨迹向量与起止端点；
 *   4. 屏幕边缘红光闪烁告警感知 (Screen Edge Border Alert)；
 *   5. 【决策 C 核心支撑】：源头要塞与跳板地回溯定位 (Origin Backtracer)，
 *      精准推算敌军出发地坐标，支持自动派出高机动骑兵拆迁队反扑断链接地！
 */
object RaidRadarDetector {

    private const val TAG = "RaidRadarDetector"

    enum class ThreatLevel {
        NONE,       // 安全，无任何敌袭红线
        WARNING,    // 远距离观测到红线行军轨迹
        CRITICAL    // 极度危险：红线直接指向主城/要塞，或屏幕边缘红光剧烈闪烁
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
        val timestampMs: Long
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
     */
    fun scanRaidThreats(frame: Bitmap, baseAnchor: PointF? = null): RaidReport {
        val now = System.currentTimeMillis()
        val srcMat = Mat()
        val hsvMat = Mat()
        val mask1 = Mat()
        val mask2 = Mat()
        val redMask = Mat()
        val kernelOpen = Mat()
        val kernelClose = Mat()
        val linesMat = Mat()

        try {
            Utils.bitmapToMat(frame, srcMat)
            Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsvMat, hsvMat, Imgproc.COLOR_RGB2HSV)

            // 1. 边缘红光闪烁告警检测
            val isEdgeAlert = detectScreenEdgeAlert(hsvMat)

            // 2. 红色行军轨迹双掩膜提取
            Core.inRange(hsvMat, LOWER_RED_1, UPPER_RED_1, mask1)
            Core.inRange(hsvMat, LOWER_RED_2, UPPER_RED_2, mask2)
            Core.bitwise_or(mask1, mask2, redMask)

            // 3. 形态学滤波：过滤微小杂点，并连接行军箭头虚线
            val kOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_OPEN, kOpen)
            val kClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(redMask, redMask, Imgproc.MORPH_CLOSE, kClose)
            kOpen.release()
            kClose.release()

            // 4. 概率霍夫线变换提取红线段
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

            // 5. 聚类合并相近的线段
            val clusteredVectors = clusterMarchLines(rawVectors)

            if (clusteredVectors.isEmpty() && !isEdgeAlert) {
                return RaidReport(
                    hasThreat = false,
                    threatLevel = ThreatLevel.NONE,
                    isScreenEdgeAlert = false,
                    detectedVectors = emptyList(),
                    enemyOriginPoint = null,
                    playerTargetPoint = null,
                    timestampMs = now
                )
            }

            // 6. 源头要塞回溯分析 (决策 C 关键支撑)
            // 设定基准己方点：若未传则默认为画面中心 (720p: 虚拟宽/2, 360)
            val anchor = baseAnchor ?: PointF(frame.width / 2f, frame.height / 2f)
            val (enemyOrigin, playerTarget) = traceAttackOriginAndTarget(clusteredVectors, anchor)

            val threatLevel = if (isEdgeAlert || clusteredVectors.any { it.length > 150f }) {
                ThreatLevel.CRITICAL
            } else {
                ThreatLevel.WARNING
            }

            Log.w(TAG, "【敌袭雷达告警】威胁等级: $threatLevel, 边缘红闪: $isEdgeAlert, 红线条数: ${clusteredVectors.size}, 敌方源头: $enemyOrigin")

            return RaidReport(
                hasThreat = true,
                threatLevel = threatLevel,
                isScreenEdgeAlert = isEdgeAlert,
                detectedVectors = clusteredVectors,
                enemyOriginPoint = enemyOrigin,
                playerTargetPoint = playerTarget,
                timestampMs = now
            )

        } catch (e: Exception) {
            Log.e(TAG, "敌袭雷达图像处理异常: ${e.message}", e)
            return RaidReport(false, ThreatLevel.NONE, false, emptyList(), null, null, now)
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
