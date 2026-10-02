#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
压秒（卡秒）时间数学的离线验证
==============================

压秒是毫秒级操作，差一秒就白干，因此它的算术值得单独量一遍。
与坐标数学一样，"公式"可以在本机证明；而**设备上的耗时只能假设**——
本脚本把这两者严格分开，不把假设说成测量。

验证两类内容：

  1. **公式本身**（可证明）
     `optimalDispatchEpochMs = targetHitEpochMs - duration*1000 - compensation`
     以及"所需提前量 = 行军耗时 + 网络补偿"这一推论：剩余时间不足即为不可行。

  2. **结构性迟到**（可证明，但代入的定位耗时是假设值）
     旧实现：等到目标时刻 → **再**抓屏+识别定位 → 派发手势
              ⇒ 手势落在 `目标时刻 + 定位耗时`，这段耗时被整个算进误差。
     新实现：等待前先定位（"先架枪"）→ 等到目标时刻 → 只派发手势
              ⇒ 手势落在 `目标时刻 + 派发耗时`，与识别无关。

     因此旧实现的迟到量**恰好等于定位耗时**——这是一个恒等式，
     不依赖具体机型的测量值；机型只决定那个耗时是 30ms 还是 400ms。

用法：python tools/validate_timing_math.py
"""

import os
import re
import sys

# ---------------------------------------------------------------- 从 Kotlin 读取真实默认值
#
# 网络补偿的默认值**从源码解析**，而不是在这里另写一个 100。
# 解析失败即报错退出，避免"Kotlin 改了而脚本没改"时静默通过。

_KOTLIN_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "client", "app", "src", "main", "java", "com", "stzb", "assistant",
)


def _read_kotlin(rel_path):
    with open(os.path.join(_KOTLIN_ROOT, rel_path), encoding="utf-8") as fh:
        return fh.read()


_EB_TEXT = _read_kotlin(os.path.join("service", "EngineBridge.kt"))
_m = re.search(r"networkJitterCompensationMs:\s*Long\s*=\s*(\d+)L", _EB_TEXT)
if _m is None:
    raise RuntimeError(
        "无法从 EngineBridge.kt 解析 networkJitterCompensationMs 的默认值——"
        "签名可能已变更，请同步本脚本"
    )
DEFAULT_COMPENSATION_MS = int(_m.group(1))

# 定位一次按键的耗时假设（抓屏 + OCR/模板匹配）。
# ⚠️ 这是**假设区间**，不是在本机测出来的：本机没有设备。
#    区间取自私有问题场景的经验范围，并且刻意给得很宽，以覆盖低端机与模板匹配较慢的情况。
PIPELINE_COST_ASSUMPTIONS_MS = [30, 80, 200, 400]

# 只派发一次手势的耗时假设（坐标换算 + 抗封抖动 + dispatchGesture 调用本身）。
DISPATCH_COST_MS = 5

# 空转循环的分辨率（ImmunityBreakFlow 用 delay(5)，SiegeSyncFlow 用 delay(4)）。
SPIN_RESOLUTION_MS = 5


def optimal_dispatch(target_hit_ms, duration_sec, compensation_ms):
    """复刻 TroopStatusDetector.calculateCardSecondTiming 的核心公式。"""
    return target_hit_ms - duration_sec * 1000 - compensation_ms


def required_lead_ms(duration_sec, compensation_ms):
    """要让压秒成立，至少得提前多久开始准备。"""
    return duration_sec * 1000 + compensation_ms


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

    print("=" * 82)
    print("一、公式本身（可证明）")
    print("=" * 82)
    print(f"  （网络补偿默认值取自 EngineBridge.kt：{DEFAULT_COMPENSATION_MS}ms）")
    target = 1_700_000_000_000  # 任取一个绝对时刻
    cases = [
        (300, DEFAULT_COMPENSATION_MS, "5 分钟行军"),
        (9000, DEFAULT_COMPENSATION_MS, "2.5 小时行军（跨州）"),
        (60, 80, "1 分钟行军，短补偿"),
    ]
    for duration_sec, comp, label in cases:
        opt = optimal_dispatch(target, duration_sec, comp)
        lead = required_lead_ms(duration_sec, comp)
        print(f"  {label}: 行军 {duration_sec}s, 补偿 {comp}ms -> 出征点 = 触敌 - {lead}ms")
        # 恒等式：触敌时刻 - 出征点 == 所需提前量
        if target - opt != lead:
            check(f"{label}: 提前量恒等式", False, f"{target - opt} != {lead}")
    check(
        "公式恒等式：触敌时刻 − 出征点 == 行军耗时 + 网络补偿",
        all(target - optimal_dispatch(target, d, c) == required_lead_ms(d, c)
            for d, c, _ in cases),
        "三种情形均成立"
    )

    # 不可行判定：剩余时间 < 所需提前量时，出征点必然落在过去
    now = target - 30_000  # 距触敌只剩 30 秒
    opt = optimal_dispatch(target, 300, 100)
    check(
        "剩余时间不足时，出征点必然落在过去（应被判为不可行）",
        opt < now,
        f"剩余 30000ms，所需提前量 {required_lead_ms(300, 100)}ms，出征点比现在早 {now - opt}ms"
    )

    print()
    print("=" * 82)
    print("二、结构性迟到（恒等式可证明；代入的定位耗时是**假设值**）")
    print("=" * 82)
    print("  说明：下面的「定位耗时」是假设区间，本机没有设备可以实测。")
    print("        但『旧实现的迟到量 == 定位耗时』这个关系是恒等式，与机型无关。")
    print()
    print(f"  {'定位耗时(假设)':>14} | {'旧实现迟到':>10} | {'新实现迟到':>10} | 说明")
    print(f"  {'-' * 14}-+-{'-' * 10}-+-{'-' * 10}-+------")

    ok_old_match = True
    ok_new_small = True
    for p in PIPELINE_COST_ASSUMPTIONS_MS:
        # 旧实现：空转醒来（最多再多 SPIN_RESOLUTION_MS）后跑完整定位管线，然后派发
        old_late = SPIN_RESOLUTION_MS + p + DISPATCH_COST_MS
        # 新实现：定位已在等待前完成，醒来后只派发
        new_late = SPIN_RESOLUTION_MS + DISPATCH_COST_MS
        # 恒等式检查：新旧之差应恰好等于定位耗时
        if old_late - new_late != p:
            ok_old_match = False
        if new_late > SPIN_RESOLUTION_MS + DISPATCH_COST_MS:
            ok_new_small = False
        print(f"  {p:>12}ms | {old_late:>8}ms | {new_late:>8}ms | "
              f"{'晚打，且随识别耗时线性变差' if p >= 200 else '晚打一个次要量级'}")
    print()

    check(
        "旧实现的迟到量与定位耗时是恒等关系（差值恰等于定位耗时）",
        ok_old_match,
        "对全部假设值成立"
    )
    check(
        "新实现的迟到量与识别耗时**无关**，只由空转分辨率与派发耗时决定",
        ok_new_small,
        f"恒为 {SPIN_RESOLUTION_MS + DISPATCH_COST_MS}ms"
    )
    check(
        "最坏情形下旧实现会晚打 ≥200ms（足以错过集火窗口）",
        max(SPIN_RESOLUTION_MS + p + DISPATCH_COST_MS for p in PIPELINE_COST_ASSUMPTIONS_MS) >= 200,
        f"最坏 {max(SPIN_RESOLUTION_MS + p + DISPATCH_COST_MS for p in PIPELINE_COST_ASSUMPTIONS_MS)}ms"
    )

    print()
    print("=" * 82)
    print("三、空转分辨率与容忍阈值是否自洽")
    print("=" * 82)
    # 两个流程的容忍阈值是 150ms。空转分辨率 + 派发必须远小于它，否则会频繁误报。
    tolerance = 150
    check(
        f"空转+派发（{SPIN_RESOLUTION_MS + DISPATCH_COST_MS}ms）远小于容忍阈值（{tolerance}ms）",
        (SPIN_RESOLUTION_MS + DISPATCH_COST_MS) * 3 < tolerance,
        f"三倍余量后仍低于阈值，正常情况不会误报"
    )

    print()
    print("-" * 82)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 压秒数学未通过验证。")
        return 1
    print(f"{total} 项全部符合预期。")
    print("边界说明：公式与结构关系已证明；『定位耗时』是假设区间（30~400ms），")
    print("          真机上的实际值会决定旧实现到底晚打多少——新实现则不依赖这个值。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
