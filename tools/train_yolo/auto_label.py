#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
率土之滨 · 固定 UI 自动标注 / 确定性视觉质检器（免手框）
==========================================================
针对**固定布局的游戏 UI**，用「多尺度模板匹配 + NMS」离线自动产出 YOLO 标签，
同时把框画回图上供你肉眼验收。它一举两得：

  * 看 vis/ 的画框质量  → 判断「确定性 CV」这条路（方案 A）够不够用；
  * 生成的 labels/ 目录 → 直接就是训练 YOLO（方案 B）的数据集。

对固定 UI，模板匹配往往比 YOLO 更准、更稳、体积近零、且**不需要成千上万张手框**。

工作流（在你的终端跑，因为有 GUI 交互）
--------------------------------------
    # 1) 截 20~30 张游戏截图，丢进 tools/train_yolo/raw/
    # 2) 交互式裁模板（在一张图上按提示逐个拖框，回车确认 / 空格跳过）
    python tools/train_yolo/auto_label.py --make-templates tools/train_yolo/raw/shot001.png
    # 3) 自动标注全部截图 + 画框验收
    python tools/train_yolo/auto_label.py --images tools/train_yolo/raw --vis
    # 4) 打开 tools/train_yolo/dataset_vis/ 看框准不准；labels/ 已按 8:2 切好 train/val

