#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/check_native_ocr_build.py 的自测（反例驱动）
=================================================

一个只会说 OK 的校验器毫无价值。本脚本把工程里的 CMakeLists 复制到临时目录，
**故意注入已知缺陷**，再断言校验器确实报出来。

注入的缺陷都对应真实踩过的坑：
  1. 去掉 ${OpenCV_INCLUDE_DIRS}     -> 会以 'opencv2/core.hpp' file not found 失败
  2. 真实分支混入 OcrStub.cpp        -> 同名 JNI 符号重复定义
  3. 不链接 ncnn                     -> <net.h> 找不到
  4. 不链接 jnigraphics              -> android/bitmap.h 相关符号缺失
  5. 真实分支删掉 main.cpp（JNI 入口）-> Java 侧 external fun 找不到实现

用法：python tools/selftest_native_ocr_check.py
退出码：0 = 全部符合预期；1 = 有不符合项。
"""

import importlib.util
import io
import os
import re
import shutil
import sys
import tempfile
import contextlib

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
TARGET = os.path.join(HERE, "check_native_ocr_build.py")
SRC_MAIN = os.path.join(REPO, "client", "app", "src", "main")


def load_checker():
    spec = importlib.util.spec_from_file_location("native_chk", TARGET)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def make_tree(tmp):
    """只复制校验器需要的部分：cpp/ 与 java/。"""
    root = os.path.join(tmp, "main")
    shutil.copytree(os.path.join(SRC_MAIN, "cpp"), os.path.join(root, "cpp"))
    shutil.copytree(os.path.join(SRC_MAIN, "java"), os.path.join(root, "java"))
    return root


def run_checker(mod, root):
    """直接调用 main()，捕获 stdout（不启子进程，避免管道相关限制）。"""
    buf = io.StringIO()
    old_argv = sys.argv
    sys.argv = ["check_native_ocr_build.py", "--root", root]
    try:
        with contextlib.redirect_stdout(buf):
            rc = mod.main()
    finally:
        sys.argv = old_argv
    return rc, buf.getvalue()


def patch_cmake(root, mutator):
    p = os.path.join(root, "cpp", "CMakeLists.txt")
    with open(p, "r", encoding="utf-8") as f:
        text = f.read()
    new = mutator(text)
    assert new != text, "注入的改动没有生效，自测本身有问题"
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(new)


CASES = [
    (
        "去掉 ${OpenCV_INCLUDE_DIRS}",
        lambda t: t.replace("        ${OpenCV_INCLUDE_DIRS}\n", ""),
        "OpenCV_INCLUDE_DIRS",
    ),
    (
        "真实分支混入 OcrStub.cpp",
        lambda t: t.replace("        ${OCR_SOURCES}\n        SecurityBridge.cpp\n",
                            "        ${OCR_SOURCES}\n        SecurityBridge.cpp\n        OcrStub.cpp\n", 1),
        "OcrStub.cpp",
    ),
    (
        "真实分支不链接 ncnn",
        lambda t: t.replace("        ncnn\n", "", 1),
        "ncnn",
    ),
    (
        "真实分支不链接 jnigraphics",
        lambda t: t.replace("        ${jnigraphics-lib}\n        ncnn\n",
                            "        ncnn\n", 1),
        "jnigraphics",
    ),
    (
        "真实分支删掉 main.cpp（JNI 入口）",
        lambda t: t.replace("    file(GLOB OCR_SOURCES ocr_lite/src/*.cpp)\n", ""),
        "JNI",
    ),
]


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if not os.path.isfile(TARGET):
        print(f"找不到目标: {TARGET}", file=sys.stderr)
        return 2

    mod = load_checker()
    bad = 0
    total = 0

    # 0) 基线：未改动时必须通过
    with tempfile.TemporaryDirectory() as tmp:
        root = make_tree(tmp)
        rc, out = run_checker(mod, root)
        total += 1
        if rc == 0:
            print("OK   | 基线（未改动）通过")
        else:
            bad += 1
            print("FAIL | 基线应当通过，实际失败：")
            print("\n".join("        " + l for l in out.splitlines()[-12:]))

    # 1..N) 逐个注入缺陷，断言被报出
    for label, mutator, expect_kw in CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = make_tree(tmp)
            patch_cmake(root, mutator)
            rc, out = run_checker(mod, root)
            total += 1
            caught = rc != 0 and expect_kw in out
            if caught:
                print(f"OK   | 注入『{label}』被捕获（命中关键字 {expect_kw}）")
            else:
                bad += 1
                print(f"FAIL | 注入『{label}』未被捕获（rc={rc}, 期望关键字 {expect_kw}）")
                print("\n".join("        " + l for l in out.splitlines()[-12:]))

    print("-" * 72)
    if bad:
        print(f"{bad}/{total} 项不符合预期")
        return 1
    print(f"{total} 项全部符合预期 —— 该校验器确实能抓到真实缺陷。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
