#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
rbt3 意图+槽位微脑 · A++ 一键脚本（下载基座 → 造数据 → 微调 → ONNX INT8 → 入包）
================================================================================

对应方案 A++ 的「战术认知微脑」。核心判别式思路（零坐标幻觉）：
  * 模型**不自由生成**，只预测受约束的槽位类别（意图8+1类 / 目标词表 /
    时间24×12桶 / 坐标10×10桶取桶心 / 兵力对数桶 / 角色多标签 / 预案5类）；
  * 拼出的 DSL 一律过 validate_dsl，不合法就判 UNKNOWN —— 端侧 IntentSlotModel 同规则。

为什么选 rbt3（hfl/rbt3, 3层中文RoBERTa）而不是英文 TinyBERT 或生成式 SLM：
  * 率土是中文黑话军令，英文 TinyBERT 不适配；
  * 生成式端侧 SLM（Gemma/Qwen-0.5B+）单个就 ~350MB，**爆掉 185MB 预算**且会幻觉误操作，
    与"关键业务 fail-closed"冲突。rbt3 微调 + INT8 后 ~10-20MB，判别式可控，才是本项目的正解。

本脚本把三件散活串成一条命令：
  1. `huggingface_hub` 拉 hfl/rbt3（走镜像），确保 config/vocab 齐（端侧拼串要 vocab.txt）；
  2. 没有 `--data` 就调 `tools/p1/gen_intent_slot_data.py` 合成军令语料（含负样本，保零幻觉）；
  3. 调 `tools/p1/train_intent_slot.py` 微调 → 导出 → INT8，落 `assets/models/intent_slot_zh.onnx`
     + `intent_slot_vocab.txt`。

诚实边界：App 侧 IntentSlotModel 已接入——**有权重即真推理，缺权重自动回落 EdgeSlmEngine 正则**。
本脚本不产出假权重；训练失败就失败。

用法
----
    python tools/a_plus_plus/train_intent_slot_rbt3.py                 # 全自动（合成数据）
    python tools/a_plus_plus/train_intent_slot_rbt3.py --data my.jsonl --epochs 12   # 用真实标注
    # 国内网络：export HF_ENDPOINT=https://hf-mirror.com （本脚本会替你兜默认）

退出码：0=成功；1=训练/校验失败；2=缺依赖/基座。
"""

import argparse
import os
import shutil
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
P1 = os.path.join(REPO, "tools", "p1")
GEN_DATA = os.path.join(P1, "gen_intent_slot_data.py")
TRAIN = os.path.join(P1, "train_intent_slot.py")
ASSETS_MODELS = os.path.join(REPO, "client", "app", "src", "main", "assets", "models")
HF_ID = "hfl/rbt3"


def log(m):
    print(m, flush=True)


def die(code, m):
    log("❌ " + m)
    sys.exit(code)


def ensure_mirror():
    if not os.environ.get("HF_ENDPOINT"):
        os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
        log("🌗 默认走镜像 HF_ENDPOINT=%s" % os.environ["HF_ENDPOINT"])


def download_base(work):
    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        die(2, "未安装 huggingface_hub：pip install huggingface_hub")
    ensure_mirror()
    log("⬇ snapshot_download(%s) ..." % HF_ID)
    try:
        return snapshot_download(repo_id=HF_ID, local_dir=work, local_dir_use_symlinks=False)
    except Exception as exc:
        die(2, "下载 rbt3 基座失败：%s\n   可手动放好目录后用 --base 指定。" % exc)


def synth_data(out, count):
    cmd = [sys.executable, GEN_DATA, "--out", out, "--count", str(count)]
    log("$ %s" % " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    sys.stdout.write(r.stdout or "")
    if r.returncode != 0:
        die(1, "合成军令语料失败：%s" % (r.stderr or "")[-600:])
    return out


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=None, help="rbt3 本地目录；缺省则自动下载")
    ap.add_argument("--data", default=None, help="真实标注 jsonl；缺省则合成")
    ap.add_argument("--count", type=int, default=20000, help="合成样本数（--data 缺省时生效）")
    ap.add_argument("--epochs", type=int, default=8)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--work", default=os.path.join(REPO, ".a_plus_plus", "rbt3"))
    ap.add_argument("--out", default=os.path.join(ASSETS_MODELS, "intent_slot_zh.onnx"))
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    base = args.base or download_base(os.path.join(args.work, "rbt3"))
    if not os.path.isdir(base):
        die(2, "基座目录无效：%s" % base)

    data = args.data or synth_data(os.path.join(args.work, "intent_slot_train.jsonl"), args.count)
    data = data if os.path.isabs(data) else os.path.join(REPO, data)
    if not os.path.isfile(data):
        die(2, "训练数据不存在：%s" % data)

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    vocab_out = os.path.join(os.path.dirname(args.out), "intent_slot_vocab.txt")

    cmd = [sys.executable, TRAIN, "--base", base, "--data", data,
           "--out", args.out, "--vocab-out", vocab_out,
           "--epochs", str(args.epochs), "--batch", str(args.batch)]
    log("$ %s" % " ".join(cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace")
    sys.stdout.write(r.stdout or "")
    sys.stderr.write((r.stderr or "")[-800:])
    if r.returncode != 0:
        die(1, "train_intent_slot.py 退出码 %d，未产出成品。" % r.returncode)

    if not (os.path.isfile(args.out) and os.path.getsize(args.out) > 1024 * 1024):
        die(1, "产物缺失或过小：%s" % args.out)

    # 端侧 IntentSlotModel 构 input_ids 需要 rbt3 的分词词表（与目标词表是两回事）：
    token_vocab_src = os.path.join(base, "vocab.txt")
    token_vocab_dst = os.path.join(os.path.dirname(args.out), "intent_slot_token_vocab.txt")
    if not os.path.isfile(token_vocab_src):
        die(1, "基座目录缺 vocab.txt，无法产出端侧分词词表 intent_slot_token_vocab.txt。")
    shutil.copy2(token_vocab_src, token_vocab_dst)

    log("\n✅ 意图+槽位微脑已入包：\n   %s (%d 字节)\n   %s\n   %s"
        % (args.out, os.path.getsize(args.out), vocab_out, token_vocab_dst))
    log("   App 侧 IntentSlotModel 会优先用它做真推理；无它时回落 EdgeSlmEngine 正则（不受影响）。")
    log("   下一步：git add 这两个文件 → 推 CI。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
