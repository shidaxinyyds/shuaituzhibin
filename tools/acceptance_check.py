#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
验收取证脚本：把「构建产物」变成可直接判定的事实
================================================

## 为什么需要它
我在本机既不能编译、也没有设备，因此"识别到底通没通"始终只能靠推理。
但**APK 本身是 ZIP**——不需要设备，就能从产物里判定几件关键事实：

  1. **是否真的编译出来了**（有没有 APK、多大、构建时间）；
  2. **OCR 是"真引擎"还是"空桩"**：真实分支会编译 ocr_lite，
     产物里 `libRapidOcr.so` 的体积与依赖库会明显不同；
  3. **模型资产是否真的在包里**（PP-OCRv3 的 param/bin 是否齐全、体积是否合理）；
  4. **是否存在伪造的权重**（早期 CI 会用随机字节生成假 .bin；
     真 GGUF 以 `GGUF` 魔数开头，伪造的则以一段 ASCII 名字开头）。

有设备时（adb 可用）再补充一部分：抓 logcat 里的关键自证行。

## 用法
    python tools/acceptance_check.py
    python tools/acceptance_check.py --apk path/to/app-debug.apk
    python tools/acceptance_check.py --adb          # 额外尝试 adb / logcat

输出同时写入 `acceptance_report.txt`（在仓库根目录），可直接贴回给我。
"""

import argparse
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# PP-OCRv3 目标资产（真实项目里位于 assets/ 根）
OCR_ASSETS = [
    "ch_PP-OCRv3_det_infer.param",
    "ch_PP-OCRv3_det_infer.bin",
    "ch_PP-OCRv3_rec_infer.param",
    "ch_PP-OCRv3_rec_infer.bin",
    "ch_ppocr_mobile_v2.0_cls_infer.param",
    "ch_ppocr_mobile_v2.0_cls_infer.bin",
    "ppocr_keys_v1.txt",
]

# 空桩 .so 与真实 .so 的体量分界（经验值：空桩只有几个 KB 的胶水代码）
STUB_SO_BYTES = 100 * 1024

# 早期 CI 用随机字节伪造权重时使用的 ASCII 前缀（真 GGUF 以 b"GGUF" 开头）
FAKE_ASSET_PREFIXES = [
    b"SMOLM2", b"SMOLLM2", b"FAKE", b"PLACEHOLDER", b"DUMMY",
]

# 真机 logcat 探针：(说明, 用于匹配的**日志原文片段**)
#
# ⚠️ 这些片段必须是源码里**真实存在**的字符串。第一版我写的
# `"native OCR 不可用"` 恰好命中了 Kotlin 侧消息，但漏掉了 native 侧
# 「native OCR 未编译」这条**更直接的"空桩在包里"证据**；
# 而另一条 `"无人托管已启动"` 则**根本不存在**（"无人托管"只出现在注释里）。
# 现在这份表由 `verify_probes_against_sources()` 逐个到源码里核对，
# 核对结果会写进报告；任何探针词失效都会在报告里点名，不会静默过期。
LOG_PROBES = [
    ("OCR 引擎就绪（真引擎）",          "RapidOCR 引擎初始化成功"),
    ("OCR 空桩（native 侧直接证据）",   "native OCR 未编译"),
    ("OCR 不可用（Kotlin 侧）",         "native OCR 不可用"),
    ("坐标自检通过",                    "坐标自检通过"),
    ("坐标自检失败",                    "坐标自检失败"),
    ("世界坐标自检通过",                "世界坐标自检通过"),
    ("捕获面创建成功",                  "创建成功"),
    ("捕获面按几何重建",                "按新尺寸重建捕获面"),
    ("UI 锚点表",                       "UI 锚点（画布"),
    ("语义按键定位成功",                "点击语义按键"),
    ("按键定位失败原因",                "未能定位按键"),
    ("模板匹配定位按键",                "模板匹配定位按键"),
    ("场景指纹判定为大地图",            "场景指纹判定为大地图"),
    ("按键模板学习跳过",                "学习按键模板"),
    ("模型资产诊断（按 TAG）",          "ModelAssetManager"),
    ("定时任务（按 TAG）",              "ScheduledTaskManager"),
    ("无人托管 / 托管循环（按 TAG）",   "AutoPilot"),
    ("压秒迟到告警",                    "晚打"),
    ("识别健康度（按 TAG）",            "RecognitionHealth"),
]

_SOURCE_EXTS = (".kt", ".cpp", ".h")


def verify_probes_against_sources():
    """
    逐个确认每个探针词都能在 `client/app/src/main` 下的源码里找到。

    这是**无需设备**就能做的漂移防线：日志文案一旦改名，这里立刻会点名，
    而不是让你在真机上对着一个永远不会出现的字符串白等。
    """
    root = os.path.join(REPO, "client", "app", "src", "main")
    blobs = []
    for dirpath, _, filenames in os.walk(root):
        for f in filenames:
            if f.endswith(_SOURCE_EXTS):
                try:
                    with open(os.path.join(dirpath, f), "r", encoding="utf-8",
                              errors="replace") as fh:
                        blobs.append(fh.read())
                except Exception:
                    pass
    all_text = "\n".join(blobs)
    missing = [needle for _label, needle in LOG_PROBES if needle not in all_text]
    return missing, len(blobs)


class Report:
    def __init__(self):
        self.lines = []
        self.facts = {}

    def add(self, text=""):
        print(text)
        self.lines.append(text)

    def fact(self, key, value):
        self.facts[key] = value


def find_apks(root):
    found = []
    for dirpath, _, filenames in os.walk(root):
        if os.sep + ".git" in dirpath:
            continue
        for f in filenames:
            if f.endswith(".apk"):
                p = os.path.join(dirpath, f)
                found.append((os.path.getmtime(p), p))
    found.sort(reverse=True)
    return [p for _, p in found]


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024.0


def rel_to_repo(path):
    """
    尽量给出相对路径；**跨盘符时必须回退为绝对路径**。

    为什么：Windows 上 `os.path.relpath` 在起点与终点不同盘符时会抛 `ValueError`。
    而"仓库在 D 盘、APK 从 C 盘某处指定"是完全正常的使用方式——
    这个 bug 是本文件的自测（合成 APK 建在临时目录）当场抓出来的。
    """
    try:
        return os.path.relpath(path, REPO)
    except ValueError:
        return path


def inspect_apk(rep, apk_path):
    size = os.path.getsize(apk_path)
    rep.add(f"APK      : {rel_to_repo(apk_path)}")
    rep.add(f"体积     : {human(size)}（{size} 字节）")
    rep.fact("apk_path", rel_to_repo(apk_path))
    rep.fact("apk_bytes", size)

    try:
        zf = zipfile.ZipFile(apk_path)
    except Exception as e:
        rep.add(f"❌ 无法作为 ZIP 打开: {e}")
        return

    names = zf.namelist()
    rep.add(f"条目数   : {len(names)}")
    rep.add("")

    # ---- 1) native 库 ----
    rep.add("── native 库 ──")
    libs = {}
    for n in names:
        if n.startswith("lib/") and n.endswith(".so"):
            try:
                libs[n] = zf.getinfo(n).file_size
            except Exception:
                libs[n] = -1
    if not libs:
        rep.add("  （没有任何 .so —— 若项目本该有 native 库，这本身就是问题）")
    abis = sorted({n.split("/")[1] for n in libs if len(n.split("/")) > 2})
    rep.add(f"  ABI: {abis if abis else '（无）'}")
    for n in sorted(libs):
        rep.add(f"  {n:<52} {human(libs[n])}")

    rapid = [n for n in libs if n.endswith("libRapidOcr.so")]
    has_ncnn = any(n.endswith("libncnn.so") for n in libs)
    has_opencv = any("opencv" in os.path.basename(n) for n in libs)

    rep.add("")
    rep.add("── OCR 判定（不看设备，只看产物）──")
    if not rapid:
        rep.add("  ❌ 没有 libRapidOcr.so —— native OCR 层根本没进包")
        rep.fact("ocr_verdict", "no-lib")
    else:
        biggest = max(libs[n] for n in rapid)
        rep.add(f"  libRapidOcr.so 最大体积: {human(biggest)}")
        rep.add(f"  libncnn.so 存在        : {'是' if has_ncnn else '否'}")
        rep.add(f"  OpenCV 库存在          : {'是' if has_opencv else '否'}")
        rep.add(f"  判定阈值（空桩上限）   : {human(STUB_SO_BYTES)}")
        if biggest < STUB_SO_BYTES:
            rep.add("  ⇒ 判定：**空桩**（体量与只含胶水代码的空桩一致）")
            rep.fact("ocr_verdict", "stub")
        elif has_ncnn or has_opencv:
            rep.add("  ⇒ 判定：**很可能是真引擎**（体积够大，且 ncnn/OpenCV 在包内）")
            rep.fact("ocr_verdict", "likely-real")
        else:
            rep.add("  ⇒ 判定：**不确定**——体积够大，但既没看到 ncnn 也没看到 OpenCV，")
            rep.add("            也可能是静态链接进去了。请以真机 logcat 为准。")
            rep.fact("ocr_verdict", "unknown")

    # ---- 2) 模型资产 ----
    rep.add("")
    rep.add("── 模型资产（PP-OCRv3）──")
    present, missing = [], []
    for want in OCR_ASSETS:
        hit = [n for n in names if n.endswith("/" + want) or n == "assets/" + want
               or n.endswith(want)]
        if hit:
            sz = zf.getinfo(hit[0]).file_size
            present.append((want, sz))
            rep.add(f"  ✓ {want:<44} {human(sz)}")
        else:
            missing.append(want)
            rep.add(f"  ✗ {want:<44} 缺失")
    rep.fact("ocr_assets_present", len(present))
    rep.fact("ocr_assets_missing", len(missing))
    if missing:
        rep.add(f"  ⇒ 有 {len(missing)}/{len(OCR_ASSETS)} 个 OCR 资产缺失；")
        rep.add("     缺任何一个都会让引擎初始化失败（而不是降级）。")

    # ---- 3) 伪造权重探测 ----
    rep.add("")
    rep.add("── 伪造权重探测 ──")
    suspicious = []
    big_assets = [n for n in names
                  if n.startswith("assets/") and n.lower().endswith(
                      (".bin", ".gguf", ".tflite", ".onnx", ".param"))]
    for n in big_assets:
        try:
            with zf.open(n) as fh:
                head = fh.read(16)
        except Exception:
            continue
        if head.startswith(b"GGUF") or head.startswith(b"\x89PNG"):
            continue
        if any(head.startswith(p) for p in FAKE_ASSET_PREFIXES):
            suspicious.append((n, head))
            rep.add(f"  ⚠️ {n}\n      头部为 ASCII 名字而非模型魔数: {head!r}")
    if not suspicious:
        rep.add("  ✓ 未发现「以 ASCII 名字开头的伪模型资产」")
    rep.fact("fake_assets", len(suspicious))
    rep.add("")
    rep.add("  诚实说明：这条只能识别**早期 CI 那种随机字节假权重**的写法。")
    rep.add("  一个真 GGUF 以 b'GGUF' 开头；一个真 ncnn .bin 没有统一魔数，")
    rep.add("  因此本项**不能**证明模型内容正确，只能证明「不是那种假货」。")


def try_adb(rep, package="com.stzb.assistant"):
    rep.add("")
    rep.add("── adb / logcat（可选）──")

    def run(cmd):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
            return r.returncode, (r.stdout or "") + (r.stderr or "")
        except FileNotFoundError:
            return 127, "adb 未找到"
        except Exception as e:
            return 1, str(e)

    rc, out = run(["adb", "devices"])
    if rc != 0:
        rep.add(f"  adb 不可用：{out.strip()[:120]}")
        rep.add("  （没有设备也能用本脚本的其它部分，那一部分才是关键）")
        rep.fact("adb", "unavailable")
        return
    rep.add("  设备列表：")
    for line in out.strip().splitlines()[1:]:
        if line.strip():
            rep.add(f"    {line.strip()}")
    rc, pid = run(["adb", "shell", "pidof", package])
    rep.add(f"  应用进程: {pid.strip() if rc == 0 and pid.strip() else '（未在运行）'}")
    rep.fact("app_running", bool(rc == 0 and pid.strip()))

    rc, log = run(["adb", "logcat", "-d", "-t", "4000"])
    if rc != 0:
        rep.add(f"  读取 logcat 失败：{log.strip()[:120]}")
        return
    rep.add("  关键自证行命中情况（探针词均已与源码核对，见下节）：")
    log_lines = log.splitlines()
    hits_map = {}
    for label, needle in LOG_PROBES:
        hit = [l for l in log_lines if needle in l]
        hits_map[label] = len(hit)
        mark = "✓" if hit else "·"
        rep.add(f"    {mark} {label:<26} {len(hit)} 条")
        if hit:
            rep.add(f"        {hit[-1].strip()[:150]}")
    rep.fact("logcat_probes", hits_map)


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=None, help="指定 APK 路径；缺省则在仓库里自动查找")
    ap.add_argument("--adb", action="store_true", help="额外尝试 adb/logcat（需要设备）")
    ap.add_argument("--package", default="com.stzb.assistant")
    args = ap.parse_args()

    rep = Report()
    rep.add("=" * 78)
    rep.add("率土全能管家 · 验收取证报告")
    rep.add("=" * 78)

    apk = args.apk
    if apk and not os.path.isfile(apk):
        rep.add(f"❌ 指定的 APK 不存在: {apk}")
        return 2

    if not apk:
        found = find_apks(REPO)
        if not found:
            rep.add("")
            rep.add("❌ 仓库里没有找到任何 .apk —— 也就是说**还没有构建过**，或者产物没留在仓库里。")
            rep.add("")
            rep.add("请先构建，例如：")
            rep.add("    cd client && ./gradlew assembleDebug")
            rep.add("    （或在 GitHub Actions 里手动触发 build_apk_with_ocr.yml 并下载 artifact）")
            rep.add("")
            rep.add("构建产物通常位于：client/app/build/outputs/apk/**/*.apk")
            rep.add("然后用 `python tools/acceptance_check.py --apk <路径>` 指定它。")
            rep.fact("apk_found", False)
        else:
            if len(found) > 1:
                rep.add(f"（找到 {len(found)} 个 APK，取最新的那个；其余可用 --apk 指定）")
                for p in found[1:6]:
                    rep.add(f"    - {os.path.relpath(p, REPO)}")
                rep.add("")
            apk = found[0]
            rep.fact("apk_found", True)

    if apk:
        inspect_apk(rep, apk)

    # ---- 探针与源码核对（不需要设备，因此总是执行）----
    rep.add("")
    rep.add("── 真机探针词与源码核对（无需设备）──")
    missing_probes, n_src = verify_probes_against_sources()
    rep.add(f"  已扫描 {n_src} 个源码文件（.kt/.cpp/.h）")
    if missing_probes:
        rep.add(f"  ❌ 有 {len(missing_probes)} 个探针词在源码里**找不到**：")
        for m in missing_probes:
            rep.add(f"       - {m}")
        rep.add("     这意味着：报告里会让你去找一行永远不会出现的日志。")
        rep.add("     修法是把探针词改成源码里的真实文案，而不是反过来改日志。")
    else:
        rep.add(f"  ✓ 全部 {len(LOG_PROBES)} 个探针词都能在源码里找到对应文案")
    rep.fact("probes_missing", len(missing_probes))

    if args.adb:
        try_adb(rep, args.package)

    rep.add("")
    rep.add("=" * 78)
    rep.add("这份报告能证明什么 / 不能证明什么")
    rep.add("=" * 78)
    rep.add("  能：产物是否存在、OCR 是空桩还是真引擎、模型资产齐不齐、")
    rep.add("      是否含早期 CI 那种伪造权重。")
    rep.add("  不能：坐标在真机上准不准、模板匹配命中率、压秒实际误差、")
    rep.add("      标定区域是否合适——这些必须有设备（加 --adb 可以补充一部分）。")

    out_path = os.path.join(REPO, "acceptance_report.txt")
    try:
        with open(out_path, "w", encoding="utf-8") as fh:
            fh.write("\n".join(rep.lines) + "\n")
        print()
        print(f"报告已写入: {out_path}")
        print("（把它的内容贴回给我，我就能从「我猜」进入「我改」）")
    except Exception as e:
        print(f"写入报告失败: {e}")

    verdict = rep.facts.get("ocr_verdict")
    if verdict == "stub":
        return 1
    if verdict == "no-lib":
        return 1
    if not rep.facts.get("apk_found", True) and not args.apk:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
