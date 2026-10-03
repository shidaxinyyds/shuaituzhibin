#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
具名构造参数对账（无编译环境下的 "No parameter with name 'x'" 闸门）
====================================================================

## 为什么需要它
Kotlin 的具名参数（`Foo(a = 1, b = 2)`）写错一个名字就是编译期错误，而
`tools/verify_refs.py` 的成员对账只覆盖**枚举常量**与**整对象成员**
（`ButtonType.X` / `EngineBridge.x`），**不检查构造调用的具名实参**。

这类错误很容易在"给 data class 加字段 / 改字段名"时引入，且没有编译器就完全看不出来。

## 实现要点（以及踩过的坑）
1. 必须**先剥离注释**再解析构造参数表：本项目的构造参数上普遍带中文注释，
   而注释里常含逗号（如 `// DEFENDER_LAND, SKILL_SYNERGY, ...`），
   按顶层逗号切分时会把参数名切碎，产生大量**假阳性**。
   （第一版没剥离注释，报出 30+ 条"不存在该参数"，全部是假的。）
2. 必须按**嵌套深度**切分：泛型 `Map<String, Int>`、默认值 `listOf(1, 2)`
   里的逗号都不能当作参数分隔。
3. 只对"本工程内声明的 data class"做对账，外部类型（`Intent` 等）跳过。

用法
----
    python tools/check_ctor_named_args.py
    python tools/check_ctor_named_args.py --root client/app/src/main

退出码：0 = 通过；1 = 发现问题；2 = 环境问题
"""

import argparse
import collections
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def strip_comments(text):
    text = re.sub(r"/\*[\s\S]*?\*/", "", text)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def balanced(text, start):
    """从 text[start] == '(' 开始，返回配对括号内的内容。"""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == '(':
            depth += 1
        elif text[i] == ')':
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
    return ""


def split_top(s):
    """按顶层逗号切分（忽略 <> () [] {} 内的逗号）。"""
    out, depth, buf = [], 0, ""
    for ch in s:
        if ch in "([{<":
            depth += 1
        if ch in ")]}>":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(buf)
            buf = ""
        else:
            buf += ch
    out.append(buf)
    return out


def collect_data_class_params(files):
    """简单类名 -> 主构造参数集合。"""
    params = collections.defaultdict(set)
    for f in files:
        try:
            with open(f, "r", encoding="utf-8", errors="replace") as fh:
                t = strip_comments(fh.read())
        except Exception:
            continue
        for m in re.finditer(r"data\s+class\s+(\w+)", t):
            name = m.group(1)
            p = t.find("(", m.end())
            if p == -1:
                continue
            # "data class Foo<...>(" 之外的形态跳过
            if not re.match(r"^\s*(?:<[^>]*>\s*)?$", t[m.end():p]):
                continue
            for param in split_top(balanced(t, p)):
                pm = re.match(r"\s*(?:private\s+|public\s+|protected\s+|internal\s+)*"
                              r"(?:val|var)\s+(\w+)", param)
                if pm:
                    params[name].add(pm.group(1))
    return params


def check_file(rel, raw, params):
    """返回 (行号, 类名, 错误参数名, 可用参数) 列表。

    注意：只从**当前行**里找 `ClassName(` 的起点，再从该行往后取窗口找配对的 `)`。
    第一版是"每行都开一个 60 行窗口去找所有调用"，同一个调用会被前面每一行各报一次，
    一个错误重复打印几十次——虽然不算误报，但会淹没真正的问题。
    """
    text = strip_comments(raw)
    lines = text.split("\n")
    problems = []
    seen = set()
    for i, cur_line in enumerate(lines):
        for m in re.finditer(r"\b(\w+)\s*\(", cur_line):
            cls = m.group(1)
            if cls not in params:
                continue
            window = "\n".join(lines[i:i + 60])
            start = m.end() - 1
            # 位置对齐：window 以 cur_line 开头，所以 m 在 cur_line 的下标可直接用于 window
            args = balanced(window, start)
            if not args:
                continue
            for arg in split_top(args):
                am = re.match(r"\s*(\w+)\s*=[^=]", arg)
                if not am:
                    continue
                key = am.group(1)
                if key not in params[cls]:
                    dedup = (i + 1, cls, key)
                    if dedup in seen:
                        continue
                    seen.add(dedup)
                    problems.append((i + 1, cls, key, sorted(params[cls])))
    return problems


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join("client", "app", "src", "main"),
                    help="源码根目录（默认 client/app/src/main）")
    args = ap.parse_args()

    os.chdir(REPO)
    root = args.root
    if not os.path.isdir(root):
        print("找不到源码根目录: %s" % root, file=sys.stderr)
        return 2

    files = sorted(glob.glob(os.path.join(root, "**", "*.kt"), recursive=True))
    if not files:
        print("在 %s 下没有找到 .kt 文件" % root, file=sys.stderr)
        return 2

    params = collect_data_class_params(files)
    if not params:
        print("未解析到任何 data class，脚本可能已失效", file=sys.stderr)
        return 2

    total = 0
    for f in files:
        rel = os.path.relpath(f, root).replace("\\", "/")
        try:
            with open(f, "r", encoding="utf-8", errors="replace") as fh:
                raw = fh.read()
        except Exception as e:
            print("读取失败 %s: %s" % (f, e), file=sys.stderr)
            return 2
        for ln, cls, key, valid in check_file(rel, raw, params):
            total += 1
            print("ERROR | %s:%d | %s(..., %s = ...) 不存在该具名参数；可用: %s"
                  % (rel, ln, cls, key, ", ".join(valid)))

    print("-" * 72)
    print("已解析 %d 个 data class，具名参数错误 %d 处" % (len(params), total))
    if total:
        print("结论: 存在具名参数错误（编译期会报 No parameter with name），请修复。")
        return 1
    print("结论: 通过（所有具名构造参数均已声明）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
