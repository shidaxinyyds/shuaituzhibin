#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bge-small-zh-v1.5 → ONNX → INT8 →（可选）建 512 维 RAG 索引（方案 A++ 检索引擎）
================================================================================

为什么保持 bge-small-zh-v1.5（不追 BGE-M3）：
  * 端侧 + 中文 + 离线 + 体积预算 这组硬约束下，bge-small-zh-v1.5（512维、~24M 参数、
    INT8 后 ~25-30MB）仍是 C-MTEB 上的最佳轻量解；BGE-M3(568M) 太重、推理慢，会顶爆预算。

它补齐了 p1/export_bge_int8.py 的缺口——**自动下基座 + 导出 + INT8 量化**：
  1. `huggingface_hub.snapshot_download("BAAI/bge-small-zh-v1.5")`（国内自动走
     `HF_ENDPOINT=https://hf-mirror.com` 镜像）；
  2. 导出 ONNX（优先 optimum，退回 transformers torch.onnx）；
  3. onnxruntime 动态 INT8 量化（权重 int8）；
  4. 落位 `assets/models/bge_zh_int8.onnx` + `bge_zh_vocab.txt`（头魔数=ONNX，体积守预算）；
  5. `--corpus` 给了就链式调用 `tools/p1/build_rag_index.py` 产出 **512 维 V2** HNSW 索引，
     替换现在那个 21KB 的哈希占位。

诚实边界：没有 bge 权重时，App 里 BgeEmbedder 会加载失败，SlgRagEngine 如实回落到 64 维
哈希向量（当前出厂即此状态）。本脚本产出的东西不会自动伪造——失败就失败。

用法
----
    # 只产出 bge INT8 onnx + 词表：
    python tools/a_plus_plus/export_bge_small.py

    # 顺带用语料建真 512 维索引（替换假索引）：
    python tools/a_plus_plus/export_bge_small.py \
        --corpus tools/p1/corpus_stzb.jsonl --build-index

