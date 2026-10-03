package com.stzb.assistant.tactics

import android.content.Context
import android.graphics.PointF

/**
 * 深夜敌袭应急处置总控与防沦中枢 (RaidDefenseFlow)
 *
 * 继承并全面升级为 [NightSentinelFlow]，保持原有 API 与调用方 100% 兼容。
 */
class RaidDefenseFlow(
    context: Context,
    listener: TacticalState.TacticalEventListener? = null
) : NightSentinelFlow(context, listener) {

    /**
     * 保持向后兼容的配置模型
     */
    data class DefenseConfig(
        val baseAnchor: PointF? = null,              // 己方主城/防守核心要塞虚拟锚点 (默认居中)
        val baseWorldCoord: Pair<Int, Int>? = null,  // 己方主城大地图世界坐标 (如 Pair(550, 480))
        val alertCircleRadiusTiles: Int = 2,        // 主城警戒圈格数 (默认 2 格，5x5 核心威胁区)
        val counterAttackSquadSlot: Int = 1,        // 决策 C 反击所用的高机动拆迁骑兵槽位
        val retreatSquadSlots: List<Int> = listOf(1, 2), // 60s 紧急秒回撤退的主力编队槽位 (默认 1、2 队)
        val patrolIntervalMs: Long = 4000L,         // 夜战雷达巡检周期 (毫秒)
        val enableAudioAlarm: Boolean = true,       // 是否拉响高分贝警报与震动
        val enableAutoRetreat: Boolean = true,      // 60秒紧急自动撤退主力保命 (核心王牌)
        val enableDecisionC: Boolean = false,       // 是否全自动执行决策 C 反击拆除 (高危，默认关闭优先保命)
        val enableEmergencyFortify: Boolean = true, // 是否在危急时刻尝试启动【闭城/坚守】
        val enableKeepAliveJiggle: Boolean = true,  // 5分钟安全微保活防掉线
        val keepAliveIntervalMs: Long = 300_000L    // 防掉线微保活周期 (5 分钟)
    )

    companion object {
        private const val TAG = "RaidDefenseFlow"
    }
}
