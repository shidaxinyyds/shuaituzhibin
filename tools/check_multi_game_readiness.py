#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
多游戏就绪闸门（check_multi_game_readiness）
==========================================

它守的是一句**对外承诺**：「加一款游戏 ≈ 加一个知识包，不用改代码」。

这句话在 P7 实测之前是假的，而且假在三处（都是取证出来的，不是推测）：

  1. `KnowledgeBaseManager.loadProfileForGame` 第一行是
     `builtInProfiles[gameId] ?: return false` —— 纯云端发布的知识包**永远激活不了**，
     因为切换入口先要求 APK 里编译进一份内置档案；
  2. `getSupportedGames()` 只遍历内置表 —— 界面上连第二个入口都没有；
  3. 本机标定出来的锚点 / 地图投影 / 场景指纹 / 按键模板 / 头像门槛**不带游戏标记**，
     切了游戏仍旧用上一款游戏的量，表现成「切过来时乱点一阵」，且没有任何一方报错。

三处都已修。本闸门的作用是把这三条**钉住**：以后任何人把它们改回去、
或者新写一处「拿 A 游戏的数据去服务 B 游戏」的代码，构建就必须挡下来。

规则（每条都对应一类会静默出错的改动）
------------------------------------
R1 游戏 id 字面量  源码里出现的 `"stzb"` / `"sgz"` 必须落在已登记例外里；
                   新增站点必须先想清楚「这是注册表还是泄漏」，登记后才能过。
R2 权重文件名      游戏专属权重文件名只允许定义在 ModelAssetManager 的常量处；
                   别处抄一份就会造成「体检说齐备、引擎说没找到」。
R3 分域数据必须重载 用了本机分域存储（readScopedString / scopedDir …）的类，
                   必须注册 reloadOnProfileSwitch，否则同进程切游戏仍吃上一款的内存标定。
R4 资产目录        游戏专属资产目录（templates / defender_refs）只能经
                   PerGameScope.assetDirs 取，不许再写死裸目录名。
R5 结构不变量      纯云端激活路径不许退回硬闸；可挂接列表必须能列出云端游戏；
                   切换失败不许当成功汇报。

用法
----
    python tools/check_multi_game_readiness.py
    python tools/check_multi_game_readiness.py --selftest

退出码：0 = 无泄漏；1 = 有泄漏（或例外登记表自己失效）。
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAVA_ROOT = os.path.join(REPO, "client", "app", "src", "main", "java")

GAME_IDS = ("stzb", "sgz")

# 游戏专属权重文件名（含扩展名）。这些是「照某款游戏产出的资产」的名字，
# 一旦在 ModelAssetManager 之外被写死，就会长出第二个命名权威。
WEIGHT_FILE_NAMES = (
    "slg_knowledge_vector_hnsw.bin",
    "intent_slot_zh.onnx",
    "intent_slot_vocab.txt",
    "intent_slot_token_vocab.txt",
    "yolov8n_stzb.param",
    "yolov8n_stzb.bin",
    "yolov8s_stzb.param",
    "yolov8s_stzb.bin",
    "yolo26s_stzb.param",
    "yolo26s_stzb.bin",
    "yolo26n_stzb.param",
    "yolo26n_stzb.bin",
    "yolov11s_multiscale_stzb.param",
    "yolov11s_multiscale_stzb.bin",
)
# 命名权威所在的文件（相对 client/.../java 的路径片段）
WEIGHT_NAME_OWNER = os.path.join("com", "stzb", "assistant", "ai", "assets", "ModelAssetManager.kt")

# 游戏专属资产目录（assets 下的裸目录名）
GAME_ASSET_DIRS = ("templates", "defender_refs")

