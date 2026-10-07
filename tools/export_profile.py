#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
云端知识库产物导出器 (export_profile.py)
=======================================

为什么必须有它
--------------
`pipeline/*.json` 是要上传到 Supabase、被全网客户端静默采纳的**产物**，
而真正的唯一权威是 Kotlin 里的内置知识库（StzbKnowledgeBase.kt / SgzKnowledgeBase.kt）。

过去这份产物是**手工维护**的，于是必然发生这样的事：内置库新增了 CANCEL/FORGE 按键、
补了守军总兵力、修好了武将名单，而 JSON 还停在几个月前——并且**没有任何检查会发现**。
一旦把它推上云端，客户端拿到的是"版本号相同、内容更弱"的配置：缺按键、缺守将、旧名单。

所以这里把产物变成**可复现的导出**：改内置库 → 重跑本脚本 → 产物必然同步。
配套的 freshness 闸门（--check）会长期盯着"产物是否落后于内置库"。

⚠️ 本脚本刻意**不做**任何"补默认值"：内置库里读不到的字段一律直接报错中断。
一份"看起来完整但其实是我编的"云端产物，比没有产物危险得多。

用法
----
    python tools/export_profile.py                 # 重新导出全部游戏产物
    python tools/export_profile.py --check         # 只校验产物是否新鲜（CI 用；落后则退出 1）
    python tools/export_profile.py --kb sgz        # 只导出某一个游戏

