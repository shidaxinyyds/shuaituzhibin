#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
游戏知识库（GameProfile）一致性与接线闸门
=========================================

为什么必须有这道闸门
--------------------
知识库是全项目的"游戏事实"唯一来源，但它现在有**三套并行权威**，而且历史上没有
任何一项静态检查盯着它（run_all_checks 里的三十余项一条都没有）。结果就是这类
缺陷可以长期存在而无人察觉：

  1. 【幽灵武将】land_suggestions 把"赵云 / 关妹 / 周仓 / 魏续"列为黑白名单，
     但守将库里根本没有这几个人。OcrLite 通道会先用 filterKnownHeroes() 过滤
     不在库里的名字，于是这些黑名单**形同虚设**——写着"避开赵云"，实际空气。
  2. 【双源打架】SlgRagEngine.queryLandDefender() 曾自带一套硬编码危险将名单与
     兵力 when 表（Lv5=5800），而知识库是 5500；软柿子雷达真的在用后者。
     热更新新增危险武将时，守军评估通道生效、雷达通道不生效。
     （P1 已改为只读知识库；本闸门继续对账，防止重新引入第二套表。）
  3. 【切库串数据】三战只有 9 个守将、且缺 CANCEL/TAX/UPGRADE/FORGE 键；
     切到三战后 RAG 仍按率土武将名给结论。
  4. 【坏字符串投屏】展示字段里混进了机器翻译残留（如 "不 numero 控"），
     会原样打在悬浮窗上。
  5. 【死配置】字段被定义、被序列化、被校验函数引用，但**没有任何决策读取它**。
     “能力写对但没人调”= 死代码，热更永远改不动真实行为。
     只在 ui/ 里印一行也算未接线：改数值只会改一行字，不会改行为。
     历史上被这个规则揪出来的 8 项已逐条处置：maxMorale / maxStamina / moraleStandard
     接进了部队卡片感知，immunityDurationSec 接成了破免兜底，pavingStepIntervalMs
     接进了铺路步进；immunityBreakPrecisionMs（与 immunityPaddingMs 重复）与
     fallbackRoiRatio（无人读，且语义是“按猜的坐标开火”）已删除；
     targetPackage 接成了前台闸门。剩下来的新死字段会在这里被拦住。
  6. 【云端产物漂移】pipeline/rate_of_land.json 里还留着已从 schema 删除的
     screen_virtual_*，同时缺 CANCEL 键与 vision_policy。同版本一旦被端上接受，
     内容更弱的缓存会静默降级内置库。
  7. 【名单与天梯互打】同一武将在地块建议里被当“软柿子”推荐，在守将库里却是
     hard/danger 档（旧库：张勋、曹仁、纪灵）——两个结论相差一个团灭。

本闸门把这些判据固化成机器可查的规则。反直觉但重要的是：**它今天必然报红**，
那份红清单就是要修的东西；修一项绿一项，绿了之后不许再漂回去。

用法
----
    python tools/check_knowledge_base.py                  # 全量检查
    python tools/check_knowledge_base.py --selftest        # 反例注入自测
    python tools/check_knowledge_base.py --list-fields     # 打印字段接线矩阵

退出码：0 = 全部通过；1 = 有违规（打印每条的原因与修法）。
"""

import argparse
import io
import json
import os
import re
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(REPO, "client", "app", "src", "main", "java", "com", "stzb", "assistant")
KNOWLEDGE = os.path.join(JAVA, "knowledge")
PROFILE_JSON_DIR = os.path.join(REPO, "pipeline")

# 真按键枚举之外的非按键常量（它们不参与"每个键都要有 KB 词条"的要求）
NON_BUTTON_CONSTANTS = {
    "OCR_UNAVAILABLE", "NO_TEXT", "NO_MATCH", "TAP_DISPATCH_FAILED", "NONE", "UNKNOWN",
}

# 展示用/内部用的合法豁免字段：字段名 -> 为什么允许没有决策消费者
FIELD_EXEMPT = {
    "gameName": "仅悬浮窗标题展示",
    "profileVersion": "由 KnowledgeBaseManager 内部做版本比较",
    "description": "仅详情展示",
    "visionPolicy": "由 setActiveProfile() 赋给 VisionRuntime.policy（知识库内部即终点）",
    "actionKey": "ButtonDef 自身的 key 镜像，按 key 索引即可",
    "landLevel": "LandSuggestion 自身索引键",
    "targetPackage": None,  # 不许豁免：前台闸门必须消费它
}

# 允许出现在中文展示字段里的拉丁token（技术缩写白名单）
LATIN_ALLOW = {
    "lv", "roi", "ocr", "hsv", "ms", "pk", "ai", "pvp", "pve", "npc", "sdk", "apk",
    "cd", "buff", "hp", "atk", "def", "fps", "s", "a", "b", "c", "x", "y", "vs", "s1",
}

# 只负责"把值印到屏幕上"的目录：字段若仅被这些目录引用，热更新改了数值
# 也只会改变一行文字，不会改变任何行为——仍按未接线处理。
DISPLAY_CONSUMER_DIRS = ("ui",)

# 纯数据定义文件：它们只是在"填值"，不能当成消费者。
# 写成**有序列表**而不是 set：闸门要逐条报告它们，set 的迭代序会随 PYTHONHASHSEED 变，
# 同一份代码两次跑出的报告顺序不同，CI 上的 diff 就没法看了。
KB_DATA_FILES = ["StzbKnowledgeBase.kt", "SgzKnowledgeBase.kt"]

# GameProfile.kt 里这几段只是在序列化/自校验，读字段不等于用它做决策；
# 若算作消费者，每个字段都会被打成"已接线"，死配置永远查不出来。
SELF_SERVING_FUNS = {"toJson", "fromJson", "validateFor", "compareVersion"}


def read(path):
    with io.open(path, encoding="utf-8") as fh:
        return fh.read()


def strip_kt_comments(text):
    """粗略去掉行注释与块注释，避免注释里的示例代码污染判定。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


# ---------------------------------------------------------------------------
# 1. 解析内置知识库（Kotlin DSL 形式）
# ---------------------------------------------------------------------------

_KV = r'(\w+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([\w.]+))'


def parse_kb_file(path):
    """把 StzbKnowledgeBase.kt / SgzKnowledgeBase.kt 解析成可比较的结构。"""
    src = strip_kt_comments(read(path))
    prof = {
        "file": os.path.basename(path),
        "game_id": "",
        "game_name": "",
        "description": "",
        "target_package": "",
        "profile_version": "",
        "vision_policy": "SLG_DEFAULT",
        "rules": {},
        "buttons": {},
        "heroes": {},          # tier -> [(name, tag, score, desc, tip)]
        "lands": [],           # {level, soldiers, safe, black, note, garrison}
        "tactical": {},
        "watchdog": [],
        # 下面是 P7 从引擎里迁出来的三张表（组 ID → 词 / 武将 → 速度 / 机制词 → 解读）：
        # 它们同样是"云端产物必须带上"的字段，漏导出就等于热更一份把词表清空的配置。
        "scene_keywords": {},
        "hero_speed": {},
        "mechanic_notes": {},
    }

    def s(key, default=""):
        m = re.search(r'\b%s\s*=\s*"((?:[^"\\]|\\.)*)"' % key, src)
        return m.group(1) if m else default

    prof["game_id"] = s("gameId")
    prof["target_package"] = s("targetPackage")
    prof["profile_version"] = s("profileVersion")
    # 下面两项不参与任何判定，只服务导出脚本（云端产物必须带上与内置一模一样的名字与描述）。
    # 取不到时宁可留空让导出脚本报错中断，也不能拿默认值编一份看起来对的产物。
    prof["game_name"] = s("gameName")
    prof["description"] = s("description")
    vp = re.search(r'\bvisionPolicy\s*=\s*VisionPolicy\.(\w+)', src)
    if vp:
        prof["vision_policy"] = vp.group(1)

    # rules / tactical_defaults：各自括号内的 key = 标量
    for field, sink in (("GameRules", prof["rules"]), ("TacticalDefaults", prof["tactical"])):
        blk = _balanced_block(src, field + "(")
        if blk:
            for k, vs, vn in re.findall(r'(\w+)\s*=\s*(?:"((?:[^"\\]|\\.)*)"|(-?[\d.]+[Lf]?|true|false|[A-Za-z_][\w.]*))', blk):
                sink[k] = _scalar(vs if vs != "" else vn)
            # listOf(...) 会被上面的标量正则只截到 "listOf" 这个词。补回真实列表：
            # 否则导出脚本拿不到 paving_default_slots / siege_demolition_slots，
            # 要么报错、要么静静少导一个键（而少一个键到端上就是走默认值）。
            for k, v in sorted(sink.items()):
                if v != "listOf":
                    continue
                m = re.search(r'\b%s\s*=\s*listOf\(([^)]*)\)' % re.escape(k), blk)
                if not m:
                    continue
                sink[k] = [_scalar(x.strip()) for x in m.group(1).split(",") if x.strip()]

    # 语义按键
    btn_block = _balanced_block(src, "semanticButtons = mapOf(")
    if btn_block:
        for key, primary, aliases in re.findall(
                r'"([A-Z_]+)"\s+to\s+ButtonDef\(\s*"[^"]*"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*listOf\(([^)]*)\)',
                btn_block):
            prof["buttons"][key] = {
                "primary": primary,
                "aliases": re.findall(r'"((?:[^"\\]|\\.)*)"', aliases),
            }

    # 守将库
    for tier, key in (("danger", "dangerHeroes"), ("hard", "hardHeroes"),
                      ("moderate", "moderateHeroes"), ("safe", "safeHeroes")):
        blk = _balanced_block(src, key + " = listOf(")
        rows = []
        if blk:
            for m in re.finditer(r'HeroEntry\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*(\d+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"', blk):
                rows.append((m.group(1), m.group(2), int(m.group(3)), m.group(4), m.group(5)))
        prof["heroes"][tier] = rows
        if not rows:  # 位置参数简写形式 HeroEntry("a", "b", 5, "c", "d")
            blk2 = _balanced_block(src, key)
            if blk2:
                for m in re.finditer(r'"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*(\d+)\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"', blk2):
                    rows.append((m.group(1), m.group(2), int(m.group(3)), m.group(4), m.group(5)))
                prof["heroes"][tier] = rows

    # 地建议
    land_blk = _balanced_block(src, "landSuggestions = mapOf(")
    if land_blk:
        for m in re.finditer(r"(\d+)\s+to\s+LandSuggestion\(\s*(\d+)\s*,\s*(\d+)\s*,\s*listOf\(([^)]*)\)\s*,\s*listOf\(([^)]*)\)\s*,\s*\"((?:[^\"\\]|\\.)*)\"(?:\s*,\s*defenderTotalSoldiers\s*=\s*(\d+))?", land_blk):
            prof["lands"].append({
                "level": int(m.group(2)),
                "soldiers": int(m.group(3)),
                "safe": re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(4)),
                "black": re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(5)),
                "note": m.group(6),
                "garrison": int(m.group(7)) if m.group(7) else 0,
            })

    wd = _balanced_block(src, "watchdogKeywords = listOf(")
    if wd:
        prof["watchdog"] = re.findall(r'"((?:[^"\\]|\\.)*)"', wd)

    # 场景/战报词表：sceneKeywords = mapOf("组ID" to listOf("词", ...), ...)
    sk = _balanced_block(src, "sceneKeywords = mapOf(")
    if sk:
        for group_id, words in re.findall(r'"([A-Za-z0-9_]+)"\s+to\s+listOf\(([^)]*)\)', sk):
            prof["scene_keywords"][group_id] = re.findall(r'"((?:[^"\\]|\\.)*)"', words)

    # 武将基础速度：heroBaseSpeed = mapOf("武将名" to 83, ...)
    hb = _balanced_block(src, "heroBaseSpeed = mapOf(")
    if hb:
        for name, value in re.findall(r'"((?:[^"\\]|\\.)*)"\s+to\s+(\d+)', hb):
            prof["hero_speed"][name] = int(value)

    # 守军机制解读：pveMechanicNotes = mapOf("暴走" to "……", ...)
    pn = _balanced_block(src, "pveMechanicNotes = mapOf(")
    if pn:
        for word, note in re.findall(r'"((?:[^"\\]|\\.)*)"\s+to\s*"((?:[^"\\]|\\.)*)"', pn):
            prof["mechanic_notes"][word] = note
    return prof


