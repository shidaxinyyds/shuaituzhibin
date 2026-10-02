#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/check_ci_shell.py 的自测（反例 + 正例）
============================================

一个"永远报 OK"的校验器和没有校验器一样危险。本脚本用一组预期已知的
正例/反例来验证 check_ci_shell 的判定确实生效。

用例全部写在 Python 里（**不要**放到 PowerShell here-string 里——
`$x` 会被 PowerShell 插值，我第一次自测就是这么被污染的）。

用法：python tools/selftest_check_ci_shell.py
退出码：0 = 全部符合预期；1 = 有不符合项。
"""

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TARGET = os.path.join(HERE, "check_ci_shell.py")

CASES = [
    ("正常 if/for 结构", "if [ -f a ]; then\necho ok\nfi\nfor x in 1 2; do\necho $x\ndone\n", False),
    ("缺 fi", "if [ -f a ]; then\necho ok\n", True),
    ("缺 done", "for x in 1 2; do\necho $x\n", True),
    ("多余 fi", "echo hi\nfi\n", True),
    ("多余 done", "echo hi\ndone\n", True),
    ("双引号未配平", 'echo "hello\n', True),
    ("单引号未配平（不检查，仅记录）", "echo 'hello\n", False),
    ("双引号内的关键字不算数", 'echo "if fi done for while"\n', False),
    ("单引号内的关键字不算数", "echo 'if fi done for while'\n", False),
    ("注释里的关键字不算数", "# if something\n echo ok\n", False),
    ("case 未闭合", "case $x in\n  a) echo a;;\n", True),
    ("case 正常", "case $x in\n  a) echo a;;\nesac\n", False),
    ("嵌套 if 正常", "if a; then\n  if b; then\n    echo x\n  fi\nfi\n", False),
    ("嵌套 if 少一个 fi", "if a; then\n  if b; then\n    echo x\n  fi\n", True),
    ("echo 中文含括号与冒号", 'echo "已定位 ncnn_DIR=$PWD/x（含 中文）"\n', False),
]


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    if not os.path.isfile(TARGET):
        print(f"找不到目标: {TARGET}", file=sys.stderr)
        return 2

    spec = importlib.util.spec_from_file_location("chk", TARGET)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    bad = 0
    for label, script, expect_problem in CASES:
        probs = mod.check_script(script)
        got_problem = bool(probs)
        ok = got_problem == expect_problem
        if not ok:
            bad += 1
        verdict = "OK  " if ok else "FAIL"
        want = "有问题" if expect_problem else "通过"
        print(f"{verdict} | 期望{want}，实际{len(probs)}项 | {label}")
        for p in probs[:3]:
            print(f"        {p}")

    print("-" * 68)
    if bad:
        print(f"{bad} 个用例不符合预期")
        return 1
    print(f"{len(CASES)} 个用例全部符合预期 —— 校验器判定有效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
