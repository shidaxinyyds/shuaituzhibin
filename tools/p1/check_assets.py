#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
入包资产体检（离线、纯 stdlib，CI 可直接跑）
===========================================

对应清单「体积预算（选 bge-small 时，最终入包）」。做四件事：

1. **体积实测**：扫描 `client/app/src/main/assets/`（含 `assets/models/` 与根目录的
   PP-OCR 权重），按引擎（①YOLO ②OCR ③bge+索引 ④意图微脑）分组打印真实字节数，
   并对照硬上限 185MB；
2. **索引契约**：`slg_knowledge_vector_hnsw.bin` 必须能被整除解析完
   （头 → 条目 → HNSW 图区，尾部无残留），维度由文件头给出；
3. **假权重体检**：
   * 0 字节文件 → 直接失败；
   * `.onnx` 头部不是 `ONNX` 魔数 → 失败（八成是 html/伪文件）；
   * `.param` 首行不是 ncnn 魔数 `7767517` → 失败；
   * `.bin` 小于 1KB → 失败；
4. **诚实清单**：逐项打印"预期入包文件 / 是否就位"，缺失明确写"缺失"，
   不把一个都不存在的方案说成"已就绪"。

用法
----
    python tools/p1/check_assets.py
    python tools/p1/check_assets.py --json
    python tools/p1/check_assets.py --cap-mb 185