def _scalar(v):
    if isinstance(v, (int, float)):
        return v
    s = str(v).strip()
    if s in ("true", "false"):
        return s == "true"
    t = s.rstrip("Lf")
    try:
        return int(t) if "." not in t else float(t)
    except ValueError:
        return s


def _balanced_block(text, opener):
    """从 `opener` 之后取到配对右括号的内容（不含外层括号）。"""
    idx = text.find(opener)
    if idx < 0:
        return ""
    i = idx + len(opener)
    depth = 1
    start = i
    in_str = False
    esc = False
    while i < len(text) and depth:
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return text[start:i]
        i += 1
    return text[start:min(len(text), start + 6000)]


# ---------------------------------------------------------------------------
# 2. 解析 schema、按键枚举、RAG 私有表、云端 JSON
# ---------------------------------------------------------------------------

def _block_after(src, idx, open_ch="("):
    """从 idx 起找到第一个 open_ch，返回其配对括号/花括号内的内容。

    扫描从 open_ch **后一位**开始：若从 open_ch 本身开始，那个开头括号会被
    再计一层深度，导致跳过真正的结尾、把后面几个类的内容一起吃进来。
    """
    close = {"(": ")", "{": "}", "[": "]"}[open_ch]
    i = src.find(open_ch, idx)
    if i < 0:
        return ""
    start = i + 1
    depth, in_str, esc, i = 1, False, False, start
    while i < len(src) and depth:
        ch = src[i]
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == open_ch:
            depth += 1
        elif ch == close:
            depth -= 1
            if depth == 0:
                return src[start:i]
        i += 1
    return src[start:min(len(src), start + 8000)]


SCHEMA_CLASSES = ("GameProfile", "GameRules", "TacticalDefaults", "ButtonDef")


def parse_schema_fields():
    """从 GameProfile.kt 提取各数据类的属性名。

    这些类的属性全部写在**主构造函数括号**里（多行），所以不能拿
    “类名到第一个 {” 之间的单行文本去匹配，必须取括号体。
    """
    src = strip_kt_comments(read(os.path.join(KNOWLEDGE, "GameProfile.kt")))
    fields = {}
    for m in re.finditer(r"\bdata class\s+(\w+)", src):
        cls = m.group(1)
        if cls not in SCHEMA_CLASSES:
            continue
        ctor = _block_after(src, m.end(), "(")
        names = set(re.findall(r"\bval\s+(\w+)\s*:", ctor))
        body = _block_after(src, src.find("(", m.end()), "{") if "{" in src[m.end():m.end() + 4000] else ""
        names |= set(re.findall(r"^\s*(?:val|var)\s+(\w+)\s*:", body, re.M))
        fields[cls] = sorted(names)
    return fields


def parse_serialized_keys():
    """toJson() 里写出的 JSON 键集合（判定"字段能不能被热更改动"）。"""
    src = strip_kt_comments(read(os.path.join(KNOWLEDGE, "GameProfile.kt")))
    keys = set(re.findall(r'put\("([a-z0-9_]+)"', src))
    # 另外那些只作为嵌套对象内部的键也属于契约的一部分
    return keys


def parse_profile_root_keys():
    """fromJson 在**根对象**上读的键 = 云端产物必须携带的键。

    为什么用 fromJson 而不是 toJson 当基准：热更这条通道真正的能力边界是
    "端上会去读哪些键"。导出脚本漏掉其中任何一个，产物看起来正常、
    端上却会走 `optXxx(默认值)`，等于用默认值悄悄覆盖掉玩家的热更数据
    （P7 实测：scene_keywords / hero_base_speed / pve_mechanic_notes / map_coord_max
    曾经全部不在导出表里）。
    """
    src = strip_kt_comments(read(os.path.join(KNOWLEDGE, "GameProfile.kt")))
    return set(re.findall(r'\broot\s*\.\s*opt\w+\s*\(\s*"([a-z0-9_]+)"', src))


def parse_button_enum():
    """取 ButtonType 枚举**体内**的成员名。

    注意枚举声明是 `enum class ButtonType(val primaryKeyword: String, ...) {`：
    紧跟类名的括号是**构造参数**，成员在后面的花括号体里。只拓前者会得到空列表，
    于是所有知识库词条都会被误报成“引擎无对应按键”。
    """
    src = strip_kt_comments(read(os.path.join(JAVA, "ocr", "StzbUiMatcher.kt")))
    i = src.find("enum class ButtonType")
    if i < 0:
        return []
    j = src.find("{", i)
    if j < 0:
        return []
    body = _balanced_block(src, src[i:j + 1])
    names = re.findall(r"([A-Z][A-Z0-9_]*)\s*\(", body)
    seen, ordered = set(), []
    for n in names:
        if n not in seen and n not in NON_BUTTON_CONSTANTS:
            seen.add(n)
            ordered.append(n)
    return ordered


def _snake(name):
    """Kotlin camelCase -> JSON snake_case（两套命名必须对齐才能比较）。"""
    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()



# 引擎里允许出现的"自用中文常量表"：这些词是引擎加工语料时的判据，不会作为
# 游戏知识念给玩家，也不属于任何单一游戏的文案（换游戏时它跟着脱敏逻辑走，不跟知识走）。
ENGINE_TABLE_ALLOWED = {"CORPUS_TROOP_CONTEXT"}


def _sanitizer_shell():
    """端侧脱敏正则描述的字段清单。解析器与夹具都走这里。

    只写一份：上一轮的失效就是“解析器改了返回结构、调用侧还按旧键取值”。
    两边共用同一个壳子后，键名漂移会在当场报错，而不是静默认成“查不到”。
    """
    return {"ctx_lit": "", "tail_lits": [], "words": [], "token": "",
            "collapses": [], "digits": "", "ok": False}


def _rag_shape_shell(applicable=True):
    """parse_rag_tables 返回结构的唯一定义处。"""
    return {"applicable": applicable, "has_private_roster": False,
            "engine_tables": [],
            "soldiers": {}, "seed_entries": [], "corpus_texts": [],
            "sanitizer": _sanitizer_shell()}


def _kt_unescape(lit):
    """把 Kotlin 字符串字面量还原成它真正代表的正则文本。

    源文件里写 `\\d{3,6}`（两个反斜杠），运行时是 `\d{3,6}`（一个）。
    解析器必须按**运行时的形状**去读，否则锚点会对着源码里的字节数反斜杠。
    """
    return lit.replace("\\\\", "\\").replace('\\"', '"')


def parse_rag_sanitizer(plain):
    """把端侧脱敏正则**原句**从 Kotlin 源码里抽出来，Python 侧只做镜像。

    为什么不在 Python 里重新写一份“窗口/位数/语汇表”：抄来的那一份一旦和端侧脱节，
    本闸门就会拿“理想中的正则”去判定——报出来的红全是假的，而真实的漏网写法反而查不出来。
    所以直接把 `$ctx` 模板句拿来编译：端侧改一个位数，镜像自动跟着变；
    抽不全则当场 rag-parser-blind，而不是静返回“看起来很干净”的空结果。
    """
    cfg = _sanitizer_shell()

    m = re.search(r"CORPUS_TROOP_CONTEXT\s*=\s*listOf\(([^)]*)\)", plain)
    if m:
        cfg["words"] = re.findall(r'"([^"]+)"', m.group(1))

    body = _block_after(plain, plain.find("private fun stripCorpusTroopNumbers("), "{")
    lits = [_kt_unescape(x) for x in re.findall(r'Regex\("((?:[^"\\]|\\.)*)"', body)]
    cfg["ctx_lit"] = next((s for s in lits if "$ctx" in s), "")
    # 数字在前的写法是**多条**正则（先"15000 兵"，再"6000 左右"），不是一条：
    # 只留第一条的话，端侧新补的写法在闸门里等于不存在，报出来的绿是假的。
    # findall 保持源码顺序，因此这里的列表顺序 == Kotlin 里的应用顺序。
    cfg["tail_lits"] = [s for s in lits if "$ctx" not in s and s.startswith("\\d")]

    dm = re.search(r"DIGITS_RX\s*=\s*Regex\(" + '"((?:[^"\\\\]|\\\\.)*)"', plain)
    if dm:
        cfg["digits"] = _kt_unescape(dm.group(1))

    # 收尾的“以知识库为准”归并表也照源码取，连替换词本身都不自己发明
    cfg["collapses"] = re.findall(r'\.replace\("([^"]*)",\s*"([^"]*)"\)', body)
    if cfg["collapses"]:
        cfg["token"] = cfg["collapses"][0][1]

    cfg["ok"] = bool(cfg["words"]) and bool(cfg["ctx_lit"]) and bool(cfg["tail_lits"]) \
        and bool(cfg["digits"]) and bool(cfg["collapses"]) and bool(cfg["token"])
    return cfg


