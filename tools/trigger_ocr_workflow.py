#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
触发 `build_apk_with_ocr.yml` 并把**真实日志**取回本地
====================================================

为什么需要它（而不是"我判断它能编过"）
--------------------------------------
默认构建里 OCR 是 native 空桩，"OCR 到底能不能真编出来"这件事本机无法自证：
没有 NDK、没有 ncnn/OpenCV 包，能下载 68MB 依赖并真编译的地方只有 GitHub Actions。
这个工作流是 `workflow_dispatch`（只能手动触发），且**历史上一次都没跑过**
（`ocr_runs=0`，由 API 实测得到，不是推测）。所以它能不能过，除了真实日志没有任何判据。

本机也没有 GitHub API 凭据：`git push` 用的是 SSH 私钥，而**手动触发工作流必须要
带 `workflow` 权限的 token**，SSH key 顶不上。这个脚本就是把这一步补齐——
你给一次 token，它触发、轮询到出结论、并把日志 zip 落到本地供人读。

诚实边界
--------
* **token 不落盘**：只从环境变量 `GITHUB_TOKEN` 或 `--token-file` 读，
  脚本不写进任何文件、不打印其内容，也不带它做触发/读日志之外的调用。
* 失败**照实返回**：编不过就打印卡在第几步、把那步日志贴回来。
  绝不把"没跑成"回报成"已可用"。
* 公开仓库的 run **列表**匿名可读（`tools/ci_status.py` 就是这么读的），但
  **日志下载**必须认证 —— 这也是非要有 token 不可的原因。

用法
----
    $env:GITHUB_TOKEN='ghp_…'; python tools/trigger_ocr_workflow.py
    python tools/trigger_ocr_workflow.py --token-file secrets.txt --wait 1800
    python tools/trigger_ocr_workflow.py --latest --logs-only    # 只取最近一次 run 的日志

