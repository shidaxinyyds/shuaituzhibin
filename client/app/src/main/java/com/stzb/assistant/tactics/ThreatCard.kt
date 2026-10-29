package com.stzb.assistant.tactics

import com.stzb.assistant.ocr.RaidRadarDetector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 告警分级 (NotifyTier)
 *
 * 三档的定义是"**叫醒你的代价**"，不是"事情的严重性"：
 *   * [EMERGENCY]：值得把人从睡梦里叫起来（主城被贴脸、倒计时告急）→ 持续鸣叫 + 唤醒锁；
 *   * [REMIND]：需要立刻知道，但不必持续惊扰（远方敌袭、熔断刹车）→ 一声提示 + 单次震动；
 *   * [INFO]：记下来就行（跟踪中、已自动处置）→ 只上日志，不出声。
 *
 * 为什么必须分档：过去的实现只有"响"和"不响"两种。于是"圈外远距离行军"要么
 * 完全不叫（玩家错过提前量），要么和贴脸敌袭一样持续鸣叫（凌晨三点为了一支还在
 * 半小时外的部队把人吵醒，几次之后玩家就会关掉整个警报——比不报警更糟）。
 */
enum class NotifyTier(val label: String) {
    EMERGENCY("紧急"),
    REMIND("提醒"),
    INFO("告知")
}

/**
 * 一张敌情卡 (ThreatCard)
 *
 * ## 为什么要有"卡"这个概念
 * 告警单独一条日志有致命的缺陷：**它不会自己消失**。凌晨的敌袭日志会和早上的
 * "铺路完成"混在同一个 `tvInGameLogs` 里，玩家根本判断不出"现在到底还有没有危险"。
 * 卡带上 [expiresAtMs]，过期自动从活动列表里掉出去，界面就能如实回答
 * "当前还有几条有效告警"，而不是让玩家自己去翻历史日志。
 *
 * @param id 唯一标识（同一事件重复推送时用于去重/替换）
 * @param createdAtMs 生成时刻（墙钟毫秒）
 * @param tier 叫醒代价档，见 [NotifyTier]
 * @param sourceLabel 来源任务名（如"暗夜哨兵"），用于分来源清理
 * @param title 一行摘要（会在通知里优先展示）
 * @param lines 明细行（地图坐标、倒计时、已执行的处置……）
 * @param expiresAtMs 过期时刻；到点后不再出现在 [NotificationCenter.active] 里
 */
data class ThreatCard(
    val id: String,
    val createdAtMs: Long,
    val tier: NotifyTier,
    val sourceLabel: String,
    val title: String,
    val lines: List<String>,
    val expiresAtMs: Long
) {

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** 剩余秒数（已过期返回 0）。 */
    fun remainingSeconds(nowMs: Long): Int =
        ((expiresAtMs - nowMs) / 1000L).coerceAtLeast(0L).toInt()

    /** 渲染成多行文本（日志/卡片都用这一份，避免两处口径不一致）。 */
    fun render(nowMs: Long): String = buildString {
        append("🃏【").append(sourceLabel).append("·").append(tier.label).append("】").append(title)
        append("（剩余 ").append(remainingSeconds(nowMs)).append("s）")
        lines.forEach { append('\n').append("  • ").append(it) }
    }
}

/**
 * 把各类检测结果翻译成 [ThreatCard] (ThreatCardEngine)
 *
 * 这一层只做"**事实 -> 卡片**"的映射，不做任何告警动作：
 * 要不要响、响多久，全部归 [NotificationCenter]。分开的理由很实在——
 * 判定阈值（什么算紧急）与打扰策略（响多久）是两类完全不同的决策，
 * 写在一起之后想调其中一个必然牵动另一个。
 */
object ThreatCardEngine {

    /** 紧急卡挂 2 分钟：够玩家醒过来处理，又不会在威胁解除后继续挂着误导人。 */
    private const val EMERGENCY_TTL_MS = 120_000L

    /** 提醒卡挂 5 分钟：远距离行军本身就可能要行军这么久。 */
    private const val REMIND_TTL_MS = 300_000L

    /** 告知卡挂 3 分钟。 */
    private const val INFO_TTL_MS = 180_000L

