#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
看门狗自愈逻辑的离线验证（状态空间枚举 + 弹窗按键选择）
======================================================

`WatchdogRecovery` 是整个工程里**唯一在感知失败时仍然主动点击**的代码，
也就是"乱点"风险的源头。因此这里不用抽查，而是**枚举状态空间**并断言性质。

复刻 `recoverToMainMap` 与 `dismissAnyDialog` 的判定规则（逐条对照 Kotlin 源码）：

  W1 **必然终止**：无论场景如何跳变，循环次数不超过 maxAttempts，不存在无限自愈循环。
  W2 **已在主图零点击**：初始就是 MAIN_MAP 时，不得发生任何点击。
  W3 **盲点有预算**：感知不可用（OCR 不可用）时，点击次数不得超过
     `MAX_BLIND_TAPS_WHEN_PERCEPTION_DEAD`（=1）。
  W4 **成功必须是验证过的**：只有"最后一次检测确认是 MAIN_MAP"才算成功，
     不能因为"点过了"就返回 true。
  W5 **感知不可用时绝不点弹窗按键**：`dismissAnyDialog` 靠读文字找按钮，
     没有文字就只能返回 false——绝不能"猜一个位置去点"。
  W6 **弹窗按键按知识库顺序优先**：`watchdogKeywords` 是**有序**表，
     必须优先取列表中靠前的关键词，而不是取 OCR 置信度最高的那个。
