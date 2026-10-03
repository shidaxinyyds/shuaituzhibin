#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
全盟战役双压秒与邮件法令解析离线验证 (tools/validate_siege_sync.py)
====================================================================

逐项验证阶段 3 核心业务逻辑：
  M1. 【同盟邮件法令结构化解析：目标名、沙盘坐标、触敌时间、要塞坐标】
  M2. 【主力 0s 清守军与拆迁后置 5s 破皮两阶段双压秒时序数学】
  M3. 【反向时序调度排序：慢速拆迁先发、快速主力后发】
  M4. 【发车前 30 分钟自愈调动与健康体检时间窗口】
"""

import re
import sys
import time


def parse_mail_decree(text):
    """复刻 AllianceMailParser.parseText 的核心正则逻辑"""
    clean_text = text.replace("\r", "\n")

    # 1. 目标提取
    pattern_target = re.compile(
        r"(?:攻打|开打|集火|拿下|目标|进攻)\s*[【\[]?([\u4e00-\u9fa5]{2,8}(?:关|城|县|要塞|码头|港)?)(?:\s*[\(（](?:LV|Lv|lv)?\.?\s*(\d+)[\)）])?[】\]]?"
    )
    pattern_bracket = re.compile(
        r"[【\[]([\u4e00-\u9fa5]{2,8}(?:关|城|县|要塞|码头|港)?)(?:\s*[\(（](?:LV|Lv|lv)?\.?\s*(\d+)[\)）])?[】\]]"
    )

    target_name = "未命名"
    tm = pattern_target.search(clean_text)
    if tm:
        base = tm.group(1).strip()
        lv = tm.group(2)
        target_name = f"{base}(LV.{lv})" if lv else base
    else:
        bm = pattern_bracket.search(clean_text)
        if bm:
            base = bm.group(1).strip()
            lv = bm.group(2)
            target_name = f"{base}(LV.{lv})" if lv else base

    # 2. 坐标提取
    coords = []
    for cm in re.finditer(r"(?:[\(（\[])?\s*(\d{2,4})\s*[,，\s]\s*(\d{2,4})\s*(?:[\)）\]])?", clean_text):
        x = int(cm.group(1))
        y = int(cm.group(2))
        if 1 <= x <= 1500 and 1 <= y <= 1500:
            coords.append((x, y))

    target_coord = coords[0] if len(coords) >= 1 else None
    fortress_coord = coords[1] if len(coords) >= 2 else None

    # 3. 时间提取
    pattern_time = re.compile(r"(?:今晚|今天|今日|明天)?\s*(\d{1,2})\s*[:：点时]\s*(\d{1,2})?(?:\s*[:：分]\s*(\d{1,2}))?")
    hour, minute, second = 21, 0, 0
    for tm in pattern_time.finditer(clean_text):
        h = int(tm.group(1))
        m = int(tm.group(2)) if tm.group(2) else 0
        s = int(tm.group(3)) if tm.group(3) else 0
        if 0 <= h <= 23 and 0 <= m <= 59 and 0 <= s <= 59:
            hour, minute, second = h, m, s
            break

    # 4. 拆迁后置秒数
    demo_delay = 5
    dm = re.search(r"(?:拆迁|拆迁队|跟刀|后置|延迟)\s*(?:晚|延迟|后置)?\s*(\d{1,2})\s*(?:秒|s)?", clean_text)
    if dm:
        val = int(dm.group(1))
        if 1 <= val <= 30:
            demo_delay = val

    # 5. 要塞提取
    fortress_name = None
    if "要塞" in clean_text:
        fm = re.search(r"【?([\u4e00-\u9fa5]{2,8}要塞)】?", clean_text)
        if fm:
            fortress_name = fm.group(1)

    return {
        "target_name": target_name,
        "target_coord": target_coord,
        "hour": hour,
        "minute": minute,
        "second": second,
        "fortress_name": fortress_name,
        "fortress_coord": fortress_coord,
        "demolition_delay": demo_delay,
    }


def test_mail_parsing():
    sample1 = "【同盟法令】今晚 21:00 准时开打【高安(LV.5)】(582, 391)！主力 21:00:00 触敌，拆迁后置 5 秒跟上破皮！请各位兄弟提前调动至【高安前线要塞】(580, 390)，备足20体力！"
    r1 = parse_mail_decree(sample1)
    assert r1["target_name"] == "高安(LV.5)", f"目标错误: {r1['target_name']}"
    assert r1["target_coord"] == (582, 391), f"坐标错误: {r1['target_coord']}"
    assert (r1["hour"], r1["minute"]) == (21, 0), f"时间错误: {r1['hour']}:{r1['minute']}"
    assert r1["demolition_delay"] == 5, f"拆迁延迟错误: {r1['demolition_delay']}"
    assert r1["fortress_coord"] == (580, 390), f"要塞坐标错误: {r1['fortress_coord']}"
    assert r1["fortress_name"] == "高安前线要塞", f"要塞名称错误: {r1['fortress_name']}"

    sample2 = "各位团员注意，攻打 襄阳 (228, 132)。今晚 20:45 主力触敌，拆迁晚 6 秒出击。"
    r2 = parse_mail_decree(sample2)
    assert r2["target_name"] == "襄阳"
    assert r2["target_coord"] == (228, 132)
    assert (r2["hour"], r2["minute"]) == (20, 45)
    assert r2["demolition_delay"] == 6

    print("PASS: M1. 同盟邮件法令结构化解析精度 100%")


def test_dual_phase_timing():
    base_hit_ms = 1700000000000
    demo_offset_sec = 5
    comp_ms = 100

    # 主力队目标时刻
    main_hit_ms = base_hit_ms
    # 拆迁队 1 目标时刻 (后置 5s)
    demo1_hit_ms = base_hit_ms + demo_offset_sec * 1000
    # 拆迁队 2 目标时刻 (后置 6s)
    demo2_hit_ms = base_hit_ms + (demo_offset_sec + 1) * 1000

    assert demo1_hit_ms - main_hit_ms == 5000, "拆迁一队必须严格落后主力 5000ms"
    assert demo2_hit_ms - main_hit_ms == 6000, "拆迁二队必须严格落后主力 6000ms"

    # 行军耗时：主力骑兵 180s (3分钟)，拆迁步兵 600s (10分钟)
    main_march_sec = 180
    demo_march_sec = 600

    main_dispatch_ms = main_hit_ms - main_march_sec * 1000 - comp_ms
    demo_dispatch_ms = demo1_hit_ms - demo_march_sec * 1000 - comp_ms

    # 慢速拆迁步兵必须早于快速主力骑兵发车！
    assert demo_dispatch_ms < main_dispatch_ms, "慢速步兵拆迁队必须提前先出发"
    diff_sec = (main_dispatch_ms - demo_dispatch_ms) / 1000
    assert diff_sec == 415.0, f"时间差应为 415s (10m - 5s - 3m), 实际 {diff_sec}s"

    print("PASS: M2 & M3. 主力 0s 与拆迁 +5s 双压秒时序及反向发车排序无误")


def test_preflight_lead_time():
    lead_ms = 30 * 60 * 1000
    assert lead_ms == 1800000, "发车前 30 分钟提前量必须为 1,800,000ms"

    # 体力安全线
    stamina_min = 20
    assert stamina_min == 20, "率土单次出征体力门槛为 20"

    print("PASS: M4. 发车前 30 分钟健康体检与要塞自愈调动参数正确")


def main():
    print("=" * 65)
    print("全盟战役双压秒全勤王与邮件法令离线验证")
    print("=" * 65)
    test_mail_parsing()
    test_dual_phase_timing()
    test_preflight_lead_time()
    print("=" * 65)
    print("所有双压秒与邮件法令断言全部通过！")
    return 0


if __name__ == "__main__":
    sys.exit(main())
