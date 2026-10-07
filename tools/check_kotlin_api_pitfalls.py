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
  6. `ai.onnxruntime.Value`                     —— 本绑定无此类；Result 索引出来是 `OnnxValue`

同一类别的三个纯语法坑（CI Run #53 真实栽掉的三条，同属“只有真编译才暴露”）：

  7. `for (((s, e), budget) in cases)`          —— for 头部**不支持嵌套解构**，直接
                                                 报 `Expecting a name`
  8. `"首=$d0ms 中=$dMidms"`                    —— Kotlin 把 `$d0ms` 整体当成标识符
                                                 `d0ms`，报 `Unresolved reference`；
                                                 必须写 `${d0}ms`
  9. `stroke.endMs`                             —— StrokeDescription 没有时长 getter，
                                                 续笔延迟需自己累加时间游标

CI Run #58（推送 21f54e3 后真编译才暴露的三条，本闸门据此再加三条规则）：

 10. `"$baseDir/$gameId()"`                     —— 模板只吃 `$gameId` 这个**名字**，
                                                 名字后面紧跟的 `()` 不会被求值；而
                                                 `gameId` 是 `fun` 不是 `val`，报
                                                 `Function invocation 'gameId()' expected`。
                                                 必须写 `${gameId()}`
 11. `ConcurrentHashMap<String, Boolean>.add(k)` —— Map 没有 `add()`（那是 Set 的 API）；
                                                 去重登记要用 `putIfAbsent(k, true) == null`
 12. `AccessibilityEvent.TYPE_WINDOW_ACTIVE`     —— **这个常量不存在**（Android 只有
                                                 TYPE_WINDOW_STATE_CHANGED / _CONTENT_CHANGED /
                                                 _OBJECT_STATE_CHANGED 等），报 `Unresolved reference`

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
    ("ort-value-vs-onnxvalue",
     "本 ORT 绑定里没有 ai.onnxruntime.Value；Result 索引出来的是 ai.onnxruntime.OnnxValue",
     re.compile(r"ai\.onnxruntime\s*\.\s*Value\b(?!\w)")),
    ("pattern-kotlin-regex-api",
     "java.util.regex.Pattern 没有 .find()/.findAll()/.groupValues（那是 Kotlin Regex 的 MatchResult）；改用 .matcher(x).find() 或 Regex()",
     None),  # 需要两遍扫描，见下
    ("strokeds-hidden-duration",
     "GestureDescription.StrokeDescription 没有公开的 endMs/durationMs getter；续笔的 startDelay 只能自己维护一条时间游标累加",
     re.compile(r"\b(?:previous|prevStroke|lastStroke|previousStroke|stroke|curStroke|firstStroke)\w*\s*\??\.\s*(endMs|durationMs)\b")),
    ("for-nested-destructuring",
     "for 头部不支持嵌套解构（Kotlin 只认 for ((a, b) in …)）；先解外层、循环体内再 val (s, e) = pair",
     re.compile(r"for\s*\(\s*\(\s*\(")),
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


# ------------------------------------------------- 字符串模板漏写花括号（CI #53）
#
# `"$d0ms"` 在 Kotlin 里不是“d0 加上字面量 ms”，而是一个名叫 `d0ms` 的引用。
# 这类坑靠读代码几乎看不出来（中文提示串里尤其隐蔽），但真编译必炸。
# 判定方式（高精度）：模板名本身在**整个文件的代码区**里从未出现，
# 但它的某个前缀是被 val/var/fun/class **声明过**的名字 —— 那就是漏写了 ${}。

_TPL_NAME = re.compile(r"\$(\{)?([A-Za-z_][A-Za-z0-9_]*)(\})?")
_DECL_NAME = re.compile(r"\b(?:val|var|fun|class)\s+([A-Za-z_][A-Za-z0-9_]*)")
_DECL_DESTRUCT = re.compile(r"\bval\s*\(([^)]*)\)")