"""

import os
import re
import sys

# ---------------------------------------------------------------- 从 Kotlin 读取真实常量
#
# 与决策验证同理：盲点预算与关闭词表**从 WatchdogRecovery.kt 解析**，
# 而不是在这里另抄一份。解析失败即报错退出，避免"Kotlin 改了而脚本没改"时静默通过。

_KOTLIN_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "client", "app", "src", "main", "java", "com", "stzb", "assistant",
)


def _read_kotlin(rel_path):
    with open(os.path.join(_KOTLIN_ROOT, rel_path), encoding="utf-8") as fh:
        return fh.read()


_WR_TEXT = _read_kotlin(os.path.join("tactics", "WatchdogRecovery.kt"))

_m = re.search(r"MAX_BLIND_TAPS_WHEN_PERCEPTION_DEAD\s*=\s*(\d+)", _WR_TEXT)
if _m is None:
    raise RuntimeError("无法从 WatchdogRecovery.kt 解析 MAX_BLIND_TAPS_WHEN_PERCEPTION_DEAD")
MAX_BLIND_TAPS = int(_m.group(1))

_m = re.search(r"DISMISS_KEYWORDS\s*=\s*listOf\(([^)]*)\)", _WR_TEXT)
if _m is None:
    raise RuntimeError("无法从 WatchdogRecovery.kt 解析 DISMISS_KEYWORDS")
DISMISS_KEYWORDS = re.findall(r'"((?:[^"\\]|\\.)*)"', _m.group(1))
if len(DISMISS_KEYWORDS) < 3:
    raise RuntimeError(f"DISMISS_KEYWORDS 只解析到 {len(DISMISS_KEYWORDS)} 个，明显不对")


def parse_known_panel_states(text):
    """
    解析"哪些场景状态会走去点空白收起浮层"这一支。

    原先把这 4 个状态名抄在脚本里；现在从源码解析。
    若有人往这一支里加了新状态（或删了一个），这里会跟着变，
    而不是让脚本继续验证一份旧的控制流。
    """
    # 注意：必须先排除 `if (currentState == ...MAIN_MAP) { return true }` 那个单条件分支。
    # 第一版的正则没有排除它，于是只匹配到 MAIN_MAP 一个状态——
    # 好在解析器按设计**报错退出**了，而不是拿一个错的值继续跑。
    m = re.search(
        r"if \((currentState == StzbUiMatcher\.GameState\.\w+"
        r"(?:\s*\|\|\s*currentState == StzbUiMatcher\.GameState\.\w+)+)\s*\)\s*\{",
        text,
    )
    if m is None:
        raise RuntimeError("无法定位'已知次级面板'那一支的 if 条件（应为多个状态用 || 连接）")
    states = re.findall(r"currentState == StzbUiMatcher\.GameState\.(\w+)", m.group(1))
    if len(states) < 2:
        raise RuntimeError(f"只解析到 {len(states)} 个'已知次级面板'状态，明显不对")
    return states


KNOWN_PANEL_STATES = parse_known_panel_states(_WR_TEXT)


# ---------------------------------------------------------------- 复刻 Kotlin 规则

def classify(ocr_available, fingerprint_is_main_map):
    """
    复刻 StzbUiMatcher.classifyGameState 在"读不到文字"时的行为：
    只能靠场景指纹判断是不是大地图，其余一律 UNKNOWN。
    """
    if not ocr_available:
        return "MAIN_MAP" if fingerprint_is_main_map else "UNKNOWN"
    return "OCR_AVAILABLE_SCENE"


def dismiss_any_dialog(ocr_available, matches):
    """
    复刻 dismissAnyDialog。
    返回 (是否成功关闭, 被选中的关键词下标 或 None)。
    感知不可用时必然 (False, None)。
    """
    if not ocr_available or not matches:
        return False, None
    # W6：按知识库顺序优先，其次才比置信度
    best = sorted(
        matches,
        key=lambda m: (
            DISMISS_KEYWORDS.index(m["kw"]) if m["kw"] in DISMISS_KEYWORDS else len(DISMISS_KEYWORDS),
            -m["confidence"],
        ),
    )[0]
    return best["closes"], DISMISS_KEYWORDS.index(best["kw"])


def pick_by_confidence_only(matches):
    """旧实现：只按置信度挑。用于对比，证明顺序修复不是装饰。"""
    if not matches:
        return None
    best = max(matches, key=lambda m: m["confidence"])
    return best["kw"]


def recover_to_main_map(ocr_available, initial_scene, transitions, max_attempts=4):
    """
    复刻 recoverToMainMap 的控制流。

    transitions: dict，描述"点完之后场景变成什么"，键为 ("dismiss"|"blank", 轮次)；
                 缺省表示"场景不变"。
    返回 (是否成功, 点击次数, 盲点次数, 迭代轮数)。
    """
    scene = initial_scene
    taps = 0
    blind_budget_used = 0      # 预算计数器：先自增再判断（与 Kotlin 的 blindTaps 同语义）
    blind_performed = 0        # 实际发生的盲点次数——性质断言应对它做，而不是对预算计数器
    iterations = 0

    def do_tap():
        """一次真实点击；感知不可用时同时记一次"实际盲点"。"""
        nonlocal taps, blind_performed
        taps += 1
        if not ocr_available:
            blind_performed += 1

    for attempt in range(1, max_attempts + 1):
        iterations = attempt
        if scene == "MAIN_MAP":
            return True, taps, blind_budget_used, blind_performed, iterations

        # ★ 统一盲点闸：感知不可用时，任何一次点击都先扣预算。
        # 与 Kotlin 一致：闸放在所有点击分支**之前**，因此新增分支也无法绕过。
        if not ocr_available:
            blind_budget_used += 1
            if blind_budget_used > MAX_BLIND_TAPS:
                break

        # 1) 尝试关闭弹窗
        matches = [{"kw": "确定", "confidence": 0.9, "closes": True}] if ocr_available else []
        dismissed, _ = dismiss_any_dialog(ocr_available, matches)
        if dismissed:
            do_tap()
            scene = transitions.get(("dismiss", attempt), "MAIN_MAP")
            continue

        # 2) 已知次级面板 → 点空白收起（状态表取自源码解析）
        if scene in KNOWN_PANEL_STATES:
            do_tap()
            scene = transitions.get(("blank", attempt), "MAIN_MAP")
            continue

        # 3) 未知场景兜底
        do_tap()
        scene = transitions.get(("blank", attempt), scene)

    return scene == "MAIN_MAP", taps, blind_budget_used, blind_performed, iterations


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

    print("=" * 84)
    print("W1–W4. 枚举状态空间（感知可用×不可用 × 初始场景 × 场景是否可恢复）")
    print("=" * 84)
    print(f"  （常量取自 WatchdogRecovery.kt：盲点预算={MAX_BLIND_TAPS}，"
          f"关闭词表 {len(DISMISS_KEYWORDS)} 项 {DISMISS_KEYWORDS}）")

    scenes = ["MAIN_MAP"] + list(KNOWN_PANEL_STATES) + ["UNKNOWN"]
    # 三种转移：点完就回主图 / 点了永远没用 / 第 3 轮才回主图
    transition_modes = {
        "点完即恢复": {},
        "点了没用(场景不变)": None,      # 特殊处理：不改变场景
        "第3轮才恢复": {"late": True},
    }

    w1 = w2 = w3 = w4 = True
    worst_taps_dead = 0
    worst_taps_alive = 0

    for ocr in (True, False):
        for scene in scenes:
            for mode in transition_modes:
                if mode == "点了没用(场景不变)":
                    trans = {}
                    # 构造"点了也没用"：每次点击后场景保持不变
                    trans = {("blank", i): scene for i in range(1, 5)}
                    trans.update({("dismiss", i): scene for i in range(1, 5)})
                elif mode == "第3轮才恢复":
                    trans = {("blank", 1): scene, ("blank", 2): scene,
                             ("blank", 3): "MAIN_MAP",
                             ("dismiss", 1): scene, ("dismiss", 2): scene,
                             ("dismiss", 3): "MAIN_MAP"}
                else:
                    trans = {}

                ok, taps, budget, blind, iters = recover_to_main_map(ocr, scene, trans)

                # W1：必然终止
                if iters > 4:
                    w1 = False
                # W2：初始即主图 → 零点击
                if scene == "MAIN_MAP" and taps != 0:
                    w2 = False
                # W3：感知不可用 → 盲点不超预算
                if not ocr and blind > MAX_BLIND_TAPS:
                    w3 = False
                # W4：成功必须来自"最终检测确认是主图"
                if ok and mode == "点了没用(场景不变)" and scene != "MAIN_MAP":
                    w4 = False
                if not ocr:
                    worst_taps_dead = max(worst_taps_dead, taps)
                else:
                    worst_taps_alive = max(worst_taps_alive, taps)

    check("W1. 必然终止：迭代轮数不超过 maxAttempts", w1,
          f"覆盖 {2 * len(scenes) * len(transition_modes)} 种组合")
    check("W2. 初始即大地图时不发生任何点击", w2, "零点击")
    check(f"W3. 感知不可用时**实际**盲点次数不超过 {MAX_BLIND_TAPS}", w3,
          f"实际最坏盲点 {worst_taps_dead} 次（注意：预算计数器会先自增再判断，"
          f"因此它的值比实际次数多 1 —— Kotlin 的日志写的正是 blindTaps-1）")
    check("W4. 只有最终确认回到大地图才算成功（不会因'点过了'而返回 true）", w4,
          "覆盖'点了没用'的三种场景")

    print()
    print("  对照：感知不可用 vs 可用时的最坏点击次数")
    print(f"    {'感知状态':<12} | {'最坏点击次数':>12}")
    print(f"    {'-' * 12}-+-{'-' * 12}")
    for label, v in (("OCR 不可用", worst_taps_dead), ("OCR 可用", worst_taps_alive)):
        print(f"    {label:<12} | {v:>12}")
    check("感知不可用时的点击次数显著少于可用时（乱点被压制）",
          worst_taps_dead < worst_taps_alive,
          f"{worst_taps_dead} < {worst_taps_alive}")

    print()
    print("=" * 84)
    print("W5. 感知不可用时绝不点弹窗按键")
    print("=" * 84)
    no_dismiss = True
    for matches in ([], [{"kw": "确定", "confidence": 0.9, "closes": True}]):
        dismissed, _ = dismiss_any_dialog(False, matches)
        if dismissed:
            no_dismiss = False
    check("W5. 读不到文字时 dismissAnyDialog 必须返回 false（不猜位置去点）", no_dismiss,
          "覆盖空匹配与'有匹配但无感知'两种情形")

    print()
    print("=" * 84)
    print("W6. 弹窗按键必须按知识库顺序优先（而不是按 OCR 置信度）")
    print("=" * 84)
    # 构造一个具体反例：置信度最高的恰好是列表里更靠后的「取消」
    matches = [
        {"kw": "确定", "confidence": 0.71, "closes": True},
        {"kw": "取消", "confidence": 0.97, "closes": False},
    ]
    old_kw = pick_by_confidence_only(matches)
    _, new_idx = dismiss_any_dialog(True, matches)
    new_kw = DISMISS_KEYWORDS[new_idx]
    print(f"  场景：弹窗上同时有「确定」(置信度 0.71) 与「取消」(置信度 0.97)")
    print(f"    旧实现（按置信度）会按：{old_kw}    ← 把用户的操作用消了")
    print(f"    新实现（按知识库顺序）会按：{new_kw}")
    check("W6. 有序表里靠前的关键词优先于置信度更高的靠后关键词", new_kw == "确定",
          f"新实现选择 {new_kw}，旧实现会选 {old_kw}")
    check("W6b. 这条修复不是装饰——旧行为确实会选错", old_kw == "取消",
          "反例成立：旧实现在该场景下会选「取消」")

    print()
    print("-" * 84)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 看门狗逻辑未通过验证。")
        return 1
    print(f"{total} 项全部符合预期 —— 看门狗逻辑通过验证。")
    print("边界说明：盲点预算、关闭词表、以及'哪些场景走点空白那一支'的状态表")
    print("          **都从 WatchdogRecovery.kt 解析**，解析失败即报错退出，")
    print("          因此这几项不会出现「Kotlin 改了而脚本没改」的静默漂移。")
    print("          仍然复刻的部分：控制流的分支结构与顺序本身。")
    print("          运行时取值无法静态解析：关闭词表实际会被知识库的")
    print("          watchdogKeywords 覆盖（非空时优先），本脚本只验证代码里的兜底表。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