# R1 例外登记表：文件 -> (必须出现在该行里的片段, 理由)。
# 登记表自己也要被验证：片段对不上就是「例外已被改动或删除」，同样失败 ——
# 一份没人核对的白名单，时间一长就变成「什么都能过」。
GAME_ID_EXCEPTIONS = {
    os.path.join("com", "stzb", "assistant", "service", "PerGameScope.kt"): [
        ('const val LEGACY_OWNER_GAME_ID = "stzb"',
         "旧数据归属的唯一登记点，各存储类都引用这个常量"),
    ],
    os.path.join("com", "stzb", "assistant", "knowledge", "KnowledgeBaseManager.kt"): [
        ('"stzb" to StzbKnowledgeBase.instance', "内置游戏注册表"),
        ('"sgz" to SgzKnowledgeBase.instance', "内置游戏注册表"),
        ('KEY_ACTIVE_GAME_ID, "stzb"', "启动时默认挂接的游戏（无存档时）"),
    ],
    os.path.join("com", "stzb", "assistant", "knowledge", "StzbKnowledgeBase.kt"): [
        ('gameId = "stzb"', "档案自述身份，注册表的键必须与之一致"),
    ],
    os.path.join("com", "stzb", "assistant", "knowledge", "SgzKnowledgeBase.kt"): [
        ('gameId = "sgz"', "档案自述身份，注册表的键必须与之一致"),
    ],
    os.path.join("com", "stzb", "assistant", "ai", "rag", "SlgRagEngine.kt"): [
        ('const val SEED_CORPUS_GAME_ID = "stzb"', "内置种子语料的归属游戏"),
    ],
    os.path.join("com", "stzb", "assistant", "license", "LicenseManager.kt"): [
        ('put("game_id", "stzb")',
         "授权链路（本轮明确不动卡密/激活，故只登记不修改；接多游戏授权时要一并改造）"),
    ],
}


def strip_comments(src):
    """去掉 // 与 块 注释，但**保持行号与行数**（例外登记表按行内容核对）。"""
    out = []
    in_block = False
    for line in src.split("\n"):
        if in_block:
            end = line.find("*/")
            if end < 0:
                out.append("")
                continue
            in_block = False
            line = line[end + 2:]
        idx = line.find("/*")
        if idx >= 0:
            close = line.find("*/", idx + 2)
            if close >= 0:
                line = line[:idx] + line[close + 2:]
            else:
                in_block = True
                line = line[:idx]
        dc = line.find("//")
        if dc >= 0:
            line = line[:dc]
        out.append(line)
    return "\n".join(out)


def collect_files(files=None):
    """返回 [(相对 java 根的 path, 去注释后的行列表)]。files 供自测注入合成源码。"""
    if files is not None:
        return [(rel, strip_comments(src).split("\n")) for rel, src in files]
    collected = []
    for dirpath, _dirs, names in os.walk(JAVA_ROOT):
        for fn in sorted(names):
            if not fn.endswith(".kt") and not fn.endswith(".java"):
                continue
            path = os.path.join(dirpath, fn)
            with open(path, "r", encoding="utf-8") as fh:
                src = fh.read()
            collected.append((os.path.relpath(path, JAVA_ROOT), strip_comments(src).split("\n")))
    if not collected:
        raise RuntimeError("没有扫到任何 Kotlin/Java 源文件：%s（解析器自己瞎了不许报绿）" % JAVA_ROOT)
    return collected


def _rel(rel):
    return rel.replace(os.sep, "/")


# ---------------------------------------------------------------- R1
def rule_game_id_literals(files):
    findings = []
    pat = re.compile(r'"(?:%s)"' % "|".join(GAME_IDS))
    hit_files = set()
    for rel, lines in files:
        exceptions = GAME_ID_EXCEPTIONS.get(rel.replace("/", os.sep)) or []
        matched = [False] * len(exceptions)
        for lineno, line in enumerate(lines, 1):
            if not pat.search(line):
                continue
            hit_files.add(rel)
            for i, (needle, _why) in enumerate(exceptions):
                if needle in line:
                    matched[i] = True
                    break
            else:
                findings.append(
                    "R1 未登记的游戏 id 字面量： %s:%d  %s" % (_rel(rel), lineno, line.strip()[:90])
                )
        for i, (needle, why) in enumerate(exceptions):
            if not matched[i]:
                findings.append(
                    "R1 例外登记表已失效（登记的内容在源码里找不到）： %s  「%s」（理由：%s）"
                    % (_rel(rel), needle, why)
                )
    return findings


