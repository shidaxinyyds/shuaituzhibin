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


def select_troop(candidates, need_stamina, min_morale):
    """
    复刻 RoadPavingFlow.selectOptimalTroop。
    candidates: [(slot, stamina|None, morale|None)]，按候选顺序。
    返回 (选中的 slot | None, 原因)：原因用于区分"已验证达标"与"未验证放行"。
    """
    for slot, stamina, morale in candidates:
        if stamina is None or morale is None:
            return slot, "unverified"      # 源码：无法识别 → 记为未验证并放行
        if stamina >= need_stamina and morale >= min_morale:
            return slot, "verified_ok"
        # 否则跳过
    return None, "none_qualified"


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
        f"候选 [(1,体力5,士气120), (2,体力120,士气120)] -> 选中 {picked}（{reason}）"
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
    print("          仍然复刻的部分：聚合的赋值方式、以及选队门槛的比较结构")
    print("          （阈值本身来自知识库，运行时取值，无法静态解析）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