产物文件名在 PRODUCTS 里集中声明；新增游戏必须同时在这里登记，
否则它根本没有云端产物，热更时无从选择。
"""

import argparse
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_knowledge_base as kb  # noqa: E402  复用同一个解析器，杜绝"两套解析各说各话"

REPO = kb.REPO

# gameId -> 产物文件（相对仓库根）
PRODUCTS = {
    "stzb": "pipeline/rate_of_land.json",
    "sgz": "pipeline/three_kingdoms_profile.json",
}

# PerceptionTier 的预设阶梯：必须与 PerceptionTier.kt 里 VisionPolicy 的定义逐字一致。
# 端上 VisionPolicy.fromJson 认的是 PerceptionTier 的枚举名，写错一个字母就等于关掉那一层感知。
VISION_TIERS = {
    "SLG_DEFAULT": ["DETERMINISTIC", "OCR", "CLASSIFIER"],
    "MMO_DEFAULT": ["DETERMINISTIC", "OCR"],
    "ACTION_DEFAULT": ["DETERMINISTIC", "OCR", "CLASSIFIER", "DETECTOR", "POLICY"],
}

# rules 的 JSON 键：顺序与 GameProfile.toJson() 完全一致，便于逐行 diff。
RULE_KEYS = [
    ("maxStamina", "max_stamina"),
    ("staminaPerAction", "stamina_per_action"),
    ("maxMorale", "max_morale"),
    ("moraleStandard", "morale_standard"),
    ("minMoraleForPaving", "min_morale_for_paving"),
    ("immunityDurationSec", "immunity_duration_sec"),
    ("immunityPaddingMs", "immunity_padding_ms"),
    ("nightWindowStartHour", "night_window_start_hour"),
    ("nightWindowEndHour", "night_window_end_hour"),
    ("nightStaminaMultiplier", "night_stamina_multiplier"),
    # 地图坐标上界：端侧四处判定（UI 匹配/安全闸门/端侧小模型/邮件解析）都读它，
    # 不导出就等于"热更改了地图边界，端上仍按 1500 裁坐标"。
    ("mapCoordMax", "map_coord_max"),
]

TACTICAL_KEYS = [
    ("pavingDefaultSlots", "paving_default_slots"),
    ("pavingStepIntervalMs", "paving_step_interval_ms"),
    ("immunityDefaultTroopSlot", "immunity_default_troop_slot"),
    ("siegeMainSquadSlot", "siege_main_squad_slot"),
    ("siegeDemolitionSlots", "siege_demolition_slots"),
    ("raidPatrolIntervalMs", "raid_patrol_interval_ms"),
    ("raidDecisionCAutoCounter", "raid_decision_c_auto_counter"),
    ("raidAlarmSound", "raid_alarm_sound"),
]

HERO_TIERS = [
    ("danger", "danger_heroes"),
    ("hard", "hard_heroes"),
    ("moderate", "moderate_heroes"),
    ("safe", "safe_heroes"),
]


def _die(msg):
    print("❌ %s" % msg)
    sys.exit(1)


def _require(cond, msg):
    if not cond:
        _die(msg)


def build_json(prof):
    """把 parse_kb_file 的结果拼成与 GameProfile.toJson() 同构的 dict。

    字段表是显式列出来的（RULE_KEYS / TACTICAL_KEYS）：内置库里出现了表里没有的
    字段时**必须报错**，而不是悄悄少导出一个键——少一个键到了端上就是
    `optXxx(默认值)`，等于用默认值覆盖了玩家的热更数据。
    """
    _require(prof["game_id"], "内置库没解析出 gameId，产物无法命名。")
    _require(prof["game_name"], "%s 没解析出 gameName。" % prof["file"])
    _require(prof["profile_version"], "%s 没解析出 profileVersion。" % prof["file"])
    _require(prof["target_package"], "%s 没解析出 targetPackage（前台闸门要靠它）。" % prof["file"])

    # 防串块：顶层字符串字段不应该落在 rules 里（_balanced_block 一旦取错范围，
    # 就会把 gameName/description 当成规则值导出去）。
    for key in ("gameName", "description", "profileVersion", "targetPackage", "gameId"):
        _require(key not in prof["rules"],
                 "%s 的 rules 里出现了 %s，说明解析器串了块，产物不可信。" % (prof["file"], key))

    unknown_rules = sorted(set(prof["rules"]) - {k for k, _ in RULE_KEYS})
    _require(not unknown_rules,
             "%s 有 rules 字段未被导出表覆盖：%s；请在 RULE_KEYS 里补上（端上 toJson 也会导出它）。"
             % (prof["file"], ", ".join(unknown_rules)))
    missing_rules = sorted({k for k, _ in RULE_KEYS} - set(prof["rules"]))
    _require(not missing_rules,
             "%s 缺 rules 字段：%s；内置库不完整，不能导出半成品产物。"
             % (prof["file"], ", ".join(missing_rules)))

    unknown_tac = sorted(set(prof["tactical"]) - {k for k, _ in TACTICAL_KEYS})
    _require(not unknown_tac,
             "%s 有 tacticalDefaults 字段未被导出表覆盖：%s；请在 TACTICAL_KEYS 里补上。"
             % (prof["file"], ", ".join(unknown_tac)))

    vp = prof["vision_policy"]
    _require(vp in VISION_TIERS,
             "%s 的 visionPolicy=%s 不在已知阶梯里；请同步 VISION_TIERS 与 PerceptionTier.kt。"
             % (prof["file"], vp))

    # 数值类型：JSON 里 immunity_padding_ms 等是 Long，端上 optLong 读；
    # 解析器已按字面量把 1000L -> 1000(int)，这里只确认可写成数字而不是字符串。
    def num(field, value):
        _require(isinstance(value, (int, float)) and not isinstance(value, bool),
                 "%s 的 rules.%s 不是数字（得到 %r），产物会被端上按默认值兜掉。"
                 % (prof["file"], field, value))
        return value

    rules = {}
    for camel, snake in RULE_KEYS:
        rules[snake] = num(camel, prof["rules"][camel])

    tactical = {}
    for camel, snake in TACTICAL_KEYS:
        if camel not in prof["tactical"]:
            continue  # 内置库没写该字段：交给端上的默认值，而不是我们编一个
        val = prof["tactical"][camel]
        _require(not isinstance(val, str),
                 "%s 的 tacticalDefaults.%s 是字符串 %r（列表/数字之外的值无法导出）。"
                 % (prof["file"], camel, val))
        tactical[snake] = val

    buttons = {}
    for key, b in prof["buttons"].items():
        _require(b["primary"], "%s 的按键 %s 主关键字为空，不能导出。" % (prof["file"], key))
        buttons[key] = {
            "primary_keyword": b["primary"],
            "aliases": list(b["aliases"]),
        }

    defender_db = {}
    for tier, json_key in HERO_TIERS:
        defender_db[json_key] = [
            {
                "name": name,
                "tag": tag,
                "threat_score": score,
                "description": desc,
                "counter_tip": tip,
            }
            for name, tag, score, desc, tip in prof["heroes"].get(tier, [])
        ]

    _require(prof["lands"], "%s 没有 land_suggestions，导出的产物会让打地指南变空。" % prof["file"])
    defender_db["land_suggestions"] = [
        {
            "land_level": l["level"],
            "recommended_soldiers": l["soldiers"],
            "safe_heroes": list(l["safe"]),
            "blacklist_heroes": list(l["black"]),
            "note": l["note"],
            # 0 = 该等级尚无可靠数据；端上按"未知"处理。这里必须原样导出，
            # 不能因为它是 0 就省掉这个键——省掉会让 fromJson 走 optInt 默认值，
            # 语义恰好也一样，但产物与 toJson() 不再同构，diff 会掩盖真实漂移。
            "defender_total_soldiers": l["garrison"],
        }
        for l in sorted(prof["lands"], key=lambda x: x["level"])
    ]

    # 词表三兄弟：内置库里没写就是没写（空对象也要原样导出）。
    # 不能"没写就省掉这个键"——端上 fromJson 读不到键时给的是空表，行为一样，
    # 但产物与 toJson() 不再同构，diff 就照不出真实漂移了（同 land 的 0 值口径）。
    scene_keywords = {g: list(w) for g, w in sorted(prof["scene_keywords"].items())}
    for g, w in prof["scene_keywords"].items():
        _require(all(x.strip() for x in w),
                 "%s 的 scene_keywords.%s 里有空白词，导出后端上会把它当有效词命中。" % (prof["file"], g))
    _require(all(isinstance(v, int) and v > 0 for v in prof["hero_speed"].values()),
             "%s 的 heroBaseSpeed 有非正数速度（%s），这类值会让先手判定算出假差距。"
             % (prof["file"], prof["hero_speed"]))

    return {
        "game_id": prof["game_id"],
        "game_name": prof["game_name"],
        "profile_version": prof["profile_version"],
        "target_package": prof["target_package"],
        "description": prof["description"],
        "rules": rules,
        "semantic_buttons": buttons,
        "defender_db": defender_db,
        "tactical_defaults": tactical,
        "watchdog_keywords": list(prof["watchdog"]),
        "scene_keywords": scene_keywords,
        "hero_base_speed": {k: v for k, v in sorted(prof["hero_speed"].items())},
        "pve_mechanic_notes": {k: v for k, v in sorted(prof["mechanic_notes"].items())},
        "vision_policy": VISION_TIERS[vp],
    }


def load_builtin(game_id):
    """按 gameId 找到内置库并解析（与闸门用同一个解析器）。"""
    for prof in kb.parse_builtin_profiles():
        if prof["game_id"] == game_id:
            return prof
    _die("内置知识库里找不到 gameId=%s（内置库文件名或 gameId 改过了？）" % game_id)


def dump(data):
    # 与 JSONObject.toString(2) 一致：两空格缩进、非 ASCII 原样写出、末尾带换行。
    return json.dumps(data, ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description="云端知识库产物导出器")
    parser.add_argument("--kb", help="只导出指定 gameId（默认全部）")
    parser.add_argument("--check", action="store_true",
                        help="只校验产物是否新鲜；落后于内置库时退出 1（CI 用，绝不写文件）")
    args = parser.parse_args()

    targets = [args.kb] if args.kb else list(PRODUCTS)
    stale, written = [], []

    for game_id in targets:
        rel = PRODUCTS.get(game_id)
        if not rel:
            _die("未登记 %s 的云端产物路径；请在 PRODUCTS 里补，或别再声称支持多游戏热更。" % game_id)
        path = os.path.join(REPO, rel)
        want = dump(build_json(load_builtin(game_id)))

        current = ""
        if os.path.exists(path):
            with io.open(path, encoding="utf-8") as fh:
                current = fh.read()

        if current == want:
            print("[OK]   %s 与内置库一致" % rel)
            continue
        if args.check:
            stale.append(rel)
            print("[STALE] %s 落后于内置库（请运行 python tools/export_profile.py）" % rel)
            continue

        d = os.path.dirname(path)
        if d and not os.path.isdir(d):
            os.makedirs(d)
        with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(want)
        written.append(rel)
        print("[WRITE] %s 已重新导出" % rel)

    if stale:
        print("\n❌ 以下云端产物与内置知识库不一致：%s" % ", ".join(stale))
        print("   产物是端上真正会采纳的那一份：不一致就意味着热更在覆盖内置库的修正。")
        sys.exit(1)
    if written:
        print("\n已导出 %d 份产物。发布前请先跑：python tools/check_knowledge_base.py"
              % len(written))
        print("发布命令：python tools/upload_profile.py --json pipeline/<产物文件>")


if __name__ == "__main__":
    main()
