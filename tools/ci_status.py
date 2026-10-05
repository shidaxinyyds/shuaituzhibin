#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CI 状态速查（本仓库专用：本机跑不了 Gradle，CI 就是唯一编译器）
==============================================================

为什么需要它
------------
本机的硬约束：没有 JDK / Android SDK / NDK，`./gradlew` 起不来。于是
**GitHub Actions 是唯一能真正编译这个工程的地方**。整条 P3 落地就是靠
CI 才抓到三个本地闸门拦不住的编译错误（CI Run #53）：

  * `for (((s, e), budget) in cases)` —— for 头部不支持嵌套解构
  * `"首=$d0ms"`                      —— Kotlin 把 `$d0ms` 整体当标识符
  * `stroke.endMs`                   —— StrokeDescription 没有时长 getter

以前每次确认 CI 都得手动打开 API 链接、再肉眼翻 JSON，容易看错 sha、
也容易把 **cancelled**（排队超时被系统撤掉，不是检查失败）误读成 **failure**。
这两者含义完全不同：failure 要改代码，cancelled 只要重新触发。本脚本把
判别口径固定下来（对判成失败的 run 会再下钻一层看 job 的 steps），
并支持轮询到出结论。

真实对照样本：
  * Run #53 = 真 failure（三个 Kotlin 编译错误，确实该改代码）
  * Run #28 = run 级写 failure，但 job `steps=[]`/`runner_id=0`（排队 15 分钟被撤，
    一个检查都没执行过）——对这种去改代码就是白改

用法
----
    python tools/ci_status.py                      # 打印最近若干条 run
    python tools/ci_status.py --watch              # 轮询直到最新一批跑完
    python tools/ci_status.py --sha  ec248b3       # 只看某个提交
    python tools/ci_status.py --from-file x.json   # 离线解析已下载的 JSON（自测用）
    python tools/ci_status.py --selftest           # 反例自测（含 cancelled≠failure）

退出码：0 = 关注的 run 全部成功（或仍在跑）；1 = 有 failure/cancelled 需处置；2 = 取不到数据。

依赖：纯标准库。默认只读公开仓库的 Actions API，不写入任何东西。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

# 仓库坐标只在这里出现一次，换 fork 只改这一处
OWNER = "shidaxinyyds"
REPO_NAME = "shuaituzhibin"
API = "https://api.github.com/repos/%s/%s/actions/runs" % (OWNER, REPO_NAME)

# run/job 的 conclusion 取值很多，但**只有这几条意味着"该改代码"**
REAL_PROBLEMS = ("failure", "timed_out", "startup_failure")
# 这几条是环境/人为问题：重触发即可，绝不许当成检查失败去乱改代码
NEEDS_RETRIGGER = ("cancelled", "action_required", "stale")
DONE = ("completed",)


def fetch_runs(limit=10, sha=None, timeout=20):
    url = "%s?per_page=%d" % (API, max(1, min(limit, 50)))
    if sha:
        url += "&head_sha=" + sha
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "stzb-ci-status",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def summarize(run):
    """把一条 run 归一成 (判定, 说明)。判定 ∈ OK / RUNNING / FIX_CODE / RETRIGGER。"""
    status = run.get("status") or ""
    concl = run.get("conclusion")
    if status not in DONE or concl is None:
        return "RUNNING", "status=%s" % (status or "?")
    if concl == "success":
        return "OK", "success"
    if concl in REAL_PROBLEMS:
        return "FIX_CODE", concl
    if concl in NEEDS_RETRIGGER:
        # 这是本脚本最有价值的一行：把"被排队机制撤掉"和"检查没过"分开。
        return "RETRIGGER", "%s（不是检查失败，重触发即可）" % concl
    return "FIX_CODE", concl or "unknown"


def fetch_jobs(run_id, timeout=20):
    """下钻到 job。run 级结论会骗人，job 级的 steps 不会。"""
    url = "%s/%s/jobs" % (API, run_id)
    req = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "stzb-ci-status",
    })
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8")).get("jobs", [])


def looks_like_infra(jobs):
    """判断一条“failure”是否其实是**根本没跑起来**。

    真实教训：Run #28 的 run 级 conclusion = failure，但它的 job 是
    `conclusion=cancelled`、`steps=[]`、`runner_id=0` —— 排队 15 分钟被撤，
    一个检查都没执行过。把它当成“静态回归不过”去改代码，纯粹是浪费时间。
    反之真检查失败时 job 里**一定有跑过的 step**（且其中含 failure）。
    """
    if not jobs:
        return True
    for j in jobs:
        if j.get("steps"):
            return False          # 真的执行过 → 按真失败对待
        if j.get("conclusion") == "failure":
            return False
    return True


def classify(run, jobs_fetcher=None):
    """先按 run 级字段判；判成“该改代码”时再下钻一次，把环境问题剔出去。"""
    verdict, why = summarize(run)
    if verdict == "FIX_CODE" and jobs_fetcher is not None:
        try:
            jobs = jobs_fetcher(run.get("id"))
        except Exception as e:
            return verdict, "%s（无法确认是否环境问题：%s）" % (why, type(e).__name__)
        if looks_like_infra(jobs):
            return "RETRIGGER", ("run 记作 failure，但 job 没有任何 step 执行过"
                                "（runner 未到位 / 被排队机制撤掉）——只需重触发")
    return verdict, why


