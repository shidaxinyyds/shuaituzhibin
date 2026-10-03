#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
发布就绪自检（商业化交付门禁）
================================

## 为什么单独做这一项
前面那些校验器盯的是"代码对不对"，但**发布**要回答的是另一组问题：
  * 付费墙到底开着吗？（开着开发模式就发版 = 零付费墙）
  * 精确闹钟权限声明了吗？（没声明 = 定时任务在息屏后不触发）
  * 崩溃兜底装了吗？（没装 = 常驻进程一崩就无任何线索）
  * 该进包的权重进了吗？（没进 = 能力名不副实）

这些都不是"代码错误"，所以 `verify_refs` 之类永远不会报；
但它们每一条都会直接导致**线上事故或商业损失**。

## 为什么默认不阻断 CI
`LicenseGate.DEVELOPMENT_MODE_OPEN_ACCESS = true` 是**当前刻意保留**的开发态
（在 Supabase 后端部署前翻成 false 会把开发者自己锁在门外）。
若把这条做成硬失败，仓库从今天起 CI 就一直是红的，
而红色 CI 的唯一后果是"所有人开始忽略它"。
因此：默认**只报告**并退出 0；`--strict` 时才把阻断项视为失败——
发布前手动跑一次 `--strict`，此时应当已全部处理完。

用法
----
    python tools/check_release_readiness.py
    python tools/check_release_readiness.py --strict

