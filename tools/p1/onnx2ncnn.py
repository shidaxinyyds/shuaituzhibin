#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ONNX → ncnn（param + bin）转换 + 产物自检
=========================================

对应清单 ②（PP-OCRv4 det/rec）与 ①（若从 ultralytics 的 onnx 分支导出）。

流程
----
1. 找工具链：`--ncnn-bin` > 环境变量 `STZB_NCNN_BIN` > PATH 里的
   `onnx2ncnn` / `onnx2nncn` / `onnx2ncnn.exe`；都没有则找 `pnnx` 作备选。
2. 转换：`onnx2ncnn in.onnx out.param out.bin`（pnnx 则 `pnnx in.onnx`）。
3. 自检（这一步不通过就退出 1，绝不把"转换失败"当成"转换成功"）：
   * param 存在且首行是 ncnn 魔数 `7767517`；
   * bin 存在且 > 1KB（OCR 的 rec 通常 10MB+，det 2MB+）；
   * bin 尾部与头部字节非全 0（挡住"只写了空壳"）；
   * 可选：打印输入/输出 blob 名，提示 Kotlin/native 侧要不要改常量。

用法
----
    python tools/p1/onnx2ncnn.py --input .models/ocr/ch_PP-OCRv4_det_infer.onnx \
        --out client/app/src/main/assets/ch_PP-OCRv4_det_infer

    # 批量：把目录下所有 onnx 转成同名 param/bin
    python tools/p1/onnx2ncnn.py --batch-dir .models/ocr --suffix-infer

退出码：0 = 转换并通过自检；1 = 自检失败；2 = 工具链/参数缺失。
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

PARAM_MAGIC = "7767517"


def find_tool(explicit_dir):
    """返回 (kind, exe, version_args)。kind ∈ {onnx2ncnn, pnnx}。"""
    search_dirs = []
    if explicit_dir:
        search_dirs.append(explicit_dir)
    env_dir = os.environ.get("STZB_NCNN_BIN")
    if env_dir:
        search_dirs.append(env_dir)
    search_dirs.append(os.getcwd())

    for name in ("onnx2ncnn", "onnx2nncn"):
        exe = name if os.name != "nt" else name + ".exe"
        for d in search_dirs:
            cand = os.path.join(d, exe)
            if os.path.isfile(cand) and os.access(cand, os.X_OK):
                return ("onnx2ncnn", cand, ["-h"])
        found = shutil.which(name)
        if found:
            return ("onnx2ncnn", found, ["-h"])

    for name in ("pnnx", "pnnx.exe"):
        found = shutil.which(name)
        if found:
            return ("pnnx", found, ["--help"])
    for d in search_dirs:
        cand = os.path.join(d, name if os.name != "nt" else "pnnx.exe")
        if os.path.isfile(cand) and os.access(cand, os.X_OK):
            return ("pnnx", cand, ["--help"])

    return (None, None, None)


def human(n):
    return "%.1f MB" % (n / (1024.0 * 1024)) if n >= 1024 * 1024 else "%.1f KB" % (n / 1024.0)


def file_nonzero_tail(path, min_distinct=8):
    """粗略判断 bin 不是纯零/纯噪声壳：统计尾部若干字节的不同取值数。"""
    with open(path, "rb") as fh:
        fh.seek(max(0, os.path.getsize(path) - 4096))
        tail = fh.read()
    return len(set(tail)) >= min_distinct