# ---------------------------------------------------------------- R2
def rule_weight_file_names(files):
    findings = []
    for rel, lines in files:
        if rel.endswith(WEIGHT_NAME_OWNER):
            continue  # 这里是命名权威本身
        for lineno, line in enumerate(lines, 1):
            for name in WEIGHT_FILE_NAMES:
                if name in line:
                    findings.append(
                        "R2 权重文件名被抄到权威之外（会造成体检与引擎各说各话）： "
                        "%s:%d  %s「%s」应改用 ModelAssetManager 的常量 + extractScopedModelPath"
                        % (_rel(rel), lineno, line.strip()[:60], name)
                    )
    return findings


# ---------------------------------------------------------------- R3
SCOPED_USE = re.compile(r"\b(readScopedString|writeScopedString|removeScopedString|scopedDir)\s*\(")
HOOK_USE = re.compile(r"\breloadOnProfileSwitch\s*[({]")


def rule_scoped_state_needs_hook(files):
    findings = []
    for rel, lines in files:
        if rel.endswith(os.path.join("service", "PerGameScope.kt")):
            continue  # 工具自身定义了这些 API
        body = "\n".join(lines)
        uses = SCOPED_USE.findall(body)
        if not uses:
            continue
        if not HOOK_USE.search(body):
            findings.append(
                "R3 该类把标定数据按游戏分域存了盘，却没注册切游戏重载钩子： %s  "
                "（同进程里切了游戏，内存里仍是上一款游戏的标定 → 照着旧位置点新界面。"
                "请在 attach/init 里调用 PerGameScope.reloadOnProfileSwitch）" % _rel(rel)
            )
    return findings


# ---------------------------------------------------------------- R4
def rule_asset_dirs(files):
    findings = []
    pat = re.compile(r'assets\.(?:open|list)\(\s*"([^"]+)"')
    for rel, lines in files:
        for lineno, line in enumerate(lines, 1):
            m = pat.search(line)
            if not m:
                continue
            path = m.group(1)
            head = path.split("/")[0]
            if head in GAME_ASSET_DIRS:
                findings.append(
                    "R4 游戏专属资产目录被写死（别的游戏会吃到率土的界面截图）： %s:%d  "
                    "「%s」应改用 PerGameScope.assetDirs(\"%s\")"
                    % (_rel(rel), lineno, path, head)
                )
    return findings


# ---------------------------------------------------------------- R5
def rule_structure(files):
    findings = []
    src = {}
    for rel, lines in files:
        # 全仓库真实扫描下路径唯一；自测注入时同一路径会出现两次，
        # 以**最后一条**为准（即注入内容生效），故此处覆盖是有意的。
        src[_rel(rel)] = "\n".join(lines)

    kbm = src.get("com/stzb/assistant/knowledge/KnowledgeBaseManager.kt", "")
    if not kbm:
        findings.append("R5 找不到 KnowledgeBaseManager.kt（路径变了？闸门必须复核登记表）")
    else:
        # 硬闸不许回来：builtInProfiles 缺键就直接 return false，会让纯云端知识包永远激活不了
        if re.search(r"builtInProfiles\s*\[[^\]]+\]\s*\?:\s*return false", kbm):
            findings.append(
                "R5 纯云端激活路径被改回了硬闸（builtInProfiles 缺键即 return false）："
                "「加一款游戏不用改代码」再次变成假话"
            )
        if not re.search(r"fun\s+getSupportedGames\s*\(\s*context\s*:\s*Context\?", kbm):
            findings.append(
                "R5 getSupportedGames 不再接收 Context：云端发布的游戏列不出来，"
                "界面上就没有第二个入口"
            )
        if "listCachedGameIds" not in kbm:
            findings.append("R5 getSupportedGames 不再读取沙盒缓存目录：纯云端游戏不会被列出")

    main_act = src.get("com/stzb/assistant/ui/MainActivity.kt", "")
    if not main_act:
        findings.append("R5 找不到 MainActivity.kt（路径变了？闸门必须复核登记表）")
    else:
        if re.search(r"getSupportedGames\s*\(\s*\)", main_act):
            findings.append(
                "R5 切换对话框又用不传 context 的方式列游戏：云端新游戏在界面上不可见"
            )
        if re.search(r"^\s*com\.stzb\.assistant\.knowledge\.KnowledgeBaseManager\.switchGame\(",
                     main_act, re.M):
            findings.append(
                "R5 切换对话框不看 switchGame 的返回值就打「已切换」：失败被伪装成成功"
            )

    scope = src.get("com/stzb/assistant/service/PerGameScope.kt", "")
    if not scope:
        findings.append("R5 找不到 PerGameScope.kt：分域工具被删除或改名，多游戏数据隔离随之失效")
    else:
        for needle, why in (
            ("LEGACY_OWNER_GAME_ID", "旧数据归属必须有唯一登记点"),
            ("assetNameCandidates", "游戏专属权重文件名必须有唯一派生口径"),
            ("assetDirs", "游戏专属资产目录必须有唯一候选顺序口径"),
        ):
            if needle not in scope:
                findings.append("R5 PerGameScope 里缺少 %s（%s）" % (needle, why))

    rag = src.get("com/stzb/assistant/ai/rag/SlgRagEngine.kt", "")
    if rag and "corpusOwnerId" not in rag:
        findings.append(
            "R5 SlgRagEngine 不再记录语料归属（corpusOwnerId）：语料主人退回写死常量，"
            "换游戏接上自己的语料后也会被一起拒服"
        )
    if rag and "reloadOnProfileSwitch" not in rag:
        findings.append(
            "R5 SlgRagEngine 没注册切游戏重载：内存里一直是上一款游戏的语料池"
        )
    return findings


