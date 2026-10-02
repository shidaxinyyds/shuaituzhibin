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

    enum class CircadianPeriod(val desc: String, val speedFactor: Float) {
        ACTIVE_DAY("日间常态 (07:00 ~ 19:00)", 1.0f),
        PEAK_EVENING("晚间同盟活跃期 (19:00 ~ 01:00)", 0.85f), // 反应更敏捷
        DORMANT_NIGHT("深夜睡眠静默期 (01:00 ~ 07:00)", 2.5f)  // 操作显著放缓
    }

    private val sessionStartTime = AtomicLong(System.currentTimeMillis())
    private val lastBreakTime = AtomicLong(System.currentTimeMillis())
    private var nextBreakIntervalMs = generateNextBreakInterval()

    fun resetSession() {
        sessionStartTime.set(System.currentTimeMillis())
        lastBreakTime.set(System.currentTimeMillis())
        nextBreakIntervalMs = generateNextBreakInterval()
    }

    /**
     * 生成人体反应非对称长尾延迟 (Ex-Gaussian 分布)
     * @param baseMs 基准反应均值 (如 1200ms)
     * @param sigmaMs 高斯标准差 (如 250ms)
     * @param tauMs 指数长尾弛豫时间 (如 400ms，模拟偶发走神/看微信)
     */
    fun generateHumanDelay(
        baseMs: Long = 1200L,
        sigmaMs: Long = 250L,
        tauMs: Long = 350L
    ): Long {
        // 1. Box-Muller 变换生成标准正态分布随机数 Z ~ N(0, 1)
        val u1 = maxOf(1e-7, Random.nextDouble())
        val u2 = Random.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * Math.cos(2.0 * Math.PI * u2)

        // 2. 高斯主体部分 (均值截断，防止出现负数)
        val gaussianPart = maxOf(200.0, baseMs + z * sigmaMs)

        // 3. 指数分布长尾部分 (模拟偶发思考、走神、眨眼)
        val u3 = maxOf(1e-7, Random.nextDouble())
        val exponentialTail = -tauMs * ln(u3)

        // 4. 叠加昼夜节律调控系数
        val circadian = getCircadianPeriod()
        val periodFactor = circadian.speedFactor

        // 5. 叠加连续游戏疲劳度 (每运行 1 小时，疲劳系数增加 10%，操作变缓)
        val elapsedHours = (System.currentTimeMillis() - sessionStartTime.get()) / (3600 * 1000.0)
        val fatigueFactor = 1.0 + minOf(0.4, elapsedHours * 0.10)

        val finalDelay = ((gaussianPart + exponentialTail) * periodFactor * fatigueFactor).toLong()
        Log.d(TAG, "生成拟人时间延迟: ${finalDelay}ms (节律: ${circadian.desc}, 疲劳倍率: ${"%.2f".format(fatigueFactor)})")
        return finalDelay
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
