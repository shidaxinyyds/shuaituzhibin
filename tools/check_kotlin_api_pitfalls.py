#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin 端"只有真编译才暴露"的第三方 API 误用静态闸门
===================================================

背景（真实教训，CI Run #40 就是被这些卡住的）
--------------------------------------------
本机没有 JDK/Android SDK，`tools/run_all_checks.py` 里的既有校验器都是**纯静态**
（引用存在性/括号平衡/领域不变量/数学），**不做 Kotlin 类型检查、也不校验第三方库
API 签名**。于是下面这几类错误能一路绿灯过本地闸门，直到 GitHub Actions 真编译才炸：

  1. `OrtEnvironment.getEnv()`                —— 正确是 `getEnvironment()`
  2. `OnnxTensor.createTensor(.., longArrayOf(1, MAX_LEN))`
                                              —— shape 必须是全 Long，混进 Int 字面量编译不过
  3. `result?.forEach { it.close() }`         —— OrtSession.Result 的 Entry 没有 close()
                                                 应直接 `result?.close()`
  4. 把 `Pattern.compile(...)` 当 Kotlin Regex 用 `.find()/.findAll()/.groupValues`
                                              —— java.util.regex.Pattern 只有 `.matcher()`
  5. `textBlock.box.left/.top/.right/.bottom` —— OCR 的 TextBlock 只有 `boxPoint: ArrayList<Point>`

本闸门把这些"编译期才看得见"的坑固化成规则，推送前就能拦下，不必每轮靠 CI 兜底。

它刻意只做**高精度**的文本级判定（宁可漏报不误报），命中即说明"为什么 + 正确写法"。

用法
----
    python tools/check_kotlin_api_pitfalls.py                 # 扫 client 源码
    python tools/check_kotlin_api_pitfalls.py --root <dir>
    python tools/check_kotlin_api_pitfalls.py --selftest       # 正反例自测