RULES = (
    ("R1 游戏 id 字面量登记", rule_game_id_literals),
    ("R2 权重文件名唯一权威", rule_weight_file_names),
    ("R3 分域数据必须随游戏重载", rule_scoped_state_needs_hook),
    ("R4 资产目录走游戏候选", rule_asset_dirs),
    ("R5 多游戏结构不变量", rule_structure),
)


def scan(files=None):
    collected = collect_files(files)
    findings = []
    for _title, fn in RULES:
        findings.extend(fn(collected))
    return findings


# ------------------------------------------------------------------ 自测
# 每条规则都注入一个「已知回退形态」，必须被抓到；同时给一份干净夹具，不许误报。
CLEAN_FILES = [
    (os.path.join("com", "stzb", "assistant", "service", "PerGameScope.kt"),
     'const val LEGACY_OWNER_GAME_ID = "stzb"\n'
     "fun assetNameCandidates(fileName: String) = listOf(fileName)\n"
     "fun assetDirs(baseDir: String) = listOf(baseDir)\n"),
    (os.path.join("com", "stzb", "assistant", "service", "UiAnchors.kt"),
     "private fun load() { PerGameScope.readScopedString(ctx, PREFS, \"data\") }\n"
     "fun attach(c: android.content.Context) { PerGameScope.reloadOnProfileSwitch { reload() } }\n"),
    (os.path.join("com", "stzb", "assistant", "knowledge", "KnowledgeBaseManager.kt"),
     'private val builtInProfiles = mapOf(\n'
     '    "stzb" to StzbKnowledgeBase.instance,\n'
     '    "sgz" to SgzKnowledgeBase.instance\n'
     ')\n'
     'val savedGameId = prefs.getString(KEY_ACTIVE_GAME_ID, "stzb") ?: "stzb"\n'
     'builtInProfiles.map { (id, p) -> Pair(id, p.gameName) }\n'
     "fun getSupportedGames(context: Context? = null) = emptyList<Pair<String, String>>()\n"
     "private fun listCachedGameIds(context: Context) = emptyList<String>()\n"),
    (os.path.join("com", "stzb", "assistant", "ui", "MainActivity.kt"),
     "val games = KnowledgeBaseManager.getSupportedGames(this)\n"
     "val switched = KnowledgeBaseManager.switchGame(this, selectedGameId)\n"
     "if (!switched) { log(\"切换失败\") }\n"),
    (os.path.join("com", "stzb", "assistant", "ai", "rag", "SlgRagEngine.kt"),
     'const val SEED_CORPUS_GAME_ID = "stzb"\n'
     "var corpusOwnerId = SEED_CORPUS_GAME_ID\n"
     "fun init(c: android.content.Context) { PerGameScope.reloadOnProfileSwitch { reloadForGame(c) } }\n"),
    (os.path.join("com", "stzb", "assistant", "ocr", "OpenCvMatcher.kt"),
     'private fun preload(dir: String) { assets.list(dir) }\n'),
    (os.path.join("com", "stzb", "assistant", "ai", "assets", "ModelAssetManager.kt"),
     'const val RAG_INDEX_FILE = "slg_knowledge_vector_hnsw.bin"\n'),
    (os.path.join("com", "stzb", "assistant", "license", "LicenseManager.kt"),
     'put("game_id", "stzb")\n'),
    (os.path.join("com", "stzb", "assistant", "knowledge", "StzbKnowledgeBase.kt"),
     'gameId = "stzb",\n'),
    (os.path.join("com", "stzb", "assistant", "knowledge", "SgzKnowledgeBase.kt"),
     'gameId = "sgz",\n'),
]

