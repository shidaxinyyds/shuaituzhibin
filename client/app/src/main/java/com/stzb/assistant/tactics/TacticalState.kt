package com.stzb.assistant.tactics

/**
 * 战术流水线状态机数据模型与事件定义 (TacticalState)
 */
object TacticalState {

    enum class TaskType(val displayName: String) {
        ROAD_PAVING("自动铺路翻地"),
        IMMUNITY_BREAK("极限卡免与压秒破免"),
        SIEGE_SYNC("同盟集火攻城毫秒卡秒"),
        RAID_DEFENSE("深夜敌袭应急处置总控"),
        STAMINA_ROTATION("主力体能动态轮班扫荡")
    }

    enum class Status(val desc: String) {
        IDLE("就绪空闲"),
        RUNNING("执行中"),
        WAITING_COUNTDOWN("倒计时卡秒等待中"),
        PAUSED("已暂停"),
        COMPLETED("任务圆满完成"),
        FAILED("异常中断失败"),
        INTERRUPTED("用户手动终止")
    }

    data class TacticalLog(
        val timestamp: Long = System.currentTimeMillis(),
        val taskType: TaskType,
        val level: String, // "INFO", "WARN", "ERROR", "TACTIC"
        val message: String
    )

    interface TacticalEventListener {
        fun onStatusChanged(taskType: TaskType, status: Status, detail: String)
        fun onLogEmitted(log: TacticalLog)
    }
}
