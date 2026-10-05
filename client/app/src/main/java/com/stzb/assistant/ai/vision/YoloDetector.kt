package com.stzb.assistant.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 移动端目标检测引擎 (YOLO 双通道)
 *
 * 两条通道，能力与真相严格对齐：
 *   1. **主通道（真推理）**：当 `assets/models/` 下存在**针对率土训练并导出为 ncnn**
 *      的成对权重（`*.param` + `*.bin`）、且本包在构建期链接了 ncnn+OpenCV 时，
 *      经 [YoloNative] 走真实 ncnn 推理（见 native `yolo/YoloNcnn.cpp`）。
 *   2. **容灾通道（几何+色度）**：权重缺失、或本包是 OCR 空桩构建（无 ncnn）时，
 *      自动回退到不依赖模型的空间色度/几何显著性检测，保证不崩、且**如实标注这是几何通道**。
 *
 * ⚠️ 诚实声明：通用 COCO 预训练权重检不出"红地/行军线/要塞"等率土大地图专属类别，
 *    因此主通道所需的权重**必须自行采集率土截图训练**（见 `tools/train_yolo/`）。
 *    仓库当前不含这些权重，故出厂默认走容灾通道——这不是降级，是现状。
 *
 * 🔌 定位（模型升级阶梯）：本类是可插拔的 **DETECTOR 层**引擎，是否加载由当前游戏的
 *    [VisionPolicy] 决定（见 [com.stzb.assistant.runtime.VisionRuntime]）。SLG/MMO 盘默认
 *    **不含 DETECTOR**，故 `VisionRuntime.yolo()` 会直接返回 null、本类根本不会被构造；
 *    只有实时动作类（如未来的 DNF）才在知识包里显式开启它。下面的 7 类是率土大地图契约，
 *    保留是为了"真要上检测器时按图训练"，不代表率土当前依赖它。
 *
 * 防封拟人点：无论哪条通道，命中框都经高斯抖动映射触控点（[DetectionBox.humanTouchPoint]）。
 */
class YoloDetector(private val context: Context) {

    /**
     * YOLO 检测契约：**只保留大地图上"密集/遮挡/跨缩放"的多目标**（确定性优先原则）。
     *
     * 固定 UI（出征/驻守/撤退/确定/取消按钮）、军令红点、敌袭告警、兵种四分裂
     * 一律**不在此列**——它们由确定性通道处理：
     *   - 按钮 → [com.stzb.assistant.ocr.StzbUiMatcher] 语义 OCR；
     *   - 军令红点 → HSV 角标；敌袭告警 → RaidRadarDetector（HSV+霍夫）；
     *   - 兵种：行军时**只有微缩行军模型、无独立兵种图标**；骑（马上）与步兵可分→建两类，
     *     弓/枪/盾步兵在地图缩放下不可稳定区分→合并为“步兵”，不硬拆四类。
     *
     * id 必须与 tools/train_yolo/data.yaml 严格对齐，错位=检错目标。
     * 诚实边界：这些类**仅由 ncnn 权重检出**；无权重时几何色度容灾通道只勉强给出行军线，
     * 其余如实为空（不臆造）。
     */
    enum class DetectionClass(val id: Int, val label: String, val minAspectRatio: Float, val maxAspectRatio: Float) {
        ENEMY_TILE(0, "敌对红地", 0.8f, 2.5f),
        RESOURCE_TILE(1, "资源地块", 0.8f, 2.5f),
        MARCH_LINE(2, "行军线", 0.1f, 10.0f),        // 敌我由端侧颜色通道判，故不区分红/蓝线
        FORTRESS(3, "要塞关卡", 0.8f, 3.0f),          // 要塞/关隘/分城/城池等大建筑
        CAMP(4, "营寨营地", 0.8f, 2.5f),              // 玩家营帐 / NPC 营地
        TROOP_CAVALRY(5, "骑兵部队", 0.5f, 3.0f),      // 微缩行军模型：骑马，可与步兵区分
        TROOP_INFANTRY(6, "步兵部队", 0.5f, 3.0f);     // 微缩行军模型：弓/枪/盾合并（缩放下不可稳定区分）

        companion object {
            fun fromId(id: Int): DetectionClass = entries.firstOrNull { it.id == id } ?: ENEMY_TILE
        }
    }