def parse_seed_entries(plain):
    """从 Kotlin 源码文本里抽出保底种子条目（真跑与 selftest 共用这一条解析路径）。

    定位方式是"**每一条 `RagEntry(` 单独取参数块，第一个字符串必须是 SEED- 开头的 id**"，
    两种历史事故都挡住：
      1. 只按 `RagEntry(` 全文件搜会把 data class 声明和 .bin 加载器也算成"条目"，
         那些位置根本没有文案，判定时等于拿空字符串凑数 —— 现在 id 前缀不匹配就丢弃；
      2. 锚点写死函数名会漂移——`loadBuiltinSeedKnowledge()` 把种子拆进
         `seedEntries()`（为了按检索通道补齐）之后，旧锚点扫到 0 条，
         全靠 rag-parser-blind 护栏当场响才没变成"查了个空却报绿"。
    种子 id 的 `SEED-` 前缀是端侧代码与闸门之间的**书面约定**（见 SlgRagEngine.seedEntries）。
    这不削弱 fail-closed：种子被删光、或 id 前缀被改掉，这里都解析为空，护栏照响。
    """
    entries = []
    idx = 0
    while True:
        i = plain.find("RagEntry(", idx)
        if i < 0:
            break
        blk = _balanced_block(plain[i:], "RagEntry(")
        idx = i + 1
        strs = re.findall(r'"((?:[^"\\]|\\.)*)"', blk)
        if not (strs and strs[0].startswith("SEED-")):
            continue
        joined = " ".join(strs)
        hint = None
        if "危险" in joined or "严禁" in joined or "翻车" in joined:
            hint = "danger"
        elif "软柿" in joined or "白给" in joined or "推荐首开" in joined:
            hint = "safe"
        entries.append({"names": strs, "hint": hint})
    return entries


def parse_engine_data_tables(plain):
    """揪出"引擎自持的一张游戏数据表"：`val 全大写名 = listOf/mapOf/setOf(...)` 且表里有中文常量。

    为什么这是 P7 的核心不变量：`SlgRagEngine` 曾经装着 PVE/PVP 语汇、战法名、武将名、
    武将基础速度、守军机制解读六张率土专属表。它们既让"加一款游戏零代码改动"变成空话
    （词表换不掉），又构成第二权威（热更改得到知识包，改不到引擎里的这一份）。
    现在这些全部走 `GameProfile`，引擎里只该剩下**组合逻辑**。

    白名单只放"引擎自己干活要用的词"（CORPUS_TROOP_CONTEXT 是语料脱敏的语汇判据，
    不是念给玩家的游戏知识），以及 VOCAB_* 这种**组 ID** 常量（值是 ASCII 组名）。
    漏登记的新表一律当场响，而不是"看起来像个常量所以放过"。
    """
    hits = []
    for m in re.finditer(r"\bval\s+([A-Z][A-Z0-9_]*)\s*(?::[^=\n]+)?=\s*(listOf|mapOf|setOf)\(", plain):
        name, kind = m.group(1), m.group(2)
        if name in ENGINE_TABLE_ALLOWED or name.startswith("VOCAB_"):
            continue
        blk = _balanced_block(plain[m.start(2):], kind + "(")
        if re.search(r'"[^"]*[\u4e00-\u9fff]', blk):
            hits.append(name)
    return sorted(set(hits))