def print_table(data, show_all=False, jobs_fetcher=None):
    """逐条列出 run；有问题的一律附链接，全绿的只在 --all 时附链接。"""
    runs = data.get("workflow_runs", [])
    if not runs:
        print("（没有 run 记录）")
        return 0
    verdicts = {}
    for r in runs:
        verdict, why = classify(r, jobs_fetcher)
        verdicts[r.get("id")] = verdict
        mark = {"OK": "✅", "RUNNING": "⏳", "FIX_CODE": "❌", "RETRIGGER": "🔁"}[verdict]
        print("%s run#%-4s %-24s %-8s %-12s %s" % (
            mark, r.get("run_number"), (r.get("name") or "")[:24],
            (r.get("head_sha") or "")[:7], verdict, why))
        if verdict != "OK" or show_all:
            print("      %s" % r.get("html_url"))
    if any(v == "FIX_CODE" for v in verdicts.values()):
        return 1
    if any(v == "RETRIGGER" for v in verdicts.values()):
        return 1
    if any(v == "RUNNING" for v in verdicts.values()):
        return 0
    return 0


def watch(limit, sha, interval, max_wait):
    """轮询到最新一批 run 全部出结论；把 CI 的等待变成一条命令。"""
    waited = 0
    while True:
        try:
            data = fetch_runs(limit=limit, sha=sha)
        except (urllib.error.URLError, OSError, ValueError) as e:
            print("取不到 CI 数据：%s" % e, file=sys.stderr)
            return 2
        rc = print_table(data, show_all=True, jobs_fetcher=fetch_jobs)
        runs = data.get("workflow_runs", [])
        still = [r for r in runs if classify(r, fetch_jobs)[0] == "RUNNING"]
        if not still:
            print("-" * 60)
            print("所有关注的 run 已出结论。")
            return rc
        if waited >= max_wait:
            print("已等待 %ds，仍有 %d 条在排队/运行中（配额排队属正常）。" % (waited, len(still)))
            return 0
        print("仍有 %d 条未完成，%ds 后重试（累计 %ds / 上限 %ds）" % (
            len(still), interval, waited, max_wait))
        time.sleep(interval)
        waited += interval


def selftest():
    """反例自测：确认 cancelled 不会被误判成"检查没过"。"""
    ok = True
    cases = [
        ({"status": "completed", "conclusion": "success"}, "OK"),
        ({"status": "completed", "conclusion": "failure"}, "FIX_CODE"),
        ({"status": "completed", "conclusion": "cancelled"}, "RETRIGGER"),
        ({"status": "in_progress", "conclusion": None}, "RUNNING"),
        ({"status": "queued", "conclusion": None}, "RUNNING"),
        ({"status": "completed", "conclusion": "timed_out"}, "FIX_CODE"),
    ]
    for run, expect in cases:
        got = summarize(run)[0]
        if got != expect:
            print("❌ 判定错误: %s → %s（应为 %s）" % (run, got, expect))
            ok = False
    # 关键反例：整条 run 只是被排队机制撤掉时，绝不能提示"去改代码"
    v, why = summarize({"status": "completed", "conclusion": "cancelled"})
    if v != "RETRIGGER" or "重触发" not in why:
        print("❌ cancelled 的处置说明不到位: %s / %s" % (v, why))
        ok = False
    # 环境归因：run 写 failure、但 job 一个 step 都没跑过 → 必须判成重触发
    fake_fetch = lambda _id: [{"conclusion": "cancelled", "steps": [], "runner_id": 0}]
    v, why = classify({"id": 1, "status": "completed", "conclusion": "failure"}, fake_fetch)
    if v != "RETRIGGER":
        print("❌ 没跑起来的 run 被误判成该改代码: %s / %s" % (v, why))
        ok = False
    # 真失败：job 里带已执行的 step，绝不能被归成环境问题
    real_fetch = lambda _id: [{"conclusion": "failure",
                              "steps": [{"name": "Run checks", "conclusion": "failure"}]}]
    v, _why = classify({"id": 2, "status": "completed", "conclusion": "failure"}, real_fetch)
    if v != "FIX_CODE":
        print("❌ 真检查失败被判成了环境问题: %s" % v)
        ok = False
    # 取不到 job 时必须保守地**保持改代码判定**，不能把未知洗成环境锅
    boom_fetch = lambda _id: (_ for _ in ()).throw(RuntimeError("no net"))
    v, _why = classify({"id": 3, "status": "completed", "conclusion": "failure"}, boom_fetch)
    if v != "FIX_CODE":
        print("❌ 下钻失败时不该放宽判定: %s" % v)
        ok = False
    if ok:
        print("[selftest] 通过：%d 种 run 状态判定正确，cancelled / 未执行 / 真失败 已明确区分"
              % len(cases))
    return ok


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=8)
    ap.add_argument("--sha", default=None, help="只看某个提交（短 sha 即可）")
    ap.add_argument("--watch", action="store_true", help="轮询直到全部出结论")
    ap.add_argument("--interval", type=int, default=30)
    ap.add_argument("--max-wait", type=int, default=900)
    ap.add_argument("--from-file", default=None, help="离线解析已下载的 runs JSON")
    ap.add_argument("--all", action="store_true", help="连成功的 run 也附上链接")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return 0 if selftest() else 1

    if args.from_file:
        # 离线模式刻意不下钻：没网就读不到 job，此时只给 run 级结论，不假装已确认过环境因素
        with open(args.from_file, "r", encoding="utf-8", errors="replace") as fh:
            data = json.load(fh)
        return print_table(data, show_all=args.all)

    if args.watch:
        return watch(args.limit, args.sha, args.interval, args.max_wait)

    try:
        data = fetch_runs(limit=args.limit, sha=args.sha)
    except (urllib.error.URLError, OSError) as e:
        print("取不到 CI 数据（本机无外网？请用浏览器打开后另存，再用 --from-file 解析）：\n  %s" % e,
              file=sys.stderr)
        print("  API: %s" % API, file=sys.stderr)
        return 2
    return print_table(data, show_all=args.all, jobs_fetcher=fetch_jobs)


if __name__ == "__main__":
    sys.exit(main())
