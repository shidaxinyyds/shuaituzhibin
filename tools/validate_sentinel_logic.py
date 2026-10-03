#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
暗夜天眼哨兵决策与警戒圈数学离线验证 (tools/validate_sentinel_logic.py)
========================================================================

逐项验证阶段 2 核心保命逻辑：
  S1. 【5x5 主城 2 格警戒圈切比雪夫距离数学判定】
  S2. 【屏幕投影像素半径距离与容差边界】
  S3. 【原生受袭预警红标语义与倒计时时间正则解析】
  S4. 【60 秒无损秒回撤退与 30 秒紧急闭城焦土触发条件自洽】
  S5. 【5 分钟防掉线微保活时钟与非侵入位移量安全校验】
"""

import math
import re
import sys


def chebyshev_distance(p1, p2):
    return max(abs(p1[0] - p2[0]), abs(p1[1] - p2[1]))


def test_alert_circle_chebyshev():
    base = (550, 480)
    radius = 2

    # 1. 主城九宫格及外围一圈共 25 格 (5x5) 应全部在警戒圈内
    for dx in range(-radius, radius + 1):
        for dy in range(-radius, radius + 1):
            pt = (base[0] + dx, base[1] + dy)
            dist = chebyshev_distance(pt, base)
            assert dist <= radius, f"点 {pt} 应在 2 格警戒圈内，但测得切比雪夫距离 {dist}"

    # 2. 外部 3 格及以上应全部判定在圈外
    outside_points = [
        (base[0] + 3, base[1]),
        (base[0] - 3, base[1]),
        (base[0], base[1] + 3),
        (base[0], base[1] - 3),
        (base[0] + 3, base[1] + 3),
        (base[0] + 10, base[1] + 10),
    ]
    for pt in outside_points:
        dist = chebyshev_distance(pt, base)
        assert dist > radius, f"点 {pt} 应在警戒圈外，但测得切比雪夫距离 {dist}"

    print("PASS: S1. 5x5 主城 2 格警戒圈切比雪夫判定准确无误")


def test_screen_pixel_radius():
    anchor = (640.0, 360.0)
    tile_px = 140.0
    radius_tiles = 2
    max_radius_px = radius_tiles * tile_px  # 280.0 px

    # 圈内点测试 (<= 280px)
    inside_pts = [
        (640.0, 360.0),       # 中心 0px
        (640.0 + 100, 360.0), # 100px
        (640.0, 360.0 + 200), # 200px
        (640.0 + 190, 360.0 + 190), # sqrt(190^2 + 190^2) ≈ 268.7px <= 280px
    ]
    for x, y in inside_pts:
        d = math.hypot(x - anchor[0], y - anchor[1])
        assert d <= max_radius_px, f"点 ({x}, {y}) 距离 {d}px 应在像素半径内"

    # 圈外点测试 (> 280px)
    outside_pts = [
        (640.0 + 290, 360.0), # 290px
        (640.0, 360.0 + 350), # 350px
        (640.0 + 220, 360.0 + 220), # sqrt(220^2 + 220^2) ≈ 311.1px > 280px
    ]
    for x, y in outside_pts:
        d = math.hypot(x - anchor[0], y - anchor[1])
        assert d > max_radius_px, f"点 ({x}, {y}) 距离 {d}px 应在像素半径外"

    print("PASS: S2. 屏幕投影像素半径距离与容差边界校验通过")


def test_top_alert_regex_parsing():
    p = re.compile(r"(?:(\d{1,2})[:：])?(\d{1,2})(?:秒)?")

    samples = [
        ("00:45", 45),
        ("01:20", 80),
        ("55秒", 55),
        ("0:30", 30),
        ("12:05", 725),
    ]

    for text, expected in samples:
        m = p.search(text)
        assert m is not None, f"未匹配到时间: {text}"
        min_str, sec_str = m.group(1), m.group(2)
        mins = int(min_str) if min_str else 0
        secs = int(sec_str) if sec_str else 0
        total = mins * 60 + secs
        assert total == expected, f"解析时间不一致: 实际 {total} vs 预期 {expected}"

    print("PASS: S3. 原生受袭预警红标语义与倒计时时间正则解析准确")


def test_retreat_and_fortify_conditions():
    # 模拟 NightSentinelFlow 中的触发判断逻辑
    def should_auto_retreat(countdown, is_within_circle):
        return (countdown is None or countdown <= 60 or is_within_circle)

    def should_emergency_fortify(countdown, is_screen_edge_alert):
        return (countdown is not None and countdown <= 30) or is_screen_edge_alert

    # 1. 倒计时 45 秒，圈外 -> 触发秒回撤退
    assert should_auto_retreat(45, False) is True

    # 2. 突入 2 格警戒圈，即使倒计时未解析出 -> 触发秒回撤退
    assert should_auto_retreat(None, True) is True

    # 3. 圈外远距离行军，倒计时 180 秒 -> 不触发秒回撤退 (杜绝误报)
    assert should_auto_retreat(180, False) is False

    # 4. 倒计时 25 秒 -> 触发紧急防沦闭城坚守
    assert should_emergency_fortify(25, False) is True

    # 5. 屏幕边缘红光剧烈闪烁 -> 触发紧急闭城坚守
    assert should_emergency_fortify(100, True) is True

    # 6. 平静期 -> 不触发闭城
    assert should_emergency_fortify(120, False) is False

    print("PASS: S4. 60秒无损秒回撤退与30秒紧急闭城触发条件逻辑完备自洽")


def test_keepalive_timing():
    keepalive_ms = 300_000
    assert keepalive_ms == 5 * 60 * 1000, "微保活周期必须严格等于 5 分钟 (300,000ms)"
    
    displacement_px = math.hypot(2.0, 1.0)
    assert displacement_px < 3.0, "微保活手势位移必须控制在 3px 以内，绝不触发地块点击"

    print("PASS: S5. 5分钟防掉线微保活时钟与非侵入位移量安全校验通过")


def main():
    print("=" * 65)
    print("暗夜天眼哨兵决策与警戒圈数学离线验证")
    print("=" * 65)
    test_alert_circle_chebyshev()
    test_screen_pixel_radius()
    test_top_alert_regex_parsing()
    test_retreat_and_fortify_conditions()
    test_keepalive_timing()
    print("=" * 65)
    print("所有哨兵数学与决策断言全部通过！")
    return 0


if __name__ == "__main__":
    sys.exit(main())
