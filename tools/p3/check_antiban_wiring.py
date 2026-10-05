#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P3 防封能力「接线契约」闸门 (check_antiban_wiring.py)
====================================================

## 为什么需要这个脚本（不是重复劳动）
`tools/validate_antiban_math.py` 证明的是**数学性质**成立：速度剖面真的钟形、
有界延迟真的不贴边、泊松间隔真的去了周期性。但数学成立 ≠ 产品生效。本仓库
已经栽过一次完全相同的跟头：`BezierTrajectory.easeInOut` 写得很对，**却没有任何
调用者**，于是速度剖面在真实手势里从来没生效过，而所有代码看上去都"很正常"。

所以这一条闸门盯的是**调用点**，即"能力有没有真的接在链路上"：

  1. 每一次动作延迟都必须走**有界**原语；分钟级微歇绝不允许混进动作之间
     （那会让夜哨「点页签 → 点撤退」之间停三分钟，用户的部队白死）。
  2. 已删除的无界 API `generateHumanDelay` 不得复活（它没有上界，是 P3a 的根因）。
  3. 滑动必须真的走**多段连续 stroke**，同时**保留**旧的单条回退（fail-closed）。
  4. 固定周期轮询（250/500/15000ms）不得回退成硬 `delay(常量)`。
  5. 两条**跨文件数值不变量**：
       • ticker 的泊松上界必须 < 60s，否则按「HH:mm」整分匹配的任务可能被漏触发；
       • 夜袭哨兵的抖动上界必须 ≤ 1.5×，因为它是安全攸关路径，长尾会拖慢敌袭发现。
  6. 自检（logSelfTest）必须在 App 启动时被真的调用，否则失效永远是静默的。

## 反漂移立场
凡是"公式换了写法"的解析点，**解析不到就直接硬失败**，绝不回退默认值——
一个默默不检查的闸门比没有闸门更危险（它会给人虚假的安全感）。这一点与
`validate_antiban_math.py` 同源。

用法：
    python tools/p3/check_antiban_wiring.py
    python tools/p3/check_antiban_wiring.py --selftest    # 注入已知缺陷，验证闸门真的会拦
