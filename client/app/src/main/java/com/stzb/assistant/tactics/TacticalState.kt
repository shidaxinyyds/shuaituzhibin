package com.stzb.assistant.tactics

/**
 * 战术流水线状态机数据模型与事件定义 (TacticalState)
 */
object TacticalState {

    enum class TaskType(val displayName: String) {
        // --- 2026 商业化 5 大黄金中枢 ---
        NIGHT_SENTINEL("暗夜天眼防沦哨兵"),
        SIEGE_SYNC("全盟战役双压秒全勤王"),
        TACTICAL_SCHEDULE("离线战术定时管家"),
        TACTICAL_HUD("端侧RAG战术智脑HUD"),
        LOGISTICS_STEWARD("单账号日常后勤全托管"),
        FARMING_STEWARD("全自动屯田打铁管家"),

        // --- 历史兼顾兼容项（平滑过渡）---
        ROAD_PAVING("自动铺路翻地(已平替)"),
        IMMUNITY_BREAK("极限破免(已并入定时管家)"),
        RAID_DEFENSE("深夜应急总控(已升级哨兵)"),
        STAMINA_ROTATION("主力体能轮换(已并入后勤)")
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
        val taskType: TaskType,
        val level: String, // "INFO", "WARN", "ERROR", "TACTIC"
        val message: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    interface TacticalEventListener {
        fun onStatusChanged(taskType: TaskType, status: Status, detail: String)
        fun onLogEmitted(log: TacticalLog)
    }
}
