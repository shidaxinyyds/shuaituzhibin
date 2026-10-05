#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""OCR 回归打分：从 results.csv 计算各场景准确率/召回率/平均耗时，可选与基线对比。

列约定: scenario,file,expected,got,latency_ms
- 准确率(precision)：got 归一化后 == expected 归一化 的比例。
- 召回率(recall)    ：expected 非空时 got 也非空（识别到东西）的比例。
- 归一化：去掉所有空白与分隔差异后再比对，避免空格/全角噪声误判。
数字来自真机，本脚本只做统计，不生成任何结果。
"""
import argparse
import csv
import re
import sys
from collections import defaultdict


def norm(s):
    if s is None:
        return ""
    s = str(s)
    # 全角冒号/斜杠/逗号归一，去所有空白
    s = s.replace("：", ":").replace("，", ",").replace("／", "/")
    s = re.sub(r"\s+", "", s)
    return s


def load(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig")))
    for r in rows:
        r.setdefault("scenario", "misc")
        r["expected"] = r.get("expected", "")
        r["got"] = r.get("got", "")
        try:
            r["latency_ms"] = float(r.get("latency_ms") or 0)
        except ValueError:
            r["latency_ms"] = 0.0
    return rows


def stats(rows):
    by = defaultdict(list)
    for r in rows:
        by[r["scenario"]].append(r)
    out = {}
    for sc, items in by.items():
        n = len(items)
        correct = sum(1 for r in items if norm(r["got"]) == norm(r["expected"]) and norm(r["expected"]))
        expect_present = sum(1 for r in items if norm(r["expected"]))
        got_present = sum(1 for r in items if norm(r["got"]))
        recall = (got_present / expect_present * 100) if expect_present else float("nan")
        prec = (correct / expect_present * 100) if expect_present else float("nan")
        lat = sum(r["latency_ms"] for r in items) / n if n else 0.0
        out[sc] = dict(n=n, prec=prec, recall=recall, lat=lat)
    return out


def fmt(d):
    lines = []
    for sc in sorted(d):
        v = d[sc]
        lines.append("  %-14s n=%-3d 准确率=%6.1f%%  召回=%6.1f%%  平均耗时=%5.1fms" % (
            sc, v["n"], v["prec"], v["recall"], v["lat"]))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("results")
    ap.add_argument("--baseline")
    args = ap.parse_args()

    cur = stats(load(args.results))
    print("==== 当前 (%s) ====" % args.results)
    print(fmt(cur))

    if args.baseline:
        base = stats(load(args.baseline))
        print("\n==== 基线 (%s) ====" % args.baseline)
        print(fmt(base))
        print("\n==== 差值 (当前 - 基线) ====")
        for sc in sorted(set(cur) | set(base)):
            c = cur.get(sc)
            b = base.get(sc)
            if c and b:
                dp = c["prec"] - b["prec"]
                dr = c["recall"] - b["recall"]
                dl = c["lat"] - b["lat"]
                print("  %-14s Δ准确率=%+6.1fpp  Δ召回=%+6.1fpp  Δ耗时=%+6.1fms" % (sc, dp, dr, dl))
            else:
                print("  %-14s 场景缺失(仅一侧有数据)" % sc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
