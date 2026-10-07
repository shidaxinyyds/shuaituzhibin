#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""Kotlin/Java 源文件的括号与字面量配平闸门 (check_kotlin_braces.py)

## 为什么需要它
本机没有 JDK / Android SDK / NDK，`./gradlew` 跑不起来。而仓库里最致命的一类缺陷
恰恰是**编译期才暴露**的结构错误：少一个 `}`、括号错配、字符串引号没闭合。
这类错误一旦上到构建机，表现为"整包编不出 APK"，改起来却常常要来回好几趟。
其余闸门（verify_refs / check_ctor_named_args / check_kotlin_api_pitfalls）都对账**语义**，
没有一个先确认"这个文件至少是个能解析的结构"。语义校验器在结构已经塌了的文件上
只会给出噪声结论——所以这道闸门排在最前面。

## 判据
逐字符扫描，维护一个上下文栈，只在 **code** 层统计 `()`/`{}`/`[]` 配对；
以下形态必须被正确跳过，否则闸门会把注释和字符串里的括号也算进账：
  * 行注释 `// …`
  * 块注释 `/* … */`，**Kotlin 允许嵌套**，所以块注释自己也要计数
  * 普通字符串 `"…"`（`\` 转义生效；内含 `}` `(` 不算数；**不允许跨行**）
  * 原始字符串（三个双引号包裹，内含单个双引号不算结束；${} 仍是模板，要进 code 层）
  * 字符字面量 `'…'`
  * 字符串模板 `"${ … }"`：模板内部是代码，括号照算，且可以再嵌字符串/lambda
    （`"${list.map { it.x }.size}"` 是配平的）

任一文件出现下列情况即判失败：
  1. 括号类型错配（`(]`、`{)`）或数量不配平，报出**第一个**失配处行号；
  2. 文件结束时仍停在字符串 / 字符 / 块注释 / 模板里（未闭合）；
  3. 普通字符串中间换行（几乎总是上一行引号少打了一个）；
  4. 括号出现在字符串/注释之外却已闭合过外层（深度变负）。

## 自测
`--selftest` 注入 10 条已知结构缺陷 + 6 个"看起来会误伤"的正例
（字符串里的 `}`、注释里的括号、原始字符串里的引号、模板里的 lambda、字符 `'{'`、
转义引号后的括号）。正例不许误报、反例必须被抓到，任一不符即自测失败。

## 边界（别把它当编译器）
本闸门只保证**结构配平**，不保证语义正确：类型错误、未解析引用、API 误用都不归它管。
反过来说，"配平通过"绝不等于"能编译"，它只是让后面的语义闸门有可信的输入。

用法
----
    python tools/check_kotlin_braces.py                 # 扫全部 .kt / .java
    python tools/check_kotlin_braces.py --root client/app/src/main
    python tools/check_kotlin_braces.py --selftest
"""

import os
import sys

REPO = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(REPO)
DEFAULT_ROOT = os.path.join("client", "app", "src", "main", "java")

PAIRS = {")": "(", "]": "[", "}": "{"}
OPENERS = set(PAIRS.values())
CLOSERS = set(PAIRS)


class ScanError(Exception):
    """解析器自己看不懂这段源码——必须报错，绝不许当作"配平通过"。"""


def check_source(text):
    """返回 (line, message) 或 None。line 为 1 基行号。

    找不到问题时返回 None；解析器力不从心时抛 ScanError（宁可红也不要假绿）。
    """
    stack = []          # 元素: (kind, ...)；kind ∈ code/string/rawstring/char/line/block/template
    braces = []         # 仅 code/template 层使用的括号栈，元素 (char, line)
    block_depth = []    # 每层块注释的嵌套计数
    line = 1
    i = 0
    n = len(text)

    def top():
        return stack[-1][0] if stack else "code"

    while i < n:
        ch = text[i]
        if ch == "\n":
            cur = top()
            if cur == "string":
                return (line, "普通字符串在行尾仍未闭合（几乎都是上一行少打了一个引号）")
            if cur == "char":
                return (line, "字符字面量在行尾仍未闭合")
            if cur in ("line",):
                stack.pop()
            i += 1
            line += 1
            # 原始字符串与块注释可以合法跨行；模板/代码层按行推进即可
            continue

        cur = top()

        # ------------------------------------------------ 行注释：吃到行尾
        if cur == "line":
            i += 1
            continue

        # ------------------------------------------------ 块注释（可嵌套）
        if cur == "block":
            if text.startswith("*/", i):
                block_depth[-1] -= 1
                i += 2
                if block_depth[-1] == 0:
                    block_depth.pop()
                    stack.pop()
                continue
            if text.startswith("/*", i):
                block_depth[-1] += 1
                i += 2
                continue
            i += 1
            continue

        # ------------------------------------------------ 字符字面量
        if cur == "char":
            if ch == "\\":
                i += 2
                continue
            if ch == "'":
                stack.pop()
                i += 1
                continue
            i += 1
            continue

        # ------------------------------------------------ 普通字符串
        if cur == "string":
            if ch == "\\":
                i += 2                       # 转义（\" \\ \n \u2026）整体跳过，含 \${
                continue
            if ch == "$" and text[i + 1:i + 2] == "{":
                stack.append(("template", len(braces)))
                braces.append(("{", line))   # 模板自己的 { 由栈位承担
                i += 2
                continue
            if ch == '"':
                stack.pop()
                i += 1
                continue
            i += 1
            continue

        # ------------------------------------------------ 原始字符串 """…"""
        if cur == "rawstring":
            if ch == '"':
                run = 0
                while text[i + run:i + run + 1] == '"':
                    run += 1
                if run >= 3:
                    # Kotlin 取最后三个作为结束符，前面多余的是内容
                    i += run - 3
                    stack.pop()
                    i += 3
                    continue
                i += run
                continue
            if ch == "$" and text[i + 1:i + 2] == "{":
                stack.append(("template", len(braces)))
                braces.append(("{", line))
                i += 2
                continue
            i += 1
            continue

        # ------------------------------------------------ 模板 ${ … }
        if cur == "template":
            start_len = stack[-1][1]
            # 模板内部就是代码：括号照算，字符串/注释照跳
            if text.startswith("//", i):
                stack.append(("line", 0))
                i += 2
                continue
            if text.startswith("/*", i):
                stack.append(("block", 0))
                block_depth.append(1)
                i += 2
                continue
            if ch == "{":
                braces.append(("{", line))
                i += 1
                continue
            if ch == "}":
                if len(braces) > start_len + 1:
                    # 这个 } 关的是模板内部自己打开的 {（lambda / when 块等）
                    braces.pop()
                    i += 1
                    continue
                braces.pop()                  # 关掉模板自身的 {
                stack.pop()
                i += 1
                continue
            # 落到下面按 code 处理（引号、其余括号）
            cur = "code"

        # ------------------------------------------------ 代码层
        if text.startswith("//", i):
            stack.append(("line", 0))
            i += 2
            continue
        if text.startswith("/*", i):
            stack.append(("block", 0))
            block_depth.append(1)
            i += 2
            continue
        if text.startswith('"""', i):
            stack.append(("rawstring", 0))
            i += 3
            continue
        if ch == '"':
            stack.append(("string", 0))
            i += 1
            continue
        if ch == "'":
            stack.append(("char", 0))
            i += 1
            continue
        if ch in OPENERS:
            braces.append((ch, line))
            i += 1
            continue
        if ch in CLOSERS:
            if not braces:
                return (line, "多余的右括号 %r（没有任何开括号与它配对）" % ch)
            opener, oline = braces.pop()
            if opener != PAIRS[ch]:
                return (line, "括号错配：%r 在行 %d 打开，却由 %r 在行 %d 关闭"
                        % (opener, oline, ch, line))
            i += 1
            continue
        i += 1

    if stack:
        kinds = [k for (k, _s) in stack]
        return (line, "文件结束时仍处于 %s 状态（未闭合）" % "/".join(kinds))
    if braces:
        opener, oline = braces[-1]
        return (oline, "开括号 %r 直到文件结束都没有闭合" % opener)
    return None


def collect(root):
    files = []
    for dirpath, _dirs, names in os.walk(root):
        for fn in sorted(names):
            if fn.endswith(".kt") or fn.endswith(".java"):
                files.append(os.path.join(dirpath, fn))
    if not files:
        raise ScanError("没有扫到任何 Kotlin/Java 源文件：%s（解析器自己瞎了不许报绿）" % root)
    return files


def scan(root):
    findings = []
    for path in collect(root):
        with open(path, "r", encoding="utf-8") as fh:
            src = fh.read()
        try:
            bad = check_source(src)
        except ScanError as e:
            findings.append("%s: 解析失败 %s" % (os.path.relpath(path, REPO), e))
            continue
        if bad:
            ln, msg = bad
            findings.append("%s:%d %s" % (os.path.relpath(path, REPO), ln, msg))
    return findings


# ------------------------------------------------------------------ 自测
# (说明, 源码, 期望是否报错)
GOOD = [
    ("字符串里的右花括号不许入账", 'val s = "}"\nfun f() { }\n'),
    ("注释里的括号不许入账", '/* ) ) ( [ */\nval a = // (\n    1\n'),
    ("原始字符串里的引号与括号", 'val r = """a " b } ( c"""\n'),
    ("模板里可以嵌 lambda", 'val s = "${list.map { it.x }.size}"\n'),
    ("字符字面量里的左花括号", "val c = '{'\n"),
    ("转义引号之后的括号", 'val s = "a\\" }"\nval t = (s)\n'),
    ("块注释可嵌套", '/* outer /* inner */ still comment ) */\nval x = 1\n'),
]
BAD = [
    ("少一个花括号", 'fun f() {\n    if (a) {\n        g()\n    }\n'),
    ("括号错配 ( 由 } 关闭", 'fun f() {\n    val x = (a + b }\n'),
    ("方括号错配", 'val a = [1, 2)\n'),
    ("多余的右花括号", 'fun f() { }\n}\n'),
    ("普通字符串未闭合", 'val s = "abc\nval t = 1\n'),
    ("块注释未闭合", 'val x = 1\n/* never closed\nval y = 2\n'),
    ("模板未闭合", 'val s = "${a + b\n'),
    ("字符字面量未闭合", "val c = 'a\n"),
    ("结构塌陷于字符串之后", 'val s = "}"\nfun f() {\n'),
    ("原始字符串吞掉结尾括号", 'val r = """x\nfun f() { }\n'),
]


def selftest():
    bad = 0
    for desc, src in GOOD:
        try:
            got = check_source(src)
        except ScanError as e:
            bad += 1
            print("❌ 正例解析失败：%s → %s" % (desc, e))
            continue
        if got:
            bad += 1
            print("❌ 正例被误报：%s → %s（第 %d 行）" % (desc, got[1], got[0]))
        else:
            print("[OK] 正例 %-24s → 无报告" % desc)

    for desc, src in BAD:
        try:
            got = check_source(src)
        except ScanError as e:
            print("[OK] 反例 %-24s → 解析器拒判（%s）" % (desc, e))
            continue
        if not got:
            bad += 1
            print("❌ 反例没被抓到：%s" % desc)
        else:
            print("[OK] 反例 %-24s → 第 %d 行：%s" % (desc, got[0], got[1][:52]))

    # 解析器不许"看不见就当通过"：空扫描根必须报错
    try:
        collect(os.path.join(REPO, "_no_such_dir_for_selftest"))
        bad += 1
        print("❌ 扫描根不存在却没报错（闸门瞎了仍会报绿）")
    except ScanError:
        print("[OK] 扫描根不存在            → 报错退出")

    print("\n[selftest] %s" % ("全部通过" if bad == 0 else "有 %d 项不符合预期" % bad))
    return 0 if bad == 0 else 1


def main(argv=None):
    # run_all_checks.py 是在**当前进程内**调用 `mod.main()`（不带参数），
    # 所以 argv 必须可省略；否则这里会 TypeError，回归上表现为"该项失败"。
    args = list(sys.argv[1:] if argv is None else argv)
    root = os.path.join(REPO, DEFAULT_ROOT)
    if "--root" in args:
        k = args.index("--root")
        if k + 1 >= len(args):
            print("--root 后面要跟目录")
            return 2
        root = os.path.join(REPO, args[k + 1])
    if "--selftest" in args:
        return selftest()
    try:
        findings = scan(root)
    except ScanError as e:
        print("[FAIL] %s" % e)
        return 2
    if findings:
        print("[FAIL] Kotlin/Java 结构配平检查发现 %d 处问题：" % len(findings))
        for f in findings[:40]:
            print("   " + f)
        if len(findings) > 40:
            print("   … 另有 %d 处" % (len(findings) - 40))
        return 1
    count = len(collect(root))
    print("[OK] 结构配平通过：%d 个源文件的括号/字符串/注释/模板全部配对" % count)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
