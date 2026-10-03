#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""守军头像图库构建器（确定性模板库）

从「守军面板全屏截图」按固定归一化槽位裁出每个武将的立绘头像，
输出到 client/app/src/main/assets/defender_refs/<武将名>.png（一人一张）。

设计要点（避免踩坑）：
- 截图是设备原生分辨率(如 2712x1220)，运行时抓帧是 720 高等比设计画布。
  两者是**等比缩放**关系，故槽位一律用**归一化比例**表达，跨尺度天然对齐。
- 所有模板统一 resize 到 CANON_W x CANON_H 的规范尺寸后再入库；运行时同样
  按归一化槽位裁剪并 resize 到同一规范尺寸，模板匹配即为同尺寸比较，尺度无关。
- 立绘底部有 Lv/兵力等**会变**的数字带，槽位窗口刻意只取上部不含数字带。

用法:
  python build_gallery.py --preview <某张截图>       # 把 3 个槽裁到 work/preview_*.png 供肉眼校验
  python build_gallery.py --build manifest.json      # 按清单批量裁图入库
  python build_gallery.py --emit-slots               # 打印当前归一化槽位(给 Kotlin 侧对齐用)
"""
import argparse
import hashlib
import json
import os
import sys

import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
WORK = os.path.join(HERE, "work")
# 产物：随 APK 打包的确定性头像模板库
OUT = os.path.normpath(os.path.join(
    HERE, "..", "..", "client", "app", "src", "main", "assets", "defender_refs"))

# 规范尺寸：模板与运行时裁剪都 resize 到这里（宽 x 高）。
CANON_W, CANON_H = 260, 150

# 三行头像立绘槽位（归一化 l,t,r,b），顺序 = 大营 / 中军 / 前锋。
# 依据 2712x1220 截图 + 像素网格标定；比例与运行时 720 高画布一致。
SLOT_RECTS = [
    (0.2304, 0.2459, 0.4222, 0.3549),  # 大营
    (0.2304, 0.4140, 0.4222, 0.5229),  # 中军
    (0.2304, 0.5779, 0.4222, 0.6868),  # 前锋
]
SLOT_NAMES = ["daying", "zhongjun", "qianfeng"]  # 大营/中军/前锋


def crop_slots(img):
    """按归一化槽位裁出 3 个规范尺寸头像（列表顺序同 SLOT_RECTS）。"""
    h, w = img.shape[:2]
    crops = []
    for (l, t, r, b) in SLOT_RECTS:
        x0, y0, x1, y1 = int(l * w), int(t * h), int(r * w), int(b * h)
        x0 = max(0, min(x0, w - 1)); x1 = max(x0 + 1, min(x1, w))
        y0 = max(0, min(y0, h - 1)); y1 = max(y0 + 1, min(y1, h))
        piece = img[y0:y1, x0:x1]
        piece = cv2.resize(piece, (CANON_W, CANON_H), interpolation=cv2.INTER_AREA)
        crops.append(piece)
    return crops


def do_preview(path):
    img = cv2.imread(path)
    if img is None:
        print("READ_FAIL", path); sys.exit(1)
    os.makedirs(WORK, exist_ok=True)
    for piece, name in zip(crop_slots(img), SLOT_NAMES):
        dst = os.path.join(WORK, "preview_%s.png" % name)
        cv2.imwrite(dst, piece)
        print("PREVIEW", dst)


def _hero_id(name):
    """武将名 -> 稳定 ASCII 文件名（避免 Windows/OpenCV/git 的中文路径坑）。"""
    return hashlib.md5(name.encode("utf-8")).hexdigest()[:10]


def do_build(manifest_path):
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest = json.load(f)
    os.makedirs(OUT, exist_ok=True)
    # 幂等：先清空本工具自管的产物（ASCII .png 与 index.json），再重建。
    for f0 in os.listdir(OUT):
        if f0.endswith(".png") or f0 == "index.json":
            os.remove(os.path.join(OUT, f0))
    written, skipped, missing, failed = 0, 0, 0, 0
    seen = {}
    for entry in manifest:
        src = entry["file"]
        if not os.path.isabs(src):
            src = os.path.join(RAW, src)
        heroes = entry.get("heroes", [])
        if not os.path.exists(src):
            print("MISSING", src); missing += 1; continue
        img = cv2.imread(src)
        if img is None:
            print("READ_FAIL", src); missing += 1; continue
        crops = crop_slots(img)
        for name, piece in zip(heroes, crops):
            name = (name or "").strip()
            if not name:
                continue
            if name in seen:
                skipped += 1; continue
            hid = _hero_id(name)
            dst = os.path.join(OUT, "%s.png" % hid)
            if not cv2.imwrite(dst, piece):   # ASCII 文件名，imwrite 可靠
                print("WRITE_FAIL", name, dst); failed += 1; continue
            seen[name] = hid
            written += 1
    # 真实武将名存进 index.json（UTF-8 内容，Android 读 assets 无碍）
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump({"canon": [CANON_W, CANON_H],
                   "slots": [list(s) for s in SLOT_RECTS],
                   "heroes": seen}, f, ensure_ascii=False, indent=2)
    print("GALLERY ->", OUT)
    print("written=%d skipped_dup=%d missing_img=%d write_fail=%d" % (written, skipped, missing, failed))


def do_emit_slots():
    print(json.dumps({
        "canon": [CANON_W, CANON_H],
        "slots": [list(s) for s in SLOT_RECTS],
        "row_order": SLOT_NAMES,
    }, ensure_ascii=False, indent=2))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", metavar="IMG")
    ap.add_argument("--build", metavar="MANIFEST_JSON")
    ap.add_argument("--emit-slots", action="store_true")
    a = ap.parse_args()
    if a.emit_slots:
        do_emit_slots()
    elif a.preview:
        do_preview(a.preview)
    elif a.build:
        do_build(a.build)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
