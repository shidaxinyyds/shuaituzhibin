#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
接收者标识符声明对账（无编译环境下的 "Unresolved reference" 闸门）
=====================================================================

## 为什么需要它
`tools/verify_refs.py` 能挡住"资源引用不存在""枚举常量写错"这类错误，但它
**查不到 `this 成员` 与 `局部变量`**。于是下面这种错误可以一路绿灯：

    scope.launch { ... }        // scope 从未声明 —— Unresolved reference: scope

真实案例：`overlay/OverlayWindowManager.kt` 里有 3 处 `scope.launch`，
而 `scope` 从未被声明。它藏在一个 2400 行的文件里，本机又没有 JDK/SDK
无法编译，于是"悬浮窗模块根本编不过"这件事长期没有被发现。

本脚本做一次**保守的**接收者对账：收集文件内所有可能引入名字的位置
（val/var/fun/class/object/import/构造参数/函数参数/lambda 参数/解构/for/catch/枚举常量），
再找出"被当作接收者使用、却不在该集合里"的裸标识符。

## 为什么必须做到零误报
一个会误报的检查比没有检查更糟——它会训练人忽略它（见 `check_reachability_text` 的注释）。
因此这里做了大量降噪：包名根段（com/java/android/...）、Kotlin 与 Android 常用类型、
解构声明与解构 lambda 参数、`::class.java` 等，全部纳入白名单/识别范围。
当前在 `client/app/src/main` 上实跑结果为 **0 项**，可以安全作为 CI 必需项。

## 它不能做什么
* 不做作用域分析：同名遮蔽、跨文件成员、父类成员都按"存在即通过"处理；
* 不校验类型、泛型、重载解析；
* 因此它**只是**一道很窄的闸门，专门拦"名字根本没声明过"这一类。

用法
----
    python tools/check_undeclared_receivers.py
    python tools/check_undeclared_receivers.py --root client/app/src/main

