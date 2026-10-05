#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""率土之滨 YOLO 数据集准备器（split / convert / tile）
========================================================
把「一堆无标注全地图截图」推进到「ultralytics 可训练数据集」的三步工具。
类别 id 一律以 data.yaml 的 names 为准（与端侧 YoloDetector.DetectionClass 严格对齐），
**按类别名映射**，从根上规避 LabelImg 数字排序错位这个坑。

工作流
------
    # 1) 切分：把 raw 原图 8:2 灌进 dataset/images/{train,val}，建空 labels 目录
    python tools/train_yolo/prepare_dataset.py split \
        --raw tools/ocr_regression/raw --ratio 0.8

    # 2) 标注：用 LabelImg 以【VOC/XML】模式打开 dataset/images/train
    #    （自动保存目录设成 dataset/labels/train；类别名照 classes.predef.xml，别改拼写）
    #    python tools/train_yolo/prepare_dataset.py predef   # 打印/生成 7 类预定义文件

    # 3) 转换：把 LabelImg 的 .xml 按名字→id 转成 YOLO .txt（写回 dataset/labels/{train,val}）
    python tools/train_yolo/prepare_dataset.py convert

    # 4)（可选）切片：全地图缩到 640 后微缩部队只剩几像素，切成带标签的 640 瓦片
    python tools/train_yolo/prepare_dataset.py tile --tile-size 640 --overlap 0.2

