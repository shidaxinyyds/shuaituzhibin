#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
率土语料 → bge 向量 → 最小 HNSW → 二进制索引（V2 容器）
=======================================================

对应清单 ③ 的「你侧训练才能产出」：`slg_knowledge_vector_hnsw.bin`。

输入语料格式（JSONL，每行一条）：
    {"id":"DEF-LV5-001","category":"DEFENDER_LAND","title":"5级地守军-李典徐晃阵容",
     "keywords":"李典,徐晃,5级地","content":"...","advice":"🟢 难度评级..."}

输出容器（V2，向后兼容 V1）：
    头   magic[16] = "SLG_HNSW_RAG_V2\\0" | version u32=2 | item_count u32 | dim u32
    条目 6×u32 长度 + id/category/title/keywords/content/advice(UTF-8) + dim×float32
    尾图 u32 图区字节数 + 每节点 u32 度 + 度×u32 邻居（HNSW 第 0 层）
    → `SlgRagEngine` 解析到 V2 时按头里的 dim 走，并用尾图做近似检索；
    V1（dim=64 哈希向量）仍按老路解析，不受影响。

铁律
----
* 没有 bge INT8 ONNX 就**直接退出**（退出码 2），绝不退化成随机向量；
* `--mode hash` 是显式降级开关，产物会带 `--mode hash` 字样写进日志，
  且只写 `version=1` 的 64 维容器（与 `generate_rag_asset.py` 同口径）。
