package com.stzb.assistant.tactics

import android.content.Context
import android.util.Log
import com.stzb.assistant.service.PerGameScope

/**
 * 告警总台 (NotificationCenter)
 *
 * ## 它解决的两个具体问题
 *   1. **打扰强度只有一档**：过去凡是"要报警"，一律 `AlarmRinger.startAlarm` ——
 *      持续鸣叫 + 10 分钟唤醒锁。凌晨为一支还在半小时外的部队这么响，
 *      玩家几天之内就会把整个哨兵关掉。现在按 [NotifyTier] 分档：
 *      只有真正贴脸（突入警戒圈/红标/倒计时告急）才持续鸣叫。
 *   2. **告警不会自己消失**：一张 [ThreatCard] 带上过期时刻，
 *      [active] 每次被调用时顺手剪掉过期的，界面就能如实回答"现在还有没有危险"。
 *      没有这一步，凌晨的红色日志会和早上的"铺路完成"堆在一起，玩家永远不知道
 *      危险到底解除没有。
 *
 * ## 为什么是内存单例而不是持久化
 * 告警的语义是"**此刻**有没有危险"。进程被杀之后仍然生效的告警是错的——
 * 那会在重启时凭空弹出一条几小时前的敌袭。因此这里刻意不落盘。
 *
 * ## 线程约束
 * [push] / [active] / [clear] 都是任意线程安全的（内部加锁）。
 * 声音与震动由 [AlarmRinger] 负责，它自己处理了线程问题。
 */
object NotificationCenter {

    private const val TAG = "NotificationCenter"

    /** 活动卡上限：告警是"当前状态"，不是归档，超过就丢最旧的。 */
    private const val MAX_CARDS = 16

    private val lock = Any()
    private val cards = mutableListOf<ThreatCard>()

    /**
     * 推送一张告警卡并按档位触发打扰。
     *
     * @param soundEnabled 用户的“声音告警”开关。关掉时卡照常登记（界面仍要如实显示
     *        “现在有几条有效告警”），但不发声、不震动——开关的语义是“别出声”，
     *        不是“别记录”。
     * @return 这张卡本身（调用方通常紧接着 `card.render(now)` 上日志，
     *         用返回值是为了保证“日志里读到的内容”与“卡片里存的内容”逐字一致）
     */
    fun push(
        context: Context,
        card: ThreatCard,
        soundEnabled: Boolean = true,
        nowMs: Long = System.currentTimeMillis()
    ): ThreatCard {
        synchronized(lock) {
            // 同 id 重复推送按“替换”处理，避免反复进入 handleRaidEvent 时堆出十几张同内容的卡。
            cards.removeAll { it.id == card.id }
            cards.add(card)
            while (cards.size > MAX_CARDS) {
                cards.removeAt(0)
            }
        }
        if (soundEnabled) {
            escalate(context, card)
        }
        return card
    }

    /**
     * 取当前**仍然有效**的告警（顺便剪掉过期的）。
     *
     * 这是本类唯一的读入口：所有展示都以它为准，因此"过期自清"不需要额外的定时器——
     * 只要有人看一眼，过期的就已经不在了。
     */
    fun active(nowMs: Long = System.currentTimeMillis()): List<ThreatCard> =
        synchronized(lock) {
            cards.removeAll { it.isExpired(nowMs) }
            cards.toList()
        }

    /**
     * 一行状态摘要（供日志/卡片展示）。
     *
     * 刻意把"最近一条是什么、还剩多久"写出来：只报"当前 1 条告警"的话，
     * 玩家仍然不知道是新的还是十分钟前那条快过期的。
     */
    fun stateLabel(nowMs: Long = System.currentTimeMillis()): String {
        val live = active(nowMs)
        if (live.isEmpty()) return "🃏 当前无有效告警"
        val emergency = live.count { it.tier == NotifyTier.EMERGENCY }
        val remind = live.count { it.tier == NotifyTier.REMIND }
        val info = live.count { it.tier == NotifyTier.INFO }
        val newest = live.maxByOrNull { it.createdAtMs }
        return buildString {
            append("🃏 当前有效告警 ").append(live.size).append(" 条")
            append("（紧急 ").append(emergency)
            append(" / 提醒 ").append(remind)
            append(" / 告知 ").append(info).append("）")
            if (newest != null) {
                append("；最近一条 ").append(ThreatCardEngine.clockOf(newest.createdAtMs))
                append(" ").append(newest.title)
                append("（剩余 ").append(newest.remainingSeconds(nowMs)).append("s）")
            }
        }
    }

    /**
     * 登记“换游戏自动清台 + 复位鸣叫”。
     *
     * 告警卡的语义是“此刻有没有危险”，而“此刻”绑定在某一款游戏上：切到三战后
     * 屏幕上还挂着一条“率土主城遭敌袭”，玩家会被引导去做一个根本不存在的应对动作。
     * 而只清卡不停铃更糟——问题从“误报”变成“响着但没人知道该关什么”。
     * 因此这两件事必须绑在一起做。
     */
    fun attachToProfileSwitch(context: Context) {
        val app = context.applicationContext
        PerGameScope.reloadOnProfileSwitch {
            clear()
            AlarmRinger.stopAlarm(app)
        }
    }

    /**
     * 清空告警卡。
     *
     * @param sourceLabel 只清某个来源的卡（传 null = 全清）。
     *        目前有两个来源：暗夜哨兵（[NightSentinelFlow]）与练级管家（熔断刹车）。
     *        按来源清不只是预留：哨兵自己解除威胁时只能撤自己的卡，不能把练级的熔断提示一并抹掉。
     */
    fun clear(sourceLabel: String? = null) {
        synchronized(lock) {
            if (sourceLabel == null) {
                cards.clear()
            } else {
                cards.removeAll { it.sourceLabel == sourceLabel }
            }
        }
        val scopeDesc = sourceLabel ?: "全部"
        Log.i(TAG, "告警卡已清理（来源=$scopeDesc）。")
    }

    /**
     * 按档位触发打扰。
     *
     * 三档与 [AlarmRinger] 的对应关系是**有意**的：
     *   * EMERGENCY -> [AlarmRinger.startAlarm]：持续鸣叫 + 唤醒锁，直到有人解除；
     *   * REMIND    -> [AlarmRinger.triggerEmergencyAlarm]：约 3 秒有界提示，不占用鸣叫状态机；
     *   * INFO      -> 不出声。只出日志。
     */
    private fun escalate(context: Context, card: ThreatCard) {
        when (card.tier) {
            NotifyTier.EMERGENCY -> {
                Log.w(TAG, "🚨 紧急告警（持续鸣叫）：${card.title}")
                AlarmRinger.startAlarm(context)
            }
            NotifyTier.REMIND -> {
                Log.w(TAG, "🔔 提醒告警（一次性提示）：${card.title}")
                AlarmRinger.triggerEmergencyAlarm(context, card.title)
            }
            NotifyTier.INFO -> {
                Log.i(TAG, "ℹ️ 告知告警（仅记录）：${card.title}")
            }
        }
    }
}
