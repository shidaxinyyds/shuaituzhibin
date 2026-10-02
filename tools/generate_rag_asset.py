#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成《率土之滨》与 SLG 端侧 RAG 知识库向量二进制资产
产物落位: client/app/src/main/assets/models/slg_knowledge_vector_hnsw.bin
包含: 全等级土地守军天梯打分、核心战法冲突克制、同盟军令战术操作手册及 64 维稠密特征向量
"""

import os
import struct
import math
import hashlib

# 知识库全量条目
KNOWLEDGE_ITEMS = [
    # ------------------ 3级土地守军 ------------------
    {
        "id": "DEF-LV3-001",
        "category": "DEFENDER_LAND",
        "title": "3级地守军-邓茂阵容",
        "keywords": "邓茂,3级地,三级地,软柿子,白给,新手开荒",
        "content": "3级地最弱守军之一。主将邓茂四维属性极低，无强力爆发战法，毫无反伤手段，前两回合即可被我军主力秒杀。",
        "advice": "🟢 难度评级: D(白给) | 推荐指数: ★★★★★ | 兵力建议: 1200+ | 战损通常在 30 兵以内，首选开荒经验包。"
    },
    {
        "id": "DEF-LV3-002",
        "category": "DEFENDER_LAND",
        "title": "3级地守军-田续阵容",
        "keywords": "田续,3级地,三级地,软柿子,白给",
        "content": "田续身板脆弱，自带战法发动率低下，普攻伤害轻微，前锋无法对我方形成实质威胁。",
        "advice": "🟢 难度评级: D(白给) | 推荐指数: ★★★★★ | 兵力建议: 1200+ | 极力推荐首批开荒打下作为跳板。"
    },
    {
        "id": "DEF-LV3-003",
        "category": "DEFENDER_LAND",
        "title": "3级地守军-管亥阵容",
        "keywords": "管亥,3级地,狂暴,吸血,战损偏高",
        "content": "管亥自带狂暴吸血与连击战法，普攻附带倒戈效果，是3级地中战损偏高的硬茬。",
        "advice": "🟡 难度评级: C(偏硬) | 推荐指数: ★★☆☆☆ | 兵力建议: 1500+ | 若有软柿子建议避让，避免开荒损兵断节奏。"
    },

    # ------------------ 4级土地守军 ------------------
    {
        "id": "DEF-LV4-001",
        "category": "DEFENDER_LAND",
        "title": "4级地守军-审配阵容",
        "keywords": "审配,4级地,四级地,软柿子,首选开荒",
        "content": "审配自带微量防御战法，全队无任何控制战法与硬核爆发，整体输出极低，是 4 级地最稳开荒目标。",
        "advice": "🟢 难度评级: D(白给) | 推荐指数: ★★★★★ | 兵力建议: 2800~3000 | 4级地首开首选，战损最低。"
    },
    {
        "id": "DEF-LV4-002",
        "category": "DEFENDER_LAND",
        "title": "4级地守军-裴元绍阵容",
        "keywords": "裴元绍,4级地,四级地,草寇,软柿子",
        "content": "裴元绍防御极低，后排输出乏力，只要我军携带基础输出战法即可轻松速推拿下。",
        "advice": "🟢 难度评级: D(安全) | 推荐指数: ★★★★★ | 兵力建议: 3000+ | 适合开荒前期铺地与快速提升武将等级。"
    },
    {
        "id": "DEF-LV4-003",
        "category": "DEFENDER_LAND",
        "title": "4级地守军-张任阵容",
        "keywords": "张任,4级地,落凤,技穷,单点爆发,控制",
        "content": "张任自带【落凤】，造成高额单体物理伤害并附带计穷控制（封主动战法），容易打乱我军输出节奏导致减员。",
        "advice": "🟡 难度评级: B(较难) | 推荐指数: ★★☆☆☆ | 兵力建议: 3500+ | 建议中军前锋带减伤战法，兵力不足不建议首开。"
    },
    {
        "id": "DEF-LV4-004",
        "category": "DEFENDER_LAND",
        "title": "4级地守军-李儒阵容",
        "keywords": "李儒,4级地,怯战,禁疗,极度危险,菜刀克星,翻车点",
        "content": "4级地头号杀手！李儒自带【逆反毒杀】附带怯战（封锁普攻）与高额谋略伤害并附带禁疗，马超、皇甫嵩等菜刀直接哑火！",
        "advice": "🔴 难度评级: S(极度危险) | 推荐指数: ❌坚决避开 | 菜刀队碰之必翻车！严禁首开李儒，侦察探到立即换地！"
    },
    {
        "id": "DEF-LV4-005",
        "category": "DEFENDER_LAND",
        "title": "4级地守军-郭嘉阵容",
        "keywords": "郭嘉,4级地,十胜十败,强控,混乱,暴毙",
        "content": "郭嘉【十胜十败】具有极高概率群体混乱控制，使我军陷入自相残杀，极易造成开荒主力大营直接暴毙灭队。",
        "advice": "🔴 难度评级: S(极危) | 推荐指数: ❌坚决避开 | 控制机制极其恶心，兵力未达 4000+ 且无解控前切勿盲撞。"
    },

    # ------------------ 5级土地守军 ------------------
    {
        "id": "DEF-LV5-001",
        "category": "DEFENDER_LAND",
        "title": "5级地守军-李典徐晃阵容",
        "keywords": "李典,徐晃,5级地,五级地,软柿子,首选5级地",
        "content": "5级地公认最温和的开荒守军。李典辅助战法威胁微弱，徐晃属于慢热型物理，前3回合毫无爆发力，最适合速战速决。",
        "advice": "🟢 难度评级: C(软柿子) | 推荐指数: ★★★★★ | 兵力建议: 5500~6000 | 5级地首开第一首选，满士气即可稳过。"
    },
    {
        "id": "DEF-LV5-002",
        "category": "DEFENDER_LAND",
        "title": "5级地守军-魏续鲍信阵容",
        "keywords": "魏续,鲍信,5级地,五级地,低战损",
        "content": "魏续战法触发率低，鲍信仅提供少量奶量而缺乏硬控与高爆发，只要主力配置正常即可顺利拿下。",
        "advice": "🟢 难度评级: C(推荐) | 推荐指数: ★★★★☆ | 兵力建议: 5800+ | 开荒第二稳健目标，战损可控。"
    },
    {
        "id": "DEF-LV5-003",
        "category": "DEFENDER_LAND",
        "title": "5级地守军-法正阵容",
        "keywords": "法正,5级地,神谋,跳过准备,猝死点,翻车",
        "content": "5级地猝死雷区！法正战法使全队主动战法概率跳过准备回合，配合后排强力输出战法，一触即发造成大营瞬间空血！",
        "advice": "🔴 难度评级: S(翻车雷区) | 推荐指数: ❌坚决避开 | 极高概率翻车打平重伤，未开觉醒且兵力不足 7500 严禁出击。"
    },
    {
        "id": "DEF-LV5-004",
        "category": "DEFENDER_LAND",
        "title": "5级地守军-陈宫阵容",
        "keywords": "陈宫,5级地,智商反弹,点名爆发,高谋略",
        "content": "陈宫谋略成长极高，战法针对敌我双方释放战法频率追加高额谋略伤害，智商低下或高频主动队伍会被反死。",
        "advice": "🔴 难度评级: S(极度危险) | 推荐指数: ❌不建议打 | 极易导致主力重伤残废，开荒期断节奏元凶。"
    },
    {
        "id": "DEF-LV5-005",
        "category": "DEFENDER_LAND",
        "title": "5级地守军-魏延阵容",
        "keywords": "魏延,5级地,奇兵奇谋,大营暴毙,切后排",
        "content": "魏延【奇兵奇谋】直接越过前锋中军跨位直切大营，常常在前锋血量充沛时我方大营已直接归零暴毙！",
        "advice": "🔴 难度评级: S(极危爆头) | 推荐指数: ❌避开 | 专克脆皮大营阵容，除非大营佩戴空城/战必，否则坚决绕道。"
    },
    {
        "id": "DEF-LV5-006",
        "category": "DEFENDER_LAND",
        "title": "5级地守军-陆逊庞统阵容",
        "keywords": "陆逊,庞统,5级地,火烧连营,锁链反噬",
        "content": "陆逊火烧连营多段灼烧引爆，庞统密谋伤害传导，两者皆属于群体毁灭性法术伤害，极易造成三武将同时全灭。",
        "advice": "🔴 难度评级: S(核弹法伤) | 推荐指数: ❌坚决避开 | 开荒期绝对不碰法系AOE，战损代价极高。"
    },

    # ------------------ 6/7/8级高级土地 ------------------
    {
        "id": "DEF-LV6-001",
        "category": "DEFENDER_LAND",
        "title": "6级地守军双队机制与阵容",
        "keywords": "6级地,六级地,双队守军,华雄,张勋,周仓,吕蒙,贾诩",
        "content": "6级地开始出现第二队守军（各16000兵力，打平会连战）。华雄/张勋为相对较软守军；若遇吕蒙（开局封普攻）或贾诩（算无遗策）易翻车。",
        "advice": "⚠️ 6级地要求主力 16000+ 兵力，且必须在旁边起要塞并配备第二队 8000+ 斯巴达补刀队防平局超时。"
    },
    {
        "id": "DEF-LV7-001",
        "category": "DEFENDER_LAND",
        "title": "7级地攻坚与防翻车策略",
        "keywords": "7级地,七级地,于禁,纪灵,要塞攻打,士气120",
        "content": "7级地双队总兵力 42000，守军战法配置大幅提升。于禁纪灵较为慢热适合攻打；陆逊庞统极易团灭我军。",
        "advice": "⚠️ 必须起要塞调动主力，等士气恢复至 120 满士气增益出征，主队兵力需 22000+，并备副队压秒触敌补刀。"
    },
    {
        "id": "DEF-LV8-001",
        "category": "DEFENDER_LAND",
        "title": "8/9级地与要塞同盟协同",
        "keywords": "8级地,9级地,高级土地,要塞协同,同盟集火",
        "content": "8级地以上守军具备完整主战法与高级战法联动，单队强攻战损过大，通常采用双主力轮番消耗或同盟卡秒集火。",
        "advice": "⚠️ 建议同盟协同出击，先锋探路摸清战法后，主力破首队，拆迁队压秒收割耐久。"
    },

    # ------------------ 核心战法联动与克制 ------------------
    {
        "id": "SKL-SYN-001",
        "category": "SKILL_SYNERGY",
        "title": "战必断金与反计之策（双封体系）",
        "keywords": "战必断金,反计之策,双封,怯战,犹豫,封普攻,封主动",
        "content": "率土最经典双封防御体系：战必断金前3回合高概率封锁普攻（怯战），反计之策前3回合大幅降低主动战法伤害并封首回合主动（犹豫）。",
        "advice": "克制菜刀与主动法刀的核心。若敌方配置双封，我军菜刀需配【枭雄】洞察免控，主动队需靠指挥/被动战法（如垒实、始计）度过前3回合。"
    },
    {
        "id": "SKL-SYN-002",
        "category": "SKILL_SYNERGY",
        "title": "神兵天降与大赏三军（法刀核弹体系）",
        "keywords": "神兵天降,大赏三军,法刀,爆发,前3回合增伤,吕蒙,张机",
        "content": "神兵天降降低敌方前3回合受到的谋略伤害抗性，大赏三军大幅提升我方前3回合造成的攻击与谋略伤害，乘法叠加造成毁灭性开局秒杀。",
        "advice": "法刀核心发动机。被克制手段：敌方带【反计之策】压制主动，或带【避其锋芒】、【无心恋战】针对性削减前3回合伤害。"
    },
    {
        "id": "SKL-SYN-003",
        "category": "SKILL_SYNERGY",
        "title": "垒实迎击与健卒不殆（肉步反击续航）",
        "keywords": "垒实迎击,健卒不殆,肉步,皇甫嵩,减伤,解控,规避,援护",
        "content": "受到普攻时，垒实迎击有高概率解除自身负面状态、进入规避免伤并援护友军，健卒不殆提供高额减伤与反击，两者结合身板极硬且源源不断反击。",
        "advice": "肉步防御天花板。克制方法：配置【绝水遏敌】、【逆反毒杀】等强力禁疗战法，或使用法刀前3回合爆发直接打穿前锋。"
    },
    {
        "id": "SKL-SYN-004",
        "category": "SKILL_SYNERGY",
        "title": "浑水摸鱼与妖术（控制与解控链）",
        "keywords": "浑水摸鱼,妖术,混乱,暴走,解控,安抚军心,全军突击",
        "content": "浑水摸鱼使敌方陷入混乱（完全丧失行动回合），妖术使敌方陷入暴走（敌我不分自相残杀），是打乱敌方爆发节奏的神技。",
        "advice": "若战报显示被浑水/妖术严重控制，需为队伍辅助位换装【安抚军心】、【九锡黄龙】或【全军突击】及时净化负面状态。"
    },
    {
        "id": "SKL-SYN-005",
        "category": "SKILL_SYNERGY",
        "title": "士气系统与战力增减益机制",
        "keywords": "士气,120士气,满士气,远距离行军,战力衰减",
        "content": "2026征服赛季士气上限为120（标准100）。长途跋涉行军每走一段距离士气会线性下降，士气低于80时战法发动率与伤害大幅滑跌！",
        "advice": "远距离攻城或打地严禁直接远射！必须先调动到附近要塞驻扎，等待士气恢复至 120 满士气增益状态再发起出征。"
    },

    # ------------------ 同盟军令与战术模式 ------------------
    {
        "id": "TAC-DEC-001",
        "category": "TACTICAL_DECREE",
        "title": "同盟攻城压秒卡秒战术",
        "keywords": "压秒,攻城,卡秒,触城,主力,拆迁,同盟法令",
        "content": "全盟集火攻打郡城/关卡时，所有队伍必须在指定时间的第 00 秒同时触城。主力队伍在 00 秒清剿城池守军，拆迁队伍紧随其后在 01~05 秒削减耐久。",
        "advice": "执行要领：先在要塞看准行军耗时，将（目标时间 - 行军耗时）计算出精准发兵秒数，提前 5 分钟做好调动待命，严禁抢跑导致拆迁撞守军灭队！"
    },
    {
        "id": "TAC-DEC-002",
        "category": "TACTICAL_DECREE",
        "title": "极限卡免与破免战术",
        "keywords": "卡免,破免,免战倒计时,飞地,抢地,翻地",
        "content": "地块被占领后具有 1 小时免战期。敌军无法在此期间进攻。破免战术是在免战倒计时归零前的瞬间派遣部队出征，实现破免瞬间立刻翻地或接壤。",
        "advice": "执行要领：开启【卡免战术流】，设置目标地块与提前量，流水线会自动计算距离并在破免前精准出兵，压秒翻地切断敌军行军路线。"
    },
    {
        "id": "TAC-DEC-003",
        "category": "TACTICAL_DECREE",
        "title": "深夜防敌袭与决策C自动反击",
        "keywords": "敌袭,深夜巡检,偷家,防守反击,要塞驻守,决策C",
        "content": "深夜0点至7点为敌军夜战偷家高发期。管家通过 OCR 实时监控战场红线与警报，发现敌军行军路线后即刻触发警报并派遣精锐主力自动反击驻守。",
        "advice": "执行要领：睡前在悬浮窗【巡检】面板点击【开启敌袭巡检与反击】，勾选决策C自动拦截，主力保留足够体力，放心安睡。"
    },

    # ------------------ 主流阵型与战法流派克制 ------------------
    {
        "id": "TEAM-SYN-001",
        "category": "HERO_COUNTER",
        "title": "砍王队战术克制与反制（马超+魏延+曹操）",
        "keywords": "砍王,马超,魏延,曹操,先驱突击,疾击其后,菜刀",
        "content": "砍王队依赖马超高物理攻击与先驱突击前3回合连击，配合魏延奇谋直切敌方大营爆头。爆发力极强但惧怕怯战。",
        "advice": "克制方法：队伍前锋必带【战必断金】直接封锁前3回合普攻，或携带【磐阵善守】+【反计之策】有效化解开局爆发。"
    },
    {
        "id": "TEAM-SYN-002",
        "category": "HERO_COUNTER",
        "title": "东吴大都督（周瑜+陆逊+吕蒙）",
        "keywords": "都督,大都督,周瑜,陆逊,吕蒙,神兵大赏,火烧连营,白衣渡江",
        "content": "经典法刀爆发阵容。吕蒙开局白衣渡江封普攻并造成稳定法伤，陆逊多段引爆，周瑜控场。克制物理突击队但惧怕减伤与犹豫。",
        "advice": "克制方法：佩戴【反计之策】压制陆逊与周瑜主动战法，配合【避其锋芒】抗过前3回合神兵大赏爆发期。"
    },
    {
        "id": "TEAM-SYN-003",
        "category": "HERO_COUNTER",
        "title": "经典肉步与蜀步（关妹+刘备+赵云/皇甫嵩）",
        "keywords": "蜀步,肉步,关银屏,刘备,赵云,皇甫嵩,步步为营,桃园结义,自愈",
        "content": "依靠刘备皇裔流离高额急救回血与关妹稳定输出，拖入中后期打消耗战，身板极其坚韧。",
        "advice": "克制方法：带【绝水遏敌】或【逆反毒杀】等禁疗战法打断回血链条，或用法刀高爆发队伍在前3回合强杀关妹。"
    },
    {
        "id": "TEAM-SYN-004",
        "category": "HERO_COUNTER",
        "title": "魏智法术队（荀彧+郭嘉+贾诩）",
        "keywords": "魏智,荀彧,郭嘉,贾诩,驱虎吞狼,算无遗策,禁疗,谋略",
        "content": "高谋略智商压制队伍，荀彧驱虎吞狼附带禁疗，贾诩算无遗策专克高频主动队伍，郭嘉强力控场。",
        "advice": "克制方法：减少主动战法携带量（避免给贾诩送伤害），选用高爆发物理菜刀队配合免控【枭雄】速杀大营荀彧。"
    },
    {
        "id": "TEAM-SYN-005",
        "category": "HERO_COUNTER",
        "title": "流氓队体系（关妹/吕布+张机+孙权）",
        "keywords": "流氓队,张机,孙权,关妹,九锡黄龙,金匮要略,始计,浑水摸鱼",
        "content": "张机前3回合高额减伤与急救，孙权九锡黄龙解控并提供规避，关妹或鬼吕核心输出，容错率极高的万金油队伍。",
        "advice": "克制方法：使用高物理爆发贯穿队伍，或佩戴【战必断金】+【绝水遏敌】打断张机回血并在孙权开出解控前集火秒杀。"
    },
    {
        "id": "TEAM-SYN-006",
        "category": "HERO_COUNTER",
        "title": "蜀骑冲锋队（马岱+徐庶+关羽）",
        "keywords": "蜀骑,马岱,徐庶,关羽,谋定后动,战必断金,击势",
        "content": "马岱受队友普攻叠加攻击力爆发，徐庶高频暴走与连击快速给马岱叠层，关羽犹豫大范围控场，点名斩杀能力极强。",
        "advice": "克制方法：携带【战必断金】封锁徐庶叠层，或使用垒实迎击肉步援护抵挡马岱单点高额伤害。"
    }
]

def text_to_embedding(text, dim=64):
    """
    将中文文本映射为 64 维稠密特征向量
    通过加权分词哈希 + L2 归一化，具备严格确定性与余弦相似度可比性
    """
    vec = [0.0] * dim
    # 滑动窗口多粒度 n-gram
    chars = list(text.strip())
    tokens = []
    # 单字、双字、三字词
    for i in range(len(chars)):
        tokens.append(chars[i])
        if i + 1 < len(chars):
            tokens.append(chars[i] + chars[i+1])
        if i + 2 < len(chars):
            tokens.append(chars[i] + chars[i+1] + chars[i+2])
    
    for token in tokens:
        h = int(hashlib.md5(token.encode('utf-8')).hexdigest()[:8], 16)
        idx = h % dim
        weight = 1.0 + (len(token) * 0.5)
        # 根据哈希符号正负扰动
        sign = 1.0 if ((h >> 4) & 1) == 0 else -1.0
        vec[idx] += sign * weight

    # L2 归一化
    norm = math.sqrt(sum(x * x for x in vec))
    if norm > 1e-6:
        vec = [x / norm for x in vec]
    return vec

def build_binary_rag_asset(target_path):
    os.makedirs(os.path.dirname(target_path), exist_ok=True)
    
    # 头部: MAGIC(16B) + VERSION(4B) + ITEM_COUNT(4B) + DIMENSION(4B)
    magic = b"SLG_HNSW_RAG_V1\x00"
    version = 1
    item_count = len(KNOWLEDGE_ITEMS)
    dim = 64
    
    header = struct.pack("<16sIII", magic, version, item_count, dim)
    
    body = bytearray()
    for item in KNOWLEDGE_ITEMS:
        # 计算稠密向量
        full_text = f"{item['title']} {item['keywords']} {item['content']} {item['advice']}"
        emb = text_to_embedding(full_text, dim)
        
        # 编码字符串
        id_bytes = item['id'].encode('utf-8')
        cat_bytes = item['category'].encode('utf-8')
        title_bytes = item['title'].encode('utf-8')
        kw_bytes = item['keywords'].encode('utf-8')
        content_bytes = item['content'].encode('utf-8')
        advice_bytes = item['advice'].encode('utf-8')
        
        # 写入单个 Item
        item_header = struct.pack(
            "<IIIIII",
            len(id_bytes),
            len(cat_bytes),
            len(title_bytes),
            len(kw_bytes),
            len(content_bytes),
            len(advice_bytes)
        )
        body.extend(item_header)
        body.extend(id_bytes)
        body.extend(cat_bytes)
        body.extend(title_bytes)
        body.extend(kw_bytes)
        body.extend(content_bytes)
        body.extend(advice_bytes)
        
        # 写入 64 个 float32 向量值
        emb_bytes = struct.pack(f"<{dim}f", *emb)
        body.extend(emb_bytes)
    
    with open(target_path, "wb") as f:
        f.write(header)
        f.write(body)
    
    print(f"[OK] RAG vector asset built successfully: {target_path}")
    print(f"   - Items count: {item_count}")
    print(f"   - Vector dimension: {dim}")
    print(f"   - File size: {os.path.getsize(target_path)} bytes ({os.path.getsize(target_path) / 1024:.2f} KB)")

if __name__ == "__main__":
    out_file = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "client", "app", "src", "main", "assets", "models", "slg_knowledge_vector_hnsw.bin"
    )
    build_binary_rag_asset(out_file)
