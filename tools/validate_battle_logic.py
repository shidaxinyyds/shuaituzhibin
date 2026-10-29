#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PVE 战斗推演（BattleResolver）与战报结构化的性质验证
====================================================

推演模型是**区间估算**，不是战斗公式（见 GameProfile.BattleModel 的注释）。
既然它给不出精确值，能证明的只有三件事，本脚本逐条量：

  1. **单调性**：带兵↑ / 守将威胁↓ / 士气↑ => 估算战损不升。
     方向错的方向盘比没有方向盘更糟——玩家会照着它加兵，而数字反而更难看。
  2. **区间自洽**：下界 ≤ 点估计 ≤ 上界，且上界不超过 100%，下界不为负。
     一条「预计战损 92%~130%」的输出会让整个功能失去可信度。
  3. **拒算分支真的存在**：输入不全时必须说"算不了 + 原因"，
     而不是吐一个看起来合理的百分比。

另外验证两件事，它们与数学模型无关，但一样会静默失效：
  4. **战报解析器里没有任何中文字面量**：所有匹配词都从知识包 REPORT_* 组读；
  5. **接线真实存在**：模块被 Application / 悬浮窗 / 哨兵真正调用，
     而不是"写好了放在那里"。

⚠️ 系数不在这里另写一份：**实际生效值**从各知识包的 `battleModel = BattleModel(...)` 解析，
   `GameProfile.kt` 的 data class 默认值只用来兜底未显式传入的项（并校验 fromJson
   的兜底值与默认值一致）；`MAX_LOSS_*` 也从 BattleResolver.kt 解析。
   Kotlin 改了而脚本没改时，本脚本会直接报错退出，而不是悄悄用旧数字继续跑。

用法：python tools/validate_battle_logic.py
"""

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAVA = os.path.join(REPO, "client", "app", "src", "main", "java", "com", "stzb", "assistant")

# MAX_LOSS_RATE / MAX_LOSS_CEILING 不在这里写死：下面从 BattleResolver.kt 的
# `const val` 里解析。它们是数学边界，抄一份到脚本里就意味着改一处忘一处，
# 而这条边界正是"上界不超过 100%"那几条断言的前提。

# 与 GameProfile.BattleModel 的构造参数逐字对应。
BATTLE_FIELDS = ("lossBaseRate", "underStrengthPenalty", "threatPenaltyPerPoint",
                 "moralePenaltyAtZero", "uncertaintyRatio", "minSoldiersForSim",
                 "costlyLossRate", "defeatLossRate")

# 战报解析器从知识包读取的 6 组词（BattleReportParser.kt 的键）。
REPORT_GROUPS = ("REPORT_RESULT_WIN", "REPORT_RESULT_LOSE", "REPORT_RESULT_DRAW",
                 "REPORT_ROUNDS", "REPORT_TROOPS", "REPORT_LOSS")


def read(rel_path):
    with open(os.path.join(JAVA, rel_path), encoding="utf-8") as fh:
        return fh.read()


def strip_kt_comments(text):
    """去掉行注释与块注释：注释里出现中文是正常的，代码里出现才是问题。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def block_of(text, opener):
    """取 `opener` 之后配对括号内的内容（含 opener 自身的那个左括号）。"""
    idx = text.find(opener)
    if idx < 0:
        return ""
    start = text.find("(", idx)
    if start < 0:
        return ""
    depth, i = 0, start
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return ""


# ---------------------------------------------------------------- 从 Kotlin 读真实系数
#
# 这里有一个很容易踩空的地方：GameProfile.kt 里系数出现了两遍——
# 一是 `data class BattleModel(val lossBaseRate: Double = 0.06, ...)` 的默认值，
# 二是 `fromJson` 里逐项 optDouble(..., 0.06) 的兜底值；
# 而端上真正跑的是知识包**显式传入**的那一套（StzbKnowledgeBase.kt 的 0.08）。
# 只读 data class 默认值，得到的是一组"从没被任何一局用过"的数字，
# 于是阈值倒挂、区间越界在这里永远测不出来——脚本全绿而线上是另一套模型。
# 所以：默认值只用来兜底，实际取值一律来自各知识包的 `battleModel = BattleModel(`。
_GP = strip_kt_comments(read(os.path.join("knowledge", "GameProfile.kt")))
_BM_DECL = block_of(_GP, "data class BattleModel(")
if not _BM_DECL:
    raise RuntimeError("GameProfile.kt 里找不到 data class BattleModel( —— 字段被改名或删了，请同步本脚本")

