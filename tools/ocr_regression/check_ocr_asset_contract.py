#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OCR 权重 ↔ 词典 配套契约校验（离线、纯 stdlib，CI 可直接跑）
============================================================

它拦的是哪一个真实的生产事故
----------------------------
`OcrEngine.kt` 的版本容错按「det + rec + keys **三者齐备**」挑选模型集：
v5 → v4 → v3。这个规则只校验**文件在不在**，不校验**对不对得上**。

于是存在一条能一路绿灯进仓库、上线后才炸的路径：

  * 有人跑 `export_ppocrv5.py` 把 v5 的 det/rec 转成 ncnn 入包，
    词典却用了复制过来的 **v3 词典改名成 ppocr_keys_v5.txt**；
  * 名字对得上 ⇒ 版本容错选中 v5 ⇒ native 用 6623 项词典去解码
    一个输出 **18385** 类的 rec 模型；
  * 结果不是崩溃、不是空结果，而是**能跑出字的乱码**——
    体力读数变成「锟斤拷」，坐标解析失败，所有依赖文字的战术静默超时。

这正是本仓库注释里早已写明、却**没有任何闸门在执行**的那个约束
（`OcrEngine.kt`:「rec 模型的输出类别数必须与配套词典严格对应，
半套(v5 权重 + v3 词典)会解码成乱码」）。本脚本把它变成硬校验。

契约（可机检的唯一形式）
------------------------
PP-OCR 的 CRNN 解码表长度恒等于 `词典行数 + 2`
（+1 个行首空格占位，+1 个 CTC blank）。ncnn 的 `.param` 是文本格式，
最后一个 `InnerProduct` 层的 `0=<num_output>` 就是 rec 的输出类别数。
因此：

    num_output(rec)  ==  lines(keys) + 2

不满足即判**半套**，硬失败。

实测依据（本机 .a_plus_plus/ocr_v5/rec.onnx 的输出维度为 18385，
仓库内唯一词典 ppocr_keys_v1.txt 为 6623 行 ⇒ 6623+2=6625 ≠ 18385）。
也就是说：**在当前仓库里，v5 识别物理上无法解码**，
迁移闸门必须保持关闭——本脚本的 `--report` 会把这条结论直接打印出来。

用法
----
    python tools/ocr_regression/check_ocr_asset_contract.py
    python tools/ocr_regression/check_ocr_asset_contract.py --report
    python tools/ocr_regression/check_ocr_asset_contract.py --selftest

退出码：0 = 在包资产全部配套；1 = 存在半套/破损；2 = 环境或路径不对。
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(REPO, "client", "app", "src", "main", "assets")


def show_path(path):
    """跨盘符安全的相对路径显示。

    Windows 上仓库在 D:、`tempfile.mkdtemp()` 在 C:，两者没有共同根，
    `os.path.relpath` 会抛 ValueError。而这一行恰好长在**报错路径**上，于是
    “发现半套资产”会变成“闸门自己崩掉”（实测就崩在反例自测的用例 5）——
    把一个可控的 FAIL 变成一次静默的异常，是最不该犯的那类错。
    """
    try:
        return os.path.relpath(path, REPO)
    except ValueError:
        return path

# 与 OcrEngine.kt 的 SETS 保持严格一致：(版本, det, cls, rec, keys)
SETS = [
    ("v5", "ch_PP-OCRv5_det_infer", "ch_ppocr_mobile_v2.0_cls_infer",
     "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt"),
    ("v4", "ch_PP-OCRv4_det_infer", "ch_ppocr_mobile_v2.0_cls_infer",
     "ch_PP-OCRv4_rec_infer", "ppocr_keys_v4.txt"),
    ("v3", "ch_PP-OCRv3_det_infer", "ch_ppocr_mobile_v2.0_cls_infer",
     "ch_PP-OCRv3_rec_infer", "ppocr_keys_v1.txt"),
]

NCNN_MAGIC = "7767517"


