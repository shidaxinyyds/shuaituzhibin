#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TemplateMatcher 算法实测（在**真机截图**上验证）
================================================

`TemplateMatcher.kt` 是纯 Bitmap 算法，本机没有设备，但算法的正确性**可以离线验证**：
用 Python 逐步复刻同一套数学（灰度块平均降采样 + 零均值归一化互相关 ZNCC +
非重叠次高分判区分度），在真机截图上跑，检验五个必须成立的性质：

  1. **能命中**：从图里裁一小块当模板，在原图里应能找到它，且位置正确、分数接近 1；
  2. **能拒绝**：把模板拿到**完全不同的界面**里搜，不应命中（分数低于阈值）；
  3. **抗亮度变化**：把整帧调暗/调亮后仍应命中同一位置（这是选 ZNCC 而非像素差的理由）；
  4. **对重复图案拒绝**：把模板在帧里贴**两次**，区分度会下降，算法应拒绝而不是猜一个；
  5. **抗裁切相位**：模板裁在非块网格对齐处时，仍应靠亚格精修命中且位置正确。

第 4 条最关键：游戏里常有重复美术（多个相同图标、多个"确定"），
宁可拒绝也不能点错目标。

还有第五个性质，是真机截图把它问出来的（⑤⑥⑦）：**抗裁切相位**。
`toGray` 从各自 Bitmap 的 (0,0) 起做 scale×scale 块平均，于是"事先裁好的模板 PNG"
与"当前这一帧"之间的块网格存在 0..scale-1 的随机相位差；相位不对齐时同一份美术
的得分会凭空掉一截。合成图上能掉到 0.40，真机美术上掉多少只能由真机截图回答 ——
实测掉到 0.86~0.95（离 0.85 阈值只剩一点余量），而负例最高 0.67，
说明**不能靠降阈值容忍**，必须做亚格精修。这三条用例就是精修的验收：
⑤ 帧中间、⑥ 贴左上边缘、⑦ 贴右下边缘（后两条覆盖精修邻域被帧边界夹掉的分支）。

夹具的两处对齐要求（曾经写死绝对像素，导致 1080 高的真机截图把用例裁进黑边、
报出"算法失败"的假结论）：帧框按图片尺寸取比例，模板边界与副本偏移都对齐到
降采样块网格 —— 注意 ⑤⑥⑦ 是**故意不对齐**的。

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


def zncc_at(hay, x, y, t_zero, t_std, tw, th):
    """原分辨率下单个位置的 ZNCC（与 zncc_map 同一套数学，只是不做向量化）。"""
    # 调用方必须已经做过边界检查；越界说明边界检查本身写错了，
    # 静默返回 0.0 会把"精修漏了某个相位"伪装成"那个相位分数低"。
    assert x >= 0 and y >= 0, f"起点为负 ({x},{y})"
    assert hay.shape[0] - y >= th and hay.shape[1] - x >= tw, \
        f"窗口越界: 起点=({x},{y}) 尺寸={tw}x{th} 图={hay.shape[1]}x{hay.shape[0]}"
    win = hay[y:y + th, x:x + tw]
    wm = float(win.mean())
    wstd = float(win.std())
    if wstd < 1e-9:
        return 0.0
    denom = float(tw * th) * wstd * t_std
    if denom < 1e-9:
        return 0.0
    return max(-1.0, min(1.0, float(((win - wm) * t_zero).sum()) / denom))