    data class DetectionBox(
        val detectionClass: DetectionClass,
        val rect: Rect,
        val confidence: Float
    ) {
        /**
         * 获取携带高斯抖动的真实拟人触控点 (防封风控)
         */
        val humanTouchPoint: PointF
            get() {
                val cx = rect.exactCenterX()
                val cy = rect.exactCenterY()
                val sigmaX = max(2f, rect.width() * 0.12f)
                val sigmaY = max(2f, rect.height() * 0.12f)

                val jRandom = java.util.Random()
                val boundX = rect.width() * 0.35f
                val boundY = rect.height() * 0.35f
                val rawX = (jRandom.nextGaussian() * sigmaX).toFloat()
                val rawY = (jRandom.nextGaussian() * sigmaY).toFloat()
                val offsetX = rawX.coerceIn(-boundX, boundX)
                val offsetY = rawY.coerceIn(-boundY, boundY)

                val targetX: Float = cx + offsetX
                val targetY: Float = cy + offsetY
                return PointF(targetX, targetY)
            }
    }

    private var isNativeModelLoaded = false

    /**
     * ncnn 主通道是否真的就绪。
     *
     * 之前它是 private，外部无从判断"当前跑的是真推理还是几何色度回退"，
     * 于是调用方可能把回退结果当成真实检测结果使用。现在显式暴露，
     * 由调用方（见 [com.stzb.assistant.service.EngineBridge]，运行时的统一视觉门面）
     * 决定要不要采信其结论。注：本类目前作为 Phase C 的可选 ncnn 学习通道预留，
     * 固定 UI 目标的主检测已改走 EngineBridge 下的确定性通道（StzbUiMatcher/TileStatusDetector/RaidRadarDetector/OpenCvMatcher）。
     */
    val isNativeReady: Boolean
        get() = isNativeModelLoaded

    init {
        // 尝试探测本地存储或 assets 中的模型资产
        initModel()
    }

    private fun initModel() {
        // 候选权重基名（ncnn 需要 .param + .bin 成对）。优先 A++ 的 YOLO26，再回 v11/v8。
        val candidates = listOf(
            "yolo26s_stzb",
            "yolo26n_stzb",
            "yolov11s_multiscale_stzb",
            "yolov8s_stzb",
            "yolov8n_stzb"
        )
        try {
            for (base in candidates) {
                val paramPath = com.stzb.assistant.ai.assets.ModelAssetManager
                    .getOrExtractModelPath(context, "$base.param")
                val binPath = com.stzb.assistant.ai.assets.ModelAssetManager
                    .getOrExtractModelPath(context, "$base.bin")
                if (paramPath == null || binPath == null) continue
                if (java.io.File(binPath).length() < 50 * 1024) continue

                // 真·加载：只有 YoloNative.load 返回 true（库可用且 ncnn 载入成功）
                // 才算主通道就绪；否则（空桩构建 / 维度不符）诚实回退几何通道。
                if (YoloNative.load(paramPath, binPath, NATIVE_INPUT_SIZE, 4)) {
                    isNativeModelLoaded = true
                    Log.i(TAG, "YOLO 主通道就绪：ncnn 已加载 $base（真实推理启用）。")
                    return
                } else {
                    Log.w(
                        TAG,
                        "发现 YOLO 权重 $base，但原生推理不可用（本包未链接 ncnn，或权重维度不符）；" +
                            "回退几何色度通道。"
                    )
                    isNativeModelLoaded = false
                    return
                }
            }
            Log.i(TAG, "未发现 YOLO 权重，目标检测走几何色度通道（这是当前唯一实现，非降级）。")
            isNativeModelLoaded = false
        } catch (e: Exception) {
            Log.w(TAG, "初始化 YOLO 检测器异常: ${e.message}")
            isNativeModelLoaded = false
        }
    }

    /**
     * 针对输入屏幕帧进行多目标边界框检测
     */
    fun detect(bitmap: Bitmap, confThreshold: Float = 0.45f, nmsThreshold: Float = 0.5f): List<DetectionBox> {
        val results = mutableListOf<DetectionBox>()

        if (isNativeModelLoaded) {
            // 原生模型推理通道 (当存在 NCNN 动态库和模型时)
            val nativeDetections = runNativeYoloInference(bitmap, confThreshold)
            results.addAll(nativeDetections)
        }

        // 若模型未就绪或未检出关键 UI，启动高精空间几何色度显著性提取器
        if (results.isEmpty()) {
            results.addAll(runSaliencyVisualFallback(bitmap, confThreshold))
        }

        return applyNms(results, nmsThreshold)
    }