    /** 倒计时低于这个秒数，无论威胁等级如何都按紧急处理。 */
    private const val URGENT_COUNTDOWN_SEC = 60

    /**
     * 暗夜哨兵的敌袭报告 -> 敌情卡。
     *
     * 分档判据（与 [NightSentinelFlow] 的处置动作同源，避免"报警档位"与"处置动作"打架）：
     *   * 突入 2 格警戒圈 / 顶部红标生效 / 倒计时 <= 60s => 紧急；
     *   * 其余有威胁（远距离红线）=> 提醒；
     *   * 有红线但威胁等级为 NONE（例如刚消失的残影）=> 告知。
     */
    fun fromRaidReport(
        report: RaidRadarDetector.RaidReport,
        sourceLabel: String = "暗夜哨兵",
        nowMs: Long = System.currentTimeMillis()
    ): ThreatCard {
        val countdown = report.remainingCountdownSeconds
        val urgent = report.isWithinAlertCircle ||
            report.isTopAlertActive ||
            report.threatLevel == RaidRadarDetector.ThreatLevel.CRITICAL ||
            (countdown != null && countdown <= URGENT_COUNTDOWN_SEC)

        val tier = when {
            urgent -> NotifyTier.EMERGENCY
            report.threatLevel == RaidRadarDetector.ThreatLevel.WARNING -> NotifyTier.REMIND
            else -> NotifyTier.INFO
        }
        val ttl = when (tier) {
            NotifyTier.EMERGENCY -> EMERGENCY_TTL_MS
            NotifyTier.REMIND -> REMIND_TTL_MS
            NotifyTier.INFO -> INFO_TTL_MS
        }

        val target = report.playerTargetPoint
        val lines = buildList {
            add("威胁等级：${report.threatLevel}")
            add(if (report.isWithinAlertCircle) "已突入主城 2 格警戒圈（5x5）" else "圈外远距离行军")
            add("顶部原生预警红标：" + if (report.isTopAlertActive) "已触发" else "未触发")
            add("倒计时：" + (countdown?.let { "${it}s" } ?: "未知/逼近中"))
            add("红线行军数：${report.detectedVectors.size} 条")
            if (target != null) {
                add("受威胁目标：(${target.x.toInt()}, ${target.y.toInt()})")
            }
        }

        return ThreatCard(
            id = UUID.randomUUID().toString(),
            createdAtMs = nowMs,
            tier = tier,
            sourceLabel = sourceLabel,
            title = if (urgent) "主城警戒圈遭敌袭" else "发现敌军行军",
            lines = lines,
            expiresAtMs = nowMs + ttl
        )
    }

    /** 通用的单条告知卡（例如任务被抢占画面、跟踪中的目标）。 */
    fun info(
        title: String,
        detail: String,
        sourceLabel: String,
        nowMs: Long = System.currentTimeMillis()
    ): ThreatCard = singleSourceCard(NotifyTier.INFO, title, detail, sourceLabel, nowMs, INFO_TTL_MS)

    /**
     * 通用的单条提醒卡（例如练级熔断刹车）。
     *
     * 熔断与"任务被抢占"的区别正是 [NotifyTier] 三分法的意义：熔断意味着**这一轮已经放弃**，
     * 玩家必须知道且需要立刻看一眼，但事情已经停了，不需要持续鸣叫把人从床上叫起来。
     */
    fun remind(
        title: String,
        detail: String,
        sourceLabel: String,
        nowMs: Long = System.currentTimeMillis()
    ): ThreatCard = singleSourceCard(NotifyTier.REMIND, title, detail, sourceLabel, nowMs, REMIND_TTL_MS)

    private fun singleSourceCard(
        tier: NotifyTier,
        title: String,
        detail: String,
        sourceLabel: String,
        nowMs: Long,
        ttlMs: Long
    ): ThreatCard = ThreatCard(
        id = UUID.randomUUID().toString(),
        createdAtMs = nowMs,
        tier = tier,
        sourceLabel = sourceLabel,
        title = title,
        lines = listOf(detail),
        expiresAtMs = nowMs + ttlMs
    )

    /** 卡片标题里用的时刻格式：只到分钟，够定位事件，也不占版面。 */
    fun clockOf(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))
}