"""

import argparse
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

JAVA_ROOT = os.path.join("client", "app", "src", "main", "java", "com", "stzb", "assistant")

FILES = {
    "coordinator": os.path.join(JAVA_ROOT, "antiban", "AntiBanCoordinator.kt"),
    "timing": os.path.join(JAVA_ROOT, "antiban", "TimingFingerprintEngine.kt"),
    "kinetic": os.path.join(JAVA_ROOT, "antiban", "KineticTouchEngine.kt"),
    "bridge": os.path.join(JAVA_ROOT, "service", "EngineBridge.kt"),
    "touch": os.path.join(JAVA_ROOT, "service", "AutoTouchService.kt"),
    "sentinel": os.path.join(JAVA_ROOT, "tactics", "NightSentinelFlow.kt"),
    "garrison": os.path.join(JAVA_ROOT, "tactics", "GarrisonStripperFlow.kt"),
    "ticker": os.path.join(JAVA_ROOT, "tactics", "ScheduledTaskManager.kt"),
    "leveling": os.path.join(JAVA_ROOT, "tactics", "SquadLevelingFlow.kt"),
    "app": os.path.join(JAVA_ROOT, "App.kt"),
}


class ContractError(Exception):
    """接线形态与闸门假设不符：必须人工确认并同步本脚本，不能默认放行。"""


def load_sources(root=REPO):
    out = {}
    for key, rel in FILES.items():
        path = os.path.join(root, rel)
        if not os.path.isfile(path):
            raise ContractError("找不到源文件 %s" % rel)
        with io.open(path, "r", encoding="utf-8") as fh:
            out[key] = fh.read()
    return out


def grab(text, pattern, name, where):
    """解析到一个子串就必须拿到；拿不到即视为接线形态已改变。"""
    m = re.search(pattern, text, re.S)
    if m is None:
        raise ContractError(
            "无法在 %s 中解析 %s。接线形态可能已被改写——请同步本闸门，"
            "否则这里校验的是已经不存在的实现（假绿）。" % (where, name)
        )
    return m


def strip_comments(text):
    """去掉 `//` 行注释与 `/* */` 块注释（保留字符串里的内容）。

    必须做这一步：这些注释里会故意引用旧的固定周期写法（“以前是
    `delay(250)`，现在改成泊松”）。不剥注释，“按字面拦掉硬编码周期”这条规则
    就会把**说明文字本身**当成回退，造成假红——而假红的后果是所有人开始忽略该闸门。
    """
    out = []
    i, n = 0, len(text)
    in_str = in_char = in_line = in_block = False
    while i < n:
        c = text[i]
        d = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and d == "/":
                in_block = False
                i += 2
                continue
            if c == "\n":
                out.append(c)  # 保行号，便于人肉对照
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            out.append(c)
            if c == "\\":
                if i + 1 < n:
                    out.append(text[i + 1])
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if c == "/" and d == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and d == "*":
            in_block = True
            i += 2
            continue
        if c == '"':
            in_str = True
        elif c == "'":
            in_char = True
        out.append(c)
        i += 1
    return "".join(out)


def func_body(text, header_pattern, where):
    """按花括号配对取出某个函数体的完整文本（含首尾花括号）。"""
    m = grab(text, header_pattern, "函数定义 " + header_pattern.split("(")[0], where)
    i = text.index("{", m.start())
    depth = 0
    j = i
    in_str = False
    while j < len(text):
        c = text[j]
        if in_str:
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[i:j + 1]
        j += 1
    raise ContractError("%s 的花括号不配对，无法取函数体" % where)


# ---------------------------------------------------------------- 各项规则

def check_delay_funnel(src):
    """动作延迟必须走有界原语，且微歇不得内嵌在动作之间。"""
    problems = []
    body = func_body(src["coordinator"], r"suspend fun injectActionDelay\(", "AntiBanCoordinator")
    if "generateBoundedDelayMs" not in body:
        problems.append("injectActionDelay 未调用 generateBoundedDelayMs：动作延迟又变回无界了")
    if "noteHumanAction" not in body:
        problems.append("injectActionDelay 未调用 noteHumanAction：疲劳/微歇会按进程存活时长累积")
    for banned in ("shouldTakeMicroBreak", "consumeMicroBreak", "generateHumanDelay"):
        if banned in body:
            problems.append("injectActionDelay 内嵌了 %s：分钟级停顿会插进「两次点击之间」" % banned)

    hb = func_body(src["bridge"], r"suspend fun humanDelay\(", "EngineBridge")
    if "injectActionDelay(minMs, maxMs)" not in re.sub(r"\s+", " ", hb):
        problems.append("EngineBridge.humanDelay 没有把 (minMs, maxMs) 原样传给有界入口")
    if "/ 3" in hb:
        problems.append("EngineBridge.humanDelay 仍在用 (maxMs-minMs)/3 造 variance：那是无界时代的调用形状")

    # 无界 API 不得复活
    for key, text in src.items():
        if re.search(r"fun generateHumanDelay\s*\(", text):
            problems.append("%s 里重新出现了无界延迟 API generateHumanDelay（P3a 的根因）" % key)
    return problems


def check_swipe_is_chained(src):
    """滑动必须真的多段变速，且必须保留 fail-closed 回退。"""
    problems = []
    sw = func_body(src["touch"], r"suspend fun swipeVirtual\(", "AutoTouchService")
    if "buildVariableSpeedSwipe" not in sw:
        problems.append("swipeVirtual 不再经过多段规划：滑动退回成恒速（曲线弯但速度恒定）")
    if "createHumanPath" not in sw:
        problems.append("swipeVirtual 丢了单条贝塞尔回退：拆段一旦失败，整个拖动会失效而不是降级")

    bv = func_body(src["touch"], r"fun buildVariableSpeedSwipe\(", "AutoTouchService")
    if "planInertialSwipe" not in bv:
        problems.append("buildVariableSpeedSwipe 未调用 planInertialSwipe：规划器又成了死代码")
    if "continueStroke(" not in bv:
        problems.append("buildVariableSpeedSwipe 没用 continueStroke 链式续笔：多段会被当成多次独立点按")
    if "plan.size - 1" not in bv:
        problems.append("buildVariableSpeedSwipe 没有「最后一段 willContinue=false」的判定：手指可能永远不抬起")
    if "return null" not in bv or "catch" not in bv:
        problems.append("buildVariableSpeedSwipe 缺少异常回退：构造失败必须降级，不能让手势异常冒到调用方")
    return problems


def check_polling_is_poissonian(src):
    """固定周期轮询不得回退；两处安全攸关路径必须带显式窄带。"""
    problems = []

    for key, header, where in (
        ("bridge", r"suspend fun waitForState\(", "EngineBridge.waitForState"),
        ("bridge", r"suspend fun waitForButton\(", "EngineBridge.waitForButton"),
        ("garrison", r"suspend fun waitForBattleReport\(", "GarrisonStripperFlow.waitForBattleReport"),
        ("ticker", r"fun startTicker\(", "ScheduledTaskManager.startTicker"),
    ):
        body = func_body(src[key], header, where)
        if "poissonIntervalMs" not in body:
            problems.append("%s 的轮询周期又变成固定值（泊松去周期性已失效）" % where)

    # 哨兵的休眠不单独成函数，直接按整文件扫描（但必须带显式窄带，见数值不变量）
    if "poissonIntervalMs" not in src["sentinel"]:
        problems.append("NightSentinelFlow 的巡检休眠又变成硬 delay(N)：等间距采样网格重新出现")

    # 回退成硬编码常量是最典型的退化，直接按字面拦掉（只看代码，不看注释）
    for key, needle, where in (
        ("bridge", "delay(250)", "EngineBridge"),
        ("garrison", "delay(500)", "GarrisonStripperFlow"),
        ("ticker", "delay(15000)", "ScheduledTaskManager"),
    ):
        if needle in strip_comments(src[key]):
            problems.append("%s 仍存在固定周期 %s" % (where, needle))
    return problems


def check_numeric_invariants(src):
    """跨文件数值不变量：上界与语义必须彼此自洽。"""
    problems = []

    # 1) 泊松默认上界倍率（与 validate_antiban_math.py 同源的反漂移解析）
    m = grab(src["timing"], r"ceilMs:\s*Long\s*=\s*\(meanMs\s*\*\s*([0-9.]+)\)",
             "poissonIntervalMs 的默认上界倍率", "TimingFingerprintEngine")
    ceil_ratio = float(m.group(1))

    # 2) ticker：按「HH:mm」整分匹配 ⇒ 相邻采样间隔必须 < 60s
    mt = grab(src["ticker"], r"TICKER_MEAN_MS\s*=\s*(\d+)L?",
              "TICKER_MEAN_MS", "ScheduledTaskManager")
    tick_mean = int(mt.group(1))
    worst = tick_mean * ceil_ratio
    if worst >= 60_000:
        problems.append(
            "ticker 最坏间隔 %.1fs ≥ 60s（均值 %ds × 上界 %.1f）："
            "定时任务按整分字符串匹配，可能出现整个分钟窗口没有一次采样而漏触发"
            % (worst / 1000.0, tick_mean, ceil_ratio))

    # 3) 哨兵：安全攸关，抖动窄带必须有上界
    ms = grab(src["sentinel"], r"floorMs\s*=\s*\(mean\s*\*\s*([0-9.]+)f?\)",
              "哨兵巡检抖动下界系数", "NightSentinelFlow")
    mc = grab(src["sentinel"], r"ceilMs\s*=\s*\(mean\s*\*\s*([0-9.]+)f?\)",
              "哨兵巡检抖动上界系数", "NightSentinelFlow")
    lo_f, hi_f = float(ms.group(1)), float(mc.group(1))
    if hi_f > 1.5:
        problems.append(
            "夜袭哨兵巡检抖动上界 %.2f× 过宽（安全攸关路径，长尾会把敌袭发现时间拖长）" % hi_f)
    if lo_f < 0.4:
        problems.append(
            "夜袭哨兵巡检抖动下界 %.2f× 过窄：会退化成高频狂抓屏" % lo_f)

    # 4) 有界延迟的节律偏置必须给疲劳留头部空间，否则「深夜」与「深夜+8h」同值
    mp = grab(src["timing"], r"SKEW_PERIOD\s*=\s*([0-9.]+)", "SKEW_PERIOD", "TimingFingerprintEngine")
    mf = grab(src["timing"], r"SKEW_CLAMP\s*=\s*([0-9.]+)", "SKEW_CLAMP", "TimingFingerprintEngine")
    night_skew = (2.5 - 1.0) * float(mp.group(1))
    if night_skew >= float(mf.group(1)):
        problems.append(
            "深夜节律偏置 %.2f 已顶到 clamp %.2f：疲劳贡献会被吞掉，两档语义合并成一条"
            % (night_skew, float(mf.group(1))))
    return problems


def check_no_dead_capability(src):
    """新原语必须有调用者——这是 easeInOut 那条教训的直接固化。"""
    problems = []
    all_text = "\n".join(src.values())

    def count_calls(symbol):
        # 允许两种 Kotlin 调用形态：`Foo.bar(...)` 与尾随 lambda `Foo.bar { ... }`（后者无括号）
        return len(re.findall(r"(?<!\w)" + symbol + r"\s*[({]", all_text))

    for symbol, need, label in (
        ("maybeTakeMicroBreak", 2, "生理微歇入口"),
        ("noteHumanAction", 2, "会话活动登记"),
        ("planInertialSwipe", 2, "多段变速滑动规划"),
        ("poissonIntervalMs", 5, "泊松轮询原语"),
        ("generateBoundedDelayMs", 2, "有界拟人延迟"),
    ):
        if count_calls(symbol) < need:
            problems.append(
                "%s（%s）只有 %d 处出现（含定义/自检），少于 %d：能力已写好但没接线，"
                "与当年的 easeInOut 同罪" % (symbol, label, count_calls(symbol), need))

    # 启动期自检必须真的被调用，否则失效永远是静默的
    selftest_hosts = {
        "KineticTouchEngine": "kinetic",
        "TimingFingerprintEngine": "timing",
    }
    for cls, host in selftest_hosts.items():
        if ("%s.logSelfTest()" % cls) not in src["app"]:
            problems.append("App.kt 未调用 %s.logSelfTest()：拟人数学的静默失效不会进日志" % cls)
        if "fun logSelfTest" not in src[host]:
            problems.append("%s 缺少 logSelfTest()" % cls)
    return problems


CHECKS = (
    ("动作延迟走有界原语", check_delay_funnel),
    ("滑动真的多段变速且可回退", check_swipe_is_chained),
    ("轮询已去周期性", check_polling_is_poissonian),
    ("跨文件数值不变量自洽", check_numeric_invariants),
    ("没有再写出死能力", check_no_dead_capability),
)


def run_all(src):
    problems = []
    for _title, fn in CHECKS:
        problems += fn(src)
    return problems


# ---------------------------------------------------------------- 反例自测

def run_selftest():
    """
    注入 9 条**已知缺陷**（都是真实会话里出现过或一旦回退就会出现的形态），
    逐条确认闸门会拦下来；同时确认"原样"是通过的。
    """
    base = load_sources()
    ok = True

    def mutate(key, old, new, expect, label):
        src = dict(base)
        if old not in src[key]:
            print("  [BAD ] %s：注入点找不到原文，闸门可能已与源码脱节" % label)
            return False
        src[key] = src[key].replace(old, new, 1)
        problems = run_all(src)
        hit = any(expect in p for p in problems)
        print("  [%s] %s%s" % ("PASS" if hit else "BAD ", label,
                               "" if hit else " → 未被拦下：%s" % problems[:2]))
        return hit

    # 0) 原样必须通过
    p0 = run_all(base)
    print("[*] 现状自检（应当全绿）")
    if p0:
        ok = False
        for p in p0:
            print("  [BAD ] 现状被自己拦下: %s" % p)
    else:
        print("  [PASS] 当前接线的 5 组契约全部成立")

    print("[*] 反例注入（每一条都必须被拦下）")
    cases = [
        ("coordinator", "generateBoundedDelayMs(minMs, maxMs)", "generateHumanDelay(minMs, maxMs)",
         "又变回无界", "延迟退回无界 API"),
        ("coordinator", "        TimingFingerprintEngine.noteHumanAction()\n", "",
         "noteHumanAction", "丢掉会话登记"),
        ("coordinator", "val delayMs = TimingFingerprintEngine",
         "if (TimingFingerprintEngine.shouldTakeMicroBreak()) delay(180000L)\n        val delayMs = TimingFingerprintEngine",
         "分钟级停顿会插进", "微歇塞回动作之间"),
        ("bridge", "injectActionDelay(minMs, maxMs)", "injectActionDelay(minMs, (maxMs - minMs) / 3)",
         "无界时代的调用形状", "humanDelay 重新造 variance"),
        ("touch", "buildVariableSpeedSwipe(p0, p3, totalMs)", "BezierTrajectory.createHumanPath(p0, p3)",
         "退回成恒速", "滑动退回单条 stroke"),
        ("ticker", "TICKER_MEAN_MS = 15000L", "TICKER_MEAN_MS = 30000L",
         "≥ 60s", "ticker 周期放宽到最坏 90s"),
        ("sentinel", "ceilMs = (mean * 1.3f)", "ceilMs = (mean * 3.0f)",
         "抖动上界 3.00× 过宽", "哨兵沿用默认长尾上界"),
        ("timing", "SKEW_PERIOD = 0.9", "SKEW_PERIOD = 1.8",
         "疲劳贡献会被吞掉", "节律偏置顶穿 clamp"),
        ("app", "com.stzb.assistant.antiban.TimingFingerprintEngine.logSelfTest()", "",
         "未调用 TimingFingerprintEngine.logSelfTest", "启动自检被摘掉"),
    ]
    for key, old, new, expect, label in cases:
        if not mutate(key, old, new, expect, label):
            ok = False

    print("=" * 70)
    print("✅ 反例自测全部拦下" if ok else "❌ 反例自测有漏网项")
    return 0 if ok else 1


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="注入已知缺陷，验证闸门真的会拦")
    ap.add_argument("--root", default=REPO, help="仓库根目录（默认脚本上级）")
    args = ap.parse_args(argv)

    if args.selftest:
        return run_selftest()

    try:
        src = load_sources(args.root)
        problems = run_all(src)
    except ContractError as e:
        print("❌ 闸门与源码脱节: %s" % e)
        return 1

    print("=" * 70)
    print("P3 防封能力接线契约")
    print("=" * 70)
    for title, fn in CHECKS:
        try:
            mine = fn(src)
        except ContractError as e:
            print("FAIL | %-28s | %s" % (title, e))
            return 1
        if mine:
            print("FAIL | %s" % title)
            for p in mine:
                print("       - %s" % p)
        else:
            print("PASS | %s" % title)

    if any(fn(src) for _t, fn in CHECKS):
        print("\n结论: 存在接线回退，须修复后才能交付。")
        return 1
    print("\n✅ 结论: 拟人能力已全部接在真实调用链上（延迟/滑动/轮询三条都成立）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
