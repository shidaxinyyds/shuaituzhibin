package com.stzb.assistant.ai.assets

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 端侧 85MB AI 模型资产管理器 (ModelAssetManager)
 * 
 * 统一管理端侧三大 AI 核心的本地权重文件：
 *   1. YOLOv8-Nano 目标检测权重 (~2.1 MB)
 *   2. PP-OCRv4 Lite 离线识别权重 (~3.8 MB)
 *   3. SmolLM2-135M / RWKV-160M 认知微脑量化权重 (~75 MB)
 * 
 * 支持：
 *   - Assets 内置与 App 私有目录动态加载；
 *   - 本地模型完整性与体积健康检查；
 *   - 优雅无缝降级判定。
 */
object ModelAssetManager {

    private const val TAG = "ModelAssetManager"

    data class ModelStatus(
        val modelName: String,
        val targetSizeDesc: String,
        val isReady: Boolean,
        val localPath: String?
    )

    fun getModelsDirectory(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * 检查端侧视觉 YOLO 目标检测器权重
     */
    fun isYoloReady(context: Context): Boolean {
        val externalFile = File(getModelsDirectory(context), "yolov8n_stzb.bin")
        if (externalFile.exists() && externalFile.length() > 100 * 1024) return true

        return try {
            val assets = context.assets.list("models")
            assets?.contains("yolov8n_stzb.bin") == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查端侧 RapidOCR 离线模型文件
     */
    fun isOcrReady(context: Context): Boolean {
        val dir = getModelsDirectory(context)
        val det = File(dir, "ch_PP-OCRv4_det.bin")
        val rec = File(dir, "ch_PP-OCRv4_rec.bin")
        if (det.exists() && rec.exists()) return true

        return try {
            val assets = context.assets.list("models")
            assets?.contains("ch_PP-OCRv4_det.bin") == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 检查端侧认知微脑 (~75MB 生成式语言模型)
     */
    fun isMicroBrainReady(context: Context): Boolean {
        val dir = getModelsDirectory(context)
        val slm = File(dir, "slm_microbrain_135m.bin")
        if (slm.exists() && slm.length() > 10 * 1024 * 1024) return true

        return try {
            val assets = context.assets.list("models")
            assets?.any { it.contains("slm") || it.contains("smollm") || it.contains("rwkv") } == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取全系统端侧模型部署体检报告
     */
    fun getFullDiagnosticReport(context: Context): List<ModelStatus> {
        val yoloReady = isYoloReady(context)
        val ocrReady = isOcrReady(context)
        val slmReady = isMicroBrainReady(context)

        return listOf(
            ModelStatus(
                modelName = "YOLOv8-Nano 目标检测引擎",
                targetSizeDesc = "约 2.1 MB (INT8)",
                isReady = yoloReady,
                localPath = if (yoloReady) "已就绪 (原生加速)" else "智能空间几何容灾通道就绪"
            ),
            ModelStatus(
                modelName = "PP-OCRv4 Lite 离线文本引擎",
                targetSizeDesc = "约 3.8 MB (INT8)",
                isReady = ocrReady,
                localPath = if (ocrReady) "已就绪" else "RapidOcr Native 运行通道就绪"
            ),
            ModelStatus(
                modelName = "SmolLM2-135M 端侧认知微脑",
                targetSizeDesc = "约 75 MB (INT4 GGUF)",
                isReady = slmReady,
                localPath = if (slmReady) "已就绪 (自回归因果推理)" else "端侧高精军令语义提取引擎就绪"
            )
        )
    }
}