def read_param_last_innerproduct(path):
    """从 ncnn .param 取最后一个 InnerProduct 的 num_output（即 rec 输出类别数）。

    返回 (num_output 或 None, 诊断信息或 None)。
    """
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError as e:
        return None, "无法读取: %s" % e
    if not lines or lines[0].strip().split("#")[0].strip() != NCNN_MAGIC:
        return None, "首行不是 ncnn 魔数 %s（param 已损坏或不是 ncnn 模型）" % NCNN_MAGIC
    n_lines = lines[1].strip() if len(lines) > 1 else ""
    if not re.match(r"^\d+", n_lines):
        return None, "第二行不是层数/输入数（param 头部结构异常）"

    last = None
    for ln in lines[2:]:
        toks = ln.split()
        if len(toks) < 2 or toks[0] != "InnerProduct":
            continue
        mo = None
        for t in toks:
            m = re.match(r"^0=(-?\d+)$", t)
            if m:
                mo = int(m.group(1))
        if mo is not None and mo > 0:
            last = mo
    if last is None:
        return None, "找不到带 num_output(0=…) 的 InnerProduct 层，无法推断 rec 输出类别数"
    return last, None


def count_keys(path):
    """词典行数（每行一个字符）。与 PaddleOCR 口径一致：不做去重、不合并空行以外的内容。"""
    n = 0
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.strip("\n").strip("\r"):
                n += 1
    return n


def check_set(version, rec_base, keys_name, assets=ASSETS):
    """校验一个版本集。返回 (status, 行文本)。status ∈ PASS/SKIP/FAIL。"""
    rec_param = os.path.join(assets, rec_base + ".param")
    keys_path = os.path.join(assets, keys_name)
    if not os.path.isfile(rec_param):
        return "SKIP", "%s: rec 权重未入包（%s）——不参与判定" % (version, rec_base + ".param")
    if not os.path.isfile(keys_path):
        return "SKIP", "%s: 配套词典缺失（%s）——App 侧版本容错不会选中它，回落更低版本，安全" % (version, keys_name)

    num_out, err = read_param_last_innerproduct(rec_param)
    if err:
        return "FAIL", "%s: %s ← %s" % (version, err, show_path(rec_param))
    lines = count_keys(keys_path)
    expect = lines + 2
    if num_out != expect:
        return "FAIL", ("%s: **半套权重**——rec 输出 %d 类，词典 %s 只有 %d 行（应为 %d 行，"
                        "使 %d==%d+2）。上机会把每个字解成乱码，且不会崩溃、不会报错，"
                        "只会让所有依赖文字的战术静默失效。"
                        % (version, num_out, keys_name, lines, num_out - 2, num_out, lines + 2))
    return "PASS", "%s: rec %d 类 == 词典 %s(%d 行) + 2 ✔" % (version, num_out, keys_name, lines)