诚实边界：convert/tile 只做真实几何换算，绝不臆造框；未知类别名一律跳过并如实报告，
让你去补 data.yaml 或改标签，而不是猜。
"""
import argparse
import glob
import os
import random
import shutil
import sys
import xml.etree.ElementTree as ET

# Windows GBK 控制台/重定向下，✔❌ 等非 ASCII 符号会触发 UnicodeEncodeError；
# 强制 stdout 走 UTF-8 并对不可编码字符做替换，保证跨终端不崩。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except (AttributeError, ValueError):
    pass

try:
    from PIL import Image
except ImportError:
    Image = None

HERE = os.path.dirname(os.path.abspath(__file__))
DATA_YAML = os.path.join(HERE, "data.yaml")
DEFAULT_DATASET = os.path.join(HERE, "dataset")
IMG_EXTS = (".jpg", ".jpeg", ".png", ".bmp")


def die(msg):
    print("❌ " + msg, file=sys.stderr)
    sys.exit(1)


def load_name_to_id(path=DATA_YAML):
    """从 data.yaml 的 names 段解析 {类别名: id}。id 顺序即端侧契约。"""
    if not os.path.isfile(path):
        die("找不到 data.yaml：%s" % path)
    name_to_id = {}
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
                if not raw.startswith((" ", "\t")):  # 顶格键 → names 段结束
                    break
                body = s.split("#", 1)[0].strip()
                if ":" not in body:
                    continue
                k, v = body.split(":", 1)
                try:
                    name_to_id[v.strip().strip("'\"")] = int(k.strip())
                except ValueError:
                    in_names = False
    if not name_to_id:
        die("data.yaml 未解析到 names 类别表")
    return name_to_id


# ------------------------------------------------------------------- split
def cmd_split(args):
    raw = os.path.abspath(args.raw)
    out = os.path.abspath(args.out)
    if not os.path.isdir(raw):
        die("raw 目录不存在：%s" % raw)
    imgs = [p for p in glob.glob(os.path.join(raw, "*"))
            if p.lower().endswith(IMG_EXTS)]
    if not imgs:
        die("raw 目录里没有图片：%s" % raw)
    imgs.sort()
    random.seed(args.seed)
    random.shuffle(imgs)
    n_val = int(round(len(imgs) * (1.0 - args.ratio)))
    val, train = imgs[:n_val], imgs[n_val:]
    for split, files in (("train", train), ("val", val)):
        img_dir = os.path.join(out, "images", split)
        lbl_dir = os.path.join(out, "labels", split)
        os.makedirs(img_dir, exist_ok=True)
        os.makedirs(lbl_dir, exist_ok=True)
        for src in files:
            shutil.copy2(src, os.path.join(img_dir, os.path.basename(src)))
    print("✔ split 完成：train=%d val=%d → %s" % (len(train), len(val), out))
    print("下一步：用 LabelImg（VOC 模式）标注 %s，类别名照 classes.predef.xml；"
          "标完跑 `convert`。" % os.path.join(out, "images", "train"))


# ----------------------------------------------------------------- convert
def _find_xml(img_path, dataset):
    stem = os.path.splitext(os.path.basename(img_path))[0]
    split = os.path.basename(os.path.dirname(img_path))  # train / val
    for cand in (
        os.path.join(os.path.dirname(img_path), stem + ".xml"),
        os.path.join(dataset, "labels", split, stem + ".xml"),
    ):
        if os.path.isfile(cand):
            return cand
    return None


def cmd_convert(args):
    dataset = os.path.abspath(args.dataset)
    name_to_id = load_name_to_id()
    total_boxes = 0
    unknown = {}
    per_class = {}
    converted = 0
    missing_xml = 0
    for split in ("train", "val"):
        img_dir = os.path.join(dataset, "images", split)
        lbl_dir = os.path.join(dataset, "labels", split)
        if not os.path.isdir(img_dir):
            continue
        os.makedirs(lbl_dir, exist_ok=True)
        for img in sorted(glob.glob(os.path.join(img_dir, "*"))):
            if not img.lower().endswith(IMG_EXTS):
                continue
            xml = _find_xml(img, dataset)
            if not xml:
                missing_xml += 1
                continue
            w, h, objs = _parse_voc(xml)
            if not w or not h:
                w, h = _image_size(img) or (w, h)
            if not w or not h:
                print("  跳过（无尺寸）：%s" % xml)
                continue
            lines = []
            for name, (x1, y1, x2, y2) in objs:
                cid = name_to_id.get(name)
                if cid is None:
                    unknown[name] = unknown.get(name, 0) + 1
                    continue
                cx = ((x1 + x2) / 2.0) / w
                cy = ((y1 + y2) / 2.0) / h
                bw = (x2 - x1) / float(w)
                bh = (y2 - y1) / float(h)
                cx, cy = _clip01(cx), _clip01(cy)
                bw, bh = _clip01(bw), _clip01(bh)
                if bw <= 0 or bh <= 0:
                    continue
                lines.append("%d %.6f %.6f %.6f %.6f" % (cid, cx, cy, bw, bh))
                per_class[name] = per_class.get(name, 0) + 1
            stem = os.path.splitext(os.path.basename(img))[0]
            with open(os.path.join(lbl_dir, stem + ".txt"), "w", encoding="utf-8") as fh:
                fh.write("\n".join(lines) + ("\n" if lines else ""))
            total_boxes += len(lines)
            converted += 1
    print("✔ convert 完成：%d 张图 → YOLO txt，共 %d 个框" % (converted, total_boxes))
    if missing_xml:
        print("⚠ %d 张图没找到对应 .xml（还没标注？），已生成空标签（=负样本）。" % missing_xml)
    if per_class:
        print("每类实例数：")
        for n in sorted(per_class, key=lambda k: -per_class[k]):
            print("   %-16s %d" % (n, per_class[n]))
    if unknown:
        print("⚠ 未知类别名（已跳过，请改标签或在 data.yaml 补类）：")
        for n, c in sorted(unknown.items(), key=lambda kv: -kv[1]):
            print("   %-16s %d" % (n, c))


def _parse_voc(xml_path):
    root = ET.parse(xml_path).getroot()
    size = root.find("size")
    w = h = None
    if size is not None:
        w = int(float(size.findtext("width", "0") or 0)) or None
        h = int(float(size.findtext("height", "0") or 0)) or None
    objs = []
    for o in root.findall("object"):
        name = (o.findtext("name") or "").strip()
        bb = o.find("bndbox")
        if not name or bb is None:
            continue
        try:
            x1 = float(bb.findtext("xmin")); y1 = float(bb.findtext("ymin"))
            x2 = float(bb.findtext("xmax")); y2 = float(bb.findtext("ymax"))
        except (TypeError, ValueError):
            continue
        objs.append((name, (x1, y1, x2, y2)))
    return w, h, objs


def _image_size(img):
    if Image is None:
        return None
    try:
        with Image.open(img) as im:
            return im.size
    except Exception:
        return None


def _clip01(v):
    return 0.0 if v < 0 else (1.0 if v > 1 else v)


# -------------------------------------------------------------------- tile
def cmd_tile(args):
    if Image is None:
        die("tile 需要 Pillow：pip install pillow")
    dataset = os.path.abspath(args.dataset)
    out = os.path.abspath(args.out)
    ts = args.tile_size
    stride = max(1, int(round(ts * (1.0 - args.overlap))))
    made = 0
    for split in ("train", "val"):
        img_dir = os.path.join(dataset, "images", split)
        lbl_dir = os.path.join(dataset, "labels", split)
        if not os.path.isdir(img_dir):
            continue
        oi = os.path.join(out, "images", split)
        ol = os.path.join(out, "labels", split)
        os.makedirs(oi, exist_ok=True)
        os.makedirs(ol, exist_ok=True)
        for img in sorted(glob.glob(os.path.join(img_dir, "*"))):
            if not img.lower().endswith(IMG_EXTS):
                continue
            stem = os.path.splitext(os.path.basename(img))[0]
            lbl = os.path.join(lbl_dir, stem + ".txt")
            boxes = _read_yolo(lbl)  # [(cid, xc, yc, w, h)] 归一化
            with Image.open(img) as im:
                W, H = im.size
            abs_boxes = [_to_abs(b, W, H) for b in boxes]
            for ty in range(0, max(1, H), stride):
                for tx in range(0, max(1, W), stride):
                    tw = min(ts, W - tx)
                    th = min(ts, H - ty)
                    if tw <= 0 or th <= 0:
                        continue
                    in_tile = []
                    for cid, (bx1, by1, bx2, by2) in abs_boxes:
                        ix1 = max(bx1, tx); iy1 = max(by1, ty)
                        ix2 = min(bx2, tx + tw); iy2 = min(by2, ty + th)
                        if ix2 - ix1 < 4 or iy2 - iy1 < 4:
                            continue  # 交叠太小，丢弃
                        in_tile.append((cid, ix1, iy1, ix2, iy2))
                    if not in_tile and not args.keep_empty:
                        if tx == 0 and ty == 0:
                            pass  # 至少保留左上角一张背景片，供负样本
                        elif not (tx + tw >= W or ty + th >= H):
                            continue
                    with Image.open(img) as im:
                        crop = im.convert("RGB").crop((tx, ty, tx + tw, ty + th))
                    crop.save(os.path.join(oi, "%s_t%d_%d.jpg" % (stem, tx, ty)))
                    lines = []
                    for cid, x1, y1, x2, y2 in in_tile:
                        cx = ((x1 + x2) / 2.0 - tx) / tw
                        cy = ((y1 + y2) / 2.0 - ty) / th
                        bw = (x2 - x1) / float(tw)
                        bh = (y2 - y1) / float(th)
                        lines.append("%d %.6f %.6f %.6f %.6f"
                                     % (cid, _clip01(cx), _clip01(cy), _clip01(bw), _clip01(bh)))
                    with open(os.path.join(ol, "%s_t%d_%d.txt" % (stem, tx, ty)),
                              "w", encoding="utf-8") as fh:
                        fh.write("\n".join(lines) + ("\n" if lines else ""))
                    made += 1
    _write_data_yaml(out, load_name_to_id())
    print("✔ tile 完成：%d 张瓦片 → %s" % (made, out))
    print("已写自包含 data.yaml（path: .），训练直接： --data %s" %
          os.path.join(out, "data.yaml"))


def _write_data_yaml(out_dir, name_to_id):
    """在瓦片数据集根目录生成自包含 data.yaml，省掉手改 path 的坑。"""
    ordered = sorted(name_to_id.items(), key=lambda kv: kv[1])
    lines = [
        "# 由 prepare_dataset.py tile 自动生成（自包含，path 相对本文件所在目录）",
        "path: .",
        "train: images/train",
        "val: images/val",
        "",
        "nc: %d" % len(ordered),
        "names:",
    ]
    for name, cid in ordered:
        lines.append("  %d: %s" % (cid, name))
    with open(os.path.join(out_dir, "data.yaml"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def _read_yolo(lbl):
    out = []
    if not os.path.isfile(lbl):
        return out
    with open(lbl, "r", encoding="utf-8") as fh:
        for line in fh:
            p = line.split()
            if len(p) >= 5:
                out.append((int(p[0]), float(p[1]), float(p[2]), float(p[3]), float(p[4])))
    return out


def _to_abs(b, W, H):
    cid, xc, yc, bw, bh = b
    cx, cy = xc * W, yc * H
    w, h = bw * W, bh * H
    return cid, (cx - w / 2.0, cy - h / 2.0, cx + w / 2.0, cy + h / 2.0)


# ------------------------------------------------------------------ predef
PREDEF = """<annotation>
  <folder>images</folder>
  <filename>predefine_classes.jpg</filename>
  <path/>
  <source><database>Unknown</database></source>
  <size><width>0</width><height>0</height><depth>3</depth></size>
  <segmented>0</segmented>
