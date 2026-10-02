#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键回归：把项目所有静态校验器串成一条命令
==========================================

背景
----
本机没有 JDK / Android SDK / NDK，也无法访问外网，因此 `./gradlew` 跑不起来。
作为替代，这个会话里逐步积累了若干**静态校验器与实测脚本**：

  1. `verify_refs.py`              资源/成员/findViewById/领域不变量 一致性
  2. `verify_refs.py --selftest`   上述规则的反例自测
  3. `check_native_ocr_build.py`   native OCR 构建的前置条件（include/链接库/JNI 签名）
  4. `selftest_native_ocr_check.py` 上述校验器的反例自测（注入已知缺陷）
  5. `check_ci_shell.py`           workflow 的 shell 结构
  6. `selftest_check_ci_shell.py`  上述校验器的反例自测
  7. `selftest_license_token.py`   授权凭证判别逻辑自测
  8. `validate_scene_fingerprint.py` 场景指纹算法在真机截图上的实测（需要截图）

散着敲这些命令既容易漏，也没法作为"可交付的验证入口"。本脚本把它们串起来，
并可直接在 CI 里跑（新增的 `.github/workflows/verify.yml` 就是这么用的）。

设计要点
--------
* **不启子进程**：全部用 importlib 在当前进程内调用各校验器的 `main()`，
  避免管道相关的平台限制，也便于统一捕获输出。
* **截图类检查优雅跳过**：真机截图不可能进仓库，因此 `validate_scene_fingerprint`
  在没有提供截图时**跳过并说明**，而不是失败。
  通过 `--fingerprint-map` 或环境变量 `STZB_FP_MAP` / `STZB_FP_VARIANT` / `STZB_FP_OTHER` 提供；
  加 `--strict` 可让"缺截图"也算失败。

用法
----
    python tools/run_all_checks.py
    python tools/run_all_checks.py --strict
    python tools/run_all_checks.py --fingerprint-map D:\\shots\\map.jpg --fingerprint-other D:\\shots\\app.jpg
    python tools/run_all_checks.py --only verify_refs check_ci_shell

