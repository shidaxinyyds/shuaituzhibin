#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""率土 YOLO bootstrap 预打标器（自训练第二步）
================================================
你手框 ~40 张种子 → 训出 v0 权重 → 本脚本用 v0 给**剩余未标注原图**批量预打标签，
生成 LabelImg 可直接打开编辑的 PascalVOC .xml。你只做"改框"，不必从零画。

它不臆造：低于 --conf 的框一律丢弃；无检测的图写空 xml（=负样本，交给训练当背景）。
类别 id↔名 一律以 data.yaml 为准（与端侧 YoloDetector.DetectionClass 对齐）。

依赖：ultralytics（训练环境里装）；仅在装了 torch+ultralytics 的机器上运行。

用法
----
    python tools/train_yolo/prelabel.py \
        --weights runs/detect/train/weights/best.pt \
        --raw tools/ocr_regression/raw \
        --dataset tools/train_yolo/dataset \
        --conf 0.25

    # 之后用 LabelImg 打开 dataset/images/val（或你指定的待修目录）逐个改框即可
"""
import argparse
import glob
import os
import sys
import xml.sax.saxutils as sx

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_YAML = os.path.join(HERE, "data.yaml")
IMG_EXTS = (".jpg", ".jpeg", ".png", ".bmp")

XML_TMPL = """<annotation>
  <folder>{folder}</folder>
  <filename>{filename}</filename>
  <path>{path}</path>
  <source><database>Unknown</database></source>
  <size><width>{w}</width><height>{h}</height><depth>3</depth></size>
  <segmented>0</segmented>
{objs}</annotation>
"""

OBJ_TMPL = ("  <object>\n    <name>{name}</name>\n    <pose>Unspecified</pose>"
            "\n    <truncated>0</truncated>\n    <difficult>0</difficult>"
            "\n    <bndbox>\n      <xmin>{x1}</xmin>\n      <ymin>{y1}</ymin>"
            "\n      <xmax>{x2}</xmax>\n      <ymax>{y2}</ymax>\n    </bndbox>"
            "  </object>\n")


def die(msg):
    print("ERROR " + msg, file=sys.stderr)
    sys.exit(1)


def load_id_to_name(path=DATA_YAML):
    if not os.path.isfile(path):
        die("找不到 data.yaml：%s" % path)
    id_to_name = {}
    in_names = False
    with open(path, "r", encoding="utf-8") as fh:
        for raw in fh:
            s = raw.strip()
            if s.startswith("names"):
                in_names = True
                continue
            if in_names:
                if not s or s.startswith("#"):
                    continue
                if not raw.startswith((" ", "\t")):
                    break
                body = s.split("#", 1)[0].strip()
                if ":" not in body:
                    continue
                k, v = body.split(":", 1)
                try:
                    id_to_name[int(k.strip())] = v.strip().strip("'\"")
                except ValueError:
                    in_names = False
    if not id_to_name:
        die("data.yaml 未解析到 names")
    return id_to_name


def cmd_prelabel(args):
    try:
        from ultralytics import YOLO
        from PIL import Image
    except ImportError as e:
        die("需要先装 ultralytics + pillow（在训练环境里）：%s" % e)
    if not os.path.isfile(args.weights):
        die("权重不存在：%s（先用种子训出 v0）" % args.weights)

    id_to_name = load_id_to_name()
    raw = os.path.abspath(args.raw)
    dataset = os.path.abspath(args.dataset)
    imgs = [p for p in sorted(glob.glob(os.path.join(raw, "*")))
            if p.lower().endswith(IMG_EXTS)]
    if not imgs:
        die("raw 里没有图片：%s" % raw)

    model = YOLO(args.weights)
    written = skipped = total_boxes = 0
    for img in imgs:
        stem = os.path.splitext(os.path.basename(img))[0]
        xml_path = os.path.join(dataset, "labels", "val", stem + ".xml")
        # 已手框过的（train 或 val 已有 xml）不覆盖，避免抹掉你的人工成果
        done = [os.path.join(dataset, "labels", sp, stem + ".xml")
                for sp in ("train", "val")]
        if any(os.path.isfile(p) for p in done):
            skipped += 1
            continue
        with Image.open(img) as im:
            W, H = im.size
        res = model.predict(img, conf=args.conf, verbose=False, device=args.device)[0]
        objs = []
        for b in res.boxes:
            cid = int(b.cls.item())
            name = id_to_name.get(cid)
            if name is None:
                continue
            x1, y1, x2, y2 = [float(v) for v in b.xyxy[0].tolist()]
            x1 = max(0, min(W - 1, x1)); y1 = max(0, min(H - 1, y1))
            x2 = max(0, min(W, x2)); y2 = max(0, min(H, y2))
            objs.append(OBJ_TMPL.format(
                name=sx.escape(name), x1=int(x1), y1=int(y1),
                x2=int(x2), y2=int(y2)))
        os.makedirs(os.path.dirname(xml_path), exist_ok=True)
        xml = XML_TMPL.format(
            folder=sx.escape("val"), filename=sx.escape(os.path.basename(img)),
            path=sx.escape(xml_path), w=W, h=H, objs="".join(objs))
        with open(xml_path, "w", encoding="utf-8") as fh:
            fh.write(xml)
        written += 1
        total_boxes += len(objs)
    print("OK prelabel: 预打标 %d 张（共 %d 框），跳过已标注 %d 张 → %s"
          % (written, total_boxes, skipped, os.path.join(dataset, "labels", "val")))
    print("下一步：用 LabelImg 打开 dataset/images/val 逐个改框（VOC 模式，别改类别名），"
          "改完跑 prepare_dataset.py convert，再训 v1。")


def main():
    ap = argparse.ArgumentParser(description="率土 YOLO bootstrap 预打标器")
    ap.add_argument("--weights", required=True, help="v0 权重 .pt")
    ap.add_argument("--raw", default=os.path.join(HERE, "..", "ocr_regression", "raw"))
    ap.add_argument("--dataset", default=os.path.join(HERE, "dataset"))
    ap.add_argument("--conf", type=float, default=0.25, help="预打标置信阈值，宁低勿高")
    ap.add_argument("--device", default="cpu", help="cpu 或 0(GPU)")
    args = ap.parse_args()
    cmd_prelabel(args)


if __name__ == "__main__":
    main()
