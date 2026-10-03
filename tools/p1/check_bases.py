#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
《模型权重下载清单》核验器（默认**完全离线**，只读不写）
=====================================================

职责
----
1. 按清单检查每一件「可下载的通用基座」是否已经落到指定路径、体积是否达标；
2. 把结果打成表格（存在 / 缺失 / 体积 / 预期体积 / 来源），并给出退出码：
     0 = 全部齐备
     1 = 有缺失
     2 = 缺依赖或参数错误
3. `--download` 时才联网拉取（下载方在自己的网络环境跑）；
   `--verify` 时读 `.models/bases.json` 记录，逐项校验 sha256 + 体积，
   防止"文件在但下载不完整/下错了仓库"被当成齐备。

铁律
----
缺件就报缺，**不允许**用空文件 / 随机字节补齐（见 `assets/models/README.md`）。

用法
----
    python tools/p1/check_bases.py                       # 离线核验
    python tools/p1/check_bases.py --root .models        # 换下载根目录
    python tools/p1/check_bases.py --download            # 真下载
    python tools/p1/check_bases.py --verify              # 依 state 校验 sha256
    python tools/p1/check_bases.py --url-override yolov8s.pt=file:///D:/x/yolov8s.pt

下载根目录约定（`--root`，默认 `.models`）：
    <root>/yolov8/yolov8s.pt
    <root>/ocr/ch_PP-OCRv4_det_infer.onnx
    <root>/bge/*            （huggingface 目录整体拷进来）
    <root>/rbt3/*           （同上）
"""

import argparse
import hashlib
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))  # tools/p1 -> tools -> repo

MB = 1024 * 1024
STATE_FILE = os.path.join(".models", "bases.json")


# --------------------------------------------------------------------------
# 清单：id -> 落盘相对路径 / 预期体积 / 来源
# 预期体积只用于对比提示，不参与"是否合格"判定（不同onnx优化档体积会有浮动）。
# --------------------------------------------------------------------------
BASES = [
    {
        "id": "yolov8s.pt",
        "rel": "yolov8/yolov8s.pt",
        "min_bytes": 18 * MB,
        "expect_bytes": 22 * MB,
        "urls": [
            # 实测：github.com/ultralytics/assets/releases 的 tag 早已 404，官方权重现在走官网 CDN
            "https://ultralytics.com/assets/yolov8s.pt",
            "https://github.com/ultralytics/assets/releases/download/v0.4.7/yolov8s.pt",
        ],
        "note": "Ultralytics 官方 CDN（22.5MB，zip 魔数可校验）；备选 yolov8n.pt（约 6MB）",
    },
    {
        "id": "yolov8s.yaml",
        "rel": "yolov8/yolov8s.yaml",
        "min_bytes": 600,
        "expect_bytes": 700,
        "urls": [],
        # 官方自 8.3 起不再随包发布 per-scale yaml（main / v8.0.0 / v8.1.0 / v8.2.0 / v8.3.0 全 404），
        # 改为运行时由 yolov8.yaml + scales 展开。有 .pt 时可用 ultralytics 从权重自举，故设为可选。
        "optional": True,
        "note": "可选：Ultralytics 官方已不再发布该文件，训练时由 .pt 自举。"
                "如仍要本地副本，装 ultralytics 后 `python -c \"from ultralytics import YOLO; "
                "print(YOLO('.models/yolov8/yolov8s.pt').model.yaml)\"` 导出即可",
    },
    {
        "id": "ch_PP-OCRv4_det_infer.onnx",
        "rel": "ocr/ch_PP-OCRv4_det_infer.onnx",
        "min_bytes": 3 * MB,
        "expect_bytes": 5 * MB,
        "urls": [
            "https://huggingface.co/cycloneboy/ch_PP-OCRv4_det_infer/resolve/main/model.onnx",
        ],
        "note": "PP-OCRv4 中文检测；HF 上由社区从 Paddle 权重导出，与官方同结构。官方 bcebos CDN 无直链 onnx",
    },
    {
        "id": "ch_PP-OCRv4_rec_infer.onnx",
        "rel": "ocr/ch_PP-OCRv4_rec_infer.onnx",
        "min_bytes": 8 * MB,
        "expect_bytes": 11 * MB,
        "urls": [
            "https://huggingface.co/cycloneboy/ch_PP-OCRv4_rec_infer/resolve/main/model.onnx",
        ],
        "note": "PP-OCRv4 中文识别",
    },
    {
        "id": "ch_ppocr_mobile_v2.0_cls_infer.onnx",
        "rel": "ocr/ch_ppocr_mobile_v2.0_cls_infer.onnx",
        "min_bytes": 300 * 1024,
        "expect_bytes": 600 * 1024,
        "urls": [
            "https://huggingface.co/Desperado-JT/CH-PP-OCRv4/resolve/main/ch_ppocr_mobile_v2.0_cls_infer.onnx",
        ],
        "note": "方向分类（v3/v4 共用的 mobile v2.0 cls）",
    },
    {
        "id": "ppocrv4_rec_ch_dict.txt",
        "rel": "ocr/ppocrv4_rec_ch_dict.txt",
        "min_bytes": 20 * 1024,
        "expect_bytes": 33 * 1024,
        "urls": [
            "https://huggingface.co/cycloneboy/ch_PP-OCRv4_rec_infer/resolve/main/ch_dict.txt",
        ],
        "note": "PP-OCRv4 中文识别字典（6623 行）；仓库内已有的 ppocr_keys_v1.txt 是 v3 字典，不可直接复用",
    },
    {
        "id": "bge-small-zh-v1.5",
        "rel": "bge/*",
        "min_bytes": 90 * MB,
        "expect_bytes": 130 * MB,
        "urls": [
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model_qint8_avx512_vnni.onnx",
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/onnx/model.onnx",
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/vocab.txt",
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer.json",
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/tokenizer_config.json",
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/config.json",
            "https://huggingface.co/BAAI/bge-small-zh-v1.5/resolve/main/special_tokens_map.json",
        ],
        "note": "整个 HF 目录拷进来；ORT 跑 INT8 那个；vocab.txt 会作为 bge_zh_vocab.txt 入包",
    },
    {
        "id": "rbt3",
        "rel": "rbt3/*",
        "min_bytes": 60 * MB,
        "expect_bytes": 90 * MB,
        "urls": [
            # 注意：hfl/rbt3 只有 pytorch_model.bin，没有 model.safetensors
            "https://huggingface.co/hfl/rbt3/resolve/main/pytorch_model.bin",
            "https://huggingface.co/hfl/rbt3/resolve/main/config.json",
            "https://huggingface.co/hfl/rbt3/resolve/main/vocab.txt",
            "https://huggingface.co/hfl/rbt3/resolve/main/tokenizer.json",
            "https://huggingface.co/hfl/rbt3/resolve/main/tokenizer_config.json",
        ],
        "note": "中文 3 层 RoBERTa 基座（④意图+槽位微调起点）；备选 rbt4",
    },
]


def _varint(buf, i):
    v = s = 0
    while True:
        x = buf[i]
        i += 1
        v |= (x & 0x7F) << s
        if not (x & 0x80):
            break
        s += 7
    return v, i


def sniff(path):
    """按魔数/结构判格式，防止错误页、LFS 指针、中断下载被当成模型。返回 (结论, 说明)。"""
    if not os.path.isfile(path):
        return "无", ""
    head = open(path, "rb").read(256)
    ext = os.path.splitext(path)[1]
    try:
        if ext == ".onnx":
            # protobuf：field1 = ir_version，tag 字节 0x08 在 head[0]，值从 head[1] 解varint
            if head[0] != 0x08:
                return "异常", "ONNX 首字节 0x%02x 不是 field1(ir_version) 的 tag 0x08" % head[0]
            ir, p = _varint(head, 1)      # 返回 (ir_version, 下一字段起始下标)
            nxt = head[p]                # 下一个字段的 tag（producer/graph 等，长度定界）
            if 3 <= ir <= 30 and nxt in (0x0A, 0x12, 0x1A, 0x22, 0x3A):
                return "ONNX", "ir_version=%d 头合法" % ir
            return "异常", "ONNX 头可疑（ir_version=%d, 下一字段tag=0x%02x）" % (ir, nxt)
        if ext == ".safetensors":
            import json as _json
            hlen = int.from_bytes(head[:8], "little")
            if hlen <= 0 or hlen > 8 * MB:
                return "异常", "safetensors 头长度非法: %d" % hlen
            with open(path, "rb") as fh:
                fh.read(8)
                hdr = _json.loads(fh.read(hlen).decode("utf-8"))
            keys = [k for k in hdr if k != "__metadata__"]
            if not keys:
                return "异常", "safetensors 无张量"
            first = hdr[keys[0]]
            return "safetensors", "%d 张，首张 %s %s %s" % (
                len(keys), keys[0], first.get("dtype"), first.get("shape"))
        if ext == ".bin" or path.endswith(".pt"):
            if head[:2] == b"PK":
                return "torch(zip)", "zip 容器"
            if head[:2] == b"\x80\x02":
                return "torch(legacy)", "legacy pickle 魔数"
            return "异常", "既不是 zip 也不是 legacy torch"
        if ext == ".txt":
            n = sum(1 for _ in open(path, encoding="utf-8", errors="replace"))
            return "文本", "%d 行" % n
        if ext == ".json":
            import json as _json
            h = _json.load(open(path, encoding="utf-8"))
            return "JSON", "%d 个键" % len(h) if isinstance(h, dict) else type(h).__name__
    except Exception as exc:                      # noqa: BLE001
        return "异常", "%s: %s" % (type(exc).__name__, exc)
    return "未识别", ""


def human(n):
    if n >= MB:
        return "%.1f MB" % (n / MB)
    if n >= 1024:
        return "%.1f KB" % (n / 1024.0)
    return "%d B" % n


def resolve_path(root, rel):
    """把 rel 里的 `*` 展开成该目录下的所有文件名。"""
    if "*" not in rel:
        return [os.path.join(root, rel)]
    d, pat = rel.split("/", 1)
    base = os.path.join(root, d)
    if not os.path.isdir(base):
        return []
    return [os.path.join(base, f) for f in sorted(os.listdir(base))
            if fnmatch(f, pat)]


def fnmatch(name, pat):
    import fnmatch as _f
    return _f.fnmatch(name, pat)


def sha256_of(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            b = fh.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def load_state(state_path):
    if not state_path or not os.path.isfile(state_path):
        return {}
    try:
        with open(state_path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception as exc:
        print("⚠️  state 文件解析失败（忽略），%s" % exc)
        return {}


def save_state(state_path, state):
    try:
        os.makedirs(os.path.dirname(os.path.abspath(state_path)), exist_ok=True)
        with open(state_path, "w", encoding="utf-8") as fh:
            json.dump(state, fh, ensure_ascii=False, indent=2)
    except Exception as exc:
        print("⚠️  state 写入失败（不影响本次判定），%s" % exc)


def download(url, dest, timeout=300):
    os.makedirs(os.path.dirname(os.path.abspath(dest)), exist_ok=True)
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 stzb-check-bases"})
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp, \
            open(dest + ".part", "wb") as out:
        while True:
            b = resp.read(1 << 20)
            if not b:
                break
            out.write(b)
    if os.path.getsize(dest + ".part") == 0:
        raise ValueError("下载到 0 字节（多半是 404 页面被存下来了）")
    os.replace(dest + ".part", dest)
    return os.path.getsize(dest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(REPO, ".models"), help="下载根目录")
    ap.add_argument("--download", action="store_true", help="缺失项联网下载（默认关闭）")
    ap.add_argument("--verify", action="store_true", help="按 state 文件逐项校验 sha256")
    ap.add_argument("--state", default=STATE_FILE, help="state 文件（记录 url/size/sha256）")
    ap.add_argument("--url-override", action="append", default=[],
                    metavar="KEY=URL", help="覆盖某个 id 的下载地址，可重复")
    ap.add_argument("--json", action="store_true", help="机器可读输出")
    args = ap.parse_args()

    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    overrides = {}
    for item in args.url_override:
        if "=" not in item:
            print("参数错误：--url-override 需要 KEY=URL，收到 %r" % item, file=sys.stderr)
            return 2
        k, v = item.split("=", 1)
        overrides[k] = v

    state = load_state(args.state)
    rows = []
    missing = []

    for spec in BASES:
        bid = spec["id"]
        paths = resolve_path(args.root, spec["rel"])
        exists = [p for p in paths if os.path.isfile(p) and os.path.getsize(p) > 0]
        size = sum(os.path.getsize(p) for p in exists)

        status, detail = "缺失", ""
        sniffs = [(os.path.basename(p), ) + sniff(p) for p in exists]
        if exists:
            if size >= spec["min_bytes"]:
                bad = [s for s in sniffs if s[1] in ("异常", "无")]
                if bad:
                    status = "格式异常"
                    detail = human(size) + " 体积够但内容不是模型：" + \
                        "; ".join("%s -> %s(%s)" % (b[0], b[1], b[2]) for b in bad)
                    missing.append(bid)
                else:
                    status = "齐备"
                    detail = human(size) + " (≥ %s)" % human(spec["min_bytes"])
            else:
                status = "偏小"
                detail = "%s < 最小 %s" % (human(size), human(spec["min_bytes"]))
                missing.append(bid)
        else:
            if spec.get("optional"):
                status = "可选·缺"
                detail = "可选项：%s" % spec["note"]
            else:
                missing.append(bid)
            if args.download:
                url = overrides.get(bid) or (spec["urls"][0] if spec["urls"] else None)
                if not url:
                    detail = "无固化 URL，请用 --url-override 指定"
                else:
                    dest = os.path.join(args.root, spec["rel"])
                    try:
                        got = download(url, dest)
                        state[bid] = {"url": url, "size": got, "sha256": sha256_of(dest)}
                        status, detail = "已下载", human(got)
                        missing.remove(bid)
                    except (urllib.error.URLError, OSError, ValueError) as exc:
                        status, detail = "下载失败", "%s: %s" % (type(exc).__name__, exc)

        sha = ""
        if args.verify and bid in state:
            rec = state[bid]
            p = os.path.join(args.root, spec["rel"])
            if "*" in spec["rel"] or not os.path.isfile(p):
                sha = "（目录型，跳过）"
            else:
                actual = sha256_of(p)
                sha = "sha256 OK" if actual == rec.get("sha256") else "sha256 不符!"

        rows.append({
            "id": bid,
            "rel": spec["rel"],
            "status": status,
            "size": size,
            "size_h": human(size),
            "expect": human(spec["expect_bytes"]),
            "detail": detail,
            "sha": sha,
            "note": spec["note"],
            "fmt": "; ".join("%s=%s" % (b[0], b[1]) for b in sniffs),
            "fmt_detail": "; ".join("%s(%s)" % (b[1], b[2]) for b in sniffs),
            "paths": [os.path.relpath(p, REPO) for p in exists],
        })

    if args.download:
        save_state(args.state, state)

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
    else:
        print("=" * 96)
        print("模型基座核验（root=%s）" % os.path.relpath(args.root, REPO))
        print("=" * 96)
        for r in rows:
            flag = {"齐备": "[OK]", "已下载": "[OK]", "偏小": "[!!]", "下载失败": "[!!]",
                    "格式异常": "[XX]"}.get(r["status"], "[--]")
            print("%s %-34s %-6s 实到 %-9s 预期 %-9s %s" % (
                flag, r["id"], r["status"], r["size_h"], r["expect"], r["sha"]))
            if r["fmt"]:
                print("      格式 %s" % r["fmt"])
            if r["fmt_detail"] and r["status"] != "格式异常":
                print("      细节 %s" % r["fmt_detail"][:220])
            if r["detail"]:
                print("      %s" % r["detail"])
            if r["paths"]:
                for p in r["paths"]:
                    print("      · %s" % p)
            else:
                print("      · 应为路径: %s（相对 --root）" % os.path.join(args.root, r["rel"]))
        print("-" * 96)
        ok = sum(1 for r in rows if r["status"] in ("齐备", "已下载"))
        print("齐备 %d / 共 %d（另有 %d 项可选）" % (
            ok, len(rows), sum(1 for s in BASES if s.get("optional"))))
        if missing:
            print("缺失/异常项: %s" % ", ".join(missing))
            print("补齐方式：python tools/p1/check_bases.py --download "
                  "（离线环境请按 tools/MODELS_DOWNLOAD_MANIFEST.md 手动下载）")
        else:
            print("全部基座已到位，可进入下一步（转换/建库/微调）。")

    if missing:
        return 1
    if args.verify and any("不符" in r["sha"] for r in rows):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
