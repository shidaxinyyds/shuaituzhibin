#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P3 拟人时序/轨迹数学的离线验证（Kotlin 忠实镜像）
==================================================

## 为什么需要这个脚本
本机没有 JDK/SDK/NDK，Kotlin 侧写好的 `TrajectoryPlanner.selfTest()` 与
`TimingFingerprintEngine.selfTest()` **在这里一行都跑不了**——它们只能在设备上执行。
但"等 CI 编译过"只能证明**语法成立**，证明不了**分布性质成立**。

因此本脚本把同一套数学**逐式镜像**到 Python，在本机真跑出数字：
钟形剖面到底有没有生效、有界延迟到底会不会贴边堆积、泊松间隔的
变异系数到底是多少。这与 `validate_timing_math.py`、`validate_coordinate_math.py`
是同一条纪律：**能在本机证明的性质，绝不推到真机上去碰运气。**

## 反漂移机制（关键）
镜像最怕的是"Kotlin 改了、Python 没改，于是绿灯验证了一个已经不存在的实现"。
所以本脚本**不硬编码任何常量**：所有阈值常量与公式系数都用正则从 Kotlin
源码里解析；任一正则匹配不上就**直接报错退出**（而不是回退到默认值）。
公式被改写时镜像会立刻失效报警，而不是静默通过。

## 验证内容
1. `TrajectoryPlanner`
   - 时间：首段从 0 起、段间无缝隙/重叠、总覆盖严格等于预算、每段 ≥1ms
   - 几何：每段 ≥2 点、相邻段共享端点（不"手指瞬移"）、末点严格命中 ≤0.6px
   - 边界：所有采样点落在可用区内（贝塞尔凸包性质的实测背书）
   - 形状：等弧长分段 ⇒ 段几何长度近似相等；时长呈钟形（中段明显更快）
2. `TimingFingerprintEngine`
   - 有界延迟：20000 次采样严格落在 [min,max]，且**不得在边界堆出质方**
     （硬截断造成的边界堆积本身就是一条新指纹，这里把它当失败项）
   - 节律语义：日间/深夜的归一化均值必须分层，但都不得越界
   - 泊松间隔：均值量级、变异系数 CV（去周期性的直接度量）、边界堆积率

用法：
    python tools/validate_antiban_math.py            # 断言 + 诊断表
    python tools/validate_antiban_math.py --quiet    # 只输出结论