# (说明, 注入的文件名, 注入内容, 期望命中的规则前缀)
BAD_CASES = [
    ("纯云端激活被改回硬闸",
     os.path.join("com", "stzb", "assistant", "knowledge", "KnowledgeBaseManager.kt"),
     'val baseProfile = builtInProfiles[gameId] ?: return false\n',
     "R5"),
    ("可挂接列表不再接收 context",
     os.path.join("com", "stzb", "assistant", "knowledge", "KnowledgeBaseManager.kt"),
     "fun getSupportedGames(): List<Pair<String, String>> = emptyList()\n",
     "R5"),
    ("切换对话框不看返回值",
     os.path.join("com", "stzb", "assistant", "ui", "MainActivity.kt"),
     "com.stzb.assistant.knowledge.KnowledgeBaseManager.switchGame(this, id)\n",
     "R5"),
    ("新写死一个游戏 id",
     os.path.join("com", "stzb", "assistant", "tactics", "TacticRunner.kt"),
     'if (profile.gameId == "sgz") return\n',
     "R1"),
    ("权重文件名被抄走",
     os.path.join("com", "stzb", "assistant", "ai", "rag", "SlgRagEngine.kt"),
     'val path = extract("slg_knowledge_vector_hnsw.bin")\n',
     "R2"),
    ("分域数据没注册重载钩子",
     os.path.join("com", "stzb", "assistant", "service", "SceneFingerprint.kt"),
     "private fun load() { PerGameScope.readScopedString(ctx, PREFS, \"data\") }\n",
     "R3"),
    ("游戏专属资产目录被写死",
     os.path.join("com", "stzb", "assistant", "ocr", "StzbUiMatcher.kt"),
     'val list = context.assets.list("defender_refs")\n',
     "R4"),
    ("语料主人退回写死常量",
     os.path.join("com", "stzb", "assistant", "ai", "rag", "SlgRagEngine.kt"),
     "fun init(c: android.content.Context) { PerGameScope.reloadOnProfileSwitch { x() } }\n",
     "R5"),
    ("切游戏不重载语料池",
     os.path.join("com", "stzb", "assistant", "ai", "rag", "SlgRagEngine.kt"),
     "var corpusOwnerId = \"stzb\".let { SEED }\n",
     "R5"),
]


def selftest():
    bad = 0
    findings = scan(CLEAN_FILES)
    if findings:
        bad += 1
        print("❌ 干净夹具被报出问题（正例不许误报）：")
        for f in findings:
            print("   " + f)
    else:
        print("[OK] 干净夹具零报告（正例不误报）")

    # 逐条注入：一次只加一个坏文件，保证「报到的是这条规则」而不是连带噪声
    for desc, rel, src, expect in BAD_CASES:
        files = list(CLEAN_FILES) + [(rel, src)]
        got = scan(files)
        hit = [f for f in got if f.startswith(expect)]
        if not hit:
            bad += 1
            print("❌ 反例没被抓到：%s（期望 %s，实际 %s）"
                  % (desc, expect, [f[:12] for f in got] or "无报告"))
        else:
            extra = [f for f in got if not f.startswith(expect)]
            note = "" if not extra else "（另有 %d 条非目标规则报告）" % len(extra)
            print("[OK] %-28s → %s%s" % (desc, hit[0][:80], note))

    # 登记表自己失效也必须被抓到：把某条例外指向一个不存在的片段
    global GAME_ID_EXCEPTIONS
    saved = GAME_ID_EXCEPTIONS
    try:
        tampered = dict(saved)
        key = os.path.join("com", "stzb", "assistant", "license", "LicenseManager.kt")
        tampered[key] = [('put("game_id", "nobody")', "被改动过的登记项")]
        got = []
        for _title, fn in RULES:
            if _title.startswith("R1"):
                GAME_ID_EXCEPTIONS = tampered
                got.extend(fn(collect_files(CLEAN_FILES)))
        if not any(f.startswith("R1 例外登记表已失效") for f in got):
            bad += 1
            print("❌ 例外登记表被改动却没被抓到（白名单失去核对就成了空档）")
        else:
            print("[OK] 例外登记表自己失效            → 被抓到")
    finally:
        GAME_ID_EXCEPTIONS = saved

    print("\n[selftest] %s" % ("全部通过" if bad == 0 else "有 %d 项不符合预期" % bad))
    return 0 if bad == 0 else 1


