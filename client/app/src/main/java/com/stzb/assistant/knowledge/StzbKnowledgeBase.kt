package com.stzb.assistant.knowledge

/**
 * 《率土之滨》2025~2026 征服赛季全场景官方级知识库 (StzbKnowledgeBase)
 * 
 * 核心特性：
 *   1. 深度适配 2026 征服赛季士气 (上限 120, 标准 100) 与体力 (120) 体系；
 *   2. 精确覆盖从 Lv.3 到 Lv.8 各级土地开荒守将天梯与战损预警；
 *   3. 包含完整按键 OCR 语义词表与容错别名，彻底摆脱旧版 PNG 模板失效的痛点；
 *   4. 内置完备的异常弹窗自动闭环规则 (天下大势/演武/邮件/维护)。
 */
object StzbKnowledgeBase {

    val instance: GameProfile by lazy {
        GameProfile(
            gameId = "stzb",
            gameName = "率土之滨 · 2026征服赛季旗舰版",
            profileVersion = "2026.10.1",
            targetPackage = "com.netease.stzb",
            description = "适配率土之滨 2025~2026 征服赛季，包含 120 士气系统、毫秒破免、深度守将天梯打分与深夜敌袭决策 C 反击。",
            rules = GameRules(
                maxStamina = 120,
                staminaPerAction = 20,
                maxMorale = 120,
                moraleStandard = 100,
                minMoraleForPaving = 100,
                immunityDurationSec = 3600,
                immunityPaddingMs = 1000L,
                nightWindowStartHour = 0,
                nightWindowEndHour = 7,
                nightStaminaMultiplier = 1.0,
                screenVirtualWidth = 1280,
                screenVirtualHeight = 720
            ),
            semanticButtons = mapOf(
                "ATTACK" to ButtonDef("ATTACK", "出征", listOf("确定出征", "出 征", "出征作战", "出兵")),
                "SWEEP" to ButtonDef("SWEEP", "扫荡", listOf("扫 荡", "扫荡练兵")),
                "DEFEND" to ButtonDef("DEFEND", "驻守", listOf("驻 守", "部队驻守", "协防")),
                "FARM" to ButtonDef("FARM", "屯田", listOf("屯 田", "资源屯田")),
                "TRAIN" to ButtonDef("TRAIN", "练兵", listOf("练 兵")),
                "SCOUT_DEFENDERS" to ButtonDef("SCOUT_DEFENDERS", "查看守军", listOf("守军", "守军详情", "查看", "守军信息", "守将")),
                "BUILD" to ButtonDef("BUILD", "建设", listOf("建 设", "筑城", "建造", "起要塞")),
                "ABANDON" to ButtonDef("ABANDON", "放弃", listOf("放 弃", "放弃领地", "放弃土地")),
                "CONFIRM" to ButtonDef("CONFIRM", "确定", listOf("确 定", "确认", "出征", "出发", "立即前往")),
                "MARCH" to ButtonDef("MARCH", "行军", listOf("行 军")),
                "TRANSFER" to ButtonDef("TRANSFER", "调兵", listOf("调 兵", "调动")),
                "RETREAT" to ButtonDef("RETREAT", "撤退", listOf("撤 退", "立即撤退", "召回")),
                "RECRUIT" to ButtonDef("RECRUIT", "征兵", listOf("征 兵", "快速征兵", "预备兵")),
                "COORDINATE" to ButtonDef("COORDINATE", "坐标", listOf("座标", "跳转", "查坐标")),
                "JUMP" to ButtonDef("JUMP", "跳转", listOf("跳 转", "前往")),
                "TAX" to ButtonDef("TAX", "税收", listOf("征税", "强征", "税额")),
                "UPGRADE" to ButtonDef("UPGRADE", "升级", listOf("升 级", "扩建", "建筑升级"))
            ),
            defenderDb = DefenderDatabase(
                dangerHeroes = listOf(
                    HeroEntry("郭嘉", "强控-混乱", 5, "十胜十败附带高概率混乱控制，极易导致主力自相残杀灭队", "坚决避开！不可盲撞"),
                    HeroEntry("李儒", "怯战-禁疗", 5, "封普攻+附带高额谋略伤害并禁疗，开荒菜刀队克星", "法系队需谨慎，菜刀队禁打"),
                    HeroEntry("法正", "神谋-跳过准备", 5, "概率跳过主动战法准备回合，超爆发猝死点", "避开，容易战损暴增"),
                    HeroEntry("陈宫", "高伤-点名反弹", 5, "根据敌我战法发动频率追加谋略伤害，智商极高", "避开"),
                    HeroEntry("黄忠", "高物理爆发", 5, "重击+嘲讽，普攻输出极高", "避让或兵力压制"),
                    HeroEntry("陆逊", "火烧连营", 5, "群体灼烧多段引爆，伤害极其爆炸", "极高危，建议换地"),
                    HeroEntry("庞统", "锁链-反噬", 5, "密谋附带伤害传导，主力容易同时暴毙", "坚决避让"),
                    HeroEntry("吕蒙", "白衣渡江-怯战", 5, "开局前两回合全体无法普攻+稳定法伤", "前锋极易直接被秒"),
                    HeroEntry("贾诩", "算无遗策", 5, "主动战法越多伤害越恐怖，专杀高频主动队", "避开"),
                    HeroEntry("周瑜", "极度高危", 5, "玄武洰流群体怯战，强力法术轰杀", "坚决避开"),
                    HeroEntry("魏延", "奇兵奇谋", 5, "突脸直切大营主力，造成大营意外暴毙", "换地打软柿子")
                ),
                hardHeroes = listOf(
                    HeroEntry("张任", "落凤-技穷", 4, "落凤高额单点伤害并计穷，容易打乱战法释放节奏", "兵力充足且带控制可打"),
                    HeroEntry("严颜", "老当益壮", 4, "自身极高免伤与减伤，极难快速破防", "需要高爆发队伍"),
                    HeroEntry("廖化", "诈降-回血", 3, "自愈续航能力强，容易拖入平局", "注意平局补刀"),
                    HeroEntry("管亥", "狂暴吸血", 3, "倒戈吸血+物理连击，身板偏硬", "可打但战损略高"),
                    HeroEntry("潘璋", "断道-定身", 3, "具有先手定身控制机制", "带解控或霸体可打"),
                    HeroEntry("张勋", "枪阵贯穿", 3, "群体物理贯穿，容易压低中军血线", "中军需保证防御"),
                    HeroEntry("纪灵", "三尖两刃", 3, "挑衅战法强制吸引火力", "输出分散，易拖回合"),
                    HeroEntry("曹仁", "八门金锁", 4, "群体怯战，物理平A队克星", "法系队可打")
                ),
                moderateHeroes = listOf(
                    HeroEntry("于禁", "整军经武", 2, "纯被动防御型武将，无爆发战法", "主力正常出征即可"),
                    HeroEntry("徐晃", "长驱直入", 2, "慢热型增伤，前3回合无威胁", "前几回合速推拿下"),
                    HeroEntry("鲍信", "援军秘策", 2, "少量奶量，无控无爆", "难度偏低，推荐打"),
                    HeroEntry("严白虎", "山贼蛮力", 2, "属性偏低，战法不稳定", "兵力达标即可拿下"),
                    HeroEntry("华雄", "恃勇无谋", 2, "高伤害但附带自身受损负面状态", "推荐收割"),
                    HeroEntry("公孙瓒", "白马义从", 2, "增加行军速度与少量规避", "战损低"),
                    HeroEntry("朱儁", "节镇关东", 2, "少量先手伤害，后程疲软", "可稳拿")
                ),
                safeHeroes = listOf(
                    HeroEntry("邓茂", "白给软柿子", 1, "全率土公认开荒大礼包，毫无战法威胁", "🟢 极力推荐！战损接近 0"),
                    HeroEntry("田续", "白给软柿子", 1, "战法释放率极低，毫无爆发伤害", "🟢 极力推荐！开荒必挑"),
                    HeroEntry("裴元绍", "义勇草寇", 1, "身板脆弱，开局直接被秒杀", "🟢 极佳目标"),
                    HeroEntry("审配", "守城弱将", 1, "轻微防御，无任何反伤手段", "🟢 极力推荐"),
                    HeroEntry("李典", "谦逊奉公", 1, "微量辅助战法，无法造成致命减员", "🟢 放心出征"),
                    HeroEntry("陶谦", "安民守土", 1, "老态龙钟，攻击力极低", "🟢 白给队伍"),
                    HeroEntry("韩馥", "懦弱守将", 1, "四维属性低下，平A极弱", "🟢 软柿子收割"),
                    HeroEntry("孔融", "知书达礼", 1, "无任何杀伤性战法", "🟢 极低战损"),
                    HeroEntry("刘焉", "据守益州", 1, "无控无输出，开荒首选经验包", "🟢 推荐收割"),
                    HeroEntry("张宝", "黄巾符水", 1, "战法触发概率极低，白给阵容", "🟢 推荐主力直接拿下")
                ),
                landSuggestions = mapOf(
                    3 to LandSuggestion(3, 1200, listOf("邓茂", "田续", "刘焉"), listOf("管亥"), "开荒第 1 阶段快速铺平跳板地，战损控制在 50 兵以内"),
                    4 to LandSuggestion(4, 3000, listOf("邓茂", "田续", "审配", "裴元绍"), listOf("李儒", "郭嘉"), "开荒首冲 4 级地，务必先用斥候【查看守军】，非软柿子不打！"),
                    5 to LandSuggestion(5, 5500, listOf("李典", "徐晃", "魏续", "鲍信"), listOf("法正", "陈宫", "陆逊", "郭嘉"), "开荒关键分水岭！撞到法正/陈宫必翻车，必须严格匹配软柿子守军"),
                    6 to LandSuggestion(6, 16000, listOf("华雄", "张勋", "周仓"), listOf("吕蒙", "贾诩", "周瑜"), "6级地开始出现第二队守军（双队战平连打），主力需 16000+ 且备战第二补刀队"),
                    7 to LandSuggestion(7, 22000, listOf("于禁", "纪灵"), listOf("陆逊", "庞统", "魏延"), "7级地双队 42000 兵力，需先打下周围地块起要塞，部队满士气出击"),
                    8 to LandSuggestion(8, 28000, listOf("曹仁", "徐晃"), listOf("关妹", "赵云", "法正"), "8级要塞与高级矿产，必须双主力协同或同盟卡秒集火")
                )
            ),
            tacticalDefaults = TacticalDefaults(
                pavingDefaultSlots = listOf(1, 2, 3),
                pavingStepIntervalMs = 2500L,
                immunityDefaultTroopSlot = 1,
                immunityBreakPrecisionMs = 1000L,
                siegeMainSquadSlot = 1,
                siegeDemolitionSlots = listOf(2, 3),
                raidPatrolIntervalMs = 4000L,
                raidDecisionCAutoCounter = true,
                raidAlarmSound = true
            ),
            watchdogKeywords = listOf(
                "点击任意位置关闭", "点击屏幕继续", "确定", "知道了", "跳过",
                "领奖", "恭喜主公", "同盟邮件", "天下大势", "演武提示",
                "系统维护", "签到", "暂不升级", "稍后再说", "完成"
            )
        )
    }
}