退出码：0=成功；1=导出/校验失败；2=缺依赖。
"""

import argparse
import os
import shutil
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS_MODELS = os.path.join(REPO, "client", "app", "src", "main", "assets", "models")
BUILD_INDEX = os.path.join(REPO, "tools", "p1", "build_rag_index.py")
HF_ID = "BAAI/bge-small-zh-v1.5"
ONNX_MAGIC = b"ONNX"
MAX_MB = 35


def log(m):
    print(m, flush=True)


def die(code, m):
    log("❌ " + m)
    sys.exit(code)


def _is_valid_onnx(path):
    """真 ONNX 是 protobuf（首字节 0x08=ir_version），不是 ASCII 'ONNX'。
    用 onnx 解析做权威校验；缺 onnx 时退回 protobuf 头启发式。"""
    try:
        import onnx
        m = onnx.load(path)
        return bool(m.graph) and len(m.graph.node) > 0
    except ImportError:
        with open(path, "rb") as fh:
            return fh.read(1) == b"\x08"
    except Exception:
        return False


def ensure_mirror():
    if not os.environ.get("HF_ENDPOINT"):
        os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
        log("🌗 未设 HF_ENDPOINT，默认走镜像 %s（国内更快；海外的 export HF_ENDPOINT=https://huggingface.co）"
            % os.environ["HF_ENDPOINT"])


def download_base(work):
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        die(2, "未安装 huggingface_hub：pip install huggingface_hub")
    ensure_mirror()
    log("⬇ snapshot_download(%s) ..." % HF_ID)
    path = snapshot_download(repo_id=HF_ID, local_dir=work, local_dir_use_symlinks=False)
    return path


def export_onnx(base_dir, out_fp32):
    # 先试 optimum（最省事），失败退回 torch.onnx
    try:
        from optimum.commands import optimum_cli  # noqa: F401
        log("▶ 用 optimum 导出 ONNX ...")
        cmd = [sys.executable, "-m", "optimum.exporters.onnx",
               "--model", base_dir, "--task", "feature-extraction", "--output", out_fp32]
        r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
        if r.returncode == 0 and os.path.isfile(out_fp32):
            return out_fp32
        log("   optimum 未成功，退回 transformers+torch 导出：%s" % (r.stderr or "")[-300:])
    except ImportError:
        log("▶ 未装 optimum，用 torch.onnx 导出 ...")

    try:
        import torch
        import torch.nn as nn
        from transformers import AutoModel
    except ImportError:
        die(2, "需要 optimum 或 (torch + transformers) 来导出 ONNX。")
    model = AutoModel.from_pretrained(base_dir)
    model.eval()
    # transformers>=4.5x/5.x 给 BertModel.forward 套了输出捕获装饰器，
    # 直接把它当顶层模块 torch.onnx.export 会报 "multiple values for argument 'use_cache'"。
    # 用一层干净 wrapper（内部一律用 kwargs 调 bert）即可绕开，跨版本稳定。
    class _BertEncoderWrapper(nn.Module):
        def __init__(self, bert):
            super().__init__()
            self.bert = bert

        def forward(self, input_ids, attention_mask, token_type_ids):
            out = self.bert(input_ids=input_ids, attention_mask=attention_mask,
                            token_type_ids=token_type_ids)
            return out.last_hidden_state

    wrapper = _BertEncoderWrapper(model)
    wrapper.eval()
    if os.path.isdir(out_fp32):
        shutil.rmtree(out_fp32)
    os.makedirs(out_fp32, exist_ok=True)
    dummy_ids = torch.ones(1, 8, dtype=torch.long)
    dummy_am = torch.ones(1, 8, dtype=torch.long)
    dummy_tt = torch.zeros(1, 8, dtype=torch.long)
    # 动态轴，配合端侧变长输入
    torch.onnx.export(
        wrapper, (dummy_ids, dummy_am, dummy_tt), os.path.join(out_fp32, "model.onnx"),
        opset_version=14,
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["last_hidden_state"],
        dynamic_axes={"input_ids": {0: "batch", 1: "seq"},
                      "attention_mask": {0: "batch", 1: "seq"},
                      "token_type_ids": {0: "batch", 1: "seq"},
                      "last_hidden_state": {0: "batch", 1: "seq"}},
        do_constant_folding=True,
    )
    return os.path.join(out_fp32, "model.onnx")


def quantize_int8(fp32, out_int8):
    try:
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        die(2, "需要 onnxruntime 做 INT8 量化：pip install onnxruntime")
    quantize_dynamic(fp32, out_int8, weight_type=QuantType.QInt8)
    return out_int8


def place(onnx_int8, base_dir):
    os.makedirs(ASSETS_MODELS, exist_ok=True)
    if not _is_valid_onnx(onnx_int8):
        die(1, "量化后的 %s 不是可解析的 ONNX 模型，导出异常。" % onnx_int8)
    size = os.path.getsize(onnx_int8)
    if size > MAX_MB * 1048576:
        die(1, "INT8 ONNX %.1fMB 超预算 %dMB，请换更小档位。" % (size / 1048576, MAX_MB))
    dst_model = os.path.join(ASSETS_MODELS, "bge_zh_int8.onnx")
    shutil.copy2(onnx_int8, dst_model)
    vocab_src = os.path.join(base_dir, "vocab.txt")
    if not os.path.isfile(vocab_src):
        die(1, "基座目录缺 vocab.txt（词表），端侧无法拼串。")
    dst_vocab = os.path.join(ASSETS_MODELS, "bge_zh_vocab.txt")
    shutil.copy2(vocab_src, dst_vocab)
    log("✅ 已入包：bge_zh_int8.onnx (%.1fMB) + bge_zh_vocab.txt" % (size / 1048576))
    return dst_model, dst_vocab


def build_index(corpus, model, vocab):
    if not os.path.isfile(corpus):
        die(2, "语料不存在：%s（--build-index 需要 --corpus JSONL）" % corpus)
    cmd = [sys.executable, BUILD_INDEX, "--corpus", corpus,
           "--bge", model, "--vocab", vocab, "--mode", "bge",
           "--out", os.path.join(ASSETS_MODELS, "slg_knowledge_vector_hnsw.bin")]
    log("$ %s" % " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    sys.stdout.write(r.stdout or "")
    if r.returncode != 0:
        die(1, "build_rag_index.py 失败：%s" % (r.stderr or "")[-600:])


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=os.path.join(REPO, ".a_plus_plus", "bge"))
    ap.add_argument("--corpus", default=None, help="率土语料 JSONL（用于建真 512 维索引）")
    ap.add_argument("--build-index", action="store_true", help="导出后链式建 V2 HNSW 索引")
    ap.add_argument("--skip-download", action="store_true", help="已有基座目录时跳过下载")
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    base = args.work
    if not args.skip_download:
        base = download_base(args.work)
    fp32_dir = os.path.join(args.work, "onnx_fp32")
    fp32 = export_onnx(base, fp32_dir)
    int8 = os.path.join(args.work, "bge_zh_int8.onnx")
    quantize_int8(fp32, int8)
    model, vocab = place(int8, base)

    if args.build_index:
        if not args.corpus:
            die(2, "--build-index 需要 --corpus。")
        build_index(args.corpus, model, vocab)
        log("✅ 512 维 V2 RAG 索引已替换旧占位。装机后 SlgRagEngine 会自动走真向量检索。")
    else:
        log("下一步（建索引）：python tools/a_plus_plus/export_bge_small.py --skip-download "
            "--corpus <corpus.jsonl> --build-index")
    return 0


if __name__ == "__main__":
    sys.exit(main())