类别顺序严格取自 data.yaml 的 names（与端侧 YoloDetector.DetectionClass.id 对齐，错位=点错按钮）。
诚实边界：本脚本只做真实匹配，绝不臆造框；某类没命中就是 0，报告里会标出来让你补模板。
"""

import argparse
import glob
import os
import random
import sys

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_YAML = os.path.join(HERE, "data.yaml")
TEMPLATES_DIR = os.path.join(HERE, "templates")
DEFAULT_OUT = os.path.join(HERE, "dataset")
DEFAULT_VIS = os.path.join(HERE, "dataset_vis")


def load_class_names(path):
    """从 data.yaml 读 names: {0: enemy_tile, ...}，避免依赖 PyYAML 的具体版本。"""
    if not os.path.isfile(path):
        die("找不到 data.yaml：%s（类别定义来源）" % path)
    names = {}
    in_names = False
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            raw = line.rstrip("\n")
            s = raw.strip()
            if s.startswith("names"):
                in_names = True
                continue
            if in_names:
                if not s or s.startswith("#"):
                    continue
                # 只有缩进行属于 names；遇到顶格键则结束
                if not raw.startswith((" ", "\t")):
                    break
                body = s.split("#", 1)[0].strip()
                if ":" not in body:
                    continue
                k, v = body.split(":", 1)
                try:
                    names[int(k.strip())] = v.strip().strip("'\"")
                except ValueError:
                    in_names = False
    if not names:
        die("data.yaml 里没解析到 names 类别表")
    return [names[i] for i in sorted(names)]


def die(msg):
    print("❌ " + msg, file=sys.stderr)
    sys.exit(1)


# ------------------------------------------------------------------ 模板制作
def make_templates(image_path, names):
    """用 OpenCV 交互式选框，在一张截图上为每个类别裁一张模板。"""
    if not os.path.isfile(image_path):
        die("样图不存在：%s" % image_path)
    img = cv2.imdecode(np.fromfile(image_path, dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        die("无法读取图片：%s" % image_path)
    os.makedirs(TEMPLATES_DIR, exist_ok=True)
    print("将逐类拖框裁模板；每类：拖矩形→回车接受 / 空格或c跳过 / q退出。")
    print("窗口标题即当前要标的类别。建议同屏能看到的先标，看不到的跳过（下轮换图再补）。\n")
    win = "pick template"
    made = 0
    for cid, cname in enumerate(names):
        tip = "[%d] %s  (回车=接受框, 空格/c=跳过, q=退出)" % (cid, cname)
        r = cv2.selectROI(tip, img, showImage=True, fromCenter=False)
        key = cv2.waitKey(0) & 0xFF
        x, y, w, h = [int(v) for v in r]
        if key == ord("q"):
            cv2.destroyWindow(tip)
            break
        if key == ord(" ") or key == ord("c") or w <= 2 or h <= 2:
            print("  跳过 %-14s" % cname)
            cv2.destroyWindow(tip)
            continue
        crop = img[y:y + h, x:x + w]
        outp = os.path.join(TEMPLATES_DIR, "%d_%s.png" % (cid, cname))
        ok, buf = cv2.imencode(".png", crop)
        if not ok:
            print("  编码失败 %s" % cname)
            continue
        buf.tofile(outp)
        made += 1
        print("  ✔ 存 %s  (%dx%d)" % (os.path.basename(outp), w, h))
        cv2.destroyWindow(tip)
    cv2.destroyAllWindows()
    print("\n完成：新增/更新 %d 类模板，目录 %s" % (made, TEMPLATES_DIR))
    print("提示：难采类（fortress 各外观 / camp 玩家营与 NPC 营地 / troop_cavalry与troop_infantry 微缩行军模型）需换对应场景的截图再跑一次本命令补齐。")


# ------------------------------------------------------------------ 加载模板
def load_templates(names):
    """扫描 templates/ 目录，按文件名前缀 cid_ 归类；一个类可有多张模板（多姿态/皮肤）。"""
    per_class = {i: [] for i in range(len(names))}
    for p in glob.glob(os.path.join(TEMPLATES_DIR, "*.png")) + \
            glob.glob(os.path.join(TEMPLATES_DIR, "*.jpg")):
        stem = os.path.splitext(os.path.basename(p))[0]
        head = stem.split("_", 1)[0]
        try:
            cid = int(head)
        except ValueError:
            continue
        if not (0 <= cid < len(names)):
            continue
        tpl = cv2.imdecode(np.fromfile(p, dtype=np.uint8), cv2.IMREAD_COLOR)
        if tpl is None or tpl.size == 0:
            continue
        per_class[cid].append((os.path.basename(p), tpl))
    return per_class


def parse_scales(spec):
    """'0.6:1.4:20' -> [0.6..1.4 共20个]。"""
    try:
        lo, hi, n = spec.split(":")
        return list(np.linspace(float(lo), float(hi), int(n)))
    except Exception:
        return list(np.linspace(0.6, 1.4, 20))


# ------------------------------------------------------------------ 匹配核心
def match_class(gray_img, tpls, scales, threshold, iou_thr):
    """对单个类别在整图上做多尺度模板匹配，返回该类的框列表 [x,y,w,h,score]。"""
    ih, iw = gray_img.shape
    boxes = []
    for tname, tpl in tpls:
        tg = cv2.cvtColor(tpl, cv2.COLOR_BGR2GRAY)
        th, tw = tg.shape[:2]
        for sc in scales:
            rw, rh = int(tw * sc), int(th * sc)
            if rw < 8 or rh < 8 or rw > iw or rh > ih:
                continue
            resized = cv2.resize(tg, (rw, rh), interpolation=cv2.INTER_AREA)
            res = cv2.matchTemplate(gray_img, resized, cv2.TM_CCOEFF_NORMED)
            ys, xs = np.where(res >= threshold)
            for y, x in zip(ys, xs):
                boxes.append([int(x), int(y), rw, rh, float(res[y, x])])
    return nms(boxes, iou_thr)


def nms(boxes, iou_thr):
    """标准贪心 NMS，去重复命中。"""
    if not boxes:
        return []
    arr = np.array([[b[0], b[1], b[0] + b[2], b[1] + b[3], b[4]] for b in boxes], dtype=float)
    x1, y1, x2, y2, sc = arr[:, 0], arr[:, 1], arr[:, 2], arr[:, 3], arr[:, 4]
    area = (x2 - x1) * (y2 - y1)
    order = sc.argsort()[::-1]
    keep = []
    while order.size:
        i = order[0]
        keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        inter = np.clip(xx2 - xx1, 0, None) * np.clip(yy2 - yy1, 0, None)
        iou = inter / (area[i] + area[order[1:]] - inter + 1e-6)
        order = order[1:][iou <= iou_thr]
    return [boxes[k] for k in keep]


COLORS = [(0, 200, 0), (0, 160, 255), (255, 120, 0), (200, 0, 200), (0, 220, 220),
          (255, 0, 0), (120, 255, 0), (0, 0, 255), (255, 255, 0), (255, 0, 160), (160, 0, 255)]


def run(images_dir, out_dir, vis_dir, names, threshold, scales, iou_thr, split, do_vis):
    exts = ("*.png", "*.jpg", "*.jpeg", "*.bmp")
    files = []
    for e in exts:
        files += glob.glob(os.path.join(images_dir, e))
    files = sorted(set(files))
    if not files:
        die("目录里没有图片：%s" % images_dir)

    per_class = load_templates(names)
    missing = [names[c] for c in per_class if not per_class[c]]
    if missing:
        print("⚠️  以下类别没有模板，将恒为 0 命中（换场景补 --make-templates）：%s" % ", ".join(missing))
    if all(not v for v in per_class.values()):
        die("templates/ 里一张模板都没有，无法匹配。先跑 --make-templates")

    os.makedirs(os.path.join(out_dir, "images"), exist_ok=True)
    os.makedirs(os.path.join(out_dir, "labels"), exist_ok=True)
    if do_vis:
        os.makedirs(vis_dir, exist_ok=True)

    total = {names[c]: 0 for c in per_class}
    labeled_imgs = 0
    for idx, fp in enumerate(files, 1):
        img = cv2.imdecode(np.fromfile(fp, dtype=np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            print("  跳过读不了的图 %s" % fp)
            continue
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        H, W = img.shape[:2]
        records = []
        for cid, tpls in per_class.items():
            if not tpls:
                continue
            for (x, y, w, h, sc) in match_class(gray, tpls, scales, threshold, iou_thr):
                records.append((cid, x, y, w, h, sc))
        if not records:
            continue
        labeled_imgs += 1
        stem = os.path.splitext(os.path.basename(fp))[0]
        lines = []
        for cid, x, y, w, h, sc in records:
            cx = (x + w / 2.0) / W
            cy = (y + h / 2.0) / H
            nw, nh = w / W, h / H
            lines.append("%d %.5f %.5f %.5f %.5f" % (cid, min(max(cx, 0), 1), min(max(cy, 0), 1),
                                                       min(max(nw, 0), 1), min(max(nh, 0), 1)))
            total[names[cid]] += 1
        # 写标签（先集中到 out/labels_all，稍后切分）
        allab = os.path.join(out_dir, "labels_all")
        os.makedirs(allab, exist_ok=True)
        with open(os.path.join(allab, stem + ".txt"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        alimg = os.path.join(out_dir, "images_all")
        os.makedirs(alimg, exist_ok=True)
        cv2.imwrite(os.path.join(alimg, os.path.basename(fp)), img)
        if do_vis:
            for cid, x, y, w, h, sc in records:
                col = COLORS[cid % len(COLORS)]
                cv2.rectangle(img, (x, y), (x + w, y + h), col, 2)
                cv2.putText(img, "%s %.2f" % (names[cid], sc), (x, max(0, y - 4)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.5, col, 1, cv2.LINE_AA)
            cv2.imwrite(os.path.join(vis_dir, stem + ".jpg"), img)
        print("  [%2d/%d] %-28s -> %d 框" % (idx, len(files), os.path.basename(fp)[:28], len(records)))

    split_dataset(out_dir, split)

    print("\n================ 自动标注汇总 ================")
    print("图片 %d 张，其中有目标 %d 张" % (len(files), labeled_imgs))
    for c in sorted(per_class):
        flag = "  ⚠️ 0 命中(补模板/换场景)" if total[names[c]] == 0 and per_class[c] else (
            "  ⚠️ 无模板" if not per_class[c] else "")
        print("  %-16s %5d%s" % (names[c], total[names[c]], flag))
    print("数据集：%s（train/val 已按 %.0f:%.0f 切好）" % (out_dir, split * 100, (1 - split) * 100))
    if do_vis:
        print("验收图：%s" % vis_dir)
    if labeled_imgs == 0:
        print("❗ 一个框都没匹配到：多半是 threshold 太高或模板与截图不匹配，降 --threshold 再试。")


def split_dataset(out_dir, split):
    """把 images_all/labels_all 随机切成 images/{train,val}+labels/{train,val}。"""
    alimg = os.path.join(out_dir, "images_all")
    alab = os.path.join(out_dir, "labels_all")
    if not os.path.isdir(alimg):
        return
    stems = [os.path.splitext(f)[0] for f in os.listdir(alab)]
    random.Random(42).shuffle(stems)
    n_tr = max(1, int(len(stems) * split)) if len(stems) > 1 else len(stems)
    groups = {"train": stems[:n_tr], "val": stems[n_tr:]}
    for sub, items in groups.items():
        di = os.path.join(out_dir, "images", sub)
        dl = os.path.join(out_dir, "labels", sub)
        os.makedirs(di, exist_ok=True)
        os.makedirs(dl, exist_ok=True)
    import shutil
    for sub, items in groups.items():
        for s in items:
            for ext in (".png", ".jpg", ".jpeg", ".bmp"):
                src = os.path.join(alimg, s + ext)
                if os.path.isfile(src):
                    shutil.copy2(src, os.path.join(out_dir, "images", sub, s + ext))
                    break
            shutil.copy2(os.path.join(alab, s + ".txt"), os.path.join(out_dir, "labels", sub, s + ".txt"))
    shutil.rmtree(alimg, ignore_errors=True)
    shutil.rmtree(alab, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description="固定 UI 免手框自动标注 / 确定性视觉质检")
    ap.add_argument("--images", default=os.path.join(HERE, "raw"), help="原始截图目录")
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出数据集根目录")
    ap.add_argument("--vis-dir", default=DEFAULT_VIS, help="画框验收图目录")
    ap.add_argument("--threshold", type=float, default=0.85, help="匹配阈值 0~1，越高越严")
    ap.add_argument("--scales", default="0.6:1.4:20", help="多尺度 'from:to:num'")
    ap.add_argument("--iou", type=float, default=0.35, help="NMS IoU 阈值")
    ap.add_argument("--split", type=float, default=0.8, help="train 占比")
    ap.add_argument("--vis", action="store_true", help="额外输出画框验收图")
    ap.add_argument("--make-templates", metavar="IMG", help="交互式在该样图上裁各类模板后退出")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    names = load_class_names(DATA_YAML)
    if args.make_templates:
        make_templates(args.make_templates, names)
        return
    scales = parse_scales(args.scales)
    run(args.images, args.out, args.vis_dir, names, args.threshold, scales, args.iou,
        args.split, args.vis)


if __name__ == "__main__":
    main()