def parse_rag_tables():
    """SlgRagEngine 里那些**会念给玩家**的第二数据源。

    历史教训（本轮实测发现）：本函数原来只抓三张私有硬编码表
    （dangerKeywords / isSafe 名单 / 兵力 when 表）。那三张表在前几轮已被删掉、
    改为直接读 defenderDb，于是两个锚点静默失配（兵力表还换了写法），
    而 `--selftest` 照样全绿——它注入的是合成 rag，只测判定函数，从不测解析器。
    真实结论是：**那三条规则今天一条也没执行过**。
    因此这里同时做三件事：
      1. 兵力锚点兼容 `return when (level) {`；
      2. 把“名单表不该再存在”本身立为不变量，而不是去一个已经搬空的表；
      3. 补上今天真正还活着的表面：保底种子语料、JSONL 语料、已发布 .bin。
    解析不到不等于没问题：返回的结构里带着“该表是否存在”的标记，
    check_rag_corpus 的 rag-parser-blind 护栏会当场响，而不是安静地返回空名单。
    """
    src = read(os.path.join(JAVA, "ai", "rag", "SlgRagEngine.kt"))
    plain = strip_kt_comments(src)
    rag = _rag_shape_shell()
    rag["has_private_roster"] = bool(
        re.search(r"val dangerKeywords\s*=|val isSafe\s*=\s*when\s*\{", plain))
    # 更一般的形状：引擎里任何一张"全大写常量 = 含中文的表"都是自持的游戏数据。
    # 上面那条只认两个旧名字，删表的人换个名字（HERO_BASE_SPEED 就是这么活下来的）就抓不到。
    rag["engine_tables"] = parse_engine_data_tables(plain)
    rag["sanitizer"] = parse_rag_sanitizer(plain)

    # 兜底兵力表：既允许 `return when (level) {`，也允许 `val x = when (level) {`
    for blk3 in re.finditer(r"when \(level\) \{", plain):
        blk = _balanced_block(plain[blk3.start():], "when (level) {")
        for lvl, num in re.findall(r"(\d+)\s*->\s*(\d+)", blk):
            rag["soldiers"].setdefault(int(lvl), set()).add(int(num))

    # 保底种子语料：解析逻辑独立成 parse_seed_entries，好让 selftest 能拿
    # "函数改名/种子删光"两种源码直接喂给**同一个解析器**验漂移。
    rag["seed_entries"] = parse_seed_entries(plain)
    for e in rag["seed_entries"]:
        rag["corpus_texts"] += [("kotlin-seed", s) for s in e["names"]]

    # 已入库的语料 JSONL（生成 .bin 的源头）
    j = os.path.join(REPO, "tools", "a_plus_plus", "corpus_stzb.jsonl")
    if os.path.isfile(j):
        for line in io.open(j, encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            for k in ("title", "content", "advice"):
                if d.get(k):
                    rag["corpus_texts"].append(("corpus-jsonl", str(d[k])))

    # 已发布的二进制资产：只取可读中文串，不拿向量字节当文本
    b = os.path.join(REPO, "client", "app", "src", "main", "assets", "models",
                     "slg_knowledge_vector_hnsw.bin")
    if os.path.isfile(b):
        with open(b, "rb") as fh:
            blob = fh.read().decode("utf-8", "ignore")
        for run in re.findall(r"[\u4e00-\u9fff][\u4e00-\u9fffA-Za-z0-9:：()（）~＋+.\s|★☆，,。；;、×/]{6,}", blob):
            rag["corpus_texts"].append(("shipped-bin", run))

    # 端侧脱敏正则：整块形状由 parse_rag_sanitizer 从源码里抽，本函数不再自己描一遍
    return rag


# 宽判据：“这个数字看起来是在说兵力”。它比端侧脱敏正则更宽松，
# 目的不是自己拦住数字，而是找出“看起来像兵力读数却没能被脱敏拦住”的写法。
_RAG_TROOP_HINT = re.compile(
    r"(?:兵力|出兵|点兵|带兵|主力|守军|战损|开荒|出征)[^0-9\n]{0,14}\d{3,6}"
    r"|\d{3,6}[+~～-]?[^0-9\n]{0,4}(?:兵|兵力|守军)")


def _strip_corpus_numbers(text, cfg):
    """Kotlin stripCorpusTroopNumbers 的 Python 镜像（正则、语汇、归并全部取自源码）。

    cfg["ok"] 为假时**绝不回退成“什么都不抹”的镜像**：那会把“解析器瞎了”伪装成
    “语料很干净”。调用方（check_rag_corpus）必须先报 rag-parser-blind。
    """
    if not cfg["ok"]:
        raise RuntimeError("_strip_corpus_numbers 拿到了不完整的端侧正则描述")
    token, digits = cfg["token"], cfg["digits"]

    def sub(mm):
        return re.sub(digits, token, mm.group(0))

    out = text
    for w in cfg["words"]:
        out = re.sub(cfg["ctx_lit"].replace("$ctx", re.escape(w)), sub, out)
    for lit in cfg["tail_lits"]:
        out = re.sub(lit, sub, out)
    for junk, clean in cfg["collapses"]:
        out = out.replace(junk, clean)
    return out


def parse_cloud_profiles():
    """pipeline/*.json —— 要上传云端的知识库产物。"""
    res = []
    for fn in sorted(os.listdir(PROFILE_JSON_DIR)):
        if not fn.endswith(".json"):
            continue
        path = os.path.join(PROFILE_JSON_DIR, fn)
        try:
            data = json.loads(read(path))
        except Exception as e:
            res.append({"file": fn, "error": "JSON 解析失败: %s" % e, "data": {}})
            continue
        if "game_id" not in data:
            continue
        heroes = {}
        for tier, key in (("danger", "danger_heroes"), ("hard", "hard_heroes"),
                          ("moderate", "moderate_heroes"), ("safe", "safe_heroes")):
            heroes[tier] = [(h.get("name", ""), h.get("tag", ""), int(h.get("threat_score", 0)))
                            for h in (data.get("defender_db", {}).get(key) or [])]
        lands = [{
            "level": l.get("land_level"),
            "soldiers": l.get("recommended_soldiers"),
            "safe": l.get("safe_heroes") or [],
            "black": l.get("blacklist_heroes") or [],
            "note": l.get("note", ""),
            # 守军总兵力也必须参与比对：它是军师锦囊里"守军约 xx"那句话的唯一出处，
            # 产物漏掉这个键时端上拿默认 0，锦囊就从此不再提守军量（静默少一半信息）。
            "garrison": l.get("defender_total_soldiers", 0),
        } for l in (data.get("defender_db", {}).get("land_suggestions") or [])]
        res.append({
            "file": fn,
            "error": "",
            "data": {
                "game_id": data.get("game_id", ""),
                "target_package": data.get("target_package", ""),
                "profile_version": data.get("profile_version", ""),
                "rules": data.get("rules", {}),
                "buttons": {k: {"primary": v.get("primary_keyword", ""),
                                "aliases": v.get("aliases") or []}
                            for k, v in (data.get("semantic_buttons") or {}).items()},
                "heroes": heroes,
                "lands": lands,
                "tactical": data.get("tactical_defaults", {}),
                "watchdog": data.get("watchdog_keywords") or [],
                # P7 迁出来的三张表：产物必须逐条带上（少一组 = 热更后端上判定整套失效）
                "scene_keywords": data.get("scene_keywords") or {},
                "hero_speed": data.get("hero_base_speed") or {},
                "mechanic_notes": data.get("pve_mechanic_notes") or {},
                "top_keys": set(data.keys()),
            },
        })
    return res


# ---------------------------------------------------------------------------
# 3. 规则判定（全部接受纯数据结构，便于离线反例自测）
# ---------------------------------------------------------------------------

def _f(check, detail, fix):
    return {"check": check, "detail": detail, "fix": fix}


def check_display_sanity(prof):
    """展示字段里的机器翻译残留 / 空串 / 跨档重名。"""
    bad = []
    rows = []
    for items in prof["heroes"].values():
        for name, tag, score, desc, tip in items:
            rows.append(("%s/%s" % (prof["file"], name), tag, desc, tip))
    for land in prof["lands"]:
        rows.append(("%s/Lv%d" % (prof["file"], land["level"]), "", land["note"], ""))

    # 空武将名比"少一个武将"严重得多：端侧 DefenderEvaluator.findMatch 是
    # name.contains(hero.name) || hero.name.contains(name) 的双向包含，
    # 空串被任意字符串包含，于是这条记录会把**每一个**读到的守将都判成它所在的难度档。
    # 天梯照样出结论、界面照样显示，只是全错 —— 正是必须 fail-closed 的那一类。
    # 端上 GameProfile.validateFor 已同步拒绝采纳整份包，这里负责在出厂侧就拦住。
    for tier, items in prof["heroes"].items():
        for name, _tag, _score, _desc, _tip in items:
            if not (name or "").strip():
                bad.append(_f("hero-empty-name",
                              "%s 的 %s 档里有一条 name 为空/纯空白的守将" % (prof["file"], tier),
                              "删掉这条或补上武将名；空名会命中任意守将，整块地评级的唯一决定者就是它"))

    for where, *texts in rows:
        for t in texts:
            if not t:
                continue
            hit = None
            for tok in re.findall(r"[A-Za-z]{3,}", t):
                if tok.lower() not in LATIN_ALLOW:
                    hit = tok
                    break
            if hit:
                bad.append(_f("latin-contamination",
                              "%s 的展示文本里混进了拉丁词 '%s'（疑似翻译/OCR 残留：%s）" % (where, hit, t[:42]),
                              "改成玩家看得懂的说法；确属技术缩写请加进 LATIN_ALLOW 白名单"))
    seen = {}
    for tier, items in prof["heroes"].items():
        for name, tag, score, desc, tip in items:
            if name in seen and seen[name] != tier:
                bad.append(_f("duplicate-hero-tier",
                              "%s 的 %s 同时出现在 %s 与 %s 两个难度档" % (prof["file"], name, seen[name], tier),
                              "同一武将只能属于一个难度档，否则 findMatch 的列表顺序会决定结论"))
            seen[name] = tier
    return bad


def check_tier_score(prof):
    """threat_score 必须与所属难度档一致，否则天梯失去意义。"""
    expect = {"danger": (4, 5), "hard": (3, 4), "moderate": (2, 2), "safe": (1, 1)}
    bad = []
    for tier, items in prof["heroes"].items():
        lo, hi = expect.get(tier, (1, 5))
        for name, tag, score, desc, tip in items:
            if not (lo <= score <= hi):
                bad.append(_f("tier-score-mismatch",
                              "%s 的 %s 在 %s 档但 threat_score=%d（该档应为 %d~%d）"
                              % (prof["file"], name, tier, score, lo, hi),
                              "resolveTier() 取两者更危险的一侧，分数与档位矛盾会让评级被静默改写"))
    return bad


def check_ghost_heroes(prof):
    """land_suggestions 的黑白名单必须在守将库中存在。"""
    known = {n for items in prof["heroes"].values() for n, *_ in items}
    bad = []
    for land in prof["lands"]:
        for role, names in (("软柿名单", land["safe"]), ("黑名单", land["black"])):
            for n in names:
                if n not in known:
                    bad.append(_f("ghost-hero",
                                  "Lv%d 的%s写了 %s，但守将库里没有此人" % (land["level"], role, n),
                                  "把 %s 补进 HeroEntry，或从名单里删掉——留着等于写了个不生效的规则" % n))
    return bad


def check_button_coverage(prof, enum_names):
    """引擎的每个按键类型都必须在知识库里有条目，否则只能吃 Kotlin 硬编码。"""
    missing = [k for k in enum_names if k not in prof["buttons"]]
    extra = sorted(set(prof["buttons"]) - set(enum_names))
    bad = []
    if missing:
        bad.append(_f("button-key-missing",
                      "%s 缺按键词条：%s" % (prof["file"], ", ".join(missing)),
                      "补进 semanticButtons；否则热更新无法为这些键增改别名"))
    if extra:
        bad.append(_f("button-key-unknown",
                      "%s 有条词但引擎无对应按键：%s" % (prof["file"], ", ".join(extra)),
                      "要么在 ButtonType 里补该键，要么删掉这条死词条"))
    for key, b in prof["buttons"].items():
        if not b["primary"]:
            bad.append(_f("button-empty-primary", "%s 的 %s 主关键字为空" % (prof["file"], key),
                          "主关键字是分级匹配权重最高的依据，不能为空"))
    return bad


def check_rules_sanity(prof):
    r = prof["rules"]

    def num(k, default=None):
        v = r.get(k, default)
        return v if isinstance(v, (int, float)) else default

    bad = []
    stamina, per = num("maxStamina", 120), num("staminaPerAction", 20)
    if per and stamina and per > stamina:
        bad.append(_f("rules-impossible", "staminaPerAction=%s 大于 maxStamina=%s" % (per, stamina),
                      "单次消耗不可能超过上限，requiredStaminaNow() 会永远不满足"))
    std, mx = num("moraleStandard", 100), num("maxMorale", 120)
    if std and mx and std > mx:
        bad.append(_f("rules-impossible", "moraleStandard=%s 大于 maxMorale=%s" % (std, mx),
                      "基准士气不可能高于士气上限"))
    minpav = num("minMoraleForPaving", 100)
    if minpav and mx and minpav > mx:
        bad.append(_f("rules-impossible", "minMoraleForPaving=%s 高于 maxMorale=%s，铺路永远无法满足士气条件" % (minpav, mx),
                      "把阈值降到可达范围内"))
    for k in ("nightWindowStartHour", "nightWindowEndHour"):
        v = num(k, 0)
        if not (0 <= v <= 23):
            bad.append(_f("rules-impossible", "%s=%s 不是合法小时" % (k, v), "取 0~23"))
    mul = num("nightStaminaMultiplier", 1.0)
    if mul is not None and mul < 1.0:
        bad.append(_f("rules-impossible", "nightStaminaMultiplier=%s <1（夜战比白天还省体力？）" % mul,
                      "倍率只可能 >=1；该游戏若无夜战惩罚请写 1.0"))
    pad = num("immunityPaddingMs", 1000)
    if pad is not None and pad > 60000:
        bad.append(_f("rules-impossible", "immunityPaddingMs=%s 超过 1 分钟" % pad,
                      "破免提前量是毫秒级补偿，超过 60s 说明单位写错了"))
    # 地图边界：端侧四处判定都走 rules.isValidWorldCoord(x, y)（x/y ∈ 1..mapCoordMax）。
    # 写太小 = 把玩家真实坐标当噪声裁掉；写太大 = 这道门禁形同虚设。两个方向都是事故。
    coord = num("mapCoordMax")
    if coord is not None and not (100 <= coord <= 10000):
        bad.append(_f("rules-impossible", "mapCoordMax=%s 不在可信区间 100~10000" % coord,
                      "请填该游戏的实际地图边界；无可信数据就沿用引擎既有口径（1500）并标注待校准"))
    return bad


def check_land_table(prof):
    bad = []
    lands = sorted(prof["lands"], key=lambda x: x["level"])
    if len(lands) < 4:
        bad.append(_f("land-coverage", "%s 只有 %d 条地块建议" % (prof["file"], len(lands)),
                      "至少覆盖 4 个地块等级，否则开荒决策大量落到硬编码兜底"))
    for a, b in zip(lands, lands[1:]):
        if b["level"] <= a["level"]:
            bad.append(_f("land-duplicate-level", "%s Lv%d 等级重复" % (prof["file"], b["level"]),
                          "同一等级只能有一条建议"))
        elif b["soldiers"] < a["soldiers"]:
            bad.append(_f("land-nonmonotonic",
                          "%s Lv%d 推荐兵力 %d 低于 Lv%d 的 %d"
                          % (prof["file"], b["level"], b["soldiers"], a["level"], a["soldiers"]),
                          "地块越难需要越多兵力，倒挂说明数据填错了行"))
    for l in lands:
        if not (1 <= l["level"] <= 10):
            bad.append(_f("land-bad-level", "%s 出现 Lv%s" % (prof["file"], l["level"]), "地块等级取 1~10"))
        if l["soldiers"] <= 0:
            bad.append(_f("land-bad-soldiers", "%s Lv%d 兵力=%s" % (prof["file"], l["level"], l["soldiers"]),
                          "推荐兵力必须是正整数"))

    # 守军总兵力（官方读数）必须随等级递增；0 = 尚无数据，不参与比较。
    known = [(l["level"], l.get("garrison", 0)) for l in lands if l.get("garrison", 0) > 0]
    for (la, va), (lb, vb) in zip(known, known[1:]):
        if vb <= va:
            bad.append(_f("land-garrison-nonmonotonic",
                          "%s Lv%d 守军总兵力 %d 不高于 Lv%d 的 %d"
                          % (prof["file"], lb, vb, la, va),
                          "高等级地块的守军不可能比低等级还少，倒挂一定是填错了行/弄错了单队还是总和"))
    return bad


def check_land_list_consistency(prof):
    """地块名单与守将天梯必须互相自洽。

    真实缺陷：旧库把 hard 档的 张勋/曹仁/纪灵 写进“软柿名单”，把库里不存在的名字
    写进黑名单（黑名单根本命不中）。前者会把玩家的主力送去送，后者是写了个
    不生效的规则——两条都是“看上去有数据、实际上误导”。
    """
    bad = []
    heroes = prof["heroes"]
    tier_of = {}
    for name, _tag, _score, _d, _t in heroes.get("danger", []):
        tier_of[name] = "danger"
    for name, _tag, _score, _d, _t in heroes.get("hard", []):
        tier_of[name] = "hard"
    for name, _tag, _score, _d, _t in heroes.get("moderate", []):
        tier_of[name] = "moderate"
    for name, _tag, _score, _d, _t in heroes.get("safe", []):
        tier_of[name] = "safe"

    for l in prof["lands"]:
        for name in l["safe"]:
            tier = tier_of.get(name)
            if tier in ("danger", "hard"):
                bad.append(_f("land-list-tier-conflict",
                              "%s Lv%d 把 %s（守将库判为 %s 档）当成“软柿守军”推荐"
                              % (prof["file"], l["level"], name, tier),
                              "从软柿名单里删掉，或把守将库里对他的评级改下来；两者必有一个在骗玩家"))
        for name in l["black"]:
            tier = tier_of.get(name)
            if tier in ("safe", "moderate"):
                bad.append(_f("land-list-tier-conflict",
                              "%s Lv%d 把 %s（守将库判为 %s 档）列为“坚决避开的翻车黑名单”"
                              % (prof["file"], l["level"], name, tier),
                              "要么它真危险（补进 danger/hard 档），要么不推荐玩家绕开它"))
    return bad


def rag_shape_for(game_id):
    """按游戏取 RAG 表面。非 stzb 项目没有这套语料，必须明说“不适用”，
    而不是递一个空 rag 进去让规则“查了个空”然后报绿。
    """
    if game_id != "stzb":
        return _rag_shape_shell(applicable=False)
    return parse_rag_tables()


def check_rag_corpus(prof, rag):
    """RAG 的第二数据源必须与知识库同源，且不得把不可热更的兵力数字递到玩家眼前。

    本函数是 RAG 侧的**唯一**闸门。以前这里有两个函数（check_rag_conflict /
    check_rag_corpus），一个盯“私有名单与兵力表”、一个盯“语料”，而前者负责的那三张表
    已经被删空、解析锚点全对不上，于是它天天“全绿”却一条也没查。
    合并成一个函数，是为了不让“两个函数谁负责哪个表面”再次变成靠人记的约定。

    为什么数字这一条值得单独拦住：语料（保底种子 / JSONL / 已发布 .bin）都是
    **编译期资产**，没有云端下发通道；而知识库是可以热更的。两边都带数字时，
    只要赛季调过一次平衡，玩家看到的就是两个自相矛盾的结论。
    所以现在的约定是：数字只允许住在知识库里；语料只写定性结论。
    """
    bad = []
    if not rag.get("applicable", True):
        return bad
    tiers = {}
    for tier in ("danger", "hard", "moderate", "safe"):
        for name, *_ in prof["heroes"].get(tier, []):
            tiers.setdefault(name, tier)

    # 0) 先自查解析器自己是不是瞎了。这是全函数最重要的一条：
    #    下面的规则全靠 rag 里这几个表面，任何一个表面解析为空，剩下的判定
    #    就会“因为什么都没看到”而报绿——那是比没闸门更糟的状态。
    blind = []
    if not rag["soldiers"]:
        blind.append("兜底兵力表 when (level)")
    if not rag["seed_entries"]:
        blind.append("保底种子语料（id 以 SEED- 开头的 RagEntry 条目）")
    if not rag["corpus_texts"]:
        blind.append("任何一条会念给玩家的语料文案")
    if not rag["sanitizer"]["ok"]:
        blind.append("端侧脱敏正则的原句")
    if blind:
        bad.append(_f("rag-parser-blind",
                      "解析器在 SlgRagEngine 里没能读到：%s" % "、".join(blind),
                      "代码被重构了而闸门锚点还在原地。逐对锚点跟着源码改；"
                      "改完必须确认本行报告消失，否则后面的规则全部在空转"))

    # 1) 私有名单表已经被删掉。删掉是对的：它会让云端热更新只改到知识库那一条。
    #    重新出现就是回归。
    if rag["has_private_roster"]:
        bad.append(_f("rag-private-roster-regressed",
                      "SlgRagEngine 里又出现了私有守将名单表",
                      "名单一律读 KnowledgeBaseManager.activeProfile.defenderDb，"
                      "否则热更新到不了这条通道、切游戏也不跟着变"))

    # 1b) 更一般的形状：引擎里不许存在任何一张"自持的游戏数据表"。
    #     旧条只认 dangerKeywords / isSafe 两个名字，而 P7 实测发现的六张率土表
    #     （PVE_MARKERS / KNOWN_SKILLS / HERO_BASE_SPEED …）一条都没被抓到，
    #     因为它们换了名字、还带着类型注解。现在按形状抓，不按名字抓。
    if rag.get("engine_tables"):
        bad.append(_f("rag-engine-holds-game-data",
                      "SlgRagEngine 里又有引擎自持的游戏数据表：%s" % ", ".join(rag["engine_tables"]),
                      "词/名单/数值属于 GameProfile（sceneKeywords / heroBaseSpeed / pveMechanicNotes），"
                      "引擎只留组合逻辑；留在引擎里 = 热更改不到它 + 换游戏不跟着变，"
                      "确实是引擎自用判据请在 ENGINE_TABLE_ALLOWED 里登记并写明理由"))

    # 2) 兜底兵力表只允许在知识库“没覆盖该等级”时存在。
    #    历史教训：这里曾写 5800，知识库写 5500，玩家同时看到两个自相矛盾的出兵量。
    soldiers = {l["level"]: l["soldiers"] for l in prof["lands"]}
    for lvl, vals in sorted(rag["soldiers"].items()):
        if lvl not in soldiers:
            continue
        for v in vals:
            if v != soldiers[lvl]:
                bad.append(_f("rag-soldiers-conflict",
                              "Lv%d 兵力：知识库=%d 但 RAG 兜底 when 表=%d" % (lvl, soldiers[lvl], v),
                              "同一事实两个值，玩家会看到自相矛盾的建议；统一取知识库值"))

    # 3) 语料里被称作“极危/翻车”或“软柿/白给”的人名，知识库至少得认同其中之一：
    #    全局同档，或者该等级把他列进了推荐名单（等级表比全局档位更具体）。
    #    这条豁免必须和端侧 isSafe 的优先级同源，否则闸门会逼着人把数据改错：
    #    知识库今天就同时把徐晃/鲍信写进 moderate 档与 Lv5 推荐名单（Lv5 是开荒分水岭）。
    #    但 danger / hard 永远不可能被白名单救回来——语料说“软柿”而知识库说“翻车点”，
    #    不管哪一侧错，都得拦下来人工定一个。
    #    人名靠知识库当字典识别，所以“软柿”“白给”这类标签词不会被当成武将名误报。
    level_whitelisted = {n for l in prof["lands"] for n in l["safe"]}
    for e in rag["seed_entries"]:
        if not e["hint"]:
            continue
        for n in e["names"]:
            if n not in tiers:
                continue
            if e["hint"] == tiers[n]:
                continue
            if e["hint"] == "safe" and tiers[n] == "moderate" and n in level_whitelisted:
                continue
            bad.append(_f("rag-seed-roster-tier-conflict",
                          "语料条目把 %s 归为 %s，知识库归为 %s" % (n, e["hint"], tiers[n]),
                          "语料文案与知识库对同一个人的判断相反；而热更新只改得动知识库"))

    # 4) 语料文本里的兵力数字必须被端侧脱敏拦住（解析器瞎了就别跑这一步，
    #    因为“镜像没能抹”会被当成“语料有问题”误报）。
    if rag["sanitizer"]["ok"]:
        seen = set()
        for surface, text in rag["corpus_texts"]:
            if not _RAG_TROOP_HINT.search(text):
                continue
            if _RAG_TROOP_HINT.search(_strip_corpus_numbers(text, rag["sanitizer"])):
                key = (surface, text[:40])
                if key in seen:
                    continue
                seen.add(key)
                bad.append(_f("rag-corpus-number-visible",
                              "%s: 兵力数字没被脱敏拦住 -> %s" % (surface, text[:56]),
                              "这份语料是编译期资产，云端热更新改不到它；"
                              "要么删掉数字，要么把新写法补进 CORPUS_TROOP_CONTEXT"))
    return bad


def check_cloud_drift(prof, cloud, serialized_keys, root_keys=None):
    """云端 JSON 必须与内置知识库、与当前 schema 完全对齐。"""
    bad = []
    for c in cloud:
        if c["error"]:
            bad.append(_f("cloud-json-broken", "%s: %s" % (c["file"], c["error"]), "修好 JSON"))
            continue
        d = c["data"]
        if d.get("game_id") != prof["game_id"]:
            continue
        ghost = sorted((d.get("top_keys") or set()) - serialized_keys)
        if ghost:
            bad.append(_f("cloud-unknown-keys",
                          "%s 含 schema 已不认的顶层键：%s" % (c["file"], ", ".join(ghost)),
                          "这些键写了也没人读，请用导出脚本重新生成"))
        # 反方向才是真正会出事的方向：端上会读的键，产物没带。
        # 缺一个键不会报错，只会让 fromJson 走 optXxx 默认值 —— 等于热更把
        # "这份配置里没有"悄悄当成"这项该用默认值"，把内置库的修正覆盖掉。
        missing_root = sorted(set(root_keys or ()) - (d.get("top_keys") or set()))
        if missing_root:
            bad.append(_f("cloud-missing-field",
                          "%s 缺端上 fromJson 会读的顶层键：%s" % (c["file"], ", ".join(missing_root)),
                          "跑 python tools/export_profile.py 重新导出；"
                          "若是导出表漏了字段，请在 export_profile.py 的字段表里补上"))
        kb_btn, cl_btn = set(prof["buttons"]), set(d["buttons"])
        if kb_btn - cl_btn:
            bad.append(_f("cloud-missing-buttons",
                          "%s 缺按键：%s" % (c["file"], ", ".join(sorted(kb_btn - cl_btn))),
                          "同版本缓存也会被 loadProfileForGame 接受，缺键等于上线即静默降级"))
        for key in sorted(kb_btn & cl_btn):
            if prof["buttons"][key]["primary"] != d["buttons"][key]["primary"]:
                bad.append(_f("cloud-button-drift",
                              "%s 的 %s 主关键字不一致（内置=%s / JSON=%s）"
                              % (c["file"], key, prof["buttons"][key]["primary"], d["buttons"][key]["primary"]),
                              "重新导出，别让两份真相各跑各的"))
        for tier in ("danger", "hard", "moderate", "safe"):
            kb_names = [n for n, *_ in prof["heroes"].get(tier, [])]
            cl_names = [n for n, _t, _s in d["heroes"].get(tier, [])]
            if kb_names != cl_names:
                bad.append(_f("cloud-hero-drift",
                              "%s 的 %s 守将名单与内置不同（内置 %d 人 / JSON %d 人）"
                              % (c["file"], tier, len(kb_names), len(cl_names)),
                              "用导出脚本重生成，云端与内置必须逐条一致"))
        # 地块建议表：这些数字是玩家直接看到的出兵量，也是军师锦囊唯一的数字出处。
        # 它在 JSON 里嵌在 defender_db 下面，顶层键齐全不代表内容齐全，所以逐条比。
        kb_lands = {l["level"]: l for l in prof["lands"]}
        cl_lands = {l["level"]: l for l in d["lands"]}
        if sorted(kb_lands) != sorted(cl_lands):
            bad.append(_f("cloud-land-levels-drift",
                          "%s 的地块等级覆盖与内置不同（内置 Lv%s / JSON Lv%s）"
                          % (c["file"],
                             "/".join(str(x) for x in sorted(kb_lands)),
                             "/".join(str(x) for x in sorted(cl_lands))),
                          "少一级 = 热更后那一级的开荒建议退回端侧兜底，多一级 = 内置库里没有的依据；重新导出"))
        for lvl in sorted(set(kb_lands) & set(cl_lands)):
            a, b = kb_lands[lvl], cl_lands[lvl]
            for field, label in (("soldiers", "推荐兵力"), ("garrison", "守军总兵力"),
                                 ("safe", "软柿名单"), ("black", "黑名单"), ("note", "备注")):
                if a.get(field) != b.get(field):
                    bad.append(_f("cloud-land-drift",
                                  "%s Lv%d 的%s不一致（内置=%s / JSON=%s）"
                                  % (c["file"], lvl, label, a.get(field), b.get(field)),
                                  "重新导出；出兵量两份真相会让玩家看到自相矛盾的建议"))
        for k in sorted(set(prof["rules"]) - {_camel(v) for v in d["rules"]}):
            bad.append(_f("cloud-rule-missing", "%s 的 rules 缺 %s" % (c["file"], _snake(k)), "补齐或重新导出"))
        for k, v in sorted(d["rules"].items()):
            kk = _CAMEL_ALIAS.get(k, _camel(k))
            if kk in prof["rules"] and prof["rules"][kk] != v:
                bad.append(_f("cloud-rule-drift",
                              "%s 的 rules.%s 不一致（内置=%s / JSON=%s）" % (c["file"], k, prof["rules"][kk], v),
                              "重新导出"))
        # 三张"引擎按组向知识包取的表"（P7 迁出来的）：内置与产物必须逐条一致。
        # 少一组 / 少一个词，都会让热更后的端上"如实不判定"，玩家只看到功能莫名消失。
        for label, kb_map, cl_map in (
                ("scene_keywords", prof.get("scene_keywords", {}), d.get("scene_keywords") or {}),
                ("hero_base_speed", prof.get("hero_speed", {}), d.get("hero_speed") or {}),
                ("pve_mechanic_notes", prof.get("mechanic_notes", {}), d.get("mechanic_notes") or {})):
            if kb_map != cl_map:
                diff = sorted(set(kb_map) ^ set(cl_map))
                bad.append(_f("cloud-vocab-drift",
                              "%s 的 %s 与内置不一致（内置 %d 项 / JSON %d 项，差异键：%s）"
                              % (c["file"], label, len(kb_map), len(cl_map), ", ".join(diff) or "内容不同"),
                              "跑 python tools/export_profile.py 重新导出，别让词表两份真相"))
    return bad


# ---------------------------------------------------------------------------
# 4. 字段接线（死配置检测）：字段必须在 knowledge/ 之外有消费者，
#    或被 GameProfile.kt 里某个"外部真的调用过的方法"间接消费。
# ---------------------------------------------------------------------------

def _camel(snake):
    parts = snake.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


# JSON 里个别键与机械转换不同名，显式列出避免错判
_CAMEL_ALIAS = {}


def collect_java_tokens(java_root, knowledge_dir):
    """返回 (外部消费的属性名->命中文件列表, GameProfile.kt 内 字段->所属fun, 全仓被调用方法名)。

    注意：knowledge/ 目录下的 KnowledgeBaseManager.kt **是**真消费者（版本闸门、激活切换），
    只有两份内置库的数据定义文件不参与接线计算。
    """
    outside = {}
    local_field_fun = {}
    called_methods = set()
    knowledge_abs = os.path.abspath(knowledge_dir)
    for dp, _dirs, files in os.walk(java_root):
        for fn in sorted(files):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dp, fn)
            if fn in KB_DATA_FILES and os.path.abspath(path).startswith(knowledge_abs):
                continue
            src = strip_kt_comments(read(path))
            rel = os.path.relpath(path, java_root).replace(os.sep, "/")
            if fn == "GameProfile.kt":
                cur = None
                for ln in src.splitlines():
                    m = re.match(r"\s*(?:private\s+|internal\s+)?fun\s+(?:<[^>]+>\s*)?(\w+)", ln)
                    if m:
                        cur = None if m.group(1) in SELF_SERVING_FUNS else m.group(1)
                    if re.match(r"^\s*\}", ln):
                        cur = None
                    if cur:
                        for word in re.findall(r"\b(\w+)\b", ln):
                            local_field_fun.setdefault(word, set()).add(cur)
                continue
            for attr in set(re.findall(r"\.(\w+)", src)):
                outside.setdefault(attr, set()).add(rel)
            for call in re.findall(r"\.\s*(\w+)\s*\(", src):
                called_methods.add(call)
    return {k: sorted(v) for k, v in outside.items()}, local_field_fun, called_methods


