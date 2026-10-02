#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GitHub Actions `run:` 块的 shell 结构校验器
==========================================

为什么需要它
------------
本机没有 JDK/Android SDK，也没有可用的 bash（Git 自带的 bash 在 DSH 沙箱下无法启动），
所以既跑不了 `./gradlew`，也跑不了 `bash -n`。而 workflow 里的 shell 脚本一旦结构性
写错（少一个 `fi`、`done`、引号没配平），只有等 CI 真的跑起来才会炸——
那意味着一次完整的失败往返。

本脚本在纯 Python 下做**结构**层面的检查：
  * `if/elif/else/fi`、`for/while/until/select/done`、`case/esac` 的配对；
  * 每行双引号是否配平；
  * 行尾注释与单引号内容会被剥离，避免关键字误计数。

它**不能**替代 `bash -n`（不能验证语法细节、变量展开、命令是否存在），
但能挡住绝大多数"手写 CI 脚本"的低级结构性错误。

用法
----
    python tools/check_ci_shell.py [--workflows .github/workflows]

退出码：0 = 全部通过；1 = 发现问题。
"""

import argparse
import os
import re
import sys

try:
    import yaml
except ImportError:
    print("需要 PyYAML：pip install pyyaml", file=sys.stderr)
    sys.exit(2)

KEYWORDS = ("if", "then", "elif", "else", "fi", "for", "do", "done",
            "while", "case", "esac", "select", "until")
KEY_RE = re.compile(r"\b(" + "|".join(KEYWORDS) + r")\b")


def strip_single_quoted(line: str) -> str:
    return re.sub(r"'[^']*'", "''", line)


def mask_quoted(line: str) -> str:
    """
    把**单引号与双引号内的内容**都替换为空，只留引号本身。

    为什么必须两种都剥：关键字计数只应统计真正的命令结构。
    `echo "if fi done"` 里的 done 是字符串内容，不该被算作循环结束。
    最初只剥了单引号，自测立刻抓到这类误报。
    """
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if c in "'\"":
            quote = c
            out.append(quote)
            i += 1
            while i < n:
                if line[i] == "\\" and quote == '"':
                    i += 2
                    continue
                if line[i] == quote:
                    out.append(quote)
                    i += 1
                    break
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def strip_trailing_comment(line: str) -> str:
    """剥离不在引号内的行尾注释。"""
    out = []
    in_s = in_d = False
    i = 0
    while i < len(line):
        c = line[i]
        if c == "'" and not in_d:
            in_s = not in_s
        elif c == '"' and not in_s:
            in_d = not in_d
        elif c == "#" and not in_s and not in_d:
            break
        out.append(c)
        i += 1
    return "".join(out)


def check_script(script: str):
    problems = []
    depth_if = depth_loop = depth_case = 0

    for n, raw in enumerate(script.splitlines(), 1):
        # 去掉注释用于关键字计数；引号内容也要屏蔽，避免字符串里的关键字被误算
        counted = mask_quoted(strip_trailing_comment(raw))
        # 引号配平要在"去注释但保留引号内容"的版本上判断
        balanced = strip_trailing_comment(raw)

        for tok in KEY_RE.findall(counted):
            if tok == "if":
                depth_if += 1
            elif tok == "fi":
                depth_if -= 1
            elif tok in ("for", "while", "until", "select"):
                depth_loop += 1
            elif tok == "done":
                depth_loop -= 1
            elif tok == "case":
                depth_case += 1
            elif tok == "esac":
                depth_case -= 1

        if depth_if < 0:
            problems.append(f"行{n}: 多余的 fi")
            depth_if = 0
        if depth_loop < 0:
            problems.append(f"行{n}: 多余的 done")
            depth_loop = 0
        if depth_case < 0:
            problems.append(f"行{n}: 多余的 esac")
            depth_case = 0

        if balanced.count('"') % 2:
            problems.append(f"行{n}: 双引号未配平 -> {raw.strip()[:70]}")

    if depth_if:
        problems.append(f"if 未闭合，缺 {depth_if} 个 fi")
    if depth_loop:
        problems.append(f"循环未闭合，缺 {depth_loop} 个 done")
    if depth_case:
        problems.append(f"case 未闭合，缺 {depth_case} 个 esac")
    return problems


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--workflows", default=os.path.join(".github", "workflows"))
    args = ap.parse_args()

    if not os.path.isdir(args.workflows):
        print(f"找不到目录: {args.workflows}", file=sys.stderr)
        return 2

    files = sorted(f for f in os.listdir(args.workflows)
                   if f.endswith((".yml", ".yaml")))
    if not files:
        print(f"{args.workflows} 下没有 workflow 文件", file=sys.stderr)
        return 2

    total = bad = 0
    for fn in files:
        path = os.path.join(args.workflows, fn)
        try:
            doc = yaml.safe_load(open(path, encoding="utf-8"))
        except Exception as e:
            print(f"FAIL  {fn}: YAML 解析失败: {e}")
            bad += 1
            continue

        for job_name, job in (doc.get("jobs") or {}).items():
            for i, step in enumerate(job.get("steps") or []):
                script = step.get("run")
                if not script:
                    continue
                total += 1
                name = step.get("name") or f"step#{i}"
                probs = check_script(script)
                if probs:
                    bad += 1
                    print(f"FAIL  {fn} :: {job_name} :: {name}")
                    for p in probs[:6]:
                        print(f"        {p}")
                else:
                    print(f"OK    {fn:28s} :: {name}")

    print("-" * 72)
    print(f"共校验 {total} 个 run 块，发现结构问题 {bad} 个")
    if bad:
        print("注意：本检查只覆盖结构（配对/引号），不等价于 bash -n。")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
