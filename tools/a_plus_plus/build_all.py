#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
方案 A++ · 全引擎一键构建 + 契约自检编排器
==========================================

一条命令把四台引擎从「下基座 → 训练 → 导出 INT8 → 入包」串起来，并在最后**用与
App 完全相同的契约**校验每一件产物，杜绝"脚本说成功、装机却加载失败"：

    ① YOLO26   → assets/models/yolo26*_stzb.param/.bin   （param 首行须 7767517）
    ② PP-OCRv5 → assets/ch_PP-OCRv5_*.{param,bin}+keys    （param 首行须 7767517）
    ③ bge+索引 → assets/models/bge_zh_int8.onnx(+vocab)   （头须 ONNX）
                assets/models/slg_knowledge_vector_hnsw.bin（须 V2 且 dim==512）
    ④ 意图微脑 → assets/models/intent_slot_zh.onnx(+vocab)（头须 ONNX）

约定：本环境**禁止联网下载/训练**，所以这里默认只做「校验 + 打印该跑什么」。
在你自己的 GPU 机器上：
    python tools/a_plus_plus/build_all.py --run-all          # 真的跑四台
    python tools/a_plus_plus/build_all.py --only ocr,bge     # 只跑其中几台
    python tools/a_plus_plus/build_all.py                    # 不 --run-all：仅校验现有资产
或逐台单跑（见各脚本报头）。缺依赖/缺数据就如实失败，绝不伪造产物。

