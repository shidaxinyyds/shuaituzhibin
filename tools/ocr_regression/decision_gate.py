#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P2 OCR 迁移判定闸门：把「赢才迁」从一句口号变成机器可执行的裁决
================================================================

背景与纪律
----------
P2 的既定纪律是：**PP-OCRv5 只有在真机跑分上赢了 PP-OCRv3 才允许迁移，
否则保持出厂 v3 不动。** 问题是「赢」当时只写在计划里，没有任何可执行定义——
而"感觉变准了"从来不是商用产品的放行依据。本脚本把胜负标准写死成阈值，
读两份真机跑分 CSV（改动前 / 改动后）直接给出三选一裁决：

    MIGRATE              全部判据通过 ⇒ 允许把默认引擎切到 v5
    STAY_V3              任一判据不过 ⇒ 保持 v3（并列出是哪条拦住的）
    INSUFFICIENT_EVIDENCE 证据不足/缺失 ⇒ **绝不等于通过**，按 v3 处理

本脚本**不产生任何数字**，只对真机回填的 `results_before.csv` /
`results_after.csv` 做统计与裁决。数字必须由设备产生（见 README.md 的协议）。

判据（全部为合取，缺一不可）
-----------------------------
| # | 判据 | 阈值 | 为什么这么定 |
|---|---|---|---|
| C1 | 样本量 | 每个场景 n ≥ 8 | 少于 8 张的百分比没有统计意义，容易被 1 张图翻转结论 |
| C2 | 小字召回 | `stamina/coordinate/countdown` Δ召回 ≥ +3.0pp | 升级 OCR 的**唯一动机**就是率土 10~16px 小字；这条不涨就是白涨体积 |
| C3 | 小字准确率 | 同上三场景 Δ准确率 ≥ 0 | 召回靠多框出来、准确率却掉下来＝噪声变多，是负收益 |
| C4 | 大字不回退 | `ui_text` Δ准确率 ≥ −0.5pp 且 Δ召回 ≥ −0.5pp | 横排大字本来就已经够用，迁移动辄把已验证能力打回是净亏 |
| C5 | 竖排不回退 | `vertical_name` Δ召回 ≥ −0.5pp | 守将竖名是第二通道，退化即伤 PVP 透视 |
| C6 | 耗时 | 每场景平均耗时 ≤ 基线 ×1.15 | 视觉循环跑在帧节奏上，慢 15% 以上会连带放大整条流水线延迟 |
| C7 | 体积 | v5 在包权重字节 ≤ v3 在包权重字节 | 已实测基线 97.8MB、红线 200MB；OCR 换版不许吃掉别的安全余量 |
| C8 | 配套契约 | `check_ocr_asset_contract` 对 v5 判 PASS | **半套权重（v5 rec + v3 词典）会静默解出乱码**，跑分再漂亮也一律拒绝 |

裁决逻辑上的两个刻意选择
------------------------
* **证据缺失 ⇒ INSUFFICIENT_EVIDENCE，而不是 PASS。** 缺数据的默认值必须是
  "不动生产链路"，否则"没测过"会被读成"测了没问题"。
* **C8 先于统计判据短路。** 配套契约是静态可证的硬前提；它不过时，
  后面的百分比全部无意义（乱码的召回率没有任何解释力）。

用法
----
    python tools/ocr_regression/decision_gate.py \
        --before results_before.csv --after results_after.csv
    python tools/ocr_regression/decision_gate.py --selftest

