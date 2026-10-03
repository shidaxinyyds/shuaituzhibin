#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
率土全能管家 · Android 资源/引用一致性静态校验器
=================================================

用途
----
本机没有 JDK / Android SDK / NDK，无法执行 `gradlew assembleDebug` 做真正的编译验证。
Android 工程在改布局后最常见的编译失败原因是 **资源引用对不上**：
  * Kotlin 里 `R.id.tvFoo` 引用了 XML 中不存在的 id；
  * Kotlin 里 `R.color.x` / `R.string.x` / `R.drawable.x` / `R.layout.x` 引用了未声明的资源；
  * XML 里 `@color/x` / `@drawable/x` / `@style/x` 引用了未声明的资源；
  * 同一个 layout 内出现重复 id（AAPT 报 duplicate id）。

本脚本把这四类问题全部静态对账，作为无编译环境下的回归闸门。
它不能替代真实编译（语法、类型、API 级别仍需编译验证），但能挡住绝大多数"改崩工程"的低级错误。

用法
----
    python tools/verify_refs.py                 # 默认校验 client/app/src/main
    python tools/verify_refs.py --root <dir>
    python tools/verify_refs.py --json out.json

退出码
------
    0 = 全部通过
    1 = 发现错误（ERROR）
    2 = 脚本自身/环境问题
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Kotlin/Java 中 R.<type>.<name> 的引用。
# (?<![\w.]) 排除 android.R.xxx / com.foo.R.xxx 这类外部包引用。
R_REF_RE = re.compile(r"(?<![\w.])R\.(id|color|string|drawable|layout|style|dimen|integer|bool|array|xml|mipmap|anim|font)\." r"([A-Za-z_][A-Za-z0-9_]*)")
# XML 中 @<type>/<name> 的引用（排除 @+id/ 定义、@android:、@*android:）。
# 名称允许含点，因为 Android 样式名的层级用点分隔，如 @style/Btn.Primary。
XML_REF_RE = re.compile(r"@(?!\+|android:|\*android:)(id|color|string|drawable|layout|style|dimen|integer|bool|array|xml|mipmap|anim|font)/([A-Za-z_][A-Za-z0-9_.]*)")
# XML 中 @+id/<name> 的定义
XML_ID_DEF_RE = re.compile(r"@\+id/([A-Za-z_][A-Za-z0-9_]*)")
# values 中的 <type name="x">（样式名允许含点）
VALUES_DEF_RE = re.compile(r"<\s*(color|string|style|dimen|integer|bool|array|string-array|integer-array|attr|declare-styleable)\b[^>]*?\bname\s*=\s*\"([^\"]+)\"")


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.info = []

    def error(self, where, msg):
        self.errors.append({"where": where, "message": msg})

    def warn(self, where, msg):
        self.warnings.append({"where": where, "message": msg})

    def note(self, msg):
        self.info.append(msg)


def read_text(path):
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


# Kotlin 注释剥离：用于"按 token 扫描"类检查。
# 必须先剥注释，否则注释里出现的示例调用（例如说明"原先写成 foo(x = 1)"）
# 会被当成真实代码，制造假阳性。
_KT_BLOCK_COMMENT = re.compile(r"/\*[\s\S]*?\*/")
_KT_LINE_COMMENT = re.compile(r"//[^\n]*")


def strip_c_comments(text):
    return _KT_LINE_COMMENT.sub("", _KT_BLOCK_COMMENT.sub("", text))


