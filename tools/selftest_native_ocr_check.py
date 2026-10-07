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
    """复制校验器需要的部分：cpp/ 与 java/，外加用于版本对账的 build.gradle 与工作流。

    目录形状刻意保持成真实仓库的样子（client/app/src/main、.github/workflows/…），
    否则"读同级仓库文件做对账"的那条检查在夹具里会永远走不到。
    """
    root = os.path.join(tmp, "client", "app", "src", "main")
    os.makedirs(root, exist_ok=True)
    shutil.copytree(os.path.join(SRC_MAIN, "cpp"), os.path.join(root, "cpp"))
    shutil.copytree(os.path.join(SRC_MAIN, "java"), os.path.join(root, "java"))
    shutil.copy(os.path.join(REPO, "client", "app", "build.gradle"),
                os.path.join(tmp, "client", "app", "build.gradle"))
    wf_dir = os.path.join(tmp, ".github", "workflows")
    os.makedirs(wf_dir, exist_ok=True)
    shutil.copy(os.path.join(REPO, ".github", "workflows", "build_apk_with_ocr.yml"), wf_dir)
    return root


def run_checker(mod, root, repo_root=None):
    """直接调用 main()，捕获 stdout（不启子进程，避免管道相关限制）。"""
    buf = io.StringIO()
    old_argv = sys.argv
    argv = ["check_native_ocr_build.py", "--root", root]
    if repo_root:
        argv += ["--repo-root", repo_root]
    sys.argv = argv
    try:
        with contextlib.redirect_stdout(buf):
            rc = mod.main()
    finally:
        sys.argv = old_argv
    return rc, buf.getvalue()


def patch_file(path, mutator):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    new = mutator(text)
    assert new != text, "注入的改动没有生效，自测本身有问题"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(new)


def patch_cmake(root, mutator):
    patch_file(os.path.join(root, "cpp", "CMakeLists.txt"), mutator)


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


# OpenCV 版本对账的反例。这类缺陷的特点是"CI 全绿、真机才崩"，
# 所以它必须有独立自测：否则校验器只要写错一次，就等于永久放行。
WF = ".github/workflows/build_apk_with_ocr.yml"
GR = "client/app/build.gradle"

VERSION_CASES = [
    (
        "工作流下载 4.9.0，AAR 仍是 4.5.3",
        [(WF, lambda t: t.replace("default: '4.5.3'", "default: '4.9.0'", 1))],
        "版本不一致",
    ),
    (
        "build.gradle 里没有 opencv AAR",
        [(GR, lambda t: t.replace("com.quickbirdstudios:opencv:4.5.3.0",
                                  "com.example.nothing:1.0", 1))],
        "opencv",
    ),
    (
        "工作流里 opencv_version 没有 default",
        [(WF, lambda t: t.replace("        default: '4.5.3'\n", "", 1))],
        "解析不到",
    ),
    (
        # 只改 AAR、不改工作流：另一侧的脱节同样必须被抓到
        "AAR 升到 4.9.0.0，工作流仍下载 4.5.3",
        [(GR, lambda t: t.replace("opencv:4.5.3.0", "opencv:4.9.0.0", 1))],
        "版本不一致",
    ),
]

# 反向控制：两边一起升版时必须**放行**。少了这一条，"永远报错"的校验器也会被判成有效。
VERSION_OK_CASES = [
    (
        "两边同时升到 4.9.0（应当放行）",
        [
            (WF, lambda t: t.replace("default: '4.5.3'", "default: '4.9.0'", 1)),
            (GR, lambda t: t.replace("opencv:4.5.3.0", "opencv:4.9.0.0", 1)),
        ],
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
        rc, out = run_checker(mod, root, tmp)
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
            rc, out = run_checker(mod, root, tmp)
            total += 1
            caught = rc != 0 and expect_kw in out
            if caught:
                print(f"OK   | 注入『{label}』被捕获（命中关键字 {expect_kw}）")
            else:
                bad += 1
                print(f"FAIL | 注入『{label}』未被捕获（rc={rc}, 期望关键字 {expect_kw}）")
                print("\n".join("        " + l for l in out.splitlines()[-12:]))

    # N+1..) OpenCV 版本对账：这类缺陷 CI 全绿、只在真机崩，所以必须有反例自测
    for label, patches, expect_kw in VERSION_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = make_tree(tmp)
            for rel, mutator in patches:
                patch_file(os.path.join(tmp, *rel.split("/")), mutator)
            rc, out = run_checker(mod, root, tmp)
            total += 1
            caught = rc != 0 and expect_kw in out
            if caught:
                print(f"OK   | 注入『{label}』被捕获（命中关键字 {expect_kw}）")
            else:
                bad += 1
                print(f"FAIL | 注入『{label}』未被捕获（rc={rc}, 期望关键字 {expect_kw}）")
                print("\n".join("        " + l for l in out.splitlines()[-12:]))

    # 反向控制：两边一致时必须放行，否则"永远报错"也算"能抓缺陷"是自欺
    for label, patches in VERSION_OK_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = make_tree(tmp)
            for rel, mutator in patches:
                patch_file(os.path.join(tmp, *rel.split("/")), mutator)
            rc, out = run_checker(mod, root, tmp)
            total += 1
            if rc == 0:
                print(f"OK   | 『{label}』如预期放行")
            else:
                bad += 1
                print(f"FAIL | 『{label}』应当放行，实际 rc={rc}：")
                print("\n".join("        " + l for l in out.splitlines()[-12:]))

    print("-" * 72)
    if bad:
        print(f"{bad}/{total} 项不符合预期")
        return 1
    print(f"{total} 项全部符合预期 —— 该校验器确实能抓到真实缺陷。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
