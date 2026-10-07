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

本脚本用**同一套算法**在真机截图上跑验证，回答四个问题：
  1. 区域划分是否落在目标 UI 上（同图自比必须 5/5、距离 0）；
  2. 判别力是否足够（完全不同的画面必须不命中）；
  3. 阈值是否合理（真实变体与编码噪声的距离应远小于阈值，错误画面应远大于阈值）；
  4. **换长宽比是否还成立**：把画布裁窄模拟别的机型，现行口径（x 按高折算）必须
     取到同一批像素，而旧口径（x 按宽折算）必须明显更差 —— 这条就是
     "阈值只在校过的那台机器上成立"这个问题的答案，不需要第二台手机。

阈值与区域矩形**一律从 SceneFingerprint.kt 解析回来**，本脚本不抄第二份常量；
解析不到就直接退出 2（拿抄来的区域去判真图，报出来的绿与设备无关）。

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
import re
import sys

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow：pip install Pillow", file=sys.stderr)
    sys.exit(2)

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
KT_PATH = os.path.join(REPO, "client", "app", "src", "main", "java", "com",
                       "stzb", "assistant", "service", "SceneFingerprint.kt")

# 端侧画布高（CoordinateTransformer.BASE_HEIGHT）。验证一律先把截图折算到这张画布上，
# 否则量的是"手机原生像素"，而设备上看的是另一套像素 —— 阈值就不是同一回事。
BASE_HEIGHT = 720


