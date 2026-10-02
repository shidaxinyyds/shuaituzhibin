package com.stzb.assistant.ai.microbrain

/**
 * 端侧认知微脑战术指令数据契约 (TacticalOrder)
 * 
 * 由端侧微脑 (EdgeSlmEngine) 解析非结构化自然语言军令、信件或同盟法令后生成的标准执行实体。
 */
data class TacticalOrder(
    val orderId: String,
    val intent: OrderIntent,
    val targetName: String,
    val targetCoord: Pair<Int, Int>? = null,
    val targetTime: Long = 0L,              // 目标触达或发兵时间戳 (Epoch ms)
    val advanceSeconds: Int = 180,           // 铺路/压秒提前量 (秒)
    val assignedTeams: List<TeamRole> = listOf(TeamRole.MAIN_FORCE),
    val contingencyPlan: ContingencyAction? = null,
    val advisorThinking: String = "",        // 军师推演思考过程 (用于实时 HUD 播报)
    val confidence: Float = 0.95f,
    val rawDecreeText: String = ""
)

enum class OrderIntent(val desc: String) {
    ALLIANCE_SIEGE("全盟攻城/集火"),
    ROAD_PAVING("先锋铺路/翻地"),
    DEFEND_GATE("关口要塞驻守"),
    RETREAT_AND_GUARD("战略撤退与回防"),
    SPARTAN_SCOUT("斯巴达探路/试探"),
    STAMINA_RECOVERY("休整征兵与屯田")
}

enum class TeamRole(val roleName: String) {
    MAIN_FORCE("主力输出队"),
    SIEGE_FORCE("攻城拆迁队"),
    SPARTAN("斯巴达探路队"),
    DEFENDER("驻守肉盾队")
}

enum class ContingencyAction(val actionName: String) {
    FALLBACK_TO_PASS("若目标被抢则就地转关口驻守"),
    IMMEDIATE_RETREAT("若遇强敌主力则立刻秒回撤退"),
    STOP_AND_ALARM("若遇异常则停机并触发警报")
}

/**
 * 战报深度会诊结果实体 (BattleDiagnosis)
 */
data class BattleDiagnosis(
    val battleId: String,
    val battleResult: BattleResult,
    val myTroopLoss: Int,
    val enemyTroopLoss: Int,
    val keySkillsDetected: List<String>,
    val strategicCounterAdvice: String,
    val militaryCommentary: String
)

enum class BattleResult(val desc: String) {
    VICTORY("大捷"),
    DEFEAT("战败"),
    DRAW("战平打平")
}
