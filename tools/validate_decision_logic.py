#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
决策逻辑的离线验证（守军评级 + 选队门槛）
========================================

选错队、评错级会**白送兵**，比"点不准"更伤，而且这类错误是**静默的**：
它会给出一个看起来合理的结论。因此这里不用"抽查几个例子"，而是**枚举整个输入空间**，
断言若干必须成立的性质。

本脚本复刻 `DefenderEvaluator` 与 `RoadPavingFlow.selectOptimalTroop` 的判定规则
（逐条对照 Kotlin 源码写成），然后检验：

守军评级
  P1 一条守军名字都没读到 → 必须是 UNKNOWN，且**推荐语里不得出现鼓励进攻的措辞**。
     （这正是本轮修掉的缺陷：空输入曾被判为 SAFE 并输出"推荐主力立刻出征收割"。）
  P2 读到至少一个守军 → 绝不停留在 UNKNOWN。
  P3 **单调性**：同一列表内，威胁评分越高，评级不得更低。
  P4 **列表下限**：结果不得低于"该武将所属列表"的危险度
     （防止"危险列表里的武将因低分被降级放过"）。
  P5 未知武将（不在任何列表里）取中等评级，不得被当成 SAFE。

选队门槛
  P6 只要体力/士气**已识别**且低于门槛，就绝不能被选中。
  P7 只在"确实无法识别"时才走"未验证但放行"的分支——该分支必须与"已识别且达标"可区分。
