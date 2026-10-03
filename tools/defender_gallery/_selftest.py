#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""守军头像识别离线自测：完全复刻运行时的几何与匹配数学。

对每张截图的三行槽，按 build_gallery 的归一化槽位裁出并 resize 到规范尺寸，
用 TM_CCOEFF_NORMED 与 defender_refs 里所有模板比对（同尺寸=单点归一化相关，
与 OpenCvMatcher.match 在 src==template 尺寸时的行为同构），取最高分者为识别结果。
统计 argmax 是否命中清单里的正确武将，并打印分数分布以判断阈值 0.62 是否合理。
"""
import json
import os

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw")
REFS = os.path.normpath(os.path.join(
    HERE, "..", "..", "client", "app", "src", "main", "assets", "defender_refs"))

idx = json.load(open(os.path.join(REFS, "index.json"), encoding="utf-8"))
SLOTS = idx["slots"]
CW, CH = idx["canon"]
heroes = idx["heroes"]  # name -> hash

# 载入全部模板为 BGR 彩色，与运行时 OpenCvMatcher.match 同构：
# 同尺寸 cv2.matchTemplate(TM_CCOEFF_NORMED) 输出 1x1，取 [0,0] 即为归一化相关分。
templates = {}
for name, h in heroes.items():
    templates[name] = cv2.imread(os.path.join(REFS, "%s.png" % h))  # BGR


def crop_norm(img, rect):
    h, w = img.shape[:2]
    l, t, r, b = rect
    x0, y0 = int(l * w), int(t * h)
    x1, y1 = int(r * w), int(b * h)
    piece = img[y0:y1, x0:x1]
    return cv2.resize(piece, (CW, CH), interpolation=cv2.INTER_AREA)


def score(src_bgr, tpl_bgr):
    # 与 Java 侧一致：TM_CCOEFF_NORMED，同尺寸 -> 1x1 结果
    res = cv2.matchTemplate(src_bgr, tpl_bgr, cv2.TM_CCOEFF_NORMED)
    return float(res[0][0])


manifest = json.load(open(os.path.join(HERE, "manifest.json"), encoding="utf-8"))
total = correct = 0
THRESH = float(os.environ.get("TH", "0.62"))
accepted = accepted_correct = 0
best_scores, second_scores = [], []
for entry in manifest:
    src = os.path.join(RAW, entry["file"])
    img = cv2.imread(src)
    if img is None:
        continue
    gray = img  # BGR 彩色，交给 matchTemplate 跨通道求和
    for rect, expect in zip(SLOTS, entry["heroes"]):
        if not expect:
            continue
        slot = crop_norm(gray, rect)
        scores = sorted(((score(slot, t), n) for n, t in templates.items()), reverse=True)
        top_score, top_name = scores[0]
        second_score = scores[1][0] if len(scores) > 1 else -1
        total += 1
        if top_name == expect:
            correct += 1
        if top_score >= THRESH:
            accepted += 1
            if top_name == expect:
                accepted_correct += 1
        else:
            print("  MISS 期望=%s 实得=%s(%.3f) 次高=%s(%.3f)" % (expect, top_name, top_score, scores[1][1], second_score))
        best_scores.append(top_score)
        second_scores.append(second_score)

bs = np.array(best_scores); ss = np.array(second_scores)
print("\n==== 守军头像识别自测 ====")
print("样本槽数=%d  命中=%d  准确率=%.1f%%" % (total, correct, 100.0 * correct / total))
print("正确目标最高分: min=%.3f mean=%.3f" % (bs.min(), bs.mean()))
print("次高(竞争者)分: max=%.3f mean=%.3f" % (ss.max(), ss.mean()))
print("阈值 %.2f 下可判为命中的槽数=%d/%d" % (THRESH, int((bs >= THRESH).sum()), total))
print("接受(>=阈值)=%d  其中正确=%d  精度=%.1f%%  覆盖率=%.1f%%" % (
    accepted, accepted_correct,
    100.0 * accepted_correct / max(accepted, 1),
    100.0 * accepted / total))
print("被误接受(错认)数=%d" % (accepted - accepted_correct))
print("最高分与次高分离度: min=%.3f" % (bs - ss).min())