DEFAULTS = {}
for _name, _num in re.findall(r"val\s+(\w+)\s*:\s*(?:Double|Int)\s*=\s*(-?[\d.]+)", _BM_DECL):
    DEFAULTS[_name] = float(_num) if "." in _num else int(_num)

_missing = [f for f in BATTLE_FIELDS if f not in DEFAULTS]
if _missing:
    raise RuntimeError("BattleModel 缺字段 %s —— 本脚本的字段表与 Kotlin 不一致" % ", ".join(_missing))

# fromJson 的逐项兜底值：热更下发的 profile 少写 battle_model 时走的就是它。
_BM_FROMJSON = block_of(_GP, "val battleModel = BattleModel(")
FALLBACK = {}
for _snake, _num in re.findall(r'opt(?:Double|Int)\("([a-z0-9_]+)"\s*,\s*(-?[\d.]+)\)', _BM_FROMJSON):
    FALLBACK[_snake] = float(_num) if "." in _num else int(_num)


def snake_of(camel):
    """lossBaseRate -> loss_base_rate（与 toJson/导出脚本的键名规则一致）。"""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", camel).lower()


_RESOLVER_SRC = strip_kt_comments(read(os.path.join("ai", "sim", "BattleResolver.kt")))
_PARSER_SRC = strip_kt_comments(read(os.path.join("battle", "BattleReportParser.kt")))
_STATS_SRC = strip_kt_comments(read(os.path.join("battle", "BattleStats.kt")))
_REPORT_SRC = strip_kt_comments(read(os.path.join("battle", "BattleReport.kt")))
_NOTIF_SRC = strip_kt_comments(read(os.path.join("tactics", "NotificationCenter.kt")))
_STZB_KB = strip_kt_comments(read(os.path.join("knowledge", "StzbKnowledgeBase.kt")))
_SGZ_KB = strip_kt_comments(read(os.path.join("knowledge", "SgzKnowledgeBase.kt")))


def parse_battle_model(src):
    """读知识包里 `battleModel = BattleModel(...)` **显式传入**的项。

    具名参数没写的项由 data class 默认值兜底，所以这里只返回"写了的"，
    由调用方与 DEFAULTS 合并——把它当成完整一套，就会漏掉默认值那一半。
    """
    block = block_of(src, "battleModel = BattleModel(")
    got = {}
    for name, num in re.findall(r"(\w+)\s*=\s*(-?[\d.]+)", block):
        if name in BATTLE_FIELDS:
            got[name] = float(num) if "." in num else int(num)
    return got


# 端上真正生效的两套系数。新增知识包时这里会少一套，所以第一节会断言
# "每个知识包都显式传了全部项"，而不是默默拿默认值去测。
KB_SOURCES = {"stzb": _STZB_KB, "sgz": _SGZ_KB}
MODELS = {}
for _gid, _kb in KB_SOURCES.items():
    _merged = dict(DEFAULTS)
    _merged.update(parse_battle_model(_kb))
    MODELS[_gid] = _merged

# BattleResolver 的数学边界：不在这里另写一份。
_KONST = dict(re.findall(r"const val (MAX_LOSS_\w+) = ([\d.]+)", _RESOLVER_SRC))
if set(_KONST) != {"MAX_LOSS_RATE", "MAX_LOSS_CEILING"}:
    raise RuntimeError("BattleResolver.kt 的 MAX_LOSS_* 常量改名了 —— 请同步本脚本")
