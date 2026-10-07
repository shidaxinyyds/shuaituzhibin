#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按键模板**种子**生成器 + 三重验收
=================================

## 它解决什么
默认构建里 OCR 是 native 空桩，按键定位实际只剩"模板匹配"这一条腿；而全新用户
装上时模板库是空的（随用随学要 OCR 先成功一次）。本脚本把真机截图里**屏幕固定的
按钮**裁成 `assets/templates/<gameId>/seeds/<ButtonType>.png` 形态的种子，
让"库里不是空的"这件事在用户动手之前就成立。

## 三条腿的验收（任何一条不过就不产出）
1. **唯一性自检**：拿裁出来的图回搜它来源的那一帧，必须命中回原位（±8px）且
   分数 ≥ 0.95。与端侧自愈刷新用的是同一道闸 —— 通不过说明这块图在画面里不唯一
   或不够清楚，当种子会让匹配器乱指。
2. **跨帧可迁移**：在"同一个界面的另一次抓屏"上必须仍认得这个按钮。
   这条才是"换一次会话还认得"的证据；只凭一张图裁出来的东西不算种子。
   判据按 `anchor` 分两种：`fixed`（屏幕固定的面板，如底栏、出征确认面板）要求
   落点仍在 ±12px 内；`floating`（地块动作轮盘，跟着被点的格子走）只要求
   分数 ≥ 0.90，落点偏移照实打印但不作判据。没有配对帧时这一条**不会被检验**，
   末尾会单独报出"跨帧证据 N/M"，不把没跑过的算成跑过的。
3. **对别的界面不误命中**：在"完全不同的界面"上必须**不**命中。
   少了这条，前面两条都可能只是"到处都能匹配上一块"的假象。

## 单一权威
- 匹配数学直接 import `validate_template_matcher.py` 的 `search()`（它是
  `TemplateMatcher.kt` 的等价实现），本脚本不重写第二份 ZNCC；
- 对比度下限、运行阈值一律**从 Kotlin 源码解析**，解析不到就退出 2。

## 关于"随包入库"
默认输出到暂存目录（`tools/p3/seed_staging/`），**不**直接写进 `assets/`。
种子是别人手机上的像素，是否随 APK 发布是产品/法务决定，需要明确授权；
授权后的动作就是把验收通过的文件放进 `client/app/src/main/assets/templates/<gameId>/seeds/`。

用法：
    python tools/gen_button_seeds.py                       # 按清单生成并验收
    python tools/gen_button_seeds.py --preview-dir DIR     # 只裁图到 DIR 供人眼核对
    python tools/gen_button_seeds.py --manifest M.json --out DIR --install-assets
