package com.stzb.assistant.antiban

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * 纯数学拟人轨迹规划器 (TrajectoryPlanner)
 *
 * ## 它补的是哪个真实缺陷
 * [KineticTouchEngine.createInertialSwipePath] 已经把**几何**做得像人
 * （三次贝塞尔弧度 + 惯性过冲 + 微回弹），但手势最终交给的是
 * `GestureDescription.StrokeDescription(path, startTime, duration)`——
 * 系统按**弧长均匀、时间线性**采样这条 Path，也就是说：
 *
 * > 轨迹是弯的，但**速度是恒定的**。
 *
 * 真实手指绝非常速：起按加速 → 中段最快 → 入位减速。恒定速度本身就是一条
 * 极强、极易提取的机器特征（对采样点做一阶差分就能看出来）。
 * 仓库里其实早就写了 `BezierTrajectory.easeInOut`，但**没有任何调用者**——
 * 速度剖面一直是死代码。
 *
 * 本类把这条死代码变成真实生效的能力：把整条曲线按**等弧长**切成若干子段，
 * 给每段按 `1/速度剖面` 分配时长，再用**多条连续 StrokeDescription**
 * （`willContinue=true` 链式续笔）派发。对手指按下来说仍是一次连续滑动，
 * 但沿程速度变成了钟形剖面。
 *
 * ## 为什么单独成类、而且不碰 android.graphics.Path
 * `Path` 是框架类，本机（无 JDK/SDK）与单元测试都验证不了它的几何。
 * 本类只处理 `PointF` 与浮点数学，因此能在设备上跑 `selfTest()` 做
 * **闭环自证**（时间覆盖、连续性、落点精度、剖面形状、越界裁剪），
 * 这与 P1 里 [MapZoomController] 坚持「纯数学 + 仿真 selfTest」是同一条纪律。
 */
object TrajectoryPlanner {

    /** 单条手势里最多允许的子段数：系统对 stroke 数量有隐性成本，太多反而失真。 */
    private const val MAX_SEGMENTS = 8
    private const val MIN_SEGMENTS = 3

    /** 小于该距离不做多段规划：几像素的滑动拆成多段反而不像人。 */
    private const val SPLIT_MIN_DISTANCE_PX = 24f

    /** 剖面最低相对速度。设 0 会让首尾段时长爆炸。 */
    private const val SPEED_FLOOR = 0.30f

    /** 每段时长的随机扰动幅度（±12%），让钟形剖面也不重复成固定图案。 */
    private const val SEGMENT_JITTER = 0.12f