退出码：0 = 全部必需检查通过；1 = 有必需检查失败。
"""

import argparse
import contextlib
import importlib.util
import io
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def _checker(name):
    return os.path.join(HERE, name)


def run_checker_inprocess(name, argv):
    """在当前进程内加载并调用校验器的 main()，返回 (rc, 输出)。"""
    path = _checker(name)
    if not os.path.isfile(path):
        return 3, f"找不到校验器: {path}"

    module_name = "run_all_checks__" + name.replace(".", "_")
    spec = importlib.util.spec_from_file_location(module_name, path)
    mod = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(mod)  # 先把模块跑起来（定义 main）
    except Exception as e:
        return 3, f"[加载失败] {type(e).__name__}: {e}"

    buf = io.StringIO()
    old_argv = sys.argv
    sys.argv = [path] + list(argv)
    rc = 0
    try:
        with contextlib.redirect_stdout(buf):
            rc = mod.main()
    except SystemExit as e:
        rc = e.code if isinstance(e.code, int) else 1
    except Exception as e:
        rc = 3
        buf.write(f"\n[执行异常] {type(e).__name__}: {e}\n")
    finally:
        sys.argv = old_argv
    return (rc or 0), buf.getvalue()


def build_checks(args):
    checks = [
        {
            "id": "refs",
            "title": "资源/成员/findViewById/领域不变量 一致性",
            "script": "verify_refs.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "refs-selftest",
            "title": "领域不变量规则的反例自测",
            "script": "verify_refs.py",
            "argv": ["--selftest"],
            "required": True,
        },
        {
            "id": "native-ocr",
            "title": "native OCR 构建前置条件",
            "script": "check_native_ocr_build.py",
            "argv": ["--root", os.path.join("client", "app", "src", "main")],
            "required": True,
        },
        {
            "id": "native-ocr-selftest",
            "title": "native 校验器的反例自测（注入缺陷）",
            "script": "selftest_native_ocr_check.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "ci-shell",
            "title": "CI shell 结构",
            "script": "check_ci_shell.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "ci-shell-selftest",
            "title": "CI shell 校验器的反例自测",
            "script": "selftest_check_ci_shell.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "license-token",
            "title": "授权凭证判别逻辑自测",
            "script": "selftest_license_token.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "coord-math",
            "title": "坐标变换数学验证（画布等比/标定还原/往返一致）",
            "script": "validate_coordinate_math.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "timing-math",
            "title": "压秒时间数学验证（公式恒等式/结构性迟到/阈值自洽）",
            "script": "validate_timing_math.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "decision-logic",
            "title": "决策逻辑验证（守军评级性质 + 选队门槛，枚举输入空间）",
            "script": "validate_decision_logic.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "watchdog-logic",
            "title": "看门狗自愈验证（状态空间枚举 + 弹窗按键顺序）",
            "script": "validate_watchdog_logic.py",
            "argv": [],
            "required": True,
        },
        {
            "id": "acceptance-selftest",
            "title": "验收取证脚本的 APK 判定逻辑自测（合成 APK）",
            "script": "selftest_acceptance_check.py",
            "argv": [],
            "required": True,
        },
    ]

    fp_map = args.fingerprint_map or os.environ.get("STZB_FP_MAP")
    fp_variant = list(args.fingerprint_variant) + (
        [os.environ["STZB_FP_VARIANT"]] if os.environ.get("STZB_FP_VARIANT") else []
    )
    fp_other = list(args.fingerprint_other) + (
        [os.environ["STZB_FP_OTHER"]] if os.environ.get("STZB_FP_OTHER") else []
    )

    fp_argv = []
    if fp_map:
        fp_argv += ["--map", fp_map]
        for v in fp_variant:
            fp_argv += ["--variant", v]
        for o in fp_other:
            fp_argv += ["--other", o]

    checks.append(
        {
            "id": "scene-fingerprint",
            "title": "场景指纹算法实测（需要真机截图）",
            "script": "validate_scene_fingerprint.py",
            "argv": fp_argv,
            "required": bool(fp_map) or args.strict,
            "skip_reason": None if fp_map else (
                "未提供真机截图。用 --fingerprint-map <大地图截图> "
                "或环境变量 STZB_FP_MAP 提供；真机截图不适合进仓库，故默认跳过。"
            ),
        }
    )
    return checks


def preflight_python_syntax():
    """
    先给 tools/ 下所有 Python 文件做一次语法检查。

    为什么值得单独做：这一路我在中文提示串里嵌双引号犯过**四次**
    （`print("…"从源码提炼规则"…")`），每次都是某个检查跑到一半才崩。
    预检把这类错误一次性、清楚地暴露出来，而不是让它伪装成"某个检查失败了"。
    """
    import ast
    bad = []
    for name in sorted(os.listdir(HERE)):
        if not name.endswith(".py"):
            continue
        path = os.path.join(HERE, name)
        try:
            with open(path, "r", encoding="utf-8") as fh:
                ast.parse(fh.read())
        except SyntaxError as e:
            bad.append((name, e.lineno, e.msg))
    if bad:
        print("❌ 预检失败：tools/ 下有 Python 文件存在语法错误")
        for name, lineno, msg in bad:
            print(f"   {name}:{lineno}  {msg}")
        print("   提示：中文提示串里不要直接嵌双引号，用「」代替。")
        return False
    print(f"预检通过：tools/ 下 {sum(1 for n in os.listdir(HERE) if n.endswith('.py'))} "
          f"个 Python 文件语法正确")
    return True


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true",
                    help="把'需要外部输入而被跳过'的检查也视为失败")
    ap.add_argument("--only", nargs="*", default=None, help="只跑指定 id 的检查")
    ap.add_argument("--fingerprint-map", default=None, help="大地图基准截图路径")
    ap.add_argument("--fingerprint-variant", action="append", default=[],
                    help="同 UI 的另一张截图（期望命中）")
    ap.add_argument("--fingerprint-other", action="append", default=[],
                    help="不同 UI 的截图（期望不命中）")
    args = ap.parse_args()

    os.chdir(REPO)

    if not preflight_python_syntax():
        return 1

    checks = build_checks(args)
    if args.only:
        checks = [c for c in checks if c["id"] in set(args.only)]
        if not checks:
            print(f"没有匹配的检查 id: {args.only}", file=sys.stderr)
            return 2

    print("=" * 78)
    print("率土全能管家 · 一键静态回归")
    print("=" * 78)
    print(f"仓库根目录: {REPO}")
    print(f"检查项数量: {len(checks)}" + ("（严格模式）" if args.strict else ""))
    print("-" * 78)

    results = []
    for c in checks:
        if c.get("skip_reason"):
            # 注意：这里必须区分"可选跳过"与"严格模式下跳过即失败"。
            # 第一版只打印 SKIP，完全没看 required 字段，
            # 结果 --strict 在文档里承诺的行为根本没有实现。
            if c["required"]:
                results.append((c, False, c["skip_reason"], 0.0))
                print(f"FAIL | {c['id']:22s} | {c['title']}")
                print(f"       {c['skip_reason']}")
                print("       （--strict：跳过视为失败）")
            else:
                results.append((c, None, c["skip_reason"], 0.0))
                print(f"SKIP | {c['id']:22s} | {c['title']}")
                print(f"       {c['skip_reason']}")
            continue

        t0 = time.time()
        rc, out = run_checker_inprocess(c["script"], c["argv"])
        dt = time.time() - t0
        ok = (rc == 0)
        results.append((c, ok, out, dt))
        print(f"{'PASS' if ok else 'FAIL'} | {c['id']:22s} | {c['title']}  ({dt:.1f}s)")
        if not ok:
            tail = [l for l in out.splitlines() if l.strip()][-12:]
            for l in tail:
                print(f"       {l}")

    print("-" * 78)
    failed = [r for r, ok, _, _ in results if ok is False]
    skipped = [r for r, ok, _, _ in results if ok is None]

    print(f"通过 {sum(1 for _, ok, _, _ in results if ok is True)} / "
          f"失败 {len(failed)} / 跳过 {len(skipped)}")

    if skipped:
        print("被跳过的检查（不算失败，但请知悉其未被验证）：")
        for r in skipped:
            print(f"  - {r['id']}: {r['title']}")

    if failed:
        print("\n失败项:")
        for r in failed:
            print(f"  - {r['id']}: {r['title']}")
        print("\n结论: 存在失败项，请先修复。")
        return 1

    print("\n结论: 全部必需检查通过。")
    print("注意: 这些都是**静态**检查，不能替代真实的 NDK/Gradle 编译与真机验证。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