def strip_comments(text):
    """去掉 // 与 /* */ 注释，但**保留字符串字面量**（模板就住在字符串里）。

    必须自己写状态机：正则扫注释会不小心吃掉 `"http://…"` 里的双斜杠。
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            out.append(" ")
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                j += 1
            out.append(text[i:min(j + 1, n)])
            i = j + 1
            continue
        if c == "'":
            # 只有**真字符字面量**（'a' / '\n'）才当字面量吃掉。Kotlin 里裸单引号太常见
            # （中文注释里的撇号、`it's` 这样的字符串内容），旧写法从这里开始两位一跳，
            # 会把后面大段代码当字面量吞掉 —— 那会让所有整文件规则静默漏判。
            if i + 2 < n and text[i + 1] == "\\":
                j = i + 4 if text[i + 3] == "'" else i + 1
            elif i + 2 < n and text[i + 2] == "'":
                j = i + 2
            else:
                out.append(c)
                i += 1
                continue
            out.append(text[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def scan_template_braces(path, text):
    """找出 `$name` 被后缀吞掉、而真正声明的是 `name` 前缀的情况。"""
    code = strip_comments(text)
    outside = re.sub(r'"(?:\\.|[^"\\])*"', " ", code)
    seen_idents = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", outside))
    declared = set(_DECL_NAME.findall(outside))
    for grp in _DECL_DESTRUCT.findall(outside):
        for part in grp.split(","):
            part = part.strip()
            if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", part):
                declared.add(part)
    hits = []
    for m in _TPL_NAME.finditer(code):
        braced, name = m.group(1), m.group(2)
        if braced:            # ${x} 形式已显式定界，不可能被后缀吞掉
            continue
        if name in seen_idents:   # 这个名字真的存在，正常
            continue
        prefix = [name[:k] for k in range(len(name) - 1, 1, -1) if name[:k] in declared]
        if prefix:
            lineno = code[:m.start()].count("\n") + 1
            best = max(prefix, key=len)
            hits.append((lineno, "template-missing-braces",
                         "字符串里的 $%s 会被当成标识符 %s（未声明）；本文件声明的是 %s，应写 ${%s}"
                         % (name, name, best, best)))
    return hits


# ------------------------------------------------- CI Run #58 新增的三条规则
#
# 共同点：本地所有闸门都绿，`compileReleaseKotlin` 才炸。都属于"文本级就能高精度判定"。

# `$name(` —— 模板只吃名字，后面的 `()` 不会被求值（`"$x/$f()"` 里的 f() 就是死代码 + 未解析引用）
_TPL_CALL = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)\(")

_MAP_DECL = re.compile(r"\b(?:val|var)\s+(\w+)\s*:\s*(?:[\w.]+\.)?(?:ConcurrentHashMap|HashMap|LinkedHashMap|TreeMap|IdentityHashMap|ArrayMap|SparseArray)\b")
# Map 上不存在的方法名（Set / List 才有的 API）。
# 只放**确定**没有的：`add` 一定不在 Map 上；`addAll` 也不（`Map` 的批量添加是 `putAll`）。
# removeAll/retainAll 我没有十足把握（Kotlin 的 Map 扩展函数生态太宽），**故意不判**——
# 这条规则的用途是拦"把 Set 的 API 套到 Map 上"这一类，不需要靠猜来扩大覆盖面。
_MAP_ABSENT = ("add", "addAll")

# AccessibilityEvent 的真实常量表（对照 Android SDK；表外一律视为幻觉）。
# 名单本身就是这条规则的全部效力来源，所以它自己必须是干净的。
ACCESSIBILITY_EVENT_TYPES = {
    "TYPE_ANNOUNCEMENT", "TYPE_ASSISTANCE_ACCESSIBILITY", "TYPE_ASSISTANCE_NAVIGATION_GUIDANCE",
    "TYPE_ASSISTANCE_SUGGESTION", "TYPE_BROADCAST", "TYPE_VIEW_ACCESSIBILITY_FOCUSED",
    "TYPE_VIEW_FOCUSED", "TYPE_VIEW_HOVER_ENTER", "TYPE_VIEW_HOVER_EXIT", "TYPE_VIEW_SCROLLED",
    "TYPE_VIEW_SELECTED", "TYPE_VIEW_TEXT_CHANGED", "TYPE_WINDOW_CONTENT_CHANGED",
    "TYPE_WINDOW_STATE_CHANGED", "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY",
    "TYPE_GESTURE_DETECTION_BEGIN", "TYPE_GESTURE_DETECTION_CONTINUE", "TYPE_GESTURE_DETECTION_END",
    "TYPE_TOUCH_EXPLORATION_GESTURE_BEGIN", "TYPE_TOUCH_EXPLORATION_GESTURE_END",
}

_AE_TYPE = re.compile(r"AccessibilityEvent\s*\.\s*(TYPE_[A-Z_]+)")


def _string_spans(code):
    """返回 (起点, 终点) 列表：每个**双引号字符串字面量**在 code 中的区间。

    模板只存在于字符串里，规则必须只在字符串内部找，否则 `if (x) {` 这种
    普通代码会被 `$[A-Za-z_]\w*(` 误伤（这条规则自己的反例差点把它写成噪声源）。
    """
    spans, i, n = [], 0, len(code)
    while i < n:
        if code[i] != '"':
            i += 1
            continue
        j = i + 1
        while j < n:
            if code[j] == "\\":
                j += 2
                continue
            if code[j] == '"':
                break
            j += 1
        spans.append((i, min(j + 1, n)))
        i = j + 1
    return spans


def _value_names(outside):
    """本文件里**能当值用**的名字：val/var 声明、函数参数、解构声明。

    规则 10 的分界正在这里：`$morale(` 里的 morale 若是 `val morale`，那是
    "值 + 字面括号"，完全合法；若是 `fun morale()`，模板只吃名字、括号不求值，
    编译期报 `Function invocation 'morale()' expected`。所以必须区分两者，
    否则这条规则会把一堆正常日志行喊成缺陷（第一版就这么误报过）。
    """
    names = set(re.findall(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)", outside))
    for m in re.finditer(r"\b(?:fun|constructor)\s*(\w*)\s*\(([^)]*)\)", outside):
        for part in m.group(2).split(","):
            part = part.strip()
            am = re.match(r"([A-Za-z_][A-Za-z0-9_]*)\s*:", part)
            if am:
                names.add(am.group(1))
    for grp in _DECL_DESTRUCT.findall(outside):
        for part in grp.split(","):
            part = part.strip().split(":")[0].strip()
            if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", part):
                names.add(part)
    return names


def scan_template_call_paren(text):
    """字符串模板里 `$fun()`：Kotlin 只解析 `$fun` 这个名字，括号留在文本里。

    与 scan_template_braces 的区别：那条管"名字被后缀吞掉"（$d0ms），
    这条管"名字本身是个**函数**"。命中条件是字符串内部出现 `$ident(`，
    且 ident 在本文件里**只**是 fun（或根本没声明过）、不是任何 val/var/参数。
    """
    code = strip_comments(text)
    outside = re.sub(r'"(?:\\.|[^"\\])*"', " ", code)
    value_names = _value_names(outside)
    fun_names = set(re.findall(r"\bfun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", outside))
    hits = []
    for s, e in _string_spans(code):
        body = code[s:e]
        for m in _TPL_CALL.finditer(body):
            name = m.group(1)
            if name in value_names:
                continue          # 值是合法的：`"$morale(评级)"` 这类日志写法
            if name not in fun_names:
                continue          # 本文件既无 val 也无 fun：不确定，宁可不报（规则只求不误报）
            if body[:m.start()].rstrip().endswith("${"):
                continue          # ${…} 内部：那里的 $ 是嵌套模板起点，不是被截断的名字
            lineno = code[:s + m.start()].count("\n") + 1
            hits.append((lineno, "template-call-needs-braces",
                         "字符串里的 $%s( 不会被求值：%s 是 fun 不是值，模板只吃名字；"
                         "要调用函数必须写 ${%s()}" % (name, name, name)))
    return hits


def scan_map_add(text):
    """声明成 Map 的变量上调 add()/addAll() 等 Set 才有的方法。"""
    code = strip_comments(text)
    names = set(_MAP_DECL.findall(code))
    # 上面那条要求显式类型标注（`val x: HashMap<…>`），而工程里更常见的是推断声明
    # （`private val warned = ConcurrentHashMap<String, Boolean>()`），所以补第二遍。
    # 前缀写成 (?:[\w.]+\.)? 而不是 java\.util\.：全限定名是 `java.util.concurrent.X`，
    # 只吃一层包名的写法会让这条规则对**真实写法**失效（自测反例第一次就没被拦住）。
    for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*=\s*(?:[\w.]+\.)?(?:ConcurrentHashMap|HashMap|LinkedHashMap|TreeMap|IdentityHashMap|ArrayMap)\s*[<(]", code):
        names.add(m.group(1))
    if not names:
        return []
    # 方法名只列 Map 上**不存在**的那四个，所以不需要排除 putIfAbsent/containsKey。
    rx = re.compile(r"\b(" + "|".join(re.escape(n) for n in sorted(names)) +
                    r")\s*\??\.\s*(" + "|".join(_MAP_ABSENT) + r")\s*\(")
    hits = []
    for m in rx.finditer(code):
        lineno = code[:m.start()].count("\n") + 1
        hits.append((lineno, "map-has-no-add",
                     "%s 是 Map，没有 .%s()（那是 Set/List 的 API）；"
                     "批量写入用 putAll()，去重登记用 putIfAbsent(k, true) == null"
                     % (m.group(1), m.group(2))))
    return hits


def scan_hallucinated_constants(text):
    """AccessibilityEvent.TYPE_* 必须落在真实常量表里。

    这条规则的形状是"白名单"而不是"黑名单"：幻觉常量**没有任何可识别的错误特征**
    （看着比真常量还合理），只有对照 SDK 名单才拦得住。
    """
    code = strip_comments(text)
    hits = []
    for m in _AE_TYPE.finditer(code):
        if m.group(1) not in ACCESSIBILITY_EVENT_TYPES:
            lineno = code[:m.start()].count("\n") + 1
            hits.append((lineno, "accessibility-event-type-not-a-thing",
                         "AccessibilityEvent.%s 不存在；可回答\u201c谁是前台\u201d的只有 "
                         "TYPE_WINDOW_STATE_CHANGED（其余见脚本内常量表）" % m.group(1)))
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
    findings += scan_template_braces(path, "".join(lines))
    text = "".join(lines)
    findings += scan_template_call_paren(text)
    findings += scan_map_add(text)
    findings += scan_hallucinated_constants(text)
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
    ("ort-value-vs-onnxvalue", "private fun headVec(v: ai.onnxruntime.Value?): FloatArray"),
    ("pattern-kotlin-regex-api", "private val P = Pattern.compile(\"x\")\nval m = P.find(text)"),
    ("for-nested-destructuring", "for (((s, e), budget) in cases) { plan(s, e, budget) }"),
    ("strokeds-hidden-duration", "val startDelay = (seg.startMs - previous.endMs).coerceAtLeast(0L)"),
    ("strokeds-hidden-duration", "val totalDur = stroke?.durationMs ?: 0L"),
]

# 模板漏花括号需要“声明 + 使用”跨行才能判，单独走 scan_template_braces
# （这两条就是 CI Run #53 真正炸掉的原文）
BAD_TEXT = [
    "fun f() {\n    val d0 = 12L\n    problems += \"首=$d0ms 中=3ms\"\n}",
    "fun f() {\n    val dMid = 3L\n    val dLast = 4L\n    problems += \"中=$dMidms 尾=$dLastms\"\n}",
]
GOOD_TEXT = [
    "fun f() {\n    val d0 = 12L\n    problems += \"首=${d0}ms\"\n}",
    # packageName 是父类属性，本文件没有 val package……不该误报
    "fun f() {\n    Log.d(TAG, \"pkg=$packageName\")\n}",
    # $total 后面的中文不是标识符字符，Kotlin 能正确截断，不该误报
    "fun f() {\n    val total = 9\n    Log.d(TAG, \"总计$total个\")\n}",
    "// 注释里写 $d0ms 不算命中：\nfun f() {\n    val d0 = 1L\n    Log.d(TAG, \"ok $d0\")\n}",
]
# CI Run #58 真炸的三条：它们**绕过了上一版闸门的全部规则**（模板花括号那条只管
# "$d0ms" 这种"名字被后缀吞掉"，管不了 "$gameId()" 这种"名字本身是函数"）。
# 因此这三条反例是对闸门本身的取证：修完闸门必须能被拦住。
BAD_TEXT += [
    # 1) 模板里 $gameId() —— 报 Function invocation 'gameId()' expected
    "object S {\n    fun gameId(): String = \"stzb\"\n"
    "    fun dirs(base: String): String = \"$base/$gameId()\"\n}",
    # 2) Map 上调 Set 的 add() —— 报 Unresolved reference: add
    "object S {\n    private val warned = java.util.concurrent.ConcurrentHashMap<String, Boolean>()\n"
    "    fun once(k: String): Boolean = warned.add(k)\n}",
    # 3) 幻觉常量 —— AccessibilityEvent 没有 TYPE_WINDOW_ACTIVE
    "class S : AccessibilityService() {\n"
    "    override fun onAccessibilityEvent(e: AccessibilityEvent?) {\n"
    "        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_ACTIVE) return\n"
    "        Log.d(TAG, e.packageName?.toString() ?: \"\")\n    }\n}",
]

GOOD_TEXT += [
    # 修好的三条写法，必须放行（否则闸门就成了只会喊狼来了的噪声源）
    "object S {\n    fun gameId(): String = \"stzb\"\n"
    "    fun dirs(base: String): String = \"$base/${gameId()}\"\n}",
    "object S {\n    private val warned = java.util.concurrent.ConcurrentHashMap<String, Boolean>()\n"
    "    fun once(k: String): Boolean = warned.putIfAbsent(k, true) == null\n}",
    "class S : AccessibilityService() {\n"
    "    override fun onAccessibilityEvent(e: AccessibilityEvent?) {\n"
    "        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return\n"
    "        Log.d(TAG, e.packageName?.toString() ?: \"\")\n    }\n}",
    # List 上 add() 是合法的，不能被 Map 规则误伤
    "object S {\n    val out = ArrayList<String>()\n    fun add(x: String) { out.add(x) }\n}",
    "object S {\n    val set = mutableSetOf<String>()\n    fun f(x: String) { set.add(x) }\n}",
    # 字符串里出现 "$5(含税)"：$ 后面不是标识符起点，不该命中模板规则
    "object S {\n    val price = \"共 $5(含税)\"\n}",
    # 反向控制：普通代码里的 `if (x) {` 与函数声明 `fun add(` 都不该被字符串规则误伤
    "class S : AccessibilityService() {\n"
    "    override fun onAccessibilityEvent(e: AccessibilityEvent?) {\n"
    "        if (e == null) { return }\n"
    "        val list = mutableListOf<String>()\n"
    "        fun add(x: String) { list.add(x) }\n"
    "        add(\"ok\")\n    }\n}",
]

GOOD_SAMPLES = [
    "val e = OrtEnvironment.getEnvironment()",
    "private fun headVec(v: ai.onnxruntime.OnnxValue?): FloatArray",
    "OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1L, MAX_LEN.toLong()))",
    "runCatching { out?.close() }",
    "inputs.values.forEach { runCatching { it.close() } }",  # 安全的显式关闭，不该误报
    "val xs = matchResult.boxPoint.map { it.x }",
    "val m = Pattern.compile(\"x\").matcher(text); if (m.find()) {}",
    "// 注释里出现 getEnv() 不该被算作命中",
]


def _multi_scan(text):
    """新三条规则共用同一份整文件文本，一次跑完拿到命中的规则名。"""
    return ([n for _l, n, _f in scan_template_braces("", text)] +
            [n for _l, n, _f in scan_template_call_paren(text)] +
            [n for _l, n, _f in scan_map_add(text)] +
            [n for _l, n, _f in scan_hallucinated_constants(text)])


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
        got += [n for _l, n, _f in scan_template_braces("", snippet)]
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
        got += [n for _l, n, _f in scan_template_braces("", snippet)]
        if got:
            print("❌ 正例被误报: %s -> %s" % (snippet[:60], got))
            ok = False
    for snippet in GOOD_TEXT:
        got = _multi_scan(snippet)
        if got:
            print("❌ 正例被误报: %s -> %s" % (snippet.replace("\n", " ")[:60], got))
            ok = False
    for text in BAD_TEXT:
        if not _multi_scan(text):
            print("❌ 反例未被拦截(整文件级): %s" % text.replace("\n", " ")[:70])
            ok = False
    if ok:
        print("[selftest] 全部通过：%d+%d 反例均被拦、%d+%d 正例均不误报"
              % (len(BAD_SAMPLES), len(BAD_TEXT), len(GOOD_SAMPLES), len(GOOD_TEXT)))
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
        print("[OK] 未发现「只有真编译才暴露」的误用（ORT/Regex/TextBlock API + 三类语法坑）")
        return 0
    print("❌ 发现 %d 处「只有真编译才暴露」的 API 误用 / 语法坑：\n" % len(bad))
    for rel, lineno, name, fix in bad:
        print("  %s:%d  [%s]\n        → %s" % (rel, lineno, name, fix))
    print("\n修法见每条箭头后。这些是本地闸门过去拦不住、CI 才炸的类别，务必改完再推。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