def _display_only(files):
    """命中文件是否全在展示层（ui/）里：是则这个字段没有任何决策消费者。"""
    return bool(files) and all(f.split("/", 1)[0] in DISPLAY_CONSUMER_DIRS for f in files)


def check_dead_fields(classes, outside, local_field_fun, called_methods):
    bad, matrix = [], []
    for cls, fields in sorted(classes.items()):
        for fld in sorted(fields):
            exempt = FIELD_EXEMPT.get(fld)
            if exempt:
                matrix.append((cls, fld, "豁免", exempt))
                continue
            files = outside.get(fld, [])
            if files and not _display_only(files):
                matrix.append((cls, fld, "已接线", ", ".join(files[:2])))
                continue
            if files:
                # 只在设置页/详情页印了一行字：数值改了不影响任何行为
                bad.append(_f("dead-profile-field",
                              "%s.%s 只有展示层引用（%s），没有任何决策读取它"
                              % (cls, fld, ", ".join(files)),
                              "要么接进真实判定（并写清 fail-closed 回退），要么按 screen_virtual_* 的先例删掉；"
                              "只在 UI 上印一行，会让人以为热更新能改行为"))
                matrix.append((cls, fld, "仅展示", ", ".join(files[:2])))
                continue
            via = sorted(local_field_fun.get(fld, set()) & called_methods)
            if via:
                matrix.append((cls, fld, "经方法间接接线", "%s()" % ", ".join(via)))
                continue
            bad.append(_f("dead-profile-field",
                          "%s.%s 被定义、被序列化，但没有任何决策读取它" % (cls, fld),
                          "要么接进真实判定（并写清 fail-closed 回退），要么按 screen_virtual_* 的先例删掉；"
                          "留着只会让人以为热更新能改行为"))
            matrix.append((cls, fld, "死配置", ""))
    return bad, matrix