退出码：0 = 通过；1 = 发现问题；2 = 环境问题
"""

import argparse
import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# 被当作"类型名/自有对象"使用的接收者，无需在本文件声明。
WHITELIST = set("""
it this super that self
String Int Long Float Double Boolean Char Byte Short Unit Any Nothing Array List Map Set
MutableList MutableMap MutableSet Pair Triple Regex Result
Math System Log Build R Icons Color Bitmap BitmapFactory Canvas Paint Rect RectF PointF Matrix
Intent Context View ViewGroup TextView Button ImageView EditText CheckBox Spinner LinearLayout
ScrollView FrameLayout AlertDialog Handler Looper WindowManager LayoutParams MotionEvent
SharedPreferences Editor Uri Settings AlarmManager PendingIntent BroadcastReceiver Job
CoroutineScope Dispatchers Delay Duration Random Thread Runnable Exception Throwable
JsonObject JSONObject JSONArray JSON OkHttpClient Request Response MediaType
Toast Gravity Visibility Typeface TypedValue ColorStateList ViewConfiguration Configuration
KeyEvent KeyCharacterMap InputDevice MotionRange
Environment File FileInputStream FileOutputStream InputStream OutputStream BufferedInputStream
ZipOutputStream ZipInputStream ByteArray ByteBuffer ByteOrder Charset Charsets StringBuilder
SimpleDateFormat Date Calendar Locale UUID Arrays Collections Objects Collectors Stream
Entry Iterator ValueOf Companion INSTANCE
FloatArray IntArray DoubleArray BooleanArray ByteArray CharArray LongArray ShortArray
entries resources list map set array items values keys
""".split())

# 包名根段：`com.stzb.assistant.X`、`kotlinx.coroutines.Y` 这类全限定名会被正则切出首段。
PKG_ROOTS = {"com", "org", "java", "javax", "android", "androidx",
             "kotlin", "kotlinx", "net", "io", "sun", "ai", "org"}


def strip_literals(text):
    """剥离注释与字符串字面量，避免在注释/文案里误报。"""
    text = re.sub(r"/\*[\s\S]*?\*/", " ", text)
    text = re.sub(r"//[^\n]*", " ", text)
    text = re.sub(r'"""(?:[\s\S]*?)"""', " ", text)
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    return text


def collect_names(raw):
    """收集该文件内所有可能引入标识符的位置。"""
    names = set()
    for m in re.finditer(r"^import\s+([\w.]+)", raw, re.M):
        names.add(m.group(1).split(".")[-1])
    for m in re.finditer(r"\b(?:val|var)\s+([A-Za-z_]\w*)", raw):
        names.add(m.group(1))
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(", raw):
        names.add(m.group(1))
    for m in re.finditer(r"\b(?:class|object|interface|typealias)\s+([A-Za-z_]\w*)", raw):
        names.add(m.group(1))
    for m in re.finditer(r"[\(,]\s*([A-Za-z_]\w*)\s*:", raw):
        names.add(m.group(1))
    for m in re.finditer(r"(\w+)\s*->", raw):
        names.add(m.group(1))
    # 解构 lambda / for / 声明：val (a, b) = ...  for ((a, b) in ...)
    for m in re.finditer(r"\(([^)]*)\)\s*(?:,\s*\w+\s*)?->", raw):
        for part in m.group(1).split(","):
            p = part.strip()
            if re.fullmatch(r"[A-Za-z_]\w*", p):
                names.add(p)
    for m in re.finditer(r"\bfor\s*\(\s*\(([^)]*)\)", raw):
        for part in m.group(1).split(","):
            p = part.strip()
            if re.fullmatch(r"[A-Za-z_]\w*", p):
                names.add(p)
    for m in re.finditer(r"\b(?:val|var)\s*\(([^)]*)\)\s*=", raw):
        for part in m.group(1).split(","):
            p = part.strip()
            if re.fullmatch(r"[A-Za-z_]\w*", p):
                names.add(p)
    for m in re.finditer(r"\bfor\s*\(\s*([A-Za-z_]\w*)", raw):
        names.add(m.group(1))
    for m in re.finditer(r"\bcatch\s*\(\s*([A-Za-z_]\w*)", raw):
        names.add(m.group(1))
    for m in re.finditer(r"^\s+([A-Z][A-Z0-9_]*)\s*(?:[,;(]|$)", raw, re.M):
        names.add(m.group(1))
    for m in re.finditer(r"\bwhen\s*\(\s*val\s+([A-Za-z_]\w*)", raw):
        names.add(m.group(1))
    for m in re.finditer(r"([A-Za-z_]\w*)@", raw):
        names.add(m.group(1))
    return names


def check_text(display_name, raw):
    """返回问题描述列表（与文件无关，便于单测）。"""
    text = strip_literals(raw)
    lines = text.split("\n")
    names = collect_names(raw) | WHITELIST
    names |= set(os.path.splitext(os.path.basename(display_name))[0].split())

    problems = []
    seen = set()
    for m in re.finditer(r"(?<![\w.$?])([a-z_]\w{1,})\s*\.\s*([A-Za-z_]\w*)", text):
        base = m.group(1)
        if base in names or base == "class" or base in PKG_ROOTS:
            continue
        line = text[:m.start()].count("\n") + 1
        src_line = lines[line - 1].strip()
        if src_line.startswith("package ") or src_line.startswith("import "):
            continue
        if base in seen:
            continue
        seen.add(base)
        problems.append(
            "第 %d 行使用了未在本文件声明的接收者 `%s`（形如 `%s.%s`）——"
            "在没有编译环境时这属于 `Unresolved reference`。" % (line, base, base, m.group(2))
        )
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

    total = 0
    for f in files:
        try:
            with open(f, "r", encoding="utf-8", errors="replace") as fh:
                raw = fh.read()
        except Exception as e:
            print("读取失败 %s: %s" % (f, e), file=sys.stderr)
            return 2
        rel = os.path.relpath(f, root).replace("\\", "/")
        for p in check_text(rel, raw):
            total += 1
            print("ERROR | %s | %s" % (rel, p))

    print("-" * 72)
    print("已扫描 %d 个 Kotlin 文件，未声明接收者 %d 处" % (len(files), total))
    if total:
        print("结论: 存在疑似 Unresolved reference，请先修复再提交。")
        return 1
    print("结论: 通过（未发现未声明的接收者标识符）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