def mask_kotlin_strings(text):
    """
    把字符串字面量的**内容**替换成空格，但**保留引号本身**。

    为什么必须保留引号：实参个数是按"顶层逗号切分后有多少非空项"来数的。
    如果把引号也一起抹掉，`logWarn("文字")` 会变成 `logWarn(      )`，
    那个实参就被数成 0 个——本检查第一版正是这样一次报出 244 项假阳性
    （"logWarn 需要至少 1 个实参，实际 0 个"）。
    保留引号后，字面量仍是一个非空项，计数正确。
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i + 3, max(i + 3, j - 3)):
                if out[k] != "\n":
                    out[k] = " "
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                if text[j] == "\n":
                    break
                j += 1
            for k in range(i + 1, max(i + 1, j - 1)):
                if out[k] != "\n":
                    out[k] = " "
            i = j
            continue
        i += 1
    return "".join(out)


def sanitize_kotlin(text):
    """注释 + 字符串都屏蔽掉，只留下"可当代码解读"的骨架。"""
    return mask_kotlin_strings(strip_c_comments(text))


# 声明前缀：用于把"函数/类的声明"从调用点里排除掉。
# 不排除的话，`fun humanDelay(minMs: Long = 200, ...)` 里的 `Long =` 会被当成具名参数。
_DECL_PREFIX_RE = re.compile(
    r"(?:\bfun\b|\bclass\b|\binterface\b|\bobject\b|\bconstructor\b)\s*(?:<[^>]*>\s*)?$"
)

# Any / 集合类成员，以及**必然与库撞名**的方法名。
#
# 为什么需要这一份：实参个数检查靠"名字能唯一解析到工程内某个声明"来工作，
# 而 `java.util.Calendar.getInstance()`、`okhttp Call.execute()`、
# `SimpleDateFormat.format()` 这类调用**也带限定名**，
# 因此"带限定名"这条挡不住它们——它们会去匹配工程里同名的函数，
# 报出一堆"实参个数不对"的假阳性（实测报出 5 项，全部是这一类）。
# 这些名字本身也说明其语义是通用的，排除它们不会损失有价值的覆盖。
_STDLIB_MEMBER_SKIP = {
    "equals", "hashCode", "toString", "compareTo", "copy", "component1", "component2",
    "iterator", "invoke", "get", "set", "contains", "compareValues",
    "getInstance", "newInstance", "execute", "format", "valueOf", "parse",
    "add", "put", "remove", "clear", "size", "isEmpty", "indexOf",
    "substring", "length", "join", "split", "trim", "replace",
}


def _looks_like_declaration(text, idx):
    return _DECL_PREFIX_RE.search(text[max(0, idx - 80):idx]) is not None


def walk_files(root, exts):
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", ".git")]
        for name in filenames:
            if os.path.splitext(name)[1].lower() in exts:
                yield os.path.join(dirpath, name)


def collect_resources(res_dir, rep):
    """收集 res/ 下真正声明的资源符号。"""
    declared = {k: set() for k in
                ("id", "color", "string", "drawable", "layout", "style", "dimen",
                 "integer", "bool", "array", "xml", "mipmap", "anim", "font")}

    # 1) res/values*/**.xml 里的具名资源
    for path in walk_files(res_dir, {".xml"}):
        rel = os.path.relpath(path, res_dir).replace("\\", "/")
        if not rel.startswith("values"):
            continue
        text = read_text(path)
        for m in VALUES_DEF_RE.finditer(text):
            kind, name = m.group(1), m.group(2)
            key = "array" if kind.endswith("array") else kind
            if key in declared:
                declared[key].add(name)
            elif kind in ("attr", "declare-styleable"):
                pass  # 自定义属性，不参与 R.<type> 引用对账

    # 2) 目录型资源：drawable-*/ mipmap-*/ layout-*/ xml/ anim-*/ font-*
    dir_kinds = {"drawable": "drawable", "mipmap": "mipmap", "layout": "layout",
                 "xml": "xml", "anim": "anim", "font": "font"}
    for entry in sorted(os.listdir(res_dir)):
        full = os.path.join(res_dir, entry)
        if not os.path.isdir(full):
            continue
        for prefix, kind in dir_kinds.items():
            if entry == prefix or entry.startswith(prefix + "-"):
                for f in os.listdir(full):
                    stem, ext = os.path.splitext(f)
                    if ext.lower() in (".xml", ".png", ".jpg", ".jpeg", ".webp", ".9.png", ".ttf", ".otf"):
                        if stem.endswith(".9"):
                            stem = stem[:-2]
                        declared[kind].add(stem)
                break

    # 3) 从 layout/*.xml 里收集 @+id
    for path in walk_files(res_dir, {".xml"}):
        rel = os.path.relpath(path, res_dir).replace("\\", "/")
        if not rel.startswith("layout"):
            continue
        for m in XML_ID_DEF_RE.finditer(read_text(path)):
            declared["id"].add(m.group(1))

    rep.note("声明资源统计: " + ", ".join(
        "%s=%d" % (k, len(v)) for k, v in sorted(declared.items()) if v))
    return declared


def check_xml(path, declared, rep):
    """校验单个 XML：格式合法、引用存在、同文件内 id 不重复。"""
    rel = path
    try:
        ET.parse(path)
    except ET.ParseError as exc:
        rep.error(rel, "XML 解析失败（格式非法）: %s" % exc)
        return

    text = read_text(path)

    # XML 引用对账
    for m in XML_REF_RE.finditer(text):
        kind, name = m.group(1), m.group(2)
        if kind in ("id",):  # @id/x 是引用已声明 id
            if name not in declared["id"]:
                rep.error(rel, "引用了未声明的 id: @id/%s" % name)
            continue
        if name == "*":
            continue
        if name not in declared.get(kind, set()):
            rep.error(rel, "引用了未声明的资源: @%s/%s" % (kind, name))

    # 同一 layout 内 id 重复
    if os.sep + "layout" + os.sep in path or "/layout" in path.replace("\\", "/"):
        seen = {}
        for m in XML_ID_DEF_RE.finditer(text):
            n = m.group(1)
            line = text.count("\n", 0, m.start()) + 1
            if n in seen:
                rep.error(rel, "重复定义 id @+id/%s（首次在第 %d 行，本次第 %d 行）"
                          % (n, seen[n], line))
            else:
                seen[n] = line


def check_kotlin(path, declared, rep):
    """校验 Kotlin/Java 中的 R.<type>.<name> 引用。"""
    rel = path
    text = read_text(path)

    # 括号/花括号平衡的粗检，用于发现编辑导致的截断
    for open_ch, close_ch, label in (("{", "}", "花括号"), ("(", ")", "圆括号")):
        # 去掉字符串字面量与注释后再计数，避免误报
        stripped = re.sub(r'"""[\s\S]*?"""', '""', text)
        stripped = re.sub(r'"(\\.|[^"\\])*"', '""', stripped)
        stripped = re.sub(r"//[^\n]*", "", stripped)
        stripped = re.sub(r"/\*[\s\S]*?\*/", "", stripped)
        if stripped.count(open_ch) != stripped.count(close_ch):
            rep.error(rel, "%s不平衡: %d 个 '%s' vs %d 个 '%s'（疑似编辑截断）"
                      % (label, stripped.count(open_ch), open_ch,
                         stripped.count(close_ch), close_ch))

    for m in R_REF_RE.finditer(text):
        kind, name = m.group(1), m.group(2)
        pool = declared.get(kind)
        if pool is None:
            continue
        if name not in pool:
            line = text.count("\n", 0, m.start()) + 1
            rep.error(rel, "第 %d 行引用了未声明的资源: R.%s.%s" % (line, kind, name))


# ---------------------------------------------------------------------------
# 领域不变量
#
# 本项目踩过一个具体而隐蔽的坑：`TacticalPipeline` 的四个 startXxx() 各自维护
# `activeTaskType`/`currentJob`，但**任务结束后从不复位**，
# 于是 `currentTaskType` 永久非 null —— 无人托管据此判断"是否有任务在跑"，
# 结果跑完第一个任务后就永远认为还在忙，再也不下发第二个。
#
# 这类"状态被绕开统一入口去改"的错误，静态是可以拦住的：
# 规定这些赋值只能出现在指定的受管函数里。
# ---------------------------------------------------------------------------

DOMAIN_INVARIANTS = [
    {
        "file": "tactics/TacticalPipeline.kt",
        "assignments": ["activeTaskType", "currentJob"],
        "allowed_functions": {"launchTask", "stopCurrentTask", "stopAll"},
        "why": "任务状态必须在统一入口 launchTask / stopCurrentTask / stopAll 内维护；"
               "在别处赋值会绕过完成回调，使 currentTaskType 永不复位，"
               "导致「无人托管」跑完第一个任务后永远认为还有任务在跑。",
    },
    {
        "file": "tactics/AutoPilot.kt",
        "assignments": ["raidDispatched", "raidLastStartAtMs", "pavingSignature", "siegeSignature"],
        "allowed_functions": {"resetRuntimeState", "ensureRaidPatrol", "dispatchWhenIdle"},
        "why": "托管的运行期状态必须集中在 resetRuntimeState / ensureRaidPatrol / dispatchWhenIdle "
               "里维护。散落到循环各处就会出现『置位后永不复位』——"
               "巡检守护异常退出或被人手动停掉后不再被拉起（夜间防护静默消失），"
               "攻城改了目标或命中时刻也不会重新武装。",
    },
]

_METHOD_RE = r"^\s{4}(?:private\s+|public\s+|internal\s+|protected\s+)?fun\s+([A-Za-z_]\w*)"


def _enclosing_function(lines, idx):
    """向上找最近的同类方法声明，作为该行的所属函数。"""
    for j in range(idx, -1, -1):
        m = re.match(_METHOD_RE, lines[j])
        if m:
            return m.group(1)
    return None


def check_domain_text(label, text, spec):
    """对一段源码文本执行领域不变量检查，返回问题描述列表（与文件无关，便于自测）。"""
    problems = []
    lines = text.splitlines()
    names = spec["assignments"]
    allowed = spec["allowed_functions"]

    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        for name in names:
            # 匹配 `name =` 或 `name ?.x =` 这类赋值（排除 == 比较与声明）
            m = re.search(r"(?<![\w.])" + re.escape(name) + r"\s*(?:\?\.\w+\s*)?=(?!=)", line)
            if not m:
                continue
            if re.match(r"^\s*(?:private\s+|public\s+|internal\s+)?var\s+" + re.escape(name), line):
                continue  # 字段声明本身
            fn = _enclosing_function(lines, i)
            if fn not in allowed:
                problems.append(
                    f"第 {i + 1} 行在 `{fn}` 中给 `{name}` 赋值；"
                    f"只允许在 {sorted(allowed)} 内。原因：{spec['why']}"
                )
    return problems


def check_domain_invariants(java_root, rep):
    # 配置里的路径是相对包根 com/stzb/assistant 写的
    pkg_root = os.path.join(java_root, "com", "stzb", "assistant")
    checked = 0
    for spec in DOMAIN_INVARIANTS:
        path = os.path.join(pkg_root, spec["file"].replace("/", os.sep))
        if not os.path.isfile(path):
            rep.error(spec["file"], f"领域不变量检查找不到目标文件（查找路径 {path}）")
            continue
        checked += 1
        for p in check_domain_text(spec["file"], read_text(path), spec):
            rep.error(spec["file"], p)
    rep.note(f"领域不变量: 已核对 {checked} 个文件")


def run_verify_refs_selftest():
    """
    领域不变量规则的反例自测（证明规则不是空转）。

    对**每一条**规则自动合成一段最小源码：
      * 一个合规函数（在 allowed_functions 里）赋值 -> 不应报
      * 一个越权函数赋值                            -> 必须报
    这样新增规则时自测会自动覆盖，不需要手写用例。
    """
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    bad = 0
    total = 0
    for spec in DOMAIN_INVARIANTS:
        name = spec["assignments"][0]
        allowed = sorted(spec["allowed_functions"])[0]
        ok_src = (
            "class Synthetic {\n"
            f"    private var {name} = 0\n"
            f"    private fun {allowed}() {{\n"
            f"        {name} = 1\n"
            "    }\n"
            "}\n"
        )
        bad_src = (
            "class Synthetic {\n"
            f"    private var {name} = 0\n"
            "    private fun someOtherPlace() {\n"
            f"        {name} = 1\n"
            "    }\n"
            "}\n"
        )
        for label, text, expect in (
            (f"{spec['file']}: 合规写法（在 {allowed} 内赋值）", ok_src, 0),
            (f"{spec['file']}: 越权写法（在 someOtherPlace 内赋值）", bad_src, 1),
        ):
            total += 1
            got = len(check_domain_text("synthetic", text, spec))
            if got == expect:
                print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
            else:
                bad += 1
                print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")

    print("-" * 68)
    if bad:
        print(f"{bad}/{total} 个用例不符合预期")
        return 1
    print(f"{total} 个用例全部符合预期 —— {len(DOMAIN_INVARIANTS)} 条领域不变量规则均有效。")

    # ---- 持久化字段对账的自测 ----
    # 注意：必须把 data_class 换成合成代码里真实声明的类名。
    # 第一版直接复用了真实 spec（data_class="ScheduledTask"），却喂给它声明 Demo 的代码，
    # 于是永远报「找不到 data class」——一个永远失败的用例和永远通过的校验器一样没用。
    pspec = dict(PERSISTENCE_CHECKS[0], data_class="Demo")
    good = """
    data class Demo(
        var alpha: String = "",
        var beta: Int = 0
    )
    private fun saveTasks(context: Context) {
        val obj = JSONObject().apply {
            put("alpha", task.alpha)
            put("beta", task.beta)
        }
    }
    private fun loadTasks(context: Context) {
        val a = obj.optString("alpha", "")
        val b = obj.optInt("beta", 0)
    }
    """
    bad_missing_save = good.replace('            put("beta", task.beta)\n', "")
    bad_missing_load = good.replace('        val b = obj.optInt("beta", 0)\n', "")
    pcases = [
        ("持久化：写入/读出成对", good, 0),
        ("持久化：漏写 saveTasks 一个字段", bad_missing_save, 1),
        ("持久化：漏读 loadTasks 一个字段", bad_missing_load, 1),
    ]
    pbad = 0
    for label, text, expect in pcases:
        got = len(check_persistence(pspec, text))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            pbad += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if pbad:
        print(f"{pbad}/{len(pcases)} 个持久化用例不符合预期")
        return 1
    print(f"{len(pcases)} 个持久化对账用例全部符合预期 —— 规则有效。")

    # ---- 版本号比较的自测 ----
    vcases = [
        ("版本号：用 compareVersion()", "if (a.compareVersion(b) >= 0) { ok() }", 0),
        ("版本号：直接用 >= 比较（字典序陷阱）",
         "if (cachedProfile.profileVersion >= baseProfile.profileVersion) { ok() }", 1),
        ("版本号：常量在左侧同样要抓到", 'if ("1.0" < profileVersion) { ok() }', 1),
        ("版本号：无关数值比较不应误报", "if (troopCount > 0) { ok() }", 0),
    ]
    vbad = 0
    for label, text, expect in vcases:
        got = len(check_version_comparisons_text(text))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            vbad += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if vbad:
        print(f"{vbad}/{len(vcases)} 个版本号用例不符合预期")
        return 1
    print(f"{len(vcases)} 个版本号比较用例全部符合预期 —— 规则有效。")

    # ---- 只写不读字段的自测 ----
    w_good = [
        ("A.kt", """
class A {
    private var used = 0
    fun f() {
        used = 1
        println(used)
    }
}
"""),
    ]
    w_bad = [
        ("A.kt", """
