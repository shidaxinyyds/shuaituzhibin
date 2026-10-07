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
            profileVersion = "2026.10.2",
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
                // 地图坐标上界：沿用引擎此前写死的 1500（率土坐标读数长期落在该界内），
                // 现在它可热更，改这一行/推一份产物即可，不必再重编 APK。
                mapCoordMax = 1500
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
                "CANCEL" to ButtonDef("CANCEL", "取消", listOf("取 消", "关闭", "再想想", "放弃操作", "返回")),
                "MARCH" to ButtonDef("MARCH", "行军", listOf("行 军")),
                "TRANSFER" to ButtonDef("TRANSFER", "调兵", listOf("调 兵", "调动")),
                "RETREAT" to ButtonDef("RETREAT", "撤退", listOf("撤 退", "立即撤退", "召回")),
                "RECRUIT" to ButtonDef("RECRUIT", "征兵", listOf("征 兵", "快速征兵", "预备兵")),
                "COORDINATE" to ButtonDef("COORDINATE", "坐标", listOf("座标", "跳转", "查坐标")),
                "JUMP" to ButtonDef("JUMP", "跳转", listOf("跳 转", "前往")),
                "TAX" to ButtonDef("TAX", "税收", listOf("征税", "强征", "税额")),
                "UPGRADE" to ButtonDef("UPGRADE", "升级", listOf("升 级", "扩建", "建筑升级")),
                "FORGE" to ButtonDef("FORGE", "锻造", listOf("打铁", "铸 造", "装备锻造", "工坊"))
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
                    // 高级地黑名单里早就写了赵云/关妹，但守将库里根本没这两人：
                    // OCR 读到“赵云”会被 filterKnownHeroes 直接丢弃，黑名单形同虚设。
                    // 具体战法机制未能自证，因此不按 5 分极危入库（避免伪数据），
                    // 而是按“较难有损”入库 + 标注待校准；至少让黑名单真的能命中。
                    HeroEntry("赵云", "高机动爆发（待校准）", 4, "高级地常见守将，突进与单体爆发高；具体自带战法待真机校准", "慎打，建议先侦察"),
                    HeroEntry("关银屏", "蜀步高压（待校准）", 4, "高级地常见守将，身板硬且附带控制；具体自带战法待真机校准（旧库写作口头昵称“关妹”，OCR 永远对不上，已改回正式名）", "慎打，建议兵力压制"),
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
                    HeroEntry("张宝", "黄巾符水", 1, "战法触发概率极低，白给阵容", "🟢 推荐主力直接拿下"),
                    // 旧库把 魏续 写进五级地软柿名单却没入守将库（名单形同虚设）。
                    // 依据：社区五级地难度表将“徐庶、蔡夫人、魏续”列为简单难度（待真机复核）。
                    HeroEntry("魏续", "五级地简单难度", 1, "五级地常见守将，无硬控与高爆发；战法细节待真机校准", "🟢 可优先挑打")
                ),
                landSuggestions = mapOf(
                    // defenderTotalSoldiers 取官方《知己知彼百战不殆·各等级土地兵力值统览》
                    // （http://stzb.163.com/m/strategy/ywsl/2018-04-19/21009_615819.html）：
                    //   Lv.3 = 1 支 8 级部队 1800；Lv.4 = 1 支 12 级 5000；Lv.5 = 1 支 3 名 20 级 约 9000；
                    //   Lv.6 = 2 支 28 级 每支 16500（总 33000）；Lv.7 = 2 支 36 级 21000（总 42000）；
                    //   Lv.8 = 2 支 42 级 25500（总 51000）。
                    // 官方同时注明：野地距玩家越远守军越多，且该表为新服基准值——所以这些数是
                    // “典型值”而不是铁定值，展示时必须带上“约”。
                    // recommendedSoldiers 是**我方该带多少**，与守军总兵力不是一回事，别混用。
                    3 to LandSuggestion(3, 1200, listOf("邓茂", "田续", "刘焉"), listOf("管亥"), "开荒第 1 阶段快速铺平跳板地，战损控制在 50 兵以内", defenderTotalSoldiers = 1800),
                    4 to LandSuggestion(4, 3000, listOf("邓茂", "田续", "审配", "裴元绍"), listOf("李儒", "郭嘉"), "开荒首冲 4 级地，务必先用斥候【查看守军】，非软柿子不打！", defenderTotalSoldiers = 5000),
                    5 to LandSuggestion(5, 5500, listOf("李典", "徐晃", "魏续", "鲍信"), listOf("法正", "陈宫", "陆逊", "郭嘉"), "开荒关键分水岭！撞到法正/陈宫必翻车，必须严格匹配软柿子守军（官方建议：一队 6000 左右平手、二队 2500 左右补刀）", defenderTotalSoldiers = 9000),
                    // 从该名单剔除了无法自证的“软柿子”：
                    //   张勋（本库 hard 档）、周仓（无任何可靠依据）；
                    //   把较难/极危武将当软柿子推荐，是会把主力送掉的假阳性。
                    6 to LandSuggestion(6, 16000, listOf("华雄"), listOf("吕蒙", "贾诩", "周瑜"), "6级地开始出现第二队守军（双队战平连打），主力需 16000+ 且备战第二补刀队", defenderTotalSoldiers = 33000),
                    7 to LandSuggestion(7, 22000, listOf("于禁"), listOf("陆逊", "庞统", "魏延"), "7级地双队 42000 兵力，需先打下周围地块起要塞，部队满士气出击", defenderTotalSoldiers = 42000),
                    8 to LandSuggestion(8, 28000, listOf("徐晃"), listOf("关银屏", "赵云", "法正"), "8级要塞与高级矿产，必须双主力协同或同盟卡秒集火", defenderTotalSoldiers = 51000)
                )
            ),
            tacticalDefaults = TacticalDefaults(
                pavingDefaultSlots = listOf(1, 2, 3),
                pavingStepIntervalMs = 2500L,
                immunityDefaultTroopSlot = 1,
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
            ),
            // 5d. 场景判定与战报判定的词表（组 ID → 词）。
            //     这些词原本是写死在 ocr/StzbUiMatcher 与 ai/rag/SlgRagEngine 里的率土文案；
            //     现在写进知识包，**改词不用再重编 APK**（热更即生效）。
            //     SCENE_* 在引擎里仍留一份同样的率土底值做兼容回落；
            //     VOCAB_*（战法/武将字典）引擎里**没有底值**，缺配就如实不判定，
            //     所以这几组是率土知识包的必配项。
            sceneKeywords = mapOf(
                "DISPATCH_ACTION" to listOf("出征", "调动", "行军"),
                "DISPATCH_TROOP" to listOf("部队", "体力", "耗时", "预计"),
                "DEFENDER_TITLE" to listOf("守军"),
                "DEFENDER_DETAIL" to listOf("兵力", "战法", "难度"),
                "TROOP_PANEL" to listOf("部队一", "部队二"),
                "COORD_TITLE" to listOf("坐标"),
                "COORD_ACTION" to listOf("跳转", "X", "Y"),
                "FORTRESS_TITLE" to listOf("要塞"),
                "FORTRESS_ACTION" to listOf("建设", "工匠", "建造"),
                "MAIN_MAP_HUD" to listOf("令", "战报", "势力", "同盟"),
                "REPORT_PVE" to listOf("守军", "贼兵", "贼寇", "黄巾", "野地", "试炼", "据点", "流寇", "匪"),
                "REPORT_PVP" to listOf("同盟", "集结", "会战", "攻城", "玩家", "军团", "PVP", "赛季战报", "攻方部队"),
                "KNOWN_SKILLS" to listOf(
                    "战必断金", "反计之策", "神兵天降", "大赏三军", "浑水摸鱼",
                    "妖术", "垒实迎击", "健卒不殆", "始计", "避其锋芒", "绝水遏敌",
                    "先驱突击", "单骑救主", "磐阵善守", "疾击其后", "枭雄",
                    // 下面两条原先只写在 EdgeSlmEngine 的私有战法字典里（该字典已删）：
                    // 不补进知识包，战报里出现它们就会不再被识别。
                    "空城", "步步为营"
                ),
                "KNOWN_HEROES" to listOf(
                    "马超", "魏延", "曹操", "吕蒙", "陆逊", "周瑜", "关银屏",
                    "刘备", "赵云", "皇甫嵩", "荀彧", "郭嘉", "贾诩", "张机", "孙权", "马岱", "徐庶", "关羽"
                ),
                "COMMAND_AMPLIFY_SKILLS" to listOf("神兵天降", "大赏三军", "避其锋芒", "始计", "绝水遏敌")
            ),
            // 武将基础速度（**量级参考**，不含装备/加点/阵营与战法加成）：
            // 只在战报读不到速度数字时用于定序，结论一律标注"需属性面板实测"。
            heroBaseSpeed = mapOf(
                "马超" to 83, "关银屏" to 82, "周瑜" to 80, "吕布" to 79, "吕蒙" to 79,
                "陆逊" to 79, "贾诩" to 79, "徐庶" to 79, "赵云" to 78, "关羽" to 76,
                "荀彧" to 76, "马岱" to 76, "魏延" to 76, "张飞" to 74, "皇甫嵩" to 74,
                "黄忠" to 74, "孙权" to 71, "曹操" to 70, "郭嘉" to 70, "张机" to 70,
                "刘备" to 68
            ),
            // 守军机制字样 → 命中后的解读（战报分流用）。
            pveMechanicNotes = mapOf(
                "暴走" to "守军触发【暴走】：无差别攻击，我方阵型会被自己人打乱，需带解控或提高容错。",
                "反击" to "守军带【反击】机制：我方普攻会被反伤，建议改用主动/战法输出或降低普攻比例。",
                "狂怒" to "守军进入【狂怒】状态：后段伤害显著抬升，务必在前 3 回合建立优势。",
                "免疫" to "守军带【免疫】：控制类战法对其无效，不要指望靠封普攻取胜。",
                "守军未溃" to "守军未溃：本轮未能清干净，需要补刀。"
            )
        )
    }
}