退出码：0 = 通过；1 = 有问题（体积超限 / 解析失败 / 假权重）。
"""

import argparse
import json
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

ASSETS = os.path.join("client", "app", "src", "main", "assets")
DEFAULT_CAP_MB = 185.0

EXPECT = [
    # (引擎, 相对 assets 的路径, 说明)
    ("① YOLO ncnn", "models/yolov8n_stzb.param", "YOLO 主通道（ModelAssetManager 首选）"),
    ("① YOLO ncnn", "models/yolov8n_stzb.bin", "同上，bin"),
    ("① YOLO ncnn", "models/yolov8s_stzb.param", "YOLO 备选命名（已兼容）"),
    ("① YOLO ncnn", "models/yolov8s_stzb.bin", "同上"),
    ("② OCR v3", "ch_PP-OCRv3_det_infer.param", "已在包"),
    ("② OCR v3", "ch_PP-OCRv3_det_infer.bin", "已在包"),
    ("② OCR v3", "ch_PP-OCRv3_rec_infer.param", "已在包"),
    ("② OCR v3", "ch_PP-OCRv3_rec_infer.bin", "已在包"),
    ("② OCR v3", "ch_ppocr_mobile_v2.0_cls_infer.param", "已在包"),
    ("② OCR v3", "ch_ppocr_mobile_v2.0_cls_infer.bin", "已在包"),
    ("② OCR v4", "ch_PP-OCRv4_det_infer.param", "由 onnx2ncnn.py 产出后入包"),
    ("② OCR v4", "ch_PP-OCRv4_det_infer.bin", "同上"),
    ("② OCR v4", "ch_PP-OCRv4_rec_infer.param", "同上"),
    ("② OCR v4", "ch_PP-OCRv4_rec_infer.bin", "同上"),
    ("③ bge", "models/bge_zh_int8.onnx", "bge-small-zh-v1.5 INT8"),
    ("③ bge", "models/bge_zh_vocab.txt", "bge 词表"),
    ("③ 索引", "models/slg_knowledge_vector_hnsw.bin", "RAG 向量索引（V1/V2）"),
    ("④ 微脑", "models/intent_slot_zh.onnx", "意图+槽位 INT8"),
    ("④ 微脑", "models/intent_slot_vocab.txt", "目标词表"),
]

ONNX_MAGIC = b"ONNX"
NCNN_MAGIC = "7767517"


def human(n):
    return "%.2f MB" % (n / (1024.0 * 1024)) if n >= 1024 * 1024 else "%.1f KB" % (n / 1024.0)


def walk_models():
    root = os.path.join(REPO, ASSETS)
    files = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git",)]
        for fn in filenames:
            p = os.path.join(dirpath, fn)
            files.append((os.path.relpath(p, root), os.path.getsize(p)))
    return files


def check_structure(rel, size, root):
    """结构性体检。返回错误列表。"""
    errs = []
    full = os.path.join(root, rel)
    if size == 0:
        return ["0 字节（空壳）"]
    if rel.endswith(".onnx"):
        with open(full, "rb") as fh:
            if fh.read(4) != ONNX_MAGIC:
                errs.append("ONNX 头部不是 ONNX 魔数")
    elif rel.endswith(".param"):
        with open(full, "r", encoding="utf-8", errors="replace") as fh:
            if fh.readline().strip() != NCNN_MAGIC:
                errs.append("param 首行不是 ncnn 魔数 %s" % NCNN_MAGIC)
    elif rel.endswith(".bin"):
        if size < 1024:
            errs.append("bin 只有 %s（<1KB）" % human(size))
    return errs


def check_index(full):
    """解析 V1/V2 索引容器，返回 (错误列表, 摘要)。"""
    errs = []
    with open(full, "rb") as fh:
        raw = fh.read()
    if len(raw) < 28:
        return ["文件短于 28 字节，连头都读不完"], {}
    magic = raw[:16]
    ver, cnt, dim = struct.unpack("<III", raw[16:28])
    if magic not in (b"SLG_HNSW_RAG_V1\x00", b"SLG_HNSW_RAG_V2\x00"):
        return ["magic 不是 V1/V2：%r" % magic], {"version": ver, "items": cnt, "dim": dim}

    off = 28
    for _ in range(min(cnt, 100000)):
        if off + 24 > len(raw):
            errs.append("第 %d 条长度字段越界" % _)
            break
        lens = struct.unpack("<IIIIII", raw[off:off + 24])
        off += 24 + sum(lens) + 4 * dim
        if off > len(raw):
            errs.append("第 %d 条越界" % _)
            break
    else:
        if ver == 2:
            if off + 4 > len(raw):
                errs.append("HNSW 图区长度字段越界")
            else:
                (glen,) = struct.unpack("<I", raw[off:off + 4])
                off += 4 + glen
        if off != len(raw):
            errs.append("解析完剩 %d 字节（容器与解析规则不一致）" % (len(raw) - off))
    return errs, {"version": ver, "items": cnt, "dim": dim, "bytes": len(raw)}


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--cap-mb", type=float, default=DEFAULT_CAP_MB)
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()

    root = os.path.join(REPO, ASSETS)
    files = walk_models()
    by_engine = {}
    problems = []
    total = 0

    for rel0, size in sorted(files):
        rel = rel0.replace("\\", "/")
        if rel.endswith((".md", ".png")):
            continue  # 文档/模板素材不是权重，不计入体积分组
        total += size
        stem = os.path.splitext(os.path.basename(rel))[0]
        if rel.startswith("models/"):
            engine = ("③ 索引" if "hnsw" in stem else
                      "③ bge" if "bge" in stem else
                      "④ 微脑" if "intent" in stem else
                      "① YOLO ncnn" if "yolov" in stem else "其它")
        else:
            engine = "② OCR"
        by_engine.setdefault(engine, []).append((rel, size))

        errs = check_structure(rel, size, root)
        if errs:
            problems.append((rel, "; ".join(errs)))

    index_note = {}
    for rel, _ in files:
        if rel.endswith("slg_knowledge_vector_hnsw.bin"):
            errs, summary = check_index(os.path.join(root, rel))
            index_note[rel] = summary
            if errs:
                problems.append((rel, "; ".join(errs)))

    total_mb = total / (1024.0 * 1024)
    over = total_mb > args.cap_mb

    if args.json:
        print(json.dumps({
            "cap_mb": args.cap_mb,
            "assets_mb": round(total_mb, 2),
            "engines": {k: {"files": v, "bytes": sum(s for _, s in v)} for k, v in by_engine.items()},
            "index": index_note,
            "problems": problems,
        }, ensure_ascii=False, indent=2))
        return 1 if (problems or over) else 0

    print("=" * 84)
    print("入包资产体检（上限 %.0fMB）" % args.cap_mb)
    print("=" * 84)
    for eng in sorted(by_engine):
        items = by_engine[eng]
        print("%-14s %s" % (eng, " + ".join("%s(%s)" % (r, human(s)) for r, s in items)))
    print("-" * 84)
    print("assets 内权重合计: %.2f MB（上限 %.0f MB，余量 %.2f MB）" % (total_mb, args.cap_mb, args.cap_mb - total_mb))
    if index_note:
        print("RAG 索引解析: " + json.dumps(index_note, ensure_ascii=False))
    print("-" * 84)
    print("预期入包清单：")
    present = set(rel.replace("\\", "/") for rel, _ in files)
    for eng, rel, note in EXPECT:
        mark = "✅" if rel in present else "❌ 缺失"
        print("  %s %-46s %s" % (mark, rel, note))
    print("-" * 84)
    if problems:
        print("❌ 结构性问题:")
        for rel, msg in problems:
            print("   %s → %s" % (rel, msg))
    if over:
        print("❌ 体积超限：%.2f MB > %.0f MB" % (total_mb, args.cap_mb))
    if not problems and not over:
        print("✅ 无结构性问题，体积在预算内。")
    return 1 if (problems or over) else 0


if __name__ == "__main__":
    sys.exit(main())