    /**
     * 几何色度容灾通道（**无权重时**的唯一兜底）。
     *
     * 契约重锚后，本通道只保留大地图上唯一能靠纯色度可靠提取的目标——行军线
     * （红色敌军线的色度簇）。按钮/红点/兵种等已从 YOLO 契约移除、改走确定性通道，
     * 故此处不再臆造它们的"检测框"。调用方须自行用 [isNativeReady] 判断结论能否作证据
     * （几何结果≠证据，与 NightSentinelFlow / AccurateFarmingFlow 的采信闸门一致）。
     */
    private fun runSaliencyVisualFallback(bitmap: Bitmap, confThreshold: Float): List<DetectionBox> {
        val detected = mutableListOf<DetectionBox>()
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return detected

        // 中央大地图：探测行军线（红色敌军线色度簇）
        val centerRoi = Rect((width * 0.15f).toInt(), (height * 0.15f).toInt(), (width * 0.85f).toInt(), (height * 0.85f).toInt())
        if (hasMarchingRedLine(bitmap, centerRoi)) {
            detected.add(DetectionBox(
                detectionClass = DetectionClass.MARCH_LINE,
                rect = centerRoi,
                confidence = 0.85f
            ))
        }
        return detected
    }

    /**
     * 检测地图上是否有敌军夜袭/行军的直线红光像素
     */
    private fun hasMarchingRedLine(bitmap: Bitmap, roi: Rect): Boolean {
        var redCount = 0
        val step = max(3, min(roi.width(), roi.height()) / 40)
        var total = 0

        for (y in roi.top until roi.bottom step step) {
            for (x in roi.left until roi.right step step) {
                total++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                if (r > 190 && g < 60 && b < 60) {
                    redCount++
                }
            }
        }
        return total > 0 && (redCount.toFloat() / total) >= 0.015f
    }

    /**
     * 真实 ncnn YOLO 推理（经 [YoloNative]）。返回原图像素坐标下的检测框。
     * 未就绪/空桩构建时返回空列表，由 [detect] 回退几何通道。
     */
    private fun runNativeYoloInference(bitmap: Bitmap, confThreshold: Float): List<DetectionBox> {
        val flat = YoloNative.detect(bitmap, confThreshold, NATIVE_NMS_THRESHOLD)
        if (flat.isEmpty()) return emptyList()
        val boxes = mutableListOf<DetectionBox>()
        var i = 0
        while (i + 5 < flat.size) {
            val cls = flat[i].toInt()
            val x1 = flat[i + 1]
            val y1 = flat[i + 2]
            val x2 = flat[i + 3]
            val y2 = flat[i + 4]
            val score = flat[i + 5]
            val left = x1.toInt().coerceIn(0, bitmap.width)
            val top = y1.toInt().coerceIn(0, bitmap.height)
            val right = x2.toInt().coerceIn(0, bitmap.width)
            val bottom = y2.toInt().coerceIn(0, bitmap.height)
            if (right > left && bottom > top) {
                boxes.add(DetectionBox(DetectionClass.fromId(cls), Rect(left, top, right, bottom), score))
            }
            i += 6
        }
        return boxes
    }

    /**
     * 非极大值抑制 (Non-Maximum Suppression, NMS)
     * 过滤重叠冗余边界框
     */
    private fun applyNms(boxes: List<DetectionBox>, iouThreshold: Float): List<DetectionBox> {
        if (boxes.size <= 1) return boxes

        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val selected = mutableListOf<DetectionBox>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIou(best.rect, next.rect) > iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return selected
    }

    private fun calculateIou(a: Rect, b: Rect): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        if (interRight < interLeft || interBottom < interTop) return 0.0f

        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val aArea = a.width() * a.height()
        val bArea = b.width() * b.height()
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0) interArea.toFloat() / unionArea else 0.0f
    }

    companion object {
        private const val TAG = "YoloDetector"
        /** 与训练/导出时的输入尺寸保持一致（ultralytics 默认 640）。 */
        private const val NATIVE_INPUT_SIZE = 640
        private const val NATIVE_NMS_THRESHOLD = 0.45f
    }
}