def _strip_kt(text):
    """去掉 Kotlin 的行注释与块注释（避免注释里出现过的旧数字被当成现行口径解析）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def parse_kotlin_geometry(path=KT_PATH):
    """把端侧的阈值与区域矩形**从源码里读回来**，本脚本不抄第二份。

    为什么必须解析而不是抄一份常量：抄来的那份一旦和端侧脱节，本脚本就会拿
    "理想中的区域"去跑真图，报出来的绿与设备上真正跑的那套几何无关 —— 这类事故
    在本仓库已经踩过两次（RAG 脱敏正则、语料 category 对照表）。
    解析不到就当场抛错退出，绝不退回"脚本里写死的默认值"。
    """
    with io.open(path, encoding="utf-8") as fh:
        plain = _strip_kt(fh.read())

    def const_int(name):
        m = re.search(r"const val %s\s*=\s*(\d+)" % name, plain)
        if not m:
            raise RuntimeError("SceneFingerprint.kt 里解析不到 %s" % name)
        return int(m.group(1))

    mt = re.search(r"const val MATCH_TOLERANCE\s*=\s*(\d+)", plain)
    mr = re.search(r"const val MIN_MATCH_RATIO\s*=\s*([\d.]+)f?", plain)
    rw = re.search(r"const val REF_CANVAS_WIDTH\s*=\s*([\d.]+)f", plain)
    gv = re.search(r"const val GEOM_VERSION\s*=\s*(\d+)", plain)
    if not (mt and mr and rw and gv):
        raise RuntimeError(
            "SceneFingerprint.kt 里解析不到现行口径所需的常量（MATCH_TOLERANCE / "
            "MIN_MATCH_RATIO / REF_CANVAS_WIDTH / GEOM_VERSION 至少缺一个）")

    block = re.search(r"private val REGIONS\s*=\s*listOf\((.*?)\n    \)", plain, re.S)
    if not block:
        raise RuntimeError("SceneFingerprint.kt 里解析不到 REGIONS 的定义块")
    regions = re.findall(
        r'Region\("([^"]+)",\s*([\d.]+)f,\s*([\d.]+)f,\s*([\d.]+)f,\s*([\d.]+)f\)',
        block.group(1))
    if len(regions) < 2:
        raise RuntimeError("REGIONS 只解析到 %d 个区域，闸门无法判定" % len(regions))

    return {
        "hash_size": const_int("HASH_SIZE"),
        "tolerance": int(mt.group(1)),
        "min_ratio": float(mr.group(1)),
        "ref_canvas_width": float(rw.group(1)),
        "geom_version": int(gv.group(1)),
        "regions": [(n, float(l), float(t), float(r), float(b))
                    for n, l, t, r, b in regions],
    }


try:
    G = parse_kotlin_geometry()
except Exception as e:
    # 取不回现行口径就不许跑：拿一份"猜出来的区域"去判真图，报什么都是假的。
    print(f"[FAIL] 无法从端侧源码取回现行口径：{e}", file=sys.stderr)
    sys.exit(2)
HASH_SIZE = G["hash_size"]
MATCH_TOLERANCE = G["tolerance"]
MIN_MATCH_RATIO = G["min_ratio"]
REF_CANVAS_WIDTH = G["ref_canvas_width"]
REGIONS = G["regions"]

# 旧口径（V1）：x 按**画布宽**的比例算。留着它只为了一个用途 ——
# 让"改几何"这件事必须拿真图证明确实更好，而不是靠注释里的一句话。
WIDTH_LAW_X = "width"
HEIGHT_LAW_X = "height"

FAILURES = []


def to_canvas(im):
    """折算到端侧真正看到的那张画布：高恒 720，宽按长宽比等比推。"""
    w, h = im.size
    cw = int(round(BASE_HEIGHT * w / float(h)))
    return im.resize((cw, BASE_HEIGHT), Image.BILINEAR)


def region_box(im, r, law=HEIGHT_LAW_X):
    """区域矩形；law 决定 x 按高折算（现行）还是按宽折算（V1 旧口径）。"""
    w, h = im.size
    _, l, t, rr, b = r
    if law == HEIGHT_LAW_X:
        sx = h / float(BASE_HEIGHT)
        x0, x1 = l * REF_CANVAS_WIDTH * sx, rr * REF_CANVAS_WIDTH * sx
    else:
        x0, x1 = w * l, w * rr
    return (int(x0), int(h * t), int(x1), int(h * b))


def region_hash(im, r, law=HEIGHT_LAW_X):
    """8x8 均值哈希，与 Kotlin 侧逐像素一致。"""
    crop = im.crop(region_box(im, r, law)).convert("L").resize(
        (HASH_SIZE, HASH_SIZE), Image.BILINEAR)
    pix = list(crop.getdata())
    avg = sum(pix) / len(pix)
    hv = 0
    for i, v in enumerate(pix):
        if v >= avg:
            hv |= (1 << i)
    return hv


def hamming(a, b):
    return bin(a ^ b).count("1")


def fingerprint(im, law=HEIGHT_LAW_X):
    return {r[0]: region_hash(im, r, law) for r in REGIONS}


def required_matches():
    import math
    return max(1, math.ceil(len(REGIONS) * MIN_MATCH_RATIO))


def check(label, ok, detail):
    mark = "PASS" if ok else "FAIL"
    print(f"[{mark}] {label}\n       {detail}")
    if not ok:
        FAILURES.append(label)


def dists(base, im, law):
    """一帧图相对基准的每个区域汉明距离。"""
    return [(r[0], hamming(base[r[0]], region_hash(im, r, law))) for r in REGIONS]


def crop_width(im, cw):
    """把画布裁窄 = 模拟"同一套 HUD、更窄的视野"的那类机型（长宽比不同）。

    只能裁窄、不能凭空加宽：加宽需要编造屏幕外那块内容，编出来的像素不算证据。
    左侧对齐的底部功能栏在裁窄后位置不变，所以这个模拟对本案是成立的。
    """
    w, h = im.size
    return im if cw >= w else im.crop((0, 0, cw, h))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--map", required=True, help="基准图：必须是大地图界面")
    ap.add_argument("--variant", action="append", default=[],
                    help="仍是大地图 UI 的另一次抓屏：期望命中，用于验证阈值宽容度")
    ap.add_argument("--other", action="append", default=[],
                    help="不显示大地图 UI 的图：期望不命中，可重复指定")
    ap.add_argument("--aspect-sweep", default="1280,1440,1520,1584",
                    help="要模拟的画布宽（逗号分隔，只能小于基准画布宽）；传 off 跳过")
    args = ap.parse_args()

    for p in [args.map] + args.variant + args.other:
        if not os.path.isfile(p):
            print(f"找不到文件: {p}", file=sys.stderr)
            return 2

    # 一律折算到端侧画布（高恒 720）再算：设备上跑的就是这套像素。
    base_im = to_canvas(Image.open(args.map))
    variants = [(p, to_canvas(Image.open(p))) for p in args.variant]
    others = [(p, to_canvas(Image.open(p))) for p in args.other]
    base = fingerprint(base_im)
    print(f"基准图: {args.map}  原生 {Image.open(args.map).size} → 画布 {base_im.size}")
    print(f"现行口径: 区域 x 按画布高折算（GEOM_VERSION={G['geom_version']}，"
          f"参考画布宽 {REF_CANVAS_WIDTH:.0f}）")
    print(f"阈值: 单区域距离 <= {MATCH_TOLERANCE}/64，需 >= {required_matches()}/{len(REGIONS)} 区域命中")
    print("-" * 72)

    # 1) 同图自比：必须全中且距离为 0
    d0 = dists(base, base_im, HEIGHT_LAW_X)
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
    jd = dists(base, Image.open(buf), HEIGHT_LAW_X)
    check("JPEG q=75 再压缩", all(d <= MATCH_TOLERANCE for _, d in jd),
          "距离 " + ", ".join(f"{n}={d}" for n, d in jd))

    # 4) 同 UI 变体必须仍命中（验证阈值足够宽容，不会产生"漏判"）
    for path, im in variants:
        ds = dists(base, im, HEIGHT_LAW_X)
        matched = sum(1 for _, d in ds if d <= MATCH_TOLERANCE)
        check(f"同 UI 变体 {os.path.basename(path)[:12]} 仍判为大地图",
              matched >= required_matches(),
              f"命中 {matched}/{len(REGIONS)}，距离 " +
              ", ".join(f"{n}={d}" for n, d in ds))

    # 5) 不同 UI 的图必须不命中（验证判别力，避免"误判"）
    for path, im in others:
        ds = dists(base, im, HEIGHT_LAW_X)
        matched = sum(1 for _, d in ds if d <= MATCH_TOLERANCE)
        check(f"不同 UI {os.path.basename(path)[:12]} 未被误判为大地图",
              matched < required_matches(),
              f"命中 {matched}/{len(REGIONS)}，距离 " +
              ", ".join(f"{n}={d}" for n, d in ds))

    # 6) 长宽比不变性：区域矩形按高折算，所以换画布宽**必须**取到同一批像素。
    #    同时把旧口径（按宽折算）摆在一起对照 —— 它必须明显更差，
    #    否则说明这个 sweep 什么都没测（例如两法实现成了同一个式子）。
    sweep = [] if args.aspect_sweep.strip().lower() == "off" else \
        [int(x) for x in args.aspect_sweep.split(",") if x.strip()]
    for cw in sweep:
        if cw >= base_im.size[0]:
            print(f"[SKIP] 画布宽 {cw} 不小于基准 {base_im.size[0]}：加宽要凭空造内容，不算证据")
            continue
        sim = crop_width(base_im, cw)
        h_law = [hamming(base[n], region_hash(sim, r, HEIGHT_LAW_X)) for r in REGIONS for n in [r[0]]]
        w_law = [hamming(base[n], region_hash(sim, r, WIDTH_LAW_X)) for r in REGIONS for n in [r[0]]]
        over = sum(1 for d in w_law if d > MATCH_TOLERANCE)
        check(f"长宽比 {sim.size[0]}x{sim.size[1]}：按高折算的区域像素不变",
              all(d == 0 for d in h_law),
              f"按高={h_law}（要求全 0）；按宽(旧口径)={w_law}，其中 {over} 个区域越界。"
              f"旧口径必须与按高**不同**，否则本项等于没测（两种算法被实现成了同一个式子）")
        if w_law == h_law:
            FAILURES.append(f"长宽比 {sim.size[0]} 的两种算法给出同一批像素，sweep 是空测")

    # 7) 换机型后的**余量**：在每台模拟机型上各自重录一次基准（端侧就是自校准的），
    #    再看正例最差距离与负例最近距离之间还剩多少沟。阈值本身没变，变的是几何。
    for cw in sweep:
        sim_w = min(cw, base_im.size[0])
        if sim_w < cw:
            continue
        for law, tag in ((HEIGHT_LAW_X, "按高(现行)"), (WIDTH_LAW_X, "按宽(旧)")):
            b = fingerprint(crop_width(base_im, sim_w), law)
            worst_ok, worst_var = 99, -1
            for _, im in variants:
                ds = [d for _, d in dists(b, crop_width(im, sim_w), law)]
                worst_ok = min(worst_ok, sum(1 for d in ds if d <= MATCH_TOLERANCE))
                worst_var = max(worst_var, max(ds))
            min_other = min(min(d for _, d in dists(b, crop_width(im, sim_w), law))
                            for _, im in others) if others else 64
            line = (f"{tag}：正例最差命中 {worst_ok}/{len(REGIONS)}、最差距离 {worst_var}，"
                    f"负例最近距离 {min_other}")
            if law == HEIGHT_LAW_X:
                check(f"长宽比 {sim_w} 上重录基准后仍可分", 
                      worst_ok >= required_matches() and
                      (not others or min_other > worst_var),
                      line)
            else:
                print(f"[INFO] 长宽比 {sim_w} 旧口径对照 —— {line}")

    print("-" * 72)
    if FAILURES:
        print(f"未通过 {len(FAILURES)} 项: " + "; ".join(FAILURES))
        return 1
    print("全部检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