"""

import itertools
import os
import re
import sys

# ---------------------------------------------------------------- 从 Kotlin 读取真实规则
#
# ⚠️ 这一节是本脚本可信度的关键。
#
# 本脚本复刻的是 Kotlin 里的判定规则。如果只是"再抄一遍常量"，
# 那么一旦 Kotlin 改了而脚本没改，脚本就会**验证一个已经不存在的实现**，
# 而且它还会一路绿灯。
#
# 因此枚举权重、评分映射、推荐语文案这些**从源码里解析出来**，
# 而不是在这里重新声明。解析失败即报错退出（"响亮的失败"），
# 而不是悄悄回退到一份可能已经过期的副本。

_KOTLIN_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "client", "app", "src", "main", "java", "com", "stzb", "assistant",
)

_STR = r'"((?:[^"\\]|\\.)*)"'


def _read_kotlin(rel_path):
    with open(os.path.join(_KOTLIN_ROOT, rel_path), encoding="utf-8") as fh:
        return fh.read()


def parse_safety_tiers(text):
    """从 DefenderEvaluator.SafetyTier 解析 {名称: (描述, 权重)}。"""
    m = re.search(r"enum class SafetyTier\([^)]*\)\s*\{([\s\S]*?)\n\s*\}", text)
    if not m:
        raise RuntimeError("无法解析 SafetyTier 枚举——源码结构可能已变更")
    tiers = {}
    for mm in re.finditer(r"([A-Z][A-Z0-9_]*)\(" + _STR + r"\s*,\s*(\d+)\)", m.group(1)):
        tiers[mm.group(1)] = {"desc": mm.group(2), "weight": int(mm.group(3))}
    if len(tiers) < 4:
        raise RuntimeError(f"SafetyTier 只解析到 {len(tiers)} 个值，明显不对")
    return tiers


def parse_recommendations(text):
    """从 evaluate() 里解析 {评级: 推荐语}。"""
    m = re.search(r"val recommendation = when \(maxRiskTier\)\s*\{([\s\S]*?)\n\s*\}", text)
    if not m:
        raise RuntimeError("无法解析 recommendation 的 when——源码结构可能已变更")
    recs = {}
    for mm in re.finditer(r"SafetyTier\.([A-Z][A-Z0-9_]*)\s*->\s*" + _STR, m.group(1)):
        recs[mm.group(1)] = mm.group(2)
    if len(recs) < 4:
        raise RuntimeError(f"推荐语只解析到 {len(recs)} 条，明显不对")
    return recs


def parse_tier_for_score(text):
    """从 tierForScore 解析 (规则列表, 默认值)。"""
    m = re.search(r"fun tierForScore\(score: Int\): SafetyTier = when\s*\{([\s\S]*?)\n\s*\}",
                  text)
    if not m:
        raise RuntimeError("无法解析 tierForScore——源码结构可能已变更")
    body = m.group(1)
    rules = []
    for mm in re.finditer(r"score <= (\d+)\s*->\s*SafetyTier\.([A-Z][A-Z0-9_]*)", body):
        rules.append(("le", int(mm.group(1)), mm.group(2)))
    for mm in re.finditer(r"score == (\d+)\s*->\s*SafetyTier\.([A-Z][A-Z0-9_]*)", body):
        rules.append(("eq", int(mm.group(1)), mm.group(2)))
    dm = re.search(r"else\s*->\s*SafetyTier\.([A-Z][A-Z0-9_]*)", body)
    if not rules or dm is None:
        raise RuntimeError("tierForScore 的规则解析不完整")
    return rules, dm.group(1)


def parse_unknown_tier_and_default(text):
    """解析"空输入返回哪个评级"以及"未知武将取哪个评级"。"""
    um = re.search(r"tier = SafetyTier\.([A-Z][A-Z0-9_]*),\s*\n\s*matchedDefenders = emptyList",
                   text)
    dm = re.search(r"return DefenderMatch\(name, SafetyTier\.([A-Z][A-Z0-9_]*), "
                   r'"常规守军"', text)
    if um is None or dm is None:
        raise RuntimeError("无法解析空输入评级或未知武将默认评级")
    return um.group(1), dm.group(1)


def parse_find_match_order(text):
    """
    从 `findMatch` 里解析**武将列表的检索顺序**与每个列表对应的下限评级。

    为什么连顺序也要解析：顺序是规则的一部分。
    它决定了"同时命中多个列表的武将"按哪个列表定级——
    例如把 `safeHeroes` 挪到 `dangerHeroes` 前面，一个既在危险表又在软柿子表里的
    武将就会按软柿子处理。这种改动在代码评审里很容易被忽略，但后果是白送兵。
    """
    m = re.search(r"private fun findMatch\([\s\S]*?\n\s{4}\}", text)
    if m is None:
        raise RuntimeError("无法定位 findMatch 函数体——源码结构可能已变更")
    body = m.group(0)
    order = []
    for mm in re.finditer(
        r"for \(hero in db\.(\w+)\)\s*\{[\s\S]*?resolveTier\(SafetyTier\.([A-Z][A-Z0-9_]*),",
        body,
    ):
        # `dangerHeroes` -> `danger`，与脚本里的合成知识库键名对齐
        list_key = re.sub(r"Heroes$", "", mm.group(1))
        order.append((list_key, mm.group(2)))
    if len(order) < 3:
        raise RuntimeError(f"findMatch 只解析到 {len(order)} 个列表，明显不对")
    return order


def parse_aggregation_start(text):
    """
    解析聚合用的起始评级（`var maxRiskTier = SafetyTier.X`）与比较运算符。

    这条断言直接守着我修掉的那个 bug：
    起始值若被改回 `SAFE`，"一个守军都没读到"就会重新变成
    「🟢 推荐主力立刻出征收割」。因此这里要求起始值必须是**权重最小**的那个评级。
    """
    m = re.search(r"var maxRiskTier = SafetyTier\.([A-Z][A-Z0-9_]*)", text)
    if m is None:
        raise RuntimeError("无法解析 maxRiskTier 的起始值——源码结构可能已变更")
    op = re.search(r"if \(match\.tier\.weight\s*(>=|>)\s*maxRiskTier\.weight\)", text)
    return m.group(1), (op.group(1) if op else None)


# ---- 载入并断言解析成功（失败即红，不静默） ----
_DE_TEXT = _read_kotlin(os.path.join("ocr", "DefenderEvaluator.kt"))
TIERS = parse_safety_tiers(_DE_TEXT)
RECOMMENDATION = parse_recommendations(_DE_TEXT)
_TIER_RULES, _TIER_DEFAULT = parse_tier_for_score(_DE_TEXT)
UNKNOWN_TIER, UNKNOWN_HERO_TIER = parse_unknown_tier_and_default(_DE_TEXT)
FIND_MATCH_ORDER = parse_find_match_order(_DE_TEXT)
AGGREGATION_START, AGGREGATION_OP = parse_aggregation_start(_DE_TEXT)
WEIGHT = {name: info["weight"] for name, info in TIERS.items()}

# 鼓励进攻的措辞——这些词绝不能出现在"没读到守军"的结论里
ENCOURAGING_WORDS = ["推荐", "立刻", "收割", "稳健拿下", "即可拿下"]


def tier_for_score(score):
    """按**从源码解析出的规则**判定评分对应的评级。"""
    for kind, bound, tier in _TIER_RULES:
        if kind == "le" and score <= bound:
            return tier
        if kind == "eq" and score == bound:
            return tier
    return _TIER_DEFAULT


def resolve_tier(list_floor, threat_score):
    """复刻 DefenderEvaluator.resolveTier：取更危险的一侧。"""
    by_score = tier_for_score(threat_score)
    return by_score if WEIGHT[by_score] >= WEIGHT[list_floor] else list_floor


def evaluate(names, lists):
    """
    复刻 DefenderEvaluator.evaluate。
    lists: {"danger": [(name, score)], "hard": [...], "safe": [...], "moderate": [...]}
    未知武将的默认评级、空输入评级、起始值都**取自源码解析结果**，不在这里另写一份。
    """
    if not any(n.strip() for n in names):
        return UNKNOWN_TIER, 0.0, RECOMMENDATION[UNKNOWN_TIER], 0

    found = []
    for raw in names:
        clean = raw.strip().replace(" ", "")
        hit = None
        # 检索顺序与每个列表的下限评级**取自源码解析结果**（见 FIND_MATCH_ORDER）
        for list_key, floor in FIND_MATCH_ORDER:
            for hname, score in lists.get(list_key, []):
                if clean in hname or hname in clean:
                    hit = resolve_tier(floor, score)
                    break
            if hit:
                break
        if hit is None:
            hit = UNKNOWN_HERO_TIER      # 源码里"未匹配到任何列表"的默认分支
        found.append(hit)

    max_tier = UNKNOWN_TIER              # 源码从 UNKNOWN（权重 0）起算
    total = 0
    for t in found:
        total += WEIGHT[t]
        if WEIGHT[t] > WEIGHT[max_tier]:
            max_tier = t
    return max_tier, float(total), RECOMMENDATION[max_tier], len(found)


def _balanced_body(text, anchor):
    r"""取锚点之后第一个 `{ … }` 配对块的内容（按括号深度扫描）。"""
    try:
        i = text.index(anchor)
    except ValueError:
        raise RuntimeError("无法在源码里定位锚点：%s——源码结构可能已变更" % anchor)
    j = text.index("{", i)
    depth = 0
    for k in range(j, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[j + 1:k]
    raise RuntimeError("锚点 %s 之后的花括号不配对，无法提取函数体" % anchor)


def parse_select_optimal_troop(text):
    """从 RoadPavingFlow.kt 真实源码里提炼 selectOptimalTroop 的结构性质。

    为什么必须解析源码而不是在本文件里另抄一份判据：本验证器的全部价值在于
    “源码改了、这里立刻红”。如果只在 Python 里重写一个理想版本，有人把源
    码里的士气优选改回去，这份验证器还会一路绿灯——那比没有验证器更危险。
    定位不到函数体时直接报错退出，而不是默认“性质都成立”。
    """
    body = _balanced_body(text, "suspend fun selectOptimalTroop(")
    return {
        # 资格判据必须还是那一条，不能被“优选”顺手改掉。
        "qualifier_unchanged": bool(re.search(
            r"if\s*\(\s*stamina\s*>=\s*needStamina\s*&&\s*morale\s*>=\s*minMorale\s*\)", body)),
        "uses_morale_grade": "moraleGrade" in body,
        "has_low_penalty_branch": "LOW_PENALTY" in body,
        # 全为打折队时必须回落到兜底队，而不是谎报“无人可用”。
        "falls_back_instead_of_refusing": bool(re.search(
            r"return\s+lowMoraleFallback", body)),
        # 读不出数值时仍按“未验证放行”处理（这条旧行为不许被改成静默淘汰）。
        "keeps_unverified_passthrough": bool(re.search(r"return\s+slot", body)),
    }


_ROAD_SRC = _read_kotlin(os.path.join("tactics", "RoadPavingFlow.kt"))
SELECT_TROOP_FACTS = parse_select_optimal_troop(_ROAD_SRC)


def _strip_comments(text):
    """去掉 `// …` 与 `/* … */`，只留代码。

    这几条结构不变量里既有"必须出现"也有"不许出现"：本文件里有多处注释在
    **叙述一段历史**（"旧实现写了 continue"、"旧日志把体力>=20 刷成固定文案"），
    不剔掉注释的话，这些注释自己就会把"不许出现"的判定推红——而一个会因为
    写了说明就误报的闸门，接下去一定有人把说明删掉，而不是把代码改对。
    副作用：字符串里的 `//`（如 URL）也会被剔掉；本文件没有这类字串。
    """
    return re.sub(r"/\*[\s\S]*?\*/|//[^\n]*", "", text)


def parse_paving_flow_facts(text):
    r"""从 RoadPavingFlow.kt 提炼"铺路不许跳格"的结构不变量。

    为什么这条必须有：第 N+1 块地的出征依托是第 N 块已经翻下来的地，跳过一格
    会让后面所有地块失去依托——而循环还能若无其事地"跑完"并汇报成功。这种
    缺陷不会编译报错，也不会让任何单测变红，只能靠对源码结构的反照守住。

    历史基线：旧实现正是在体力不足时写了 `delay(3 * 60 * 1000L); continue`，
    而 continue 在逐格循环里等于前进到下一格。
    """
    raw = text                      # “不许出现”的数据断言连注释一起查
    text = _strip_comments(text)    # 结构性质只看代码，不让历史说明自己把自己推红
    loop = _balanced_body(text, "suspend fun startPaving(")
    tile = _balanced_body(text, "private suspend fun paveOneTile(")
    waiter = _balanced_body(text, "private suspend fun awaitQualifiedTroop(")
    occupied_at = loop.find("TileOutcome.OCCUPIED")
    advance_at = loop.find("tileIndex++")
    return {
        # 指针只前进一次，且只能在"已确认占领"之后。
        "single_advance_site": loop.count("tileIndex++") == 1,
        "advance_only_after_confirmed_occupation": 0 <= occupied_at < advance_at,
        # 主循环里不许再出现任何 continue（它在这里的语义就是跳格）。
        "no_continue_in_tile_loop": not re.search(r"\bcontinue\b", loop),
        # 瞬时故障必须是"原地重试 + 预算用尽即中止"，不能无声滑过。
        "retries_in_place_then_aborts": "tileAttempt++" in loop and "abortPaving(" in loop,
        # 用户中途终止不能被播报成"任务圆满完成"。
        "user_stop_is_not_success": "Status.INTERRUPTED" in loop,
        # 等队必须发生在"本格内部"，且真的回面板重读一遍。
        "waits_inside_this_tile": "awaitQualifiedTroop(" in tile and "selectOptimalTroop(" in waiter,
        # 未确认占领绝不能计入成功，只能中止。
        "unconfirmed_is_not_counted": (
            bool(re.search(r"if\s*\(\s*!occupied\s*\)", tile))
            and "return TileOutcome.OCCUPATION_UNCONFIRMED" in tile
        ),
        # 门槛从知识库取，不写进日志话术（旧话术写死 20/100，夜间实际是 40）。
        "thresholds_from_knowledge_base": (
            "requiredStaminaNow()" in tile and "config.minMoraleThreshold" in tile
        ),
        "no_hardcoded_gate_in_tile_log": "体力>=20" not in tile and "均不足 20" not in tile,
        # 无来源的数据断言不许回来（体力回复速率、满士气增伤百分比）。
        # 这一条用 raw：注释里出现它同样是在宣称一条无依据的游戏规律，
        # 而下一个读代码的人会把它当成可以依赖的事实。
        "no_unsourced_numbers_in_file": (
            "3 分钟回 1 点" not in raw and "增伤 16%" not in raw
        ),
    }


PAVING_FLOW_FACTS = parse_paving_flow_facts(_ROAD_SRC)


def select_troop(candidates, need_stamina, min_morale, morale_standard=None):
    """
    复刻 RoadPavingFlow.selectOptimalTroop。
    candidates: [(slot, stamina|None, morale|None)]，按候选顺序。
    返回 (选中的 slot | None, 原因)：原因用于区分"已验证达标"与"未验证放行"。

    morale_standard 对应知识库的基准士气 (GameRules.moraleStandard)：
    达标但低于它的队**不会被淘汰**，只是降级为兜底（源码里的 MoraleGrade.LOW_PENALTY
    分支）。传 None 表示不做这一层优选（老行为）。

    这个参数不是摆设：三战 minMoraleForPaving=80 < moraleStandard=100，所以确实存
    在“达标但战力已打折”的队；率土两者都是 100，故优选在率土上是安全的空转。
    """
    fallback = None
    for slot, stamina, morale in candidates:
        if stamina is None or morale is None:
            return slot, "unverified"      # 源码：无法识别 → 记为未验证并放行
        if stamina >= need_stamina and morale >= min_morale:
            if morale_standard is None or morale >= morale_standard:
                return slot, "verified_ok"
            if fallback is None:
                fallback = slot            # 达标但士气打折：先记下，继续找更好的
        # 否则不达标，跳过
    if fallback is not None:
        return fallback, "verified_low_morale_fallback"
    return None, "none_qualified"


def simulate_paving(outcome_table, max_attempts=3):
    """复刻 startPaving 的逐格推进语义。

    outcome_table: {格号: [该格各次尝试的结果]}；次数超出时沿用最后一项。
    返回 (尝试过的格序列, 已确认占领的格序列, 结论 DONE|ABORT)。

    这个镜像只用来**枚举不变量**（见 P15）：证明在这套调度规则下，
    "跳过一格"与"汇报成功"不可能同时发生。它是否与源码一致，由 P14 负责。
    """
    tried = []
    confirmed = []
    index = 0
    attempt = 0
    total = len(outcome_table)
    while index < total:
        seq = outcome_table[index]
        outcome = seq[min(attempt, len(seq) - 1)]
        tried.append(index)
        if outcome == "OCCUPIED":
            confirmed.append(index)
            index += 1
            attempt = 0
        elif outcome in ("OCCUPATION_UNCONFIRMED", "NO_QUALIFIED_TROOP"):
            return tried, confirmed, "ABORT"
        else:  # TRANSIENT_FAILURE
            attempt += 1
            if attempt >= max_attempts:
                return tried, confirmed, "ABORT"
    return tried, confirmed, "DONE"


def simulate_paving_legacy(outcome_table):
    """旧语义（已被修掉）：每格只试一次，无论结果都前进，跑完无条件报成功。

    保留它不是为了文档，而是为了让 P15b 能证明"这套不变量真的能咬住旧行为"——
    如果验证器对旧实现也绿灯，那它对新实现同样一文不值。
    """
    tried = list(range(len(outcome_table)))
    confirmed = [i for i in sorted(outcome_table) if outcome_table[i][0] == "OCCUPIED"]
    return tried, confirmed, "DONE"


def paving_violations(tried, confirmed):
    """返回"上一格没被确认就前进"的违规点（相邻性不变量）。

    为什么不是简单看格号有没有空洞：旧实现每次只 +1，格号序列根本没有空洞；
    真正的破坏在于它是"没成功也前进"。所以判据必须落在"前进的前提"上：
    只有前一格已被**确认占领**（原地重试则永远合规），才允许去试下一格。
    """
    confirmed_set = set(confirmed)
    bad = []
    for prev, cur in zip(tried, tried[1:]):
        if cur == prev:
            continue          # 原地重试同一格：合规
        if cur != prev + 1 or prev not in confirmed_set:
            bad.append((prev, cur))
    return bad


# ---------------------------------------------------------------- RAG 检索与守军安全判定（P4）
#
# 这一节管两件会直接导致“送兵”的事：
#   1. 检索排序——排错序等于给玩家念错药方；
#   2. “这块地能不能打”的判定——它真的流向 SoftTileRadarFlow 的选地。
# 权重、阈值、语汇表一律从 SlgRagEngine.kt **解析**出来；解析不到就抛异常。

_RAG_SRC = _read_kotlin(os.path.join("ai", "rag", "SlgRagEngine.kt"))


def parse_rag_facts(text):
    """把检索与安全判定的真实规则从源码里抠出来（不在本脚本里重新声明一份）。"""
    m = re.search(r"private const val EXACT_HIT_WEIGHT\s*=\s*([\d.]+)f", text)
    if not m:
        raise AssertionError("解析失败：SlgRagEngine.kt 里没有 EXACT_HIT_WEIGHT")
    exact_w = float(m.group(1))

    m = re.search(r"private const val MIN_DENSE_SIMILARITY\s*=\s*([\d.]+)f", text)
    if not m:
        raise AssertionError("解析失败：SlgRagEngine.kt 里没有 MIN_DENSE_SIMILARITY")
    dense_min = float(m.group(1))

    m = re.search(r"CORPUS_TROOP_CONTEXT\s*=\s*listOf\(([^)]*)\)", text)
    if not m:
        raise AssertionError("解析失败：没有 CORPUS_TROOP_CONTEXT 语汇表")
    ctx_words = re.findall(_STR, m.group(1))
    if not ctx_words:
        raise AssertionError("解析失败：CORPUS_TROOP_CONTEXT 是空的")

    body = _balanced_body(text, "fun search(query: String")
    guard = _balanced_body(text, "fun queryLandDefender(")
    seed = _balanced_body(text, "private fun loadBuiltinSeedKnowledge()")
    sanitizer = _balanced_body(text, "private fun stripCorpusTroopNumbers(")
    # “不许出现”类判据只看代码：本文件里多处注释在**叙述旧实现长什么样**（包括
    # “旧判定里有一条 advice.contains("白给")”），拿它们去撞红线只会让人删说明而不是改代码。
    guard_code = _strip_comments(guard)

    exact_gate = body.find("if (exact.isNotEmpty()) return exact.take(topK)")
    dense_return = body.find(".filter { it.score >= MIN_DENSE_SIMILARITY }")
    is_safe_line = re.search(r"val isSafe = ([^\n]*)", guard)
    noblock_line = re.search(r"val noHardBlock = ([^\n]*)", guard)
    modok_line = re.search(r"val moderateOk = ([^\n]*)", guard)
    advice_call = guard.find("best?.entry?.advice?.let { stripCorpusTroopNumbers(it) }")

    return {
        "exact_hit_weight": exact_w,
        "dense_min": dense_min,
        "ctx_words": ctx_words,
        # —— 检索面
        "exact_channel_precedes_dense": 0 <= exact_gate < dense_return,
        "dense_floor_applied": dense_return >= 0,
        "no_weighted_sum_left": ("kwBoost" not in body) and ("0.65f" not in body),
        "keyword_hits_counted": "entry.keywords.count" in body,
        # —— 判定面
        "corpus_text_not_in_safety": "advice.contains" not in guard_code,
        # 安全判定由三道独立闸门合成。把它们拆开校，而不是校“moderate 一刀切否决”：
        # 知识库自己就把 徐晃/鲍信 同时列在 moderate 档与 Lv5 推荐名单里（Lv5 是开荒分水岭），
        # 全局档位一刀切会让雷达跳过 KB 亲自推荐去首开的地。等级表比全局档位更具体。
        "safety_composed_of_three_gates": bool(is_safe_line) and all(
            k in is_safe_line.group(1) for k in ("kbSaysSafe", "noHardBlock", "moderateOk")),
        "hard_and_danger_always_block": bool(noblock_line)
            and "hitDanger == null" in noblock_line.group(1)
            and "hitHard == null" in noblock_line.group(1),
        "moderate_rescued_only_by_this_level": bool(modok_line)
            and "levelWhitelisted != null" in modok_line.group(1)
            and "levelBlacklisted == null" in modok_line.group(1),
        "blacklist_beats_whitelist": "levelBlacklisted == null &&" in guard,
        "positive_evidence_required": "(hitSafe != null || levelWhitelisted != null)" in guard,
        "four_tiers_read": all(k in guard for k in
                               ("dangerHeroes", "hardHeroes", "moderateHeroes", "safeHeroes")),
        # —— 数据面
        "corpus_numbers_neutralized": advice_call >= 0,
        "kb_number_backfilled": "推荐出兵" in guard and "minSoldiers" in guard,
        "seed_free_of_troop_numbers": not re.search(r"\d{3,6}", _strip_all(seed)),
        # 区间写法“5500~6000”会被抹成两个占位词，源码里必须有收敛回一句的替换，
        # 否则念给玩家的是“以知识库为准~以知识库为准”这种废话。
        "collapse_present": 'replace("以知识库为准~以知识库为准", "以知识库为准")' in sanitizer,
    }


def _strip_all(text):
    """剔注释后的代码（这里还要剔掉标识符里的数字，如 SEED-01 的编号）。"""
    t = _strip_comments(text)
    return re.sub(r"SEED-\d+", "", t)


_RAG = parse_rag_facts(_RAG_SRC)


# ---------------------------------------------------------------- 军令执行链路事实（P6）
#
# 这一节管的是全项目最贵的一条路：**军令原文 → 解析 → 安全闸 → 下发点击**。
# 这里走错的代价不是“显示不好看”，而是部队跑到地图另一端。
# 所以每条判据都从源码里解析，不接受“脚本里另抄一份理想规则”——
# 否则有人把“模型结论覆盖原文坐标”改回去，这个验证器还会一路绿灯。

_DECREE_SRC = _read_kotlin(os.path.join("ai", "microbrain", "EdgeSlmEngine.kt"))
_INTENT_MODEL_SRC = _read_kotlin(os.path.join("ai", "microbrain", "IntentSlotModel.kt"))
_SAFETY_GATE_SRC = _read_kotlin(os.path.join("ai", "decision", "DualTrackSafetyGate.kt"))
_ORDER_CONTRACT_SRC = _read_kotlin(os.path.join("ai", "microbrain", "TacticalOrder.kt"))


def parse_decree_pipeline_facts(ede, ism, gate, contract):
    """把“模型能改什么、不能改什么”从四个源文件里抽成可判定的事实。

    只看代码、不看注释（沿用 `_strip_comments` 的教训）：本项目的注释里大量在
    **叙述被否决的旧写法**（“旧实现是 0.80f + 0.18f”、“旧写法写死了 20”），
    若不剔注释，这些说明会把“不许出现”的判定自己推红；一个写了解释就误报的闸门，
    接下来一定有人删解释而不是改代码。
    """
    ede, ism, gate, contract = (_strip_comments(x) for x in (ede, ism, gate, contract))
    f = {}

    # 1) 坐标只能来自原文。模型输出是 10×10 桶的**桶心**（一格 60、只覆盖 0..600），
    #    拿它覆盖正则读到的精确坐标，会把部队派到最多偏 ±60 格的错地方，
    #    而桶心永远落在 [1,1500] 里，边界检查抓不到。
    f["coord_from_original_text"] = (
        "val effCoord = targetCoord" in ede and "modelParse?.coord ?:" not in ede
    )

    # 2) 模型给的目标名必须被原文印证（否则“宛城”会被模型的“洛阳”顶掉）。
    target_body = _balanced_body(ede, "val modelTarget = modelParse?.target?.takeIf")
    f["model_target_requires_literal_evidence"] = (
        "cleanText.contains(it)" in target_body and "isConcreteTargetName()" in target_body
    )

    # 3) 意图：正则命中即字面证据，模型只能补位（优先级写死在源码里）。
    f["regex_intent_wins_over_model"] = bool(
        re.search(r"val effIntent = regexIntent \?: modelParse\?\.intent", ede)
    )

    # 4) 正则一个关键词都没命中时必须交回 null，而不是硬塞“全盟攻城/集火”。
    deduce = _balanced_body(ede, "private fun deduceIntentOrNull(")
    f["regex_intent_may_be_null"] = (
        bool(re.search(r"else\s*->\s*null", deduce))
        and "else -> OrderIntent.ALLIANCE_SIEGE" not in deduce
    )

    # 5) 置信度只按证据累加：底数/上限/逐项增量全部从源码取。
    ev = _balanced_body(ede, "private fun evidenceConfidence(")
    base = re.search(r"var conf = ([\d.]+)f", ev)
    cap = re.search(r"coerceAtMost\(([\d.]+)f\)", ev)
    if not base or not cap:
        raise RuntimeError("无法解析 evidenceConfidence 的底数与上限——源码结构可能已变更")
    f["confidence_base"] = float(base.group(1))
    f["confidence_cap"] = float(cap.group(1))
    # 顺序敏感：源码里四行 `conf +=` 依次对应“具体地名/坐标/时间/意图命中”。
    f["confidence_increments"] = [float(x) for x in re.findall(r"conf \+= ([\d.]+)f", ev)]
    if len(f["confidence_increments"]) != 4:
        raise RuntimeError(
            "evidenceConfidence 只解析到 %d 项增量，预期 4 项" % len(f["confidence_increments"]))
    f["confidence_has_no_model_floor"] = ("0.80f +" not in ede and "0.18f *" not in ede)

    # 6) 恒真哨兵比较不许复发：判据必须覆盖解析器**实际返回**的占位名。
    f["no_dead_sentinel_compare"] = (
        '!= "未明目标"' not in ede and '!= "未明目标"' not in gate
    )
    sent = re.search(r"val GENERIC_TARGET_NAMES: Set<String> = setOf\(([\s\S]*?)\)", contract)
    if not sent:
        raise RuntimeError("无法解析 GENERIC_TARGET_NAMES——占位名表可能被删了")
    declared = set(re.findall(r'"([^"]+)"', sent.group(1)))
    etn = _balanced_body(ede, "private fun extractTargetName(")
    produced = set(re.findall(r'return[^"]*"([^"]+)"', etn)) | set(
        re.findall(r'\?:\s*"([^"]+)"', etn))
    f["placeholder_names"] = sorted(produced)
    f["sentinels_cover_actual_placeholders"] = bool(produced) and produced.issubset(declared)

    # 7) 模型侧：bucket 0 必须当作“没坐标”，而不是还原成桶心 (30,30)。
    #    （训练脚本把“军令里没写坐标”统一标成了 0，两者在输出上同形。）
    f["coord_bucket_zero_means_absent"] = bool(
        re.search(r"val coord = if \(coordBucket == 0\) null else coordCenter\(coordBucket\)", ism)
    )

    # 8) 回落原因必须逐出口如实记录（否则“装了 37MB 模型却从来没跑过”看不出来）。
    f["release_reasons_recorded"] = len(re.findall(r"unavailableReason = ", ism)) >= 5

    # 9) 体力基线来自知识库，且不得伪造“体力已满”的默认值。
    f["stamina_baseline_from_knowledge_base"] = (
        "requiredStaminaNow()" in gate and "minStaminaRequired = 20" not in gate
    )
    f["stamina_not_fabricated"] = (
        bool(re.search(r"currentStamina: Int\? = null", gate))
        and "currentStamina: Int = 100" not in gate
    )

    # 10) 模型接管阈值必须可解析，并且在 (0,1] 里（写死了也要能对账）。
    th = re.search(r"MODEL_INTENT_MIN_CONFIDENCE = ([\d.]+)f", ede)
    if not th:
        raise RuntimeError("无法解析 MODEL_INTENT_MIN_CONFIDENCE")
    f["model_intent_threshold"] = float(th.group(1))
    return f


DECREE_FACTS = parse_decree_pipeline_facts(
    _DECREE_SRC, _INTENT_MODEL_SRC, _SAFETY_GATE_SRC, _ORDER_CONTRACT_SRC)
CONF_INCREMENTS = DECREE_FACTS["confidence_increments"]


def evidence_confidence(has_target, has_coord, has_time, has_intent):
    """置信度镜像：底数与四项增量全部来自源码解析，不在本文件里重新声明。"""
    conf = DECREE_FACTS["confidence_base"]
    for flag, inc in zip((has_target, has_coord, has_time, has_intent), CONF_INCREMENTS):
        if flag:
            conf += inc
    return min(conf, DECREE_FACTS["confidence_cap"])


def evidence_confidence_legacy(target_name, model_prob):
    """被否决的旧口径，只给 P19c 做反向对照：模型加底分 + 正则恒真比较。"""
    if model_prob is not None:
        return 0.80 + 0.18 * model_prob
    return 0.96 if target_name != "未明目标" else 0.82


def rag_search(query, entries, top_k=3):
    """检索镜像：**精确命中优先，向量只在无精确命中时兜底**。

    cosine 由外部给定（真实实现里由向量算出，那是另一套已被 P1–P5 覆盖的数学），
    本镜像只检验“排序与门控规则”，因此它检验的是本节的真正风险点。
    """
    low = query.lower()
    exact = []
    for e in entries:
        n = sum(1 for kw in e["keywords"] if kw.lower() in low)
        if e["title"].lower() in low:
            n += 1
        if n:
            exact.append((n * _RAG["exact_hit_weight"] + e["cos"], e["id"]))
    if exact:
        exact.sort(reverse=True)
        return [i for _, i in exact[:top_k]]
    fallback = sorted(((e["cos"], e["id"]) for e in entries if e["cos"] >= _RAG["dense_min"]),
                      reverse=True)
    return [i for _, i in fallback[:top_k]]


def rag_search_legacy(query, entries, top_k=3):
    """被否决的旧实现（cosine×0.65 + 关键词 boost 封顶 0.35）。

    这三个常量只能在这里写死：源码里已经没有它们了，无处可解析。
    本函数唯一的作用是给 P17b 做反向对照——证明新规则真的会咬旧行为。
    """
    low = query.lower()
    scored = []
    for e in entries:
        boost = 0.25 * sum(1 for kw in e["keywords"] if kw.lower() in low)
        if e["title"].lower() in low:
            boost += 0.35
        scored.append((e["cos"] * 0.65 + min(boost, 0.35), e["id"]))
    scored.sort(reverse=True)
    return [i for _, i in scored[:top_k]]


def land_safety(hit_danger, hit_hard, hit_moderate, hit_safe, level_blacklisted, level_whitelisted):
    """能不能打：必须有正向证据、等级黑名单压过一切，且全局硬危险不可救。

    与源码同构的三道闸门：
      kbSaysSafe —— 没被本等级黑名单拦住，且（全局 safe 档命中 或 本等级明确推荐）；
      noHardBlock —— danger / hard 命中就否，等级白名单救不回来；
      moderateOk —— moderate 命中时，只有“本等级明确推荐且没进黑名单”才放行。
    """
    kb_says_safe = (level_blacklisted is None
                    and (hit_safe is not None or level_whitelisted is not None))
    no_hard_block = hit_danger is None and hit_hard is None
    moderate_ok = (hit_moderate is None
                   or (level_whitelisted is not None and level_blacklisted is None))
    return kb_says_safe and no_hard_block and moderate_ok


def land_safety_legacy(hit_danger, hit_hard, hit_safe, advice_text, level):
    """旧判定：不可热更的编译期文案可以单独把一块地判成“可打”。给 P17b 用。"""
    return (hit_danger is None and hit_hard is None
            and (hit_safe is not None or "白给" in (advice_text or "") or level <= 3))


def strip_corpus_troop_numbers(text):
    """语料文案脱敏镜像（语汇表来自源码解析，不是这里另写一份）。

    最后四个收敛写法必须与 Kotlin stripCorpusTroopNumbers 一致；P17h 会把“镜像里
    已收敛而源码里没收敛”这种漂移抓出来（见 parse_rag_facts 的 collapse_present）。
    """
    out = text
    for ctx in _RAG["ctx_words"]:
        out = re.sub(re.escape(ctx) + r"[^0-9。\n]{0,8}?\d{3,6}(?:[~～-]\d{2,6})?[+]?",
                     lambda m: re.sub(r"\d+", "以知识库为准", m.group(0)), out)
    out = re.sub(r"\d{3,6}(?:[~～-]\d{2,6})?[+]?\s*(?:合计)?(?:兵|兵力|守军)",
                 lambda m: re.sub(r"\d+", "以知识库为准", m.group(0)), out)
    for junk in ("以知识库为准以知识库为准", "以知识库为准~以知识库为准",
                 "以知识库为准～以知识库为准", "以知识库为准-以知识库为准"):
        out = out.replace(junk, "以知识库为准")
    return out


# ---------------------------------------------------------------- 用例

def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    bad = 0
    total = 0

    def check(label, cond, detail=""):
        nonlocal bad, total
        total += 1
        if cond:
            print(f"OK   | {label}" + (f"\n        {detail}" if detail else ""))
        else:
            bad += 1
            print(f"FAIL | {label}" + (f"\n        {detail}" if detail else ""))

    lists = {
        "danger": [("吕布", 5), ("张辽", 4)],
        "hard": [("曹操", 3), ("司马懿", 2)],
        "safe": [("刘禅", 1)],
        "moderate": [("夏侯惇", 3)],
    }

    print("=" * 80)
    print("P0. 解析器自检（证明「从源码提炼规则」不是空转）")
    print("=" * 80)
    syn = ('enum class SafetyTier(val desc: String, val weight: Int) {\n'
           '    UNKNOWN("u", 0),\n    SAFE("s", 1),\n    MODERATE("m", 2),\n'
           '    HARD("h", 3),\n    DANGER("d", 5)\n}')
    parsed = parse_safety_tiers(syn)
    sane = (parsed.get("DANGER", {}).get("weight") == 5
            and parsed.get("UNKNOWN", {}).get("weight") == 0)
    malformed_raises = False
    try:
        parse_safety_tiers("enum class SafetyTier(x) { }")
    except RuntimeError:
        malformed_raises = True
    check(
        "P0. 能从合成源码提炼权重，且畸形输入会**报错退出**（而不是静默回退）",
        sane and malformed_raises,
        f"合成解析 DANGER 权重={parsed.get('DANGER', {}).get('weight')}；"
        f"畸形输入抛错={malformed_raises}；"
        f"真实源码解析到 {len(TIERS)} 个评级"
    )

    # P0b：聚合起始值必须是权重最小的评级 —— 这一条直接守着已修掉的
    # "空输入被判为 SAFE 并输出「推荐立刻收割」"那个 bug 不被改回来。
    min_weight = min(WEIGHT.values())
    check(
        "P0b. 聚合起始评级必须是权重最小的那个（守住「空输入不会变成 SAFE」）",
        WEIGHT[AGGREGATION_START] == min_weight and AGGREGATION_OP is not None,
        f"源码起始值={AGGREGATION_START}(权重 {WEIGHT[AGGREGATION_START]})，"
        f"最小权重={min_weight}（{UNKNOWN_TIER}），比较符=[{AGGREGATION_OP}]；"
        f"检索顺序={[k for k, _ in FIND_MATCH_ORDER]}"
    )

    print()
    print("=" * 80)
    print("P1. 没读到守军 → 必须是 UNKNOWN，且不得出现鼓励进攻的措辞")
    print("=" * 80)
    empty_variants = [[], [""], ["   "], ["", "  ", ""]]
    all_unknown = True
    all_clean = True
    for names in empty_variants:
        tier, score, rec, n = evaluate(names, lists)
        if tier != "UNKNOWN":
            all_unknown = False
        if any(w in rec for w in ENCOURAGING_WORDS):
            all_clean = False
            print(f"    含鼓励措辞: names={names} rec={rec}")
    check("空输入/纯空白输入一律判为 UNKNOWN", all_unknown,
          f"覆盖 {len(empty_variants)} 种空输入形态")
    check("空输入时的推荐语不含任何鼓励进攻的措辞", all_clean,
          f"检查词表 {ENCOURAGING_WORDS}")

    print()
    print("=" * 80)
    print("P2–P5. 有输入时的性质（枚举全部列表 × 全部评分）")
    print("=" * 80)
    # P2：读到至少一个守军 → 不停留在 UNKNOWN
    p2 = True
    for tier_name, floor in (("danger", "DANGER"), ("hard", "HARD"),
                             ("safe", "SAFE"), ("moderate", "MODERATE")):
        for hname, score in lists[tier_name]:
            t, _, _, _ = evaluate([hname], lists)
            if t == "UNKNOWN":
                p2 = False
    t_unknown, _, _, _ = evaluate(["某个不在库里的武将"], lists)
    check("P2. 读到守军名字即不再停留在 UNKNOWN", p2 and t_unknown == "MODERATE",
          f"未知武将得 {t_unknown}（应为 MODERATE，不得是 SAFE）")

    # P3：单调性——评分越高评级不得更低
    p3 = True
    for floor in ("SAFE", "MODERATE", "HARD", "DANGER"):
        prev = -1
        for score in range(1, 6):
            w = WEIGHT[resolve_tier(floor, score)]
            if w < prev:
                p3 = False
                print(f"    非单调: floor={floor} score={score} weight={w} < {prev}")
            prev = w
    check("P3. 同一列表内威胁评分越高、评级不降低（单调）", p3, "枚举 floor×score 共 20 组")

    # P4：列表下限——结果不得低于所属列表的危险度
    p4 = True
    for tier_name, floor in (("danger", "DANGER"), ("hard", "HARD"),
                             ("safe", "SAFE"), ("moderate", "MODERATE")):
        for hname, score in lists[tier_name]:
            for s in range(1, 6):
                r = resolve_tier(floor, s)
                if WEIGHT[r] < WEIGHT[floor]:
                    p4 = False
                    print(f"    低于下限: floor={floor} score={s} -> {r}")
    check("P4. 结果不低于该武将所属列表的危险度（危险列表不会被降级放过）", p4,
          "枚举 4 个列表 × 评分 1..5")

    # P5：多守军取最危险
    lookup = {"吕布": ("DANGER", 5), "张辽": ("DANGER", 4), "曹操": ("HARD", 3),
              "司马懿": ("HARD", 2), "刘禅": ("SAFE", 1), "夏侯惇": ("MODERATE", 3)}
    p5 = True
    for combo in (["吕布", "刘禅"], ["刘禅", "张辽"], ["曹操"], ["刘禅"], ["吕布", "曹操", "刘禅"]):
        t, _, _, _ = evaluate(combo, lists)
        want = max(WEIGHT[resolve_tier(*lookup[n])] for n in combo)
        if WEIGHT[t] != want:
            p5 = False
            print(f"    取最危险失败: {combo} -> {t}({WEIGHT[t]}) 期望权重 {want}")
    check("P5. 多个守军时取最危险的一个", p5, "覆盖含软柿子的组合")

    # P8：噪声只能提升危险度，绝不降低。
    # 这是"识别区域可以开得偏大"的**依据**：多圈进来的文字不会让危险守将被放过。
    p8 = True
    noise = ["兵力", "守军", "等级", "某", "守军信息"]
    for base in ([], ["刘禅"], ["曹操"], ["吕布"], ["刘禅", "曹操"], ["司马懿"]):
        base_w = WEIGHT[evaluate(base, lists)[0]]
        for extra in ([noise[0]], noise, ["吕布"]):
            grown = WEIGHT[evaluate(base + extra, lists)[0]]
            if grown < base_w:
                p8 = False
                print(f"    噪声降低了危险度: base={base}({base_w}) + {extra} -> {grown}")
    check("P8. 追加任何文字（含无关噪声）都不会降低危险度", p8,
          "枚举 6 组基线 × 3 种追加（含 5 个无关词）")

    # P9：无关 token 会被保守地当作"未知守军"记为 MODERATE。
    # 这是一个**有意**的取舍（偏保守），但它有一个副作用值得写明：
    # 只要识别区域里混进任何无关文字，SAFE（"推荐立刻收割"）就很难出现。
    t_noise, _, _, _ = evaluate(["兵力"], lists)
    check(
        "P9. 无关文字被保守记为 MODERATE（副作用：区域偏大会压掉 SAFE 判定）",
        t_noise == "MODERATE",
        f"单看「兵力」得到 {t_noise}；这意味着 SAFE 需要区域里**没有任何**无关文字"
    )

    print()
    print("=" * 80)
    print("P6–P7. 选队门槛（枚举体力×士气×门槛）")
    print("=" * 80)
    need = 20
    min_morale = 100
    violations = []
    unverified_paths = 0
    for stamina in [None, 0, 5, 19, 20, 21, 120, 200]:
        for morale in [None, 0, 50, 99, 100, 101, 120]:
            for slot in (1, 2, 3):
                cand = [(slot, stamina, morale)]
                picked, reason = select_troop(cand, need, min_morale)
                if picked is None:
                    continue
                if reason == "unverified":
                    unverified_paths += 1
                    continue
                # 已验证路径：必须真的达标
                if not (stamina is not None and morale is not None
                        and stamina >= need and morale >= min_morale):
                    violations.append((stamina, morale, reason))
    check(
        "P6. 体力/士气已识别且低于门槛时，绝不会被选中",
        not violations,
        f"枚举 8 种体力 × 7 种士气 × 3 个槽位；违规 {len(violations)} 例"
    )
    # "未验证放行"只应由 None 触发：(体力 None × 任意士气) + (任意体力 × 士气 None)
    #   = 1×7 + 7×1 = 14 种组合/槽位，3 个槽位共 42
    check(
        "P7. 「未验证放行」只出现在确实无法识别的组合上",
        unverified_paths == 42,
        f"实际 {unverified_paths} 组（期望 42 = (1×7 + 7×1) × 3）"
    )

    # 顺序性：第一个达标的被选中（不是"最优"，但必须可解释）
    order_case = [(1, 5, 120), (2, 120, 120)]
    picked, reason = select_troop(order_case, need, min_morale)
    check(
        "补充. 顺序策略可解释：跳过不达标的队，取第一个达标的",
        picked == 2 and reason == "verified_ok",
        f"候选 [(1,体力,士气)=(5,120), (2,体力,士气)=(120,120)] -> 选中 {picked}（{reason}）"
    )

    print()
    print("=" * 80)
    print("P10–P12. 士气档位优选（只改「选哪支」，不改「能不能选」）")
    print("=" * 80)
    # 用三战那组真正会咬人的数字：铺路下限 80 、基准士气 100。
    std = 100
    pave_min = 80
    # 1 号达标但士气 90（< 基准）；2 号同样达标且士气 100。
    picked, reason = select_troop([(1, 120, 90), (2, 120, 100)], need, pave_min, std)
    check(
        "P10. 优先取士气不打折的那支，而不是“第一个达标的”",
        picked == 2 and reason == "verified_ok",
        f"候选 [(1,120,90), (2,120,100)] -> 选中 {picked}（{reason}）"
    )

    # 全部候选都士气打折：仍必须给出一个队，不能谎报“无人可用”。
    picked, reason = select_troop([(1, 120, 90), (2, 120, 85)], need, pave_min, std)
    check(
        "P11. 全是打折队时仍选第一个达标的（优选不得把「可选」变成「不可选」）",
        picked == 1 and reason == "verified_low_morale_fallback",
        f"候选 [(1,120,90), (2,120,85)] -> 选中 {picked}（{reason}）"
    )

    # 不变量：对任意候选列表，启用优选后的「是否能选出队」与不启用时完全一致。
    # （资格判据没变，所以两个分支的 None/非 None 必须同步。）
    vals = [None, 0, 79, 80, 90, 100, 120]
    stams = [None, 0, 19, 20, 120]
    pool = [(s, m) for s in stams for m in vals]
    mismatches = []
    enumerated = 0
    for n_cand in (1, 2):
        for combo in itertools.product(pool, repeat=n_cand):
            enumerated += 1
            cands = [(i + 1, s, m) for i, (s, m) in enumerate(combo)]
            a = select_troop(cands, need, pave_min)[0] is not None
            b = select_troop(cands, need, pave_min, std)[0] is not None
            if a != b:
                mismatches.append(cands)
    check(
        "P12. 不变量：优选只改变选中哪一支，不改变「能不能选出队」",
        not mismatches,
        f"枚举 1～2 支候选共 {enumerated} 组（单支 {len(pool)} 种取值笛卡尔积）；"
        f"不一致 {len(mismatches)} 例"
    )

    # P13：上面这套优选必须是**源码里真的存在**的实现，而不是本脚本里的理想版本。
    # 如果有人把 RoadPavingFlow 改回“遇到第一个达标的就走”，P10/P11 仍然会绿
    # （它们测的是 Python 镜像），只有这一条会红——它才是验证器不说谎的根。
    missing = [k for k, v in SELECT_TROOP_FACTS.items() if not v]
    check(
        "P13. 源码反照：士气档位优选确实写在 selectOptimalTroop 里（而不是只写在验证器里）",
        not missing,
        f"从源码提炼到的性质：{sorted(SELECT_TROOP_FACTS)}；未成立：{missing or '无'}"
    )

    print()
    print("=" * 80)
    print("P14–P15b. 铺路逐格推进：不许跳格，也不许把未完成报成成功")
    print("=" * 80)
    # P14：先确认调度规则真的长在源码里。被改回去的往往不是算法，而是这种结构约定；
    #      约定一旦破了，P15 的枚举证明就只是在证明一个已经不存在的实现。
    missing_paving = [k for k, v in PAVING_FLOW_FACTS.items() if not v]
    check(
        "P14. 源码反照：「不许跳格」的结构不变量都写在 RoadPavingFlow.kt 里",
        not missing_paving,
        f"提炼到 {len(PAVING_FLOW_FACTS)} 条性质；未成立：{missing_paving or '无'}"
    )

    # P15：枚举所有结果序列，证明两条不变量同时成立：
    #   ① 只有前一格被确认占领，才允许前进（原地重试合规）；
    #   ② DONE（成功）只能出现在每一格都被确认占领的时候。
    tile_outcomes = ["OCCUPIED", "TRANSIENT_FAILURE", "OCCUPATION_UNCONFIRMED", "NO_QUALIFIED_TROOP"]
    gaps = []
    false_done = []
    count_mismatch = []
    enumerated = 0
    for n_tiles in (2, 3, 4):
        for seq in itertools.product(tile_outcomes, repeat=n_tiles):
            enumerated += 1
            table = {i: [o] for i, o in enumerate(seq)}
            tried, confirmed, verdict = simulate_paving(table)
            if paving_violations(tried, confirmed):
                gaps.append(seq)
            if verdict == "DONE" and len(confirmed) != n_tiles:
                false_done.append(seq)
            if verdict == "DONE" and len(confirmed) != sum(1 for o in seq if o == "OCCUPIED"):
                count_mismatch.append(seq)
    check(
        "P15. 不变量：任何结果序列下都不会未经确认就前进，也不会把未完成报成成功",
        not gaps and not false_done and not count_mismatch,
        f"枚举 2、3、4 格共 {enumerated} 组结果序列；"
        f"违规前进 {len(gaps)} 例、假 DONE {len(false_done)} 例、计数不符 {len(count_mismatch)} 例"
    )

    # P15b：拿旧语义跑同一个检测器，确认它真的会变红。
    #      不加这一条，P15 完全可能只是在一句废话上绿灯（比如镜像本身永远不前进）。
    legacy_table = {0: ["NO_QUALIFIED_TROOP"], 1: ["OCCUPIED"], 2: ["OCCUPIED"]}
    l_tried, l_confirmed, l_verdict = simulate_paving_legacy(legacy_table)
    l_violations = paving_violations(l_tried, l_confirmed)
    legacy_false_done = l_verdict == "DONE" and len(l_confirmed) != len(legacy_table)
    check(
        "P15b. 反向对照：旧语义（体力不足就换下一格、最后报成功）会被上面两条判红",
        bool(l_violations) and legacy_false_done,
        f"旧语义尝试序列 {l_tried}、确认 {l_confirmed}、结论 {l_verdict}；"
        f"违规前进 {l_violations}，假 DONE={legacy_false_done}"
    )

    print()
    print("P16–P18. RAG 检索与守军安全判定：精确优先、不采信旧文案、语料数字不递到玩家眼前")

    # P16：先确认这些规则真的长在 SlgRagEngine.kt 里。下面两条枚举只能证明“镜像成立”；
    #      没有这一条，源码改回去了而这里还能一路绿灯。
    rag_false = [k for k, v in _RAG.items() if v is False]
    check(
        "P16. 源码反照：检索与安全判定的结构不变量都写在 SlgRagEngine.kt 里",
        not rag_false,
        f"从源码解析出 exact_weight={_RAG['exact_hit_weight']}、dense_min={_RAG['dense_min']}、"
        f"语汇表={_RAG['ctx_words']}；不成立项：{rag_false or '无'}"
    )

    # P17：枚举。注意这里只断言“镜像与源码同一套规则下必须成立的性质”，
    #      而不是抽查几个例子——下面这两组输入空间是穷举的。
    entries = [
        {"id": "EXACT_LOWCOS", "title": "5级地守军-李儒阵容", "keywords": ["李儒", "怯战"], "cos": 0.10},
        {"id": "FUZZY_HIGHCOS", "title": "同盟压秒攻略", "keywords": ["压秒", "攻城"], "cos": 0.95},
        {"id": "EXACT_HICOS", "title": "4级地守军-张任阵容", "keywords": ["张任", "落凤"], "cos": 0.60},
    ]
    q = "李儒怯战"
    got = rag_search(q, entries, top_k=1)
    check(
        "P17a. 精确命中优先：低 cosine 但名字对上的条目，必须排在高 cosine 但不相干的前面",
        got and got[0] == "EXACT_LOWCOS",
        f"查询「{q}」→ 首选 {got}（旧加权求和会把 FUZZY_HIGHCOS 顶到第一）"
    )

    # 穷举：任意子集 + 任意 cosine 组合，只要有精确命中，返回集里不得出现不相干条目
    import itertools as _it
    bad_mix = 0
    combos = 0
    for r in range(1, 4):
        for subset in _it.combinations(entries, r):
            for coses in _it.product([0.05, 0.34, 0.36, 0.99], repeat=r):
                combos += 1
                pool = [dict(e, cos=c) for e, c in zip(subset, coses)]
                res = rag_search(q, pool, top_k=3)
                exact_ids = {e["id"] for e in pool
                             if any(k.lower() in q.lower() for k in e["keywords"])
                             or e["title"].lower() in q.lower()}
                if exact_ids and any(i not in exact_ids for i in res):
                    bad_mix += 1
    # 本例子里「李儒」永远只属于 EXACT_LOWCOS，所以一旦混进不相干条目就是规则被改坏
    check(
        "P17b. 不变量：穷举条目子集×cosine 组合，精确命中存在时绝不混进不相干条目",
        bad_mix == 0,
        f"共 {combos} 组（1/2/3 个条目的全部非空子集 × 每个 4 档 cosine），违规 {bad_mix} 组"
    )

    silent = [{"id": "X", "title": "无关", "keywords": ["无关"], "cos": 0.34},
              {"id": "Y", "title": "也无关", "keywords": ["也无关"], "cos": 0.20}]
    check(
        "P17c. 无精确命中且全部低于阈值 → 返回空（宁可不念，也不把不相干的文案当专业建议）",
        rag_search("邓茂", silent, top_k=3) == [],
        f"cos 0.34/0.20 均低于 dense_min={_RAG['dense_min']}，结果={rag_search('邓茂', silent)}"
    )

    # 安全判定：6 个输入穷举 64 组，断言三条性质：
    #   (1) isSafe 成立 ⇒ 必须有正向证据（hitSafe 或 levelWhitelisted）且黑/危/难全空；
    #   (2) 等级黑名单永远压过全局白名单；
    #   (3) moderate 命中却判为可打时，一定是“本等级明确推荐”一手递上去的。
    viol = []
    for combo in _it.product([None, "hit"], repeat=6):
        d, h, mo, sa, bl, wl = combo
        s = land_safety(d, h, mo, sa, bl, wl)
        if s and (d or h or bl or (sa is None and wl is None)
                  or (mo and wl is None)):
            viol.append(combo)
    check(
        "P17d. 不变量（64 组穷举）：判为可打必须同时满足“无黑/危/难”与“有正向证据”；"
        "moderate 只能靠本等级推荐过关",
        not viol,
        f"违规组合：{viol or '无'}"
    )
    # 新行为当场钉住（不只是“没变坏”，还要“变得对”）：KB 把 徐晃/鲍信 同时列为
    # moderate 与 Lv5 推荐，这两面缺任何一面都是 bug。
    check(
        "P17d2. moderate + 本等级明确推荐 → 可打（否则雷达会跳过 KB 亲自推荐的 Lv5 首开目标）",
        land_safety(None, None, "徐晃", None, None, "徐晃") is True,
        "hitModerate=徐晃、levelWhitelisted=徐晃 → True；去掉白名单则 False：" +
        str(land_safety(None, None, "徐晃", None, None, None))
    )
    check(
        "P17e. 等级黑名单压过全局白名单（KB 说该将安全但本等级列为避开→仍不可打）",
        land_safety(None, None, None, "张任", "张任", None) is False,
        "hitSafe=张任、levelBlacklisted=张任 → False"
    )
    check(
        "P17f. 什么都没认出来时不得判为可打（旧实现里 level<=3 会无条件放过）",
        land_safety(None, None, None, None, None, None) is False,
        "OCR 未读出守将名 → fail-closed，雷达跳过该地"
    )

    # 语料文案脱敏：样本取自 corpus_stzb.jsonl 的真实句式（完整覆盖率由
    # check_knowledge_base.py 对真文件逐个校，不在这里重复读数据）。
    samples = [
        "🟢 难度评级: C(软柿子) | 推荐指数: ★★★★★ | 兵力建议: 5500~6000 | 5级地首开第一首选，满士气即可稳过。",
        "🟢 极佳！主力需 16000+ 且备战第二补刀队",
        "7级地双队合计约 42000 兵力，需先打下周围地块起要塞",
        "5级地开荒守军建议（起手指点兵约 1200）",
        "未开觉醒且兵力不足 7500 严禁出击",
    ]
    cleaned = [strip_corpus_troop_numbers(s) for s in samples]
    left = [c for c in cleaned if re.search(r"\d{3,6}", c)]
    # 定性结论必须一字不动：只该抹数字，不该顺手把“软柿子/首开/严禁出击”这类判断词抹掉。
    kept_words = ("软柿子" in cleaned[0] and "首开第一首选" in cleaned[0]
                  and "严禁出击" in cleaned[4])
    check(
        "P17g. 语料文案脱敏：5 种真实句式里的兵力数字全部被拦住，定性结论一字不动",
        not left and kept_words,
        f"残留数字：{left or '无'}；首条脱敏后：{cleaned[0][:52]}"
    )
    doubled = [c for c in cleaned if "以知识库为准~" in c or "以知识库为准以知识库为准" in c]
    check(
        "P17h. 脱敏不得把玩家的话说成绕口（区间写法必须收敛成一份）",
        not doubled and _RAG["collapse_present"],
        f"镜像绕口输出：{doubled or '无'}；源码里的收敛替换存在：{_RAG['collapse_present']}"
    )

    # P18：反向对照。拿被否决的旧实现跑同一批场景，确认新规则真的会咬旧行为——
    #      否则 P17a/P17f 可能只是在一句废话上绿灯。
    legacy_top = rag_search_legacy(q, entries, top_k=1)
    legacy_says_safe = land_safety_legacy(None, None, None, "3级地白给", 7)
    current_disagrees = legacy_top != ["EXACT_LOWCOS"]
    check(
        "P18. 反向对照：旧加权求和把精确命中挤掉、旧判定让编译期文案决定能不能打",
        current_disagrees and legacy_says_safe,
        f"旧检索首选={legacy_top}（新={ ['EXACT_LOWCOS'] }）；"
        f"旧文案判安全={legacy_says_safe}（新 same input→{land_safety(None, None, None, None, None, None)}）"
    )

    # P19：军令执行链路的源码反照（P6 收口）。没有这一条，有人把“模型结论覆盖原文坐标”
    #      改回去、把体力基线改回写死 20，本脚本还能一路绿灯。
    dec_false = [k for k, v in DECREE_FACTS.items() if v is False]
    check(
        "P19. 源码反照：模型只补证据不改写原文事实，置信度无底分，体力基线来自知识库",
        not dec_false and 0.0 < DECREE_FACTS["model_intent_threshold"] <= 1.0
        and all(i > 0 for i in CONF_INCREMENTS),
        "占位名实集=%s、置信度底/顶=%.2f/%.2f、四项增量=%s、接管阈值=%.2f；不成立项：%s" % (
            DECREE_FACTS["placeholder_names"], DECREE_FACTS["confidence_base"],
            DECREE_FACTS["confidence_cap"], CONF_INCREMENTS,
            DECREE_FACTS["model_intent_threshold"], dec_false or "无")
    )

    # P19b：16 组证据组合。硬不变量：多一条证据绝不能把分变低，
    #      而“什么都没读到”必须低于面板上的“证据不足”警戒线 0.60。
    conf_viol = []
    for mask in range(16):
        flags = tuple(bool(mask & (1 << b)) for b in range(4))
        c = evidence_confidence(*flags)
        if c > DECREE_FACTS["confidence_cap"] + 1e-9:
            conf_viol.append((flags, c, "超上限"))
        for bit in range(4):
            if not flags[bit]:
                up = flags[:bit] + (True,) + flags[bit + 1:]
                if evidence_confidence(*up) + 1e-9 < c:
                    conf_viol.append((flags, c, "加证据反而降分 %s" % (up,)))
    check(
        "P19b. 置信度单调性（16 组穷举）：证据只增不减分，且无证据时低于面板警戒线 0.60",
        not conf_viol and evidence_confidence(False, False, False, False) < 0.60,
        "违规=%s；无证据=%.2f、满证据=%.2f" % (
            conf_viol or "无",
            evidence_confidence(False, False, False, False),
            evidence_confidence(True, True, True, True))
    )

    # P19c：反向对照。一句“什么都没读到”的军令，旧口径会报什么：
    #      正则分支的 `targetName != "未明目标"` 对真实占位名“目标据点”**恒真** → 0.96；
    #      模型刚过接管阈值时 `0.80 + 0.18*p` → 0.91。两者都接近“十分把握”，
    #      而事实上一个坐标、一个地名、一个时间都没读到。不测这一条，P19b 可能只是在一句废话上绿灯。
    legacy_regex_conf = evidence_confidence_legacy("目标据点", None)
    legacy_model_conf = evidence_confidence_legacy(
        "目标据点", DECREE_FACTS["model_intent_threshold"] + 0.01)
    new_bare_conf = evidence_confidence(False, False, False, False)
    # 这一条才是“恒真”的实证：旧比较拿“未明目标”当哨兵，而解析器实际吐出的名字里
    # 根本没有它（所以不是我在 Python 里自说自话，而是来自主文件的硬事实）。
    sentinel_never_produced = "未明目标" not in DECREE_FACTS["placeholder_names"]
    check(
        "P19c. 反向对照：旧口径在零证据军令上恒报高置信（死比较永真、模型带底分），新口径如实报低",
        sentinel_never_produced
        and legacy_regex_conf >= 0.95 and legacy_model_conf >= 0.90 and new_bare_conf <= 0.45,
        "旧：正则=%.2f（解析器实际产出为 %s，从不等于“未明目标”，故死比较为真）、"
        "模型=%.2f（带底分）；新：%.2f；面板低于 0.60 会提示“证据不足”" % (
            legacy_regex_conf, "、".join(DECREE_FACTS["placeholder_names"]),
            legacy_model_conf, new_bare_conf)
    )

    print()
    print("-" * 80)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 决策逻辑未通过验证。")
        return 1
    print(f"{total} 项全部符合预期 —— 决策逻辑通过验证。")
    print("边界说明：枚举权重、评分映射、各级推荐语、空输入与未知武将的默认评级、")
    print("          **武将列表的检索顺序**、**聚合的起始评级与比较符**，")
    print("          都是**从 DefenderEvaluator.kt 解析出来的**，不是在这里另抄一份；")
    print("          解析失败会直接报错退出，因此「Kotlin 改了而脚本没改」不会静默通过。")
    print("          仍然复刻的部分：聚合的赋值方式、选队门槛的比较结构、以及铺路逐格推进的调度规则")
    print("          （阈值本身来自知识库，运行时取值，无法静态解析）；")
    print("          以及本轮新增的 RAG 面：检索权重、向量兜底下限、语料脱敏语汇表，均从")
    print("          SlgRagEngine.kt 解析（P16）；旧加权求和与旧安全判定只以“被否决的镜像”")
    print("          形式存在于本脚本，专供反向对照（P18）。")
    print("          军令执行链路（P19）的判据分别从 EdgeSlmEngine.kt / IntentSlotModel.kt /")
    print("          DualTrackSafetyGate.kt / TacticalOrder.kt 四处源码解析得出（置信度底数与四项增量、")
    print("          占位名集、接管阈值、体力基线来源）；旧的“模型加底分 + 恒真死比较”只以")
    print("          evidence_confidence_legacy 形式存在，专供 P19c 反向对照。")
    print("          凡是复刻实现的地方都配了**源码反照**：选队士气优选（P13）、铺路逐格推进（P14）、")
    print("          RAG 检索与守军判定（P16），")
    print("          保证“Kotlin 改回去了而这里还一路绿灯”不会发生；")
    print("          铺路调度还额外做了**反向对照**（P15b）：拿旧语义跑同一个检测器，")
    print("          确认它真会变红——否则“不变量成立”可能只是在一句废话上绿灯。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
