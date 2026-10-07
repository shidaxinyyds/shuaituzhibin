#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
RAG 语料溯源闸门：语料 ↔ 知识包**双向**对账
==========================================

盯的是这一类 bug（本轮实测抓到，不是假想）
--------------------------------------------
`corpus_stzb.jsonl` 曾经是知识包 `defender_db` 的手抄副本，抄完之后知识包又加了将：
    知识包 danger 11 / hard 10 / moderate 7 / safe 11  vs  语料 11 / 8 / 7 / 10
少的 3 条不会让任何东西报错 —— 端侧只是"检索不到那个守将"，
玩家看到的是"军师不认识赵云"，而日志里一切正常。

单向校验（"每条语料都能回指知识包"）**防不住它**：语料少几条，剩下每条照样有出处。
所以本闸门做双向：
    方向一（防编造）：JSONL 里每条的 `source` 路径必须能在知识包解析出值，
                      且该值确实出现在文案文字里；
    方向二（防漂移）：知识包里每个**应当成语料**的锚点（守将、土地建议、规则项、
                      战术默认值、武将速度、指挥增益战法、PVE 机制）都必须被某条
                      语料引用；漏了就点名指出是哪一条。
另加两项接线检查：
    方向三（通道契约）：语料 category 一律以 `SlgRagEngine.SEARCH_CHANNELS` 的**源码解析
                      结果**为准 —— 闸门不许自带一份对照表，否则闸门自己就成了第二权威。
                      三个检索通道每个都得有语料，否则"军师懂战法冲突/军令"就是口号。
    方向四（资产同步）：已发布的 `.bin` 头里的 item_count 必须等于 JSONL 行数，
                      防"改了语料忘记重建索引"（产物比源旧，真机跑的是旧文案）。

