#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""名字联络表生成器：把每张截图三行的「竖排武将名条」裁出并拼接，
便于一次性批量读名（替代逐张看全屏）。输出 work/namesheet_XX.png 并打印 index->file。"""
import os
import cv2

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
WORK = os.path.join(HERE, "work")

# 名字条区域（归一化）：竖排武将名在立绘左缘（位置标签大营/中军/前锋在其左侧，需避开）
NX0, NX1 = 0.230, 0.250
# 三行的 y 带（归一化 top,bottom），与 build_gallery 槽位对齐并略放宽
ROW_Y = [(0.235, 0.360), (0.402, 0.528), (0.566, 0.692)]
PER_SHEET = 7  # 每屏几张图


def main():
    os.makedirs(WORK, exist_ok=True)
    files = sorted(f for f in os.listdir(RAW) if f.lower().endswith((".jpg", ".png")))
    strips = []
    mapping = []
    for idx, fn in enumerate(files):
        img = cv2.imread(os.path.join(RAW, fn))
        if img is None:
            continue
        h, w = img.shape[:2]
        x0, x1 = int(NX0 * w), int(NX1 * w)
        parts = []
        for (t, b) in ROW_Y:
            y0, y1 = int(t * h), int(b * h)
            parts.append(img[y0:y1, x0:x1])
        sep = [np_full(parts[0].shape[1], 4)]
        col = parts[0]
        for p in parts[1:]:
            col = np_vstack([col, np_full(col.shape[1], 4), p])
        # 顶部标 index
        head = np_full(col.shape[1], 26)
        cv2.putText(head, "#%02d" % idx, (3, 19), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 2)
        col = np_vstack([head, col])
        strips.append(col)
        mapping.append((idx, fn))

    # 各条等高拼接（用最大高度 pad）
    maxh = max(s.shape[0] for s in strips)
    pad = []
    for s in strips:
        if s.shape[0] < maxh:
            s = np_vpad(s, maxh)
        pad.append(s)

    sheet, cnt, si = [], 0, 0
    out_names = []
    for s in pad:
        sheet.append(s)
        cnt += 1
        if cnt == PER_SHEET:
            out = os.path.join(WORK, "namesheet_%02d.png" % si)
            cv2.imwrite(out, np_hstack(sheet))
            out_names.append(out)
            sheet, cnt, si = [], 0, si + 1
    if sheet:
        out = os.path.join(WORK, "namesheet_%02d.png" % si)
        cv2.imwrite(out, np_hstack(sheet))
        out_names.append(out)

    print("SHEETS:")
    for o in out_names:
        print(" ", o)
    print("INDEX->FILE:")
    for idx, fn in mapping:
        print("  #%02d %s" % (idx, fn))


def np_full(width, height):
    import numpy as np
    return np.full((height, width, 3), 30, np.uint8)


def np_vstack(arrs):
    import numpy as np
    return np.vstack(arrs)


def np_vpad(a, target_h):
    import numpy as np
    pad = np.full((target_h - a.shape[0], a.shape[1], 3), 30, np.uint8)
    return np.vstack([a, pad])


def np_hstack(arrs):
    import numpy as np
    return np.hstack(arrs)


if __name__ == "__main__":
    main()
