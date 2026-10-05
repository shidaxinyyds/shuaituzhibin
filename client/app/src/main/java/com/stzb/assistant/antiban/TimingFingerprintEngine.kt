package com.stzb.assistant.antiban

import android.util.Log
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 行为时间指纹打散引擎 (TimingFingerprintEngine)
 * 
 * 核心痛点解决：
 *   1. 彻底瓦解网易基于“固定时间间隔”与“香农熵平坦度”的大数据外挂检测规则；
 *   2. 采用【外高斯 (Ex-Gaussian) / 泊松长尾分布】算法生成符合人体神经反射学的时间间隔；
 *   3. 模拟【真实作息昼夜节律 (Circadian Rhythm)】：深夜 01:30~06:30 自动降频休眠（除夜袭雷达外）；
 *   4. 模拟【生理疲劳度积累 (Fatigue Modeling)】与【随机生理微歇 (Micro-Breaks)】：
 *      每连续运行 45~80 分钟，随机停顿 2~5 分钟模拟喝水或回复微信，抹杀 24h 挂机机器人指纹。
 */
object TimingFingerprintEngine {

    private const val TAG = "TimingFingerprintEngine"

    // ---- 有界拟人延迟的形状常数（由 tools/validate_antiban_math.py 逐式镜像并在本机实测）----

    /** 形状域的中位数（高斯 0 + 指数中位 0.55·ln2 ≈ 0.381），即 logistic 的零点。 */
    private const val LOGIT_CENTER = 0.381

    /** 形状→logit 的尺度。越小分布越铺开：0.55 时日间铺满区间的 8%~99%。 */
    private const val LOGIT_SCALE = 0.55

    /** 昼夜节律到 logit 偏置的斜率。夜间 (2.5-1)×0.9=1.35，留出头部空间给疲劳。 */
    private const val SKEW_PERIOD = 0.9

    /** 疲劳到 logit 偏置的斜率（疲劳上限 0.4 ⇒ 最多再推 0.24）。 */
    private const val SKEW_FATIGUE = 0.6

    /**
     * logit 偏置的硬夹位。定成 1.6而不是 0.6，是为了让“深夜 + 久跑”
     * 仍在线性区里（否则一 clamp 就跟疲劳同位，两者差异被抹掉）。
     */
    private const val SKEW_CLAMP = 1.6

    enum class CircadianPeriod(val desc: String, val speedFactor: Float) {
        ACTIVE_DAY("日间常态 (07:00 ~ 19:00)", 1.0f),
        PEAK_EVENING("晚间同盟活跃期 (19:00 ~ 01:00)", 0.85f), // 反应更敏捷
        DORMANT_NIGHT("深夜睡眠静默期 (01:00 ~ 07:00)", 2.5f)  // 操作显著放缓
    }

    /**
     * 会话空转超过该间隔，就视为“玩家本来就离开过”：重开会话起点。
     *
     * 疲劳与微歇都必须按**连续操作时长**计，而不是按“进程存活多久”计。
     * 旧写法有个很实的笑话：应用挂了六小时没动，用户一回来点第一下，
     * 疲劳度已经顶到上限 0.4（操作最慢一档），而且紧接着就要“休息 3 分钟”。
     */
    private const val IDLE_RESET_GAP_MS = 10 * 60 * 1000L

    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val lastBreakTime = AtomicLong(System.currentTimeMillis())
    private val lastActionAt = AtomicLong(0L)
    private var nextBreakIntervalMs = generateNextBreakInterval()

    fun resetSession() {
        sessionStartTime.set(System.currentTimeMillis())
        lastBreakTime.set(System.currentTimeMillis())
        lastActionAt.set(System.currentTimeMillis())
        nextBreakIntervalMs = generateNextBreakInterval()
    }

    /**
     * 登记“刚刚发生了一次真实操作”。每个动作延迟前调用一次。
     *
     * 只做一件事：发现两次操作之间空了 [IDLE_RESET_GAP_MS] 以上，就把会话
     * 起点（疲劳计时 + 下一次微歇时点）整体重开。于是：
     *   • 长时间挂机才会真的累出疲劳（而不是“开机就累”）；
     *   • 真正已经闲置过的玩家，回来不会被再强制“休息几分钟”。
     */
    fun noteHumanAction() {
        val now = System.currentTimeMillis()
        val prev = lastActionAt.getAndSet(now)
        if (prev == 0L || now - prev >= IDLE_RESET_GAP_MS) {
            resetSession()
        }
    }

