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
 * 3. 取最高分，并同时记录"与之不重叠的次高分"；
 * 4. 分数不够高时做**亚格相位精修**（见 [refinePhase]）——这一步不是可选的优化，
 *    缺了它模板会"随机失效"，理由见下。
 *
 * ## 为什么还要看次高分
 * 只看最高分是不够的：游戏里常有重复美术（多个相同图标、多个"确定"）。
 * 若最高分与次高分接近，说明这个模板**不具区分度**，此时贸然点击可能点错目标。
 * 因此 [Match.distinctiveness] 是一个独立判据——宁可拒绝，也不要猜。
 *
 * ## 亚格精修：真机截图量出来的问题（不是推测）
 * [toGray] 是从**各自 Bitmap 的 (0,0)** 起做 scale×scale 块平均，所以整帧的块网格
 * 与"事先从别处裁出来的一张模板 PNG"的块网格之间，存在 0..scale-1 的**随机相位差**。
 * 相位没对齐时，同一份美术在降采样后彼此错位，ZNCC 会凭空掉一截。
 *
 * 用 21 张 2712x1220 真机截图实测（`tools/validate_template_matcher.py`，
 * 模板 168x80、scale=4）：
 *   - 模板边界对齐块网格：分数 **1.0000**，位置偏差 0px；
 *   - 模板边界只偏移 (1,2)px：**0.8646 / 0.9293 / 0.9495**（三张不同界面），
 *     离 threshold=0.85 最坏只剩 0.0146 的余量；
 *   - 而跨界面的负例最高分是 0.6674 —— 也就是说**不能靠调低阈值来容忍相位损失**，
 *     降阈值会同时放过负例。
 *
 * 所以在降采样最佳位置的邻域内，按**原分辨率**把 (2·scale-1)² 个偏移都复算一遍 ZNCC，
 * 取最高的那个。成本实测是粗搜的 3~4%（粗搜 23408 个位置，精修 49 个）。
 * 精修后同一组用例回到 **1.0000**、位置偏差 0px。
 *
 * 注：[runnerUp] 仍来自未经精修的降采样网格。这是有意的——次高分是"另一个位置"的分数，
 * 对它们逐个大范围精修会让成本失控；而相位损失对最佳位与次高位是同向的，
 * 区分度判据不会被精修放宽。副作用是区分度略偏乐观，真机上若出现误命中，
 * 应优先调 [minDistinctiveness] 而不是去掉精修。
 *
 * 另一条通道 [com.stzb.assistant.ocr.OpenCvMatcher]（TM_CCOEFF_NORMED）在全分辨率上滑窗，
 * 不存在相位问题，因此不需要这套精修；本类是 OpenCV 不可用时的兜底实现。
 */
object TemplateMatcher {