"""

import argparse
import importlib.util
import io
import json
import os
import re
import sys

import numpy as np
from PIL import Image

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
KT_STORE = os.path.join(REPO, "client", "app", "src", "main", "java", "com",
                        "stzb", "assistant", "service", "ButtonTemplateStore.kt")
KT_MATCHER = os.path.join(REPO, "client", "app", "src", "main", "java", "com",
                          "stzb", "assistant", "service", "TemplateMatcher.kt")
DEFAULT_MANIFEST = os.path.join(REPO, "tools", "button_seeds.json")
ASSET_SEED_DIR = os.path.join(REPO, "client", "app", "src", "main", "assets", "templates")

# 跨帧允许的落点偏差（画布像素）。**仅对 anchor=fixed 的屏幕固定面板生效**：
# 那种面板换一次抓屏还在原地，容差给大了就等于"在别的位置也算命中"，验收失去意义。
TRANSFER_POS_TOLERANCE_PX = 12
SELF_CHECK_POS_TOLERANCE_PX = 8
SELF_CHECK_MIN_SCORE = 0.95
# 浮动面板（地块动作轮盘跟着被点的地块走，落点本就该变）只能改用"分数够高才算
# 认得"来验收：位置不作要求，但 0.90 以上的 ZNCC 必须成立，否则就是随便匹配到一块。
FLOATING_MIN_SCORE = 0.90


def _strip_kt(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def parse_kotlin_constants():
    """从端侧源码取回运行阈值与对比度下限（本脚本不许抄第二份）。"""
    with io.open(KT_STORE, encoding="utf-8") as fh:
        store = _strip_kt(fh.read())
    with io.open(KT_MATCHER, encoding="utf-8") as fh:
        matcher = _strip_kt(fh.read())

    m = re.search(r"const val MIN_TEMPLATE_CONTRAST\s*=\s*([\d.]+)", store)
    if not m:
        raise RuntimeError("ButtonTemplateStore.kt 里解析不到 MIN_TEMPLATE_CONTRAST")
    contrast = float(m.group(1))

    hw = re.search(r"const val CROP_HALF_WIDTH\s*=\s*(\d+)", store)
    hh = re.search(r"const val CROP_HALF_HEIGHT\s*=\s*(\d+)", store)
    if not (hw and hh):
        raise RuntimeError("ButtonTemplateStore.kt 里解析不到 CROP_HALF_*")

    th = re.search(r"threshold:\s*Double\s*=\s*([\d.]+)", matcher)
    if not th:
        raise RuntimeError("TemplateMatcher.kt 里解析不到 search 的默认 threshold")
    return {
        "contrast": contrast,
        "crop_half_w": int(hw.group(1)),
        "crop_half_h": int(hh.group(1)),
        "run_threshold": float(th.group(1)),
    }


def load_matcher_module():
    """把 validate_template_matcher.py 当模块引进来，复用它的 ZNCC 实现。"""
    path = os.path.join(REPO, "tools", "validate_template_matcher.py")
    spec = importlib.util.spec_from_file_location("_vtm", path)
    mod = importlib.util.module_from_spec(spec)
    saved = sys.argv
    sys.argv = ["_vtm", "--map", "nul", "--other", "nul"]  # 防 argparse 在导入期抢参数
    try:
        spec.loader.exec_module(mod)
    finally:
        sys.argv = saved
    return mod


def to_canvas(im, height=720):
    w, h = im.size
    return im.resize((int(round(height * w / float(h))), height), Image.BILINEAR)


def gray_std_dev(crop):
    """与 ButtonTemplateStore.grayscaleStdDev 同口径（步长 4 抽样）。"""
    a = np.asarray(crop.convert("RGB"), dtype=np.float64)[::4, ::4, :]
    lum = 0.299 * a[:, :, 0] + 0.587 * a[:, :, 1] + 0.114 * a[:, :, 2]
    return float(lum.std())


def same_pixels(a, b):
    """两张图逐像素是否一致（比文件字节可靠：PNG 重新压缩一次字节就变了，内容却没变）。"""
    if a.size != b.size:
        return False
    pa = np.asarray(a.convert("RGB"), dtype=np.int16)
    pb = np.asarray(b.convert("RGB"), dtype=np.int16)
    return bool((np.abs(pa - pb) <= 1).all())  # 容 1 位：解码/再压缩可能带来的末位抖动


def find_shot(shots_dirs, prefix):
    for d in shots_dirs:
        for name in sorted(os.listdir(d)):
            if name.startswith(prefix) and name.lower().endswith((".jpg", ".jpeg", ".png")):
                return os.path.join(d, name)
    raise SystemExit("清单里引用的截图找不到: %s (%s)" % (prefix, ", ".join(shots_dirs)))


def negatives_for(man, seed):
    """这个种子的"必须不命中"帧集合。

    清单里没写 other_ui 时，从 `all_shots` 自动取全集，再扣掉来源帧、同界面配对帧、
    以及 `contains`（**确实也画着这个按钮**的帧——拿它当负例等于自己考自己）。
    这样做是因为负例池应当随截图增多而自动变大，而不是每加一张图就手改 8 个列表。
    """
    if "other_ui" in seed:
        return seed["other_ui"]
    pool = man.get("all_shots")
    if not pool:
        return []
    skip = {seed["src"]} | set(seed.get("same_ui", [])) | set(seed.get("contains", []))
    return [p for p in pool if p not in skip]


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default=DEFAULT_MANIFEST)
    ap.add_argument("--out", default=os.path.join(REPO, "tools", "p3", "seed_staging"),
                    help="种子 PNG 的暂存目录（默认不写进 assets）")
    ap.add_argument("--preview-dir", default=None,
                    help="只把裁出来的小块写到该目录供人眼核对，不做验收、不落种子")
    ap.add_argument("--check", action="store_true",
                    help="闸门模式：只验收、不写任何文件；并核对已入包的种子是否与验收结果一致")
    ap.add_argument("--install-assets", action="store_true",
                    help="把验收通过的种子直接写入 assets/templates/<gameId>/seeds/（需要明确授权）")
    args = ap.parse_args()

    K = parse_kotlin_constants()
    vtm = load_matcher_module()
    with io.open(args.manifest, encoding="utf-8-sig") as fh:
        man = json.load(fh)

    raw_dirs = man["shots_dir"]
    if isinstance(raw_dirs, str):
        raw_dirs = [raw_dirs]
    shots_dirs = [os.path.join(REPO, d) for d in raw_dirs]
    for d in shots_dirs:
        if not os.path.isdir(d):
            print("截图目录不存在: %s" % d, file=sys.stderr)
            return 2
    canvas_h = int(man.get("canvas_height", 720))
    out_dir = args.preview_dir or args.out
    if not args.check and not os.path.isdir(out_dir):
        os.makedirs(out_dir)

    print("端侧口径: 对比度 ≥ %g，运行阈值 %g，登记裁剪 %dx%d" % (
        K["contrast"], K["run_threshold"], 2 * K["crop_half_w"], 2 * K["crop_half_h"]))

    frames = {}

    def canvas_of(prefix):
        if prefix not in frames:
            frames[prefix] = to_canvas(Image.open(find_shot(shots_dirs, prefix)), canvas_h)
        return frames[prefix]

    failures = []
    produced = []
    stats = {"verified": [], "untested": []}
    if not man["seeds"]:
        # 明说这是空跑：闸门"通过"却什么都没判，是最容易骗人的一种绿。
        print("[INFO] 清单里一个种子都没有 —— 本闸门当前是空跑。")
    for seed in man["seeds"]:
        t = seed["type"]
        print("-" * 72)
        print("种子 %s（来自 %s，rect=%s）" % (t, seed["src"], seed["rect"]))
        im = canvas_of(seed["src"])
        x0, y0, x1, y1 = [int(v) for v in seed["rect"]]
        if (x0, y0, x1, y1) != (max(0, x0), max(0, y0), min(im.width, x1), min(im.height, y1)):
            print("   裁剪框 %s 在画布 %s 内不合法（越界即拒绝，不 clamp）" % (seed["rect"], im.size))
            failures.append("%s: 裁剪框越界" % t)
            continue
        crop = im.crop((x0, y0, x1, y1))
        cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

        if args.preview_dir:
            p = os.path.join(out_dir, "%s.png" % t)
            crop.save(p)
            print("   [预览] %s" % p)
            continue

        # --- 1) 对比度闸门（与端侧登记时同一条） ---
        contrast = gray_std_dev(crop)
        ok_contrast = contrast >= K["contrast"]
        print("   [%s] 对比度 %.1f（要求 ≥ %g）" %
              ("PASS" if ok_contrast else "FAIL", contrast, K["contrast"]))
        if not ok_contrast:
            failures.append("%s: 对比度不足" % t)
            continue

        # --- 2) 唯一性自检 ---
        self_hit = vtm.search(im, crop, threshold=SELF_CHECK_MIN_SCORE)
        ok_self = bool(self_hit) and not self_hit.get("rejected") and \
            abs(self_hit["cx"] - cx) <= SELF_CHECK_POS_TOLERANCE_PX and \
            abs(self_hit["cy"] - cy) <= SELF_CHECK_POS_TOLERANCE_PX
        print("   [%s] 自检回搜：期望 (%d,%d)，实得 %s" %
              ("PASS" if ok_self else "FAIL", cx, cy,
               "无命中/被区分度拒" if not self_hit or self_hit.get("rejected")
               else "({cx},{cy}) 分数 {best:.3f}".format(**self_hit)))
        if not ok_self:
            failures.append("%s: 自检未通过" % t)
            continue

        # --- 3) 跨帧可迁移 ---
        # fixed：换一帧必须还在原地（±12px）。floating：地块轮盘跟着被点的格子走，
        # 落点本就该变，于是改判"另一帧里能以 ≥0.90 认出同一个按钮"，偏移照实打印。
        anchor = seed.get("anchor", "fixed")
        same_list = seed.get("same_ui", [])
        ok_transfer = True
        for prefix in same_list:
            other = canvas_of(prefix)
            hit = vtm.search(other, crop, threshold=K["run_threshold"])
            if not hit or hit.get("rejected"):
                good, detail = False, "无命中/被区分度拒"
            else:
                dx, dy = abs(hit["cx"] - cx), abs(hit["cy"] - cy)
                if anchor == "floating":
                    good = hit["best"] >= FLOATING_MIN_SCORE
                    detail = "落点 (%d,%d) 分数 %.3f 偏移 %dpx（浮动面板：位置不作判据）" % (
                        hit["cx"], hit["cy"], hit["best"], dx + dy)
                else:
                    good = dx <= TRANSFER_POS_TOLERANCE_PX and dy <= TRANSFER_POS_TOLERANCE_PX
                    detail = "落点 (%d,%d) 分数 %.3f 偏移 %dpx（要求 ≤ %dpx）" % (
                        hit["cx"], hit["cy"], hit["best"], dx + dy, TRANSFER_POS_TOLERANCE_PX)
            print("   [%s] 迁移到 %s：%s" % ("PASS" if good else "FAIL", prefix, detail))
            ok_transfer &= good
        if same_list and ok_transfer:
            stats["verified"].append(t)
        elif not same_list:
            stats["untested"].append(t)
            print("   [NOTE] 没有\"同一界面的另一帧\"——跨帧可迁移这一条**未被检验**。")
        if not ok_transfer:
            failures.append("%s: 跨帧迁移失败" % t)
            continue

        # --- 4) 对别的界面不误命中 ---
        ok_specific = True
        negatives = negatives_for(man, seed)
        print("   负例池 %d 帧（all_shots 自动推导）" % len(negatives))
        for prefix in negatives:
            other = canvas_of(prefix)
            hit = vtm.search(other, crop, threshold=K["run_threshold"])
            good = not hit or hit.get("rejected")
            print("   [%s] 不误命中 %s：%s" % ("PASS" if good else "FAIL", prefix,
                                              "未命中" if good else
                                              "命中在 ({cx},{cy}) 分数 {best:.3f}".format(**hit)))
            ok_specific &= good
        if not ok_specific:
            failures.append("%s: 在别的界面上误命中" % t)
            continue

        # --- 5) 与"已经入包的那一份"对账 ---
        # 闸门必须能抓到"清单改了、包里的种子还是旧图"这类脱节：那才是真正发给用户的像素。
        installed = os.path.join(ASSET_SEED_DIR, man["game_id"], "seeds", "%s.png" % t)
        if os.path.isfile(installed):
            same = same_pixels(Image.open(installed), crop)
            print("   [%s] 与已入包种子对账：%s" % ("PASS" if same else "FAIL", installed))
            if not same:
                failures.append("%s: assets 里的种子与本清单的验收结果不是同一张图" % t)
                continue
        else:
            print("   [INFO] assets 里尚无该种子（未随包发布，需明确授权）")

        if args.check:
            print("   ✅ 验收通过（--check 模式不写文件）")
            produced.append((t, installed if os.path.isfile(installed) else "(未入库)", crop.size))
            continue
        dst = os.path.join(out_dir, "%s.png" % t)
        crop.save(dst)
        produced.append((t, dst, crop.size))
        print("   ✅ 验收通过 → %s" % dst)

    print("=" * 72)
    if args.preview_dir:
        print("预览已写入 %s（未做验收）" % out_dir)
        return 0
    if failures:
        print("未通过 %d 项：%s" % (len(failures), "; ".join(failures)))
        print("→ 一个种子都不产出。种子会随 APK 发给所有用户，宁缺毋滥。")
        return 1
    for t, path, size in produced:
        print("产出 %s：%s %dx%d" % (t, path, size[0], size[1]))
    # 把"证据完整度"单独报出来：闸门是绿的，不等于每条验收都真的跑过。
    print("跨帧证据：%d/%d 个种子配了同界面另一帧并迁移成功；%d 个没有配对帧，该条未检验%s"
          % (len(stats["verified"]), len(man["seeds"]), len(stats["untested"]),
             ("（%s）" % "、".join(stats["untested"])) if stats["untested"] else ""))
    if args.check:
        print("闸门模式：全部种子验收通过（未写文件）。")
        return 0
    if args.install_assets:
        game = man["game_id"]
        dst_dir = os.path.join(ASSET_SEED_DIR, game, "seeds")
        if not os.path.isdir(dst_dir):
            os.makedirs(dst_dir)
        for t, path, _ in produced:
            Image.open(path).save(os.path.join(dst_dir, "%s.png" % t))
        print("已写入 assets/templates/%s/seeds/（这一步等于授权随包发布）" % game)
    else:
        print("暂存目录：%s —— 未写入 assets（随包发布需要明确授权）" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
