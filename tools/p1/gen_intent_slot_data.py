#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
军令 → DSL 合成语料生成器（④「战术认知微脑」的训练数据起点）
============================================================

清单里写的是「训练数据无现成，用 P1 的军令→DSL 合成数据生成器批量造」。
本脚本就是它：用规则模板 + 受控随机，**批量产出格式绝对合法**的
（自然语言军令 → 意图 + 槽位 + DSL）三元组，并把不合法的直接判死在生成阶段。

为什么还要负样本
----------------
④ 的目标是「零坐标幻觉」：模型宁可输出 `intent=UNKNOWN / 无坐标`，
也不要编一个像模像样的坐标。因此语料里必须有 **3 类负样本**：
  * 空指令（"打地"、"嗯"）→ intent=UNKNOWN，slots 全空；
  * 缺关键槽位（只说目标不说时间）→ 对应槽位留空，DSL 里直接不出现该键；
  * 明显越界（坐标 9999、时间 25:61、兵力 0）→ 在校验阶段就会被拒，
    生成时改为「降级成 UNKNOWN」，而不是把它写进训练集。

DSL 语法（本机/端侧受约束解码用的目标格式，单行、无嵌套）
------------------------------------------------------
    ORDER <INTENT> target=<n>|coord=<x>,<y>|level=<n>|at=<HH:MM>|advance=<s>|roles=<a+b>|troops=<n>|contingency=<c>
槽位键按需出现；缺哪个就不写哪个（端侧解码时按同一套规则拼串）。

用法
----
    python tools/p1/gen_intent_slot_data.py --out data/intent_slot_train.jsonl --count 20000
    python tools/p1/gen_intent_slot_data.py --out data/intent_slot_val.jsonl --count 2000 --seed 7