def run_selftest():
    """反例自测：注入「v5 权重配 v3 词典改名」这类事故，确认闸门真的会拦。"""
    import tempfile
    import shutil

    ok = True
    tmp = tempfile.mkdtemp(prefix="ocr_contract_selftest_")
    try:
        def write_param(base, num_out):
            p = os.path.join(tmp, base + ".param")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write("7767517\n2 2\nInput x 0 1\n")
                fh.write("InnerProduct MatMul_12 1 1 a b 0=%d 1=1 2=100\n" % num_out)

        # 词典 6623 行（照 v3 真实规模造）
        keys = os.path.join(tmp, "ppocr_keys_v5.txt")
        with open(keys, "w", encoding="utf-8") as fh:
            for i in range(6623):
                fh.write("字%d\n" % i)

        # 用例 1：半套（v5 rec 输出 18385 类 + 6623 行词典）⇒ 必须 FAIL
        write_param("ch_PP-OCRv5_rec_infer", 18385)
        st, msg = check_set("v5", "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt", tmp)
        if st != "FAIL":
            print("❌ 自测失败：半套组合未被拦下 → %s" % msg)
            ok = False
        else:
            print("✅ 自测 1 通过：注入「v5 权重 + v3 规模词典改名」被拦下")

        # 用例 2：配套（6625 类 + 6623 行）⇒ 必须 PASS
        write_param("ch_PP-OCRv5_rec_infer", 6625)
        st, msg = check_set("v5", "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt", tmp)
        if st != "PASS":
            print("❌ 自测失败：合法配套被判错 → %s" % msg)
            ok = False
        else:
            print("✅ 自测 2 通过：配套组合判为 PASS")

        # 用例 3：词典被悄悄截短 1 行 ⇒ 必须 FAIL（差 1 类也是半套）
        with open(keys, "r", encoding="utf-8") as fh:
            rows = fh.read().splitlines()
        with open(keys, "w", encoding="utf-8") as fh:
            fh.write("\n".join(rows[:6622]) + "\n")
        st, msg = check_set("v5", "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt", tmp)
        if st != "FAIL":
            print("❌ 自测失败：差 1 行的词典未被拦下 → %s" % msg)
            ok = False
        else:
            print("✅ 自测 3 通过：词典差 1 行同样判为半套")

        # 用例 4：rec 权重根本不存在 ⇒ SKIP（不参与判定，不误伤）
        # v4 从未在本临时目录里落过任何文件，正好用来验证「未入包」这一分支。
        assert not os.path.exists(os.path.join(tmp, "ch_PP-OCRv4_rec_infer.param"))
        st, msg = check_set("v4", "ch_PP-OCRv4_rec_infer", "ppocr_keys_v4.txt", tmp)
        if st != "SKIP":
            print("❌ 自测失败：未入包版本应 SKIP，实得 %s → %s" % (st, msg))
            ok = False
        else:
            print("✅ 自测 4 通过：未入包版本不产生命令误报")

        # 用例 5：param 魔数破损 ⇒ FAIL（不能因为读不懂就放绿）
        write_param("ch_PP-OCRv5_rec_infer", 6625)
        with open(os.path.join(tmp, "ch_PP-OCRv5_rec_infer.param"), "w", encoding="utf-8") as fh:
            fh.write("<html>404 Not Found</html>\n")
        st, msg = check_set("v5", "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt", tmp)
        if st != "FAIL":
            print("❌ 自测失败：损坏 param 未被拦下 → %s" % msg)
            ok = False
        else:
            print("✅ 自测 5 通过：损坏 param 判 FAIL（读不懂绝不放绿）")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 72)
    print("自测结论: %s" % ("全部通过" if ok else "存在失败项"))
    return 0 if ok else 1


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="跑反例自测（注入半套/破损，确认闸门生效）")
    ap.add_argument("--report", action="store_true", help="额外打印迁移现状结论（P2 跑分阻塞说明）")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest()

    if not os.path.isdir(ASSETS):
        print("找不到 assets 目录: %s" % ASSETS, file=sys.stderr)
        return 2

    print("=" * 76)
    print("OCR 权重 ↔ 词典 配套契约校验")
    print("=" * 76)
    print("assets: %s" % ASSETS)
    print("-" * 76)

    failures = []
    for version, _det, _cls, rec_base, keys_name in SETS:
        st, msg = check_set(version, rec_base, keys_name)
        print("  [%s] %s" % (st, msg))
        if st == "FAIL":
            failures.append(msg)

    print("-" * 76)
    if failures:
        print("❌ 发现 %d 个半套组合——这些版本一旦上机会把文字全解成乱码。" % len(failures))
        print("   处置：删除该版本的 ch_PP-OCRvN_*.{param,bin} 或补齐同版本词典。")
        return 1
    print("✅ 在包 OCR 版本的 rec 输出类别数与词典行数全部配套。")

    if args.report:
        print()
        print("=" * 76)
        print("P2 OCR 迁移闸门 · 现状结论（实测，非推断）")
        print("=" * 76)
        v3 = check_set("v3", "ch_PP-OCRv3_rec_infer", "ppocr_keys_v1.txt")
        v5_rec = os.path.join(ASSETS, "ch_PP-OCRv5_rec_infer.param")
        print("  · 在包可解码方案: %s" % v3[1])
        if os.path.isfile(v5_rec):
            print("  · v5 rec 权重已入包 → 迁移闸门需按跑分结果决定（见 decision_gate.py）。")
        else:
            print("  · v5 rec 权重未入包（ch_PP-OCRv5_rec_infer.param 不存在）。")
        print("  · 本机实测: .a_plus_plus/ocr_v5/rec.onnx 输出 18385 类，"
              "仓库词典 ppocr_keys_v1.txt 为 6623 行 ⇒ 6623+2=6625 ≠ 18385。")
        print("  · 结论: **缺 ppocr_keys_v5.txt（需 18383 行）**，v5 在当前环境无法解码；")
        print("    且离线网络受限，词典与 ncnn 转换（onnx2ncnn/pnnx 需本机可执行）均不可得。")
        print("    按「赢才迁」的既定纪律，**迁移闸门保持关闭，出厂仍为 PP-OCRv3**。")
        print("    解锁步骤见 tools/ocr_regression/README.md 与本目录 decision_gate.py。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
