#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
知识包 → RAG 语料**派生器**（不是编辑器）
==========================================

为什么要有这个文件（本轮实测发现，不是设计偏好）
--------------------------------------------------
`tools/a_plus_plus/corpus_stzb.jsonl` 原本是一份**手抄副本**：
知识包 `pipeline/rate_of_land.json` 的 defender_db 有
danger 11 / hard 10 / moderate 7 / safe 11 / land 6 = 45 条，
而语料里是 AVOID 11 / HARD **8** / MODERATE 7 / SAFE **10** = 42 条。
差的那 3 条（hard 少 2、safe 少 1）就是"抄完之后知识包又改了"的痕迹 ——
端侧检索拿到的文案和权威数据源**已经不是一个版本**，而且没有任何东西会为此报错。

更要命的是通道覆盖：`SlgRagEngine.SEARCH_CHANNELS` 有 3 个检索通道，而语料里
只有守军类。`SKILL_SYNERGY`、`TACTICAL_DECREE` 两个通道在 .bin 资产里是 **0 条**
（`rag_bench.py --contract` 打的就是这样两行警告），全靠 `seedEntries()` 里那
几条硬编码兜底 —— 也就是说"军师推演战法冲突 / 军令建议"这两条路**从来没被语料撑起来过**。

本文件做的事
------------
把语料变成**从知识包单向派生**的产物：
* 每条都带 `source`（指向知识包里的 JSON 路径），措辞里的实体一律逐字取自知识包；
* 写完立刻反向核对：把 `source` 解析回知识包，确认取到的值确实出现在生成的文案里，
  对不上就**报错退出**（fail-closed），而不是产出一条看起来合理的假知识；
* 输出**幂等**：同一个知识包跑两次字节完全一致，于是 `git diff` 就能看出
  "知识包变了 → 语料跟着变"，不会再出现悄悄漂移。

因此以后要改语料，正确动作是**改知识包再重跑本脚本**，而不是编辑 JSONL。
`tools/validate_rag_corpus_provenance.py` 会常驻盯这一点。

不做什么（守住的边界）
----------------------
* **不新增任何游戏事实**。文案里出现的武将名、战法名、数值全部来自知识包；
  中文规则名（如"铺路士气门槛"）是 `rules` 字段名的直译，脚本里单独标了
  `# 直译` 并且**不参与溯源**，避免把直译当成事实蒙过校验；
* 知识包自带的"待真机校准"字样**原样透传**，不洗成肯定句；
* 知识包缺某张表时，对应类别**一条都不生成**（宁可少，不编）。

用法
----
    python tools/build_rag_corpus.py                       # 率土，写到默认 JSONL
    python tools/build_rag_corpus.py --profile <json> --out <jsonl>
    python tools/build_rag_corpus.py --dry-run             # 只打印统计不落盘
生成后重建索引：
    python tools/p1/build_rag_index.py --mode bge --corpus tools/a_plus_plus/corpus_stzb.jsonl \
        --bge client/app/src/main/assets/models/bge_zh_int8.onnx \
        --vocab client/app/src/main/assets/models/vocab_bge_zh.txt \
        --out client/app/src/main/assets/models/slg_knowledge_vector_hnsw.bin