退出码：0 = 无阻断项（或仅告警）；1 = 存在阻断项且 --strict；2 = 环境问题
"""

import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

MANIFEST = os.path.join("client", "app", "src", "main", "AndroidManifest.xml")
LICENSE_GATE = os.path.join(
    "client", "app", "src", "main", "java", "com", "stzb", "assistant",
    "license", "LicenseGate.kt")
LICENSE_MANAGER = os.path.join(
    "client", "app", "src", "main", "java", "com", "stzb", "assistant",
    "license", "LicenseManager.kt")
APP_KT = os.path.join(
    "client", "app", "src", "main", "java", "com", "stzb", "assistant", "App.kt")
BUILD_GRADLE = os.path.join("client", "app", "build.gradle")
ASSETS_MODELS = os.path.join("client", "app", "src", "main", "assets", "models")

BLOCKERS = []
WARNINGS = []
PASSES = []


def ok(msg):
    PASSES.append(msg)


def warn(msg):
    WARNINGS.append(msg)


def block(msg):
    BLOCKERS.append(msg)


def read(path):
    full = os.path.join(REPO, path)
    if not os.path.isfile(full):
        return None
    with open(full, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read()


def check_exact_alarm_permission():
    t = read(MANIFEST)
    if t is None:
        block("找不到 AndroidManifest.xml，无法核对权限")
        return
    if "SCHEDULE_EXACT_ALARM" in t:
        ok("已声明 SCHEDULE_EXACT_ALARM（精确闹钟 / RTC 唤醒可用）")
    else:
        block(
            "AndroidManifest 未声明 SCHEDULE_EXACT_ALARM："
            "Android 12+ 上 setExactAndAllowWhileIdle 会被系统拒绝，"
            "定时任务在深度息屏后不会准点触发。"
        )


def check_license_gate():
    t = read(LICENSE_GATE)
    if t is None:
        block("找不到 LicenseGate.kt，无法核对付费墙开关")
        return
    m = re.search(r"DEVELOPMENT_MODE_OPEN_ACCESS\s*:\s*Boolean\s*=\s*(true|false)", t)
    if m is None:
        block("在 LicenseGate.kt 中找不到 DEVELOPMENT_MODE_OPEN_ACCESS 开关")
        return
    if m.group(1) == "false":
        ok("付费墙已开启（DEVELOPMENT_MODE_OPEN_ACCESS = false）")
    else:
        block(
            "付费墙处于开发模式（DEVELOPMENT_MODE_OPEN_ACCESS = true）："
            "任何人安装都自动获得旗舰版。商业化发布前必须改为 false，"
            "并先部署 Supabase 后端与卡密库存，否则会把你自己锁在门外。"
        )


def check_supabase_endpoint():
    t = read(LICENSE_MANAGER)
    if t is None:
        block("找不到 LicenseManager.kt，无法核对鉴权端点")
        return
    if "your-supabase-project" in t:
        warn(
            "鉴权端点仍是占位符 your-supabase-project："
            "占位符命中时任意卡密都会被离线放行（这是设计好的开发态容灾）。"
            "发布前必须替换为真实 Supabase 实例地址。"
        )
    else:
        ok("鉴权端点已替换为真实地址")


def check_crash_guard_installed():
    t = read(APP_KT)
    if t is None:
        warn("找不到 App.kt，无法确认崩溃兜底是否安装")
        return
    if "CrashGuard.install" in t:
        ok("已安装全局崩溃兜底 CrashGuard")
    else:
        block(
            "未安装崩溃兜底（App 里没有 CrashGuard.install）："
            "常驻进程一旦崩溃将不留任何可诊断线索，用户只会看到'不知道为什么就不动了'。"
        )


def check_resource_guard_installed():
    t = read(APP_KT)
    if t is None:
        return
    if "ResourceGuard.attach" in t:
        ok("已挂载资源守卫 ResourceGuard")
    else:
        warn("未挂载 ResourceGuard：加载重模型时没有资源水位准入，低内存下有被 LMK 杀掉的风险。")


def check_abi_filter():
    t = read(BUILD_GRADLE)
    if t is None:
        warn("找不到 build.gradle，无法核对 ABI 过滤")
        return
    m = re.search(r"abiFilters\s+([^\n]+)", t)
    if m and "arm64-v8a" in m.group(1):
        ok("已限定单一 ABI（arm64-v8a），原生库不会按 3~4 份重复打包")
    else:
        warn("未限定单一 ABI：原生库会按多架构重复打包，APK 体积会成倍增长。")


def check_minify():
    t = read(BUILD_GRADLE)
    if t is None:
        return
    if re.search(r"minifyEnabled\s+true", t):
        ok("已开启代码混淆/裁剪")
    else:
        warn(
            "minifyEnabled 仍为 false：代码与资源未裁剪，且发布包未做混淆。"
            "若要开启，请先补齐 JNI / Parcelable / 序列化的 keep 规则。"
        )


def check_model_assets():
    if not os.path.isdir(os.path.join(REPO, ASSETS_MODELS)):
        warn("assets/models 目录不存在，业务侧模型权重全部未入包")
        return
    names = set(os.listdir(os.path.join(REPO, ASSETS_MODELS)))
    expect = {
        "bge": ["bge_zh_int8.onnx"],
        "yolo": ["yolov8s_stzb.bin", "yolov8n_stzb.bin"],
        "intent": ["intent_slot_zh.onnx"],
    }
    for label, cands in expect.items():
        if any(c in names for c in cands):
            ok(f"模型资产已入包: {label}")
        else:
            warn(
                f"模型资产缺失: {label}（候选 {cands} 均不存在）——"
                f"对应能力会走降级路径；发布前请按 tools/MODELS_DOWNLOAD_MANIFEST.md 补齐。"
            )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true",
                    help="把阻断项视为失败（发布前应当用这个跑）")
    args = ap.parse_args()

    os.chdir(REPO)
    check_exact_alarm_permission()
    check_license_gate()
    check_supabase_endpoint()
    check_crash_guard_installed()
    check_resource_guard_installed()
    check_abi_filter()
    check_minify()
    check_model_assets()

    print("=" * 78)
    print("发布就绪自检")
    print("=" * 78)
    for m in PASSES:
        print(f"  ✅ {m}")
    for m in WARNINGS:
        print(f"  ⚠️  {m}")
    for m in BLOCKERS:
        print(f"  ⛔ {m}")
    print("-" * 78)
    print(f"通过 {len(PASSES)} / 告警 {len(WARNINGS)} / 阻断 {len(BLOCKERS)}")

    if BLOCKERS:
        print("\n阻断项必须在发布前处理完毕：")
        for m in BLOCKERS:
            print(f"  - {m}")
        if args.strict:
            print("\n结论: 存在发布阻断项（--strict）。")
            return 1
        print("\n结论: 存在发布阻断项（当前为非阻断模式；发布前请用 --strict 复跑）。")
        return 0

    if WARNINGS:
        print("\n结论: 无阻断项，但有告警项，建议发布前确认。")
        return 0
    print("\n结论: 发布就绪。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
