package com.stzb.assistant.service

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 模板匹配定位器（纯 Bitmap 运算，**不依赖 OCR、不依赖 ncnn/OpenCV**）。
 *
 * ## 为什么需要它
 * 本项目的按键定位此前**只有 OCR 一条路**：读屏上的文字 → 匹配关键词 → 取文字框中心。
 * 而默认构建里 native OCR 是空桩，于是这条路整条失效——
 * 表现就是"点不到按键""点击不准"，且没有任何替代方案。
 *
 * 游戏的按键是**固定美术**。对固定美术做模板匹配不需要训练、不需要模型、
 * 不需要网络，而且在 OCR 完全不可用时**照样能工作**。
 * 这两条路是互补的：OCR 能适应改文案，模板匹配能适应"读不出字"。
 *
 * ## 算法
 * 1. 都转成灰度并按整数 [scale] 降采样（成本降 [scale]²，对固定美术足够）；
 * 2. 在搜索区域内滑动模板，算 **零均值归一化互相关（ZNCC）**：
 *    它对整体亮度/对比度的线性变化不敏感，比直接比像素差稳；
 * 3. 取最高分，并同时记录"与之不重叠的次高分"。
 *
 * ## 为什么还要看次高分
 * 只看最高分是不够的：游戏里常有重复美术（多个相同图标、多个"确定"）。
 * 若最高分与次高分接近，说明这个模板**不具区分度**，此时贸然点击可能点错目标。
 * 因此 [Match.distinctiveness] 是一个独立判据——宁可拒绝，也不要猜。
 */
object TemplateMatcher {

    /**
     * 匹配结果。
     *
     * @param score           最佳 ZNCC 分数，范围约 [-1, 1]
     * @param runnerUp        与最佳位置**不重叠**的次高分（用于判断区分度）
     * @param searchSamples   参与比较的位置数（便于判断"根本没搜到"还是"搜到了但不像"）
     */
    data class Match(
        val centerX: Int,
        val centerY: Int,
        val score: Double,
        val runnerUp: Double,
        val searchSamples: Int
    ) {
        /** 与次高分的差距：越大越"独一无二"。 */
        val distinctiveness: Double get() = score - runnerUp

        fun describe(): String =
            "匹配 (${centerX}, ${centerY}) 分数=${"%.3f".format(score)} " +
                "次高=${"%.3f".format(runnerUp)} 区分度=${"%.3f".format(distinctiveness)} " +
                "搜索位置数=$searchSamples"
    }

    /** 灰度 + 降采样后的紧凑图像。 */
    class Gray(val w: Int, val h: Int, val scale: Int, val data: DoubleArray) {
        fun at(x: Int, y: Int): Double = data[y * w + x]
    }