def refine_phase(frame_full, tpl_full, bx, by, blocks_w, blocks_h, s):
    """降采样最佳位置的**亚格相位精修**。

    返回 (最佳偏移 dx, dy, 分数)；无法精修时返回 None。
    为什么必须精修：to_gray 从各自 Bitmap 的 (0,0) 起做 s×s 块平均，
    模板 PNG 的裁切相位相对帧是随机的 0..s-1，落在半格上时同内容的 ZNCC 也会掉
    （真机截图实测：对齐 1.0000 → 非对齐 0.86~0.95，离 0.85 阈值只剩一点余量）。

    这里**刻意与 Kotlin 的 refinePhase 同构**（只截候选邻域、按邻域内相对坐标做边界检查），
    而不是在整帧上直接试、越界就跳过 —— 因为这两者必须给出同一个数字，
    这份脚本才算得上是 Kotlin 实现的可执行规格；否则我在脚本里验证过的分支，
    到 Kotlin 里可能根本没被验证过。
    """
    tw = blocks_w * s
    th = blocks_h * s
    if tw < 4 or th < 4:
        return None
    if tw > frame_full.shape[1] or th > frame_full.shape[0]:
        return None
    if tw > tpl_full.shape[1] or th > tpl_full.shape[0]:
        return None
    t = tpl_full[:th, :tw]
    t_mean = float(t.mean())
    t_std = float(t.std())
    if t_std < 1e-6:
        return None
    t_zero = t - t_mean

    off = s - 1
    x0 = bx * s
    y0 = by * s
    fw, fh = frame_full.shape[1], frame_full.shape[0]
    left = max(0, x0 - off)
    top = max(0, y0 - off)
    right = min(fw, x0 + tw + off)
    bottom = min(fh, y0 + th + off)
    nw = right - left
    nh = bottom - top
    if nw < tw or nh < th:
        return None
    win = frame_full[top:bottom, left:right]

    base_x = x0 - left
    base_y = y0 - top
    best = -2.0
    best_dx = 0
    best_dy = 0
    for dy in range(-off, s):
        ry = base_y + dy
        if ry < 0 or ry + th > nh:
            continue
        for dx in range(-off, s):
            rx = base_x + dx
            if rx < 0 or rx + tw > nw:
                continue
            v = zncc_at(win, rx, ry, t_zero, t_std, tw, th)
            if v > best:
                best = v
                best_dx = dx
                best_dy = dy
    if best < -1.0:
        return None
    return best_dx, best_dy, best


