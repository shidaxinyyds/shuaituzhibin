#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bge-small-zh-v1.5 INT8 ONNX → 入包资产
=====================================

对应清单 ③。做三件事，且**只**做这三件事：

1. 在 HF 目录里找出 INT8 那个 ONNX（`onnx/model_qint8_avx512_vnni.onnx`，
   退而求其次 `onnx/model.onnx`）与词表（`vocab.txt` / `tokenizer.json`）；
2. 按 **ONNX 文件头魔数**校验它不是 html/伪文件（ONNX 文件前 8 字节是 b"ONNX"）；
3. 复制到 `assets/models/bge_zh_int8.onnx` + `bge_zh_vocab.txt`，并复检字节数一致。

超出体积预算（默认 35MB）直接失败退出——宁可不下，也不要把 OTA 拖爆。

用法
----
    python tools/p1/export_bge_int8.py --src .models/bge
    python tools/p1/export_bge_int8.py --src .models/bge --max-mb 32

退出码：0 = 落位成功；1 = 校验/预算失败；2 = 找不到源权重。
"""

import argparse
import os
import shutil
import sys

ONNX_MAGIC = b"ONNX"
DEFAULT_MAX_MB = 35

MODEL_CANDIDATES = ["model_qint8_avx512_vnni.onnx", "model_int8.onnx", "model.onnx", "model_qint8.onnx"]
VOCAB_CANDIDATES = ["vocab.txt"]


def human(n):
    return "%.2f MB" % (n / (1024.0 * 1024)) if n >= 1024 * 1024 else "%.1f KB" % (n / 1024.0)


def find_under(root, names, also_rel=None):
    """在 root（含子目录）里按候选名找第一个存在的文件。"""
    for rel in (also_rel or []):
        p = os.path.join(root, rel)
        if os.path.isfile(p):
            return p
    hits = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "__pycache__")]
        for name in names:
            if name in filenames:
                hits.append(os.path.join(dirpath, name))
    if not hits:
        return None
    return sorted(hits, key=os.path.getsize, reverse=True)[0]


def check_onnx_header(path):
    with open(path, "rb") as fh:
        head = fh.read(len(ONNX_MAGIC))
    return head == ONNX_MAGIC


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, help="bge HF 源目录（含 onnx/ 与 vocab.txt）")
    ap.add_argument("--out-dir", default=None, help="默认 client/app/src/main/assets/models")
    ap.add_argument("--model-name", default="bge_zh_int8.onnx")
    ap.add_argument("--vocab-name", default="bge_zh_vocab.txt")
    ap.add_argument("--max-mb", type=float, default=DEFAULT_MAX_MB, help="模型体积上限（MB）")
    args = ap.parse_args()

    repo = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    out_dir = args.out_dir or os.path.join(repo, "client", "app", "src", "main", "assets", "models")
    os.makedirs(out_dir, exist_ok=True)

    if not os.path.isdir(args.src):
        print("❌ 源目录不存在: %s" % args.src, file=sys.stderr)
        return 2

    model = find_under(args.src, MODEL_CANDIDATES, also_rel=["onnx"])
    vocab = find_under(args.src, VOCAB_CANDIDATES)

    if model is None:
        print("❌ 在 %s 里找不到 INT8 ONNX。候选：%s"
              % (args.src, ", ".join(MODEL_CANDIDATES)), file=sys.stderr)
        print("   若只有 fp32 的 model.onnx，请先跑 ORT 动态量化再入包，本脚本不接受 fp32 直入。",
              file=sys.stderr)
        return 2
    if vocab is None:
        print("❌ 在 %s 里找不到词表（%s）" % (args.src, "/".join(VOCAB_CANDIDATES)), file=sys.stderr)
        return 2

    if not check_onnx_header(model):
        print("❌ %s 不是合法 ONNX（头部魔数不是 ONNX），八成下成了网页/跳转页。" % model, file=sys.stderr)
        return 1

    size = os.path.getsize(model)
    if size > args.max_mb * 1024 * 1024:
        print("❌ INT8 ONNX %s（%s）超过预算 %.0fMB。换 model_qint8_avx512_vnni.onnx 或降档。"
              % (model, human(size), args.max_mb), file=sys.stderr)
        return 1

    dst_model = os.path.join(out_dir, args.model_name)
    dst_vocab = os.path.join(out_dir, args.vocab_name)
    shutil.copy2(model, dst_model)
    shutil.copy2(vocab, dst_vocab)

    ok = True
    for src, dst in ((model, dst_model), (vocab, dst_vocab)):
        a, b = os.path.getsize(src), os.path.getsize(dst)
        status = "OK" if a == b else "字节数不一致!"
        if a != b:
            ok = False
        print("[%s] %s (%s) → %s" % (status, os.path.basename(src), human(a),
                                     os.path.relpath(dst, repo)))

    if not ok:
        print("❌ 复制后字节数不一致，已落位文件请手动核对后重跑。", file=sys.stderr)
        return 1

    print("\n下一步：用它建索引 —— python tools/p1/build_rag_index.py "
          "--corpus <corpus.jsonl> --bge %s --vocab %s"
          % (dst_model, dst_vocab))
    return 0


if __name__ == "__main__":
    sys.exit(main())