# ---------------------------------------------------------------------------
# 5. 组装与执行
# ---------------------------------------------------------------------------

def parse_builtin_profiles():
    """读两份内置知识库。

    闸门与导出脚本**必须共用这一个入口**：两处各自解析就等于又开了第二套权威，
    而那正是本闸门要拦的东西。
    """
    return [parse_kb_file(os.path.join(KNOWLEDGE, f)) for f in KB_DATA_FILES]


def run_all(list_fields=False):
    problems = []
    kbs = parse_builtin_profiles()
    enum_names = parse_button_enum()
    cloud = parse_cloud_profiles()
    serialized = parse_serialized_keys()
    root_keys = parse_profile_root_keys()
    if not root_keys:
        # 解析器瞎了绝不能当成"产物不需要带任何键"：这是整条云端漂移判定的地基。
        problems.append(_f("kb-parser-blind",
                          "没能从 GameProfile.fromJson 里读出任何根键（写法改过了？锚点跟着改）",
                          "补 parse_profile_root_keys 的锚点，否则 cloud-missing-field 永远空转"))
    classes = parse_schema_fields()
    rag_stzb = parse_rag_tables()

    for prof in kbs:
        rag = rag_stzb if prof["game_id"] == "stzb" else rag_shape_for(prof["game_id"])
        problems += check_display_sanity(prof)
        problems += check_tier_score(prof)
        problems += check_ghost_heroes(prof)
        problems += check_button_coverage(prof, enum_names)
        problems += check_rules_sanity(prof)
        problems += check_land_table(prof)
        problems += check_land_list_consistency(prof)
        problems += check_rag_corpus(prof, rag)
        problems += check_cloud_drift(prof, cloud, serialized, root_keys)

    outside, local_field_fun, called_methods = collect_java_tokens(JAVA, KNOWLEDGE)
    dead, matrix = check_dead_fields(classes, outside, local_field_fun, called_methods)
    problems += dead

    if list_fields:
        print("\n字段接线矩阵：")
        for cls, fld, status, note in matrix:
            print("  %-18s %-28s %-16s %s" % (cls, fld, status, note))
    return problems


# ---------------------------------------------------------------------------
# 6. 反例自测（不读文件系统，纯结构判定，保证规则本身有效）
# ---------------------------------------------------------------------------

def _base_prof():
    return {
        "file": "Fixture.kt", "game_id": "fx", "target_package": "com.fx", "profile_version": "1.0",
        "rules": {"maxStamina": 120, "staminaPerAction": 20, "maxMorale": 120, "moraleStandard": 100,
                  "minMoraleForPaving": 100, "immunityDurationSec": 3600, "immunityPaddingMs": 1000,
                  "nightWindowStartHour": 0, "nightWindowEndHour": 7, "nightStaminaMultiplier": 1.0},
        "buttons": {"ATTACK": {"primary": "出征", "aliases": ["出 征"]},
                    "CONFIRM": {"primary": "确定", "aliases": ["确 定"]}},
        "heroes": {"danger": [("郭嘉", "强控-混乱", 5, "混乱控制", "坚决避开")],
                   "hard": [("张任", "落凤-技穷", 4, "计穷", "兵力充足可打")],
                   "moderate": [("于禁", "整军经武", 2, "纯防御", "正常出征")],
                   "safe": [("邓茂", "白给软柿子", 1, "无威胁", "推荐")]},
        "lands": [{"level": 3, "soldiers": 1200, "safe": ["邓茂"], "black": [], "note": "跳板地", "garrison": 1800},
                  {"level": 4, "soldiers": 3000, "safe": ["邓茂"], "black": ["郭嘉"], "note": "先侦察", "garrison": 5000},
                  {"level": 5, "soldiers": 5500, "safe": ["邓茂"], "black": ["郭嘉"], "note": "分水岭", "garrison": 9000},
                  {"level": 6, "soldiers": 16000, "safe": ["邓茂"], "black": ["郭嘉"], "note": "双队", "garrison": 33000}],
        "tactical": {"pavingDefaultSlots": [1, 2, 3], "pavingStepIntervalMs": 2500},
        "watchdog": ["确定"],
        "scene_keywords": {"DISPATCH_ACTION": ["出征"], "KNOWN_HEROES": ["邓茂"]},
        "hero_speed": {"邓茂": 60},
        "mechanic_notes": {"暴走": "守军触发【暴走】：需要解控。"},
    }