MAX_LOSS_RATE = float(_KONST["MAX_LOSS_RATE"])
MAX_LOSS_CEILING = float(_KONST["MAX_LOSS_CEILING"])

# 当前正在检验的那一套；classify/simulate 直接读它，免得把 model 一路穿参。
MODEL = MODELS["stzb"]


def use_model(model):
    global MODEL
    MODEL = model


# ---------------------------------------------------------------- 与 Kotlin 同构的镜像
#
# 这是**同一个公式的第二份实现**（Kotlin 那份端上跑、这份在这里被性质测试反复代入）。
# 两份都在 CI 里：Kotlin 靠编译，Python 靠下面的断言；任一份单独存在都证明不了什么。

# 士气基准：镜像里写死 100，端上读的是 profile.rules.moraleStandard。
# 两者一旦不同，士气那几条数值结论就不成立，所以零b 会逐个知识包对账。
MORALE_STD = 100


def classify(loss_rate):
    """战损率 -> 定性档。顺序必须与 BattleResolver.outlookOf 一致。"""
    if loss_rate >= MODEL["defeatLossRate"]:
        return "DEFEAT"
    if loss_rate >= MODEL["costlyLossRate"]:
        return "COSTLY"
    if loss_rate >= MODEL["lossBaseRate"]:
        return "ACCEPTABLE"
    return "LOW_LOSS"


def simulate(reference_soldiers, my_soldiers, threat_score, morale, morale_std=MORALE_STD):
    """返回 (点估计, 下界, 上界, 档位)。调用方负责先判拒算条件。"""
    ratio = my_soldiers / reference_soldiers
    if ratio >= 1.0:
        strength = MODEL["lossBaseRate"] / ratio
    else:
        strength = MODEL["lossBaseRate"] + MODEL["underStrengthPenalty"] * (1.0 - ratio)
    shortfall = (morale_std - morale) / morale_std
    shortfall = min(max(shortfall, 0.0), 1.0)
    morale_term = MODEL["moralePenaltyAtZero"] * shortfall
    point = strength + threat_score * MODEL["threatPenaltyPerPoint"] + morale_term
    point = min(max(point, 0.0), MAX_LOSS_RATE)
    low = min(max(point * (1.0 - MODEL["uncertaintyRatio"]), 0.0), MAX_LOSS_RATE)
    high = min(max(point * (1.0 + MODEL["uncertaintyRatio"]), 0.0), MAX_LOSS_CEILING)
    return point, low, high, classify(point)