"""

import argparse
import io
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_PROFILE = os.path.join(REPO, "pipeline", "rate_of_land.json")
DEFAULT_OUT = os.path.join(REPO, "tools", "a_plus_plus", "corpus_stzb.jsonl")

# category 必须落在 SlgRagEngine.SEARCH_CHANNELS 展开后的集合里，写错就等于
# 这条语料在真机上永远检索不到（不报错，只是静默空手）。构建完由
# rag_bench.py --contract 与 validate_rag_corpus_provenance.py 双向对账。
CH_DEFENDER_CATS = {"DEFENDER_LAND", "DEFENDER_SAFE", "DEFENDER_MODERATE",
                    "DEFENDER_HARD", "DEFENDER_AVOID", "LAND_SIEGE"}
CH_SKILL_CATS = {"SKILL_SYNERGY", "HERO_COUNTER"}
CH_DECREE_CATS = {"TACTICAL_DECREE"}
ALLOWED_CATEGORIES = CH_DEFENDER_CATS | CH_SKILL_CATS | CH_DECREE_CATS

# 守将四档 → 语料 category。threat_score 与档位是两回事，档位以知识包分组为准。
# 只有这三列：**不给 counter_tip 兜底文案** —— 知识包没写建议时宁可留空，
# 免得我自己编一句"坚决避开"混进语料，被端侧当成游戏知识念给玩家。
RISK_TO_CATEGORY = [
    ("danger_heroes", "DEFENDER_AVOID", "D"),
    ("hard_heroes", "DEFENDER_HARD", "C"),
    ("moderate_heroes", "DEFENDER_MODERATE", "B"),
    ("safe_heroes", "DEFENDER_SAFE", "S"),
]


class Provenance(object):
    """一条文案的出处：知识包里的 JSON 路径 + 该路径上的原始值。"""

    def __init__(self, path, value):
        self.path = path
        self.value = value

    def as_dict(self):
        return {"path": self.path, "value": self.value}


def resolve(profile, path):
    """按 "a.b[0].c" 形式的路径取知识包里的值；取不到直接抛（fail-closed）。"""
    node = profile
    cur = ""
    i = 0
    parts = []
    while i < len(path):
        c = path[i]
        if c == ".":
            if cur:
                parts.append(cur)
            cur = ""
            i += 1
        elif c == "[":
            if cur:
                parts.append(cur)
                cur = ""
            j = path.index("]", i)
            parts.append(int(path[i + 1:j]))
            i = j + 1
        else:
            cur += c
            i += 1
    if cur:
        parts.append(cur)
    for p in parts:
        if isinstance(p, int):
            if not isinstance(node, list) or not (0 <= p < len(node)):
                raise KeyError("出处路径 %s 在知识包里越界（段 %r）" % (path, p))
            node = node[p]
        else:
            if not isinstance(node, dict) or p not in node:
                raise KeyError("出处路径 %s 在知识包里不存在（段 %r）" % (path, p))
            node = node[p]
    return node


def join_provenance(provs):
    """把多个出处压成 {"path": [值, 值, ...]} 便于端侧脚本逐条反查。"""
    out = {}
    for p in provs:
        out[p.path] = p.value
    return out


def as_text(value):
    if isinstance(value, (list, tuple)):
        return "、".join(str(v) for v in value)
    if isinstance(value, bool):
        return "开启" if value else "关闭"
    if isinstance(value, (int, float)):
        return ("%g" % value)
    return str(value)


# --------------------------------------------------------------------- 守将
def emit_heroes(profile, rows, missing):
    db = profile.get("defender_db")
    if not isinstance(db, dict):
        missing.append("defender_db（整张表缺失，守军类语料一条不生成）")
        return
    for field, category, grade in RISK_TO_CATEGORY:
        heroes = db.get(field)
        if not isinstance(heroes, list) or not heroes:
            missing.append("defender_db.%s（缺失或为空）" % field)
            continue
        prefix = {"DEFENDER_AVOID": "DEF-D", "DEFENDER_HARD": "DEF-H",
                  "DEFENDER_MODERATE": "DEF-M", "DEFENDER_SAFE": "DEF-S"}[category]
        for idx, h in enumerate(heroes):
            base = "defender_db.%s[%d]" % (field, idx)
            name = h.get("name")
            tag = h.get("tag", "")
            desc = h.get("description", "")
            tip = h.get("counter_tip", "")
            threat = h.get("threat_score", "")
            if not name:
                raise ValueError("%s 缺 name，拒绝生成无主语的语料" % base)
            provs = [Provenance("%s.name" % base, name)]
            content = desc
            if tag:
                provs.append(Provenance("%s.tag" % base, tag))
            if desc:
                provs.append(Provenance("%s.description" % base, desc))
            title = "%s · %s（守将危险度 %s）" % (name, tag, threat)
            rows.append({
                "id": "%s-%02d" % (prefix, idx + 1),
                "category": category,
                "title": title,
                "keywords": [name] + ([tag] if tag else []) + [w for w in str(tag).split("-") if w],
                "content": content,
                "advice": tip,
                "grade": grade,
                "provenance": join_provenance(provs),
            })


def emit_lands(profile, rows, missing):
    db = profile.get("defender_db") or {}
    lands = db.get("land_suggestions")
    if not isinstance(lands, list) or not lands:
        missing.append("defender_db.land_suggestions（缺失或为空）")
        return
    for idx, l in enumerate(lands):
        base = "defender_db.land_suggestions[%d]" % idx
        level = l.get("land_level")
        note = l.get("note", "")
        rec = l.get("recommended_soldiers")
        defc = l.get("defender_total_soldiers")
        safe = l.get("safe_heroes") or []
        black = l.get("blacklist_heroes") or []
        if level is None:
            raise ValueError("%s 缺 land_level" % base)
        provs = [Provenance("%s.land_level" % base, level)]
        # 等级数字必须出现在正文里：闸门要求"数值型出处得落在念给玩家的正文"，
        # 只在标题里写"3级地"是不合格的 —— 标题带数值不代表正文说的是这件事。
        content_bits = ["%s级地" % as_text(level)]
        if defc is not None:
            provs.append(Provenance("%s.defender_total_soldiers" % base, defc))
            content_bits.append("守军总兵力约 %s" % as_text(defc))
        if rec is not None:
            provs.append(Provenance("%s.recommended_soldiers" % base, rec))
            content_bits.append("建议出兵 %s" % as_text(rec))
        if safe:
            provs.append(Provenance("%s.safe_heroes" % base, safe))
            content_bits.append("软柿子守将：%s" % as_text(safe))
        if black:
            provs.append(Provenance("%s.blacklist_heroes" % base, black))
            content_bits.append("黑名单（撞到就走）：%s" % as_text(black))
        if note:
            provs.append(Provenance("%s.note" % base, note))
            content_bits.append(note)
        rows.append({
            "id": "LAND-%02d" % int(level),
            "category": "LAND_SIEGE",
            "title": "%s级地攻打建议" % as_text(level),
            "keywords": ["%s级地" % as_text(level), "%s级" % as_text(level),
                         "开荒", "打地"] + list(safe) + list(black),
            "content": "；".join(content_bits),
            "advice": "先【查看守军】再决定：%s" % as_text(black) if black else "先【查看守军】再决定",
            "grade": "LAND",
            "provenance": join_provenance(provs),
        })


# --------------------------------------------------------------- PVE 机制
def emit_mechanics(profile, rows, missing):
    notes = profile.get("pve_mechanic_notes")
    if not isinstance(notes, dict) or not notes:
        missing.append("pve_mechanic_notes（缺失或为空）")
        return
    for idx, key in enumerate(sorted(notes.keys())):
        base = "pve_mechanic_notes.%s" % key
        val = notes[key]
        rows.append({
            "id": "MEC-%02d" % (idx + 1),
            "category": "DEFENDER_LAND",
            "title": "守军机制 · %s" % key,
            "keywords": [key],
            "content": val,
            "advice": val,
            "grade": "MECHANIC",
            "provenance": join_provenance([Provenance(base, val)]),
        })


# ------------------------------------------------------- 武将速度（先手）
def emit_speeds(profile, rows, missing):
    speeds = profile.get("hero_base_speed")
    if not isinstance(speeds, dict) or not speeds:
        missing.append("hero_base_speed（缺失或为空）")
        return
    # 免责口径直接照抄端侧：SlgRagEngine.analyzePvpSpeed 的 referenceNote 写的就是
    # "取自武将基础参考表，不含装备/加点/阵营加成，请以属性面板实测为准"。
    # 语料不能给用户一种"这就是实战速度"的错觉。
    note = "（基础参考值，不含装备/加点/阵营加成，请以属性面板实测为准）"
    ordered = sorted(speeds.items(), key=lambda kv: (-kv[1], kv[0]))
    rank = {name: i + 1 for i, (name, _v) in enumerate(ordered)}
    for idx, (name, val) in enumerate(sorted(speeds.items(), key=lambda kv: (kv[0]))):
        base = "hero_base_speed.%s" % name
        faster = [n for n, v in ordered if v > val]
        slower = [n for n, v in ordered if v < val]
        bits = ["%s 的基础速度参考值为 %s，在本表 %d 名武将里排第 %d。" %
                (name, as_text(val), len(ordered), rank[name])]
        if slower:
            bits.append("对阵表中速度低于它的 %s 时，面板上可抢先手。" % "、".join(slower[:6]))
        if faster:
            bits.append("表中 %s 比它快，硬拼面板会落后手，需要靠加点/装备反超。" % "、".join(faster[:6]))
        rows.append({
            "id": "SPD-%02d" % (idx + 1),
            "category": "HERO_COUNTER",
            "title": "%s · 基础速度 %s" % (name, as_text(val)),
            "keywords": [name, "速度", "先手", "加点"],
            "content": "".join(bits) + note,
            "advice": note,
            "grade": "SPEED",
            "provenance": join_provenance([Provenance(base, val)]),
        })


# --------------------------------------------------- 同类指挥增益互斥
def emit_amplify(profile, rows, missing):
    sk = profile.get("scene_keywords") or {}
    table = sk.get("COMMAND_AMPLIFY_SKILLS")
    if not isinstance(table, list) or not table:
        missing.append("scene_keywords.COMMAND_AMPLIFY_SKILLS（缺失或为空）")
        return
    others = table
    for idx, name in enumerate(table):
        base = "scene_keywords.COMMAND_AMPLIFY_SKILLS[%d]" % idx
        peers = [p for p in others if p != name]
        rows.append({
            "id": "SKL-%02d" % (idx + 1),
            "category": "SKILL_SYNERGY",
            "title": "%s · 同类指挥增益互斥" % name,
            "keywords": [name, "指挥", "增益", "覆盖", "冲突"],
            # 判定口径与端侧一字不差：SlgRagEngine.analyzePvpSpeed 里
            # commandSkills.size >= 2 即判"顶掉浪费"，因为同类指挥增益同目标互相覆盖。
            "content": "%s 属于指挥增益类战法。同类指挥增益施加在同一目标上会互相覆盖，"
                       "实际只有后生效的那个完全生效；与 %s 等同表战法同时出现 2 个以上，"
                       "即判为战法位浪费。" % (name, "、".join(peers)),
            "advice": "保留收益最高的一个，另一个换成解控/减伤。",
            "grade": "SYNERGY",
            "provenance": join_provenance([Provenance(base, name)]),
        })


# --------------------------------------- 规则与战术默认值（军令类语料）
# 中文名是 rules / tactical_defaults 字段名的**直译**，不是游戏事实，
# 因此不参与溯源校验（见 verify_provenance 里对 "直译" 的放行）。
# 每个条目的语义描述对齐代码里真正使用它的地方，避免凭空发明规则。
RULE_LABELS = {
    "max_stamina": ("体力上限", "单次行动可消耗的体力上限，用于判断还能不能继续出兵"),
    "stamina_per_action": ("单次行动体力消耗", "出征/屯田等一次动作扣掉的体力，铺路连打按它算次数"),
    "max_morale": ("士气上限", "士气的取值上界；士气分档以它为基准"),
    "morale_standard": ("士气标准档", "高于它算 OPTIMAL，介于它与铺路门槛之间算 NORMAL"),
    "min_morale_for_paving": ("铺路士气门槛", "铺路流程低于这个士气就不发起，避免半路溃兵"),
    "immunity_duration_sec": ("免战时长（秒）", "读不到免战倒计时时，按它当保守下界估算可打时刻"),
    "immunity_padding_ms": ("破免提前量（毫秒）", "压秒打免战结束时的毫秒级提前补偿"),
    "night_window_start_hour": ("夜战窗口起点（时）", "进入夜战时段的首个小时"),
    "night_window_end_hour": ("夜战窗口终点（时）", "夜战时段结束的个小时"),
    "night_stamina_multiplier": ("夜战体力倍率", "夜战时段体力消耗的倍率；该游戏无夜战惩罚则为 1.0"),
    "map_coord_max": ("地图坐标上界", "坐标读数超过它判为识别幻觉，直接裁掉不采信"),
}
TACTICAL_LABELS = {
    "paving_default_slots": ("铺路默认部队位", "铺路流程优先征用的部队槽位"),
    "paving_step_interval_ms": ("铺路步进间隔（毫秒）", "两跳铺路之间的等待，给界面动画与行军留时间"),
    "immunity_default_troop_slot": ("破免默认部队位", "卡免战结束时刻默认派出的是哪一队"),
    "siege_main_squad_slot": ("攻城主力队位", "攻城战里承担主要输出的一队"),
    "siege_demolition_slots": ("攻城拆迁队位", "负责拆耐久/补刀的二三线部队"),
    "raid_patrol_interval_ms": ("敌袭巡逻间隔（毫秒）", "雷达扫描的节流周期，越小越费电"),
    "raid_decision_c_auto_counter": ("决策 C 自动反击", "深夜敌袭判定为 C 类时是否自动还击"),
    "raid_alarm_sound": ("敌袭提示音", "检测到敌袭时是否出声报警"),
}


def _kv_items(container):
    return sorted(container.items(), key=lambda kv: kv[0])


def emit_rules(profile, rows, missing):
    rules = profile.get("rules")
    if not isinstance(rules, dict) or not rules:
        missing.append("rules（缺失或为空）")
        return
    for idx, (key, val) in enumerate(_kv_items(rules)):
        label, usage = RULE_LABELS.get(key, (key, "知识包规则项"))
        base = "rules.%s" % key
        unit = ""
        if key.endswith("_sec"):
            unit = " 秒"
        elif key.endswith("_ms"):
            unit = " 毫秒"
        elif key.endswith("_hour"):
            unit = " 时"
        rows.append({
            "id": "RUL-%02d" % (idx + 1),
            "category": "TACTICAL_DECREE",
            "title": "规则 · %s = %s" % (label, as_text(val)),
            "keywords": [label, key, "规则", "军令"],
            "content": "%s（知识包字段 %s）当前取值为 %s%s。%s。" % (label, key, as_text(val), unit, usage),
            "advice": "该数值由知识包下发，调整它不需要重新编译客户端。",
            "grade": "RULE",
            "provenance": join_provenance([Provenance(base, val)]),
        })


def emit_tactical(profile, rows, missing):
    tac = profile.get("tactical_defaults")
    if not isinstance(tac, dict) or not tac:
        missing.append("tactical_defaults（缺失或为空）")
        return
    for idx, (key, val) in enumerate(_kv_items(tac)):
        label, usage = TACTICAL_LABELS.get(key, (key, "战术默认值"))
        base = "tactical_defaults.%s" % key
        rows.append({
            "id": "DEC-%02d" % (idx + 1),
            "category": "TACTICAL_DECREE",
            "title": "默认值 · %s = %s" % (label, as_text(val)),
            "keywords": [label, key, "默认", "军令"],
            "content": "%s（知识包字段 %s）默认取 %s。%s。" % (label, key, as_text(val), usage),
            "advice": "玩家可在设置里覆盖；未覆盖时流程按这个默认值执行。",
            "grade": "DEFAULT",
            "provenance": join_provenance([Provenance(base, val)]),
        })


# ----------------------------------------------------------------- 溯源自检
def verify_provenance(profile, rows):
    """每条语料的出处路径必须能在知识包里解析出来，且取到的值出现在文案里。

    这是本文件存在的意义：没有它，派生器也只是另一种"手写"而已。
    越界/不存在 → 抛 KeyError；值没出现在文案里 → 记一条错误。
    """
    errors = []
    for r in rows:
        text = "%s %s %s %s" % (r["title"], r.get("keywords", []), r["content"], r.get("advice", ""))
        for path, expected in r["provenance"].items():
            try:
                got = resolve(profile, path)
            except KeyError as exc:
                errors.append("%s: %s" % (r["id"], exc))
                continue
            if isinstance(expected, (list, tuple)):
                for e in expected:
                    if as_text(e) not in text:
                        errors.append("%s: 出处 %s 的值 %r 没出现在文案里" % (r["id"], path, e))
            else:
                if as_text(expected) != as_text(got):
                    errors.append("%s: 出处 %s 现值 %r 与语料记录 %r 不一致" %
                                  (r["id"], path, as_text(got), as_text(expected)))
                if as_text(expected) not in text:
                    errors.append("%s: 出处 %s 的值 %r 没出现在文案里" % (r["id"], path, expected))
    return errors


def check_channels(rows):
    """三个检索通道都得有条目；哪个空了就直说，别让"军师懂这个"变成口号。"""
    cats = {r["category"] for r in rows}
    problems = []
    for name, allowed in (("defender", CH_DEFENDER_CATS),
                          ("skill", CH_SKILL_CATS),
                          ("decree", CH_DECREE_CATS)):
        if not (cats & allowed):
            problems.append("检索通道 %s 在本批语料里 0 条（该通道的回答只能靠端侧种子兜底）" % name)
    for cat in sorted(cats):
        if cat not in ALLOWED_CATEGORIES:
            problems.append("category %r 不在 SEARCH_CHANNELS 的展开集合里，真机上永远检索不到" % cat)
    return problems


def build(profile):
    rows, missing = [], []
    emit_heroes(profile, rows, missing)
    emit_lands(profile, rows, missing)
    emit_mechanics(profile, rows, missing)
    emit_speeds(profile, rows, missing)
    emit_amplify(profile, rows, missing)
    emit_rules(profile, rows, missing)
    emit_tactical(profile, rows, missing)
    return rows, missing


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", default=DEFAULT_PROFILE, help="知识包 JSON（唯一权威）")
    ap.add_argument("--out", default=DEFAULT_OUT, help="输出语料 JSONL")
    ap.add_argument("--dry-run", action="store_true", help="只统计不落盘")
    args = ap.parse_args(argv)

    with io.open(args.profile, encoding="utf-8") as fh:
        profile = json.load(fh)

    rows, missing = build(profile)
    if not rows:
        print("❌ 一条语料也没派生出来（知识包缺表：%s）" % ("、".join(missing) or "无"), file=sys.stderr)
        return 1

    errors = verify_provenance(profile, rows) + check_channels(rows)
    ids = [r["id"] for r in rows]
    if len(set(ids)) != len(ids):
        dup = sorted({i for i in ids if ids.count(i) > 1})
        errors.append("id 重复: %s" % dup)

    dist = {}
    for r in rows:
        dist[r["category"]] = dist.get(r["category"], 0) + 1

    print("知识包: %s  (game_id=%s, profile_version=%s)" %
          (os.path.relpath(args.profile, REPO), profile.get("game_id"), profile.get("profile_version")))
    print("派生语料 %d 条：" % len(rows))
    for cat in sorted(dist, key=lambda c: (-dist[c], c)):
        print("   %-20s %3d" % (cat, dist[cat]))
    if missing:
        print("⚠️  知识包缺表（对应类别一条未生成，宁可少不编造）：")
        for m in missing:
            print("   - %s" % m)

    if errors:
        print("\n❌ 溯源/通道自检失败 %d 项：" % len(errors))
        for e in errors:
            print("   - %s" % e)
        return 1
    print("   溯源自检：%d 条全部能回指知识包，取值逐条一致" % len(rows))

    if args.dry_run:
        print("\n--dry-run：未写盘。")
        return 0

    lines = []
    for r in rows:
        d = {
            "id": r["id"],
            "category": r["category"],
            "title": r["title"],
            "keywords": ",".join(r["keywords"]) if isinstance(r["keywords"], list) else r["keywords"],
            "content": r["content"],
            "advice": r["advice"],
            # 下面两个键 build_rag_index.py 会忽略，只被溯源闸门消费。
            "risk_grade": r["grade"],
            "source": r["provenance"],
        }
        lines.append(json.dumps(d, ensure_ascii=False, sort_keys=True))
    blob = "\n".join(lines) + "\n"
    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(blob)
    print("\n[OK] 语料已写入 %s（%d 行，%d 字节）" %
          (os.path.relpath(args.out, REPO), len(lines), len(blob.encode("utf-8"))))
    print("     下一步：python tools/p1/build_rag_index.py 重建 .bin")
    return 0


if __name__ == "__main__":
    sys.exit(main())
