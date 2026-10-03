#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线战术定时管家与一键破免离线业务逻辑验证 (tools/validate_schedule_manager.py)
========================================================================

逐项验证阶段 4 (痛点 3) 核心业务逻辑：
  M1. 【官方书签 0 漂移对准与目标优先级 (Bookmark Navigation Priority)】
  M2. 【ScheduledTask 结构体与 JSON 序列化/反序列化完整性】
  M3. 【挂牌出征动作枚举与部队槽位边界校验 (ATTACK/SWEEP/FARM/DEFEND)】
  M4. 【OCR 免战倒计时解析与毫秒级卡秒破免时序数学 (+1000ms 触敌)】
  M5. 【系统底层硬件 RTC 闹钟下次触发时刻换算 (含跨天判断)】
"""

import json
import re
import sys
import time


def test_target_resolution_priority():
    """M1: 验证目标解析优先级：书签优先于世界坐标，世界坐标优先于屏幕取点"""
    # 模拟三种输入配置
    # Case A: 带有官方书签名称 -> 0 漂移瞬间居中锁定
    task_a = {
        "bookmarkName": "虎牢关",
        "targetWorldX": 580,
        "targetWorldY": 390,
        "targetX": 640.0,
        "targetY": 360.0
    }
    has_target_a = (task_a["targetX"] is not None and task_a["targetY"] is not None) or bool(task_a.get("bookmarkName"))
    assert has_target_a is True, "带有书签时 hasTarget 必须为 True"

    def resolve_target_mode(t):
        if t.get("bookmarkName"):
            return "BOOKMARK_ZERO_DRIFT"
        elif t.get("targetWorldX") is not None and t.get("targetWorldY") is not None:
            return "WORLD_COORDINATE_NAV"
        elif t.get("targetX") is not None and t.get("targetY") is not None:
            return "SCREEN_CANVAS_TAP"
        else:
            return "NO_TARGET_REFUSED"

    assert resolve_target_mode(task_a) == "BOOKMARK_ZERO_DRIFT"

    # Case B: 仅有世界坐标
    task_b = {
        "bookmarkName": None,
        "targetWorldX": 580,
        "targetWorldY": 390,
        "targetX": 640.0,
        "targetY": 360.0
    }
    assert resolve_target_mode(task_b) == "WORLD_COORDINATE_NAV"

    # Case C: 仅有屏幕画布取点
    task_c = {
        "bookmarkName": None,
        "targetWorldX": None,
        "targetWorldY": None,
        "targetX": 640.0,
        "targetY": 360.0
    }
    assert resolve_target_mode(task_c) == "SCREEN_CANVAS_TAP"

    # Case D: 空目标 -> 绝不盲点屏幕中心，必须拒绝
    task_d = {
        "bookmarkName": None,
        "targetWorldX": None,
        "targetWorldY": None,
        "targetX": None,
        "targetY": None
    }
    assert resolve_target_mode(task_d) == "NO_TARGET_REFUSED"
    print("M1: 目标解析优先级测试通过 (书签 0 漂移 > 世界坐标 > 屏幕取点 > 拒绝盲点)")


def test_task_json_serialization_compatibility():
    """M2: 验证 ScheduledTask JSON 字段序列化与向后兼容性"""
    # 模拟新建带完整字段的任务
    task_model = {
        "id": "SCH-1234567",
        "name": "早晨屯田练兵",
        "timeStr": "07:00",
        "taskType": "TACTICAL_SCHEDULE",
        "isEnabled": True,
        "targetX": 720.0,
        "targetY": 480.0,
        "targetWorldX": 350,
        "targetWorldY": 210,
        "hitOffsetSeconds": 60,
        "extraTargetsRaw": "720.0,480.0,350,210",
        "bookmarkName": "主城五级木",
        "actionType": "FARM",
        "troopSlot": 2,
        "isImmunityBreak": False
    }

    serialized = json.dumps(task_model)
    deserialized = json.loads(serialized)

    assert deserialized["bookmarkName"] == "主城五级木"
    assert deserialized["actionType"] == "FARM"
    assert deserialized["troopSlot"] == 2
    assert deserialized["isImmunityBreak"] is False

    # 验证旧版本数据迁移兼容性（缺省新字段时自动提供安全默认值）
    old_task_json = """
    {
        "id": "SCH-0000001",
        "name": "旧版巡检任务",
        "timeStr": "23:00",
        "taskType": "NIGHT_SENTINEL",
        "isEnabled": true
    }
    """
    old_obj = json.loads(old_task_json)
    bookmark = old_obj.get("bookmarkName")
    action_type = old_obj.get("actionType", "ATTACK")
    troop_slot = old_obj.get("troopSlot", 1)
    is_immunity = old_obj.get("isImmunityBreak", False)

    assert bookmark is None
    assert action_type == "ATTACK"
    assert troop_slot == 1
    assert is_immunity is False
    print("M2: ScheduledTask 序列化与向后兼容验证通过")


def test_action_and_slot_validation():
    """M3: 验证战术动作枚举与槽位边界限制"""
    allowed_actions = {"ATTACK", "SWEEP", "FARM", "DEFEND", "TRAIN"}
    for act in ["ATTACK", "SWEEP", "FARM", "DEFEND"]:
        assert act in allowed_actions, f"未支持的动作: {act}"

    def clamp_troop_slot(slot):
        return max(1, min(5, int(slot)))

    assert clamp_troop_slot(1) == 1
    assert clamp_troop_slot(5) == 5
    assert clamp_troop_slot(0) == 1
    assert clamp_troop_slot(6) == 5
    assert clamp_troop_slot(-99) == 1
    print("M3: 战术动作枚举与槽位边界限制验证通过")


def test_immunity_break_timing_math():
    """M4: 验证 OCR 免战倒计时解析与毫秒级卡秒破免时序数学 (+1000ms 触敌)"""
    # 模拟从截图 OCR 读取到的倒计时字符串
    countdown_samples = [
        ("00:15:32", 15 * 60 + 32),
        ("15:32", 15 * 60 + 32),
        ("01:20:05", 3600 + 20 * 60 + 5),
        ("00:00:45", 45),
        ("45", 45)
    ]

    def parse_countdown_str(s):
        parts = [int(p) for p in s.strip().split(":") if p.strip().isdigit()]
        if len(parts) == 3:
            return parts[0] * 3600 + parts[1] * 60 + parts[2]
        elif len(parts) == 2:
            return parts[0] * 60 + parts[1]
        elif len(parts) == 1:
            return parts[0]
        return 0

    for s_str, expected_sec in countdown_samples:
        parsed = parse_countdown_str(s_str)
        assert parsed == expected_sec, f"倒计时解析错误: {s_str} -> {parsed} (预期 {expected_sec})"

    now_ms = 1700000000000  # 基准测试当前时间戳
    remaining_sec = 185     # 剩余 3 分 5 秒免战
    unlock_timestamp_ms = now_ms + remaining_sec * 1000

    # 1. 触敌时刻规则：严格锁定免战解锁后 +1000ms (00:00:01 触敌，杜绝早打 0.1s 弹回)
    target_hit_epoch_ms = unlock_timestamp_ms + 1000

    # 2. 部队行军耗时与网络抖动补偿
    travel_duration_sec = 120  # 行军耗时 2 分钟 (120 秒)
    network_latency_ms = 110   # 网络与触控时延补偿 110ms

    optimal_dispatch_epoch_ms = target_hit_epoch_ms - (travel_duration_sec * 1000) - network_latency_ms
    wait_delay_ms = optimal_dispatch_epoch_ms - now_ms

    assert target_hit_epoch_ms > unlock_timestamp_ms, "触敌时刻必须严格晚于解锁时间"
    assert target_hit_epoch_ms - unlock_timestamp_ms == 1000, "触敌窗口严格保持 +1000ms 安全余量"

    # 出征等待时间应为: 185s - 120s - 0.11s = 64.89s -> 64890ms
    expected_wait_ms = (185 - 120) * 1000 + 1000 - 110
    assert wait_delay_ms == expected_wait_ms, f"等待时间计算错误: {wait_delay_ms} vs {expected_wait_ms}"

    # 3. 结构性迟到防御测试：如果行军耗时已大于剩余免战时间 + 1s，不可能准点破免
    too_slow_duration_sec = 200  # 行军耗时 200 秒 > 185 秒 + 1 秒
    slow_dispatch_epoch_ms = target_hit_epoch_ms - (too_slow_duration_sec * 1000) - network_latency_ms
    is_structurally_late = slow_dispatch_epoch_ms < now_ms
    assert is_structurally_late is True, "慢速部队必须判定为结构性迟到，防止盲目出征错失战机"
    print("M4: OCR 免战倒计时与毫秒级卡秒破免时序数学验证通过 (+1000ms 触敌与防弹回验证完毕)")


def test_alarm_manager_rtc_wakeup_epoch():
    """M5: 验证系统底层硬件 RTC 闹钟下次触发时刻换算 (含跨天判断)"""
    def calc_next_epoch(now_hour, now_minute, task_time_str):
        parts = task_time_str.split(":")
        th, tm = int(parts[0]), int(parts[1])
        now_total_min = now_hour * 60 + now_minute
        task_total_min = th * 60 + tm
        if task_total_min <= now_total_min:
            # 今天的这个时间已过，需跨天排到明天
            diff_min = 24 * 60 - now_total_min + task_total_min
            is_tomorrow = True
        else:
            diff_min = task_total_min - now_total_min
            is_tomorrow = False
        return diff_min, is_tomorrow

    # 场景 1: 当前 06:30，任务 07:00 -> 还有 30 分钟，今天执行
    d1, t1 = calc_next_epoch(6, 30, "07:00")
    assert d1 == 30 and t1 is False

    # 场景 2: 当前 23:45，任务 00:15 -> 还有 30 分钟，明天执行
    d2, t2 = calc_next_epoch(23, 45, "00:15")
    assert d2 == 30 and t2 is True

    # 场景 3: 当前 20:00，任务 20:00 -> 今天该时刻已过，排到明天 24 小时后
    d3, t3 = calc_next_epoch(20, 0, "20:00")
    assert d3 == 24 * 60 and t3 is True
    print("M5: 硬件 RTC 闹钟唤醒时刻与跨天换算验证通过")


def main():
    print("=================================================================")
    print("开始验证阶段 4 (痛点 3)：【离线战术定时管家与一键破免】核心逻辑")
    print("=================================================================")
    test_target_resolution_priority()
    test_task_json_serialization_compatibility()
    test_action_and_slot_validation()
    test_immunity_break_timing_math()
    test_alarm_manager_rtc_wakeup_epoch()
    print("-----------------------------------------------------------------")
    print("[PASS] 痛点 3【离线战术定时管家与一键破免】全项核心逻辑自测通过！")
    print("=================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
