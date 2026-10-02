#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
坐标变换数学的离线验证
======================

坐标变换是"点击不准"的核心，而它**完全可以在没有设备的情况下验证**：
把 Kotlin 里那套数学逐步复刻（`CoordinateTransformer` 的画布推导 +
`MapProjection` 的两点标定与世界/屏幕互转），然后在真实与边界分辨率上检验四件事：

  A. 画布推导是否**等比**：`virtualWidth = round(720 * W / H)` 之后，
     scaleX 与 scaleY 必须几乎相等。两轴不等比 = 一条轴系统性偏，这正是"点不准"的形态。
  B. 两点标定是否**能还原校准点**：用一组"真值"生成两个地块的画布坐标，
     再跑一遍标定，看换算回来的位置误差有多大。
  C. **取整代价**：`calibrateFromTwoPoints` 把镜头中心 `roundToInt()` 后再参与换算。
     这里量化这一步到底会引入多少像素的系统偏移。
  D. **往返一致性**：屏幕 → 世界 → 屏幕，误差应当只有"取整到最近一格"的量级。

用法：python tools/validate_coordinate_math.py
"""

import math
import sys

REAL_RESOLUTIONS = [
    (2712, 1220, "本机真机截图（横屏）"),
    (1080, 2400, "常见竖屏 1080p"),
    (1080, 2340, "常见竖屏 1080p(19.5:9)"),
    (1440, 3120, "2K 竖屏"),
    (2400, 1080, "游戏横屏 2400x1080"),
    (1280, 720, "低端横屏 720p"),
    (2560, 1600, "平板 16:10"),
    (1600, 2560, "平板竖屏"),
    (720, 1280, "极低端竖屏"),
    (3440, 1440, "超宽横屏"),
]


# ---------------------------------------------------------------- 与 Kotlin 等价

def virtual_size(phys_w, phys_h):
    """CoordinateTransformer: BASE_HEIGHT=720，宽度按真实长宽比推导。"""
    vw = max(1, int(round(720.0 * phys_w / phys_h)))
    return vw, 720


def calibrate(p1, w1, p2, w2, vw, vh, round_center=True):
    """
    复刻 MapProjection.calibrateFromTwoPoints。
    round_center=False 用于对比"不取整"的情形（用来量化取整代价）。
    """
    dx = float(w2[0] - w1[0])
    dy = float(w2[1] - w1[1])
    if abs(dx) < 0.5 or abs(dy) < 0.5:
        return None
    tile_x = (p2[0] - p1[0]) / dx
    tile_y = (p2[1] - p1[1]) / dy
    if tile_x <= 1.0 or tile_y <= 1.0:
        return None
    cx = vw / 2.0
    cy = vh / 2.0
    center_wx = w1[0] - (p1[0] - cx) / tile_x
    center_wy = w1[1] - (p1[1] - cy) / tile_y
    if round_center:
        center_wx = int(round(center_wx))
        center_wy = int(round(center_wy))
    return {
        "tile_x": tile_x, "tile_y": tile_y,
        "center_x": center_wx, "center_y": center_wy,
        "fx": 0.5, "fy": 0.5,
    }


def world_to_screen(w, cal, vw, vh):
    cx = vw * cal["fx"]
    cy = vh * cal["fy"]
    return (cx + (w[0] - cal["center_x"]) * cal["tile_x"],
            cy + (w[1] - cal["center_y"]) * cal["tile_y"])


def screen_to_world(p, cal, vw, vh):
    cx = vw * cal["fx"]
    cy = vh * cal["fy"]
    wx = cal["center_x"] + (p[0] - cx) / cal["tile_x"]
    wy = cal["center_y"] + (p[1] - cy) / cal["tile_y"]
    return (int(round(wx)), int(round(wy)))


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

    print("=" * 80)
    print("A. 画布推导是否等比（两轴缩放比必须几乎相等）")
    print("=" * 80)
    worst_px = 0.0
    worst_label = ""
    for w, h, label in REAL_RESOLUTIONS:
        vw, vh = virtual_size(w, h)
        sx, sy = w / vw, h / vh
        rel = abs(sx - sy) / sy
        edge_px = rel * w
        if edge_px > worst_px:
            worst_px, worst_label = edge_px, label
        print(f"  {w}x{h:<5} -> 画布 {vw}x{vh}  scaleX={sx:.4f} scaleY={sy:.4f} "
              f"相对差={rel * 100:.3f}%  右边缘偏差≈{edge_px:.2f}px  ({label})")
    # 1px 以内认为可接受（画布宽度取整本身就有亚像素级代价）
    check(
        "A. 所有分辨率下两轴缩放基本等比（边缘偏差 < 1.5px）",
        worst_px < 1.5,
        f"最差 {worst_px:.2f}px（{worst_label}）"
    )

    print()
    print("=" * 80)
    print("B/C. 两点标定的还原误差（关键：镜头中心**不得取整**）")
    print("=" * 80)
    vw, vh = virtual_size(2712, 1220)
    print(f"  场景：画布 {vw}x{vh}")

    err_fixed = 0.0
    err_rounded = 0.0
    for tile in (8.0, 11.0, 14.0, 18.0, 25.0):
        truth = {"tile_x": tile, "tile_y": tile, "center_x": 228.4, "center_y": 132.7,
                 "fx": 0.5, "fy": 0.5}
        w1, w2 = (220, 125), (240, 143)
        p1 = world_to_screen(w1, truth, vw, vh)
        p2 = world_to_screen(w2, truth, vw, vh)

        for round_center, tag in ((False, "不取整(当前实现)"), (True, "取整(旧实现)")):
            cal = calibrate(p1, w1, p2, w2, vw, vh, round_center=round_center)
            if cal is None:
                check(f"tile={tile} {tag} 标定应成功", False)
                continue
            err = 0.0
            for wx in range(180, 281, 5):
                for wy in range(90, 181, 5):
                    got = world_to_screen((wx, wy), cal, vw, vh)
                    want = world_to_screen((wx, wy), truth, vw, vh)
                    err = max(err, abs(got[0] - want[0]), abs(got[1] - want[1]))
            if round_center:
                err_rounded = max(err_rounded, err)
            else:
                err_fixed = max(err_fixed, err)
            print(f"  tile={tile:>4}px {tag}: 最大位置误差 = {err:6.2f}px")

    check(
        "B. 当前实现（镜头中心保留小数）位置误差应为 0",
        err_fixed < 1e-6,
        f"最大误差 {err_fixed:.6f}px"
    )
    check(
        "C. 旧实现（取整）确实会引入显著误差——这正是被修掉的缺陷",
        err_rounded > 1.0,
        f"旧实现最大误差 {err_rounded:.2f}px；新实现 0.00px"
    )

    print()
    print("=" * 80)
    print("D. 往返一致性：屏幕 → 世界 → 屏幕")
    print("=" * 80)
    for tile in (11.0, 14.0):
        truth = {"tile_x": tile, "tile_y": tile, "center_x": 228.4, "center_y": 132.7,
                 "fx": 0.5, "fy": 0.5}
        cal = calibrate(
            world_to_screen((220, 125), truth, vw, vh), (220, 125),
            world_to_screen((240, 143), truth, vw, vh), (240, 143),
            vw, vh,
        )
        worst = 0.0
        for wx in range(190, 271, 3):
            for wy in range(100, 171, 3):
                p = world_to_screen((wx, wy), cal, vw, vh)
                w_back = screen_to_world(p, cal, vw, vh)
                p2 = world_to_screen(w_back, cal, vw, vh)
                worst = max(worst, abs(p2[0] - p[0]), abs(p2[1] - p[1]))
        # 世界坐标是整数格，因此往返偏差不应超过一格
        check(
            f"D. tile={tile:>4}px 往返偏差不超过一格",
            worst <= tile,
            f"往返最大偏差 {worst:.2f}px（一格 = {tile:.2f}px）"
        )

    print()
    print("-" * 80)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 坐标数学未通过验证。")
        return 1
    print(f"{total} 项全部符合预期 —— 坐标数学通过验证。")
    print("说明：本脚本只验证**数学**，不验证设备上报的尺寸/旋转是否正确，")
    print("      也不验证 dispatchGesture 的坐标系是否与设计画布一致——那需要真机。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