    /**
     * 获取当前真实时间对应的昼夜生理节律
     */
    fun getCircadianPeriod(): CircadianPeriod {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 7..18 -> CircadianPeriod.ACTIVE_DAY
            in 19..23, 0 -> CircadianPeriod.PEAK_EVENING
            else -> CircadianPeriod.DORMANT_NIGHT
        }
    }

    // ==========================================================
    // 有界拟人延迟（修上界失控）
    // ==========================================================

    /**
     * 生成**保证落在 [minMs, maxMs] 内**的拟人延迟。
     *
     * ## 为什么必须加这个方法（这是个真实缺陷，不是锦上添花）
     * 本类早期版本只有一个“均值 + 高斯 + 指数长尾 + 节律×疲劳”的
     * **乘性叠加**函数（已删除，不要重新引进它）——它没有任何上界。于是：
     *
     * ```
     * humanDelay(350, 700)   // 调用方读到的是“最多 0.7 秒”
     * ```
     * 深夜（`DORMANT_NIGHT` 的 speedFactor=2.5）叠加疲劳（最高 1.4）以
     * 及指数长尾（均 350ms，尾部可以跑到 2s+）之后，**实际可以躺到 10s+**。
     * 而全项目的调用点（地图拖动每步、抽屉动画、页签切换）都是按
     * “不超过 maxMs”来安排自己的时间预算的——于是“等 350ms”变成“等 10s”，
     * 多步循环里就是分钟级漂行。对压秒、夜哨这类有时限的战术，
     * 这不是拟人，是失控。
     *
     * ## 为什么不能直接 `coerceIn(min, max)` 草草收场
     * 硬截断会把节律与长尾**堆到边界上**：夜里一大片样本恰好等于 maxMs，
     * 那反而是一条更脆、更规律的指纹（固定值比长尾更好检测）。
     *
     * ## 这里的做法：只改“形状”，不改“区间”
     * 先取一个无界形状 `s = 高斯主体 + 指数长尾`，再用 **logistic 映射**
     * 把它单调映进开区间 `(0,1)`：`q = 1 / (1 + exp(-(s - CENTER)/SCALE))`。
     * 节律与疲劳不乘在延迟上，而是加在 **logit 域**（= 在“几率”上乘法放大）：
     * 夜里/疲劳时同样本往 maxMs 一端聚拢，但永远穿不出去。
     *
     * 为何不能把节律偏置直接加在 `q` 上（本机镜像测出的真实坑）：
     * `q + skew` 后一 `coerceIn(0,1)`，深夜档会有 **99.95% 的样本被抹到
     * 同一个值 maxMs**——那就是一个完美的固定周期指纹，比恒速循环更难解释。
     * 而 logit 域的加件只会把整条曲线平移，`q` 永远取不到 0/1，
     * 因此边界上**不会堆出质量块**（实测贴边率 0.00%）。
     * 这些数字不是估算，是 `tools/validate_antiban_math.py` 在本机跑出来的。
     *
     * 结果：区间是硬契约，分布仍是非对称、长尾形状、非常数、不贴边的。
     *
     * @return 一定满足 `minMs <= v <= maxMs`（区间退化时返回其中任一端）
     */
    fun generateBoundedDelayMs(minMs: Long, maxMs: Long, random: Random = Random): Long {
        val lo = minOf(minMs, maxMs)
        val hi = maxOf(minMs, maxMs)
        if (hi - lo <= 1L) return hi

        // 1. 无界拟人形状：高斯主体 + 指数长尾
        val u1 = maxOf(1e-7, random.nextDouble())
        val u2 = random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * Math.cos(2.0 * Math.PI * u2)
        val gaussian = z * 0.28 // 归一化域内的主体宽度（相称于区间上 28% 的标准差）
        val u3 = maxOf(1e-7, random.nextDouble())
        val tail = -0.55 * ln(u3) // 指数长尾（单侧拖尾，不是对称高斯）
        val shape = gaussian + tail

        // 2. logit 映射：单调、严格落在开区间 (0,1)，两端都不取等号
        val logit = (shape - LOGIT_CENTER) / LOGIT_SCALE

        // 3. 节律与疲劳加在 **logit 域**：整条曲线平移，而不在边界堆质量
        val periodFactor = getCircadianPeriod().speedFactor
        val elapsedHours = (System.currentTimeMillis() - sessionStartTime.get()) / (3600 * 1000.0)
        val fatigue = minOf(0.4, elapsedHours * 0.10)
        val skew = (((periodFactor - 1.0) * SKEW_PERIOD) + fatigue * SKEW_FATIGUE)
            .coerceIn(-SKEW_CLAMP, SKEW_CLAMP)
        val q = 1.0 / (1.0 + Math.exp(-(logit + skew)))

        // 4. 线性映到请求区间；这里的 coerceIn 只是兜底，正常路径上碰不到
        val span = (hi - lo).toDouble()
        val v = lo + (span * q).toLong()
        return v.coerceIn(lo, hi)
    }

    /**
     * 泊松过相邻事件间隔：`interval = -mean·ln(U)`。
     *
     * 用途：把轮询循环（等 UI 状态、等邮件红点、定时 ticker）从**固定周期**
     * 改成无记忆随机周期。固定 `delay(250)`/`delay(15000)` 的抓屏+识别循环
     * 会形成一个极为突出的指纹：对时刻序列做傅里叶分析会看到一根尖峰。
     * 泊松过程是唯一同时满足“无周期性”与“均值可控”的标准模型。
     *
     * ## 为何不能直接 `-mean·ln(U)` 再 `coerceIn(floor, ceil)`
     * 裸指数分布质量全挤在 0 附近，一旦硬截断，实测会有 **36% 的样本落在
     * floor、11% 落在 ceil**（本机镜像测得）。两个“固定值占了三成”的
     * 时间序列，比一个恒定的 250ms 更难解释——那是在人为制造新的尖峰。
     *
     * ## 这里的做法：不应期 + 无记忆长尾（位移指数）
     * `interval = floor + Exp(scale)`，取 `scale = mean - floor` 使整体期望仍≈mean。
     * floor 不再是“截断”，而是生理**不应期**（手指还没抬起来就不可能再拍）：
     * 分布从 floor 起步但连续，floor 上**没有原子**；上限只当作保险网，
     * 定到 3×mean 使超出概率降到 1% 以下（实测贴上界 0.97%）。
     * 代价：均值附近的相对偏差更大，换来的是“任何两个相邻周期都不相等”。
     *
     * @param meanMs 期望均值（整体周期时长）
     * @param floorMs 硬下限（不应期）：防止 0ms 空转狂抓屏
     * @param ceilMs  硬上限：防止一次偶发长尾把响应时间拉爆
     */
    fun poissonIntervalMs(
        meanMs: Long,
        floorMs: Long = (meanMs * 0.45).toLong().coerceAtLeast(40L),
        ceilMs: Long = (meanMs * 3.0).toLong(),
        random: Random = Random
    ): Long {
        if (meanMs <= 0L) return floorMs.coerceAtLeast(1L)
        val lo = floorMs.coerceAtMost(ceilMs)
        // scale 定为 mean-lo，叠加在不应期上后整体期望恰好回到 meanMs；
        // mean 比 floor 还小时（超短轮询）保留 40% 余量避免退化成一个常数。
        val scale = (meanMs - lo).coerceAtLeast((meanMs * 0.4).toLong()).toDouble()
        val u = maxOf(1e-7, random.nextDouble())
        val raw = lo - scale * ln(u)
        return raw.toLong().coerceIn(lo, ceilMs)
    }

    /**
     * 运行时自检：不依赖真机、不依赖任何传感器，纯数学不变量。
     *
     * @return 失败原因列表；为空表示通过
     */
    fun selfTest(): List<String> {
        val problems = mutableListOf<String>()

        // 1) 有界延迟：任何一次采样都不得越界（这是整个修复的核心契约）
        run {
            val lo = 350L
            val hi = 700L
            var out = 0
            var sum = 0L
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            var atLo = 0
            var atHi = 0
            val n = 20000
            val rng = Random(20261006L)
            repeat(n) {
                val v = generateBoundedDelayMs(lo, hi, rng)
                if (v < lo || v > hi) out++
                if (v == lo) atLo++
                if (v == hi) atHi++
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
            if (out > 0) problems += "有界延迟越界 $out/$n 次（声明区间 [$lo,$hi]）"
            if (max - min < (hi - lo) / 3L) {
                problems += "有界延迟几乎常数: [$min,$max]，拟人分散度已丢失"
            }
            val mean = sum / n
            if (mean < lo || mean > hi) problems += "有界延迟均值跳出区间: $mean"
            // 旧缺陷反例：上一版把节律偏置直接加在分位数上再 coerceIn(0,1)，
            // 深夜档实测 99.95% 样本贴死 maxMs。饱和/截断堆边是比越界更隐蔽的失效。
            if (atHi > n * 0.02) {
                problems += "有界延迟在 maxMs 堆出质量块: ${atHi * 100 / n}% 恰好等于 $hi（节律语义已失效）"
            }
            if (atLo > n * 0.02) {
                problems += "有界延迟在 minMs 堆出质量块: ${atLo * 100 / n}% 恰好等于 $lo"
            }
        }

        // 2) 夜间必须“更靠上限”，但不能越界——否则节流语义又怭了
        run {
            val lo = 1000L
            val hi = 2000L
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val expectHigh = getCircadianPeriod() === CircadianPeriod.DORMANT_NIGHT
            val rng = Random(99L)
            var sum = 0L
            val n = 6000
            repeat(n) { sum += generateBoundedDelayMs(lo, hi, rng) }
            val meanNorm = (sum / n - lo).toDouble() / (hi - lo)
            if (expectHigh && meanNorm < 0.45) {
                problems += "深夜节律未把分布往上推（归一化均值=${"%.2f".format(meanNorm)}），疲劳语义失效"
            }
            if (!expectHigh && meanNorm > 0.85) {
                problems += "日间常态不应整体贴向上限（归一化均值=${"%.2f".format(meanNorm)}）"
            }
            if (hour < 0 || hour > 23) problems += "小时字段异常: $hour"
        }

        // 3) 泊松间隔：在 [floor,ceil] 内、均值量级合理、且边界没堆出质量块
        run {
            val rng = Random(7L)
            val mean = 250L
            val floor = (mean * 0.45).toLong().coerceAtLeast(40L)   // 112
            val ceil = (mean * 3.0).toLong()                        // 750
            var out = 0
            var sum = 0L
            var atFloor = 0
            var atCeil = 0
            val n = 20000
            repeat(n) {
                val v = poissonIntervalMs(mean, random = rng)
                if (v < floor || v > ceil) out++
                if (v == floor) atFloor++
                if (v == ceil) atCeil++
                sum += v
            }
            if (out > 0) problems += "泊松间隔越界 $out/$n 次"
            val got = sum / n
            if (got < mean * 0.6 || got > mean * 1.8) {
                problems += "泊松间隔均值偏移过大: 期望≈${mean}ms, 实得${got}ms"
            }
            // 硬截断会把三成样本抹到同一个值，那本身就是新指纹
            if (atFloor > n * 0.02) {
                problems += "泊松间隔在不应期堆出质量块: ${atFloor * 100 / n}% 恰好等于 floor=$floor"
            }
            if (atCeil > n * 0.03) {
                problems += "泊松间隔在上限堆出质量块: ${atCeil * 100 / n}% 恰好等于 ceil=$ceil"
            }
        }

        // 4) 去周期性实测：相邻间隔差值的变异系数应显著大于 0
        //    （固定周期下这个值会接近 0，那就是频域上那根尖峰）
        run {
            val rng = Random(31L)
            val xs = ArrayList<Long>(2000)
            repeat(2000) { xs.add(poissonIntervalMs(15000L, random = rng)) }
            val mean = xs.average()
            var v = 0.0
            for (x in xs) v += (x - mean) * (x - mean)
            val sd = sqrt(v / xs.size)
            val cv = sd / mean
            if (cv < 0.35) problems += "轮询间隔变异系数过低 CV=${"%.3f".format(cv)}，仍有周期性残留"
        }

        return problems
    }

    /**
     * 启动期自检并落日志（与 `CoordinateTransformer` / `MapZoomController.logSelfTest()`
     * 同一约定）。这里盯的是三件一旦失效就**完全静默**的事：有界延迟真的
     * 不贴边界、节律真的把分布往上推、轮询真的去了周期性。
     */
    fun logSelfTest() {
        val problems = selfTest()
        if (problems.isEmpty()) {
            Log.i(TAG, "时间指纹自检通过：延迟严格有界且不贴边、轮询已去周期性（CV 达标）。")
        } else {
            Log.e(TAG, "时间指纹自检失败: " + problems.joinToString("; "))
        }
    }

    /**
     * 判定是否应当触发“生理微歇停顿”
     * 避免像死板机器人一样连续点击几小时毫无停滞
     */
    fun shouldTakeMicroBreak(): Boolean {
        val now = System.currentTimeMillis()
        val elapsedSinceBreak = now - lastBreakTime.get()
        return elapsedSinceBreak >= nextBreakIntervalMs
    }

    /**
     * 执行生理微歇并重置下一次微歇周期
     * @return 实际休息停顿的毫秒数
     */
    fun consumeMicroBreak(): Long {
        val now = System.currentTimeMillis()
        lastBreakTime.set(now)
        nextBreakIntervalMs = generateNextBreakInterval()

        // 随机休息 2 分钟 ~ 5.5 分钟 (120s ~ 330s)
        val breakMs = Random.nextLong(120 * 1000L, 330 * 1000L)
        Log.i(TAG, "☕ 触发拟人生理微歇防风控！模拟喝水/离开，预计停顿: ${breakMs / 1000} 秒...")
        return breakMs
    }

    private fun generateNextBreakInterval(): Long {
        // 下一次微歇间隔在 45 分钟 ~ 80 分钟之间随机
        return Random.nextLong(45 * 60 * 1000L, 80 * 60 * 1000L)
    }
}
