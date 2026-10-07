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

/**
 * [EdgeSlmEngine.extractTargetName] 在原文里读不到具体地名时返回的**占位名**。
 *
 * 为什么要把它们列成一张明表：工程里有两处曾写 `targetName != "未明目标"` 来判"是否读到了目标"，
 * 而解析器**从来不返回** "未明目标" 这个串（它返回的是 "目标要地/目标城池/目标据点"），
 * 于是那两个判断**恒真**——无论是否真的读到地名，置信度永远算出 0.96、
 * 攻城流也永远带上一个占位名去当书签名漂移。
 * 判据必须落在真实哨兵值上，所以哨兵值本身要集中定义、不许各处自己抄。
 */
val GENERIC_TARGET_NAMES: Set<String> = setOf(
    "目标要地", "目标城池", "目标据点", "未指定目标", "未明目标"
)

/** 是否是"真的从军令原文里读到的地名"（而不是占位名/空串）。 */
fun String?.isConcreteTargetName(): Boolean =
    !this.isNullOrBlank() && this !in GENERIC_TARGET_NAMES
