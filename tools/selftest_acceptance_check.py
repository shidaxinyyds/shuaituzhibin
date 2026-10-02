#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
`tools/acceptance_check.py` 的自测（合成 APK）
=============================================

`acceptance_check` 里那段「从 APK 判定 OCR 是真引擎还是空桩」的逻辑，
如果第一次用就出错，那这个工具等于白做。因此这里**构造合成 APK** 来验证它：

  1. **空桩包**：`libRapidOcr.so` 很小、无 ncnn/OpenCV → 必须判为 `stub`
  2. **真引擎包**：`libRapidOcr.so` 很大 + `libncnn.so` + OCR 资产齐全 → 必须判为 `likely-real`
  3. **缺库包**：没有 `libRapidOcr.so` → 必须判为 `no-lib`
  4. **伪造权重包**：资产头部是 ASCII 名字而非模型魔数 → 必须被标出
  5. **模型资产计数**：7 个目标文件里放了几个就要数对几个

不需要设备、不需要真实 APK，APK 就是 ZIP。

用法：python tools/selftest_acceptance_check.py
"""

import contextlib
import importlib.util
import io
import os
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))

_OCR_ASSETS = [
    "ch_PP-OCRv3_det_infer.param",
    "ch_PP-OCRv3_det_infer.bin",
    "ch_PP-OCRv3_rec_infer.param",
    "ch_PP-OCRv3_rec_infer.bin",
    "ch_ppocr_mobile_v2.0_cls_infer.param",
    "ch_ppocr_mobile_v2.0_cls_infer.bin",
    "ppocr_keys_v1.txt",
]


def load_module():
    spec = importlib.util.spec_from_file_location(
        "acceptance_check", os.path.join(HERE, "acceptance_check.py")
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def make_apk(path, *, so_size, with_ncnn, with_assets, fake_asset_head=None):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        if so_size is not None:
            zf.writestr("lib/arm64-v8a/libRapidOcr.so", b"\0" * so_size)
        if with_ncnn:
            zf.writestr("lib/arm64-v8a/libncnn.so", b"\0" * 300_000)
        if with_assets:
            for a in _OCR_ASSETS:
                zf.writestr(f"assets/{a}", b"\0" * 4096)
        if fake_asset_head is not None:
            zf.writestr("assets/models/slm_fake.bin", fake_asset_head + b"\0" * 2048)
        zf.writestr("AndroidManifest.xml", b"\0" * 64)


def inspect(mod, apk_path):
    rep = mod.Report()
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        mod.inspect_apk(rep, apk_path)
    return rep, buf.getvalue()


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    mod = load_module()
    bad = 0
    total = 0

    def check(label, cond, detail=""):
        nonlocal bad, total
        total += 1
        if cond:
            print(f"OK   | {label}" + (f"\n        {detail}" if detail else ""))
        else:
            bad += 1
            print(f"FAIL | {label}" + (f"\n        {detail}" if detail else ""))

    with tempfile.TemporaryDirectory() as tmp:
        # 1) 空桩包
        p1 = os.path.join(tmp, "stub.apk")
        make_apk(p1, so_size=20_000, with_ncnn=False, with_assets=True)
        r1, _ = inspect(mod, p1)
        check("空桩包（.so 仅 20KB、无 ncnn）判为 stub",
              r1.facts.get("ocr_verdict") == "stub",
              f"verdict={r1.facts.get('ocr_verdict')}")

        # 2) 真引擎包
        p2 = os.path.join(tmp, "real.apk")
        make_apk(p2, so_size=900_000, with_ncnn=True, with_assets=True)
        r2, _ = inspect(mod, p2)
        check("真引擎包（.so 900KB + libncnn.so）判为 likely-real",
              r2.facts.get("ocr_verdict") == "likely-real",
              f"verdict={r2.facts.get('ocr_verdict')}")

        # 3) 缺库包
        p3 = os.path.join(tmp, "nolib.apk")
        make_apk(p3, so_size=None, with_ncnn=False, with_assets=False)
        r3, _ = inspect(mod, p3)
        check("没有 libRapidOcr.so 判为 no-lib",
              r3.facts.get("ocr_verdict") == "no-lib",
              f"verdict={r3.facts.get('ocr_verdict')}")

        # 4) 模型资产计数
        check("OCR 资产计数正确（7 个全在）",
              r2.facts.get("ocr_assets_present") == 7
              and r2.facts.get("ocr_assets_missing") == 0,
              f"present={r2.facts.get('ocr_assets_present')} "
              f"missing={r2.facts.get('ocr_assets_missing')}")
        check("缺资产时计数正确（空桩包那份也是 7 个，缺库包 0 个）",
              r3.facts.get("ocr_assets_present") == 0,
              f"present={r3.facts.get('ocr_assets_present')}")

        # 5) 伪造权重探测
        p4 = os.path.join(tmp, "fake.apk")
        make_apk(p4, so_size=900_000, with_ncnn=True, with_assets=True,
                 fake_asset_head=b"SMOLLM2_360M_INT4_GGUF_CORE")
        r4, out4 = inspect(mod, p4)
        check("以 ASCII 名字开头的伪权重必须被标出",
              r4.facts.get("fake_assets") == 1,
              f"fake_assets={r4.facts.get('fake_assets')}")
        check("伪权重告警文本里给出了该文件路径与头部字节",
              "SMOLLM2" in out4 and "slm_fake.bin" in out4,
              "告警内容包含文件名与头部")

        # 6) 真 GGUF 魔数不应被误报
        p5 = os.path.join(tmp, "gguf.apk")
        make_apk(p5, so_size=900_000, with_ncnn=True, with_assets=True,
                 fake_asset_head=b"GGUF")
        r5, _ = inspect(mod, p5)
        check("以 b'GGUF' 开头的真实权重不得被误报",
              r5.facts.get("fake_assets") == 0,
              f"fake_assets={r5.facts.get('fake_assets')}")

    # 7) 探针词必须与源码一致（**活的**漂移防线，不需要设备）
    missing, n_src = mod.verify_probes_against_sources()
    check("全部真机探针词都能在源码里找到（说明探针没有过期）",
          not missing,
          f"扫描 {n_src} 个源码文件；缺失的探针: {missing if missing else '无'}")

    # 8) 反例：故意塞一个不存在的探针词，必须被点名
    mod.LOG_PROBES.append(("故意写错的探针", "这句日志绝对不存在_ZZZ"))
    missing2, _ = mod.verify_probes_against_sources()
    check("故意写错的探针词必须被点出来（否则这条防线是空转）",
          "这句日志绝对不存在_ZZZ" in missing2,
          f"缺失列表: {missing2}")
    mod.LOG_PROBES.pop()

    # 9) 报告里会让你去找的每一行，都必须真的由代码打印
    check("探针表非空且每条都有标签与文案",
          len(mod.LOG_PROBES) >= 10
          and all(isinstance(lbl, str) and isinstance(nd, str) and lbl and nd
                  for lbl, nd in mod.LOG_PROBES),
          f"共 {len(mod.LOG_PROBES)} 条探针")

    print("-" * 72)
    if bad:
        print(f"{bad}/{total} 项不符合预期 —— acceptance_check 的 APK 判定逻辑未通过验证。")
        return 1
    print(f"{total} 项全部符合预期 —— APK 判定逻辑有效（空桩/真引擎/缺库/伪权重 全部识别正确）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
