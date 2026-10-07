package com.benjaminwan.ocrlibrary

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap

class OcrEngine(context: Context) {
    companion object {
        const val numThread: Int = 4

        // 各 OCR 版本的资产基名（native 侧自行拼 .param/.bin）。
        // 版本容错顺序：v5 → v4 → v3，但**只有当该版本的 det+rec+keys 三者齐备**才选中它，
        // 因为 rec 模型的输出类别数必须与配套词典严格对应，半套(v5 权重 + v3 词典)会解码成乱码。
        // 出厂仓库里只有 v3，故默认路径与改造前完全一致，零行为变化。
        private data class OcrSet(val det: String, val cls: String, val rec: String, val keys: String)

        private const val CLS_V2 = "ch_ppocr_mobile_v2.0_cls_infer"

        private val SETS = listOf(
            OcrSet("ch_PP-OCRv5_det_infer", CLS_V2, "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt"),
            OcrSet("ch_PP-OCRv4_det_infer", CLS_V2, "ch_PP-OCRv4_rec_infer", "ppocr_keys_v4.txt"),
            OcrSet("ch_PP-OCRv3_det_infer", CLS_V2, "ch_PP-OCRv3_rec_infer", "ppocr_keys_v1.txt"),
        )

        private fun assetExists(assets: AssetManager, name: String): Boolean = try {
            assets.openFd(name).use { it.length > 0 }
        } catch (e: Exception) {
            try {
                assets.open(name).use { it.available() > 0 }
            } catch (e2: Exception) {
                false
            }
        }

        /** 选出 assets 里实际齐备的最高版本；都不齐备则回落到 v3（随后 native 会如实初始化失败）。 */
        private fun resolveModelSet(assets: AssetManager): OcrSet {
            for (set in SETS) {
                val ready = assetExists(assets, "${set.det}.param") &&
                    assetExists(assets, "${set.det}.bin") &&
                    assetExists(assets, "${set.rec}.param") &&
                    assetExists(assets, "${set.rec}.bin") &&
                    assetExists(assets, set.keys)
                if (ready) return set
            }
            return SETS.last() // v3
        }
    }

    init {
        try {
            System.loadLibrary("opencv_java4")
        } catch (_: Throwable) {
            // 已由 OpenCVLoader 预载入或平台动态链接接管，忽略异常
        }
        System.loadLibrary("RapidOcr")
        val set = resolveModelSet(context.assets)
        val ret = init(
            context.assets, numThread,
            set.det, set.cls, set.rec, set.keys
        )
        if (!ret) throw IllegalArgumentException("OCR 模型初始化失败：assets 中未找到可用的 PP-OCR 权重（v5/v4/v3）")
    }

    var padding: Int = 50
    var boxScoreThresh: Float = 0.5f
    var boxThresh: Float = 0.3f
    var unClipRatio: Float = 1.6f
    var doAngle: Boolean = true
    var mostAngle: Boolean = true

    fun detect(input: Bitmap, output: Bitmap, maxSideLen: Int) =
        detect(
            input, output, padding, maxSideLen,
            boxScoreThresh, boxThresh,
            unClipRatio, doAngle, mostAngle
        )

    external fun init(
        assetManager: AssetManager,
        numThread: Int, detName: String,
        clsName: String, recName: String, keysName: String
    ): Boolean

    external fun detect(
        input: Bitmap, output: Bitmap, padding: Int, maxSideLen: Int,
        boxScoreThresh: Float, boxThresh: Float,
        unClipRatio: Float, doAngle: Boolean, mostAngle: Boolean
    ): OcrResult

    external fun benchmark(input: Bitmap, loop: Int): Double
}