%s
</annotation>
"""


def cmd_predef(args):
    name_to_id = load_name_to_id()
    ordered = sorted(name_to_id.items(), key=lambda kv: kv[1])
    objs = []
    for name, _cid in ordered:
        objs.append(
            "  <object>\n    <name>%s</name>\n    <pose>Unspecified</pose>"
            "\n    <truncated>0</truncated>\n    <difficult>0</difficult>"
            "\n    <bndbox>\n      <xmin>0</xmin>\n      <ymin>0</ymin>"
            "\n      <xmax>0</xmax>\n      <ymax>0</ymax>\n    </bndbox>\n  </object>"
            % name)
    xml = PREDEF % "\n".join(objs)
    path = os.path.join(HERE, "classes.predef.xml")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(xml)
    print("✔ 已生成 LabelImg 预定义类：%s" % path)
    print("  在 LabelImg 里 View → Use Predefined Classes 选它；类别名请照抄，别改拼写。")


def main():
    ap = argparse.ArgumentParser(description="率土 YOLO 数据集准备器")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("split", help="raw 原图 → dataset/images/{train,val}")
    s.add_argument("--raw", default=os.path.join(HERE, "..", "ocr_regression", "raw"))
    s.add_argument("--out", default=DEFAULT_DATASET)
    s.add_argument("--ratio", type=float, default=0.8, help="train 占比")
    s.add_argument("--seed", type=int, default=7)
    s.set_defaults(fn=cmd_split)

    c = sub.add_parser("convert", help="LabelImg .xml → YOLO .txt（按名字→id）")
    c.add_argument("--dataset", default=DEFAULT_DATASET)
    c.set_defaults(fn=cmd_convert)

    t = sub.add_parser("tile", help="带标签切片大图（微缩目标友好）")
    t.add_argument("--dataset", default=DEFAULT_DATASET)
    t.add_argument("--out", default=os.path.join(HERE, "dataset_tiled"))
    t.add_argument("--tile-size", type=int, default=640)
    t.add_argument("--overlap", type=float, default=0.2)
    t.add_argument("--keep-empty", action="store_true", help="保留无目标瓦片")
    t.set_defaults(fn=cmd_tile)

    p = sub.add_parser("predef", help="生成 classes.predef.xml")
    p.set_defaults(fn=cmd_predef)

    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