class A {
    private var dead = 0
    fun f() {
        dead = 1
    }
}
"""),
    ]
    wcases = [
        ("只写不读：读了就不该报", w_good, 0),
        ("只写不读：只有赋值必须报", w_bad, 1),
    ]
    wbad = 0
    for label, files, expect in wcases:
        got = len(check_write_only_fields_text(files))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            wbad += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if wbad:
        print(f"{wbad}/{len(wcases)} 个只写不读用例不符合预期")
        return 1
    print(f"{len(wcases)} 个只写不读用例全部符合预期 —— 规则有效。")

    # ---- 关键能力界面可见性的自测 ----
    # 三个用例分别钉住：① 全限定名（前面是 `.`）不能被漏判；
    #                 ② 别名写法不能被误判；③ 界面层完全没引用必须报出来。
    vis_ok_fq = [
        ("com/stzb/assistant/ui/MainActivity.kt",
         "val ocr = com.stzb.assistant.ocr.OcrManager\n"
         "tv.text = if (ocr.isEngineAvailable) \"ok\" else \"no\"\n"
         "val gate = com.stzb.assistant.license.LicenseGate\n"
         "tv.text = gate.describe(this)\n"),
    ]
    vis_ok_alias = [
        ("com/stzb/assistant/overlay/OverlayWindowManager.kt",
         "val ocr = com.stzb.assistant.ocr.OcrManager\n"
         "ocr.isEngineAvailable\n"
         "com.stzb.assistant.license.LicenseGate.isExecutionAllowed(ctx)\n"),
    ]
    vis_bad = [
        # 界面层只提到 LicenseGate；OcrManager 只出现在非界面层
        ("com/stzb/assistant/ui/MainActivity.kt",
         "com.stzb.assistant.license.LicenseGate.describe(this)\n"),
        ("com/stzb/assistant/ocr/StzbUiMatcher.kt",
         "com.stzb.assistant.ocr.OcrManager.detect(bmp)\n"),
    ]
    vscases = [
        ("界面可见性：全限定名（前面是点）应算引用", vis_ok_fq, 0),
        ("界面可见性：别名写法应算引用", vis_ok_alias, 0),
        ("界面可见性：界面层没引用 OcrManager 必须报", vis_bad, 1),
    ]
    vsbad = 0
    for label, files, expect in vscases:
        got = len(check_capability_visibility_text(files))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            vsbad += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if vsbad:
        print(f"{vsbad}/{len(vscases)} 个界面可见性用例不符合预期")
        return 1
    print(f"{len(vscases)} 个界面可见性用例全部符合预期 —— 规则有效。")

    # ---- 枚举可达性的自测 ----
    rspec = REACHABILITY_CHECKS[0]
    enum_src = """
    enum class PickTarget {
        PAVING,
        SIEGE,
        BUTTON_TEMPLATE
    }
    """
    reach_ok = enum_src + """
    startCrosshairPicker(PickTarget.PAVING)
    startCrosshairPicker(PickTarget.SIEGE)
    startCrosshairPicker(PickTarget.BUTTON_TEMPLATE)
    """
    reach_bad = enum_src + """
    startCrosshairPicker(PickTarget.PAVING)
    startCrosshairPicker(PickTarget.SIEGE)
    """  # 少了 BUTTON_TEMPLATE
    rcases = [
        ("可达性：全部枚举值都有启动调用", reach_ok, 0),
        ("可达性：漏掉一个枚举值必须报出", reach_bad, 1),
    ]
    rbad = 0
    for label, text, expect in rcases:
        got = len(check_reachability_text(text, rspec))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            rbad += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if rbad:
        print(f"{rbad}/{len(rcases)} 个可达性用例不符合预期")
        return 1
    print(f"{len(rcases)} 个可达性用例全部符合预期 —— 规则有效。")

    # ---- 具名参数对账的自测 ----
    # 五个用例分别钉住：① 正常调用不报；② 参数名写错必须报；
    #   ③ 函数声明里的默认值不能被当成具名参数；④ 字符串里的 `名字 =` 不能被当成参数；
    #   ⑤ 库成员（equals 等）撞名工程声明时不能误报。
    na_decl = """
    fun addTask(context: Any, name: String, hitOffsetSeconds: Long = 60L) { }
    fun humanDelay(minMs: Long = 200, maxMs: Long = 600) { }
    fun needTwo(a: Int, b: Int) { }
    fun takesLambda(x: Int, block: () -> Unit) { }
    fun varargs(vararg xs: Int) { }
    data class Demo(val alpha: String = "", val beta: Int = 0)
    """
    na_cases = [
        ("具名参数：正常调用不报",
         na_decl + 'addTask(ctx, "x", hitOffsetSeconds = 1L)', 0),
        ("具名参数：名字写错必须报",
         na_decl + 'addTask(ctx, "x", hitOffsetSecond = 1L)', 1),
        ("具名参数：data class 构造正常不报",
         na_decl + 'Demo(alpha = "a", beta = 2)', 0),
        ("具名参数：声明里的默认值不算具名参数",
         na_decl, 0),
        ("具名参数：字符串里的 `名字 =` 不算参数",
         na_decl + 'logWarn("当前所有候选部队体力 = 不足")', 0),
        ("具名参数：库成员 equals 撞名不误报",
         na_decl + "fun doIt() { \"abc\".equals(\"x\", ignoreCase = true) }", 0),
        # ---- 实参个数 ----
        ("实参个数：正好够，不报",
         na_decl + "fun c1() { needTwo(1, 2) }", 0),
        ("实参个数：少给必填参数，必须报",
         na_decl + "fun c2() { needTwo(1) }", 1),
        ("实参个数：多给参数，必须报",
         na_decl + "fun c3() { needTwo(1, 2, 3) }", 1),
        ("实参个数：有默认值时可省略，不报",
         na_decl + 'fun c4() { addTask(ctx, "n") }', 0),
        ("实参个数：尾随 lambda 也算一个实参",
         na_decl + "fun c5() { takesLambda(1) { } }", 0),
        ("实参个数：vararg 不设上限",
         na_decl + "fun c6() { varargs(1, 2, 3, 4, 5) }", 0),
        ("实参个数：比较运算符里的 > 不能打乱计数",
         na_decl + "fun c7() { needTwo(if (a >= 1) 1 else 0, 2) }", 0),
    ]
    nab = 0
    for label, text, expect in na_cases:
        files = [("synthetic.kt", text)]
        fns, cts, pf = collect_signatures(files)
        got = len(check_named_arguments_text(files, fns, cts, pf))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            nab += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if nab:
        print(f"{nab}/{len(na_cases)} 个具名参数用例不符合预期")
        return 1
    print(f"{len(na_cases)} 个具名参数用例全部符合预期 —— 规则有效。")

    # ---- 重复声明的自测 ----
    # 四个用例分别钉住：① 同名同参数必须报；② 同名不同参数是合法重载，不得报；
    #                 ③ 同一文件里两个不同类各有同名函数，不得报；
    #                 ④ 两个方法里各自声明同名局部函数，不得报。
    d_cases = [
        ("重复声明：同名同参数必须报",
         "object A {\n    fun f(x: Int) {}\n    fun f(x: Int) {}\n}\n", 1),
        ("重复声明：同名不同参数是合法重载，不得报",
         "object A {\n    fun f(x: Int) {}\n    fun f(x: String) {}\n}\n", 0),
        ("重复声明：两个不同类各有同名函数，不得报",
         "class A {\n    fun stop() {}\n}\nclass B {\n    fun stop() {}\n}\n", 0),
        ("重复声明：两个方法里的同名局部函数，不得报",
         "class A {\n    fun m1() {\n        fun helper() {}\n        helper()\n    }\n"
         "    fun m2() {\n        fun helper() {}\n        helper()\n    }\n}\n", 0),
    ]
    dbad = 0
    for label, text, expect in d_cases:
        got = len(check_duplicate_declarations_text("synthetic.kt", text))
        if got == expect:
            print(f"OK   | 期望 {expect} 项，实际 {got} 项 | {label}")
        else:
            dbad += 1
            print(f"FAIL | 期望 {expect} 项，实际 {got} 项 | {label}")
    print("-" * 68)
    if dbad:
        print(f"{dbad}/{len(d_cases)} 个重复声明用例不符合预期")
        return 1
    print(f"{len(d_cases)} 个重复声明用例全部符合预期 —— 规则有效。")

    # ---- 能力接线（"摆设"探测）的自测 ----
    # 两个用例：① 只在定义文件里出现的名字必须被报出来；
    #            ② 有定义文件之外的引用则不得报。
    import tempfile

    class _Rep:
        def __init__(self):
            self.errors = []

        def error(self, path, msg):
            self.errors.append((path, msg))

        def warn(self, path, msg):
            pass

        def note(self, msg):
            pass

    saved = CAPABILITY_WIRING_CHECKS[:]
    wcases = []
    try:
        with tempfile.TemporaryDirectory() as tmp:
            os.makedirs(os.path.join(tmp, "svc"))
            os.makedirs(os.path.join(tmp, "ui"))
            with open(os.path.join(tmp, "svc", "Thing.kt"), "w", encoding="utf-8") as fh:
                fh.write("object Thing {\n    fun doIt() {}\n}\n")

            globals()["CAPABILITY_WIRING_CHECKS"] = [("测试能力 doIt", "doIt", "svc/Thing.kt")]
            r = _Rep()
            check_capability_wiring(tmp, r)
            wcases.append(("能力只在定义文件里出现 → 必须报出",
                           len(r.errors) == 1, f"报出 {len(r.errors)} 项"))

            with open(os.path.join(tmp, "ui", "User.kt"), "w", encoding="utf-8") as fh:
                fh.write("class User {\n    fun use() { Thing.doIt() }\n}\n")
            r2 = _Rep()
            check_capability_wiring(tmp, r2)
            wcases.append(("定义文件之外有引用 → 不得报",
                           len(r2.errors) == 0, f"报出 {len(r2.errors)} 项"))
    finally:
        globals()["CAPABILITY_WIRING_CHECKS"] = saved

    wbad = 0
    for label, ok, detail in wcases:
        if ok:
            print(f"OK   | 期望符合 | {label}\n        {detail}")
        else:
            wbad += 1
            print(f"FAIL | 期望不符 | {label}\n        {detail}")
    print("-" * 68)
    if wbad:
        print(f"{wbad}/{len(wcases)} 个能力接线用例不符合预期")
        return 1
    print(f"{len(wcases)} 个能力接线用例全部符合预期 —— 规则有效。")
    return 0


# ---------------------------------------------------------------------------
# 持久化字段对账
#
# 差点犯的一个错：给 ScheduledTask 加了 extraTargetsRaw 字段、也写了 loadTasks 的读取，
# 却漏了 saveTasks 里的 put()。后果是**设置能保存、重启后悄悄丢掉**——
# 这类 bug 在真机上极难发现（用户只会觉得"我设置的怎么又没了"）。
# 字段一多就更容易漏，因此静态对账：每个字段名都必须同时出现在写入与读取里。
# ---------------------------------------------------------------------------

PERSISTENCE_CHECKS = [
    {
        "file": "tactics/ScheduledTaskManager.kt",
        "data_class": "ScheduledTask",
        "save_fn": "saveTasks",
        "load_fn": "loadTasks",
        "why": "定时任务的每个字段都必须同时被写入(saveTasks)与读出(loadTasks)。"
               "漏掉写入会表现为『设置能保存、重启后悄悄丢』；"
               "漏掉读取则表现为『存进去了却从来没用上』。两者都只有在真机上才暴露。",
    },
]


def _data_class_fields(text, class_name):
    """取 data class 主构造里的属性名。"""
    m = re.search(r"data\s+class\s+" + re.escape(class_name) + r"\s*\(", text)
    if not m:
        return []
    i = m.end()
    depth = 1
    body = []
    while i < len(text) and depth > 0:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        body.append(c)
        i += 1

    # 顶层逗号切分（忽略 <> 与 () 内的逗号）
    parts, depth2, cur = [], 0, []
    for ch in "".join(body):
        if ch in "(<[":
            depth2 += 1
        elif ch in ")>]":
            depth2 -= 1
        if ch == "," and depth2 == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))

    names = []
    for p in parts:
        mm = re.match(r"\s*(?:var|val)\s+([A-Za-z_]\w*)", p)
        if mm:
            names.append(mm.group(1))
    return names


def _function_body(text, fn_name):
    m = re.search(r"fun\s+" + re.escape(fn_name) + r"\s*\(", text)
    if not m:
        return None
    start = text.find("{", m.end())
    if start < 0:
        return None
    depth = 0
    i = start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1
    return None


def check_persistence(spec, text):
    """返回问题描述列表（与文件无关，便于自测）。"""
    fields = _data_class_fields(text, spec["data_class"])
    if not fields:
        return [f"找不到 data class {spec['data_class']} 或其构造参数"]
    save = _function_body(text, spec["save_fn"])
    load = _function_body(text, spec["load_fn"])
    if save is None or load is None:
        return [f"找不到 {spec['save_fn']} 或 {spec['load_fn']} 的函数体"]

    problems = []
    for f in fields:
        key = f'"{f}"'
        if key not in save:
            problems.append(
                f"字段 `{f}` 在 {spec['save_fn']} 里没有写入（找不到 {key}）：该字段重启后会丢失。"
            )
        if key not in load:
            problems.append(
                f"字段 `{f}` 在 {spec['load_fn']} 里没有读取（找不到 {key}）：存了也不会被用上。"
            )
    return problems


def check_persistence_all(java_root, rep):
    pkg_root = os.path.join(java_root, "com", "stzb", "assistant")
    for spec in PERSISTENCE_CHECKS:
        path = os.path.join(pkg_root, spec["file"].replace("/", os.sep))
        if not os.path.isfile(path):
            rep.error(spec["file"], "持久化对账找不到目标文件")
            continue
        text = read_text(path)
        for p in check_persistence(spec, text):
            rep.error(spec["file"], p)
    rep.note(f"持久化字段对账: 已核对 {len(PERSISTENCE_CHECKS)} 个数据类")


# ---------------------------------------------------------------------------
# 版本号比较检查
#
# 版本号在本项目里是 String。Kotlin 的 `<` `>` `<=` `>=` 对 String 是**字典序**比较，
# 编译完全通过、运行期悄悄出错：
#     "9" >= "10"          -> true（'9' > '1'）—— 旧缓存压住新内置版本
#     "1.10.0" < "1.9.0"   -> true            —— 真正的新版本反被拒绝
# 因此凡是名字里带 version 的标识符参与大小比较，一律要求改用 compareVersion()。
# ---------------------------------------------------------------------------

# 操作数：标识符，或形如 "1.0" / "9" 的字符串字面量。
# 必须允许字面量——`if (version < "1.5")` 与 `if ("9" >= version)` 都是真实写法，
# 第一版只允许标识符，造成漏报（自测用例把它抓出来了）。
_VER_CMP_IDENT = r'(?:[A-Za-z_][\w.]*|"\d+(?:\.\d+)*")'
_VER_CMP_VER = r'(?:[A-Za-z_][\w.]*[Vv]ersion[\w]*|"\d+(?:\.\d+)*")'
_VERSION_CMP_RE = re.compile(
    r"(" + _VER_CMP_VER + r")\s*(>=|<=|>|<)\s*(" + _VER_CMP_IDENT + r")"
    r"|(" + _VER_CMP_IDENT + r")\s*(>=|<=|>|<)\s*(" + _VER_CMP_VER + r")"
)


def check_version_comparisons_text(text):
    """返回问题描述列表（与文件无关，便于自测）。"""
    problems = []
    for i, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if line.startswith("//") or line.startswith("*"):
            continue
        if "compareVersion" in line:
            continue  # 已改用数值比较
        if _VERSION_CMP_RE.search(line):
            problems.append(
                f"第 {i} 行用 `<`/`>` 直接比较了版本号（字典序，不是数值序）：{line}。"
                f"请改用 GameProfile.compareVersion()。"
            )
    return problems


def check_version_comparisons(java_root, rep):
    count = 0
    for dirpath, _, files in os.walk(java_root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            count += 1
            p = os.path.join(dirpath, f)
            for problem in check_version_comparisons_text(read_text(p)):
                rep.error(os.path.relpath(p, java_root), problem)
    rep.note(f"版本号比较检查: 已扫描 {count} 个 Kotlin 文件")


# ---------------------------------------------------------------------------
# 只写不读的私有字段
#
# 这一类"死状态"在本项目里反复出现，而且每一次都伪装成"功能已就绪"：
#   * ScreenCaptureService.projectionResultCode/Data —— 看着像为重建会话做好了准备，
#     实际从未被读取（真正的重建方式根本用不到它们）；
#   * EdgeSlmEngine.isModelWeightLoaded —— 被赋值 5 次、读取 0 次，
#     于是"模型已加载"这个判断从来没有生效过。
# 它们不会报错、不会被编译器发现，只会让人误以为某件事已经在做了。
# 因此静态对账：私有字段若全工程只有赋值、没有任何读取，就报出来。
# ---------------------------------------------------------------------------

# 类体内部的私有字段声明（缩进 4 空格，可带 @Volatile / lateinit 等修饰）
_FIELD_DECL_RE = re.compile(
    r"^\s{4}(?:@\w+\s+)?private\s+(?:lateinit\s+)?(?:var|val)\s+([A-Za-z_]\w*)"
)


def _is_write_occurrence(line, name):
    """判断某一行里的 name 是否只是"给它赋值"。"""
    m = re.search(r"(?:^|[^\w.])" + re.escape(name) + r"\s*(?:\+|-|\*|/)?=(?!=)", line)
    if not m:
        return False
    # 赋值语句要求 name 前面只有空白 / this. / 修饰符
    prefix = line[:m.start()].strip()
    prefix = prefix.replace("this.", "").strip()
    return prefix == "" or prefix.endswith((";", "{", "}"))


def _strip_lambda_params(line):
    """把 `{ name -> ... }` 这类 lambda 形参去掉，避免被当成读取。"""
    return re.sub(r"\{\s*\w+\s*->", "{", line)


def check_write_only_fields_text(files):
    """
    files: [(文件相对路径, 文本)]
    返回 [(文件, 行号, 字段名)]。
    """
    decls = []
    corpus = []
    for path, text in files:
        corpus.append(text)
        for i, raw in enumerate(text.splitlines(), start=1):
            m = _FIELD_DECL_RE.match(raw)
            if not m:
                continue
            name = m.group(1)
            # 声明行本身不算读取
            decls.append((path, i, name))

    joined = _strip_lambda_params("\n".join(corpus))
    lines = joined.splitlines()

    findings = []
    for path, lineno, name in decls:
        read_count = 0
        for line in lines:
            if not re.search(r"(?:^|[^\w.])" + re.escape(name) + r"(?![\w])", line):
                continue
            if re.match(r"^\s*(?:@\w+\s+)?private\s+(?:lateinit\s+)?(?:var|val)\s+"
                        + re.escape(name), line):
                continue  # 声明
            if _is_write_occurrence(line, name):
                continue  # 纯赋值
            read_count += 1
        if read_count == 0:
            findings.append((path, lineno, name))
    return findings


def check_write_only_fields(java_root, rep):
    files = []
    for dirpath, _, fs in os.walk(java_root):
        for f in fs:
            if f.endswith(".kt"):
                p = os.path.join(dirpath, f)
                files.append((os.path.relpath(p, java_root), read_text(p)))
    for path, lineno, name in check_write_only_fields_text(files):
        rep.error(
            path,
            f"第 {lineno} 行的私有字段 `{name}` 全工程只有赋值、没有任何读取："
            f"这是「死状态」，会让人误以为某项功能已经在生效。请要么真正使用它，要么删除。",
        )
    rep.note(f"只写不读字段检查: 已扫描 {len(files)} 个 Kotlin 文件")


# ---------------------------------------------------------------------------
# 关键能力的"界面可见性"检查
#
# 反复出现的教训：某项能力**静默失效**时，用户只能等某个流程莫名失败才发现。
# 最典型的是 OCR —— 默认构建里 native 引擎是空桩，而主界面此前**完全不显示**
# 这一点（MainActivity 里 OCR 相关引用为零）。
# 因此对"决定整套能力是否成立"的标志做硬性要求：必须被界面层读取。
# ---------------------------------------------------------------------------

UI_DIR_MARKERS = ("/ui/", "/overlay/")


def _is_ui_path(path):
    """
    判断是否属于界面层。

    注意：路径是相对 `java/` 的，形如 `com/stzb/assistant/ui/MainActivity.kt`，
    因此**不能**用 startswith("ui/") —— 第一版就是这么写的，
    结果把刚刚接好的线也判成"没接"。必须按路径中的目录段匹配。
    """
    p = "/" + path.replace(os.sep, "/")
    return any(m in p for m in UI_DIR_MARKERS)

WIRING_CHECKS = [
    {
        "owner": "OcrManager",
        "why": "OCR 是否可用决定了场景判定/按键定位/坐标读取能否成立。"
               "若界面层完全不引用它，用户就只能等某个战术流程莫名失败才发现"
               "「识别根本不存在」——这正是本项目长期的状态。",
    },
    {
        "owner": "LicenseGate",
        "why": "授权状态若不在界面上体现，用户无法判断自己为什么被拦截。",
    },
]

# 说明：这里刻意只检查"界面层是否引用了这个**对象**"，而不检查具体成员。
#
# 第一版检查的是 `Owner.member` 字面形式，结果产生假阳性：代码里常写成
#     val ocr = com.stzb.assistant.ocr.OcrManager
#     ... ocr.isEngineAvailable ...
# 成员是被读取了的，但字面上看不到 `OcrManager.isEngineAvailable`。
#
# 而**不应该**为了迁就检查器去改产品代码的写法（那是本末倒置）。
# 同时"界面层根本不引用该对象"已经足以覆盖历史上的真实缺陷
# （MainActivity 里 OCR 相关引用为零），所以粒度取到对象名即可。


def _is_ui_path(path):
    """
    判断是否属于界面层。

    注意：路径是相对 `java/` 的，形如 `com/stzb/assistant/ui/MainActivity.kt`，
    因此**不能**用 startswith("ui/")。必须按路径中的目录段匹配。
    """
    p = "/" + path.replace(os.sep, "/")
    return any(m in p for m in UI_DIR_MARKERS)


def check_capability_visibility_text(files):
    """files: [(相对路径, 文本)]。返回问题描述列表。"""
    problems = []
    for spec in WIRING_CHECKS:
        # 只要不是"更长标识符的一部分"就算引用。
        # ⚠️ 不能把前面的 `.` 排除掉：全限定名 com.stzb.assistant.ocr.OcrManager
        # 前面正好是 `.`，第一版就是这么写的，于是把已经接好的线判成"没接"。
        pat = re.compile(r"(?<![\w])" + re.escape(spec["owner"]) + r"(?![\w])")
        if any(pat.search(text) for path, text in files if _is_ui_path(path)):
            continue
        problems.append(
            f"界面层（{', '.join(m.strip('/') for m in UI_DIR_MARKERS)} 目录）"
            f"完全没有引用 `{spec['owner']}`。原因：{spec['why']}"
        )
    return problems


def check_capability_visibility(java_root, rep):
    files = []
    for dirpath, _, fs in os.walk(java_root):
        for f in fs:
            if not f.endswith(".kt"):
                continue
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, java_root).replace(os.sep, "/")
            files.append((rel, read_text(p)))
    for problem in check_capability_visibility_text(files):
        rep.error("界面可见性", problem)
    rep.note(f"关键能力界面可见性: 已核对 {len(WIRING_CHECKS)} 组标志")


# ---------------------------------------------------------------------------
# 枚举可达性检查
#
# 本轮的教训：我给标定流程新增了 `PickTarget.BUTTON_TEMPLATE`，
# 而"新增枚举值却漏接某个入口"是本项目很容易犯的错——
# 枚举值一旦没人启动它，那段分支就是死代码，而且不报错、不崩溃，只是永远不生效。
#
# 这里只查**能清除判断**的那一类：枚举常量是否至少被"启动"过一次。
# 刻意不去查"每个 when 是否覆盖全部常量"——那个检查会误报：
# 例如第一个 `when (target)` 只负责"应用选点结果"，未列出的常量落到空分支是**有意**的。
# 一个会误报的检查比没有检查更糟，因为它会训练人忽略它。
# ---------------------------------------------------------------------------

REACHABILITY_CHECKS = [
    {
        "file": "overlay/OverlayWindowManager.kt",
        "enum_owner": "PickTarget",
        "launcher": "startCrosshairPicker",
        "why": "标定用的选点目标必须能从界面启动；没人启动的枚举值等于一段永远不生效的分支。",
    },
]


def check_reachability_text(text, spec):
    """返回问题描述列表（与文件无关，便于自测）。"""
    # 常量：enum class <Owner> { A, B, C } —— 取枚举体内的裸标识符
    m = re.search(r"enum\s+class\s+" + re.escape(spec["enum_owner"]) + r"\s*\{([\s\S]*?)\n\s*\}", text)
    if not m:
        return [f"找不到 enum class {spec['enum_owner']}"]

    body = re.sub(r"/\*[\s\S]*?\*/", "", m.group(1))
    body = re.sub(r"//[^\n]*", "", body)
    constants = re.findall(r"^\s*([A-Z][A-Z0-9_]*)\s*(?:,|$)", body, re.M)
    if not constants:
        return [f"enum class {spec['enum_owner']} 里没有解析到任何常量"]

    problems = []
    for c in constants:
        call = f"{spec['launcher']}({spec['enum_owner']}.{c})"
        if call not in text:
            problems.append(
                f"枚举值 `{spec['enum_owner']}.{c}` 没有任何 `{call}` 调用——"
                f"它永远不会被启动。原因：{spec['why']}"
            )
    return problems


def check_reachability(java_root, rep):
    pkg_root = os.path.join(java_root, "com", "stzb", "assistant")
    for spec in REACHABILITY_CHECKS:
        path = os.path.join(pkg_root, spec["file"].replace("/", os.sep))
        if not os.path.isfile(path):
            rep.error(spec["file"], "可达性检查找不到目标文件")
            continue
        for p in check_reachability_text(read_text(path), spec):
            rep.error(spec["file"], p)
    rep.note(f"枚举可达性: 已核对 {len(REACHABILITY_CHECKS)} 个枚举")


# ---------------------------------------------------------------------------
# 具名参数对账
#
# 这是本项目**唯一还没被覆盖**的编译错误类别里最常见的一种：
#     ScheduledTaskManager.addTask(context, name, time, type, x, y,
#                                  hitOffsetSeconds = 60L, targetWorldX = wx, ...)
# 只要有一个具名参数写错（拼错、或函数改名后没同步），就是编译错误。
# 没有编译器时，这类错误此前完全查不出来。
#
# 做法：先把工程内所有函数与 data class 主构造的参数名收集起来，
# 再扫描调用点里 depth=0 的 `名字 =` 形式，逐个比对。
#
# 保守策略（**宁可漏报也不误报**，因为误报会训练人忽略检查）：
#   * 被调用的名字在本工程里有多个不同签名的声明 → 歧义 → 跳过；
#   * 被调用的名字在本工程里找不到声明（那是库 API，如 Toast.makeText）→ 跳过；
#   * 只在括号与花括号深度都为 0 处识别具名参数，避免把 lambda 里的赋值当参数。
# ---------------------------------------------------------------------------

_FUN_DECL_RE = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)\s*\(")
_CLASS_DECL_RE = re.compile(r"\bdata\s+class\s+([A-Za-z_]\w*)\s*\(")


def _split_top_level(params):
    """
    按顶层逗号切分（忽略 () [] {} 内的逗号，以及泛型 <> 内的逗号）。

    ⚠️ 尖括号必须**单独、谨慎**处理：
    第一版把每个 `<` / `>` 都当成括号深度，结果 `stamina >= 120` 里的 `>`
    把深度打成负数，后面的逗号就不再算顶层——
    `TroopSlotDetail` 的 8 个具名参数因此被数成 3 个，报出一条假阳性。
    现在的规则：
      * `>=` `<=` `->` `=>` 这类多字符运算符直接跳过；
      * `<` 只有在**紧跟在标识符后面**时才当作泛型开始；
      * `>` 只有在确实处在泛型内部时才闭合。
    """
    parts, depth, angle, cur = [], 0, 0, []
    i = 0
    n = len(params)
    while i < n:
        two = params[i:i + 2]
        if two in (">=", "<=", "->", "=>"):
            cur.append("  ")
            i += 2
            continue
        ch = params[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif ch == "<" and i > 0 and (params[i - 1].isalnum() or params[i - 1] == "_"):
            angle += 1
        elif ch == ">" and angle > 0:
            angle -= 1
        if ch == "," and depth == 0 and angle == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def _extract_paren(text, open_idx):
    """从 open_idx（指向 '('）取出括号内文本。"""
    depth = 0
    out = []
    i = open_idx
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
            if depth == 1:
                i += 1
                continue
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        out.append(c)
        i += 1
    return "".join(out)


def _param_meta(params_list_text):
    """
    解析参数列表，返回 (名字列表, 最少实参个数, 最多实参个数或 None)。
    `None` 表示有 vararg（无上界）。带默认值的参数计入上限、不计入下限。
    """
    names = []
    min_args = 0
    max_args = 0
    has_vararg = False
    for p in _split_top_level(params_list_text):
        p = p.strip()
        if not p:
            continue
        head = p.split(":", 1)[0]
        if "vararg" in head:
            has_vararg = True
        else:
            max_args += 1
            # 只看"类型之后"是否有默认值，避免把 `List<Int> = ...` 之类看错
            if ":" not in p or "=" not in p.split(":", 1)[1]:
                min_args += 1
        m = re.match(r"\s*(?:\w+\s+)*([A-Za-z_]\w*)\s*:", p)
        if m:
            names.append(m.group(1))
    return names, min_args, (None if has_vararg else max_args)


def _count_top_level_args(args_text):
    """数出参数列表里 depth=0 处的实参个数（忽略空白项）。"""
    n = 0
    for part in _split_top_level(args_text):
        if part.strip():
            n += 1
    return n


def _extract_paren_with_end(text, open_idx):
    """与 _extract_paren 相同，但额外返回右括号的下标。"""
    depth = 0
    out = []
    i = open_idx
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
            if depth == 1:
                i += 1
                continue
        elif c == ")":
            depth -= 1
            if depth == 0:
                return "".join(out), i
        out.append(c)
        i += 1
    return "".join(out), len(text) - 1


def _param_names_from_list(params):
    names = set()
    for p in _split_top_level(params):
        m = re.match(r"\s*(?:vararg\s+|noinline\s+|crossinline\s+)?([A-Za-z_]\w*)\s*:", p)
        if m:
            names.add(m.group(1))
    return names


def collect_signatures(files):
    """
    返回 (funcs, ctors, per_file_declared)。
    值为 {"names":set, "min":int, "max":int|None} 或 None（同名重载，歧义，不检查）。
    `per_file_declared`：每个文件里声明的函数名集合，用于把"库调用撞名"挡在门外。
    """
    funcs, ctors = {}, {}
    per_file_declared = {}

    def add(table, name, params):
        names, lo, hi = _param_meta(params)
        meta = {"names": set(names), "min": lo, "max": hi}
        if name in table and table[name] != meta:
            table[name] = None  # 歧义（重载）→ 不参与检查
        else:
            table.setdefault(name, meta)

    for path, text in files:
        clean = sanitize_kotlin(text)
        declared = set()
        for m in _FUN_DECL_RE.finditer(clean):
            declared.add(m.group(1))
            add(funcs, m.group(1), _extract_paren(clean, m.end() - 1))
        for m in _CLASS_DECL_RE.finditer(clean):
            add(ctors, m.group(1), _extract_paren(clean, m.end() - 1))
        per_file_declared[path] = declared
    return funcs, ctors, per_file_declared


def _named_args_at_depth0(args):
    """取参数列表里 depth=0 处的 `名字 =` 形式。"""
    names = []
    depth = 0
    i = 0
    while i < len(args):
        c = args[i]
        if c in "(<[{":
            depth += 1
        elif c in ")>]}":
            depth -= 1
        elif depth == 0 and (c.isalpha() or c == "_"):
            j = i
            while j < len(args) and (args[j].isalnum() or args[j] == "_"):
                j += 1
            word = args[i:j]
            k = j
            while k < len(args) and args[k] in " \t\n":
                k += 1
            if k < len(args) and args[k] == "=" and (k + 1 >= len(args) or args[k + 1] != "="):
                names.append(word)
            i = j
            continue
        i += 1
    return names


_CALL_SITE_RE = re.compile(r"(?:[A-Za-z_][\w.]*\.)?([A-Za-z_]\w*)\s*\(")


def check_named_arguments_text(files, funcs, ctors, per_file_declared=None):
    """返回 [(文件, 名字, 详情, 期望)] —— 既查具名参数名，也查**位置实参个数**。"""
    per_file_declared = per_file_declared or {}
    problems = []
    for path, text in files:
        clean = sanitize_kotlin(text)
        for m in _CALL_SITE_RE.finditer(clean):
            callee = m.group(1)
            if callee in _STDLIB_MEMBER_SKIP:
                continue
            if _looks_like_declaration(clean, m.start()):
                continue  # 这是声明，不是调用
            table = None
            if callee in ctors and ctors[callee] is not None:
                table = ctors[callee]
            elif callee in funcs and funcs[callee] is not None:
                table = funcs[callee]
            if table is None or not table["names"]:
                continue

            args, end_idx = _extract_paren_with_end(clean, m.end() - 1)
            has_named_or_default = ("=" in args)
            if has_named_or_default:
                for nm in _named_args_at_depth0(args):
                    if nm not in table["names"]:
                        problems.append((path, callee, nm, "未知参数名"))

            # ---- 位置实参个数 ----
            # ⚠️ 只对"能确定指向工程内这个声明"的调用做个数检查：
            #   * 带限定名的调用（`mgr.addTask(...)`、`SiegeSyncFlow.SiegeConfig(...)`），或
            #   * 被调用者就在**本文件内**声明。
            # 否则会与库调用大面积撞名——`Calendar.getInstance()`、`call.execute()`
            # 都会去匹配工程里同名的 `getInstance(context)` / `execute(handler)`，
            # 于是报出一堆"实参个数不对"的假阳性（第一版正是这样报出 8 项）。
            qualified = "." in m.group(0).split("(", 1)[0]
            declared_here = callee in per_file_declared.get(path, ())
            if not (qualified or declared_here):
                continue

            provided = _count_top_level_args(args)
            # 尾随 lambda 也是一个实参：`foo(a) { ... }`
            tail = clean[end_idx + 1:].lstrip()
            if tail.startswith("{"):
                provided += 1
            if table["max"] is not None and provided > table["max"]:
                problems.append(
                    (path, callee, f"{provided} 个实参", f"最多 {table['max']} 个")
                )
            elif provided < table["min"]:
                problems.append(
                    (path, callee, f"{provided} 个实参", f"至少需要 {table['min']} 个")
                )
    return problems


def check_named_arguments(java_root, rep):
    files = []
    for dirpath, _, fs in os.walk(java_root):
        for f in fs:
            if f.endswith(".kt"):
                p = os.path.join(dirpath, f)
                files.append((os.path.relpath(p, java_root), read_text(p)))

    funcs, ctors, per_file = collect_signatures(files)
    for path, callee, detail, expect in check_named_arguments_text(files, funcs, ctors, per_file):
        rep.error(
            path,
            f"调用 `{callee}(...)` 时 {detail}，但该函数/构造没有对应的参数（{expect}）——"
            f"这是编译错误。请核对拼写、个数，或该函数签名已变更。",
        )
    rep.note(
        f"具名参数与实参个数对账: 已收集 {len(funcs)} 个函数、{len(ctors)} 个 data class 构造"
    )


# ---------------------------------------------------------------------------
# 重复声明检查
#
# 另一类"没有编译器就查不出"的错误：同一作用域里声明了两个**同名同参数**的函数，
# 或同一个文件中重复声明了同名 data class。前者是编译错误，
# 后者同样；而它们都不会被现有任何检查覆盖。
#
# 关键点是**不许误报**：Kotlin 允许
#   * 同名不同参数的重载；
#   * 同一文件里两个不同类各自有 `fun stop()`；
#   * 两个不同方法里各自声明一个同名的**局部函数**。
# 因此作用域键要带上"最近的类 + 最近的成员函数"，并且在自测里把这三种情形都钉住。
# ---------------------------------------------------------------------------

_CLASS_DECL_LINE_RE = re.compile(
    r"^(\s*)(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|protected\s+|abstract\s+|open\s+|"
    r"sealed\s+|data\s+|enum\s+|annotation\s+|inner\s+)*(?:class|object|interface)\s+([A-Za-z_]\w*)"
)
_FUN_DECL_LINE_RE = re.compile(r"^(\s*)(?:@\w+\s+)*(?:private\s+|internal\s+|public\s+|protected\s+|"
                               r"open\s+|override\s+|suspend\s+|inline\s+|operator\s+|tailrec\s+)*"
                               r"fun\s+([A-Za-z_]\w*)\s*\(")


def _fun_type_signature(params_text):
    """把参数列表压成"只有类型"的签名，用于判断两个声明是否真的重复。"""
    types = []
    for p in _split_top_level(params_text):
        p = p.strip()
        if not p:
            continue
        # 去掉默认值：`x: Int = 1` -> `x: Int`
        p = p.split("=", 1)[0].strip()
        if ":" in p:
            types.append(p.split(":", 1)[1].strip())
        else:
            types.append(p)
    return "|".join(types)


def check_duplicate_declarations_text(path, text):
    """返回该文件的重复声明问题列表。"""
    clean = sanitize_kotlin(text)
    # ⚠️ 必须同时记住每一行在**整个文件字符串**里的起始偏移。
    # 第一版用 `mf.end()-1` 这个"行内索引"去索引整个文件，
    # 位置完全错位、算出的参数签名是垃圾——自测立刻抓到了它
    # （"同名不同参数是合法重载"这一条被误报）。
    lines = clean.splitlines(keepends=True)
    offsets = []
    pos = 0
    for ln in lines:
        offsets.append(pos)
        pos += len(ln)

    problems = []

    class_stack = []          # [(类名, 缩进)]
    cur_fun = None            # (函数名, 缩进)
    seen_funcs = {}           # (scope, name, sig) -> 行号
    seen_classes = {}         # 类名 -> 行号（仅同一文件内）

    for idx, raw in enumerate(lines):
        i = idx + 1
        mc = _CLASS_DECL_LINE_RE.match(raw)
        if mc:
            indent = len(mc.group(1))
            name = mc.group(2)
            while class_stack and class_stack[-1][1] >= indent:
                class_stack.pop()
            class_stack.append((name, indent))
            cur_fun = None
            if name in seen_classes:
                problems.append(
                    f"第 {i} 行与第 {seen_classes[name]} 行在**同一文件内**重复声明了 `{name}`"
                )
            else:
                seen_classes[name] = i
            continue

        mf = _FUN_DECL_LINE_RE.match(raw)
        if mf:
            indent = len(mf.group(1))
            name = mf.group(2)
            while class_stack and class_stack[-1][1] >= indent:
                class_stack.pop()
            scope_class = class_stack[-1][0] if class_stack else "<top>"

            # 局部函数：缩进比外层函数更深 → 作用域里带上外层函数名，避免跨方法误报
            if cur_fun is not None and indent > cur_fun[1]:
                scope = f"{scope_class}.{cur_fun[0]}(local)"
            else:
                cur_fun = (name, indent)
                scope = scope_class

            # 用"行内索引 + 行起始偏移"换算出文件内绝对位置
            abs_open = offsets[idx] + mf.end() - 1
            sig = _fun_type_signature(_extract_paren(clean, abs_open))
            key = (scope, name, sig)
            if key in seen_funcs:
                problems.append(
                    f"第 {i} 行与第 {seen_funcs[key]} 行在作用域 `{scope}` 内**重复声明**了"
                    f" `{name}({sig})` —— 同名同参数，编译会报重复声明"
                )
            else:
                seen_funcs[key] = i
    return problems


def check_duplicate_declarations(java_root, rep):
    count = 0
    for dirpath, _, files in os.walk(java_root):
        for f in files:
            if not f.endswith(".kt"):
                continue
            count += 1
            p = os.path.join(dirpath, f)
            rel = os.path.relpath(p, java_root)
            for problem in check_duplicate_declarations_text(rel, read_text(p)):
                rep.error(rel, problem)
    rep.note(f"重复声明检查: 已扫描 {count} 个 Kotlin 文件")


# ---------------------------------------------------------------------------
# 能力接线检查（"摆设"探测）
#
# 为什么需要自动化：这一路我用手工 grep 判断"某个能力有没有调用方"，
# 结果**同一个错误模式发生了三次**：
#   * 只搜 .kt/.md/.yml/.py，漏掉 .cpp，于是误判"文档引用了一句不存在的日志"；
#   * 搜了一个**我自己臆造的函数名**（checkCoordinateSanity），其实叫 logSelfTest；
#   * 搜**全限定名** `GameProfile.validateFor`，而真实调用是实例调用
#     `cachedProfile.validateFor`，于是又误报"零引用"。
# 三次都是搜法的问题，不是代码的问题。
# 因此把这件事交给脚本：**只按简单名统计，并排除定义所在文件**。
# ---------------------------------------------------------------------------

CAPABILITY_WIRING_CHECKS = [
    ("守军评估 EngineBridge.evaluateDefenderPanel",
     "evaluateDefenderPanel", "service/EngineBridge.kt"),
    ("按键模板库 ButtonTemplateStore",
     "ButtonTemplateStore", "service/ButtonTemplateStore.kt"),
    ("纯 Java 模板匹配 TemplateMatcher",
     "TemplateMatcher", "service/TemplateMatcher.kt"),
    ("识别健康度 RecognitionHealth",
     "RecognitionHealth", "ocr/RecognitionHealth.kt"),
    ("知识库版本/内容校验 validateFor",
     "validateFor", "knowledge/GameProfile.kt"),
    ("坐标自检 logSelfTest",
     "logSelfTest", "service/CoordinateTransformer.kt"),
    ("UI 锚点表 UiAnchors.rect",
     "UiAnchors.rect", "service/UiAnchors.kt"),
    ("看门狗自愈 recoverToMainMap",
     "recoverToMainMap", "tactics/WatchdogRecovery.kt"),
    ("定时任务 addTask",
     "addTask", "tactics/ScheduledTaskManager.kt"),
    ("无人托管 AutoPilot",
     "AutoPilot", "tactics/AutoPilot.kt"),
]


def check_capability_wiring(java_root, rep):
    """
    每个被点名的能力，必须在**定义文件之外**至少有一处引用。

    为什么排除定义文件：一个只在自己文件里出现的名字，恰恰就是"实现了但没人用"。
    为什么只按简单名匹配：实例调用（`x.validateFor(...)`）、伴生调用、
    以及 `import` 全都会含简单名；而搜全限定名会漏掉实例调用——我正是这样误报过的。
    """
    files = []
    for dirpath, _, fs in os.walk(java_root):
        for f in fs:
            if f.endswith(".kt"):
                p = os.path.join(dirpath, f)
                files.append((os.path.relpath(p, java_root), read_text(p)))

    checked = 0
    for label, needle, decl_rel in CAPABILITY_WIRING_CHECKS:
        checked += 1
        hits = 0
        for path, text in files:
            if path.replace("\\", "/").endswith(decl_rel):
                continue
            if needle in text:
                hits += 1
        if hits == 0:
            rep.error(
                decl_rel,
                f"能力「{label}」在定义文件之外**找不到任何引用**"
                f"（按简单名 `{needle}` 统计）。这属于'实现了却没人用'的摆设，"
                f"请接进流程，或删除它。",
            )
    rep.note(f"能力接线检查: 已核对 {checked} 项")


# 声明关键字：fun / val / var / class / object / interface / enum class / sealed class
_DECL_RE = r"(?:fun|val|var|class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)"
# 枚举常量：缩进后的全大写标识符，后面跟 , 或 ( ，也可能是末项（跟注释或行尾）
_ENUM_RE = r"^\s{4,}([A-Z][A-Z0-9_]*)\s*(?:[,(]|//|$)"

MEMBER_CHECKS = [
    {
        "label": "TacticalState.TaskType 常量",
        "target": "TacticalState",
        "member_type": "TaskType",
        "decl_file": "tactics/TacticalState.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal", "displayName"},
    },
    {
        "label": "TacticalState.Status 常量",
        "target": "TacticalState",
        "member_type": "Status",
        "decl_file": "tactics/TacticalState.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal", "desc"},
    },
    {
        "label": "StzbUiMatcher.GameState 常量",
        "target": "StzbUiMatcher",
        "member_type": "GameState",
        "decl_file": "ocr/StzbUiMatcher.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal"},
    },
    {
        "label": "StzbUiMatcher.ButtonType 常量",
        "target": "StzbUiMatcher",
        "member_type": "ButtonType",
        "decl_file": "ocr/StzbUiMatcher.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal", "primaryKeyword", "aliases"},
    },
    {
        "label": "ImmersiveMoveMode 常量(免疫流程)",
        "target": "ImmunityBreakFlow",
        "member_type": "ImmunityMode",
        "decl_file": "tactics/ImmunityBreakFlow.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal"},
    },
    {
        "label": "UiAnchors.Key 常量",
        "target": "UiAnchors",
        "member_type": "Key",
        "decl_file": "service/UiAnchors.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal", "label", "defaultFx",
                    "defaultFy", "isCalibrated"},
    },
    {
        "label": "UiAnchors.RectKey 常量",
        "target": "UiAnchors",
        "member_type": "RectKey",
        "decl_file": "service/UiAnchors.kt",
        "decl": _ENUM_RE,
        "builtin": {"valueOf", "values", "name", "ordinal", "label", "isCalibrated"},
    },
]

# 整对象成员对账：(对象名, 声明文件)
OBJECT_MEMBER_CHECKS = [
    ("UiAnchors", "service/UiAnchors.kt"),
    ("MapProjection", "service/MapCoordinateSystem.kt"),
    ("MapNavigator", "service/MapCoordinateSystem.kt"),
    ("AutoPilot", "tactics/AutoPilot.kt"),
    ("ScheduledTaskManager", "tactics/ScheduledTaskManager.kt"),
    ("ModelAssetManager", "ai/assets/ModelAssetManager.kt"),
    ("OcrManager", "ocr/OcrManager.kt"),
    ("CoordinateTransformer", "service/CoordinateTransformer.kt"),
    ("TacticalPipeline", "tactics/TacticalPipeline.kt"),
    ("LicenseGate", "license/LicenseGate.kt"),
    ("SceneFingerprint", "service/SceneFingerprint.kt"),
    ("RecognitionHealth", "ocr/RecognitionHealth.kt"),
    # 下面几个是"被引用最多、却一直没纳入对账"的对象。
    # 例如 EngineBridge 在全工程有 70+ 处 `EngineBridge.xxx` 引用：
    # 只要其中一个成员名拼错或已被删除，没有编译器时就完全查不出来。
    ("EngineBridge", "service/EngineBridge.kt"),
    ("WatchdogRecovery", "tactics/WatchdogRecovery.kt"),
    ("KnowledgeBaseManager", "knowledge/KnowledgeBaseManager.kt"),
    # 这三个是本轮新增的对象，调用点也一并纳入成员对账
    ("TemplateMatcher", "service/TemplateMatcher.kt"),
    ("ButtonTemplateStore", "service/ButtonTemplateStore.kt"),
    ("OpenCvMatcher", "ocr/OpenCvMatcher.kt"),
]


def _collect(source_files, pattern, group=1):
    found = set()
    rx = re.compile(pattern, re.MULTILINE)
    for p in source_files:
        for m in rx.finditer(read_text(p)):
            found.add(m.group(group))
    return found


# ---------------------------------------------------------------------------
# findViewById<T> 类型安全
#
# `findViewById<Button>(R.id.someTextView)` 会在运行时抛 ClassCastException——
# 这是改动布局时另一类高发事故，而且**编译期不会报错**，只有跑到那一行才崩。
# 这里用 XML 元素的继承关系做静态判定。
# ---------------------------------------------------------------------------

# 键：XML 里出现的元素标签（短名）；值：它的父类集合（不含自身）
_VIEW_HIERARCHY = {
    "View": set(),
    "TextView": {"View"},
    "ImageView": {"View"},
    "EditText": {"TextView", "View"},
    "Button": {"TextView", "View"},
    "com.google.android.material.button.MaterialButton": {"Button", "TextView", "View"},
    "LinearLayout": {"ViewGroup", "View"},
    "FrameLayout": {"ViewGroup", "View"},
    "RelativeLayout": {"ViewGroup", "View"},
    "ScrollView": {"FrameLayout", "ViewGroup", "View"},
    "HorizontalScrollView": {"FrameLayout", "ViewGroup", "View"},
    "Spinner": {"ViewGroup", "View"},
    "TimePicker": {"FrameLayout", "ViewGroup", "View"},
    "CheckBox": {"CompoundButton", "Button", "TextView", "View"},
    "CompoundButton": {"Button", "TextView", "View"},
    "androidx.appcompat.widget.AppCompatTextView": {"TextView", "View"},
    "androidx.appcompat.widget.AppCompatButton": {"Button", "TextView", "View"},
}

# Kotlin 里写的短类型名 -> 规范名
_TYPE_ALIAS = {
    "MaterialButton": "com.google.android.material.button.MaterialButton",
}

_FIND_VIEW_RE = re.compile(
    r"findViewById\s*<\s*([A-Za-z_][A-Za-z0-9_.]*)\s*>\s*\(\s*R\.id\.([A-Za-z_][A-Za-z0-9_]*)\s*\)"
)


def collect_id_element_types(res_dir):
    """收集 @+id 所在 XML 元素的标签，用于 findViewById 类型安全判定。"""
    id_tags = {}
    for path in walk_files(res_dir, {".xml"}):
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for el in tree.iter():
            aid = el.get(ANDROID_NS + "id")
            if aid and aid.startswith("@+id/"):
                id_tags.setdefault(aid[5:], set()).add(el.tag)
    return id_tags


def check_find_view_by_id(java_root, id_tags, rep):
    """校验 findViewById<T>(R.id.x) 的 T 与 XML 元素类型兼容。"""
    checked = 0
    for path in sorted(walk_files(java_root, {".kt", ".java"})):
        text = read_text(path)
        for m in _FIND_VIEW_RE.finditer(text):
            raw_type, view_id = m.group(1), m.group(2)
            tags = id_tags.get(view_id)
            if not tags:
                # R.id 校验已由资源对账负责，这里不重复报
                continue
            want = _TYPE_ALIAS.get(raw_type, raw_type)
            # 短名也要能对上 XML 里的全限定标签
            want_short = want.split(".")[-1]
            known_any = False
            for tag in tags:
                if tag not in _VIEW_HIERARCHY:
                    continue  # 未知元素（自定义 View）不判定，避免误报
                known_any = True
                allowed = {tag} | _VIEW_HIERARCHY[tag]
                allowed_short = {a.split(".")[-1] for a in allowed}
                if want in allowed or want_short in allowed_short:
                    break
            else:
                if not known_any:
                    continue  # 全部是未知元素时跳过，避免误报
                line = text.count("\n", 0, m.start()) + 1
                rep.error(
                    path,
                    "第 %d 行 findViewById<%s>(R.id.%s) 与 XML 元素类型不兼容"
                    "（R.id.%s 在布局中是 %s）——运行时会抛 ClassCastException"
                    % (line, raw_type, view_id, view_id, "/".join(sorted(tags))),
                )
            checked += 1
    rep.note("findViewById 类型安全: 已核对 %d 处" % checked)


def check_members(java_root, rep):
    """对关键类型的成员引用做 usage <-> declaration 对账。"""
    # 上面配置里的路径是相对包根 com/stzb/assistant 写的
    pkg_root = os.path.join(java_root, "com", "stzb", "assistant")
    if not os.path.isdir(pkg_root):
        rep.warn(java_root, "未找到包根 com/stzb/assistant，跳过成员引用对账")
        return
    kt_files = sorted(walk_files(java_root, {".kt"}))

    for spec in MEMBER_CHECKS:
        decl_path = os.path.join(pkg_root, spec["decl_file"].replace("/", os.sep))
        if not os.path.isfile(decl_path):
            rep.error(spec["decl_file"], "成员对账找不到声明文件")
            continue

        # 只匹配 `<Type>.<NAME>`，避免把 `TaskType.valueOf` 之外的东西算进来
        usage_rx = re.compile(
            r"\b%s\.(%s)\.([A-Za-z_][A-Za-z0-9_]*)" % (re.escape(spec["target"]), re.escape(spec["member_type"]))
        )
        used = set()
        for p in kt_files:
            for m in usage_rx.finditer(read_text(p)):
                used.add(m.group(2))

        declared = _collect([decl_path], spec["decl"])
        allowed = declared | spec["builtin"]
        missing = sorted(n for n in used if n not in allowed)
        if missing:
            rep.error(
                spec["decl_file"],
                "%s: 引用了未声明的成员 %s（已声明: %s）"
                % (spec["label"], ", ".join(missing), ", ".join(sorted(declared)) or "无"),
            )
        else:
            rep.note("%s: %d 个引用全部已声明" % (spec["label"], len(used)))

    for obj, rel_decl in OBJECT_MEMBER_CHECKS:
        decl_path = os.path.join(pkg_root, rel_decl.replace("/", os.sep))
        if not os.path.isfile(decl_path):
            rep.error(rel_decl, "对象成员对账找不到声明文件")
            continue
        declared = _collect([decl_path], _DECL_RE)
        # 声明文件自身的方法/属性 + 常见内置
        allowed = declared | {"valueOf", "values", "name", "ordinal", "equals", "hashCode", "toString"}

        # 允许全限定写法（com.stzb.assistant.tactics.AutoPilot.xxx），
        # 但要求对象名前面不是单词字符，避免匹配到 fooAutoPilot 这类名称
        usage_rx = re.compile(r"(?:^|[^\w])%s\.([a-zA-Z_][a-zA-Z0-9_]*)" % re.escape(obj))
        used = set()
        for p in kt_files:
            for m in usage_rx.finditer(read_text(p)):
                used.add(m.group(1))

        missing = sorted(n for n in used if n not in allowed)
        if missing:
            rep.error(
                rel_decl,
                "%s: 引用了未声明的成员 %s" % (obj, ", ".join(missing)),
            )
        else:
            rep.note("%s: %d 个成员引用全部已声明" % (obj, len(used)))



def main():
    # Windows 控制台默认 GBK，中文报告会乱码；强制 UTF-8 输出。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join("client", "app", "src", "main"),
                    help="Android main 源集目录")
    ap.add_argument("--json", default=None, help="把结果写入 JSON 文件")
    ap.add_argument("--selftest", action="store_true",
                    help="只运行内置规则的反例自测（证明规则不是空转），不检查工程")
    args = ap.parse_args()

    if args.selftest:
        return run_verify_refs_selftest()

    root = os.path.abspath(args.root)
    res_dir = os.path.join(root, "res")
    if not os.path.isdir(res_dir):
        print("找不到 res 目录: %s" % res_dir, file=sys.stderr)
        return 2

    rep = Report()
    declared = collect_resources(res_dir, rep)

    xml_files = sorted(walk_files(res_dir, {".xml"}))
    for p in xml_files:
        check_xml(p, declared, rep)

    kt_files = sorted(list(walk_files(root, {".kt", ".java"})))
    for p in kt_files:
        check_kotlin(p, declared, rep)

    # 成员引用对账（枚举常量 / 关键对象成员），捕获"引用了不存在的成员"这类编译错误
    java_root = os.path.join(root, "java")
    if os.path.isdir(java_root):
        check_members(java_root, rep)
        # findViewById<T> 与 XML 元素类型是否兼容（不兼容会在运行时抛 ClassCastException）
        check_find_view_by_id(java_root, collect_id_element_types(res_dir), rep)
        # 领域不变量：关键状态是否只在统一入口内维护
        check_domain_invariants(java_root, rep)
        # 持久化字段对账：字段是否写入/读出成对（漏一个就是"重启后悄悄丢设置"）
        check_persistence_all(java_root, rep)
        # 版本号是否被当成字符串做字典序比较
        check_version_comparisons(java_root, rep)
        # 只写不读的私有字段（死状态）
        check_write_only_fields(java_root, rep)
        # 关键能力的可用性标志是否真的被界面层读取（避免"缺失不可见"）
        check_capability_visibility(java_root, rep)
        # 枚举值是否至少能被启动一次（避免"加了枚举却漏接入口"的死分支）
        check_reachability(java_root, rep)
        # 具名参数是否对应真实参数（此前完全无法查出的编译错误类别）
        check_named_arguments(java_root, rep)
        # 同一作用域内是否有同名同参数的重复声明
        check_duplicate_declarations(java_root, rep)
        # 被点名的能力是否真的有调用方（"摆设"探测）
        check_capability_wiring(java_root, rep)
    else:
        rep.warn(root, "未找到 java 源码目录，跳过成员引用对账")

    # 也校验 AndroidManifest 与其他 xml 目录（manifest 里也有 @drawable 等引用）
    manifest = os.path.join(root, "AndroidManifest.xml")
    if os.path.isfile(manifest):
        check_xml(manifest, declared, rep)

    print("=" * 72)
    print("率土全能管家 · 资源引用一致性校验")
    print("=" * 72)
    print("根目录      : %s" % root)
    print("res XML     : %d 个" % len(xml_files))
    print("Kotlin/Java : %d 个" % len(kt_files))
    for line in rep.info:
        print("信息        : %s" % line)
    print("-" * 72)

    for e in rep.errors:
        print("ERROR  %s\n       %s" % (e["where"], e["message"]))
    for w in rep.warnings:
        print("WARN   %s\n       %s" % (w["where"], w["message"]))

    print("-" * 72)
    print("错误 %d 项，警告 %d 项" % (len(rep.errors), len(rep.warnings)))
    if not rep.errors:
        print("结论: 资源引用一致性校验通过。")
    else:
        print("结论: 校验未通过，请先修复上述 ERROR。")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump({"errors": rep.errors, "warnings": rep.warnings,
                       "info": rep.info}, fh, ensure_ascii=False, indent=2)
        print("报告已写入: %s" % args.json)

    return 0 if not rep.errors else 1


if __name__ == "__main__":
    sys.exit(main())
