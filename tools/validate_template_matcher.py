#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TemplateMatcher 算法实测（在**真机截图**上验证）
================================================

`TemplateMatcher.kt` 是纯 Bitmap 算法，本机没有设备，但算法的正确性**可以离线验证**：
用 Python 逐步复刻同一套数学（灰度块平均降采样 + 零均值归一化互相关 ZNCC +
非重叠次高分判区分度），在真机截图上跑，检验四个必须成立的性质：

  1. **能命中**：从图里裁一小块当模板，在原图里应能找到它，且位置正确、分数接近 1；
  2. **能拒绝**：把模板拿到**完全不同的界面**里搜，不应命中（分数低于阈值）；
  3. **抗亮度变化**：把整帧调暗/调亮后仍应命中同一位置（这是选 ZNCC 而非像素差的理由）；
  4. **对重复图案拒绝**：把模板在帧里贴**两次**，区分度会下降，算法应拒绝而不是猜一个。

第 4 条最关键：游戏里常有重复美术（多个相同图标、多个"确定"），
宁可拒绝也不能点错目标。

用法：
    python tools/validate_template_matcher.py --map <大地图截图> --other <另一界面截图>
"""

import argparse
import os
import sys

import numpy as np
from PIL import Image


# ---------------------------------------------------------------- 与 Kotlin 等价

def to_gray(img, scale):
    """灰度 + 块平均降采样。与 TemplateMatcher.toGray 等价。"""
    a = np.asarray(img.convert("RGB"), dtype=np.float64)
    lum = 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]
    h, w = lum.shape
    gh, gw = max(1, h // scale), max(1, w // scale)
    lum = lum[: gh * scale, : gw * scale]
    return lum.reshape(gh, scale, gw, scale).mean(axis=(1, 3))


def zncc_map(hay, nee):
    """
    返回 (score_map, ...)，score_map[y, x] = 在 (x, y) 处放置模板的 ZNCC。

    与 Kotlin 的逐点实现数学等价：
        分子   = Σ h*(t - tMean)
        分母   = n * hStd * tStd
    用滑窗向量化计算，避免 Python 层循环。
    """
    th, tw = nee.shape
    hh, hw = hay.shape
    if th >= hh or tw >= hw:
        return None

    t = nee - nee.mean()
    t_std = nee.std()
    if t_std < 1e-6:
        return None
    n = float(th * tw)

    # 滑窗视图：(oy, ox, th, tw)
    win = np.lib.stride_tricks.sliding_window_view(hay, (th, tw))
    # 分子
    cross = np.einsum("ijkl,kl->ij", win, t)
    # 窗口内均值与标准差
    wmean = win.mean(axis=(2, 3))
    wvar = np.maximum(0.0, (win ** 2).mean(axis=(2, 3)) - wmean ** 2)
    wstd = np.sqrt(wvar)

    denom = n * wstd * t_std
    with np.errstate(divide="ignore", invalid="ignore"):
        score = np.where(denom > 1e-9, cross / denom, 0.0)
    return np.clip(score, -1.0, 1.0)


def search(frame_img, tpl_img, scale=4, threshold=0.85, min_distinct=0.04):
    """与 TemplateMatcher.search 等价的搜索（全图）。返回 dict 或 None。"""
    hay = to_gray(frame_img, scale)
    nee = to_gray(tpl_img, scale)
    if nee.shape[0] < 4 or nee.shape[1] < 4:
        # 与 Kotlin 一致：模板过小时退回 scale=1
        hay = to_gray(frame_img, 1)
        nee = to_gray(tpl_img, 1)
        scale = 1

    sm = zncc_map(hay, nee)
    if sm is None:
        return None

    th, tw = nee.shape
    idx = int(np.argmax(sm))
    by, bx = divmod(idx, sm.shape[1])
    best = float(sm[by, bx])

    # 非重叠次高分
    mask = np.ones_like(sm, dtype=bool)
    y0, y1 = max(0, by - th + 1), min(sm.shape[0], by + th)
    x0, x1 = max(0, bx - tw + 1), min(sm.shape[1], bx + tw)
    mask[y0:y1, x0:x1] = False
    runner = float(sm[mask].max()) if mask.any() else -1.0

    if best < threshold or (best - runner) < min_distinct:
        return {"rejected": True, "best": best, "runner": runner, "samples": int(sm.size)}
    cx = int((bx + tw / 2) * scale)
    cy = int((by + th / 2) * scale)
    return {
        "rejected": False, "cx": cx, "cy": cy,
        "best": best, "runner": runner, "samples": int(sm.size),
    }


# ---------------------------------------------------------------- 用例

def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--map", required=True, help="大地图截图（作帧）")
    ap.add_argument("--other", required=True, help="另一界面截图（作负例）")
    args = ap.parse_args()

    for p in (args.map, args.other):
        if not os.path.isfile(p):
            print(f"找不到文件: {p}", file=sys.stderr)
            return 2

    frame = Image.open(args.map)
    other = Image.open(args.other)

    # 在帧内取一块区域当作"帧"，并从中裁一块作模板。
    # 选的是底部功能栏附近（固定美术，最能代表"按键/图标"这类目标）。
    fx, fy, fw, fh = 1100, 700, 700, 460
    frame_crop = frame.crop((fx, fy, fx + fw, fy + fh))

    tx, ty, tw, th = 180, 200, 120, 70
    tpl = frame_crop.crop((tx, ty, tx + tw, ty + th))
    # 模板中心在"帧裁剪"坐标下的位置 —— 期望命中点
    expect_x, expect_y = tx + tw // 2, ty + th // 2

    print("=" * 78)
    print("TemplateMatcher 算法实测")
    print("=" * 78)
    print(f"帧(裁剪)     : {fw}x{fh}  ← {os.path.basename(args.map)}")
    print(f"模板         : {tw}x{th} 期望命中 ({expect_x}, {expect_y})")
    print(f"降采样 scale : 4（搜索位置数见下）")
    print("-" * 78)

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

    # ---- 1. 能命中 ----
    r = search(frame_crop, tpl)
    if r is None or r.get("rejected"):
        check("① 应命中同一位置", False, f"结果={r}")
    else:
        err = max(abs(r["cx"] - expect_x), abs(r["cy"] - expect_y))
        check(
            "① 应命中同一位置",
            err <= 4,  # 降采样 scale=4，±4px 内属正常量化误差
            f"命中 ({r['cx']}, {r['cy']})，与期望偏差 {err}px；"
            f"分数={r['best']:.4f} 次高={r['runner']:.4f} "
            f"区分度={r['best'] - r['runner']:.4f} 搜索位置数={r['samples']}"
        )
        check("①b 分数应接近 1", r["best"] > 0.98, f"分数={r['best']:.4f}")

    # ---- 2. 能拒绝（换一个界面） ----
    ow, oh = other.size
    ox = max(0, (ow - fw) // 2)
    oy = max(0, (oh - fh) // 2)
    other_crop = other.crop((ox, oy, ox + fw, oy + fh))
    r2 = search(other_crop, tpl)
    check(
        "② 在另一界面里不应命中",
        r2 is None or r2.get("rejected"),
        f"结果={r2}"
    )

    # ---- 3. 抗亮度变化 ----
    arr = np.asarray(frame_crop.convert("RGB"), dtype=np.float64)
    for name, mut in (("调暗 0.75x", lambda a: a * 0.75),
                      ("调亮 +40", lambda a: np.clip(a + 40, 0, 255))):
        bright = Image.fromarray(np.clip(mut(arr), 0, 255).astype(np.uint8), "RGB")
        r3 = search(bright, tpl)
        if r3 is None or r3.get("rejected"):
            check(f"③ 抗亮度变化（{name}）仍应命中", False, f"结果={r3}")
        else:
            err = max(abs(r3["cx"] - expect_x), abs(r3["cy"] - expect_y))
            check(
                f"③ 抗亮度变化（{name}）仍应命中",
                err <= 4,
                f"命中 ({r3['cx']}, {r3['cy']}) 偏差 {err}px 分数={r3['best']:.4f}"
            )

    # ---- 4. 重复图案应被拒绝 ----
    dup = arr.copy()
    # 把模板贴到另一处（与自身不重叠），制造"重复美术"
    dx, dy = tx + tw + 120, ty + 40
    if dy + th < fh and dx + tw < fw:
        dup[dy:dy + th, dx:dx + tw, :] = arr[ty:ty + th, tx:tx + tw, :]
        dup_img = Image.fromarray(dup.astype(np.uint8), "RGB")
        r4 = search(dup_img, tpl)
        rejected = r4 is None or r4.get("rejected")
        check(
            "④ 出现重复图案时应拒绝（不猜）",
            rejected,
            f"结果={r4}"
        )
    else:
        check("④ 重复图案用例", False, "构造重复图案时越界，用例本身需调整")

    print("-" * 78)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 算法在真机截图上未通过验证。")
        return 1
    print(f"{total} 项全部符合预期 —— 算法在真机截图上通过了命中/拒绝/抗亮度/抗重复 四类检验。")
    print("说明：这是算法层的离线验证，不代表在游戏按键上的实际命中率（那需要真机标定模板）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
