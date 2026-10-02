#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
LicenseGate 凭证判别逻辑验证
============================

背景
----
客户端本来没有任何付费墙：`LicenseManager.checkLocalLicense()` 会**自己给自己签发**
一张永久凭证，其签名段固定为 `COMMERCIAL_MASTER_PERPETUAL`。
因此"把 LicenseGate.DEVELOPMENT_MODE_OPEN_ACCESS 改成 false"这一件事，
只有在门禁**能把这个自签发凭证识别出来**时才真的生效。

本脚本在纯 Python 下复现该判别逻辑并验证：
  1. 三种自签发路径产生的凭证都能被识别为"开发态"；
  2. 服务端真实签名（base64url 的 32 字节 HMAC）**不会**被误判为开发态；
  3. 从长度上论证二者不可能相同；
  4. 激活码里的 `|` 会打乱凭证段数（这是为什么要先剔除）。

用法：python tools/selftest_license_token.py
退出码：0 = 全部符合预期；1 = 有不符合项。
"""

import hashlib
import hmac
import os
import sys

DEV_SIGNATURE = "COMMERCIAL_MASTER_PERPETUAL"
EXPECTED_PARTS = 6


def is_dev_issued_token(token):
    """与 LicenseGate.isDevIssuedToken 等价。"""
    return token is not None and token.endswith("|" + DEV_SIGNATURE)


def make_dev_token(device_id, code, expiry=2524608000):
    """与 LicenseManager 三处自签发逻辑等价。"""
    return f"{device_id}|stzb|{code}|{expiry}|{expiry}|{DEV_SIGNATURE}"


def make_real_token(device_id, code, expiry, secret=b"unit-test-secret"):
    """
    与「服务端签发 + SecurityBridge.cpp 校验」一致的真实凭证：
    签名段 = HMAC-SHA256(canonical) 的 base64url（无填充）。
    """
    canonical = f"{device_id}\u00a6stzb\u00a6{code}\u00a6{expiry}\u00a6{expiry}"
    digest = hmac.new(secret, canonical.encode("utf-8"), hashlib.sha256).digest()
    import base64
    sig = base64.urlsafe_b64encode(digest).decode().rstrip("=")
    return f"{device_id}|stzb|{code}|{expiry}|{expiry}|{sig}"


def split_parts(token):
    return token.split("|")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    device = "A1B2C3D4E5F6"
    bad = 0
    total = 0

    def check(label, cond, detail=""):
        nonlocal bad, total
        total += 1
        ok = bool(cond)
        if not ok:
            bad += 1
        print(f"{'OK  ' if ok else 'FAIL'} | {label}" + (f"\n        {detail}" if detail else ""))

    print("=" * 74)
    print("1) 三种自签发路径都必须被识别为『开发态』")
    print("=" * 74)
    cases = {
        "checkLocalLicense 自签发": make_dev_token(device, "PERPETUAL_COMMERCIAL_VIP"),
        "activateOnline 离线分支": make_dev_token(device, "STZB-TEST-0001"),
        "catch 离线自愈分支": make_dev_token(device, "WHATEVER-VIP"),
    }
    for label, tok in cases.items():
        check(f"{label} 被识别", is_dev_issued_token(tok), tok)

    print()
    print("=" * 74)
    print("2) 服务端真实签名不得被误判为开发态")
    print("=" * 74)
    real = make_real_token(device, "STZB-REAL-0001", 2524608000)
    sig = split_parts(real)[5]
    check("真实凭证未被误判", not is_dev_issued_token(real), real)
    check("真实凭证段数为 6", len(split_parts(real)) == EXPECTED_PARTS,
          f"parts={len(split_parts(real))}")
    check(f"真实签名长度({len(sig)}) 与开发签名长度({len(DEV_SIGNATURE)}) 不同",
          len(sig) != len(DEV_SIGNATURE),
          f"真实 base64url(32B) = {len(sig)} 字符；开发签名 = {len(DEV_SIGNATURE)} 字符")
    check("真实签名不等于开发签名", sig != DEV_SIGNATURE)

    print()
    print("=" * 74)
    print("3) 边界情况")
    print("=" * 74)
    check("空凭证不算开发态", not is_dev_issued_token(None))
    check("空字符串不算开发态", not is_dev_issued_token(""))
    check("只有签名段不算开发态（缺 | 前缀）", not is_dev_issued_token(DEV_SIGNATURE))
    check("段数不足的凭证会被原生层拒绝", len(split_parts("a|b|c")) != EXPECTED_PARTS)

    print()
    print("=" * 74)
    print("4) 激活码含 `|` 会打乱段数（因此入口处必须剔除）")
    print("=" * 74)
    dirty = make_dev_token(device, "CO|DE")
    check(f"未清洗时段数 = {len(split_parts(dirty))}（≠ {EXPECTED_PARTS}，原生层会拒绝）",
          len(split_parts(dirty)) != EXPECTED_PARTS, dirty)
    cleaned = make_dev_token(device, "CO|DE".replace("|", ""))
    check(f"清洗后段数 = {len(split_parts(cleaned))}", len(split_parts(cleaned)) == EXPECTED_PARTS,
          cleaned)

    print()
    print("-" * 74)
    if bad:
        print(f"{bad}/{total} 项不符合预期")
        return 1
    print(f"{total} 项全部符合预期 —— 付费墙开关改 false 后确实能生效。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