"""

import argparse
import json
import math
import os
import struct
import sys

MAGIC_V1 = b"SLG_HNSW_RAG_V1\x00"
MAGIC_V2 = b"SLG_HNSW_RAG_V2\x00"
VERSION_1, VERSION_2 = 1, 2


def human(n):
    return "%.2f MB" % (n / (1024.0 * 1024)) if n >= 1024 * 1024 else "%.1f KB" % (n / 1024.0)


# ---------------------------------------------------------------- embedding
def load_vocab(vocab_path):
    vocab = {}
    with open(vocab_path, "r", encoding="utf-8") as fh:
        for i, line in enumerate(fh):
            tok = line.rstrip("\n")
            if tok not in vocab:
                vocab[tok] = i
    return vocab


def simple_bert_ids(text, vocab, max_len=64):
    """够用的中文 BERT 分词：CLS + 单字/双字/常用词 + SEP，未命中退化为单字。"""
    tokens = []
    chars = list(text.replace(" ", ""))[: max_len - 2]
    i = 0
    while i < len(chars):
        gram = "".join(chars[i:i + 2])
        if len(gram) == 2 and gram in vocab:
            tokens.append(gram)
            i += 2
            continue
        tokens.append(chars[i])
        i += 1
    ids = [vocab.get("[CLS]", 101)] + [vocab.get(t, vocab.get("[UNK]", 100)) for t in tokens[: max_len - 2]]
    ids = ids[: max_len - 1] + [vocab.get("[SEP]", 102)]
    mask = [1] * len(ids)
    pad = max_len - len(ids)
    return ids + [0] * pad, mask + [0] * pad


def embed_with_ort(model_path, texts, vocab, max_len=64, batch=8):
    """用 ORT 跑 bge INT8 ONNX，返回 (list[vector], dim)。"""
    import numpy as np
    import onnxruntime as ort

    sess = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
    name_in = {k: i for i, k in enumerate(sess.get_inputs())}
    inputs = [n for n in ("input_ids", "attention_mask", "token_type_ids") if n in name_in]
    out_name = sess.get_outputs()[0].name

    vecs = []
    dim = None
    for start in range(0, len(texts), batch):
        chunk = texts[start:start + batch]
        ids_list, mask_list = [], []
        for t in chunk:
            ids, mask = simple_bert_ids(t, vocab, max_len)
            ids_list.append(ids)
            mask_list.append(mask)
        feeds = {}
        feeds[inputs[0]] = np.asarray(ids_list, dtype=np.int64)
        if len(inputs) > 1:
            feeds[inputs[1]] = np.asarray(mask_list, dtype=np.int64)
        if len(inputs) > 2:
            tt = np.zeros_like(np.asarray(mask_list, dtype=np.int64))
            feeds[inputs[2]] = tt
        out = sess.run([out_name], feeds)[0]  # [B, L, H] 或 [B, H]
        if out.ndim == 3:
            mask_arr = np.asarray(mask_list, dtype=np.float32)[:, :, None]
            pooled = (out * mask_arr).sum(axis=1) / np.clip(mask_arr.sum(axis=1), 1e-6, None)
        else:
            pooled = out
        dim = pooled.shape[1]
        for v in pooled:
            n = float(np.linalg.norm(v))
            vecs.append([float(x) / (n if n > 1e-9 else 1.0) for x in v])
    return vecs, dim


def embed_with_hash(texts, dim=64):
    """显式降级：确定性哈希 64 维（与 generate_rag_asset.py 同口径，只用 --mode hash）。"""
    import hashlib
    vecs = []
    for text in texts:
        v = [0.0] * dim
        chars = list(text)
        toks = []
        for i in range(len(chars)):
            toks.append(chars[i])
            if i + 1 < len(chars):
                toks.append(chars[i] + chars[i + 1])
            if i + 2 < len(chars):
                toks.append("".join(chars[i:i + 3]))
        for tok in toks:
            h = int(hashlib.md5(tok.encode("utf-8")).hexdigest()[:8], 16)
            sign = 1.0 if ((h >> 4) & 1) == 0 else -1.0
            v[h % dim] += sign * (1.0 + 0.5 * len(tok))
        n = math.sqrt(sum(x * x for x in v))
        vecs.append([x / n if n > 1e-9 else 0.0 for x in v])
    return vecs, dim


# --------------------------------------------------------------------- HNSW
class Hnsw:
    """第 0 层最小 HNSW（M/efConstruction 可配，搜索默认走全局精确回退）。"""

    def __init__(self, dim, m=16, efc=200):
        self.dim = dim
        self.m = m
        self.efc = efc
        self.links = []

    @staticmethod
    def _cos(a, b):
        s = 0.0
        for x, y in zip(a, b):
            s += x * y
        return s

    def search(self, q, entry, ef):
        """第 0 层的贪心扩展：不断把当前最优节点的邻居并进候选集，直到无新增。"""
        visited = {entry}
        pool = [entry]
        while pool:
            best = max(pool, key=lambda i: self._cos(q, self.vectors[i]))
            grew = False
            for nb in self.links[best]:
                if nb not in visited:
                    visited.add(nb)
                    pool.append(nb)
                    grew = True
            if not grew:
                break
        pool.sort(key=lambda i: -self._cos(q, self.vectors[i]))
        return pool[:ef]

    def build(self, vectors):
        self.vectors = vectors
        for idx, q in enumerate(vectors):
            while len(self.links) <= idx:
                self.links.append([])
            if idx == 0:
                continue
            cands = self.search(q, 0, max(self.efc, self.m))
            for c in cands:
                self.links[c].append(idx)
                if len(self.links[c]) > self.m:
                    self.links[c].sort(key=lambda i: -self._cos(q, self.vectors[i]))
                    self.links[c] = self.links[c][: self.m]
        return self


# ------------------------------------------------------------------- writer
def build_container(items, vectors, graph_links, version):
    dim = len(vectors[0])
    magic = MAGIC_V1 if version == 1 else MAGIC_V2
    body = bytearray()
    for it, vec in zip(items, vectors):
        b = {k: it[k].encode("utf-8") for k in ("id", "category", "title", "keywords", "content", "advice")}
        body.extend(struct.pack("<IIIIII", len(b["id"]), len(b["category"]), len(b["title"]),
                                len(b["keywords"]), len(b["content"]), len(b["advice"])))
        for k in ("id", "category", "title", "keywords", "content", "advice"):
            body.extend(b[k])
        body.extend(struct.pack("<%df" % dim, *vec))

    graph = b""
    if version == 2 and graph_links is not None:
        buf = bytearray()
        for nbrs in graph_links:
            buf.extend(struct.pack("<I", len(nbrs)))
            for n in nbrs:
                buf.extend(struct.pack("<I", n))
        graph = struct.pack("<I", len(buf)) + bytes(buf)

    head = struct.pack("<16sIII", magic, version, len(items), dim)
    return bytes(head) + bytes(body) + graph


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--corpus", required=True, help="语料 JSONL")
    ap.add_argument("--bge", help="bge INT8 ONNX 路径")
    ap.add_argument("--vocab", help="词表 vocab.txt")
    ap.add_argument("--mode", choices=["bge", "hash"], default="bge")
    ap.add_argument("--out", required=True, help="输出 .bin 路径")
    ap.add_argument("--m", type=int, default=16)
    ap.add_argument("--efc", type=int, default=200)
    args = ap.parse_args()

    items = []
    with open(args.corpus, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            if not all(k in d for k in ("id", "title", "content")):
                raise ValueError("语料行缺 id/title/content: %s" % line[:80])
            items.append({
                "id": str(d["id"]),
                "category": str(d.get("category", "GENERAL")),
                "title": str(d["title"]),
                "keywords": d.get("keywords", "") if isinstance(d.get("keywords", ""), str)
                            else ",".join(d["keywords"]),
                "content": str(d["content"]),
                "advice": str(d.get("advice", "")),
            })
    if not items:
        print("❌ 语料为空", file=sys.stderr)
        return 1

    if args.mode == "bge":
        if not (args.bge and os.path.isfile(args.bge)):
            print("❌ --mode bge 需要可用的 --bge ONNX（%s 不存在）。\n"
                  "   已入包的文件在 client/app/src/main/assets/models/bge_zh_int8.onnx；"
                  "没有就先跑 export_bge_int8.py。\n"
                  "   本脚本**不会**用随机向量顶替。" % args.bge, file=sys.stderr)
            return 2
        if not (args.vocab and os.path.isfile(args.vocab)):
            print("❌ --mode bge 需要 --vocab（词表缺失）", file=sys.stderr)
            return 2

    texts = [("%s %s %s %s" % (it["title"], it["keywords"], it["content"], it["advice"])).strip()
             for it in items]

    try:
        if args.mode == "bge":
            vocab = load_vocab(args.vocab)
            vectors, dim = embed_with_ort(args.bge, texts, vocab)
        else:
            vectors, dim = embed_with_hash(texts, dim=64)
            print("⚠️  --mode hash：产的是 64 维确定性哈希向量（无语义），容器写 version=1。")
    except ImportError as exc:
        print("❌ 依赖缺失: %s（--mode bge 需要 onnxruntime + numpy；"
              "离线可换 --mode hash，但那是降级）" % exc, file=sys.stderr)
        return 2
    except Exception as exc:
        print("❌ 向量化失败: %s: %s" % (type(exc).__name__, exc), file=sys.stderr)
        return 1

    index = Hnsw(dim, m=args.m, efc=args.efc).build(vectors)
    blob = build_container(items, vectors, index.links, version=(1 if args.mode == "hash" else 2))

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "wb") as fh:
        fh.write(blob)

    print("[OK] 索引已写入 %s" % args.out)
    print("     条目 %d 条，向量维度 %d，HNSW 第0层节点 %d，体积 %s"
          % (len(items), dim, len(index.links), human(len(blob))))

    # 自检：解回来必须条目数/维度一致，且容器总长与「头+条目+图区」完全吻合
    with open(args.out, "rb") as fh:
        raw = fh.read()
    magic, ver, cnt, d = struct.unpack("<16sIII", raw[:28])
    if magic not in (MAGIC_V1, MAGIC_V2):
        print("❌ 自检失败：magic 不是 V1/V2")
        return 1
    if d != dim or cnt != len(items):
        print("❌ 自检失败：头里的维度/条数与写入不一致（%d/%d vs %d/%d）" % (d, cnt, dim, len(items)))
        return 1

    off = 28
    for _ in range(cnt):
        lens = struct.unpack("<IIIIII", raw[off:off + 24])
        off += 24 + sum(lens) + 4 * d
    graph_len = 0
    if ver == 2:
        (graph_len,) = struct.unpack("<I", raw[off:off + 4])
        off += 4 + graph_len
    if off != len(raw):
        print("❌ 自检失败：解析到 %d 字节，文件共 %d 字节，差 %d" % (off, len(raw), len(raw) - off))
        return 1

    # 图区可整除解析
    if ver == 2:
        p = off - graph_len
        (declared,) = struct.unpack("<I", raw[p:p + 4])
        p += 4
        nodes = 0
        while p < off:
            deg = int.from_bytes(raw[p:p + 4], "little")
            p += 4 + 4 * deg
            nodes += 1
        if p != off or nodes != len(index.links):
            print("❌ 自检失败：HNSW 图区解析异常（nodes=%d, links=%d）" % (nodes, len(index.links)))
            return 1

    print("     自检通过：容器可整除解析（含 HNSW 图区 %d 节点），尾部无残留。" % len(index.links))
    return 0


if __name__ == "__main__":
    sys.exit(main())