退出码：0 = MIGRATE；1 = STAY_V3；2 = INSUFFICIENT_EVIDENCE 或参数/文件错误。
"""

import argparse
import csv
import importlib.util
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
ASSETS = os.path.join(REPO, "client", "app", "src", "main", "assets")

SMALL_SCENARIOS = ("stamina", "coordinate", "countdown")
RECALL_GAIN_PP = 3.0        # C2
ACC_REGRESS_PP = 0.0        # C3
BIG_TOL_PP = 0.5            # C4 / C5
LAT_RATIO_MAX = 1.15        # C6
MIN_SAMPLES = 8             # C1

# 与 OcrEngine.kt / check_ocr_asset_contract.py 一致的在包命名约定
V3_WEIGHTS = ["ch_PP-OCRv3_det_infer", "ch_PP-OCRv3_rec_infer"]
V5_WEIGHTS = ["ch_PP-OCRv5_det_infer", "ch_PP-OCRv5_rec_infer"]


def _load_sibling(name):
    """按文件路径加载同目录脚本（不依赖 cwd 与包结构）。"""
    path = os.path.join(HERE, name)
    if not os.path.isfile(path):
        return None
    spec = importlib.util.spec_from_file_location(name[:-3], path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def stats_from_csv(path):
    """复用 score.py 的口径，保证「打分」与「裁决」不会出现两套定义。"""
    score = _load_sibling("score.py")
    if score is None:
        raise RuntimeError("找不到同目录的 score.py，无法复用打分口径")
    return score.stats(score.load(path))


def weights_bytes(bases):
    """按 ncnn 命名约定统计在包权重体积（param+bin）。返回 (字节数, 是否齐全)。"""
    total, all_present, missing = 0, True, []
    for b in bases:
        for ext in (".param", ".bin"):
            p = os.path.join(ASSETS, b + ext)
            if os.path.isfile(p):
                total += os.path.getsize(p)
            else:
                all_present = False
                missing.append(os.path.basename(p))
    return total, all_present, missing


def finite(v):
    try:
        return v == v and abs(v) != float("inf")
    except Exception:
        return False


def decide(before, after, contract_ok, v5_bytes, v3_bytes):
    """返回 (verdict, [(code, ok, 说明), …])。"""
    checks = []
    scen = set(before) | set(after)

    # ---- C8 配套契约（硬前提，先短路）----
    if not contract_ok:
        checks.append(("C8 权重↔词典配套", False,
                       "v5 半套或未入包 ⇒ rec 输出类别数与词典不匹配，上机会把每个字解成乱码。"
                       "统计判据在此前提下没有解释力，直接拒绝。"))
        return "STAY_V3", checks
    checks.append(("C8 权重↔词典配套", True, "v5 rec 输出类别数 == 词典行数 + 2"))

    # ---- C1 样本量 ----
    thin = []
    for s in sorted(scen):
        n = after.get(s, {}).get("n", 0)
        nb = before.get(s, {}).get("n", 0)
        if n < MIN_SAMPLES or nb < MIN_SAMPLES:
            thin.append("%s(after=%d/before=%d)" % (s, n, nb))
    if thin:
        checks.append(("C1 样本量≥%d" % MIN_SAMPLES, False,
                       "样本不足的场景: " + ", ".join(thin) +
                       " ⇒ 百分比在这种样本量下可被单张图翻转，判为证据不足。"))
        return "INSUFFICIENT_EVIDENCE", checks
    checks.append(("C1 样本量≥%d" % MIN_SAMPLES, True,
                   "; ".join("%s n=%d" % (s, after[s]["n"]) for s in sorted(scen))))

    def d(s, key):
        return after[s][key] - before[s][key]

    # ---- C2 小字召回必须显著提升 ----
    bad = []
    for s in SMALL_SCENARIOS:
        if s not in after or s not in before:
            bad.append("%s(场景缺失)" % s)
            continue
        if not (finite(after[s]["recall"]) and finite(before[s]["recall"])):
            bad.append("%s(基线无正样本，召回不可比)" % s)
            continue
        gain = d(s, "recall")
        if gain < RECALL_GAIN_PP:
            bad.append("%s Δ召回=%+.1fpp" % (s, gain))
    ok2 = not bad
    checks.append(("C2 小字召回Δ≥+%.1fpp" % RECALL_GAIN_PP, ok2,
                   "全部达标: " + ", ".join("%s Δ=%+.1fpp" % (s, d(s, "recall")) for s in SMALL_SCENARIOS
                                            if s in after and s in before)
                   if ok2 else "未达标 → " + "; ".join(bad)))

    # ---- C3 小字准确率不得退化 ----
    bad = []
    for s in SMALL_SCENARIOS:
        if s in after and s in before and d(s, "prec") < -ACC_REGRESS_PP:
            bad.append("%s Δ准确率=%+.1fpp" % (s, d(s, "prec")))
    ok3 = not bad
    checks.append(("C3 小字准确率不回退", ok3,
                   "无退化" if ok3 else "出现退化 → " + "; ".join(bad)))

    # ---- C4/C5 大字与竖排不得回退 ----
    for code, s, tol in (("C4 ui_text 不回退", "ui_text", BIG_TOL_PP),
                         ("C5 vertical_name 不回退", "vertical_name", BIG_TOL_PP)):
        if s not in after or s not in before:
            checks.append((code, True, "两侧均无该场景数据 ⇒ 不参与判定（非失败）"))
            continue
        dp, dr = d(s, "prec"), d(s, "recall")
        okx = dp >= -tol and dr >= -tol
        checks.append((code, okx,
                       "Δ准确率=%+.1fpp Δ召回=%+.1fpp（容差 %.1fpp）" % (dp, dr, tol)))

    # ---- C6 耗时 ----
    bad = []
    for s in sorted(scen):
        if s not in after or s not in before:
            continue
        lb, la = before[s]["lat"], after[s]["lat"]
        if lb <= 0:
            continue
        if la > lb * LAT_RATIO_MAX:
            bad.append("%s %.1fms→%.1fms(×%.2f)" % (s, lb, la, la / lb))
    ok6 = not bad
    checks.append(("C6 平均耗时≤基线×%.2f" % LAT_RATIO_MAX, ok6,
                   "全部在阈值内" if ok6 else "超阈值 → " + "; ".join(bad)))

    # ---- C7 体积 ----
    if v5_bytes <= 0:
        checks.append(("C7 体积≤v3", False, "v5 权重体积实测为 0（未入包），无从比较"))
    else:
        ok7 = v5_bytes <= v3_bytes
        checks.append(("C7 体积≤v3", ok7,
                       "v5 %.2fMB vs v3 %.2fMB（%+.2fMB）"
                       % (v5_bytes / 1048576.0, v3_bytes / 1048576.0,
                          (v5_bytes - v3_bytes) / 1048576.0)))

    verdict = "MIGRATE" if all(ok for _c, ok, _m in checks) else "STAY_V3"
    return verdict, checks


def print_report(verdict, checks, before_path, after_path, v3_bytes, v5_bytes):
    print("=" * 78)
    print("P2 OCR 迁移判定闸门 · 裁决")
    print("=" * 78)
    print("基线跑分 : %s" % (before_path if before_path else "（缺失）"))
    print("候选跑分 : %s" % (after_path if after_path else "（缺失）"))
    print("在包体积 : v3=%.2fMB  v5=%.2fMB"
          % (v3_bytes / 1048576.0, v5_bytes / 1048576.0))
    print("-" * 78)
    for code, ok, msg in checks:
        print("  [%s] %-26s %s" % ("PASS" if ok else "FAIL", code, msg))
    print("-" * 78)
    print("裁决: **%s**" % verdict)
    if verdict == "MIGRATE":
        print("  全部判据通过。允许把默认引擎切到 v5；切完必须再跑一轮同协议回归留痕。")
    elif verdict == "STAY_V3":
        print("  存在未通过判据 ⇒ 按「赢才迁」保持出厂 PP-OCRv3，native 版本容错会继续选 v3，零行为变化。")
    else:
        print("  证据不足 ≠ 通过。默认不动生产链路；补齐样本后重跑本闸门。")
    return verdict


def run_selftest():
    """反例自测：用**合成** CSV 驱动每一条拒绝分支。

    ⚠️ 这里的数字全部是构造出来的逻辑用例，只证明「阈值确实会按设计翻转裁决」，
    不构成任何真实跑分证据。
    """
    ok = True
    tmp = tempfile.mkdtemp(prefix="ocr_gate_selftest_")

    def write_csv(path, rows):
        with open(path, "w", encoding="utf-8", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["scenario", "file", "expected", "got", "latency_ms"])
            w.writerows(rows)

    def mk(scenario, hits, misses, wrongs, lat):
        """直接按「命中/漏检/识别错」三元组造样本，n = hits+misses+wrongs。

        对应 score.py 口径：召回=(hits+wrongs)/n（只要读出来就算召回），
        准确率=hits/n。把三者写成显式参数，避免用下标算阈值时算错。
        """
        out = []
        i = 0
        for _ in range(hits):
            out.append([scenario, "%s_%03d.png" % (scenario[:1], i), "X", "X", lat]); i += 1
        for _ in range(misses):
            out.append([scenario, "%s_%03d.png" % (scenario[:1], i), "X", "", lat]); i += 1
        for _ in range(wrongs):
            out.append([scenario, "%s_%03d.png" % (scenario[:1], i), "X", "Y", lat]); i += 1
        return out

    try:
        V3B, V5B = 3_000_000, 2_500_000
        SMALL = SMALL_SCENARIOS

        def build_before():
            r = []
            for s in SMALL:                 # 小字基线：召回 80%、准确率 80%、耗时 20ms
                r += mk(s, 80, 20, 0, 20)
            r += mk("ui_text", 100, 0, 0, 18)   # 大字基线：已经全对
            return r

        def run(after_rows, contract_ok=True, v5=V5B, v3=V3B):
            p1 = os.path.join(tmp, "b.csv")
            p2 = os.path.join(tmp, "a.csv")
            write_csv(p1, build_before())
            write_csv(p2, after_rows)
            return decide(stats_from_csv(p1), stats_from_csv(p2), contract_ok, v5, v3)

        # T1 小字召回大涨且各项不退 ⇒ MIGRATE
        v, c = run(sum(([mk(s, 100, 0, 0, 20) for s in SMALL]
                        + [mk("ui_text", 100, 0, 0, 18)]), []))
        if v != "MIGRATE":
            print("❌ 自测 T1 失败：明显胜利场景未判 MIGRATE（得 %s）" % v)
            for code, okf, m in c:
                print("     %s %s: %s" % ("FAIL" if not okf else "pass", code, m))
            ok = False
        else:
            print("✅ 自测 T1 通过：小字召回 +20pp 且无回退 ⇒ MIGRATE")

        # T2 召回只涨 1pp（低于 3pp 阈值）⇒ STAY_V3（不许“有点提升就迁”）
        v, c = run(sum(([mk(s, 81, 19, 0, 20) for s in SMALL]
                        + [mk("ui_text", 100, 0, 0, 18)]), []))
        if v != "STAY_V3":
            print("❌ 自测 T2 失败：Δ召回仅 +1pp 却判成 %s" % v)
            ok = False
        else:
            print("✅ 自测 T2 通过：提升不到阈值 ⇒ STAY_V3")

        # T3 召回涨到 85% 但准确率从 80% 跌到 79%（靠多框换漏检）⇒ STAY_V3
        v, c = run(sum(([mk(s, 79, 15, 6, 20) for s in SMALL]
                        + [mk("ui_text", 100, 0, 0, 18)]), []))
        if v != "STAY_V3":
            print("❌ 自测 T3 失败：准确率退化却判成 %s" % v)
            ok = False
        else:
            print("✅ 自测 T3 通过：召回+5pp 但准确率退化 ⇒ STAY_V3（噪声换召回不算赢）")

        # T4 全部判据都好，只是慢 30% ⇒ STAY_V3
        v, c = run(sum(([mk(s, 100, 0, 0, 26) for s in SMALL]
                        + [mk("ui_text", 100, 0, 0, 18)]), []))
        if v != "STAY_V3":
            print("❌ 自测 T4 失败：耗时超阈值却判成 %s" % v)
            ok = False
        else:
            print("✅ 自测 T4 通过：耗时 ×1.3 ⇒ STAY_V3")

        # T5 跑分全赢但体积变大 ⇒ STAY_V3
        v, c = run(sum(([mk(s, 100, 0, 0, 20) for s in SMALL]
                        + [mk("ui_text", 100, 0, 0, 18)]), []), v5=4_000_000)
        if v != "STAY_V3":
            print("❌ 自测 T5 失败：体积超限却判成 %s" % v)
            ok = False
        else:
            print("✅ 自测 T5 通过：v5 体积 > v3 ⇒ STAY_V3")

        # T6 配套契约不过 ⇒ 无条件 STAY_V3（哪怕跑分全线飘红）
        v, c = run(sum(([mk(s, 100, 0, 0, 20) for s in SMALL]
                        + [mk("ui_text", 100, 0, 0, 18)]), []), contract_ok=False)
        if v != "STAY_V3":
            print("❌ 自测 T6 失败：半套权重竟被放行（%s）" % v)
            ok = False
        else:
            print("✅ 自测 T6 通过：配套契约不满足时短路拒绝（乱码无解释力）")

        # T7 样本量不足 ⇒ INSUFFICIENT_EVIDENCE，且必须不是绿灯
        v, c = run(sum(([mk(s, 3, 1, 0, 20) for s in SMALL]
                        + [mk("ui_text", 4, 0, 0, 18)]), []))
        if v != "INSUFFICIENT_EVIDENCE":
            print("❌ 自测 T7 失败：n=4 应判证据不足，实得 %s" % v)
            ok = False
        else:
            print("✅ 自测 T7 通过：n<8 ⇒ INSUFFICIENT_EVIDENCE（缺数据绝不等于通过）")

        # T8 候选侧缺一个场景 ⇒ 不得隐式通过
        v, c = run([mk(s, 100, 0, 0, 20) for s in SMALL])
        if v not in ("STAY_V3", "INSUFFICIENT_EVIDENCE"):
            print("❌ 自测 T8 失败：ui_text 候选侧缺失时不得放行，实得 %s" % v)
            ok = False
        else:
            print("✅ 自测 T8 通过：单侧缺场景判为 %s，不会被当成隐式通过" % v)
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 78)
    print("自测结论: %s" % ("全部通过" if ok else "存在失败项"))
    return 0 if ok else 1


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--before", default=os.path.join(HERE, "results_before.csv"))
    ap.add_argument("--after", default=os.path.join(HERE, "results_after.csv"))
    ap.add_argument("--selftest", action="store_true", help="用合成 CSV 驱动每条拒绝分支")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest()

    v3_bytes, v3_ok, _m3 = weights_bytes(V3_WEIGHTS)
    v5_bytes, v5_ok, m5 = weights_bytes(V5_WEIGHTS)

    # C8：静态可证的硬前提，优先短路（连跑分都不必读）
    contract = _load_sibling("check_ocr_asset_contract.py")
    contract_ok = False
    if contract is not None and v5_ok:
        _st, _msg = contract.check_set("v5", "ch_PP-OCRv5_rec_infer", "ppocr_keys_v5.txt")
        contract_ok = (_st == "PASS")

    if not v5_ok or not contract_ok:
        verdict, checks = decide({}, {}, contract_ok, v5_bytes, v3_bytes)
        if not v5_ok:
            checks.insert(0, ("v5 在包状态", False,
                              "v5 权重未入包，缺: " + ", ".join(m5)))
        print_report(verdict, checks, None, None, v3_bytes, v5_bytes)
        print("\n提示：本次未读取跑分 CSV——C8 是静态前提，它不过时跑分无从谈起。")
        return 1

    for p, label in ((args.before, "基线"), (args.after, "候选")):
        if not os.path.isfile(p):
            print("=" * 78)
            print("P2 OCR 迁移判定闸门 · 裁决")
            print("=" * 78)
            print("裁决: **INSUFFICIENT_EVIDENCE**")
            print("  %s跑分 CSV 不存在: %s" % (label, os.path.relpath(p, REPO)))
            print("  证据不足按「不迁」处理。产出方法见 tools/ocr_regression/README.md，")
            print("  打分口径见 score.py（本闸门与它共用同一套统计函数）。")
            return 2

    before = stats_from_csv(args.before)
    after = stats_from_csv(args.after)
    verdict, checks = decide(before, after, contract_ok, v5_bytes, v3_bytes)
    print_report(verdict, checks, args.before, args.after, v3_bytes, v5_bytes)
    return {"MIGRATE": 0, "STAY_V3": 1, "INSUFFICIENT_EVIDENCE": 2}[verdict]


if __name__ == "__main__":
    sys.exit(main())
