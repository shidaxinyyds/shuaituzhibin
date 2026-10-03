#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PP-OCRv5 mobile → ncnn（+可选 INT8 ONNX）落地脚本（方案 A++ OCR 引擎）
====================================================================

为什么升级到 PP-OCRv5：
  * PaddleOCR 3.0（2025）的 PP-OCRv5 mobile 档识别精度显著超 v4/v3，且官方称
    "轻量专用 OCR 能打平/超过大规模 VLM"，对**率土生僻字/书法体**这类难例更稳。
  * 你整个 App 的所有文字判断（体力/士气/坐标/倒计时/按键）都建立在 OCR 之上，
    升级 OCR 是全项目性价比最高、风险最低的一步。

工程侧现状（务必读懂）：
  * native OCR（ocr_lite：DbNet + CrnnNet + AngleNet）按**基名**从 assets 根目录读
    `<base>.param` + `<base>.bin`；App 里 OcrEngine 现在会**版本容错**地优先选
    v5 → v4 → v3。本脚本就是把 v5 的 ncnn 三件套 + 词典落到这些约定名字上。
  * 出厂仓库里现在只有 PP-OCRv3（真实可用）。**在你跑本脚本产出 v5 之前，App 仍用 v3，
    行为不变**；v5 落位后 App 自动优先用它。

本脚本只做三件事（失败即失败，绝不伪造）：
  1. **取 v5 mobile ONNX**：优先用 `--src`（你已下好的目录）；否则从可配置的 RapidOCR /
    PaddleOCR 源用 urllib 下载（在你的网络环境跑；国内建议镜像）。校验 ONNX 头魔数。
  2. **ONNX → ncnn**：复用 `tools/p1/onnx2ncnn.py`（内部走 onnx2ncnn/pnnx，产 param+bin 并自检）。
  3. **落位 + 词典**：把 det/rec/cls 的 param/bin 以约定基名写进 `assets/` 根，并放对应词典
    `ppocr_keys_v5.txt`；校验 param 首行魔数与 bin 体积。

用法
----
    # A. 你已把 v5 onnx 放好（最稳）：
    python tools/a_plus_plus/export_ppocrv5.py --src .models/ocr_v5
    #   .models/ocr_v5/ 下需有：PP-OCRv5_mobile_det.onnx / PP-OCRv5_mobile_rec.onnx /
    #                            ch_ppocr_mobile_v2.0_cls.onnx / ppocr_keys_v5.txt

    # B. 让脚本联网下载（在你机器上跑，不在本审计环境）：
    python tools/a_plus_plus/export_ppocrv5.py --download

    # 只想要一份 INT8 ONNX（若将来把 OCR 迁到 ONNX Runtime 路径）：
    python tools/a_plus_plus/export_ppocrv5.py --src .models/ocr_v5 --int8-onnx