"""

import argparse
import math
import os
import random as _pyrandom
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
KOTLIN_ANTIBAN = os.path.join(
    ROOT, "client", "app", "src", "main", "java", "com", "stzb", "assistant", "antiban"
)

EPS = 1e-6


def _read(name):
    path = os.path.join(KOTLIN_ANTIBAN, name)
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _grab(text, pattern, name, src):
    m = re.search(pattern, text)
    if m is None:
        raise RuntimeError(
            "无法从 %s 解析 %s——Kotlin 公式/常量可能已改写，请同步本镜像脚本，"
            "否则这里验证的是已经不存在的实现。" % (src, name)
        )
    return m.group(1) if m.groups() else m.group(0)


def _grab_all(text, pattern, names, src):
    m = re.search(pattern, text)
    if m is None:
        raise RuntimeError(
            "无法从 %s 解析 (%s)——Kotlin 公式可能已改写，请同步本镜像脚本。"
            % (src, ", ".join(names))
        )
    return list(m.groups())


# ------------------------------------------------------------------ TrajectoryPlanner 常量
_TP_SRC = "TrajectoryPlanner.kt"
_tp = _read(_TP_SRC)

MAX_SEGMENTS = int(_grab(_tp, r"private const val MAX_SEGMENTS = (\d+)", "MAX_SEGMENTS", _TP_SRC))
MIN_SEGMENTS = int(_grab(_tp, r"private const val MIN_SEGMENTS = (\d+)", "MIN_SEGMENTS", _TP_SRC))
SPLIT_MIN_DISTANCE_PX = float(_grab(_tp, r"private const val SPLIT_MIN_DISTANCE_PX = ([\d.]+)f", "SPLIT_MIN_DISTANCE_PX", _TP_SRC))
SPEED_FLOOR = float(_grab(_tp, r"private const val SPEED_FLOOR = ([\d.]+)f", "SPEED_FLOOR", _TP_SRC))
SEGMENT_JITTER = float(_grab(_tp, r"private const val SEGMENT_JITTER = ([\d.]+)f", "SEGMENT_JITTER", _TP_SRC))

# ------------------------------------------------------------------ TimingFingerprintEngine 系数
_TF_SRC = "TimingFingerprintEngine.kt"
_tf = _read(_TF_SRC)

GAUSS_SIGMA, TAIL_TAU = _grab_all(
    _tf,
    r"val gaussian = z \* ([\d.]+)[\s\S]*?"
    r"val tail = -([\d.]+) \* ln\(u3\)",
    ["gaussian sigma", "tail tau"], _TF_SRC,
)
GAUSS_SIGMA, TAIL_TAU = float(GAUSS_SIGMA), float(TAIL_TAU)
LOGIT_CENTER, LOGIT_SCALE = _grab_all(
    _tf,
    r"private const val LOGIT_CENTER = ([\d.]+)[\s\S]*?private const val LOGIT_SCALE = ([\d.]+)",
    ["LOGIT_CENTER", "LOGIT_SCALE"], _TF_SRC,
)
LOGIT_CENTER, LOGIT_SCALE = float(LOGIT_CENTER), float(LOGIT_SCALE)
SKEW_PERIOD, SKEW_FATIGUE, SKEW_CLAMP = _grab_all(
    _tf,
    r"private const val SKEW_PERIOD = ([\d.]+)[\s\S]*?"
    r"private const val SKEW_FATIGUE = ([\d.]+)[\s\S]*?"
    r"private const val SKEW_CLAMP = ([\d.]+)",
    ["SKEW_PERIOD", "SKEW_FATIGUE", "SKEW_CLAMP"], _TF_SRC,
)
SKEW_PERIOD, SKEW_FATIGUE, SKEW_CLAMP = float(SKEW_PERIOD), float(SKEW_FATIGUE), float(SKEW_CLAMP)
FATIGUE_CAP, FATIGUE_RATE = _grab_all(
    _tf, r"val fatigue = minOf\(([\d.]+), elapsedHours \* ([\d.]+)\)",
    ["fatigue cap", "fatigue rate"], _TF_SRC,
)
FATIGUE_CAP, FATIGUE_RATE = float(FATIGUE_CAP), float(FATIGUE_RATE)
_pf, _pmin, _pc = _grab_all(
    _tf,
    r"floorMs: Long = \(meanMs \* ([\d.]+)\)\.toLong\(\)\.coerceAtLeast\((\d+)L\),\s*\n\s*"
    r"ceilMs: Long = \(meanMs \* ([\d.]+)\)\.toLong\(\)",
    ["poisson floor ratio", "poisson floor min", "poisson ceil ratio"], _TF_SRC,
)
POISSON_FLOOR_RATIO, POISSON_FLOOR_MIN, POISSON_CEIL_RATIO = float(_pf), int(_pmin), float(_pc)
POISSON_MIN_SCALE_RATIO = float(_grab(
    _tf, r"\(meanMs \* ([\d.]+)\)\.toLong\(\)\)\.toDouble\(\)", "poisson min scale ratio", _TF_SRC))

# 昼夜节律表（用于把"节律语义"分层跑断言，而不是依赖脚本运行时刻）
CIRCADIAN = [(n, float(f)) for n, f in re.findall(
    r'([A-Z_]+)\("[^"]*", ([\d.]+)f\)', _tf
)]
if len(CIRCADIAN) < 3:
    raise RuntimeError("无法从 %s 解析 CircadianPeriod 的 speedFactor 表。" % _TF_SRC)


# ------------------------------------------------------------------ RNG 外壳（对齐 Kotlin 取值域）
class Rng(object):
    """Kotlin 的 nextDouble()/nextFloat() 均返回 [0,1)，nextBoolean() 等概率。"""

    def __init__(self, seed=0):
        self._r = _pyrandom.Random(seed)

    def nextDouble(self):
        return self._r.random()

    def nextFloat(self):
        return self._r.random()

    def nextBoolean(self):
        return self._r.random() < 0.5


def hypot(ax, ay):
    return math.sqrt(ax * ax + ay * ay)


# ========================================================================== 1. TrajectoryPlanner
class Bounds(object):
    def __init__(self, left, top, right, bottom):
        self.left, self.top, self.right, self.bottom = left, top, right, bottom

    def clamp_x(self, x):
        return min(max(x, self.left), self.right)

    def clamp_y(self, y):
        return min(max(y, self.top), self.bottom)


def cubic_point(p0, p1, p2, p3, t):
    mt = 1.0 - t
    a, b, c, d = mt * mt * mt, 3 * mt * mt * t, 3 * mt * t * t, t * t * t
    return (a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0],
            a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1])


def quad_point(p0, ctrl, p1, t):
    mt = 1.0 - t
    return (mt * mt * p0[0] + 2 * mt * t * ctrl[0] + t * t * p1[0],
            mt * mt * p0[1] + 2 * mt * t * ctrl[1] + t * t * p1[1])


def speed_profile(u):
    x = min(max(u, 0.0), 1.0)
    return SPEED_FLOOR + (1.0 - SPEED_FLOOR) * 4.0 * x * (1.0 - x)


def sample_polyline(start, end, bounds=None, samples_per_curve=24, rnd=None):
    rnd = rnd or Rng(1)
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    distance = hypot(dx, dy)
    if distance < 1.001:
        return [(start[0], start[1]), (end[0], end[1])]

    unit_x, unit_y = dx / distance, dy / distance
    normal_x, normal_y = -unit_y, unit_x

    curvature = min(35.0, distance * 0.15) * (1.0 if rnd.nextBoolean() else -1.0)
    arc1 = curvature * (0.8 + rnd.nextFloat() * 0.4)
    arc2 = curvature * (0.6 + rnd.nextFloat() * 0.4)

    p0 = (start[0], start[1])
    p1 = (start[0] + dx * 0.32 + normal_x * arc1, start[1] + dy * 0.32 + normal_y * arc1)
    p2 = (start[0] + dx * 0.78 + normal_x * arc2, start[1] + dy * 0.78 + normal_y * arc2)

    overshoot = min(22.0, distance * (0.04 + rnd.nextFloat() * 0.04))
    p_over = (end[0] + unit_x * overshoot, end[1] + unit_y * overshoot)
    p_end = (end[0], end[1])

    if bounds is not None and overshoot > 0:
        bx, by = bounds.clamp_x(p_over[0]), bounds.clamp_y(p_over[1])
        if bx != p_over[0] or by != p_over[1]:
            limited = min(overshoot, hypot(bx - end[0], by - end[1]))
            p_over = (end[0] + unit_x * limited, end[1] + unit_y * limited)

    rebound_ctrl = (
        p_over[0] - unit_x * (overshoot * 0.3) + normal_x * (rnd.nextFloat() * 2 - 1),
        p_over[1] - unit_y * (overshoot * 0.3) + normal_y * (rnd.nextFloat() * 2 - 1),
    )

    def inside(pt):
        if bounds is None:
            return pt
        return (bounds.clamp_x(pt[0]), bounds.clamp_y(pt[1]))

    c0, c1, c2 = inside(p0), inside(p1), inside(p2)
    c_over, c_end, c_rebound = inside(p_over), inside(p_end), inside(rebound_ctrl)

    pts = [c0]
    for i in range(1, samples_per_curve + 1):
        pts.append(cubic_point(c0, c1, c2, c_over, float(i) / samples_per_curve))
    for i in range(1, samples_per_curve + 1):
        pts.append(quad_point(c_over, c_rebound, c_end, float(i) / samples_per_curve))
    pts[-1] = p_end
    return pts


def arc_pos_on_polyline(pts, acc, p):
    """把一个点投影回原折线，返回它在原折线上的弧长位置（取最近线段）。"""
    best_d, best_s = 1e18, 0.0
    for k in range(len(pts) - 1):
        ax, ay = pts[k]
        bx, by = pts[k + 1]
        vx, vy = bx - ax, by - ay
        seg2 = vx * vx + vy * vy
        t = 0.5 if seg2 <= 1e-12 else ((p[0] - ax) * vx + (p[1] - ay) * vy) / seg2
        t = max(0.0, min(1.0, t))
        cx, cy = ax + vx * t, ay + vy * t
        d = (p[0] - cx) ** 2 + (p[1] - cy) ** 2
        if d < best_d:
            best_d, best_s = d, acc[k] + (acc[k + 1] - acc[k]) * t
    return best_s


def cumulative_length(pts):
    acc = [0.0] * len(pts)
    s = 0.0
    for i in range(1, len(pts)):
        s += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        acc[i] = s
    return acc


def resample_by_arc_length(pts, acc, total, count):
    n = max(2, count)
    if len(pts) < 2 or total <= 0.0001:
        last = pts[-1]
        return [(last[0], last[1])] * n
    out = []
    j = 0
    for i in range(n):
        target = total * i / (n - 1)
        # 语义：停在中点左侧的区间内，保证 j+1 合法
        while j + 1 < len(acc) - 1 and acc[j + 1] < target:
            j += 1
        l0, l1 = acc[j], acc[j + 1]
        f = min(max((target - l0) / (l1 - l0), 0.0), 1.0) if (l1 - l0) > 1e-6 else 0.0
        a, b = pts[j], pts[j + 1]
        out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
    out[-1] = (pts[-1][0], pts[-1][1])
    return out


class Segment(object):
    def __init__(self, points, start_ms, duration_ms):
        self.points = points
        self.start_ms = start_ms
        self.duration_ms = duration_ms

    @property
    def end_ms(self):
        return self.start_ms + self.duration_ms


def _kotlin_int(x):
    """Kotlin 的 Float/Double -> Long 是向零截断。"""
    return int(x) if x >= 0 else -int(-x)


def plan(start, end, total_ms, bounds=None, segments=0, points_per_segment=6, rnd=None):
    rnd = rnd or Rng(1)
    distance = hypot(end[0] - start[0], end[1] - start[1])
    if total_ms <= 0:
        return [Segment([(start[0], start[1]), (end[0], end[1])], 0, 1)]
    if distance < SPLIT_MIN_DISTANCE_PX:
        return [Segment(sample_polyline(start, end, bounds, 8, rnd), 0, total_ms)]

    pts = sample_polyline(start, end, bounds, 24, rnd)
    acc = cumulative_length(pts)
    total = acc[-1]
    if total <= 0.0001:
        return [Segment([(start[0], start[1]), (end[0], end[1])], 0, total_ms)]

    k = segments if segments > 0 else (3 + _kotlin_int(distance / 120.0))
    k = min(max(k, MIN_SEGMENTS), MAX_SEGMENTS)
    k = min(k, max(1, int(total_ms)))
    m = max(2, points_per_segment)

    uniform = resample_by_arc_length(pts, acc, total, k * m + 1)
    buckets = [uniform[i * m: i * m + m + 1] for i in range(k)]
    uni_acc = cumulative_length(uniform)

    raw = []
    for i in range(k):
        u = (i + 0.5) / k
        arc = uni_acc[(i + 1) * m] - uni_acc[i * m]
        raw.append((max(arc, 0.5) / speed_profile(u))
                   * (1.0 + (rnd.nextFloat() * 2 - 1) * SEGMENT_JITTER))
    raw_sum = sum(raw) or 1.0

    durations = [max(1, _kotlin_int(raw[i] / raw_sum * total_ms)) for i in range(k)]
    used = sum(durations)
    durations[k - 1] += (total_ms - used)
    if durations[k - 1] < 1:
        durations[k - 1] = 1

    out, cursor = [], 0
    for i in range(k):
        out.append(Segment(buckets[i], cursor, durations[i]))
        cursor += durations[i]
    return out


# ========================================================================== 2. TimingFingerprintEngine
def generate_bounded_delay_ms(lo, hi, rnd, period_factor=1.0, fatigue_hours=0.0):
    """镜像 generateBoundedDelayMs；节律/疲劳作参数注入，以便按档位分别取证。"""
    lo, hi = min(lo, hi), max(lo, hi)
    if hi - lo <= 1:
        return hi
    u1 = max(1e-7, rnd.nextDouble())
    u2 = rnd.nextDouble()
    z = math.sqrt(-2.0 * math.log(u1)) * math.cos(2.0 * math.pi * u2)
    shape = z * GAUSS_SIGMA + (-TAIL_TAU * math.log(max(1e-7, rnd.nextDouble())))
    logit = (shape - LOGIT_CENTER) / LOGIT_SCALE
    fatigue = min(FATIGUE_CAP, fatigue_hours * FATIGUE_RATE)
    skew = max(min(((period_factor - 1.0) * SKEW_PERIOD) + fatigue * SKEW_FATIGUE, SKEW_CLAMP), -SKEW_CLAMP)
    q = 1.0 / (1.0 + math.exp(-(logit + skew)))
    v = lo + _kotlin_int((hi - lo) * q)
    return min(max(v, lo), hi)


def poisson_interval_ms(mean_ms, rnd, floor_ms=None, ceil_ms=None):
    if floor_ms is None:
        floor_ms = max(int(mean_ms * POISSON_FLOOR_RATIO), POISSON_FLOOR_MIN)
    if ceil_ms is None:
        ceil_ms = int(mean_ms * POISSON_CEIL_RATIO)
    if mean_ms <= 0:
        return max(floor_ms, 1)
    lo_b = min(floor_ms, ceil_ms)
    scale = max(mean_ms - lo_b, int(mean_ms * POISSON_MIN_SCALE_RATIO))
    u = max(1e-7, rnd.nextDouble())
    raw = lo_b - scale * math.log(u)
    return min(max(_kotlin_int(raw), lo_b), ceil_ms)


# ========================================================================== 断言与诊断
FAILS = []
DIAG = []


def check(ok, label, detail=""):
    if ok:
        print("  [PASS] %-46s %s" % (label, detail))
    else:
        FAILS.append("%s %s" % (label, detail))
        print("  [FAIL] %-46s %s" % (label, detail))


def seg_len(seg):
    """段内折线弧长（不是弦长：弦长在下弧/回弹区会明显偏小）。"""
    acc = cumulative_length(seg.points)
    return acc[-1]


def seg_chord(seg):
    return hypot(seg.points[-1][0] - seg.points[0][0], seg.points[-1][1] - seg.points[0][1])


def verify_trajectory():
    print("\n=== 1. TrajectoryPlanner：时间覆盖 / 连续性 / 落点 / 边界 / 速度剖面 ===")
    bounds = Bounds(0, 0, 1280, 720)
    cases = [
        ((640, 450), (640, 150), 520, "竖直长拖（地图）"),
        ((200, 360), (1000, 360), 640, "水平长拖"),
        ((1200, 700), (1270, 715), 300, "贴右下角短拖（考验越界裁剪）"),
        ((5, 5), (30, 8), 240, "贴左上角（考验弧度越界）"),
        ((300, 300), (306, 303), 180, "超短距（应退化单段）"),
    ]
    for s, e, budget, tag in cases:
        rnd = Rng(20261006)
        p = plan(s, e, budget, bounds, rnd=rnd)
        problems = []
        if p[0].start_ms != 0:
            problems.append("首段未从 0 起(%d)" % p[0].start_ms)
        for i in range(1, len(p)):
            if p[i].start_ms != p[i - 1].end_ms:
                problems.append("第%d段有时隙/重叠(%d vs %d)" % (i, p[i].start_ms, p[i - 1].end_ms))
        covered = p[-1].end_ms
        if abs(covered - budget) > 1:
            problems.append("总时长未覆盖预算(%d vs %d)" % (covered, budget))
        if any(x.duration_ms < 1 for x in p):
            problems.append("出现非正时长段")
        for seg in p:
            if len(seg.points) < 2:
                problems.append("存在点数<2 的段")
                break
            for (x, y) in seg.points:
                if not (math.isfinite(x) and math.isfinite(y)):
                    problems.append("非有限坐标")
                    break
        last = p[-1].points[-1]
        if hypot(last[0] - e[0], last[1] - e[1]) > 0.6:
            problems.append("落点偏移 %.2fpx" % hypot(last[0] - e[0], last[1] - e[1]))
        for i in range(1, len(p)):
            a, b = p[i - 1].points[-1], p[i].points[0]
            if hypot(a[0] - b[0], a[1] - b[1]) > 0.6:
                problems.append("第%d段端点不连续（手指瞬移）" % i)
                break
        for seg in p:
            for (x, y) in seg.points:
                if x < bounds.left - 0.5 or x > bounds.right + 0.5 or y < bounds.top - 0.5 or y > bounds.bottom + 0.5:
                    problems.append("采样点越界 (%.1f,%.1f)" % (x, y))
                    break
        check(not problems, "%s" % tag,
              "; ".join(problems[:3]) if problems
              else "段数=%d 覆盖=%dms 末点误差=%.2fpx" % (len(p), covered, hypot(last[0] - e[0], last[1] - e[1])))


def verify_bell_profile():
    print("\n=== 2. 速度剖面：easeInOut 必须真的生效（否则这条能力仍是死代码）===")
    bounds = Bounds(0, 0, 1280, 720)
    # 关闭抖动不可行（Kotlin 无该开关），因此用大样本统计而非单次判定
    first_mid, last_mid = [], []
    off = Bounds(0, 0, 100000, 100000)
    for seed in range(400):
        rnd = Rng(seed)
        p = plan((640, 600), (640, 120), 600, off, segments=6, rnd=rnd)
        if len(p) < 5:
            continue
        d0, dm, dl = p[0].duration_ms, p[len(p) // 2].duration_ms, p[-1].duration_ms
        first_mid.append(float(dm) / d0)
        last_mid.append(float(dm) / dl)
    fm = sum(first_mid) / len(first_mid)
    lm = sum(last_mid) / len(last_mid)
    ok_mid = len([x for x in first_mid if x < 1.0]) >= 380 and len([x for x in last_mid if x < 1.0]) >= 380
    check(ok_mid, "中段时长普遍短于首/尾段",
          "中/首=%.3f 中/尾=%.3f（<1 即中段更快）" % (fm, lm))
    ratio = min(fm, lm)
    check(ratio <= 0.85, "钟形落差具备可提取量级",
          "min(中/首, 中/尾)=%.3f ≤ 0.85（恒速滑动该值≈1.0）" % ratio)

    # 分段的两条真实不变量：
    #   (a) 重采样顶点在原折线上严格等弧长（重采样算法正确）
    #   (b) 每段的**沿程速度** = 渲染弧长/时长，必须呈钟形
    # 注意：“各段渲染弧长相等”并不是不变量——顶点在原曲线上等弧长，
    # 但过冲处的 U 型转弯会被弦切角，所以渲染弧长会略短于曲线弧长。
    # 这恰好是我们想要的：时长按**实际会渲染出来的弧长**分配，
    # 系统采样时速度才真的等于剖面。
    src = sample_polyline((640, 600), (640, 120), None, 24, Rng(1))
    a = cumulative_length(src)
    tot = a[-1]
    uni = resample_by_arc_length(src, a, tot, 37)
    spacing_err = []
    for i in range(len(uni)):
        ideal = tot * i / (len(uni) - 1)
        spacing_err.append(abs(arc_pos_on_polyline(src, a, uni[i]) - ideal))
    check(max(spacing_err) < 0.05, "重采样顶点在原折线上等弧长",
          "最大弧长位置误差=%.4fpx（共 %d 点）" % (max(spacing_err), len(uni)))
    check(abs(arc_pos_on_polyline(src, a, uni[-1]) - tot) < 1e-6,
          "末点严格落在原折线终点", "弧长位置=%.6f / 应为 %.6f"
          % (arc_pos_on_polyline(src, a, uni[-1]), tot))
    
    # 逐段渲染速度必须呈钟形（这才是“easeInOut 生效”的直接度量）
    ratios_first, ratios_last = [], []
    for seed in range(400):
        p = plan((640, 600), (640, 120), 600, off, segments=6, rnd=Rng(seed + 1))
        if len(p) < 5:
            continue
        sp = [seg_len(x) / max(1, x.duration_ms) for x in p]
        mid = sp[len(sp) // 2]
        ratios_first.append(mid / sp[0])
        ratios_last.append(mid / sp[-1])
    rf = sum(ratios_first) / len(ratios_first)
    rl = sum(ratios_last) / len(ratios_last)
    ok = len([x for x in ratios_first if x > 1.15]) >= 380 and len([x for x in ratios_last if x > 1.15]) >= 380
    check(ok, "沿程速度剖面是钟形（中段真的更快）",
          "中段/首段=%.3f 中段/尾段=%.3f（恒速则均≈1.0）" % (rf, rl))
    check(min(rf, rl) >= 1.5, "速度剖面具备可提取量级",
          "min(中/首, 中/尾)=%.3f ≥ 1.5" % min(rf, rl))
    
    # 诊断：为何“渲染弧长”不等于“曲线弧长”（不是失败项，是几何事实）
    p = plan((640, 600), (640, 120), 600, off, segments=6, rnd=Rng(1))
    arcs = [seg_len(x) for x in p]
    print("         └ 各段渲染弧长 %.1f~%.1fpx（末段处在过冲 U 型转弯，弦切角使其偏短）；"
          % (min(arcs), max(arcs)))
    print("           时长按渲染弧长分配 ⇒ 系统采样时速度才真正等于剖面")


def verify_no_boundary_escape():
    print("\n=== 3. 凸包裁剪：随机大批量手势都不得越出派发区 ===")
    bounds = Bounds(0, 0, 1080, 2400)
    rnd = Rng(4242)
    escaped, miss_end = 0, 0.0
    for _ in range(4000):
        sx = bounds.left + rnd.nextFloat() * (bounds.right - bounds.left)
        sy = bounds.top + rnd.nextFloat() * (bounds.bottom - bounds.top)
        ex = bounds.left + rnd.nextFloat() * (bounds.right - bounds.left)
        ey = bounds.top + rnd.nextFloat() * (bounds.bottom - bounds.top)
        p = plan((sx, sy), (ex, ey), int(200 + rnd.nextFloat() * 800), bounds, rnd=rnd)
        for seg in p:
            for (x, y) in seg.points:
                if x < bounds.left - 0.01 or x > bounds.right + 0.01 or y < bounds.top - 0.01 or y > bounds.bottom + 0.01:
                    escaped += 1
                    break
        d = hypot(p[-1].points[-1][0] - ex, p[-1].points[-1][1] - ey)
        miss_end = max(miss_end, d)
    check(escaped == 0, "4000 条随机手势无一点越界", "越界点数=%d" % escaped)
    check(miss_end <= 0.61, "落点精度最坏值可接受", "最大落点偏差=%.3fpx" % miss_end)


def verify_tiny_budget():
    print("\n=== 4. 病态输入：预算比段数还短 / 零时长 / 零距离 ===")
    bounds = Bounds(0, 0, 1280, 720)
    problems = []
    for budget in (1, 2, 3, 5, 8):
        p = plan((640, 600), (640, 120), budget, bounds, rnd=Rng(budget))
        if p[-1].end_ms != budget:
            problems.append("%dms 覆盖=%d" % (budget, p[-1].end_ms))
        if any(x.duration_ms < 1 for x in p):
            problems.append("%dms 出现非正段" % budget)
    check(not problems, "极端短预算仍严格覆盖且每段≥1ms", "; ".join(problems) or "1/2/3/5/8ms 全通过")
    p0 = plan((100, 100), (600, 600), 0, bounds, rnd=Rng(1))
    check(p0[-1].end_ms >= 1, "totalMs=0 不产生 0 时长手势", "覆盖=%dms" % p0[-1].end_ms)
    pd = plan((100, 100), (100, 100), 300, bounds, rnd=Rng(2))
    check(len(pd) == 1 and pd[-1].duration_ms == 300, "零距离退化为单段合法手势",
          "段数=%d 时长=%d" % (len(pd), pd[-1].duration_ms))


def verify_bounded_delay():
    print("\n=== 5. 有界拟人延迟：区间是硬契约，且边界不得堆出质方 ===")
    lo, hi = 350, 700
    n = 20000
    for name, factor in CIRCADIAN:
        rnd = Rng(abs(hash(name)) % 100000)
        vals = [generate_bounded_delay_ms(lo, hi, rnd, period_factor=factor) for _ in range(n)]
        out = sum(1 for v in vals if v < lo or v > hi)
        span = hi - lo
        mean_norm = (sum(vals) / n - lo) / float(span)
        at_hi = sum(1 for v in vals if v == hi) / float(n)          # 真正的原子质量
        near_hi = sum(1 for v in vals if v >= hi - max(1, span // 100)) / float(n)
        at_lo = sum(1 for v in vals if v == lo) / float(n)
        distinct = len(set(vals))
        DIAG.append(("有界延迟 " + name,
                     "均值位=%.2f 不同取值=%d/%d 严格贴hi=%.2f%% 末1%%区间=%.2f%%"
                     % (mean_norm, distinct, span + 1, at_hi * 100, near_hi * 100)))
        check(out == 0, "%s: %d 次采样全部在 [%d,%d] 内" % (name, n, lo, hi), "越界=%d" % out)
        check(at_hi < 0.02, "%s: 未在上限堆出质量块" % name,
              "严格等于 maxMs 的占 %.2f%%（硬截断/饱和会在此堆积，形成新的固定值指纹）"
              % (at_hi * 100))
        check(at_lo < 0.02, "%s: 未在下限堆出质量块" % name, "严格等于 minMs 的占 %.2f%%" % (at_lo * 100))
        check(near_hi < 0.15, "%s: 上限附近平坦，未集中" % name, "末 1%% 区间占 %.2f%%" % (near_hi * 100))
        check(distinct > span * 0.5, "%s: 分布仍有足够分散度" % name,
              "不同取值=%d / 区间宽 %d" % (distinct, span))
        # 节律语义：日间/晚间应居中，深夜应明显上推但不贴顶
        band = {"ACTIVE_DAY": (0.40, 0.65), "PEAK_EVENING": (0.38, 0.62),
                "DORMANT_NIGHT": (0.70, 0.92)}.get(name)
        if band:
            check(band[0] <= mean_norm <= band[1], "%s: 均值位置在档位带内" % name,
                  "实测 %.2f ∈ [%.2f, %.2f]" % (mean_norm, band[0], band[1]))

    # 节律与疲劳必须真的分层（否则“节律”只是装饰）
    means = {}
    for name, factor in CIRCADIAN:
        rnd = Rng(1234)
        means[name] = sum(generate_bounded_delay_ms(1000, 2000, rnd, period_factor=factor)
                          for _ in range(8000)) / 8000.0
    DIAG.append(("节律分层(1000~2000)", "; ".join("%s=%.0fms" % (k, v) for k, v in sorted(means.items()))))
    day = [v for k, v in means.items() if "DAY" in k]
    night = [v for k, v in means.items() if "NIGHT" in k]
    eve = [v for k, v in means.items() if "EVENING" in k]
    if day and night:
        check(night[0] > day[0] + 100, "深夜显著慢于日间",
              "日间≈%.0fms 深夜≈%.0fms（差 %.0fms）" % (day[0], night[0], night[0] - day[0]))
    if day and eve:
        check(eve[0] < day[0], "晚间活跃期比日间更快（反方向偏斜也成立）",
              "晚间≈%.0fms < 日间≈%.0fms" % (eve[0], day[0]))
    check(max(means.values()) < 2000, "各节律均值均未贴顶", "最大均值=%.0fms < 2000ms" % max(means.values()))
    # 疲劳单调性：久跑应更靠上限，但依旧有界
    rnd_a, rnd_b = Rng(555), Rng(555)
    fresh = sum(generate_bounded_delay_ms(1000, 2000, rnd_a, period_factor=1.0, fatigue_hours=0.0) for _ in range(8000)) / 8000.0
    tired = sum(generate_bounded_delay_ms(1000, 2000, rnd_b, period_factor=1.0, fatigue_hours=8.0) for _ in range(8000)) / 8000.0
    check(tired > fresh, "疲劳把分布往上推（而非越界）", "新手=%.0fms 久跑=%.0fms" % (fresh, tired))
    # 深夜下的疲劳必须仍可分辨（SKEW_CLAMP 定得太小会把疲劳抹平）
    rnd_c, rnd_d = Rng(556), Rng(556)
    nf = sum(generate_bounded_delay_ms(1000, 2000, rnd_c, period_factor=2.5, fatigue_hours=0.0) for _ in range(8000)) / 8000.0
    nt = sum(generate_bounded_delay_ms(1000, 2000, rnd_d, period_factor=2.5, fatigue_hours=8.0) for _ in range(8000)) / 8000.0
    check(nt > nf + 5, "深夜档里疲劳仍不被 clamp 吞掉", "深夜=%.0fms 深夜+8h=%.0fms" % (nf, nt))


def verify_poisson():
    print("\n=== 6. 轮询间隔（不应期+无记忆长尾）：去周期性 CV 与边界堆积率 ===")
    for mean in (250, 500, 15000):
        rnd = Rng(77 + mean)
        vals = [poisson_interval_ms(mean, rnd) for _ in range(20000)]
        n = len(vals)
        mu = sum(vals) / n
        floor_ms = max(int(mean * POISSON_FLOOR_RATIO), POISSON_FLOOR_MIN)
        ceil_ms = int(mean * POISSON_CEIL_RATIO)
        out = sum(1 for v in vals if v < floor_ms or v > ceil_ms)
        var = sum((v - mu) ** 2 for v in vals) / n
        cv = math.sqrt(var) / mu if mu else 0.0
        at_hi = sum(1 for v in vals if v == ceil_ms) / float(n)
        at_lo = sum(1 for v in vals if v == floor_ms) / float(n)
        # 单一取值占比（真正的“固定值指纹”度量：整型分辨率下应用模式而非 distinct）
        counts = {}
        for v in vals:
            counts[v] = counts.get(v, 0) + 1
        mode_val, mode_cnt = max(counts.items(), key=lambda kv: kv[1])
        mode_share = mode_cnt / float(n)
        near_floor_share = sum(counts.get(floor_ms + i, 0) for i in range(1, 6)) / float(n)
        DIAG.append(("泊松 mean=%dms" % mean,
                     "区间[%d,%d] 实测均值=%.0f CV=%.3f 贴ceil=%.2f%% 贴floor=%.2f%% 单值峰=%.2f%%"
                     % (floor_ms, ceil_ms, mu, cv, at_hi * 100, at_lo * 100, mode_share * 100)))
        check(out == 0, "mean=%d: 20000 次不越界" % mean, "越界=%d" % out)
        check(cv >= 0.35, "mean=%d: 变异系数足够（去周期性）" % mean,
              "CV=%.3f（固定周期≈0；不应期+指数长尾≈0.55）" % cv)
        check(mu >= mean * 0.8 and mu <= mean * 1.35, "mean=%d: 均值量级保持" % mean,
              "实测=%.0fms（目标≈%dms）" % (mu, mean))
        # 不应期上可以有“连续密度峰”，但不得有“截断跳变”：
        # 看地板值与其后 5ms 的总质量是否同一量级，就能区分两者。
        check(at_lo < 0.02 and at_lo < near_floor_share + 0.01,
              "mean=%d: floor 上是密度峰而不是截断原子" % mean,
              "等于 floor 的占 %.2f%%，紧邻 5ms 内占 %.2f%%（裸指数硬截断会是 36%% vs 5%%）"
              % (at_lo * 100, near_floor_share * 100))
        check(at_hi < 0.03, "mean=%d: 上限只是保险网" % mean,
              "严格等于 ceil=%d 的占 %.2f%%" % (ceil_ms, at_hi * 100))
        check(mode_share < 0.03, "mean=%d: 没有任何单值占优" % mean,
              "最高峰值 %d 仅占 %.2f%%（固定周期会是 100%%）" % (mode_val, mode_share * 100))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    print("=" * 78)
    print("P3 拟人数学离线验证 · 常量全部由 Kotlin 源码解析（防镜像漂移）")
    print("=" * 78)
    print("  TrajectoryPlanner: MIN/MAX_SEGMENTS=%d/%d SPLIT=%.0fpx SPEED_FLOOR=%.2f JITTER=%.0f%%"
          % (MIN_SEGMENTS, MAX_SEGMENTS, SPLIT_MIN_DISTANCE_PX, SPEED_FLOOR, SEGMENT_JITTER * 100))
    print("  TimingFingerprint: sigma=%.2f tail_tau=%.2f logit=(%.3f,%.2f) skew=(%.2f,%.2f)clamp=%.2f"
          % (GAUSS_SIGMA, TAIL_TAU, LOGIT_CENTER, LOGIT_SCALE, SKEW_PERIOD, SKEW_FATIGUE, SKEW_CLAMP))
    print("  Poisson: floor=max(%.2f×mean,%dms) ceil=%.2f×mean min_scale=%.2f×mean"
          % (POISSON_FLOOR_RATIO, POISSON_FLOOR_MIN, POISSON_CEIL_RATIO, POISSON_MIN_SCALE_RATIO))

    verify_trajectory()
    verify_bell_profile()
    verify_no_boundary_escape()
    verify_tiny_budget()
    verify_bounded_delay()
    verify_poisson()

    if not args.quiet and DIAG:
        print("\n--- 诊断明细 ---")
        for k, v in DIAG:
            print("  · %-22s %s" % (k, v))

    print("\n" + "=" * 78)
    if FAILS:
        print("❌ 未通过 %d 项：" % len(FAILS))
        for f in FAILS:
            print("   - " + f)
        return 1
    print("✅ P3 拟人数学全部不变量在本机成立")
    return 0


if __name__ == "__main__":
    sys.exit(main())