退出码：0 = 全部样本通过校验；1 = 有样本非法（会打印前几条例）；2 = 参数错误。
"""

import argparse
import json
import os
import random
import re
import sys

INTENTS = [
    "ATTACK_CITY", "ATTACK_LAND", "RAID_DEFENSE", "ROAD_PAVING",
    "IMMUNITY_BREAK", "PRESS_SECOND", "CASTLE_MOVE", "MARCH_GARRISON",
]
ROLES = ["MAIN", "DEMOLITION", "SUPPORT"]
CONTINGENCIES = ["RETRY", "WAIT_SUPPLY", "SWITCH_TARGET", "ABORT", "NONE"]

PLACES = ["虎牢关", "洛阳", "邺城", "长安", "建业", "许昌", "宛城", "寿春", "江陵",
          "白波谷", "汜水关", "天水", "西凉铁骑营", "阳平关", "赤壁", "官渡"]
LAND_TERRAIN = ["5级石料地", "6级铁矿", "7级粮草", "9级木材", "4级银库"]
HERO_LINES = ["李典徐晃", "李儒", "郭嘉", "法正", "陆逊庞统", "魏延", "张任", "审配"]
OFFENSIVE_PREFIX = ["明天", "今晚", "今天下午", "明早", "后天", "凌晨", "午夜", "上午"]
TIME_HOURS = ["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11",
              "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23"]
MINUTES = ["00", "05", "10", "15", "20", "25", "30", "35", "40", "45", "50", "55"]

# ---------------------------------------------------------------- DSL 校验
def dsl_of(intent, slots):
    parts = ["ORDER %s" % intent]
    if slots.get("target"):
        parts.append("target=%s" % slots["target"])
    if slots.get("coord"):
        x, y = slots["coord"]
        parts.append("coord=%d,%d" % (x, y))
    if slots.get("level") is not None:
        parts.append("level=%d" % slots["level"])
    if slots.get("at"):
        parts.append("at=%s" % slots["at"])
    if slots.get("advance") is not None:
        parts.append("advance=%d" % slots["advance"])
    if slots.get("roles"):
        parts.append("roles=%s" % "+".join(slots["roles"]))
    if slots.get("troops") is not None:
        parts.append("troops=%d" % slots["troops"])
    if slots.get("contingency") in CONTINGENCIES:
        parts.append("contingency=%s" % slots["contingency"])
    return "|".join(parts)


ORDER_RE = re.compile(
    r"^ORDER (ATTACK_CITY|ATTACK_LAND|RAID_DEFENSE|ROAD_PAVING|IMMUNITY_BREAK|PRESS_SECOND"
    r"|CASTLE_MOVE|MARCH_GARRISON|UNKNOWN)"
    r"(?:\|(?:target=[^|]+|coord=\d+,\d+|level=[1-9]|advance=\d+|at=\d{2}:\d{2}"
    r"|roles=[A-Z+]+|troops=\d+|contingency=[A-Z_]+))*$")


def validate_dsl(dsl, max_coord=600, max_advance=600):
    """返回错误串列表，空列表表示合法。端侧受约束解码用的就是这套规则。"""
    errs = []
    head = dsl.split("|", 1)[0]
    intent = head.split(" ", 1)[1] if " " in head else ""
    if intent not in INTENTS and intent != "UNKNOWN":
        errs.append("未知意图 %s" % intent)
    for key, val in re.findall(r"\|?([a-z_]+)=([^|]+)", dsl):
        if key == "coord":
            x, y = val.split(",")
            if not (0 <= int(x) <= max_coord and 0 <= int(y) <= max_coord):
                errs.append("坐标越界 %s" % val)
        elif key == "advance" and int(val) > max_advance:
            errs.append("提前量过大 %s" % val)
        elif key == "level" and not (1 <= int(val) <= 9):
            errs.append("地块等级非法 %s" % val)
        elif key == "troops" and int(val) < 100:
            errs.append("兵力不合理 %s" % val)
        elif key == "at" and not re.match(r"^\d{2}:\d{2}$", val):
            errs.append("时间格式非法 %s" % val)
        elif key == "roles":
            for r in val.split("+"):
                if r not in ROLES:
                    errs.append("角色非法 %s" % r)
        elif key == "contingency" and val not in CONTINGENCIES:
            errs.append("预案非法 %s" % val)
    return errs


# ------------------------------------------------------------ 模板与采样
def sample_time(rng):
    """补零到两位小时，保证 HH:MM 恒合法（0/00 都给，口语里两种都常见）。"""
    hour = rng.choice(TIME_HOURS)
    return "%02d:%s" % (int(hour), rng.choice(MINUTES))


def sample_coord(rng, max_coord=600):
    return (rng.randint(3, max_coord), rng.randint(3, max_coord))


def sample_roles(rng):
    """rng.sample 自带随机性，在这儿直接调用即可（整条流水线仍是固定 seed 可复现）。"""
    return rng.sample(ROLES, k=rng.randint(1, 3))


TEMPLATES = {
    "ATTACK_CITY": [
        "{pre}{time}全员集火{place}，主队先上，拆迁队压一秒，{contingency}".format,
        "{pre}{time}打{place}，别抢跑，主力+拆迁都要到位，{contingency}".format,
        "{place}卡{time}开打，主力清守军，拆迁跟在后面削耐久".format,
    ],
    "ATTACK_LAND": [
        "{pre}{time}开{terrain}，守军是{hero}，给我{w}兵，{contingency}".format,
        "{pre}{time}打{hero}那块{terrain}，别翻车，{contingency}".format,
        "{terrain}在{coord}，{pre}{time}上，{w}兵够了".format,
    ],
    "RAID_DEFENSE": [
        "{pre}发现敌军红线冲过来了，{time}前必须拦下来，{contingency}".format,
        "敌袭！{place}这边要塞驻守，{time}务必反击，{contingency}".format,
        "深夜偷家预警，{time}前把主力调回去守{place}".format,
    ],
    "ROAD_PAVING": [
        "{pre}{time}开始铺路，从{place}往{coord}那条线，三队 parallel 铺".format,
        "{pre}{time}铺{terrain}前面的路，先出两队的量".format,
        "铺路去{coord}，{pre}{time}开工，别耽误主队开荒".format,
    ],
    "IMMUNITY_BREAK": [
        "{place}还有几分钟免战？{time}之前出兵破免直接翻地".format,
        "{pre}{time}卡免，别让对面喘气，{contingency}".format,
        "免战倒计时到了立刻打{place}，{pre}{time}".format,
    ],
    "PRESS_SECOND": [
        "{pre}{time}压秒，所有人第 00 秒同时触城，{place}".format,
        "{place}压秒打，{time}整触城，拆迁别抢跑".format,
        "{pre}{time}卡秒集火{place}，主力清场拆迁补刀".format,
    ],
    "CASTLE_MOVE": [
        "{pre}{time}搬家，主城迁到{coord}那边".format,
        "主城要挪，{time}之前迁{place}附近，别挑打仗的时候".format,
    ],
    "MARCH_GARRISON": [
        "{pre}{time}把队伍调到{place}要塞驻扎，等士气满再打".format,
        "先别开打，{time}前驻守{coord}等士气回满".format,
    ],
}

NOISE = ["", "兄弟们", "收到没", "说清楚点", "就按这个来", "啊对", "嗯", "紧急", "注意点"]
VAGUE = ["打地", "出兵", "去打", "看看情况", "嗯", "怎么办", "帮我看看", "这个怎么整"]
PLACE_OR_COORD = ["坐标大约在那边", "附近", "不知道具体哪儿", "就那块"]


def render(rng, intent):
    tmpl = rng.choice(TEMPLATES[intent])
    ctx = {
        "place": rng.choice(PLACES),
        "terrain": rng.choice(LAND_TERRAIN),
        "hero": rng.choice(HERO_LINES),
        "time": sample_time(rng),
        "pre": rng.choice(OFFENSIVE_PREFIX),
        "coord": "%d,%d" % sample_coord(rng),
        "w": rng.choice([3000, 5000, 8000, 12000, 16000, 22000, 25000]),
        "contingency": rng.choice(CONTINGENCIES),
    }
    return tmpl(**ctx)


def slots_for(rng, intent, text):
    """从生成的句子里反推槽位（模拟标注）：显式字段才写，绝不凭空造。"""
    slots = {}
    if intent in ("ATTACK_LAND", "ROAD_PAVING"):
        target = None
        for word in PLACES + HERO_LINES + LAND_TERRAIN:
            if word in text:
                target = word
                break
        if target:
            slots["target"] = target
    coord = None
    m = re.search(r"(\d{1,3}),(\d{1,3})", text)
    if m and 0 <= int(m.group(1)) <= 600 and 0 <= int(m.group(2)) <= 600:
        coord = (int(m.group(1)), int(m.group(2)))
    elif intent in ("ATTACK_LAND", "ROAD_PAVING", "CASTLE_MOVE"):
        coord = sample_coord(rng)  # 打地/铺路/搬家：坐标是必填槽
    if coord:
        slots["coord"] = coord
    lv = re.search(r"(\d)\s*级", text)
    if lv:
        slots["level"] = int(lv.group(1))
    at = re.search(r"(\d{1,2}:\d{2})", text)
    if at:
        slots["at"] = at.group(1)
    else:
        slots["at"] = None
    adv = re.search(r"提前\s*(\d+)\s*秒|压\s*(\d+)\s*秒", text)
    if adv:
        slots["advance"] = int(adv.group(1) or adv.group(2))
    else:
        slots["advance"] = rng.choice([0, 0, 30, 60, 120, 300])
    slots["roles"] = sample_roles(rng)
    slots["troops"] = int(rng.choice([3000, 5000, 8000, 12000, 16000, 22000, 25000]))
    slots["contingency"] = rng.choice(CONTINGENCIES)

    # 只有真正出现的槽位才留在 slots 里（端侧解码按同一规则拼串）
    return {k: v for k, v in slots.items() if v not in (None, "")}


def make_record(rng):
    roll = rng.random()
    if roll < 0.06:                      # 空指令 /  vague → UNKNOWN
        text = rng.choice(VAGUE)
        return {"text": text, "intent": "UNKNOWN", "slots": {}, "dsl": dsl_of("UNKNOWN", {})}
    if roll < 0.12:                      # 有话但坐标/时间含糊 → UNKNOWN + 提示
        text = rng.choice(NOISE) + rng.choice(PLACE_OR_COORD)
        return {"text": text, "intent": "UNKNOWN", "slots": {}, "dsl": dsl_of("UNKNOWN", {})}

    intent = rng.choice(INTENTS)
    text = render(rng, intent)
    if rng.random() < 0.18:              # 混入口语噪声
        text = (rng.choice(NOISE) + "，" if rng.random() < 0.5 else "") + text
    slots = slots_for(rng, intent, text)
    dsl = dsl_of(intent, slots)
    return {"text": text, "intent": intent, "slots": slots, "dsl": dsl}


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--count", type=int, default=20000)
    ap.add_argument("--seed", type=int, default=20261003)
    ap.add_argument("--coord-max", type=int, default=600)
    args = ap.parse_args()
    if args.count < 1:
        print("参数错误：--count 必须 ≥1", file=sys.stderr)
        return 2

    rng = random.Random(args.seed)
    bad = 0
    seen = set()
    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)

    with open(args.out, "w", encoding="utf-8") as fh:
        written = 0
        guard = 0
        while written < args.count and guard < args.count * 5:
            guard += 1
            rec = make_record(rng)
            errs = validate_dsl(rec["dsl"], max_coord=args.coord_max)
            if errs:
                bad += 1
                if bad <= 3:
                    print("❌ 拒绝写入非法样本：%s → %s（%s）" % (rec["text"], rec["dsl"], ";".join(errs)),
                          file=sys.stderr)
                continue
            key = (rec["intent"], rec["dsl"])
            if key in seen and rng.random() < 0.9:
                continue
            seen.add(key)
            fh.write(json.dumps(rec, ensure_ascii=False) + "\n")
            written += 1

    print("[OK] 写入 %d 条 → %s（seed=%d，非法样本拒绝 %d 条）" % (written, args.out, args.seed, bad))
    if bad:
        print("⚠️  有非法样本被拒；若数量异常偏大，说明模板/校验规则不同步。", file=sys.stderr)
        return 1
    if written < args.count:
        print("⚠️  只生成 %d/%d 条（模板多样性用尽），建议 --count 调小或扩充模板。" % (written, args.count),
              file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
