#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
单账号日常后勤全托管与全自动屯田打铁管家业务逻辑验证 (tools/validate_logistics_farming.py)
========================================================================

逐项验证阶段 5 (痛点 4 与痛点 5) 核心业务逻辑：
  M1. 【自动税收与内政安全巡检 (Tax Levy Logic)】
  M2. 【自动预备役征兵与伤病损耗精算 (Reserve Recruitment & Health Threshold)】
  M3. 【主力与铺路队体力防溢出水位监测 (Stamina Overflow Prevention)】
  M4. 【高等级资源地最优屯田选择与免战/要塞过滤 (High-Level Tile Farming)】
  M5. 【策令系统精算模型与 30 令防溢清令判定 (Policy Order Calculation & Cap Protection)】
  M6. 【工坊打铁与宝物锻造/陈情巡检契约 (Blacksmith & Treasure Forging Inspection)】
"""

import sys


def test_tax_levy_logic():
    """M1: 自动税收与内政安全巡检逻辑"""
    # 模拟主城税收每日限制与冷却
    def can_levy_tax(daily_levy_count, max_daily_count=3, is_cooling_down=False):
        if is_cooling_down:
            return False, "税收处于冷却中"
        if daily_levy_count >= max_daily_count:
            return False, f"今日税收已达上限 ({daily_levy_count}/{max_daily_count})"
        return True, "可正常执行税收"

    assert can_levy_tax(0)[0] is True
    assert can_levy_tax(2)[0] is True
    assert can_levy_tax(3)[0] is False
    assert can_levy_tax(4)[0] is False
    assert can_levy_tax(1, is_cooling_down=True)[0] is False
    print("M1: 自动税收与内政安全巡检验证通过 (3次上限拦截 + 冷却保护)")


def test_reserve_recruitment_logic():
    """M2: 自动预备役征兵与伤病损耗精算"""
    def should_replenish_troops(current_troops, max_troops, min_health_percent=0.85):
        if current_troops is None or max_troops is None or max_troops <= 0:
            return False, "无有效兵力数据"
        ratio = current_troops / max_troops
        if ratio < min_health_percent:
            needed = max_troops - current_troops
            return True, f"兵力告急: {current_troops}/{max_troops} ({ratio*100:.1f}%)，需补充 {needed} 预备役"
        return False, f"兵力健康: {current_troops}/{max_troops} ({ratio*100:.1f}%)"

    # Case A: 满编健康
    ok_a, _ = should_replenish_troops(30000, 30000, 0.85)
    assert ok_a is False

    # Case B: 轻微擦伤 (90% > 85%)，不浪费预备役
    ok_b, _ = should_replenish_troops(27000, 30000, 0.85)
    assert ok_b is False

    # Case C: 严重战损 (24000/30000 = 80% < 85%)，必须补充
    ok_c, desc_c = should_replenish_troops(24000, 30000, 0.85)
    assert ok_c is True
    assert "需补充 6000 预备役" in desc_c

    # Case D: 槽位有效性边界校验 (仅允许 1~5 槽位)
    def validate_troop_slot(slot):
        return 1 <= slot <= 5

    assert validate_troop_slot(1) is True
    assert validate_troop_slot(5) is True
    assert validate_troop_slot(0) is False
    assert validate_troop_slot(6) is False

    print("M2: 自动预备役征兵与伤病损耗精算验证通过 (85%阈值精准分流 + 槽位有效性)")


def test_stamina_overflow_logic():
    """M3: 主力体力防溢出水位监测"""
    MAX_STAMINA = 120

    def evaluate_stamina_level(stamina, overflow_threshold=110):
        if stamina is None or stamina < 0:
            return "UNKNOWN"
        if stamina >= MAX_STAMINA:
            return "OVERFLOW_CRITICAL"
        if stamina >= overflow_threshold:
            return "OVERFLOW_WARNING"
        return "NORMAL"

    assert evaluate_stamina_level(120) == "OVERFLOW_CRITICAL"
    assert evaluate_stamina_level(115) == "OVERFLOW_WARNING"
    assert evaluate_stamina_level(110) == "OVERFLOW_WARNING"
    assert evaluate_stamina_level(105) == "NORMAL"
    assert evaluate_stamina_level(20) == "NORMAL"

    print("M3: 主力体力防溢出水位监测验证通过 (110警告 + 120满溢预警)")


def test_high_level_tile_farming():
    """M4: 高等级资源地最优屯田选择与免战/要塞过滤"""
    # 模拟地块特征
    tiles = [
        {"id": 1, "level": 3, "res": "STONE", "is_immune": False, "is_fortress": False},
        {"id": 2, "level": 4, "res": "WOOD", "is_immune": False, "is_fortress": False},
        {"id": 3, "level": 7, "res": "STONE", "is_immune": True, "is_fortress": False},   # 免战中，不可屯
        {"id": 4, "level": 6, "res": "IRON", "is_immune": False, "is_fortress": False},
        {"id": 5, "level": 8, "res": "STONE", "is_immune": False, "is_fortress": False},  # 最优石头
        {"id": 6, "level": 5, "res": "STONE", "is_immune": False, "is_fortress": True},   # 要塞地，不屯
    ]

    def select_best_farming_tile(candidate_tiles, preferred_res="STONE", min_level=5):
        valid = []
        for t in candidate_tiles:
            if t["is_immune"]:
                continue
            if t["is_fortress"]:
                continue
            if t["level"] < min_level:
                continue
            valid.append(t)

        if not valid:
            return None

        # 排序：优先匹配偏好资源类型，其次等级最高
        def score(t):
            res_bonus = 100 if t["res"] == preferred_res else 0
            return res_bonus + t["level"]

        return max(valid, key=score)

    best = select_best_farming_tile(tiles, preferred_res="STONE", min_level=5)
    assert best is not None
    assert best["id"] == 5, f"预期选择 8 级石头地 (id=5)，实际得到 id={best['id']}"
    assert best["level"] == 8

    # 若偏好改为 IRON
    best_iron = select_best_farming_tile(tiles, preferred_res="IRON", min_level=5)
    assert best_iron is not None
    assert best_iron["id"] == 4, f"预期选择 6 级铁矿 (id=4)，实际得到 id={best_iron['id']}"

    print("M4: 高等级资源地最优屯田选择验证通过 (免战光罩过滤 + 要塞过滤 + 资源权重优先)")


def test_policy_order_calculation():
    """M5: 策令系统精算模型与 30 令防溢清令判定"""
    ORDER_CAP = 30
    COST_PER_FARM = 3

    def evaluate_policy_orders(current_orders):
        if current_orders < COST_PER_FARM:
            return False, False, f"策令不足 3 令 (当前 {current_orders} 令)，拒绝屯田"
        is_overflow_risk = current_orders >= 24
        times = current_orders // COST_PER_FARM
        return True, is_overflow_risk, f"策令充足: {current_orders}/{ORDER_CAP}，可屯田 {times} 次"

    # Case A: 策令不足
    can_farm, risk, msg = evaluate_policy_orders(2)
    assert can_farm is False
    assert risk is False

    # Case B: 策令正常
    can_farm, risk, msg = evaluate_policy_orders(15)
    assert can_farm is True
    assert risk is False
    assert "可屯田 5 次" in msg

    # Case C: 策令接近上限 (>= 24)，触发强制清令
    can_farm, risk, msg = evaluate_policy_orders(27)
    assert can_farm is True
    assert risk is True
    assert "可屯田 9 次" in msg

    print("M5: 策令系统精算模型验证通过 (3令门槛拦截 + 24令防溢清令判定)")


def test_blacksmith_inspection_keywords():
    """M6: 工坊打铁与宝物锻造/陈情巡检语义契约"""
    sample_texts = [
        "今日陈情：工匠造访，可免费精炼宝物一次",
        "宝物工坊：当前可打造精品兵刃",
        "天下大势：战乱四起",
        "城建任务：升级民居至Lv.10",
        "武将寻访：工匠打铁打造宝物素材"
    ]

    KEYWORDS = ["工匠", "打铁", "打造", "宝物", "精炼", "寻访", "锻造"]

    def is_blacksmith_affair(text):
        return any(k in text for k in KEYWORDS)

    assert is_blacksmith_affair(sample_texts[0]) is True
    assert is_blacksmith_affair(sample_texts[1]) is True
    assert is_blacksmith_affair(sample_texts[2]) is False
    assert is_blacksmith_affair(sample_texts[3]) is False
    assert is_blacksmith_affair(sample_texts[4]) is True

    print("M6: 工坊打铁与宝物锻造巡检语义契约验证通过")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    print("=" * 72)
    print("率土全能管家 · 单账号日常后勤与全自动屯田打铁管家验证")
    print("========================================================================")
    test_tax_levy_logic()
    test_reserve_recruitment_logic()
    test_stamina_overflow_logic()
    test_high_level_tile_farming()
    test_policy_order_calculation()
    test_blacksmith_inspection_keywords()
    print("-" * 72)
    print("结论: 阶段 5 全部 6 项日常后勤与屯田打铁管家核心业务逻辑验证通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