退出码：0=校验全通过；1=有产物不合格；2=参数/依赖错误。
"""

import argparse
import os
import struct
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.abspath(os.path.dirname(__file__))
ASSETS = os.path.join(REPO, "client", "app", "src", "main", "assets")
MODELS = os.path.join(ASSETS, "models")

NCNN_PARAM_MAGIC = "7767517"
ONNX_MAGIC = b"ONNX"
RAG_MAGIC_V2 = b"SLG_HNSW_RAG_V2\x00"


def log(m):
    print(m, flush=True)


def run(script, extra):
    path = os.path.join(HERE, script)
    cmd = [sys.executable, path] + extra
    log("\n$ %s" % " ".join(cmd))
    return subprocess.run(cmd).returncode


# ------------------------------------------------------------------ 校验件
def check_ncnn_pair(base):
    p, b = base + ".param", base + ".bin"
    if not (os.path.isfile(p) and os.path.isfile(b)):
        return "缺失(param/bin)"
    with open(p, "r", encoding="utf-8", errors="replace") as fh:
        if fh.readline().strip() != NCNN_PARAM_MAGIC:
            return "param 魔数错"
    if os.path.getsize(b) < 64 * 1024:
        return "bin 过小"
    return None  # ok


def check_onnx(path):
    if not os.path.isfile(path):
        return "缺失"
    with open(path, "rb") as fh:
        if fh.read(4) != ONNX_MAGIC:
            return "非 ONNX 头"
    if os.path.getsize(path) < 1024 * 1024:
        return "体积过小(疑空壳)"
    return None


def check_rag_v2_512(path):
    if not os.path.isfile(path):
        return "缺失"
    raw_head = open(path, "rb").read(28)
    if len(raw_head) < 28:
        return "头不足 28 字节"
    magic, ver, cnt, dim = struct.unpack("<16sIII", raw_head)
    if magic != RAG_MAGIC_V2:
        return "非 V2（magic=%r，多半是 64 维哈希占位）" % magic.rstrip(b"\x00")
    if dim != 512:
        return "dim=%d（期望 512 维 bge 向量）" % dim
    return None


def validate():
    rows = []

    def add(name, err, path):
        rows.append((name, "❌ " + err if err else "✅ ok", os.path.relpath(path, REPO)))

    # ② OCR：接受 v3 或 v5 任一齐备（版本容错），报出实际命中的版本与其路径
    ocr_hit, ocr_base = None, os.path.join(ASSETS, "ch_PP-OCRv5_det_infer.param")
    for tag, d, r in (("v5", "ch_PP-OCRv5_det_infer", "ch_PP-OCRv5_rec_infer"),
                      ("v3", "ch_PP-OCRv3_det_infer", "ch_PP-OCRv3_rec_infer")):
        if check_ncnn_pair(os.path.join(ASSETS, d)) is None and \
           check_ncnn_pair(os.path.join(ASSETS, r)) is None:
            ocr_hit, ocr_base = tag, os.path.join(ASSETS, d + ".param")
            break
    add("OCR det/rec", "无任一完整 ncnn 三件套" if not ocr_hit else None, ocr_base)
    if ocr_hit:
        rows[-1] = ("OCR det/rec", "✅ ok（命中 %s）" % ocr_hit, ocr_base)

    # ① YOLO：任一 yolo*_stzb
    yolo_ok = False
    yolo_path = ""
    for nm in sorted(os.listdir(MODELS)) if os.path.isdir(MODELS) else []:
        if nm.startswith("yolo") and nm.endswith("_stzb.param"):
            base = os.path.join(MODELS, nm[: -len(".param")])
            if check_ncnn_pair(base) is None:
                yolo_ok, yolo_path = True, base + ".param"
                break
    add("YOLO26 ncnn", None if yolo_ok else "缺失（跑 train_yolo26.py）",
        yolo_path or os.path.join(MODELS, "yolo26s_stzb.param"))

    # ③ bge + 索引
    add("bge INT8 onnx", check_onnx(os.path.join(MODELS, "bge_zh_int8.onnx")),
        os.path.join(MODELS, "bge_zh_int8.onnx"))
    add("RAG V2(512) 索引", check_rag_v2_512(os.path.join(MODELS, "slg_knowledge_vector_hnsw.bin")),
        os.path.join(MODELS, "slg_knowledge_vector_hnsw.bin"))

    # ④ 意图微脑
    add("intent_slot onnx", check_onnx(os.path.join(MODELS, "intent_slot_zh.onnx")),
        os.path.join(MODELS, "intent_slot_zh.onnx"))

    return rows


def total_size():
    tot = 0
    for d in (ASSETS, MODELS):
        if os.path.isdir(d):
            for f in os.listdir(d):
                p = os.path.join(d, f)
                if os.path.isfile(p):
                    tot += os.path.getsize(p)
    return tot


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--run-all", action="store_true", help="真跑四台（在你的 GPU 机上）")
    ap.add_argument("--only", default="", help="逗号分隔：yolo,ocr,bge,slm")
    ap.add_argument("--data", default=None, help="YOLO 数据集 data.yaml")
    ap.add_argument("--corpus", default=None, help="RAG 语料 jsonl")
    ap.add_argument("--validate-only", action="store_true", help="只校验，不跑")
    args = ap.parse_args()

    if args.run_all and not args.validate_only:
        sel = {s.strip() for s in args.only.split(",") if s.strip()} or {"yolo", "ocr", "bge", "slm"}
        rc = 0
        if "yolo" in sel:
            extra = ["--data", args.data] if args.data else []
            rc |= run("train_yolo26.py", extra)
        if "ocr" in sel:
            rc |= run("export_ppocrv5.py", ["--download"])
        if "bge" in sel:
            extra = ["--build-index"] if args.corpus else []
            if args.corpus:
                extra += ["--corpus", args.corpus]
            rc |= run("export_bge_small.py", extra)
        if "slm" in sel:
            rc |= run("train_intent_slot_rbt3.py", [])
        if rc != 0:
            log("\n⚠ 至少一台引擎脚本未 0 退出（缺依赖/缺数据很正常，按各自报头补）。仍继续校验现有资产：")

    log("\n================ A++ 资产契约自检 ================")
    rows = validate()
    width = max(len(r[0]) for r in rows)
    bad = 0
    for name, status, path in rows:
        log("  %-*s  %-28s  %s" % (width, name, status, path))
        if status.startswith("❌"):
            bad += 1
    log("  -------------------------------------------------")
    log("  入包资产总量：%.1f MB" % (total_size() / 1048576))
    if bad == 0:
        log("  ✅ 全部齐备且符合 App 契约。推 CI 即可打进 APK。")
        return 0
    log("  ⚠ %d 项缺失/不合格：这些不影响 App 构建（缺啥自动降级），但意味着该引擎尚是真权重缺席状态。"
        % bad)
    log("     在 GPU 机跑 `--run-all`（或逐台脚本）产出后，此表会自动转绿。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