CHECKLIST = """
加一款游戏：现在到底要动什么（实测清单，不是愿景）
--------------------------------------------------
【不用改代码的部分】（P7d/P7f 之后成立）
  1. 知识包：为该游戏发布 profile JSON（game_id / profile_version / targetPackage /
     rules / defenderDb / semanticButtons 19 个语义键全覆盖 / landSuggestions /
     sceneKeywords 词表组 / heroBaseSpeed / pveMechanicNotes）。
     采纳闸门是 GameProfile.validateFor：不过就拒绝并回落，不会静默采纳半套。
  2. 激活：KnowledgeBaseManager 的纯云端分支（内置表缺键不再一票否决），
     切换对话框会把它列出来。
  3. 本机数据：锚点/投影/指纹/模板/门槛按 gameId 自动分域（PerGameScope），
     切游戏自动重载；旧数据只登记归属给率土。
  4. 资产：assets 目录走 templates/<gameId>/、权重名走 <base>_<gameId>.<ext>
     （率土仍兼容旧的无标记名，构建脚本无需改名）。

【仍然必须改代码/补资产的部分】（诚实登记，别信「零改动」的口号）
  A. 像素模板与头像库：新游戏必须自己截按钮、建 defender_refs/<gameId>/，
     否则按键定位退回语义 OCR 默认词、守军头像通道不启用。
  B. YOLO 权重：新游戏要自己采集-标注-训练并导出 ncnn，
     文件名形如 models/yolo26s_<gameId>.param/.bin；没有就走几何色度通道。
  C. RAG 语料与意图微脑：需要该游戏自己的向量索引
     （models/slg_knowledge_vector_hnsw_<gameId>.bin）与意图权重
     （models/intent_slot_zh_<gameId>.onnx + 两张词表），
     否则语料检索如实返回空、军令解析回落正则。
  D. 授权链路：LicenseManager 仍把 game_id 写死成率土（本轮明确不动激活），
     卖多游戏授权前必须先改造这一处。
  E. APK 内置档案（可选）：想让离线也带全套知识，就得再加一个 StzbKnowledgeBase
     式的内置类并登记进 builtInProfiles 注册表——纯云端游戏离线时只能用缓存。
"""


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="跑闸门自身的反例自测")
    ap.add_argument("--show-checklist", action="store_true", help="打印多游戏就绪实测清单")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    try:
        findings = scan()
    except RuntimeError as e:
        print("❌ 闸门无法自检：%s" % e)
        return 1

    if findings:
        print("❌ 多游戏就绪闸门发现 %d 处泄漏：" % len(findings))
        for f in findings:
            print("   " + f)
        print("\n结论：这些站点会让「加一款游戏不用改代码」变成假话，"
              "或让 A 游戏的数据/资产去服务 B 游戏（错得很自信）。")
        return 1

    print("[OK] 多游戏就绪闸门通过：游戏 id 字面量已登记、权重命名单一权威、"
          "分域数据随游戏重载、资产目录走游戏候选、纯云端激活路径未被改回硬闸")
    if args.show_checklist:
        print(CHECKLIST)
    return 0


if __name__ == "__main__":
    sys.exit(main())