`--selftest` 不是走过场：它拿**磁盘上的真实 JSONL** 做四种突变（删一条、改一个数值、
塞一个不存在的 category、把 .bin 条数改头），逐条断言**同一个校验函数**变红。
未突变时必须全绿，否则说明基线本身不可信。这个设计是对 `check_knowledge_base.py`
那条历史教训的回应：它的 selftest 曾只测判定函数、从不测解析器，于是解析器瞎了还全绿。
"""

import argparse
import copy
import io
import json
import os
import re
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

PROFILE = os.path.join(REPO, "pipeline", "rate_of_land.json")
CORPUS = os.path.join(REPO, "tools", "a_plus_plus", "corpus_stzb.jsonl")
ASSET = os.path.join(REPO, "client", "app", "src", "main", "assets",
                     "models", "slg_knowledge_vector_hnsw.bin")
ENGINE_KT = os.path.join(REPO, "client", "app", "src", "main", "java",
                         "com", "stzb", "assistant", "ai", "rag", "SlgRagEngine.kt")

# 每个检索通道至少多少条语料。低于它就报"该通道实际撑不起回答"。
# 这个下界是**能力判断**而不是数学结果：一个通道里只有一两条文案时，
# 检索出来的建议没有区分度，等同于没有。
MIN_PER_CHANNEL = 3

# 与 build_rag_corpus.py **共用同一份**路径解析与格式化实现。
# 刻意不在这里再写一遍：上一轮就是因为"验证过的实现"和"生产用的实现"是两份手抄，
# 让闸门绿而 Kotlin 里的对应分支根本没跑到。两份 resolve 漂移时，闸门就成了摆设。
if HERE not in sys.path:
    sys.path.insert(0, HERE)
from build_rag_corpus import resolve, as_text  # noqa: E402


def strip_kt_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def parse_search_channels():
    """从 Kotlin 源码解析 {通道名: {category}}；解析不出来直接抛。

    刻意**不**在这里复制一份对照表：端侧改了表而闸门没跟上，就等于闸门在拿
    一个过期口径放行/拦人——那比没有闸门更坏。
    """
    with io.open(ENGINE_KT, encoding="utf-8") as fh:
        plain = strip_kt_comments(fh.read())
    chan_consts = dict(re.findall(r'const val (CHANNEL_[A-Z_]+)\s*=\s*"([^"]+)"', plain))
    m = re.search(r"SEARCH_CHANNELS[^=]*=\s*mapOf\(", plain)
    if not m:
        raise RuntimeError("SlgRagEngine 里解析不到 SEARCH_CHANNELS 的定义形状，闸门无法判定口径")
    block = plain[m.end() - 1:]
    depth, out = 0, ""
    for ch in block:
        out += ch
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                break
    channels = {}
    for key, setlit in re.findall(r'([A-Za-z_][A-Za-z0-9_]*)\s+to\s+setOf\(([^)]*)\)', out):
        cats = set(re.findall(r'"([^"]+)"', setlit))
        name = chan_consts.get(key, key)
        if not cats:
            raise RuntimeError("通道 %s 展开为空集合，源码形状与闸门解析器不符" % key)
        channels[name] = cats
    if not channels:
        raise RuntimeError("SEARCH_CHANNELS 块里一条通道也没解析到（解析器瞎了）")
    return channels


def load_corpus(path=CORPUS):
    rows = []
    with io.open(path, encoding="utf-8") as fh:
        for ln, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            for k in ("id", "category", "title", "content"):
                if k not in d:
                    raise ValueError("%s:%d 缺字段 %s" % (os.path.basename(path), ln, k))
            rows.append(d)
    return rows


def load_profile(path=PROFILE):
    with io.open(path, encoding="utf-8") as fh:
        return json.load(fh)


# ------------------------------------------------------------------ 方向一
def check_provenance(rows, profile):
    """方向一：语料的每个 source 值都要能回指知识包，并且真的出现在文案里。

    "出现在文案里"分两档，这个区分是被 selftest 逼出来的：
    最初我把 title/content/advice/keywords 拼成一个大串做子串判断，于是有人
    把正文里的 120 改成 999 也照样绿——因为标题"规则 · 体力上限 = 120"还留着。
    **标题带着数值不代表正文说的是这件事**。所以：
      * 数值/布尔型出处 → 必须出现在 content 或 advice（玩家真正听到的那句）；
      * 字符串型出处（武将名、机制文案）→ 允许出现在 title/content/advice 任一处，
        因为守将条目的人名本来就只写在标题里，正文写的是他的战法效果。
    """
    problems = []
    for r in rows:
        src = r.get("source")
        if not isinstance(src, dict) or not src:
            problems.append("%s: 没有 source 字段（无法证明这条来自知识包，属于手写）" % r["id"])
            continue
        voice = "%s %s %s" % (r["title"], r["content"], r.get("advice", ""))
        body = "%s %s" % (r["content"], r.get("advice", ""))
        for path, expected in src.items():
            try:
                got = resolve(profile, path)
            except KeyError as exc:
                problems.append("%s: %s" % (r["id"], exc))
                continue
            if isinstance(expected, list):
                for e in expected:
                    if as_text(e) not in voice:
                        problems.append("%s: 出处 %s 的值「%s」没出现在文案里" % (r["id"], path, e))
                continue
            if as_text(expected) != as_text(got):
                problems.append("%s: 出处 %s 记的是「%s」，知识包现值「%s」——语料比知识包旧" %
                                (r["id"], path, expected, as_text(got)))
                continue
            numeric = isinstance(expected, (int, float)) and not isinstance(expected, bool)
            where = body if numeric else voice
            if as_text(expected) not in where:
                problems.append("%s: 出处 %s 的值「%s」没有出现在%s里（标题带着它不算，正文必须说这件事）"
                                % (r["id"], path, expected, "正文/建议" if numeric else "文案"))
    return problems


# ------------------------------------------------------------------ 方向二
def expected_anchors(profile):
    """知识包里**应当成语料**的锚点路径集合。新增一类知识就要在这里加一行，
    否则新表永远没人给它出语料，而没人会报错。"""
    a = []
    db = profile.get("defender_db") or {}
    for field in ("danger_heroes", "hard_heroes", "moderate_heroes", "safe_heroes"):
        for i in range(len(db.get(field) or [])):
            a.append("defender_db.%s[%d].name" % (field, i))
    for i in range(len(db.get("land_suggestions") or [])):
        a.append("defender_db.land_suggestions[%d].land_level" % i)
    for k in sorted((profile.get("pve_mechanic_notes") or {})):
        a.append("pve_mechanic_notes.%s" % k)
    for k in sorted((profile.get("hero_base_speed") or {})):
        a.append("hero_base_speed.%s" % k)
    for i, _s in enumerate((profile.get("scene_keywords") or {}).get("COMMAND_AMPLIFY_SKILLS") or []):
        a.append("scene_keywords.COMMAND_AMPLIFY_SKILLS[%d]" % i)
    for k in sorted((profile.get("rules") or {})):
        a.append("rules.%s" % k)
    for k in sorted((profile.get("tactical_defaults") or {})):
        a.append("tactical_defaults.%s" % k)
    return a


def check_coverage(rows, profile):
    cited = set()
    for r in rows:
        for path in (r.get("source") or {}):
            cited.add(path)
            # 列表型出处（safe_heroes 等）按整组引用，其元素不算独立锚点
    missing = []
    for anchor in expected_anchors(profile):
        if anchor in cited:
            continue
        # 派生器把整张守将表的同类字段逐条引用；这里做一次前缀宽松匹配，
        # 允许"同一路径被写成不带 .name 的父级"（如 land_level 之外还有整条目引用）。
        parent = anchor.rsplit(".", 1)[0] if anchor.split(".")[-1] not in ("name", "land_level") else anchor
        if parent != anchor and parent in cited:
            continue
        missing.append(anchor)
    return ["知识包的 %s 没有对应语料（手抄漏了/知识包更新后没重跑派生器）" % m for m in missing]


# ------------------------------------------------------------------ 方向三
def check_channels(rows, channels):
    allowed = set()
    for cats in channels.values():
        allowed |= cats
    problems = []
    seen = {}
    for r in rows:
        cat = r["category"]
        seen[cat] = seen.get(cat, 0) + 1
        if cat not in allowed:
            problems.append("%s: category「%s」不在端侧 SEARCH_CHANNELS 展开集合里，"
                            "真机上这条永远检索不到（不报错，只是静默空手）" % (r["id"], cat))
    for name, cats in sorted(channels.items()):
        n = sum(seen.get(c, 0) for c in sorted(cats))
        if n < MIN_PER_CHANNEL:
            problems.append("检索通道「%s」在语料里只有 %d 条（要求 ≥%d）："
                            "这个通道的回答撑不起「军师懂它」的说法" % (name, n, MIN_PER_CHANNEL))
    return problems


# ------------------------------------------------------------------ 方向四
def check_asset_sync(rows, asset=ASSET):
    """方向四：产物必须是这份语料的产物。

    只比条数是不够的：把某条文案改掉、条数不变，真机就会念旧文案而闸门全绿。
    所以再把每条 title 的 UTF-8 字节拿去 .bin 里找一遍 —— 找不到即"产物比源旧"。
    """
    if not os.path.isfile(asset):
        return ["发布资产 .bin 不存在（%s）：语料从未被编译进端侧产物" % os.path.basename(asset)]
    with open(asset, "rb") as fh:
        blob = fh.read()
    if len(blob) < 28:
        return [".bin 不足 28 字节，容器损坏"]
    magic, ver, count, dim = struct.unpack("<16sIII", blob[:28])
    if magic not in (b"SLG_HNSW_RAG_V1\x00", b"SLG_HNSW_RAG_V2\x00"):
        return [".bin magic 不识别: %r" % magic]
    problems = []
    if count != len(rows):
        problems.append(".bin 里的条目数是 %d，而 corpus_stzb.jsonl 有 %d 行 —— "
                        "改完语料没重建索引，真机跑的是旧产物" % (count, len(rows)))
    if ver == 1 and dim == 64:
        problems.append(".bin 是 version=1 / 64 维哈希向量容器（无语义）："
                        "需要用 --mode bge 重建为 512 维真向量")
    stale = [r["id"] for r in rows if r["title"].encode("utf-8") not in blob]
    if stale:
        problems.append("有 %d 条语料的标题在 .bin 里找不到（%s…）：语料改过但索引是旧的"
                        % (len(stale), ", ".join(stale[:5])))
    return problems


def run_all(profile=None, rows=None, channels=None, asset=ASSET):
    profile = profile if profile is not None else load_profile()
    rows = rows if rows is not None else load_corpus()
    channels = channels if channels is not None else parse_search_channels()
    problems = []
    ids = [r["id"] for r in rows]
    if len(set(ids)) != len(ids):
        problems.append("语料 id 重复: %s" % sorted({i for i in ids if ids.count(i) > 1}))
    problems += check_provenance(rows, profile)
    problems += check_coverage(rows, profile)
    problems += check_channels(rows, channels)
    problems += check_asset_sync(rows, asset)
    return problems


# ---------------------------------------------------------------- selftest
def selftest():
    """拿真实 JSONL 做突变，断言同一个 run_all 变红。"""
    profile, rows, channels = load_profile(), load_corpus(), parse_search_channels()
    base = run_all(profile, rows, channels)
    if base:
        print("❌ 基线不干净，selftest 失去意义；先修基线：%d 项" % len(base))
        for p in base:
            print("   - %s" % p)
        return 1

    mutations = []

    # 1) 删掉一条守将语料 —— 复现"手抄漏了三条"那个原始 bug
    r1 = list(rows)
    victim = next(r for r in r1 if r["category"] == "DEFENDER_HARD")
    r1.remove(victim)
    mutations.append(("删一条 DEFENDER_HARD 语料", r1, None, "没有对应语料"))

    # 2) 知识包给守将表加了人，语料没跟上（漂移的真实成因）
    p2 = copy.deepcopy(profile)
    p2["defender_db"]["safe_heroes"].append(
        {"name": "新加守将", "tag": "占位", "threat_score": 1,
         "description": "占位", "counter_tip": "占位"})
    mutations.append(("知识包新增一条守将", rows, p2, "没有对应语料"))

    # 3) 有人直接编辑 JSONL 里的数值（绕过派生器）
    r3 = copy.deepcopy(rows)
    for r in r3:
        if "rules.max_stamina" in (r.get("source") or {}):
            r["content"] = r["content"].replace(as_text(r["source"]["rules.max_stamina"]), "999")
            break
    else:
        print("❌ selftest 找不到 rules.max_stamina 那条语料，突变 3 无法构造")
        return 1
    mutations.append(("手改语料里的数值", r3, None, "没有出现在正文/建议里"))

    # 4) 新 category 没进端侧对照表
    r4 = copy.deepcopy(rows)
    r4[0] = dict(r4[0])
    r4[0]["category"] = "DEFENDER_SOMETHING_NEW"
    mutations.append(("写出对照表外的 category", r4, None, "不在端侧 SEARCH_CHANNELS"))

    # 5) 通道被清空（把 skill 通道覆盖的那些 category 全删干净）
    if "SKILL_SYNERGY" not in channels:
        print("❌ 端侧 SEARCH_CHANNELS 里已经没有 SKILL_SYNERGY 通道，selftest 的突变 5 无法构造")
        return 1
    skill_cats = channels["SKILL_SYNERGY"]
    r5 = [r for r in rows if r["category"] not in skill_cats]
    mutations.append(("战法通道语料清零", r5, None, "撑不起"))

    # 6) 只改文案、条数不变 —— 这是"比条数"这种弱检查唯一漏掉的情形
    r6 = copy.deepcopy(rows)
    r6[0] = dict(r6[0])
    r6[0]["title"] = "被改过但没重建索引的标题"
    mutations.append(("改标题不重建索引", r6, None, "标题在 .bin 里找不到"))

    failed = 0
    for label, mr, mp, expect in mutations:
        probs = run_all(mp or profile, mr, channels)
        hit = [p for p in probs if expect in p]
        if hit:
            print("   [OK] %-24s → 变红：%s" % (label, hit[0][:78]))
        else:
            print("   ❌ %-24s → 闸门没反应（期望包含「%s」）" % (label, expect))
            failed += 1
    if failed:
        print("❌ selftest 失败 %d 项：这些突变能骗过闸门，说明校验是摆设" % failed)
        return 1
    print("[OK] selftest：%d 种突变全部被同一个校验函数拦下（基线全绿）" % len(mutations))
    return 0


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true", help="用突变证明闸门真会 fail")
    ap.add_argument("--profile", default=PROFILE)
    ap.add_argument("--corpus", default=CORPUS)
    ap.add_argument("--asset", default=ASSET)
    args = ap.parse_args(argv)

    if args.selftest:
        return selftest()

    try:
        channels = parse_search_channels()
    except RuntimeError as exc:
        print("❌ %s" % exc)
        return 1

    profile, rows = load_profile(args.profile), load_corpus(args.corpus)
    problems = run_all(profile, rows, channels, args.asset)

    print("知识包 %s / 语料 %d 条 / 通道 %d 个（解析自 SlgRagEngine.SEARCH_CHANNELS）"
          % (os.path.basename(args.profile), len(rows), len(channels)))
    for name in sorted(channels):
        print("   %-16s %s" % (name, "/".join(sorted(channels[name]))))
    if problems:
        print("\n❌ 溯源对账失败 %d 项：" % len(problems))
        for p in problems:
            print("   - %s" % p)
        print("\n正确修法：改知识包后重跑 `python tools/build_rag_corpus.py`，"
              "再重建 .bin；不要直接编辑 JSONL。")
        return 1
    print("\n[OK] 语料与知识包双向一致：无编造、无遗漏、通道齐全、资产已同步")
    return 0


if __name__ == "__main__":
    sys.exit(main())