    /**
     * 派发边界（物理像素）。过冲点会**越过**终点，靠近屏幕边缘时可能越界，
     * 而坐标越界的手势会被系统整条丢弃（表现为"滑了但没反应"）。
     * 因此规划阶段就必须把它夹回可用区。
     */
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun clampX(x: Float) = x.coerceIn(left, right)
        fun clampY(y: Float) = y.coerceIn(top, bottom)
    }

    /** 一段连续手势：折线点 + 在整条手势中的起始偏移与时长。 */
    data class StrokeSegment(
        val points: List<PointF>,
        val startMs: Long,
        val durationMs: Long
    ) {
        val endMs: Long get() = startMs + durationMs
    }

    /** 三次贝塞尔在参数 t 处的解析点（不依赖框架 PathMeasure）。 */
    fun cubicPoint(p0: PointF, p1: PointF, p2: PointF, p3: PointF, t: Float): PointF {
        val mt = 1f - t
        val a = mt * mt * mt
        val b = 3f * mt * mt * t
        val c = 3f * mt * t * t
        val d = t * t * t
        return PointF(a * p0.x + b * p1.x + c * p2.x + d * p3.x, a * p0.y + b * p1.y + c * p2.y + d * p3.y)
    }

    /** 二次贝塞尔（用于末段微回弹）。 */
    fun quadPoint(p0: PointF, ctrl: PointF, p1: PointF, t: Float): PointF {
        val mt = 1f - t
        return PointF(
            mt * mt * p0.x + 2f * mt * t * ctrl.x + t * t * p1.x,
            mt * mt * p0.y + 2f * mt * t * ctrl.y + t * t * p1.y
        )
    }

    /**
     *  ease-in-out 的**相对速度**剖面（钟形）：首尾慢、中段快。
     *
     * 取二次 in-out `t<0.5 ? 2t² : -1+(4-2t)t` 的导数 `4t(1-t)`，
     * 归一到 [0,1] 后抬一个下限 [SPEED_FLOOR]，避免首尾段时长趋于无穷。
     */
    fun speedProfile(u: Float): Float {
        val x = u.coerceIn(0f, 1f)
        return SPEED_FLOOR + (1f - SPEED_FLOOR) * 4f * x * (1f - x)
    }

    /**
     * 生成一条滑动的**几何折线**（含手臂侧偏弧度、惯性过冲与微回弹）。
     *
     * @return 有序采样点，首点 == start，末点 == end
     */
    fun samplePolyline(
        start: PointF,
        end: PointF,
        bounds: Bounds? = null,
        samplesPerCurve: Int = 24,
        random: Random = Random
    ): List<PointF> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = hypot(dx, dy)

        if (distance < 1.001f) return listOf(PointF(start.x, start.y), PointF(end.x, end.y))

        val unitX = dx / distance
        val unitY = dy / distance
        val normalX = -unitY
        val normalY = unitX

        // 侧偏弧度：距离越长偏得越多，最多 35px，方向随机
        val curvature = minOf(35f, distance * 0.15f) * (if (random.nextBoolean()) 1f else -1f)
        val arc1 = curvature * (0.8f + random.nextFloat() * 0.4f)
        val arc2 = curvature * (0.6f + random.nextFloat() * 0.4f)

        val p0 = PointF(start.x, start.y)
        val p1 = PointF(start.x + dx * 0.32f + normalX * arc1, start.y + dy * 0.32f + normalY * arc1)
        val p2 = PointF(start.x + dx * 0.78f + normalX * arc2, start.y + dy * 0.78f + normalY * arc2)

        // 过冲点：沿前进方向冲出 4%~8%（最多 22px），随后微回弹归位
        val overshoot = minOf(22f, distance * (0.04f + random.nextFloat() * 0.04f))
        var pOver = PointF(end.x + unitX * overshoot, end.y + unitY * overshoot)
        var pEnd = PointF(end.x, end.y)

        // 过冲越界时，先把过冲量按可用空间收缩，而不是让系统丢手势
        if (bounds != null && overshoot > 0f) {
            val bx = bounds.clampX(pOver.x)
            val by = bounds.clampY(pOver.y)
            if (bx != pOver.x || by != pOver.y) {
                val shrink = hypot(bx - end.x, by - end.y)
                val limited = minOf(overshoot, shrink)
                pOver = PointF(end.x + unitX * limited, end.y + unitY * limited)
            }
        }

        val reboundCtrl = PointF(
            pOver.x - unitX * (overshoot * 0.3f) + normalX * (random.nextFloat() * 2f - 1f),
            pOver.y - unitY * (overshoot * 0.3f) + normalY * (random.nextFloat() * 2f - 1f)
        )

        // **关键一步：把所有控制点（含端点）夹进可用区。**
        //
        // 只夹过冲点是不够的：侧偏弧度的控制点同样能把曲线顶到屏外
        // （例如贴底边往上拖、弧度却往下方鼓）。靠事后过滤采样点会
        // 把曲线削出尖角，反而更像机器。
        // 正确做法直接拿贝塞尔的**凸包性质**：曲线恒包在控制点构成的
        // 凸包内，所以控点全在矩形里 ⇒ 整条曲线必在矩形里，无需逐点裁剪。
        fun inside(p: PointF): PointF =
            if (bounds == null) p
            else PointF(bounds.clampX(p.x), bounds.clampY(p.y))

        val c0 = inside(p0)
        val c1 = inside(p1)
        val c2 = inside(p2)
        val cOver = inside(pOver)
        val cEnd = inside(pEnd)
        val cRebound = inside(reboundCtrl)

        val pts = ArrayList<PointF>(samplesPerCurve * 2 + 2)
        pts.add(c0)
        for (i in 1..samplesPerCurve) {
            pts.add(cubicPoint(c0, c1, c2, cOver, i.toFloat() / samplesPerCurve))
        }
        for (i in 1..samplesPerCurve) {
            pts.add(quadPoint(cOver, cRebound, cEnd, i.toFloat() / samplesPerCurve))
        }

        // 末点严格等于请求终点：贝塞尔在 t=1 处解析上就是 pEnd，
        // 这里再显式覆盖一次，杜绝浮点末位误差累积成"差半像素点不中"。
        pts[pts.size - 1] = pEnd
        return pts
    }

    /** 折线的累计弧长表。返回与 pts 等长的数组，[0]=0。 */
    fun cumulativeLength(pts: List<PointF>): FloatArray {
        val acc = FloatArray(pts.size)
        if (pts.size < 2) return acc
        var sum = 0f
        for (i in 1 until pts.size) {
            sum += hypot((pts[i].x - pts[i - 1].x).toDouble(), (pts[i].y - pts[i - 1].y).toDouble()).toFloat()
            acc[i] = sum
        }
        return acc
    }

    /**
     * 按**弧长**均匀重采样。次数 `count` 含首尾。
     *
     * 为何必须重采样：[samplePolyline] 是按贝塞尔**参数 t** 均匀的，
     * 而参数均匀 ≠ 弧长均匀（曲线弯的地方采样密、直的地方采样稀）。
     * 直接按点数分段，会让各段几何长度不相等，于是「时长差异」里混进了
     * 「路程差异」——速度剖面就不再是纯粹的形状了。
     */
    fun resampleByArcLength(pts: List<PointF>, acc: FloatArray, total: Float, count: Int): List<PointF> {
        val n = count.coerceAtLeast(2)
        if (pts.size < 2 || total <= 0.0001f) {
            val last = pts.last()
            return List(n) { PointF(last.x, last.y) }
        }
        val out = ArrayList<PointF>(n)
        var j = 0
        for (i in 0 until n) {
            val target = total * i / (n - 1)
            // 上界取 `acc.size - 1` 是为了让 `acc[j + 1]` 始终合法；j 最大停在
            // size-2，正好能用到最后一个弧长区间（写成 `<= size-2` 会越界）。
            while (j + 1 < acc.size - 1 && acc[j + 1] < target) j++
            val l0 = acc[j]
            val l1 = acc[j + 1]
            val f = if (l1 - l0 > 1e-6f) ((target - l0) / (l1 - l0)).coerceIn(0f, 1f) else 0f
            val a = pts[j]
            val b = pts[j + 1]
            out.add(PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f))
        }
        // 末点严格取原折线终点，把浮点末位误差压回 0
        out[out.size - 1] = PointF(pts.last().x, pts.last().y)
        return out
    }

    /**
     * 把折线按**等弧长**切成 k 段，并按速度剖面分配时长。
     *
     * @param totalMs 整条手势的总时长；返回值严格覆盖到该值（含舍入余数回填），
     *                这样调用方的时间预算依然成立，不会因子段划分而漂移。
     */
    fun plan(
        start: PointF,
        end: PointF,
        totalMs: Long,
        bounds: Bounds? = null,
        segments: Int = 0,
        pointsPerSegment: Int = 6,
        random: Random = Random
    ): List<StrokeSegment> {
        val distance = hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).toFloat()
        if (totalMs <= 0L) {
            return listOf(StrokeSegment(listOf(PointF(start.x, start.y), PointF(end.x, end.y)), 0L, 1L))
        }
        if (distance < SPLIT_MIN_DISTANCE_PX) {
            // 短距滑动：单段、常速即可——把 5px 的位移拆成 6 段反而失真
            return listOf(StrokeSegment(samplePolyline(start, end, bounds, 8, random), 0L, totalMs))
        }

        val pts = samplePolyline(start, end, bounds, 24, random)
        val acc = cumulativeLength(pts)
        val total = acc[acc.size - 1]
        if (total <= 0.0001f) {
            return listOf(StrokeSegment(listOf(PointF(start.x, start.y), PointF(end.x, end.y)), 0L, totalMs))
        }

        val k = (if (segments > 0) segments else (3 + (distance / 120f).toInt()))
            .coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)
            // 预算比段数还短时，归一化后会出现“每段至少 1ms 但总和超预算”的无解，
            // 此处直接根据预算削段，保证下面“每段 ≥1ms 且总时长严格等于预算”可同时成立。
            .coerceAtMost(totalMs.toInt().coerceAtLeast(1))
        val m = pointsPerSegment.coerceAtLeast(2)

        // 1) 等弧长重采样，再按点数切桶 ⇒ 各段几何长度严格相等，
        //    且相邻段共享边界点（续笔时不会"手指瞬移"）
        val uniform = resampleByArcLength(pts, acc, total, k * m + 1)
        val buckets = ArrayList<List<PointF>>(k)
        for (i in 0 until k) {
            buckets.add(uniform.subList(i * m, i * m + m + 1).map { PointF(it.x, it.y) })
        }
        // 各段**实际要走的弧长**。注意不能用"首末点直线距离"：末段处在
        // 过冲回弹区，弦长会远小于弧长（实测差近一倍），于是回弹段被分到
        // 一半的时间——速度剖面在尾部凭空翻上去，钟形直接破掉。
        val uniAcc = cumulativeLength(uniform)

        // 2) 每段时长 ∝ 段弧长 / 段中点的相对速度
        val raw = FloatArray(k)
        for (i in 0 until k) {
            val u = (i + 0.5f) / k
            val arc = uniAcc[(i + 1) * m] - uniAcc[i * m]
            val speed = speedProfile(u)
            raw[i] = (maxOf(arc, 0.5f) / speed) * (1f + (random.nextFloat() * 2f - 1f) * SEGMENT_JITTER)
        }
        var rawSum = 0f
        for (v in raw) rawSum += v
        if (rawSum <= 0f) rawSum = 1f

        // 3) 归一到 totalMs，并把舍入余数回填到最后一段 ⇒ 覆盖严格等于预算
        val durations = LongArray(k) { (raw[it] / rawSum * totalMs).toLong().coerceAtLeast(1L) }
        var used = 0L
        for (d in durations) used += d
        durations[k - 1] += (totalMs - used)
        if (durations[k - 1] < 1L) durations[k - 1] = 1L

        val out = ArrayList<StrokeSegment>(k)
        var cursor = 0L
        for (i in 0 until k) {
            out.add(StrokeSegment(buckets[i], cursor, durations[i]))
            cursor += durations[i]
        }
        return out
    }

    /**
     * 运行期自检：不依赖真机截屏、不依赖 OCR，纯数学闭环。
     *
     * 检查的是"时间必须连续覆盖预算""落点必须严格命中""剖面必须真的是钟形"
     * 这三类一旦破口就静默失效的性质。
     *
     * @return 失败原因列表；为空表示通过
     */
    fun selfTest(): List<String> {
        val problems = mutableListOf<String>()
        val rng = Random(20261006) // 固定种子：自检结果可复现

        val bounds = Bounds(0f, 0f, 1280f, 720f)
        val cases = listOf(
            Pair(PointF(640f, 450f), PointF(640f, 150f)) to 520L,   // 竖直长拖（地图）
            Pair(PointF(200f, 360f), PointF(1000f, 360f)) to 640L,  // 水平长拖
            Pair(PointF(1200f, 700f), PointF(1270f, 715f)) to 300L, // 贴边角短拖（考验越界裁剪）
            Pair(PointF(300f, 300f), PointF(306f, 303f)) to 180L    // 超短距（应退化成单段）
        )

        for (((s, e), budget) in cases) {
            val plan = plan(s, e, budget, bounds, random = rng)
            val tag = "用例(${s.x.toInt()},${s.y.toInt()})->(${e.x.toInt()},${e.y.toInt()})"

            // 时间必须无缝覆盖到预算
            if (plan.first().startMs != 0L) problems += "$tag 首段未从 0 开始: ${plan.first().startMs}"
            for (i in 1 until plan.size) {
                if (plan[i].startMs != plan[i - 1].endMs) {
                    problems += "$tag 第 $i 段与上一段之间有时间缝隙/重叠: ${plan[i].startMs} vs ${plan[i - 1].endMs}"
                }
            }
            val covered = plan.last().endMs
            if (abs(covered - budget) > 1L) {
                problems += "$tag 总时长未覆盖预算: 实际 ${covered}ms, 应为 ${budget}ms"
            }
            if (plan.any { it.durationMs < 1L }) problems += "$tag 出现非正时长段"

            // 几何：每段至少两点；段间共享端点；末点严格命中
            for (seg in plan) {
                if (seg.points.size < 2) { problems += "$tag 存在点数 <2 的段（手势会被系统判非法）"; break }
                for (p in seg.points) if (!p.x.isFinite() || !p.y.isFinite()) { problems += "$tag 出现非有限坐标"; break }
            }
            val last = plan.last().points.last()
            if (hypot((last.x - e.x).toDouble(), (last.y - e.y).toDouble()) > 0.6f) {
                problems += "$tag 落点偏移过大: 距终点 ${"%.2f".format(hypot((last.x - e.x).toDouble(), (last.y - e.y).toDouble()))}px"
            }
            for (i in 1 until plan.size) {
                val prevEnd = plan[i - 1].points.last()
                val curStart = plan[i].points.first()
                if (hypot((prevEnd.x - curStart.x).toDouble(), (prevEnd.y - curStart.y).toDouble()) > 0.6f) {
                    problems += "$tag 第 $i 段与上一段端点不连续（会出现手指瞬移）"
                }
            }

            // 边界：所有点必须在可用区内（越界会被系统整条丢弃）
            for (seg in plan) for (p in seg.points) {
                if (p.x < bounds.left - 0.5f || p.x > bounds.right + 0.5f ||
                    p.y < bounds.top - 0.5f || p.y > bounds.bottom + 0.5f
                ) {
                    problems += "$tag 采样点越界: (${p.x}, ${p.y})，手势可能被系统丢弃"
                    break
                }
            }
        }

        // 剖面：长滑必须"首尾慢、中段快"，否则说明 easeInOut 根本没生效
        run {
            val plan = plan(PointF(640f, 600f), PointF(640f, 120f), 600L, Bounds(0f, 0f, 1280f, 720f), segments = 6, random = Random(7))
            if (plan.size < 5) {
                problems += "长滑未做多段划分: 段数=${plan.size}"
            } else {
                val d0 = plan.first().durationMs
                val dMid = plan[plan.size / 2].durationMs
                val dLast = plan.last().durationMs
                if (!(dMid < d0 && dMid < dLast)) {
                    problems += "速度剖面非钟形: 首=$d0ms 中=$dMidms 尾=$dLastms（中段应明显更快）"
                }
                val arcs = plan.map { seg ->
                    val a = cumulativeLength(seg.points)
                    a[a.size - 1]
                }
                // 末段处于过冲回弹区。它曾经比中段还快，原因就是
                // 用弦长冒充弧长——这里直接卡住量级，不让它退回旧缺陷。
                if (dLast * 4L < dMid * 3L) {
                    problems += "末段相对中段过快: 中=${dMid}ms 尾=${dLast}ms，弧长口径可能被弦长取代"
                }

                // 沿程速度才是真正该钟形的量：v_i = 渲染弧长_i / 时长_i。
                // 时长按弧长分配，所以 v_i 应严格回到速度剖面本身。
                val speeds = plan.map { seg ->
                    val a = cumulativeLength(seg.points)
                    (a[a.size - 1] / seg.durationMs.coerceAtLeast(1L))
                }
                val sMid = speeds[speeds.size / 2]
                val sFirst = speeds.first()
                val sLast = speeds.last()
                if (sMid < sFirst * 1.15f || sMid < sLast * 1.15f) {
                    problems += "沿程速度非钟形: 首=${"%.3f".format(sFirst)} 中=${"%.3f".format(sMid)} 尾=${"%.3f".format(sLast)} px/ms"
                }

                // 各段渲染弧长不得塌陷：重采样出问题（如卡在某一段）时，
                // 会有段的弧长远小于均值，而坐标看上去仍然完全合理。
                val meanArc = (arcs.sum() / arcs.size).coerceAtLeast(0.001f)
                for ((idx, arc) in arcs.withIndex()) {
                    if (arc < meanArc * 0.35f) {
                        problems += "第 $idx 段渲染弧长塌陷: ${"%.2f".format(arc)}px vs 均值 ${"%.2f".format(meanArc)}px"
                    }
                }
            }
        }

        // 短距退化路径：不应被拆成多段
        run {
            val plan = plan(PointF(300f, 300f), PointF(305f, 302f), 200L, Bounds(0f, 0f, 1280f, 720f), random = Random(9))
            if (plan.size != 1) problems += "超短距滑动被拆成 ${plan.size} 段，应退化为单段"
        }

        return problems
    }
}
