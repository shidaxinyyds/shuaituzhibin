#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实 OCR native 构建的前置条件静态校验
======================================

为什么需要它
------------
"提供 ncnn + OpenCV 就能编出可用的 OCR" 依赖若干**静态就能查证**的条件，
任何一条不满足，结果都是"provision 了依赖却仍然失败"，且错误信息往往指向不了根因：

  * ocr_lite 的头文件 `#include <opencv2/core.hpp>`，而链接用的是 `${OpenCV_LIBS}`
    （库路径列表）——它**不会**传递头文件目录。只 `find_package(OpenCV)`
    而不把 `${OpenCV_INCLUDE_DIRS}` 加进 `include_directories`，
    会以 `'opencv2/core.hpp' file not found` 失败；
  * 真实分支的 JNI 入口在 `ocr_lite/src/main.cpp`，必须与该源集一起编译，
    否则 Java 侧 `external fun` 找不到实现（UnsatisfiedLinkError）；
  * 空桩 `OcrStub.cpp` 与真实入口定义**同名 JNI 符号**，两个分支绝不能同时包含；
  * `android/bitmap.h` 需要链接 `jnigraphics`。

实现要点（第一版在这里栽过跟头）
--------------------------------
判断"分支里是否有某个配置"**不能**用子串搜索：
`if (NOT OpenCV_INCLUDE_DIRS)` 这种守卫语句、以及注释里提到的文件名，
都会让子串搜索得到假阳性，结果校验器全程说 OK 却什么都没查。
因此这里改为**解析真实的函数调用参数**：
  * `include_directories(...)` 的参数里是否有 `OpenCV_INCLUDE_DIRS`；
  * `target_link_libraries(RapidOcr ...)` 的参数里是否有 `ncnn` / `jnigraphics-lib`；
  * `add_library(RapidOcr ...)` 实际引用了哪些源文件（并展开 `file(GLOB ...)`）。
配套的反例自测见 `tools/selftest_native_ocr_check.py`。

用法
----
    python tools/check_native_ocr_build.py [--root client/app/src/main]