def search(frame_img, tpl_img, scale=4, threshold=0.85, min_distinct=0.04, refine_below=0.95):
    """与 TemplateMatcher.search 等价的搜索（全图）。返回 dict 或 None。

    refine_below：降采样分数低于它时才做亚格精修（多数命中本来就接近 1.0，
    不必为它们付额外代价；而相位损失恰好只发生在分数偏低的时候）。
    """
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
    coarse = best

    # 非重叠次高分
    mask = np.ones_like(sm, dtype=bool)
    y0, y1 = max(0, by - th + 1), min(sm.shape[0], by + th)
    x0, x1 = max(0, bx - tw + 1), min(sm.shape[1], bx + tw)
    mask[y0:y1, x0:x1] = False
    runner = float(sm[mask].max()) if mask.any() else -1.0

    # ---- 亚格相位精修 ----
    refined = False
    if best < refine_below and scale > 1:
        full = to_gray(frame_img, 1)
        tpl_full = to_gray(tpl_img, 1)
        hit = refine_phase(full, tpl_full, bx, by, tw, th, scale)
        if hit is not None:
            dx, dy, v = hit
            if v > best:
                best = v
                refined = True
                # 中心按**原分辨率**算：左上角 = 降采样最佳格 × scale + 亚格偏移
                cx = int(bx * scale + dx + tw * scale / 2)
                cy = int(by * scale + dy + th * scale / 2)
                if best < threshold or (best - runner) < min_distinct:
                    return {"rejected": True, "best": best, "coarse": coarse,
                            "runner": runner, "refined": refined, "samples": int(sm.size)}
                return {"rejected": False, "cx": cx, "cy": cy, "best": best,
                        "coarse": coarse, "runner": runner, "refined": refined,
                        "samples": int(sm.size)}

    if best < threshold or (best - runner) < min_distinct:
        return {"rejected": True, "best": best, "coarse": coarse,
                "runner": runner, "refined": refined, "samples": int(sm.size)}
    cx = int((bx + tw / 2) * scale)
    cy = int((by + th / 2) * scale)
    return {
        "rejected": False, "cx": cx, "cy": cy, "best": best, "coarse": coarse,
        "runner": runner, "refined": refined, "samples": int(sm.size),
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
    #
    # 这些框**必须按图片尺寸算比例**，不能写死绝对像素：写死成 1100/700/700x460
    # 时，只有 ≥1800x1160 的截图才装得下，而率土真机横屏常见是 2340x1080 / 2400x1080，
    # 高度不够时 PIL 会用黑边补齐 —— 于是"模板裁在空白上"，
    # 跑出来的 FAIL 是**夹具坏了**，不是算法坏了。宁可现在把分辨率兼容做对。
    W, H = frame.size
    min_w, min_h = 640, 360            # 再小就裁不出有信息量的模板
    if W < min_w or H < min_h:
        print(f"截图太小：{W}x{H}，至少需要 {min_w}x{min_h}（模板要能裁出有内容的一块）",
              file=sys.stderr)
        return 2
    # 负例也得装得下同一个"帧"，否则两边公共可用尺寸为准
    ow_all, oh_all = other.size
    fw = int(min(W, ow_all) * 0.37)
    fh = int(min(H, oh_all) * 0.43)
    fx = int(W * 0.30)
    fy = max(0, min(int(H * 0.45), H - fh))
    if fw < 160 or fh < 120:
        print(f"两张截图里可用的公共区域太小（{fw}x{fh}），换分辨率更高的一张", file=sys.stderr)
        return 2
    frame_crop = frame.crop((fx, fy, fx + fw, fy + fh))

    tx = int(fw * 0.26)
    ty = int(fh * 0.43)
    tw = max(32, int(fw * 0.17))
    th = max(24, int(fh * 0.15))
    # 模板边界**必须落在降采样块网格上**（对齐到 scale 的整数倍）：
    # to_gray 是从帧的 (0,0) 起按 4x4 块平均的，模板若从半格处裁（tx=166），
    # 它自己的块与帧的块就错开 2px，同内容也对不齐 —— 用例 ① 会假失败，
    # 而且会把"夹具的网格错位"误诊成"算法不行"。
    ALIGN = 4
    tx = max(ALIGN, round(tx / ALIGN) * ALIGN)
    ty = max(ALIGN, round(ty / ALIGN) * ALIGN)
    tw = max(ALIGN * 8, round(tw / ALIGN) * ALIGN)
    th = max(ALIGN * 6, round(th / ALIGN) * ALIGN)
    if tx + tw >= fw or ty + th >= fh:
        print(f"对齐后的模板框越出帧外（tx={tx},ty={ty},tw={tw},th={th},帧={fw}x{fh}），换更大的截图",
              file=sys.stderr)
        return 2
    tpl = frame_crop.crop((tx, ty, tx + tw, ty + th))
    # 模板中心在"帧裁剪"坐标下的位置 —— 期望命中点
    expect_x, expect_y = tx + tw // 2, ty + th // 2

    # 夹具自检：裁出来的这块如果几乎是纯色（落在黑边、天空、空白面板上），
    # ZNCC 的分母会退化，用例 ①③④ 全部失去意义 —— 先说清是夹具不行。
    tpl_gray = to_gray(tpl, 1)
    if float(tpl_gray.std()) < 3.0:
        print(f"❌ 裁出的模板几乎纯色（灰度标准差={tpl_gray.std():.2f}），"
              f"这个位置没有可识别的美术。换一张截图或让它包含底部功能栏。", file=sys.stderr)
        return 2

    print("=" * 78)
    print("TemplateMatcher 算法实测")
    print("=" * 78)
    print(f"截图尺寸     : {W}x{H}（{os.path.basename(args.map)}）/ 负例 {ow_all}x{oh_all}")
    print(f"帧(裁剪)     : {fw}x{fh} @ ({fx}, {fy})")
    print(f"模板         : {tw}x{th} @ 帧内({tx}, {ty}) 期望命中 ({expect_x}, {expect_y})")
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
    # 把模板贴到另一处（与自身不重叠），制造"重复美术"。
    # 偏移也必须对齐到块网格（ALIGN 的倍数）：贴在半格处会让副本在降采样下变形，
    # 第二峰掉下来，用例 ④ 就测不到"两处内容完全一致时必须拒绝"这个最坏情况。
    dx = tx + tw + max(ALIGN * 5, round(fw * 0.15 / ALIGN) * ALIGN)
    dy = ty + max(ALIGN * 2, round(fh * 0.08 / ALIGN) * ALIGN)
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

    # ---- 5. 相位错位模板必须靠亚格精修救回来 ----
    # toGray 是从各自 Bitmap 的 (0,0) 起做 4x4 块平均：帧的块网格，与"事先从别处
    # 裁出来的一张模板 PNG"的块网格之间，相位差是 0..3px 的随机值。
    # 真机截图实测：对齐裁 → 1.0000；非对齐裁 → 0.86~0.95（离 0.85 阈值只剩一点余量，
    # 再叠一次 JPEG 压缩或美术微调就会随机失效）。所以精修不是"锦上添花"，是必需的。
    ph_x, ph_y = tx + 1, ty + 2
    if ph_x + tw < fw and ph_y + th < fh:
        tpl_ph = frame_crop.crop((ph_x, ph_y, ph_x + tw, ph_y + th))
        r5 = search(frame_crop, tpl_ph)
        if r5 is None:
            check("⑤ 非对齐相位模板经精修后应命中", False, "search 返回空")
        elif r5.get("rejected"):
            check("⑤ 非对齐相位模板经精修后应命中", False,
                  f"仍被拒（精修前={r5.get('coarse'):.4f} 精修后={r5['best']:.4f}）")
        else:
            err5 = max(abs(r5["cx"] - (ph_x + tw // 2)), abs(r5["cy"] - (ph_y + th // 2)))
            check(
                "⑤ 非对齐相位模板经精修后应命中",
                err5 <= 4 and r5["best"] >= 0.95,
                f"精修前={r5.get('coarse'):.4f} → 精修后={r5['best']:.4f} "
                f"位置偏差 {err5}px（期望 ≥0.95 且 ≤4px）"
            )
    else:
        check("⑤ 非对齐相位模板", False, "截图过小无法构造非对齐用例")

    # ---- 6/7. 非对齐相位 + 贴着帧边缘 ----
    # ⑤ 的最佳位置在帧中间，精修的邻域四周都取得到。但模板一旦贴在边缘，
    # 邻域就会被 frame 边界**夹掉一侧**，此时：
    #   - 哪些相位是"模板会伸出帧外"的无效相位，必须被跳过而不是算出垃圾；
    #   - 而真实最佳相位（在夹边那一侧）必须**仍然落在可试范围内**。
    # Kotlin 的 refinePhase 用的是同一套"截邻域 + 相对坐标判界"的写法，
    # 这两个用例就是它的边界分支在真机上的覆盖。
    #
    # 这两条**强制走精修**（refine_below=1.0）：默认的 0.95 门控会让"边缘上恰好
    # 分数已经够高"的那张图直接跳过精修，用例看着过了、边缘分支其实一次都没执行。
    # ⑤ 保持默认门控，它测的是生产路径的真实行为。
    edge_cases = (
        ("⑥ 相位错位且贴左上边缘", 1, 2),
        ("⑦ 相位错位且贴右下边缘", fw - tw - 1, fh - th - 2),
    )
    for label, ex, ey in edge_cases:
        if ex < 0 or ey < 0 or ex + tw >= fw or ey + th >= fh:
            check(label, False, "截图过小，边缘相位用例无法构造")
            continue
        tpl_e = frame_crop.crop((ex, ey, ex + tw, ey + th))
        r6 = search(frame_crop, tpl_e, refine_below=1.0)
        want = (ex + tw // 2, ey + th // 2)
        if r6 is None or r6.get("rejected"):
            check(label, False, f"同一张图上内容完全一致却被拒（精修在边缘失效）：结果={r6}")
        else:
            err6 = max(abs(r6["cx"] - want[0]), abs(r6["cy"] - want[1]))
            check(
                label,
                err6 <= 4 and r6["best"] >= 0.95 and r6["refined"],
                f"精修前={r6.get('coarse'):.4f} → 精修后={r6['best']:.4f} "
                f"实际精修={r6['refined']} 命中 ({r6['cx']}, {r6['cy']}) 期望 {want} 偏差 {err6}px"
            )

    print("-" * 78)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— 算法在真机截图上未通过验证。")
        return 1
    print(f"{total} 项全部符合预期 —— 算法在真机截图上通过了"
          f"命中/拒绝/抗亮度/抗重复/抗相位 五类检验。")
    print("说明：这是算法层的离线验证，不代表在游戏按键上的实际命中率（那需要真机标定模板）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