def main():
    total = 0
    bad = 0

    def check(name, ok, detail=""):
        nonlocal total, bad
        total += 1
        if not ok:
            bad += 1
        mark = "✓" if ok else "✗"
        print(f"  {mark} {name}" + (f"  [{detail}]" if detail else ""))

    print("=" * 82)
    print("零、系数来源（不从脚本里另写一份）")
    print("=" * 82)
    for gid, model in MODELS.items():
        explicit = parse_battle_model(KB_SOURCES[gid])
        print(f"  [{gid}] 知识包显式传入 {len(explicit)} 项，其余走 data class 默认值：")
        for f in BATTLE_FIELDS:
            print(f"    {f:<24} = {model[f]}" + ("" if f in explicit else "   （默认值）"))
        check(f"[{gid}] 知识包显式传了全部 {len(BATTLE_FIELDS)} 项（少一项就是在测默认值）",
              len(explicit) == len(BATTLE_FIELDS), f"实测 {len(explicit)} 项")
        check(f"[{gid}] 昂贵档阈值严格低于翻车档（否则档位永远命中不到）",
              model["costlyLossRate"] < model["defeatLossRate"],
              f"{model['costlyLossRate']} < {model['defeatLossRate']}")
        check(f"[{gid}] 基准战损率低于重损阈值（否则每一场都是重损）",
              model["lossBaseRate"] < model["costlyLossRate"],
              f"{model['lossBaseRate']} < {model['costlyLossRate']}")
        check(f"[{gid}] 区间宽度在 (0, 0.6]，不把估算当确定值",
              0.0 < model["uncertaintyRatio"] <= 0.6,
              f"uncertaintyRatio={model['uncertaintyRatio']}")

    want = {snake_of(f): float(DEFAULTS[f]) for f in BATTLE_FIELDS}
    mismatch = [k for k, v in want.items() if float(FALLBACK.get(k, -1)) != v]
    print("  fromJson 兜底值：" + ", ".join(f"{k}={FALLBACK.get(k)}" for k in want))
    check("fromJson 兜底值与 data class 默认值逐字相同（两处各写一遍，改一处忘一处就会静默降级）",
          not mismatch, "不一致: " + ", ".join(mismatch) if mismatch else "8 项全一致")
    check("MAX_LOSS_RATE < MAX_LOSS_CEILING（否则上界会被下界截断）",
          MAX_LOSS_RATE < MAX_LOSS_CEILING, f"{MAX_LOSS_RATE} < {MAX_LOSS_CEILING}")

    print()
    print("=" * 82)
    print("零b、端上公式与镜像同构（系数对了但公式改了，一样是两套模型）")
    print("=" * 82)
    for gid in MODELS:
        std = re.findall(r"moraleStandard\s*=\s*(\d+)", KB_SOURCES[gid])
        check(f"[{gid}] 知识包的 moraleStandard 恰为镜像假设的 {MORALE_STD}（否则士气那几条数值不成立）",
              bool(std) and int(std[0]) == MORALE_STD,
              "moraleStandard=" + (std[0] if std else "缺"))
    for label, pat in (
            ("兵力比 = 我的带兵 / 推荐带兵", r"bm\.lossBaseRate\s*/\s*ratio"),
            ("兵力不足惩罚按 (1 - ratio) 线性叠加",
             r"bm\.underStrengthPenalty\s*\*\s*\(\s*1\.0\s*-\s*ratio\s*\)"),
            ("士气欠缺以基准士气为分母", r"\(moraleStandard\s*-\s*effectiveMorale\)"),
            ("威胁分按分/点线性叠加", r"threatScore\s*\*\s*bm\.threatPenaltyPerPoint"),
            ("区间上下界由 uncertaintyRatio 对称展开", r"1\.0\s*\+\s*bm\.uncertaintyRatio"),
    ):
        check("端上仍是：" + label, re.search(pat, _RESOLVER_SRC) is not None)

    for gid, model in MODELS.items():
        use_model(model)
        print()
        print("=" * 82)
        print(f"一、单调性：方向错了比算不出来更危险  [{gid}]")
        print("=" * 82)
        reference = 16000  # 一个与任何等级无关的基准，只用于比较相对变化
        grid = [int(reference * r) for r in (0.2, 0.4, 0.6, 0.8, 1.0, 1.5, 2.0, 3.0)]
        points = [simulate(reference, s, 0, 100)[0] for s in grid]
        print("  带兵 vs 点估计：" + "  ".join(f"{s}->{p:.3f}" for s, p in zip(grid, points)))
        check(f"[{gid}] 带兵越多，估算战损越低（单调不升）",
              all(a >= b - 1e-12 for a, b in zip(points, points[1:])))
        check(f"[{gid}] 带兵翻三倍时确实更低（不是平坦的一根线）",
              points[-1] < points[0],
              f"{points[-1]:.3f} < {points[0]:.3f}")
        check(f"[{gid}] 达到推荐兵力后仍随兵力继续下降（lossBaseRate/ratio 段）",
              points[4] > points[5] > points[6] > points[7])

        threats = [(0, simulate(reference, reference, 0, 100)[0]),
                   (3, simulate(reference, reference, 3, 100)[0]),
                   (5, simulate(reference, reference, 5, 100)[0])]
        print("  守将威胁分 vs 点估计：" + "  ".join(f"{t}->{p:.3f}" for t, p in threats))
        check(f"[{gid}] 威胁分越高，估算战损越高（单调不降）",
              threats[0][1] <= threats[1][1] <= threats[2][1])
        check(f"[{gid}] 5 分威胁（郭嘉级）确实抬高了战损",
              threats[2][1] > threats[0][1],
              f"+{threats[2][1] - threats[0][1]:.3f}")

        morales = [(100, simulate(reference, reference, 0, 100)[0]),
                   (70, simulate(reference, reference, 0, 70)[0]),
                   (0, simulate(reference, reference, 0, 0)[0])]
        print("  士气 vs 点估计：" + "  ".join(f"{m}->{p:.3f}" for m, p in morales))
        check(f"[{gid}] 士气越低，估算战损越高（单调不降）",
              morales[0][1] <= morales[1][1] <= morales[2][1])
        check(f"[{gid}] 士气 0 时的抬升量恰好等于 moralePenaltyAtZero",
              abs((morales[2][1] - morales[0][1]) - MODEL["moralePenaltyAtZero"]) < 1e-9,
              f"+{morales[2][1] - morales[0][1]:.3f}")

        print()
        print("=" * 82)
        print(f"二、区间自洽：不能出现 92%~130% 这种输出  [{gid}]")
        print("=" * 82)
        worst_high = 0.0
        worst_low = 1.0
        bad_interval = []
        for s in (500, 1500, 3000, 8000, 16000, 30000, 60000):
            for t in (0, 2, 5):
                for m in (0, 40, 100):
                    point, low, high, _ = simulate(reference, s, t, m)
                    worst_high = max(worst_high, high)
                    worst_low = min(worst_low, low)
                    if not (low <= point + 1e-12 and point <= high + 1e-12):
                        bad_interval.append((s, t, m, point, low, high))
                    if high > MAX_LOSS_CEILING + 1e-12 or low < 0:
                        bad_interval.append((s, t, m, point, low, high))
        check(f"[{gid}] 下界 ≤ 点估计 ≤ 上界（全网格 63 组输入）", not bad_interval,
              f"{len(bad_interval)} 组越界")
        check(f"[{gid}] 上界不超过天花板（战损不可能 ≥100%）",
              worst_high <= MAX_LOSS_CEILING + 1e-12, f"最大值 {worst_high:.3f}")
        check(f"[{gid}] 下界不为负", worst_low >= 0.0, f"最小值 {worst_low:.3f}")

        print()
        print("=" * 82)
        print(f"三、分档：阈值一旦乱序，档位就会互相覆盖  [{gid}]")
        print("=" * 82)
        samples = [i / 100.0 for i in range(0, 100)]
        labels = [classify(x) for x in samples]
        check(f"[{gid}] 低损档全部落在基准战损下方",
              all(x < MODEL["lossBaseRate"] for x, l in zip(samples, labels) if l == "LOW_LOSS"))
        check(f"[{gid}] 翻车档全部落在翻车阈值上方",
              all(x >= MODEL["defeatLossRate"] for x, l in zip(samples, labels) if l == "DEFEAT"))
        check(f"[{gid}] 四档都能被命中（没有死档）",
              set(labels) == {"LOW_LOSS", "ACCEPTABLE", "COSTLY", "DEFEAT"},
              "/".join(sorted(set(labels))))

    print()
    print("=" * 82)
    print("四、拒算分支：算不了时必须说原因，而不是硬凑一个数")
    print("=" * 82)
    refusals = len(re.findall(r"BattleSimResult\.Unavailable\(", _RESOLVER_SRC))
    check("至少 4 个拒算分支（带兵过低/等级缺基线/推荐兵力为 0/守军兵力未知）",
          refusals >= 4, f"实测 {refusals} 个")
    check("未知守将不抬高战损（threatScoreOf 未命中返回 0，不臆断成高危）",
          re.search(r"hit\?\.threatScore\s*\?\:\s*0", _RESOLVER_SRC) is not None)
    check("点估计被 clamp 到 MAX_LOSS_RATE", "MAX_LOSS_RATE" in _RESOLVER_SRC)

    print()
    print("=" * 82)
    print("五、战报解析器里不允许出现中文字面量")
    print("=" * 82)
    cjk = re.findall(r"[\u4e00-\u9fff]", _PARSER_SRC)
    check("BattleReportParser.kt 代码部分零中文（匹配词全部来自知识包）",
          not cjk, f"发现 {len(cjk)} 个中文字符")
    for group in REPORT_GROUPS:
        check(f"解析器引用组 {group}", group in _PARSER_SRC)
    for group in REPORT_GROUPS:
        check(f"stzb 知识包登记了 {group}", group in _STZB_KB)
    check("sgz 知识包**故意**没有 REPORT_* 词（无真机样张，宁可如实不判定）",
          not any(g in _SGZ_KB for g in REPORT_GROUPS),
          "真机拿到三战战报样张后补词，并把这条断言改成 6 组")
    check("胜率分母不含 UNKNOWN（没读到 ≠ 输了）",
          "val decisive = win + loss + draw" in _STATS_SRC)
    check("战损配对只有一处定义（lossPair），百分比与摘要不各算一套",
          _REPORT_SRC.count("fun lossPair(") == 1 and "val pair = lossPair()" in _REPORT_SRC)
    check("战报摘要带出原始数（损失/兵力），配错阵营可当场发现",
          "（${pair.first}/${pair.second}）" in _REPORT_SRC)

    print()
    print("=" * 82)
    print("六、接线：模块真的被调用，而不是写好了放着")
    print("=" * 82)
    app_src = strip_kt_comments(read("App.kt"))
    overlay_src = strip_kt_comments(read(os.path.join("overlay", "OverlayWindowManager.kt")))
    sentinel_src = strip_kt_comments(read(os.path.join("tactics", "NightSentinelFlow.kt")))
    leveling_src = strip_kt_comments(read(os.path.join("tactics", "SquadLevelingFlow.kt")))

    check("App 启动时 attach 战报库（否则 append 会静默返回 false）",
          "BattleReportStore.attach(this)" in app_src)
    check("战报会诊时真的解析并入库存", "BattleReportParser.parse(" in overlay_src
          and "BattleReportStore.append(" in overlay_src)
    check("战报会诊后上屏实测对照与统计面板",
          "describeVerification(" in overlay_src and "BattleStats.render(" in overlay_src)
    check("评估守军后追加推演段（不改原天梯结论）",
          "DefenderOutlook.describe(" in overlay_src)
    check("推演输入里真的会问士气（否则 morale 永远是 -1，“士气未读到”就是写死的一句话）",
          "etMorale" in overlay_src)
    check("士气真的传到了推演，而不是收集了不用",
          re.search(r"describe\(\s*result\s*,\s*landLevel\s*,\s*mySoldiers\s*,\s*morale\s*\)",
                    overlay_src) is not None)
    check("士气留空传 -1（未知）而不是 0，0 是“士气归零”的意思",
          re.search(r"toIntOrNull\(\)\s*\?:\s*-1", overlay_src) is not None)
    check("暗夜哨兵走告警总台分档，而不是一律持续鸣叫",
          "NotificationCenter.push(" in sentinel_src and "NotificationCenter.active(" in sentinel_src)
    check("练级熔断走提醒档（一声提示，不再长时间鸣叫）",
          "ThreatCardEngine.remind(" in leveling_src)
    check("换游戏时告警总台清台（否则上一款游戏的敌袭卡会留在屏幕上）",
          "attachToProfileSwitch" in _NOTIF_SRC
          and "NotificationCenter.attachToProfileSwitch(" in app_src)
    check("清台同时复位鸣叫（只清卡不停铃 = 响着但没人知道该关什么）",
          "AlarmRinger.stopAlarm(app)" in _NOTIF_SRC)

    print()
    print("-" * 82)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 战斗推演逻辑未通过验证。")
        return 1
    print(f"{total} 项全部符合预期。")
    print("边界说明：本脚本证明的是方向、区间与拒算行为；")
    print("          真实战损仍受战法与兵种克制影响，系数需靠战报实测校准（BattleResolver.verify）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