    /**
     * 匹配结果。
     *
     * @param score           最佳 ZNCC 分数，范围约 [-1, 1]
     * @param runnerUp        与最佳位置**不重叠**的次高分（用于判断区分度）
     * @param searchSamples   参与比较的位置数（便于判断"根本没搜到"还是"搜到了但不像"）
     * @param refined         分数是否经过亚格相位精修（见 [refinePhase]）。
     *                        真机标定时很有用：`refined=true` 说明这张模板的裁切相位
     *                        与画面不对齐，是靠精修才命中的——把它重裁成对齐的会更稳更快。
     */
    data class Match(
        val centerX: Int,
        val centerY: Int,
        val score: Double,
        val runnerUp: Double,
        val searchSamples: Int,
        val refined: Boolean = false
    ) {
        /** 与次高分的差距：越大越"独一无二"。 */
        val distinctiveness: Double get() = score - runnerUp

        fun describe(): String =
            "匹配 (${centerX}, ${centerY}) 分数=${"%.3f".format(score)} " +
                "次高=${"%.3f".format(runnerUp)} 区分度=${"%.3f".format(distinctiveness)} " +
                "搜索位置数=$searchSamples" +
                (if (refined) " [亚格精修]" else "")
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
                        sum += luma(pixels[row + x])
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
     * @param refineBelow 降采样分数低于它时才做亚格相位精修（见 [refinePhase]）。
     *                    对齐相位的真命中本来就接近 1.0，不必为它们付额外代价；
     *                    而相位损失恰好只体现在分数偏低的时候。
     * @return 命中时返回**设计画布坐标**下的中心点；不命中返回 null
     */
    fun search(
        frame: Bitmap,
        template: Bitmap,
        region: Rect? = null,
        scale: Int = 4,
        threshold: Double = 0.85,
        minDistinctiveness: Double = 0.04,
        refineBelow: Double = 0.95
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

        // ---- 亚格相位精修 ----
        // 降采样网格上的最佳位置可能因为"模板裁切相位与帧不一致"而分数偏低，
        // 在它四周的 (2·useScale-1)² 个原分辨率偏移里复算一次，取最高的。
        var bestScore = best
        var subX = 0
        var subY = 0
        var refined = false
        if (best < refineBelow && useScale > 1) {
            val hit = refinePhase(frame, template, bestX, bestY, nee.w, nee.h, useScale)
            if (hit != null && hit.score > bestScore) {
                bestScore = hit.score
                subX = hit.dx
                subY = hit.dy
                refined = true
            }
        }

        if (bestScore < threshold) return null
        if (bestScore - runnerUp < minDistinctiveness) return null

        // 换算回设计画布坐标：取模板中心。
        // 精修成功时左上角是"降采样最佳格 × scale + 亚格偏移"，中心必须按**原分辨率**算，
        // 否则会把这个偏移又量化回 4px 网格，白精修一次。
        val cx = if (refined) bestX * useScale + subX + nee.w * useScale / 2
                 else (bestX + nee.w / 2) * useScale
        val cy = if (refined) bestY * useScale + subY + nee.h * useScale / 2
                 else (bestY + nee.h / 2) * useScale
        return Match(cx, cy, bestScore, runnerUp, samples, refined)
    }

    // ---------------- 亚格相位精修 ----------------

    /** [refinePhase] 的返回值：相对降采样最佳格的偏移，与按原分辨率复算的分数。 */
    class Refine(val dx: Int, val dy: Int, val score: Double)

    /**
     * 在降采样最佳格 [bestX],[bestY] 的邻域内，按**原分辨率**做亚格相位精修。
     *
     * 把 dx,dy 从 -(scale-1) 试到 (scale-1)，即把模板在整帧块网格上的所有相位都试一遍。
     *
     * @param blocksW 模板在降采样图里的宽度（格数）
     * @param blocksH 模板在降采样图里的高度（格数）
     * @return 精修结果；越界/模板无信息量时返回 null
     */
    fun refinePhase(
        frame: Bitmap,
        template: Bitmap,
        bestX: Int,
        bestY: Int,
        blocksW: Int,
        blocksH: Int,
        scale: Int
    ): Refine? {
        val s = max(1, scale)
        if (s == 1) return null
        val tw = blocksW * s
        val th = blocksH * s
        if (tw < 4 || th < 4) return null
        if (tw > frame.width || th > frame.height) return null
        // 只用模板左上角 tw×th 这一块：降采样时被整除截断掉的余边本来就不参与匹配
        if (tw > template.width || th > template.height) return null

        // 模板的零均值灰度图（原分辨率）
        val tplPx = IntArray(tw * th)
        template.getPixels(tplPx, 0, tw, 0, 0, tw, th)
        val t = DoubleArray(tw * th)
        var tSum = 0.0
        var tSumSq = 0.0
        for (i in t.indices) {
            val v = luma(tplPx[i])
            t[i] = v
            tSum += v
            tSumSq += v * v
        }
        val n = t.size.toDouble()
        val tMean = tSum / n
        val tStd = sqrt(max(0.0, tSumSq / n - tMean * tMean))
        if (tStd < 1e-6) return null // 纯色模板：与 toGray 路径同一口径
        for (i in t.indices) t[i] -= tMean

        // 帧只取**候选邻域**，不是整帧：(2s-1)² 个相位需要的像素范围就这么大。
        // 整帧走 getPixels + 双精度转换，在 2712x1220 上要同时握住
        // IntArray 13MB 与 DoubleArray 26MB（约 40MB），而这只是兜底通道里的一次精修；
        // 邻域版按真机模板 168x80、scale=4 估算只有 (174x86)·(4+8)B ≈ 0.2MB。
        val off = s - 1
        val x0 = bestX * s
        val y0 = bestY * s
        val left = max(0, x0 - off)
        val top = max(0, y0 - off)
        val right = min(frame.width, x0 + tw + off)
        val bottom = min(frame.height, y0 + th + off)
        val nw = right - left
        val nh = bottom - top
        if (nw < tw || nh < th) return null

        val win = IntArray(nw * nh)
        frame.getPixels(win, 0, nw, left, top, nw, nh)
        val src = DoubleArray(nw * nh)
        for (i in src.indices) src[i] = luma(win[i])

        val baseX = x0 - left
        val baseY = y0 - top
        var best = -2.0
        var bestDx = 0
        var bestDy = 0
        for (dy in -off until s) {
            val ry = baseY + dy
            if (ry < 0 || ry + th > nh) continue
            for (dx in -off until s) {
                val rx = baseX + dx
                if (rx < 0 || rx + tw > nw) continue
                val v = znccAt(src, nw, rx, ry, t, tw, th, tStd)
                if (v > best) {
                    best = v
                    bestDx = dx
                    bestDy = dy
                }
            }
        }
        if (best < -1.0) return null
        return Refine(bestDx, bestDy, best)
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

    /**
     * [zncc] 的原分辨率版本：数据来自 [refinePhase] 取好的紧凑灰度邻域。
     *
     * @param src    邻域灰度（行主序，长度 stride*nrows）
     * @param stride 邻域每行的像素数（= 邻域宽度）
     * @param tZero  已零均值化的模板灰度
     */
    private fun znccAt(
        src: DoubleArray,
        stride: Int,
        x: Int,
        y: Int,
        tZero: DoubleArray,
        tw: Int,
        th: Int,
        tStd: Double
    ): Double {
        var hSum = 0.0
        var hSumSq = 0.0
        var cross = 0.0
        for (row in 0 until th) {
            val sRow = (y + row) * stride + x
            val tRow = row * tw
            for (col in 0 until tw) {
                val hv = src[sRow + col]
                cross += hv * tZero[tRow + col]
                hSum += hv
                hSumSq += hv * hv
            }
        }
        val n = (tw * th).toDouble()
        val hMean = hSum / n
        val hStd = sqrt(max(0.0, hSumSq / n - hMean * hMean))
        if (hStd < 1e-6) return 0.0
        val denom = n * hStd * tStd
        if (denom < 1e-9) return 0.0
        return min(1.0, max(-1.0, cross / denom))
    }

    /** Rec.601 亮度（与 [toGray] 同一套权重，两条路径的灰度口径必须一致）。 */
    private fun luma(p: Int): Double {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