退出码：0 = 全部通过；1 = 发现问题。
"""

import argparse
import glob as globmod
import os
import re
import sys

KT_TO_JNI = {
    "Int": "jint", "Long": "jlong", "Boolean": "jboolean",
    "Float": "jfloat", "Double": "jdouble", "Byte": "jbyte",
    "Short": "jshort", "Char": "jchar", "String": "jstring",
}
KT_RET_TO_JNI = dict(KT_TO_JNI)
KT_RET_TO_JNI["Unit"] = "void"

NCNN_HEADERS = {"net.h", "layer.h", "mat.h", "platform.h", "benchmark.h", "cpu.h"}
OPENCV_PREFIXES = ("opencv2/", "opencv/")

SYSTEM_HEADERS = {
    "jni.h", "jni_md.h", "unistd.h", "pthread.h", "dlfcn.h", "time.h",
    "stdio.h", "stdlib.h", "string.h", "math.h", "stdint.h",
}

COMMENT_BLOCK = re.compile(r"/\*[\s\S]*?\*/")
COMMENT_LINE = re.compile(r"//[^\n]*")
INCLUDE_RE = re.compile(r'^\s*#\s*include\s*([<"])([^>"]+)[>"]', re.M)
CMAKE_COMMENT_RE = re.compile(r"#[^\n]*")


class Rep:
    def __init__(self):
        self.errors = []
        self.notes = []
        self.infos = []

    def error(self, where, msg):
        self.errors.append((where, msg))

    def note(self, msg):
        self.notes.append(msg)

    def info(self, msg):
        self.infos.append(msg)


def read(p):
    with open(p, "r", encoding="utf-8", errors="replace") as f:
        return f.read()


def strip_c_comments(text):
    return COMMENT_LINE.sub("", COMMENT_BLOCK.sub("", text))


def extract_call_args(text, call_name):
    """取 text 中第一处 `call_name(...)` 的括号内文本（正确处理嵌套括号）。"""
    m = re.search(r"(?<![\w.])" + re.escape(call_name) + r"\s*\(", text)
    if not m:
        return None
    i = m.end()
    depth = 1
    out = []
    while i < len(text) and depth > 0:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        out.append(c)
        i += 1
    return "".join(out)


def cmake_branches(cmake_text):
    """
    按**顶格**的 `if (ncnn_FOUND` 与顶格的 `else()` 切分两个分支。

    不能用嵌套正则：真实分支里还有嵌套 if/endif，正则很容易切错。
    工程里嵌套 if 都是缩进的，因此这两个锚点唯一。
    """
    m_if = re.search(r"^if\s*\(\s*ncnn_FOUND", cmake_text, re.M)
    if not m_if:
        return None, None
    rest = cmake_text[m_if.end():]
    m_else = re.search(r"^else\s*\(\)", rest, re.M)
    if not m_else:
        return None, None
    return rest[:m_else.start()], rest[m_else.end():]


def resolve_sources(cpp_dir, branch):
    """
    解析 `add_library(RapidOcr ...)` 实际引用的源文件（展开 file(GLOB ...)）。
    返回 (绝对路径列表, 未解析的 token 列表, add_library 参数原文)
    """
    args = extract_call_args(branch, "add_library")
    if args is None:
        return [], [], None

    globs = {}
    for gm in re.finditer(r"\bfile\s*\(\s*GLOB\s+(\w+)\s+([^)]+)\)", branch):
        globs["${" + gm.group(1) + "}"] = gm.group(2).strip()

    skip = {"RapidOcr", "SHARED", "STATIC", "MODULE"}
    resolved, unresolved = [], []
    for tok in args.split():
        if tok in skip:
            continue
        if tok in globs:
            hits = sorted(globmod.glob(os.path.join(cpp_dir, globs[tok])))
            if not hits:
                unresolved.append(tok)
            resolved.extend(hits)
        elif tok.endswith((".cpp", ".c", ".cc")):
            p = os.path.join(cpp_dir, tok)
            if os.path.isfile(p):
                resolved.append(p)
            else:
                unresolved.append(tok)
        else:
            unresolved.append(tok)
    return resolved, unresolved, args


def collect_includes(files):
    out = []
    for p in files:
        for m in INCLUDE_RE.finditer(read(p)):
            out.append((p, m.group(1), m.group(2)))
    return out


def parse_kotlin_externals(kt_path):
    text = strip_c_comments(read(kt_path))
    result = {}
    for m in re.finditer(r"external\s+fun\s+(\w+)\s*\(([^)]*)\)\s*:\s*([\w.<>\[\]]+)", text, re.S):
        params = []
        for part in m.group(2).split(","):
            part = part.strip()
            if not part:
                continue
            params.append(part.split(":", 1)[1].strip() if ":" in part else part)
        result[m.group(1)] = (params, m.group(3).strip())
    return result


def parse_cpp_jni(cpp_files):
    result = {}
    for p in cpp_files:
        text = read(p)
        for m in re.finditer(
            r"JNIEXPORT\s+(\w+)\s+JNICALL\s*\n?\s*(Java_\w+)\s*\(([^)]*)\)", text, re.S
        ):
            params = [x.strip() for x in m.group(3).split(",") if x.strip()]
            result[m.group(2)] = (params, m.group(1), p)
    return result


def cpp_param_type(param_decl):
    """取形参的**类型**（末位是变量名）。"""
    tokens = param_decl.strip().split()
    if not tokens:
        return ""
    decl = " ".join(tokens[:-1]) if len(tokens) >= 2 else tokens[0]
    return decl.replace("*", "").replace("&", "").strip()


def jni_symbol_for(package, class_name, fn_name):
    return "Java_" + (package + "." + class_name).replace(".", "_") + "_" + fn_name


def expected_jni_param_type(kt_type):
    kt_type = kt_type.strip()
    if kt_type in KT_TO_JNI:
        return KT_TO_JNI[kt_type]
    if kt_type.startswith("Array<") or kt_type.endswith("Array"):
        return None
    return "jobject"


def expected_jni_ret_type(kt_ret):
    return KT_RET_TO_JNI.get(kt_ret.strip(), "jobject")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join("client", "app", "src", "main"))
    args = ap.parse_args()
    root = os.path.abspath(args.root)

    cpp_dir = os.path.join(root, "cpp")
    ocr_dir = os.path.join(cpp_dir, "ocr_lite")
    ocr_src_dir = os.path.join(ocr_dir, "src")
    ocr_inc_dir = os.path.join(ocr_dir, "include")
    repo = Rep()

    cmake_text = read(os.path.join(cpp_dir, "CMakeLists.txt"))
    real_branch, stub_branch = cmake_branches(cmake_text)
    if real_branch is None:
        print("无法在 CMakeLists.txt 中定位 if(ncnn_FOUND ...)/else() 分支", file=sys.stderr)
        return 2

    print("=" * 76)
    print("真实 OCR native 构建 · 前置条件静态校验")
    print("=" * 76)
    print(f"根目录      : {root}")
    print("-" * 76)

    # ---------- 1. 真实分支实际参与编译的源文件 ----------
    real_src, unresolved_tokens, add_lib_args = resolve_sources(cpp_dir, real_branch)
    if add_lib_args is None:
        repo.error("cpp/CMakeLists.txt", "真实分支里找不到 add_library(RapidOcr ...)")
    else:
        if unresolved_tokens:
            repo.error("cpp/CMakeLists.txt",
                       f"add_library 里这些 token 无法解析：{unresolved_tokens}")
        has_ocr_sources = any(os.path.abspath(p).startswith(os.path.abspath(ocr_dir) + os.sep)
                              for p in real_src)
        if not has_ocr_sources:
            repo.error("cpp/CMakeLists.txt",
                       "真实分支的 add_library 没有包含 ocr_lite 的源文件 —— "
                       "OcrEngine 的三个 JNI 入口（在 ocr_lite/src/main.cpp）不会被编译，"
                       "运行时必然 UnsatisfiedLinkError。")
        else:
            repo.note(f"真实分支编译 {len(real_src)} 个源文件（含 ocr_lite）")
        if any(os.path.basename(p) == "OcrStub.cpp" for p in real_src):
            repo.error("cpp/CMakeLists.txt",
                       "真实分支包含了 OcrStub.cpp —— 会与 ocr_lite 的同名 JNI 符号重复定义")
        if not any(os.path.basename(p) == "SecurityBridge.cpp" for p in real_src):
            repo.error("cpp/CMakeLists.txt", "真实分支缺少 SecurityBridge.cpp")

    stub_src, _, stub_args = resolve_sources(cpp_dir, stub_branch)
    if stub_src and any(os.path.abspath(p).startswith(os.path.abspath(ocr_dir) + os.sep)
                        for p in stub_src):
        repo.error("cpp/CMakeLists.txt",
                   "空桩分支包含了 ocr_lite 源文件 —— 会与 OcrStub.cpp 的 JNI 符号重复定义")

    # ---------- 2. include 目录与链接库：解析真实调用参数 ----------
    inc_args = extract_call_args(real_branch, "include_directories") or ""
    link_args = extract_call_args(real_branch, "target_link_libraries") or ""

    all_includes = collect_includes(
        [os.path.join(dp, f) for dp, _, fs in os.walk(cpp_dir) for f in fs
         if f.endswith((".cpp", ".h", ".hpp"))]
    )
    uses_opencv = any(h.startswith(OPENCV_PREFIXES) for _, _, h in all_includes)
    uses_ncnn = any(h in NCNN_HEADERS for _, _, h in all_includes)

    if uses_opencv:
        if "OpenCV_INCLUDE_DIRS" in inc_args:
            repo.note("include_directories 中已包含 ${OpenCV_INCLUDE_DIRS}")
        else:
            repo.error("cpp/CMakeLists.txt",
                       "ocr_lite 使用了 <opencv2/...>，但真实分支的 include_directories 参数里"
                       "没有 ${OpenCV_INCLUDE_DIRS}。注意 ${OpenCV_LIBS} 是库路径列表、"
                       "不会传递头文件目录 —— 这会以 'opencv2/core.hpp' file not found 失败。")
    if uses_ncnn:
        if re.search(r"(?<![\w.])ncnn(?![\w])", link_args):
            repo.note("target_link_libraries(RapidOcr ...) 已链接 ncnn（自动传递头文件目录）")
        elif "ncnn_INCLUDE_DIRS" in inc_args:
            repo.note("include_directories 中已包含 ${ncnn_INCLUDE_DIRS}")
        else:
            repo.error("cpp/CMakeLists.txt",
                       "ocr_lite 使用了 ncnn 的 <net.h>，但真实分支既没在 "
                       "target_link_libraries 里链接 ncnn，也没有加入 ${ncnn_INCLUDE_DIRS}")
    if any(h.startswith("android/bitmap") for _, _, h in all_includes):
        if "jnigraphics" in link_args:
            repo.note("使用 android/bitmap.h，且真实分支已链接 jnigraphics")
        else:
            repo.error("cpp/CMakeLists.txt",
                       "使用了 android/bitmap.h，但真实分支的 target_link_libraries 没有 jnigraphics")

    # ---------- 3. 工程内头文件是否都能解析到 ----------
    project_headers = set()
    for d in (ocr_inc_dir, cpp_dir, ocr_dir):
        for dp, _, fs in os.walk(d):
            for f in fs:
                if f.endswith((".h", ".hpp")):
                    project_headers.add(f)

    unresolved = []
    for path, _, header in all_includes:
        if header.startswith(OPENCV_PREFIXES) or header in NCNN_HEADERS:
            continue
        if header.startswith(("android/", "sys/", "netinet/", "arpa/")):
            continue
        base = os.path.basename(header)
        if base in SYSTEM_HEADERS:
            continue
        if "." not in base:
            continue  # C++ 标准库头（list/set/queue...）常常也用引号引入
        if base in project_headers:
            continue
        if os.path.isfile(os.path.join(cpp_dir, header)) or \
                os.path.isfile(os.path.join(ocr_inc_dir, header)):
            continue
        unresolved.append((path, header))
    if unresolved:
        for path, header in unresolved[:10]:
            repo.error(os.path.relpath(path, root), f'#include "{header}" 无法在已配置的 include 目录中解析到')
    else:
        repo.note(f"工程内头文件引用全部可解析（扫描 {len(all_includes)} 条 include）")

    # ---------- 4. JNI 符号与签名（用真实分支**实际编译**的源文件）----------
    ocr_kt = os.path.join(root, "java", "com", "benjaminwan", "ocrlibrary", "OcrEngine.kt")
    externals = parse_kotlin_externals(ocr_kt)

    sb_cpp = os.path.join(cpp_dir, "SecurityBridge.cpp")
    real_jni_files = real_src if real_src else []
    if sb_cpp not in real_jni_files and os.path.isfile(sb_cpp) and not real_src:
        real_jni_files = [sb_cpp]
    real_syms = parse_cpp_jni(real_jni_files)
    stub_syms = parse_cpp_jni(stub_src if stub_src else [os.path.join(cpp_dir, "OcrStub.cpp")])

    pkg = "com.benjaminwan.ocrlibrary"
    for name, (params, ret) in sorted(externals.items()):
        sym = jni_symbol_for(pkg, "OcrEngine", name)
        for label, table in (("真实分支", real_syms), ("空桩分支", stub_syms)):
            entry = table.get(sym)
            if not entry:
                repo.error("cpp/CMakeLists.txt",
                           f"{label} 缺少 JNI 入口 {sym}（Java 侧 OcrEngine.{name} 声明为 external）")
                continue
            cpp_params, cpp_ret, f = entry
            if len(cpp_params) != len(params) + 2:
                repo.error(os.path.relpath(f, root),
                           f"{label} {sym} 参数个数不匹配：Java {len(params)} 个，"
                           f"C++ {len(cpp_params)} 个（应为 {len(params) + 2}）")
                continue
            exp_ret = expected_jni_ret_type(ret)
            if cpp_ret != exp_ret:
                repo.error(os.path.relpath(f, root),
                           f"{label} {sym} 返回类型不匹配：Java {ret} 期望 {exp_ret}，C++ 为 {cpp_ret}")
            for i, kt_type in enumerate(params):
                exp = expected_jni_param_type(kt_type)
                if exp is None:
                    continue
                got = cpp_param_type(cpp_params[i + 2])
                if got != exp:
                    repo.error(os.path.relpath(f, root),
                               f"{label} {sym} 第 {i + 1} 个参数类型不匹配：Java {kt_type} "
                               f"期望 {exp}，C++ 为 {got}")
        repo.info(f"OcrEngine.{name} 的 JNI 入口与签名已在两个分支中核对")

    # ---------- 5. SecurityBridge ----------
    sb_kt = os.path.join(root, "java", "com", "stzb", "assistant", "SecurityBridge.kt")
    if os.path.isfile(sb_kt):
        sb_externals = parse_kotlin_externals(sb_kt)
        sb_syms = parse_cpp_jni([sb_cpp])
        for name in sorted(sb_externals):
            sym = jni_symbol_for("com.stzb.assistant", "SecurityBridge", name)
            if sym not in sb_syms:
                repo.error("cpp/SecurityBridge.cpp", f"缺少 JNI 入口 {sym}（Java 侧 SecurityBridge.{name}）")
        repo.info(f"SecurityBridge 的 {len(sb_externals)} 个 JNI 入口已核对")

    # ---------- 输出 ----------
    print("检查项:")
    for n in repo.infos:
        print(f"  [OK]   {n}")
    for n in repo.notes:
        print(f"  [OK]   {n}")
    for where, msg in repo.errors:
        print(f"  [FAIL] {where}\n         {msg}")
    print("-" * 76)
    if repo.errors:
        print(f"发现 {len(repo.errors)} 个问题 —— 真实 OCR 构建在当前配置下无法通过。")
        return 1
    print("全部通过：真实分支的源文件、include 路径、链接库、JNI 符号与签名均已就绪。")
    print("说明：本检查是静态的，不能替代真实的 NDK 编译。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
