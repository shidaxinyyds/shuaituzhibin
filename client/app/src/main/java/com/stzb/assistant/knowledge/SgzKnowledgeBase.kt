package com.stzb.assistant.knowledge

/**
 * 《三国志·战略版》PK 赛季独立知识库 (SgzKnowledgeBase)
 * 
 * 充分展示通用引擎架构的多游戏扩展能力：
 *   1. 适配三战独有的【100 士气上限】与【夜战双倍体力消耗 (00:00~06:00)】机制；
 *   2. 采用三战标准的【占领/调动/讨伐/行军/策书/闭城】语义按键；
 *   3. 涵盖三战常见守将阵容天梯与兵种相克打分；
 *   4. 底层完全共用（Shizuku + 720p + 拟人防封 + 卡密鉴权），**本文件不含一行引擎代码**；
 *    但"接一款新游戏"并不等于零改动：仍要把这个知识包注册进
 *    KnowledgeBaseManager.builtInProfiles 并重编 APK（云端热更只能改已注册游戏的内容），
 *    逐条站点由 `python tools/check_multi_game_readiness.py` 实测列出，别信口号。
 */
object SgzKnowledgeBase {

    val instance: GameProfile by lazy {
        GameProfile(
            gameId = "sgz",
            gameName = "三国志·战略版 · PK赛季旗舰版",
            profileVersion = "2026.03.2",
            targetPackage = "com.aligames.sgzzlb",
            description = "适配三国志战略版 PK 赛季，支持 100 士气系统、夜战双倍消耗预警、守军兵种克制与一键打地调动。",
            rules = GameRules(
                maxStamina = 120,
                staminaPerAction = 15,
                maxMorale = 100,
                moraleStandard = 100,
                minMoraleForPaving = 80,
                immunityDurationSec = 3600,
                immunityPaddingMs = 1200L,
                nightWindowStartHour = 0,
                nightWindowEndHour = 6,
                nightStaminaMultiplier = 2.0, // 三战夜战双倍体力消耗
                // 三战的实际地图边界无可靠依据，这里**原样沿用**引擎此前对两款游戏
                // 都写死的 1500（不臆造更大的值），真机读到越界坐标时按现有口径裁掉；
                // 待真机校准后热更这一行即可。
                mapCoordMax = 1500
            ),
            semanticButtons = mapOf(
                "ATTACK" to ButtonDef("ATTACK", "占领", listOf("占 领", "出征占领", "出兵")),
                "SWEEP" to ButtonDef("SWEEP", "扫荡", listOf("扫 荡")),
                "DEFEND" to ButtonDef("DEFEND", "驻守", listOf("驻 守", "协助驻守")),
                "FARM" to ButtonDef("FARM", "屯田", listOf("屯 田")),
                "TRAIN" to ButtonDef("TRAIN", "讨伐", listOf("讨 伐", "贼寇")),
                "SCOUT_DEFENDERS" to ButtonDef("SCOUT_DEFENDERS", "侦察", listOf("侦 察", "查看守军", "守军兵种")),
                "BUILD" to ButtonDef("BUILD", "筑城", listOf("筑 城", "营帐", "拒马", "箭塔")),
                "ABANDON" to ButtonDef("ABANDON", "放弃", listOf("放 弃", "放弃领地")),
                "CONFIRM" to ButtonDef("CONFIRM", "确定", listOf("确 定", "确认", "出征", "行军")),
                "MARCH" to ButtonDef("MARCH", "行军", listOf("行 军")),
                "TRANSFER" to ButtonDef("TRANSFER", "调动", listOf("调 动", "调兵")),
                "RETREAT" to ButtonDef("RETREAT", "撤退", listOf("撤 退", "返回")),
                "RECRUIT" to ButtonDef("RECRUIT", "征兵", listOf("征 兵", "预备兵")),
                "COORDINATE" to ButtonDef("COORDINATE", "坐标", listOf("定位", "跳转", "坐标")),
                "JUMP" to ButtonDef("JUMP", "跳转", listOf("前 往", "跳转")),
                // 下面四个键在 ButtonType 枚举里存在，但旧库没有词条：
                // 切到三战后这些动作只能退回枚举默认关键字，热更新也无法为它们增删别名。
                "CANCEL" to ButtonDef("CANCEL", "取消", listOf("取 消", "关闭", "返回", "再想想")),
                "TAX" to ButtonDef("TAX", "税收", listOf("征 税", "赋税", "收取税收")),
                "UPGRADE" to ButtonDef("UPGRADE", "升级", listOf("升 级", "建筑升级", "提升等级")),
                "FORGE" to ButtonDef("FORGE", "锻造", listOf("打铁", "铸 造", "装备锻造"))
            ),
            defenderDb = DefenderDatabase(
                dangerHeroes = listOf(
                    HeroEntry("丁奉", "短兵相见", 5, "高概率破防+单体高伤，极易打崩前锋", "坚决避开"),
                    // 旧库写作“不 numero 控”——那是“不辱使命”被机器翻译/OCR 污染后的残体，
                    // 玩家看不懂、也没法跟游戏内武将详情逐字核对。三战潘璋自带战法即【不辱使命】。
                    HeroEntry("潘璋", "不辱使命-震慑", 5, "兵刃伤害并有概率施加震慑（无法行动），很容易把我方主动战法节奏打断", "换地打"),
                    HeroEntry("皇甫嵩", "后发制人", 4, "自带强力反击，反击战损极高", "避开或兵力压制"),
                    HeroEntry("沙摩柯", "弯弓饮羽", 4, "减防+计穷封技能", "谨慎攻打")
                ),
                hardHeroes = listOf(
                    HeroEntry("孙坚", "江东猛虎", 4, "嘲讽并提高自身免伤", "需要法伤爆发队"),
                    HeroEntry("关平", "奋突连击", 3, "普攻缴械附带增伤", "法系队可打"),
                    HeroEntry("曹真", "白马义从", 3, "增加先攻与规避", "战损略高")
                ),
                moderateHeroes = listOf(
                    HeroEntry("郭淮", "御敌屏障", 2, "纯群体减伤，无爆发伤害", "推荐打"),
                    HeroEntry("曹洪", "骁勇善战", 2, "普通兵刃战法，容易战平", "兵力充足可拿下")
                ),
                safeHeroes = listOf(
                    HeroEntry("宋宪", "白给软柿子", 1, "开荒极度推荐，极低战损", "🟢 推荐优先攻打"),
                    HeroEntry("糜芳", "软柿子守军", 1, "毫无伤害，开荒首选", "🟢 推荐收割"),
                    HeroEntry("傅士仁", "白给守军", 1, "极度脆弱", "🟢 轻松拿下")
                ),
                landSuggestions = mapOf(
                    // 守军总兵力出处（三战）：4级地 5400、5级地 9000（均为单队）；
                    // 6级地起为两队，每队 15000（总 30000，第二队不可侦察）；7级地每队 21000（总 42000）。
                    // 我方推荐兵力出处：5级地“强力阵容 5000 / 普通 6000，再带一队 3000 补刀”；
                    // 6级地“至少准备 1 万兵”；7级地“至少两队 15000 以上才能保证胜利”。
                    4 to LandSuggestion(4, 3200, listOf("宋宪", "糜芳"), listOf("丁奉", "潘璋"), "三战 4 级地务必先派斥候侦察兵种克制，骑克盾、盾克弓、弓克枪、枪克骑！", defenderTotalSoldiers = 5400),
                    5 to LandSuggestion(5, 5000, listOf("郭淮", "曹洪"), listOf("皇甫嵩", "沙摩柯"), "5级地为开荒关键跃升期，兵种大克制方可低损拿下", defenderTotalSoldiers = 9000),
                    6 to LandSuggestion(6, 10000, listOf("宋宪", "糜芳", "傅士仁"), listOf("孙坚", "曹真"), "6级地起守军为两队且第二队不可侦察，必须带第二队补刀防翻车（软柿名单为跨等级守将池推断，待真机校准）", defenderTotalSoldiers = 30000),
                    7 to LandSuggestion(7, 15000, listOf("郭淮", "曹洪"), listOf("丁奉", "潘璋", "皇甫嵩", "沙摩柯"), "7级地输出与控制战法搭配成型，至少两队 15000+ 再上", defenderTotalSoldiers = 42000)
                )
            ),
            tacticalDefaults = TacticalDefaults(
                pavingDefaultSlots = listOf(1, 2),
                pavingStepIntervalMs = 3000L,
                immunityDefaultTroopSlot = 1,
                siegeMainSquadSlot = 1,
                siegeDemolitionSlots = listOf(2, 3),
                raidPatrolIntervalMs = 5000L,
                raidDecisionCAutoCounter = false,
                raidAlarmSound = true
            ),
            // 战报判定字典：只登记**本文件守将天梯里已经写了的名字**，
            // 这样"战报里出现的战法/武将"能被命中，且每一条都能和上面的天梯逐字核对。
            // REPORT_PVE / REPORT_PVP（战报里表示"打地/打人"的字样）、COMMAND_AMPLIFY_SKILLS
            // 与 heroBaseSpeed 目前无可自证的三战数据，**故意留空**：
            // 留空的结果是这些判定如实跳过（战报归为"类型未定"、先手一律"需实测算"），
            // 而猜错的结果是把三战里没有的机制念给玩家——后者才是真事故。
            sceneKeywords = mapOf(
                "KNOWN_SKILLS" to listOf(
                    "短兵相见", "不辱使命", "后发制人", "弯弓饮羽",
                    "江东猛虎", "奋突连击", "白马义从", "御敌屏障", "骁勇善战"
                ),
                "KNOWN_HEROES" to listOf(
                    "丁奉", "潘璋", "皇甫嵩", "沙摩柯", "孙坚", "关平", "曹真",
                    "郭淮", "曹洪", "宋宪", "糜芳", "傅士仁"
                )
            ),
            watchdogKeywords = listOf(
                "点击空白处继续", "确定", "知道了", "领奖", "签到", "邮件",
                "势力值提升", "暂不升级", "放弃", "跳过", "关闭"
            )
        )
    }
}