    /**
     * 转灰度并降采样。降采样用块平均（比抽点抗噪）。
     *
     * @param scale 降采样倍数，至少 1
     */
    fun toGray(bitmap: Bitmap, scale: Int = 4): Gray {
        val s = max(1, scale)
        val w = max(1, bitmap.width / s)
        val h = max(1, bitmap.height / s)
        val out = DoubleArray(w * h)

        // 逐像素读取会很慢，先整幅取出像素数组
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (gy in 0 until h) {
            for (gx in 0 until w) {
                var sum = 0.0
                var n = 0
                val y0 = gy * s
                val x0 = gx * s
                for (dy in 0 until s) {
                    val y = y0 + dy
                    if (y >= bitmap.height) break
                    val row = y * bitmap.width
                    for (dx in 0 until s) {
                        val x = x0 + dx
                        if (x >= bitmap.width) break
                        val p = pixels[row + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        // Rec.601 亮度权重
                        sum += 0.299 * r + 0.587 * g + 0.114 * b
                        n++
                    }
                }
                out[gy * w + gx] = if (n == 0) 0.0 else sum / n
            }
        }
        return Gray(w, h, s, out)
    }

    /**
     * 在一帧里找按键模板。
     *
     * @param region 只在该区域内搜索（设计画布坐标，可显著降成本并提高区分度）；
     *               为 null 时全图搜索
     * @param threshold 最低 ZNCC 分数
     * @param minDistinctiveness 最低区分度；低于它视为"这个模板不具区分度"，返回 null
     * @return 命中时返回**设计画布坐标**下的中心点；不命中返回 null
     */
    fun search(
        frame: Bitmap,
        template: Bitmap,
        region: Rect? = null,
        scale: Int = 4,
        threshold: Double = 0.85,
        minDistinctiveness: Double = 0.04
    ): Match? {
        if (frame.width <= 0 || frame.height <= 0) return null
        if (template.width <= 0 || template.height <= 0) return null
        if (template.width > frame.width || template.height > frame.height) return null

        val s = max(1, scale)
        // 模板太小的话降采样会把它压没，此时退回 scale=1
        val useScale = if (template.width / s < 4 || template.height / s < 4) 1 else s

        val hay = toGray(frame, useScale)
        val nee = toGray(template, useScale)
        if (nee.w >= hay.w || nee.h >= hay.h) return null

        // 搜索边界（换算到降采样坐标）
        val rx0 = ((region?.left ?: 0) / useScale).coerceIn(0, max(0, hay.w - nee.w))
        val ry0 = ((region?.top ?: 0) / useScale).coerceIn(0, max(0, hay.h - nee.h))
        val rx1 = (((region?.right ?: frame.width) / useScale) - nee.w).coerceIn(rx0, hay.w - nee.w)
        val ry1 = (((region?.bottom ?: frame.height) / useScale) - nee.h).coerceIn(ry0, hay.h - nee.h)
        if (rx1 < rx0 || ry1 < ry0) return null

        // 模板的均值与标准差
        val tMean = mean(nee.data)
        val tStd = std(nee.data, tMean)
        if (tStd < 1e-6) return null // 纯色模板没有区分度

        var best = -2.0
        var bestX = -1
        var bestY = -1
        var samples = 0

        for (y in ry0..ry1) {
            for (x in rx0..rx1) {
                val score = zncc(hay, x, y, nee, tMean, tStd)
                samples++
                if (score > best) {
                    best = score
                    bestX = x
                    bestY = y
                }
            }
        }
        if (bestX < 0) return null

        // 次高分：与最佳位置**不重叠**的区域里取最高，避免把同一目标的邻位当竞争者
        var runnerUp = -2.0
        val clearW = nee.w
        val clearH = nee.h
        for (y in ry0..ry1) {
            for (x in rx0..rx1) {
                val overlapX = abs(x - bestX) < clearW
                val overlapY = abs(y - bestY) < clearH
                if (overlapX && overlapY) continue
                val score = zncc(hay, x, y, nee, tMean, tStd)
                if (score > runnerUp) runnerUp = score
            }
        }
        if (runnerUp < -1.0) runnerUp = -1.0

        if (best < threshold) return null
        if (best - runnerUp < minDistinctiveness) return null

        // 换算回设计画布坐标：取模板中心
        val cx = (bestX + nee.w / 2) * useScale
        val cy = (bestY + nee.h / 2) * useScale
        return Match(cx, cy, best, runnerUp, samples)
    }

    // ---------------- 内部数值工具 ----------------

    private fun mean(a: DoubleArray): Double {
        var s = 0.0
        for (v in a) s += v
        return s / a.size
    }

    private fun std(a: DoubleArray, m: Double): Double {
        var s = 0.0
        for (v in a) {
            val d = v - m
            s += d * d
        }
        return sqrt(s / a.size)
    }

    /** 零均值归一化互相关：对整体亮度/对比度的线性变化不敏感。 */
    private fun zncc(
        hay: Gray,
        x0: Int,
        y0: Int,
        nee: Gray,
        tMean: Double,
        tStd: Double
    ): Double {
        var hSum = 0.0
        var hSumSq = 0.0
        var cross = 0.0

        for (y in 0 until nee.h) {
            val hRow = (y0 + y) * hay.w + x0
            val nRow = y * nee.w
            for (x in 0 until nee.w) {
                val hv = hay.data[hRow + x]
                // 模板已零均值化：这里直接用 (t - tMean)
                cross += hv * (nee.data[nRow + x] - tMean)
                hSum += hv
                hSumSq += hv * hv
            }
        }

        val n = (nee.w * nee.h).toDouble()
        val hMean = hSum / n
        // 方差 = E[x²] - E[x]²，夹到 0 以上避免浮点误差出负
        val hVar = max(0.0, hSumSq / n - hMean * hMean)
        val hStd = sqrt(hVar)
        if (hStd < 1e-6) return 0.0

        // cross 里用的是原始 hv，需减去 hMean * Σ(t - tMean) = 0，因此无需修正
        val denom = n * hStd * tStd
        if (denom < 1e-9) return 0.0
        return min(1.0, max(-1.0, cross / denom))
    }
}