def selftest():
    results = []

    def expect(name, findings, want):
        got = any(f["check"] == want for f in findings)
        if not got:
            print("❌ 反例未被拦截: %s 应触发 %s" % (name, want))
        return got

    p = _base_prof()
    p["heroes"]["danger"][0] = ("潘璋", "不 numero 控", 5, "带控制", "避开")
    results.append(expect("latin 夹具", check_display_sanity(p), "latin-contamination"))

    p = _base_prof()
    p["heroes"]["danger"].append(("郭嘉", "重复", 5, "x", "y"))
    p["safe_dup"] = []
    p["heroes"]["safe"].append(("郭嘉", "跨档", 1, "x", "y"))
    results.append(expect("跨档重名夹具", check_display_sanity(p), "duplicate-hero-tier"))

    p = _base_prof()
    p["heroes"]["safe"][0] = ("邓茂", "白给", 4, "无威胁", "推荐")
    results.append(expect("tier 夹具", check_tier_score(p), "tier-score-mismatch"))

    # 空武将名：双向 contains 会让这条记录命中一切守将，端上 validateFor 也据此拒收整包。
    p = _base_prof()
    p["heroes"]["danger"].append(("", "空白名", 5, "x", "y"))
    results.append(expect("空武将名夹具", check_display_sanity(p), "hero-empty-name"))
    # 同一夹具的反面：干净内置库不许被误报，否则"越严"看起来像"很稳"。
    ok_names = not any(f["check"] == "hero-empty-name" for f in check_display_sanity(_base_prof()))
    if not ok_names:
        print("❌ 正例被误报: 夹具里所有守将名都非空，不该报 hero-empty-name")
    results.append(ok_names)

    p = _base_prof()
    p["lands"][1]["black"] = ["赵云"]
    results.append(expect("ghost 夹具", check_ghost_heroes(p), "ghost-hero"))

    p = _base_prof()
    del p["buttons"]["CONFIRM"]
    results.append(expect("按键缺失夹具", check_button_coverage(p, ["ATTACK", "CONFIRM", "FORGE"]),
                          "button-key-missing"))
    p = _base_prof()
    p["buttons"]["FORGE"] = {"primary": "锻造", "aliases": []}
    results.append(expect("按键多余夹具", check_button_coverage(p, ["ATTACK", "CONFIRM"]), "button-key-unknown"))
    p = _base_prof()
    p["buttons"]["ATTACK"]["primary"] = ""
    results.append(expect("主关键字空夹具", check_button_coverage(p, ["ATTACK", "CONFIRM"]), "button-empty-primary"))

    p = _base_prof()
    p["rules"]["staminaPerAction"] = 200
    results.append(expect("体力不可能夹具", check_rules_sanity(p), "rules-impossible"))
    p = _base_prof()
    p["rules"]["nightStaminaMultiplier"] = 0.5
    results.append(expect("夜战倍率夹具", check_rules_sanity(p), "rules-impossible"))
    p = _base_prof()
    p["rules"]["minMoraleForPaving"] = 200
    results.append(expect("士气阈值夹具", check_rules_sanity(p), "rules-impossible"))
    p = _base_prof()
    p["rules"]["immunityPaddingMs"] = 90000
    results.append(expect("破免补偿单位夹具", check_rules_sanity(p), "rules-impossible"))

    p = _base_prof()
    p["lands"][2]["soldiers"] = 2000
    results.append(expect("兵力倒挂夹具", check_land_table(p), "land-nonmonotonic"))
    p = _base_prof()
    p["lands"] = p["lands"][:2]
    results.append(expect("地块覆盖夹具", check_land_table(p), "land-coverage"))

    # RAG 闸门。夹具的键一律从 _rag_shape_shell() 出发：“夹具自己描一份结构”
    # 正是上一轮事故的形状——解析器改了返回体，selftest 拿旧键的夹具照样全绿。
    def _rag(**over):
        r = _rag_shape_shell()
        r["soldiers"] = {5: {5500}}
        r["seed_entries"] = [{"names": ["邓茂"], "hint": "safe"}]
        r["corpus_texts"] = [("kotlin-seed", "主力兵力以知识库为准，先探后打")]
        r["sanitizer"] = dict(_sanitizer_shell(), ok=True, words=["主力", "兵力"],
                              ctx_lit=r"$ctx[^0-9。\n]{0,8}?\d{3,6}",
                              tail_lits=[r"\d{3,6}(?:兵|兵力|守军)", r"\d{3,6}\s*左\s*右"],
                              digits=r"\d+", token="以知识库为准",
                              collapses=[("以知识库为准以知识库为准", "以知识库为准")])
        r.update(over)
        return r

    p = _base_prof()
    results.append(expect("RAG 兵力冲突夹具",
                          check_rag_corpus(p, _rag(soldiers={5: {5800}})), "rag-soldiers-conflict"))
    results.append(expect("RAG 私有名单回归夹具",
                          check_rag_corpus(p, _rag(has_private_roster=True)),
                          "rag-private-roster-regressed"))
    results.append(expect("RAG 语料档位冲突夹具",
                          check_rag_corpus(p, _rag(seed_entries=[{"names": ["郭嘉"], "hint": "safe"}])),
                          "rag-seed-roster-tier-conflict"))
    # 豁免的正反两面：moderate 被本等级明确推荐时不算冲突（否则闸门会逼着人改坏数据）；
    # 一旦没有等级推荐，同一个 moderate 又必须重新报红。
    p_rescue = _base_prof()
    p_rescue["lands"][2]["safe"] = ["于禁"]
    rescue_rag = _rag(seed_entries=[{"names": ["于禁"], "hint": "safe"}])
    results.append(not any(f["check"] == "rag-seed-roster-tier-conflict"
                           for f in check_rag_corpus(p_rescue, rescue_rag)) or
                   (print("❌ 正例被误报: moderate 已被本等级推荐，不该算冲突") or False))
    results.append(expect("moderate 无推荐时仍应报红夹具",
                          check_rag_corpus(_base_prof(), rescue_rag), "rag-seed-roster-tier-conflict"))
    # “15000 兵”中间有个空格：端侧正则拿不到它，所以这条数字会直接念给玩家。
    results.append(expect("RAG 漏网数字夹具",
                          check_rag_corpus(p, _rag(corpus_texts=[("shipped-bin", "开局攒到 15000 兵再打")])),
                          "rag-corpus-number-visible"))
    # 多条脱敏正则必须**全部**进镜像（正例）：“30000”靠语汇词窗口抹、“6000 左右”靠第二条
    # tail 抹。把 tail_lit 退回单条，这里留下的 6000 会被宽判据抓到并报红 ——
    # 于是 selftest 当场响，而不是等真语料撞上去才发现闸门只会念一条。
    zuoyou = check_rag_corpus(p, _rag(corpus_texts=[
        ("shipped-bin", "守军兵力约 30000，一队 6000 左右平手")]))
    results.append(not any(f["check"] == "rag-corpus-number-visible" for f in zuoyou) or
                   (print("❌ 正例被误报: 多条脱敏正则没被镜像全（只应用了第一条）") or False))
    results.append(expect("RAG 解析器瞎了夹具",
                          check_rag_corpus(p, _rag(soldiers={})), "rag-parser-blind"))
    # 解析器真身体检（**读真实源码**，不是合成 rag）。
    # 上面每一条 RAG 夹具喂的都是合成结构，它们只能证明判定函数正确，
    # 证明不了解析器还指着活着的源码——上一轮事故正是这个形状：selftest 全绿，
    # 而 parse_rag_tables 的种子锚点已经扫到 0 条，整层规则空转。
    real_plain = strip_kt_comments(read(os.path.join(JAVA, "ai", "rag", "SlgRagEngine.kt")))
    real_seeds = parse_seed_entries(real_plain)
    if len(real_seeds) < 5:
        print("❌ 解析器漂移: 真实 SlgRagEngine 只解析出 %d 条保底种子（应 >= 5）" % len(real_seeds))
    results.append(len(real_seeds) >= 5)
    # 锚点必须跟着结构走，不能跟着函数名走：把 seedEntries 改成任意名字，条数不变。
    renamed = parse_seed_entries(real_plain.replace("fun seedEntries(", "fun renamedAway("))
    if len(renamed) != len(real_seeds):
        print("❌ 解析器仍依赖函数名: 改名后解析出 %d 条（应仍为 %d 条）" % (len(renamed), len(real_seeds)))
    results.append(len(renamed) == len(real_seeds))
    # 反向：种子真被删光时必须解析为空（护栏照响），不许"扫不到就随便凑几条"。
    emptied = parse_seed_entries(real_plain.replace('"SEED-', '"XXXX-'))
    if emptied:
        print("❌ fail-closed 失效: 种子 id 全被改掉后仍解析出 %d 条" % len(emptied))
    results.append(not emptied)
    # 诱饵：`RagEntry(` 出现在 data class 声明与普通函数里都不算种子条目。
    decoy = ('object X {\n'
             '    data class RagEntry(val id: String)\n'
             '    fun loader() { val e = RagEntry("NOTSEED", "A"); println(e) }\n'
             '    private fun seedEntries(): List<RagEntry> = listOf(\n'
             '        RagEntry("SEED-01", "DEFENDER_LAND", "危险 翻车", "x", "y", "z", computeTextEmbedding("a"))\n'
             '    )\n'
             '}\n')
    got = parse_seed_entries(decoy)
    if len(got) != 1 or got[0]["hint"] != "danger":
        print("❌ 种子定位被诱饵带偏: 期望 1 条 danger，实得 %r" % got)
    results.append(len(got) == 1 and got[0]["hint"] == "danger")
    # 引擎自持游戏数据表（P7 不变量）正反两面都测：
    #   正例：真实 SlgRagEngine 现在应该是干净的（六张率土表已迁进知识包）；
    #   反例：把旧表原样塞回去必须被抓到 —— 少了这一条，"抓不到"和"没有"就无法区分。
    real_tables = parse_engine_data_tables(real_plain)
    if real_tables:
        print("❌ 引擎里仍有游戏数据表: %s（词/名单/数值属于知识包）" % ", ".join(real_tables))
    results.append(not real_tables)
    mutant = real_plain + '\n    private val HERO_BASE_SPEED: Map<String, Int> = mapOf("马超" to 83)\n'
    caught = parse_engine_data_tables(mutant)
    if "HERO_BASE_SPEED" not in caught:
        print("❌ 形状探测抓不到塞回去的旧表（带类型注解的 mapOf）: %r" % caught)
    results.append("HERO_BASE_SPEED" in caught)
    # 白名单只放行"引擎自用判据"，不是整文件免检：同文件里另一张表必须照抓。
    mixed = parse_engine_data_tables(
        'object X {\n    private val CORPUS_TROOP_CONTEXT = listOf("兵力", "守军")\n'
        '    private val PVE_MARKERS = listOf("贼寇")\n}\n')
    if mixed != ["PVE_MARKERS"]:
        print("❌ 白名单越界（应只放行 CORPUS_TROOP_CONTEXT）: %r" % mixed)
    results.append(mixed == ["PVE_MARKERS"])
    results.append(expect("引擎自持数据表夹具",
                          check_rag_corpus(_base_prof(), _rag(engine_tables=["HERO_BASE_SPEED"])),
                          "rag-engine-holds-game-data"))
    # 反向正例：不适用时必须一个报告都不出。少这一条，下一个“查了个空却报绿”的
    # 重构会连 selftest 一起骗过。
    clean_nonstzb = check_rag_corpus(_base_prof(), _rag_shape_shell(applicable=False))
    results.append(not clean_nonstzb or
                   (print("❌ 正例被误报: 非 stzb 项目应直接短路，不得报任何一条") or False))

    p = _base_prof()
    p["lands"][3]["garrison"] = 8000          # Lv6 守军总量反而低于 Lv5 → 倒挂
    results.append(expect("守军总量倒挂夹具", check_land_table(p), "land-garrison-nonmonotonic"))

    p = _base_prof()
    p["lands"][2]["safe"] = ["郭嘉"]            # danger 档被当软柿子推荐
    results.append(expect("名单/天梯互打夹具", check_land_list_consistency(p), "land-list-tier-conflict"))

    p = _base_prof()
    p["lands"][2]["black"] = ["邓茂"]           # safe 档被当翻车黑名单
    results.append(expect("黑名单反向夹具", check_land_list_consistency(p), "land-list-tier-conflict"))

    p = _base_prof()
    p["lands"][2]["safe"] = ["于禁"]            # moderate 档进软柿名单：可接受，不许误报
    ok_moderate = not any(f["check"] == "land-list-tier-conflict" for f in check_land_list_consistency(p))
    if not ok_moderate:
        print("❌ 正例被误报: moderate 档不该进 danger/hard 冲突名单")
    results.append(ok_moderate)
    ok_clean_lands = not any(f["check"] == "land-list-tier-conflict"
                             for f in check_land_list_consistency(_base_prof()))
    results.append(ok_clean_lands)

    p = _base_prof()
    cloud = [{"file": "fx.json", "error": "", "data": {
        "game_id": "fx", "target_package": "com.fx", "profile_version": "1.0",
        "rules": {"maxStamina": 120, "staminaPerAction": 25},
        "buttons": {"ATTACK": {"primary": "占领", "aliases": []}},
        "heroes": {"danger": [("郭嘉", "强控-混乱", 5)], "hard": [], "moderate": [], "safe": []},
        "lands": [], "tactical": {}, "watchdog": [],
        "top_keys": {"game_id", "rules", "semantic_buttons", "screen_virtual_width"}}}]
    keys = {"game_id", "game_name", "profile_version", "target_package", "description",
            "rules", "semantic_buttons", "max_stamina", "stamina_per_action", "vision_policy",
            "scene_keywords", "hero_base_speed", "pve_mechanic_notes"}
    # root_keys = 端上 fromJson 真会读的键。产物少任何一个都会"静默走默认值"，
    # 所以这条方向（缺键）比 ghost（多键）更要命：P7 实测时四个新字段全漏在这一点上。
    root_keys = keys - {"game_name"}
    findings = check_cloud_drift(p, cloud, keys, root_keys)
    for want in ("cloud-unknown-keys", "cloud-missing-field", "cloud-missing-buttons",
                 "cloud-button-drift", "cloud-hero-drift", "cloud-rule-missing", "cloud-rule-drift",
                 "cloud-vocab-drift", "cloud-land-levels-drift"):
        results.append(expect("云端漂移夹具", findings, want))
    results.append(expect("坏 JSON 夹具",
                          check_cloud_drift(p, [{"file": "x.json", "error": "boom", "data": {}}], keys, root_keys),
                          "cloud-json-broken"))
    # 正例：一份与内置逐条一致、且把根键带全的产物，必须一个 cloud-* 报告都不出。
    # 没有这条正向断言，"规则写得越来越严"会被误当成"闸门很稳"。
    good = {"file": "good.json", "error": "", "data": {
        "game_id": "fx", "target_package": "com.fx", "profile_version": "1.0",
        "rules": dict(p["rules"]), "buttons": {k: dict(v) for k, v in p["buttons"].items()},
        "heroes": {t: [(n, tg, sc) for n, tg, sc, *_ in p["heroes"].get(t, [])]
                   for t in ("danger", "hard", "moderate", "safe")},
        # 地块表也必须逐条带上：它是玩家直接看到的出兵量，漏一级/漏一个数字都是静默降级
        "lands": [dict(l) for l in p["lands"]],
        "tactical": dict(p["tactical"]), "watchdog": list(p["watchdog"]),
        "scene_keywords": dict(p["scene_keywords"]), "hero_speed": dict(p["hero_speed"]),
        "mechanic_notes": dict(p["mechanic_notes"]), "top_keys": set(root_keys)}}
    good_findings = check_cloud_drift(p, [good], keys, root_keys)
    if good_findings:
        print("❌ 正例被误报: 与内置逐条一致的产物不该出问题 -> %s"
              % ", ".join(f["check"] for f in good_findings))
    results.append(not good_findings)
    # 反向探针：一致产物里悄悄改掉一个出兵量 / 抹掉守军总量，必须被抓。
    for label, mutate in (("推荐兵力被改", lambda l: l.update({"soldiers": 6000})),
                          ("守军总量被抹", lambda l: l.update({"garrison": 0}))):
        drifted = {"file": "drift.json", "error": "", "data": dict(good["data"],
                   lands=[dict(l) for l in good["data"]["lands"]])}
        mutate(drifted["data"]["lands"][2])
        results.append(expect("%s夹具" % label, check_cloud_drift(p, [drifted], keys, root_keys),
                              "cloud-land-drift"))

    classes = {"GameRules": {"maxMorale", "maxStamina", "staminaPerAction", "immunityPaddingMs",
                            "nightStaminaMultiplier"},
               "TacticalDefaults": {"pavingStepIntervalMs", "legacyPrecisionMs"}}
    outside = {"immunityPaddingMs": ["service/AssistantService.kt"],
               # 只在设置页印了一行字：不能算接线（热更新改数值不会改变任何行为）
               "maxStamina": ["ui/MainActivity.kt"],
               # 展示层 + 决策层都引用：算真接线
               "nightStaminaMultiplier": ["ui/MainActivity.kt", "tactics/RoadPavingFlow.kt"]}
    local_fun = {"staminaPerAction": {"requiredStaminaNow"},
                 "maxMorale": {"isNightNow"},
                 "legacyPrecisionMs": {"someUnusedHelper"}}
    called = {"requiredStaminaNow"}
    bad, matrix = check_dead_fields(classes, outside, local_fun, called)
    results.append(any("maxMorale" in f["detail"] for f in bad) or
                   (print("❌ 死配置夹具未被拦截: maxMorale 应报死配置（唯一引用在未被调用的 isNightNow 里）") or False))
    results.append(any("pavingStepIntervalMs" in f["detail"] for f in bad) or
                   (print("❌ 死配置夹具未被拦截: pavingStepIntervalMs") or False))
    results.append(any("legacyPrecisionMs" in f["detail"] for f in bad) or
                   (print("❌ 死配置夹具未被拦截: legacyPrecisionMs（只被未被调用的 helper 引用）") or False))
    results.append(any(f[1] == "maxStamina" and f[2] == "仅展示" for f in matrix) and
                   any("maxStamina" in f["detail"] and "展示层" in f["detail"] for f in bad) or
                   (print("❌ 仅展示夹具未被拦截: maxStamina 只有 ui/ 引用，应计入未接线") or False))
    results.append(not any("nightStaminaMultiplier" in f["detail"] for f in bad) or
                   (print("❌ 正例被误报: nightStaminaMultiplier 有决策层消费者") or False))
    results.append(not any("staminaPerAction" in f["detail"] for f in bad) or
                   (print("❌ 正例被误报: staminaPerAction 经 requiredStaminaNow() 间接接线") or False))
    results.append(any(f[1] == "maxMorale" and f[2] == "死配置" for f in matrix) or
                   (print("❌ 接线矩阵缺 maxMorale 死配置行") or False))

    clean = _base_prof()
    clean_rag = _rag()
    clean_findings = (check_display_sanity(clean) + check_tier_score(clean) + check_ghost_heroes(clean)
                      + check_button_coverage(clean, ["ATTACK", "CONFIRM"])
                                            + check_rules_sanity(clean) + check_land_table(clean)
                      + check_land_list_consistency(clean)
                      + check_rag_corpus(clean, clean_rag))
    for f in clean_findings:
        print("❌ 干净夹具被误报: %s -> %s" % (f["check"], f["detail"]))
    results.append(not clean_findings)

    ok = all(results)
    if ok:
        print("[selftest] 全部通过：%d 项判定均符合预期（反例必拦、正例不误报）" % len(results))
    return ok


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true",
                    help="反例注入自测；其中解析器体检会读真实 SlgRagEngine.kt（防止锚点漂移只被真跑发现）")
    ap.add_argument("--list-fields", action="store_true", help="打印知识库字段接线矩阵")
    args = ap.parse_args()

    if args.selftest:
        return 0 if selftest() else 1

    problems = run_all(list_fields=args.list_fields)
    if not problems:
        print("[OK] 知识库自洽、三源一致、字段全部接线、云端产物与内置对齐")
        return 0
    print("❌ 知识库存在 %d 项问题：\n" % len(problems))
    by_check = {}
    for p in problems:
        by_check.setdefault(p["check"], []).append(p)
    for check in sorted(by_check):
        print("— %s（%d 处）" % (check, len(by_check[check])))
        for p in by_check[check]:
            print("    %s\n        → %s" % (p["detail"], p["fix"]))
    print("\n以上每一条要么让玩家拿到自相矛盾的建议，要么让热更新白改。按类别逐条修完再跑本闸门。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