退出码：0 = run 成功；1 = run 失败/取消/超时，或拿不到 token / API 报错（日志尽量已落地）。
（注：token 缺失与 API 错误走 `raise SystemExit(消息)`，Python 语义就是退出 1，
所以这里**不写 2**——上一版文档承诺了 2 而代码给的是 1，属于说谎，已按代码订正。）
"""

import argparse
import io
import json
import os
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
import zipfile

OWNER = "shidaxinyyds"
REPO_NAME = "shuaituzhibin"
API = "https://api.github.com/repos/%s/%s" % (OWNER, REPO_NAME)
WORKFLOW_FILE = ".github/workflows/build_apk_with_ocr.yml"
DEFAULT_LOG_DIR = os.path.join("tools", "p3", "ocr_ci_logs")


def read_token(token_file):
    """token 只从环境变量或显式文件读，绝不硬编码、绝不写盘。"""
    if token_file:
        if not os.path.isfile(token_file):
            raise SystemExit("token 文件不存在: %s" % token_file)
        with io.open(token_file, encoding="utf-8-sig") as fh:
            v = fh.read().strip()
        if v:
            return v
    return (os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or "").strip()


def api(token, path, method="GET", payload=None, accept_redirect=False):
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "stzb-ocr-trigger",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = "Bearer " + token
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read()
            if accept_redirect:
                return resp.geturl()
            if not body:
                return None
            return json.loads(body.decode("utf-8"))
    except urllib.error.HTTPError as e:
        # 404 常见于"工作流从未跑过，runs 端点还没有数据"，与"没有权限"要分开说
        detail = ""
        try:
            detail = (json.loads(e.read().decode("utf-8")) or {}).get("message", "")
        except Exception:
            pass
        if e.code == 404:
            raise SystemExit("API 404 (%s %s)：%s\n"
                             "先确认仓库坐标与 workflow id；公开仓库的日志端点也必须带 token 才不返回 404。"
                             % (method, path, detail))
        if e.code in (401, 403):
            raise SystemExit("API %d：%s\n"
                             "token 无效、过期，或缺 `workflow`（触发）/`Actions: read`（取日志）权限。"
                             % (e.code, detail))
        raise SystemExit("API %d (%s %s)：%s" % (e.code, method, path, detail))


def workflow_id(token):
    got = api(token, "/actions/workflows")
    for w in got.get("workflows", []):
        if w.get("path") == WORKFLOW_FILE:
            return w["id"], w["name"]
    raise SystemExit("仓库里找不到 %s —— 工作流文件名可能已改" % WORKFLOW_FILE)


def trigger(token, wid, ref, inputs):
    body = {"ref": ref}
    if inputs:
        body["inputs"] = inputs
    before = latest_run_id(token, wid)
    api(token, "/actions/workflows/%s/dispatches" % wid, method="POST", payload=body)
    print("已发送触发请求（workflow %s, ref %s, inputs %s）" % (wid, ref, inputs or "默认"))
    # dispatch 是异步的：等它出现在 runs 里，并明确区分"排队"与"不存在"
    deadline = time.time() + 120
    while time.time() < deadline:
        time.sleep(5)
        rid = latest_run_id(token, wid)
        if rid and rid != before:
            print("新 run id = %s" % rid)
            return rid
        if rid and not before:
            return rid
    raise SystemExit("触发后 120 秒内没看到新 run —— 请到 Actions 页面确认是否被拒绝（权限/审批）")


def latest_run_id(token, wid):
    got = api(token, "/actions/workflows/%s/runs?per_page=1" % wid)
    runs = got.get("workflow_runs") or []
    return runs[0]["id"] if runs else None


def run_detail(token, rid):
    return api(token, "/actions/runs/%s" % rid)


def wait(token, rid, wait_s):
    deadline = time.time() + wait_s
    last = ""
    while True:
        r = run_detail(token, rid)
        state = "%s / %s" % (r.get("status"), r.get("conclusion"))
        if state != last:
            print("run %s: %s（attempt %s，%s 起）" % (rid, state, r.get("run_attempt"), r.get("created_at")))
            last = state
        if r.get("status") == "completed":
            return r
        if time.time() > deadline:
            print("→ 等待超时（%ds），run 仍在进行。稍后用 --latest --logs-only 取结论。" % wait_s)
            return r
        time.sleep(20)


def grab_logs(token, rid, out_dir):
    """下载日志 zip 并解出每个 job 的 *.txt，路径按 API 给的布局来，不猜。"""
    url = api(token, "/actions/runs/%s/logs" % rid, accept_redirect=True)
    req = urllib.request.Request(url, headers={"User-Agent": "stzb-ocr-trigger"})
    if not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    zpath = os.path.join(out_dir, "run_%s.zip" % rid)
    with urllib.request.urlopen(req, timeout=180) as resp, open(zpath, "wb") as fh:
        shutil.copyfileobj(resp, fh)
    names = []
    with zipfile.ZipFile(zpath) as zf:
        for n in zf.namelist():
            if n.endswith(".txt"):
                zf.extract(n, out_dir)
                names.append(n)
    print("日志已落地：%s（%d 个 txt）" % (out_dir, len(names)))
    return names


def summarize(out_dir, keywords):
    """把关键失败行挑出来。只报日志里真有的文字，不做"大概是版本问题"这种猜测。"""
    hits = []
    for root, _dirs, files in os.walk(out_dir):
        for f in files:
            if not f.endswith(".txt"):
                continue
            p = os.path.join(root, f)
            try:
                with io.open(p, encoding="utf-8", errors="replace") as fh:
                    for i, line in enumerate(fh, 1):
                        low = line.lower()
                        if any(k in low for k in keywords):
                            hits.append("%s:%d: %s" % (f, i, line.rstrip()[:300]))
            except Exception as e:
                print("读取 %s 失败: %s" % (p, e))
    return hits


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--token-file", default=None, help="从该文件读 token（否则读 GITHUB_TOKEN/GH_TOKEN）")
    ap.add_argument("--ref", default="main")
    ap.add_argument("--ncnn-version", default=None, help="留空则用工作流默认值")
    ap.add_argument("--opencv-version", default=None, help="必须与 build.gradle 里 AAR 同一 minor")
    ap.add_argument("--wait", type=int, default=2400, help="轮询秒数上限（默认 2400=40 分钟）")
    ap.add_argument("--latest", action="store_true", help="不触发，只对最近一次 run 取结论+日志")
    ap.add_argument("--logs-only", action="store_true", help="只取日志，不等")
    ap.add_argument("--out", default=DEFAULT_LOG_DIR)
    ap.add_argument("--grep", default="error,failed,undefined,cmake,ndk,UnsatisfiedLink,"
                                        "cannot find,not found,no such,mismatch",
                    help="关键失败词（逗号分隔，小写匹配）")
    args = ap.parse_args()

    token = read_token(args.token_file)
    if not token:
        raise SystemExit(
            "没有 GitHub token，这一步做不了。\n"
            "  需要的权限：Repository permissions → Actions: read and write，"
            "Content: read and write（触发 workflow_dispatch 用）。\n"
            "  创建：https://github.com/settings/tokens （Fine-grained 或 classic 都行）\n"
            "  用法：$env:GITHUB_TOKEN='…' 后重跑本脚本；token 不会被写进任何文件。")

    wid, name = workflow_id(token)
    print("工作流：%s（id %s）" % (name, wid))

    if args.latest:
        rid = latest_run_id(token, wid)
        if not rid:
            raise SystemExit("这个工作流还从来没有 run —— 去掉 --latest 以触发一次")
    else:
        inputs = {}
        if args.ncnn_version:
            inputs["ncnn_version"] = args.ncnn_version
        if args.opencv_version:
            inputs["opencv_version"] = args.opencv_version
        rid = trigger(token, wid, args.ref, inputs)

    run = run_detail(token, rid) if args.logs_only else wait(token, rid, args.wait)
    concl = run.get("conclusion")
    print("URL：%s" % run.get("html_url"))
    print("结论：%s / %s" % (run.get("status"), concl))

    names = grab_logs(token, rid, args.out)
    kws = [k.strip().lower() for k in args.grep.split(",") if k.strip()]
    hits = summarize(args.out, kws)
    print("-" * 72)
    if hits:
        print("关键日志行（%d 条，截前 60 条）：" % len(hits))
        for h in hits[:60]:
            print("  " + re.sub(r"\x1b\[[0-9;]*m", "", h))
    else:
        print("日志里没搜到关键词命中；请人工翻 %s" % args.out)
    print("-" * 72)
    if concl == "success":
        print("真编过了。下一步：按工作流日志里的 APK 产物名下载，再用 "
              "tools/acceptance_check.py 验 OCR 不是空桩。")
        return 0
    if concl in (None, "cancelled"):
        print("没有有效结论（排队被撤/仍在跑）——这不算失败，重跑即可，不要去改代码。")
        return 1
    print("编不过。上面的日志行就是判据：按里面点名的资产名/版本号补进工作流，"
          "再重跑。**在拿到 success 日志之前，OCR 一律按不可用对待。**")
    return 1


if __name__ == "__main__":
    sys.exit(main())