退出码：0 = 无命中；1 = 有命中（打印 file:line 与修法）。
"""

import argparse
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

# (名称, 编译期正确写法提示, 触发正则)
RULES = [
    ("ort-getEnv",
     "OrtEnvironment 应使用 getEnvironment()（没有 getEnv()）",
     re.compile(r"OrtEnvironment\s*\.\s*getEnv\s*\(\s*\)")),
    ("result-foreach-close",
     "OrtSession.Result 的 Map.Entry 没有 close()；释放要直接 result?.close()",
     re.compile(r"\b\w+\??\s*\.\s*forEach\s*\{\s*it\s*\.\s*close\s*\(\s*\)\s*\}")),
    ("textblock-dot-box",
     "OCR TextBlock 没有 .box 矩形；用 boxPoint: ArrayList<Point> 取外接框",
     re.compile(r"\b\w+\s*\.\s*box\s*\.\s*(left|right|top|bottom)\b")),
    ("pattern-kotlin-regex-api",
     "java.util.regex.Pattern 没有 .find()/.findAll()/.groupValues（那是 Kotlin Regex 的 MatchResult）；改用 .matcher(x).find() 或 Regex()",
     None),  # 需要两遍扫描，见下
]

KT_EXTS = (".kt",)


def iter_kt(root):
    for dirpath, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle")]
        for f in files:
            if f.endswith(KT_EXTS):
                yield os.path.join(dirpath, f)


def scan_pattern_regex_misuse(path, lines):
    """两遍：找**直接**赋值 `val NAME = Pattern.compile(...)`（行内不含 .matcher——那会先得到 Matcher），
    再看同文件是否对 NAME 直接用了 .find()/.findAll()/.groupValues（那是 Matcher/Regex 的 API，Pattern 没有）。"""
    hits = []
    defs = set()
    for ln in lines:
        if ".matcher" in ln:
            continue  # 这行拿到的是 Matcher，不是 Pattern 本体
        m = re.search(r"\b(?:val|var)\s+(\w+)\s*=\s*Pattern\.compile\s*\(", ln)
        if m:
            defs.add(m.group(1))
    if not defs:
        return hits
    misuse = re.compile(r"\b(" + "|".join(re.escape(d) for d in defs) + r")\s*\.\s*(find|findAll|groupValues)\b")
    for i, ln in enumerate(lines, 1):
        m = misuse.search(ln)
        if m:
            hits.append((i, "pattern-kotlin-regex-api",
                         "对 Pattern 变量 %s 直接用了 .%s；java.util.regex.Pattern 无此 API，改用 .matcher(x) 后再 .find()，或直接 Regex()"
                         % (m.group(1), m.group(2))))
    return hits


BARE_INT_IDENT = re.compile(r"^[A-Za-z_]\w*$")


def _extract_paren_args(line, start_idx):
    """从 line[start_idx] 处的 '(' 开始，返回括号内**平衡**内容与其右括号下标；不平衡返回 (None, -1)。"""
    if start_idx >= len(line) or line[start_idx] != "(":
        return None, -1
    depth = 0
    for j in range(start_idx, len(line)):
        c = line[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return line[start_idx + 1:j], j
    return None, -1


def _split_top_commas(s):
    """按**顶层**逗号切分（忽略嵌套括号内的逗号）。"""
    parts, buf, depth = [], [], 0
    for c in s:
        if c == "(":
            depth += 1
            buf.append(c)
        elif c == ")":
            depth -= 1
            buf.append(c)
        elif c == "," and depth == 0:
            parts.append("".join(buf))
            buf = []
        else:
            buf.append(c)
    parts.append("".join(buf))
    return parts


def scan_create_tensor_shape(path, lines):
    """只对 createTensor(...) 里的 longArrayOf(...) 参数判形状：
    裸 Int 变量（如 MAX_LEN 而非 MAX_LEN.toLong() 也不是 1L）会编译失败。
    不伤及震动波形那种独立 longArrayOf；也不被 createTensor 参数里
    LongBuffer.wrap(ids) 这类嵌套右括号打断（手动配平括号）。"""
    hits = []
    for i, ln in enumerate(lines, 1):
        if "createTensor" not in ln:
            continue
        la = ln.find("longArrayOf(")
        if la < 0:
            continue
        args, _end = _extract_paren_args(ln, la + len("longArrayOf"))
        if args is None:
            continue
        for a in _split_top_commas(args):
            a = a.strip()
            if not a:
                continue
            # 排除：纯数字字面量（会隐式转 Long）、N L 后缀字面量、含 toLong() 的表达式
            if re.fullmatch(r"[-\d]+", a) or re.fullmatch(r"[-\d]+L", a) or "toLong" in a:
                continue
            if BARE_INT_IDENT.fullmatch(a):
                hits.append((i, "tensor-shape-int",
                             "createTensor 的 shape 必须全 Long：%s 若是 Int 变量需 .toLong()，字面量加 L" % a))
                break
    return hits


def scan_file(path):
    findings = []
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.readlines()
    except Exception:
        return findings
    for i, ln in enumerate(lines, 1):
        # 行内注释/文档块粗过滤：跳过明显是注释的行，降低误报
        stripped = ln.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        for name, fix, rx in RULES:
            if rx is None:
                continue
            if rx.search(ln):
                findings.append((i, name, fix))
    findings += scan_pattern_regex_misuse(path, lines)
    findings += scan_create_tensor_shape(path, lines)
    return findings


def run_scan(root):
    bad = []
    for path in iter_kt(root):
        for lineno, name, fix in scan_file(path):
            bad.append((os.path.relpath(path, REPO), lineno, name, fix))
    return bad


# ------------------------------------------------------------------ selftest
BAD_SAMPLES = [
    ("ort-getEnv", "val e = OrtEnvironment.getEnv()"),
    ("tensor-shape-int", "OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, MAX_LEN))"),
    ("tensor-shape-int", "createTensor(env, buf, longArrayOf(1, N))"),
    ("result-foreach-close", "out?.forEach { it.close() }"),
    ("textblock-dot-box", "val x = matchResult.box.left"),
    ("pattern-kotlin-regex-api", "private val P = Pattern.compile(\"x\")\nval m = P.find(text)"),
]
GOOD_SAMPLES = [
    "val e = OrtEnvironment.getEnvironment()",
    "OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, MAX_LEN.toLong()))",
    "runCatching { out?.close() }",
    "inputs.values.forEach { runCatching { it.close() } }",  # 安全的显式关闭，不该误报
    "val xs = matchResult.boxPoint.map { it.x }",
    "val m = Pattern.compile(\"x\").matcher(text); if (m.find()) {}",
    "// 注释里出现 getEnv() 不该被算作命中",
]


def selftest():
    ok = True
    for expect_name, snippet in BAD_SAMPLES:
        lines = snippet.splitlines(keepends=False)
        got = []
        for i, ln in enumerate(lines, 1):
            for name, _fix, rx in RULES:
                if rx and rx.search(ln):
                    got.append(name)
        got += [n for _l, n, _f in scan_pattern_regex_misuse("", lines)]
        got += [n for _l, n, _f in scan_create_tensor_shape("", lines)]
        if expect_name not in got:
            print("❌ 反例未被拦截: %s | %s" % (expect_name, snippet.replace("\n", " ")[:70]))
            ok = False
    for snippet in GOOD_SAMPLES:
        lines = snippet.splitlines(keepends=False)
        got = []
        for i, ln in enumerate(lines, 1):
            s = ln.strip()
            if s.startswith("//"):
                continue
            for name, _fix, rx in RULES:
                if rx and rx.search(ln):
                    got.append(name)
        got += [n for _l, n, _f in scan_pattern_regex_misuse("", lines)]
        got += [n for _l, n, _f in scan_create_tensor_shape("", lines)]
        if got:
            print("❌ 正例被误报: %s -> %s" % (snippet[:60], got))
            ok = False
    if ok:
        print("[selftest] 全部通过：%d 反例均被拦、%d 正例均不误报" % (len(BAD_SAMPLES), len(GOOD_SAMPLES)))
    return ok


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join("client", "app", "src", "main"))
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return 0 if selftest() else 1

    root = args.root if os.path.isabs(args.root) else os.path.join(REPO, args.root)
    bad = run_scan(root)
    if not bad:
        print("[OK] 未发现 Kotlin 第三方 API 误用（ORT/Regex/TextBlock 三类坑）")
        return 0
    print("❌ 发现 %d 处「只有真编译才暴露」的 API 误用：\n" % len(bad))
    for rel, lineno, name, fix in bad:
        print("  %s:%d  [%s]\n        → %s" % (rel, lineno, name, fix))
    print("\n修法见每条箭头后。这些是本地闸门过去拦不住、CI 才炸的类别，务必改完再推。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