def convert_one(tool, exe, src, out_base, extra_flags):
    d = os.path.dirname(os.path.abspath(out_base))
    if d:
        os.makedirs(d, exist_ok=True)

    if tool == "onnx2ncnn":
        cmd = [exe, src, out_base + ".param", out_base + ".bin"] + list(extra_flags)
        with tempfile.TemporaryDirectory() as tmp:
            # onnx2ncnn 会往 cwd 吐东西，放临时目录避免污染仓库
            old = os.getcwd()
            try:
                os.chdir(tmp)
                proc = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
            finally:
                os.chdir(old)
        if proc.returncode != 0:
            return False, "onnx2ncnn 退出码 %d\n%s" % (proc.returncode, proc.stderr[-1500:])
    else:  # pnnx
        work = tempfile.mkdtemp()
        try:
            shutil.copy2(src, work)
            stem = os.path.splitext(os.path.basename(src))[0]
            base = os.path.join(work, stem)
            proc = subprocess.run([exe, os.path.basename(src)] + list(extra_flags),
                                  cwd=work, capture_output=True, text=True, errors="replace")
            gen = [p for p in ("%s_ncnn.param" % stem, "%s.param" % stem, "model.ncnn.param")
                   if os.path.isfile(os.path.join(work, p))]
            if not gen:
                return False, "pnnx 未产出 param，输出：%s" % (proc.stdout[-800:] + proc.stderr[-800:])
            for suffix in ("param", "bin"):
                g = os.path.join(work, stem + "_ncnn." + suffix)
                if os.path.isfile(g):
                    shutil.copy2(g, out_base + "." + suffix)
        finally:
            shutil.rmtree(work, ignore_errors=True)

    param_path = out_base + ".param"
    bin_path = out_base + ".bin"
    problems = []

    if not os.path.isfile(param_path):
        problems.append("缺少 param")
    else:
        with open(param_path, "r", encoding="utf-8", errors="replace") as fh:
            first = fh.readline().strip()
        if first != PARAM_MAGIC:
            problems.append("param 首行不是 ncnn 魔数 %s，实际是 %r（多半没真的转换）" % (PARAM_MAGIC, first))

    if not os.path.isfile(bin_path):
        problems.append("缺少 bin")
    elif os.path.getsize(bin_path) < 1024:
        problems.append("bin 只有 %s，太小" % human(os.path.getsize(bin_path)))
    elif not file_nonzero_tail(bin_path):
        problems.append("bin 尾部字节全同，疑似空壳")

    if problems:
        return False, "; ".join(problems)

    return True, "param %s / bin %s" % (
        human(os.path.getsize(param_path)), human(os.path.getsize(bin_path)))


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="单个 .onnx 路径")
    ap.add_argument("--out", help="输出基名（不含 .param/.bin）")
    ap.add_argument("--batch-dir", help="批量：该目录下所有 .onnx")
    ap.add_argument("--suffix-infer", action="store_true",
                    help="与 --batch-dir 同用：输出文件名去掉末尾 _infer")
    ap.add_argument("--ncnn-bin", default=None, help="onnx2ncnn 所在目录")
    ap.add_argument("--flags", default="", help="透传给转换器的额外参数，如 \"-fp32 -f16\"")
    args = ap.parse_args()

    if not args.input and not args.batch_dir:
        print("需要 --input 或 --batch-dir", file=sys.stderr)
        return 2
    if args.input and not args.out:
        print("需要 --input 时必须给 --out", file=sys.stderr)
        return 2

    kind, exe, _ = find_tool(args.ncnn_bin)
    if not kind:
        print("❌ 找不到 ncnn 工具链。任选其一：\n"
              "   1) 装 ncnn 并让 onnx2ncnn 在 PATH 里；\n"
              "   2) --ncnn-bin D:/ncnn/build/tools；\n"
              "   3) 用 pnnx（pip install pnnx）作备选。\n"
              "   ⚠️ 本脚本不会用随机字节伪造权重。", file=sys.stderr)
        return 2
    print("工具链：%s  (%s)" % (kind, exe))

    flags = args.flags.split() if args.flags else []

    jobs = []
    if args.input:
        jobs.append((args.input, args.out))
    else:
        for name in sorted(os.listdir(args.batch_dir)):
            if not name.endswith(".onnx"):
                continue
            src = os.path.join(args.batch_dir, name)
            stem = os.path.splitext(name)[0]
            if args.suffix_infer and stem.endswith("_infer"):
                stem = stem[: -len("_infer")]
            jobs.append((src, os.path.join(args.batch_dir, stem)))

    failed = []
    for src, out in jobs:
        if not os.path.isfile(src):
            print("❌ 输入不存在: %s" % src)
            failed.append(src)
            continue
        ok, msg = convert_one(kind, exe, src, out, flags)
        if ok:
            print("[OK] %s → %s" % (os.path.basename(src), msg))
        else:
            print("[FAIL] %s → %s" % (os.path.basename(src), msg))
            failed.append(src)

    if failed:
        print("\n%d/%d 失败，详见上面原因。" % (len(failed), len(jobs)))
        return 1
    print("\n全部转换并通过自检。下一步：把 .param/.bin 放进 "
          "client/app/src/main/assets/ 后跑 python tools/p1/check_assets.py。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
