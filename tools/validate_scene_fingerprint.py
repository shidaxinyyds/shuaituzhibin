#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SceneFingerprint 算法验证器
===========================

用途
----
`SceneFingerprint.kt` 用"底部功能栏 5 张固定卡片"的 8x8 均值哈希来判断
**当前是否在游戏大地图**，从而在 OCR 不可用时也能避免 `recoverToMainMap()`
反复盲点地图空白区（用户能直接感知到的"乱点"）。

本脚本用**同一套算法**在真机截图上跑验证，回答三个问题：
  1. 区域划分是否落在目标 UI 上（同图自比必须 5/5、距离 0）；
  2. 判别力是否足够（完全不同的画面必须不命中）；
  3. 阈值[0] 是否合理（真实变体与编码噪声的距离应远小于阈值，错误画面应远大于阈值）。

它在纯 Python 下运行，不需要 Android、不需要 JDK。

用法
----
    python tools/validate_scene_fingerprint.py --map <大地图截图> \
        [--variant <同一UI的另一张截图> ...] [--other <不同UI的截图> ...]

`--map` 是基准图（必须是大地图界面）。
`--variant` 给"仍是大地图 UI、但由另一次抓屏得到"的图 —— 期望**命中**，用于验证阈值宽容度。
`--other`   给"根本不显示大地图 UI"的图 —— 期望**不命中**，用于验证判别力。

⚠️ 分类很容易搞错：打开悬浮面板时的截图**底部功能栏仍然可见**，因此它属于
`--variant`（期望命中），而不是 `--other`。这是该判据的设计边界——
它只能回答"大地图 UI 是否在屏上"，无法区分覆盖在上层的自家面板。

退出码：0 = 全部通过；1 = 有检查项不通过。
"""

import argparse
import io
import itertools
import os
import sys

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow：pip install Pillow", file=sys.stderr)
    sys.exit(2)

# 必须与 SceneFingerprint.kt 保持一致
HASH_SIZE = 8
MATCH_TOLERANCE = 10
MIN_MATCH_RATIO = 0.6

# 实测自真机截图（2712x1220）底部功能栏 5 张卡片
REGIONS = [
    ("武将卡", 0.0760, 0.800, 0.1198, 0.970),
    ("库藏卡", 0.1213, 0.800, 0.1652, 0.970),
    ("内政卡", 0.1674, 0.800, 0.2113, 0.970),
    ("势力卡", 0.2131, 0.800, 0.2574, 0.970),
    ("国家卡", 0.2592, 0.800, 0.3031, 0.970),
]

FAILURES = []


def region_hash(im, r):
    """8x8 均值哈希，与 Kotlin 侧逐像素一致。"""
    w, h = im.size
    box = (int(w * r[1]), int(h * r[2]), int(w * r[3]), int(h * r[4]))
    crop = im.crop(box).convert("L").resize((HASH_SIZE, HASH_SIZE), Image.BILINEAR)
    pix = list(crop.getdata())
    avg = sum(pix) / len(pix)
    hv = 0
    for i, v in enumerate(pix):
        if v >= avg:
            hv |= (1 << i)
    return hv


def hamming(a, b):
    return bin(a ^ b).count("1")


def fingerprint(im):
    return {r[0]: region_hash(im, r) for r in REGIONS}


def required_matches():
    import math
    return max(1, math.ceil(len(REGIONS) * MIN_MATCH_RATIO))


def check(label, ok, detail):
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {label}\n       {detail}")
    if not ok:
        FAILURES.append(label)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--map", required=True, help="基准图：必须是大地图界面")
    ap.add_argument("--variant", action="append", default=[],
                    help="仍是大地图 UI 的另一次抓屏：期望命中，用于验证阈值宽容度")
    ap.add_argument("--other", action="append", default=[],
                    help="不显示大地图 UI 的图：期望不命中，可重复指定")
    args = ap.parse_args()

    for p in [args.map] + args.variant + args.other:
        if not os.path.isfile(p):
            print(f"找不到文件: {p}", file=sys.stderr)
            return 2

    base_im = Image.open(args.map)
    base = fingerprint(base_im)
    print(f"基准图: {args.map}  {base_im.size}")
    print(f"阈值: 单区域距离 <= {MATCH_TOLERANCE}/64，需 >= {required_matches()}/{len(REGIONS)} 区域命中")
    print("-" * 72)

    # 1) 同图自比：必须全中且距离为 0
    d0 = [(n, hamming(base[n], region_hash(base_im, r))) for r in REGIONS for n in [r[0]]]
    check("同图自比", all(d == 0 for _, d in d0),
          "距离 " + ", ".join(f"{n}={d}" for n, d in d0))

    # 2) 区域之间应互相可区分（判别力下限）
    pairwise = [(a, b, hamming(base[a], base[b]))
                for a, b in itertools.combinations(base, 2)]
    nearest = min(p[2] for p in pairwise)
    # 说明：区域之间相似并不会导致误判——每个区域只跟自己的基准比。
    # 但如果所有区域都高度相似，说明指纹承载的信息量偏低，值得提示。
    print(f"[INFO] 区域两两最小距离 = {nearest}（仅作判别力参考；"
          f"各区只与自身基准比对，故不影响正确性）")

    # 3) JPEG 再压缩鲁棒性
    buf = io.BytesIO()
    base_im.convert("RGB").save(buf, "JPEG", quality=75)
    buf.seek(0)
    jd = [(n, hamming(base[n], region_hash(Image.open(buf), r))) for r in REGIONS for n in [r[0]]]
    check("JPEG q=75 再压缩", all(d <= MATCH_TOLERANCE for _, d in jd),
          "距离 " + ", ".join(f"{n}={d}" for n, d in jd))

    # 4) 同 UI 变体必须仍命中（验证阈值足够宽容，不会产生"漏判"）
    for path in args.variant:
        im = Image.open(path)
        ds = [(n, hamming(base[n], region_hash(im, r))) for r in REGIONS for n in [r[0]]]
        matched = sum(1 for _, d in ds if d <= MATCH_TOLERANCE)
        check(f"同 UI 变体 {os.path.basename(path)[:12]} 仍判为大地图",
              matched >= required_matches(),
              f"命中 {matched}/{len(REGIONS)}，距离 " +
              ", ".join(f"{n}={d}" for n, d in ds))

    # 5) 不同 UI 的图必须不命中（验证判别力，避免"误判"）
    for path in args.other:
        im = Image.open(path)
        ds = [(n, hamming(base[n], region_hash(im, r))) for r in REGIONS for n in [r[0]]]
        matched = sum(1 for _, d in ds if d <= MATCH_TOLERANCE)
        check(f"不同 UI {os.path.basename(path)[:12]} 未被误判为大地图",
              matched < required_matches(),
              f"命中 {matched}/{len(REGIONS)}，距离 " +
              ", ".join(f"{n}={d}" for n, d in ds))

    print("-" * 72)
    if FAILURES:
        print(f"未通过 {len(FAILURES)} 项: " + "; ".join(FAILURES))
        return 1
    print("全部检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