退出码：0=成功；1=转换/校验失败；2=缺源/依赖。
"""

import argparse
import os
import shutil
import struct
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(REPO, "client", "app", "src", "main", "assets")
ONNX2NCNN = os.path.join(REPO, "tools", "p1", "onnx2ncnn.py")

# 与 App 侧 OcrEngine 版本容错约定**严格一致**的落位基名：
DEST_DET = "ch_PP-OCRv5_det_infer"      # -> ch_PP-OCRv5_det_infer.param/.bin
DEST_REC = "ch_PP-OCRv5_rec_infer"      # -> ch_PP-OCRv5_rec_infer.param/.bin
DEST_CLS = "ch_ppocr_mobile_v2.0_cls_infer"  # v5 仍沿用同一角度分类模型
DEST_KEYS = "ppocr_keys_v5.txt"

# 源目录里可能出现的文件名（不同发布渠道命名不一，全部兼容）
DET_CAND = ["PP-OCRv5_mobile_det.onnx", "ch_PP-OCRv5_det_infer.onnx", "det.onnx", "PP-OCRv5_det.onnx"]
REC_CAND = ["PP-OCRv5_mobile_rec.onnx", "ch_PP-OCRv5_rec_infer.onnx", "rec.onnx", "PP-OCRv5_rec.onnx"]
CLS_CAND = ["ch_ppocr_mobile_v2.0_cls.onnx", "ch_ppocr_mobile_v2.0_cls_infer.onnx", "cls.onnx",
            "PP-OCRv5_mobile_cls.onnx"]
KEYS_CAND = ["ppocr_keys_v5.txt", "ppocr_keys_v1.txt", "dict.txt", "keys.txt"]

ONNX_MAGIC = b"ONNX"

# 下载源（RapidOCR / PaddleOCR 常见发布地址；命名/版本可能漂移，失败请用 --src 或 --url-override）
DL_DEFAULT = {
    "det": "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx",
    "rec": "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile.onnx",
    "cls": "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/onnx/PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx",
    "keys": "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master/ppocr_keys_v5.txt",
}


def log(m):
    print(m, flush=True)


def die(code, m):
    log("❌ " + m)
    sys.exit(code)


def find_first(root, cands):
    for name in cands:
        for dirpath, _dn, files in os.walk(root):
            if name in files:
                return os.path.join(dirpath, name)
    return None


def check_onnx(path):
    with open(path, "rb") as fh:
        return fh.read(4) == ONNX_MAGIC


def download(url, dst):
    import ssl
    import urllib.request
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    log("   ⬇  %s" % url)
    ctx = ssl.create_default_context()
    req = urllib.request.Request(url, headers={"User-Agent": "stzb-a++/1.0"})
    with urllib.request.urlopen(req, context=ctx, timeout=120) as r, open(dst, "wb") as fh:
        shutil.copyfileobj(r, fh)
    return dst


def run_onnx2ncnn(onnx, out_base):
    cmd = [sys.executable, ONNX2NCNN, "--input", onnx, "--out", out_base]
    log("   $ %s" % " ".join(cmd))
    proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    sys.stdout.write(proc.stdout or "")
    if proc.returncode != 0:
        log("   ⚠ onnx2ncnn rc=%d：%s" % (proc.returncode, (proc.stderr or "")[-500:]))
    return proc.returncode == 0


def verify_param_bin(base):
    p, b = base + ".param", base + ".bin"
    if not (os.path.isfile(p) and os.path.isfile(b)):
        return "缺 param 或 bin"
    with open(p, "r", encoding="utf-8", errors="replace") as fh:
        if fh.readline().strip() != "7767517":
            return "param 首行非 ncnn 魔数"
    if os.path.getsize(b) < 8 * 1024:
        return "bin 过小(%d)" % os.path.getsize(b)
    return None


def maybe_int8_onnx(onnx, out):
    try:
        import onnxruntime as ort
        from onnxruntime.quantization import quantize_dynamic, QuantType
    except ImportError:
        log("   ⚠ 未装 onnxruntime[quant]，跳过 INT8 ONNX（可选）。pip install onnxruntime")
        return False
    quantize_dynamic(onnx, out, weight_type=QuantType.INT8)
    log("   ✅ INT8 ONNX → %s (%s)" % (out, os.path.getsize(out)))
    return True


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--src", help="已下好的 v5 onnx + keys 目录")
    ap.add_argument("--download", action="store_true", help="联网下载（在你机器上跑；本审计环境禁止）")
    ap.add_argument("--url-override", nargs="*", default=[],
                    help="形如 det=https://... rec=https://... keys=https://...")
    ap.add_argument("--work", default=os.path.join(REPO, ".a_plus_plus", "ocr_v5"))
    ap.add_argument("--ncnn-bin", default=None, help="onnx2ncnn 目录（透传给 p1/onnx2ncnn.py）")
    ap.add_argument("--int8-onnx", action="store_true", help="额外产出 INT8 ONNX（备用 ORT 路径）")
    args = ap.parse_args()

    if not (args.src or args.download):
        die(2, "需要 --src <v5onnx目录> 或 --download 二者其一。")
    if not os.path.isfile(ONNX2NCNN):
        die(2, "找不到复用脚本：%s" % ONNX2NCNN)

    os.makedirs(args.work, exist_ok=True)
    overrides = {}
    for kv in args.url_override:
        if "=" in kv:
            k, v = kv.split("=", 1)
            overrides[k] = v

    # ---- 取得三个 onnx + keys 的本地路径 ----
    src_dir = args.src if args.src else args.work
    det = rec = cls = keys = None
    if args.src:
        det = find_first(args.src, DET_CAND)
        rec = find_first(args.src, REC_CAND)
        cls = find_first(args.src, CLS_CAND)
        keys = find_first(args.src, KEYS_CAND)
    if args.download or det is None or rec is None:
        det = det or download(overrides.get("det", DL_DEFAULT["det"]), os.path.join(args.work, "det.onnx"))
        rec = rec or download(overrides.get("rec", DL_DEFAULT["rec"]), os.path.join(args.work, "rec.onnx"))
        cls = cls or download(overrides.get("cls", DL_DEFAULT["cls"]), os.path.join(args.work, "cls.onnx"))
        keys = keys or download(overrides.get("keys", DL_DEFAULT["keys"]), os.path.join(args.work, "keys.txt"))

    for label, path in (("det", det), ("rec", rec), ("cls", cls)):
        if not path or not os.path.isfile(path):
            die(2, "缺少 %s 的 onnx（--src 命名不匹配或下载失败）。候选名：%s" % (label, {"det": DET_CAND, "rec": REC_CAND, "cls": CLS_CAND}[label]))
        if not check_onnx(path):
            die(1, "%s 不是合法 ONNX（头魔数非 ONNX），八成下成了网页/跳转页：%s" % (label, path))
    if not keys or not os.path.isfile(keys):
        die(2, "缺少词典 keys（ppocr_keys_v5.txt）。没有词典 rec 无法解码。")

    log("源：\n  det=%s\n  rec=%s\n  cls=%s\n  keys=%s" % (det, rec, cls, keys))

    if args.int8_onnx:
        for nm, onnx in (("det", det), ("rec", rec)):
            maybe_int8_onnx(onnx, os.path.join(args.work, "%s_int8.onnx" % nm))

    # ---- ONNX → ncnn，直接落到约定基名 ----
    extra = ["--ncnn-bin", args.ncnn_bin] if args.ncnn_bin else []
    jobs = [(det, os.path.join(ASSETS, DEST_DET)),
            (rec, os.path.join(ASSETS, DEST_REC)),
            (cls, os.path.join(ASSETS, DEST_CLS))]
    failed = []
    for onnx, base in jobs:
        log("▶ %s → %s.{param,bin}" % (os.path.basename(onnx), os.path.basename(base)))
        cmd = [sys.executable, ONNX2NCNN, "--input", onnx, "--out", base] + extra
        proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
        sys.stdout.write(proc.stdout or "")
        err = verify_param_bin(base)
        if proc.returncode != 0 or err:
            failed.append("%s: rc=%d %s %s" % (base, proc.returncode, err or "", (proc.stderr or "")[-300:]))

    # ---- 词典落位 ----
    shutil.copy2(keys, os.path.join(ASSETS, DEST_KEYS))
    log("✅ 词典已落位：%s" % os.path.join(ASSETS, DEST_KEYS))

    if failed:
        log("\n❌ 以下转换未通过自检：\n   " + "\n   ".join(failed))
        log("   App 仍会自动使用仓库里现存的 PP-OCRv3（版本容错），不受影响。")
        return 1

    total = sum(os.path.getsize(b + s) for _o, b in jobs for s in (".param", ".bin")) \
        + os.path.getsize(os.path.join(ASSETS, DEST_KEYS))
    log("\n✅ PP-OCRv5 三件套 + 词典已入包（合计约 %.1f MB）。" % (total / 1048576.0))
    log("   OcrEngine 会自动优先加载 v5；如需回退，删除这些 ch_PP-OCRv5_* 文件即可回落 v3。")
    log("   下一步：git add client/app/src/main/assets/ch_PP-OCRv5_* ppocr_keys_v5.txt → 推 CI。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
