#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
率土之滨 YOLO26 训练 → ncnn → INT8 一体化脚本（方案 A++ 视觉引擎）
================================================================

为什么是 YOLO26 而不是 YOLOv8：
  * Ultralytics YOLO26（2025-09）是 v8/v11 的直系换代，精度/延迟更优，且
    **官方支持 `format=ncnn` 导出**——本项目 native 侧就是 ncnn 栈（YoloNcnn.cpp）。
  * 关键点：ncnn 无法在图内跑端到端 NMS/topk，因此 Ultralytics 对 ncnn 导出
    **回落到标准 v8/v11 布局** `[1, 4+nc, anchors]`、输入层 `images`、输出层 `output0`。
    这与工程里 YoloNcnn.cpp 的解码**完全兼容**——所以换 YOLO26 不需要改任何 native 代码。

本脚本做的三件事（只做这三件，且失败就说失败，绝不伪造权重）：
  1. **自动取基座**：`YOLO("yolo26s.pt")` 首次加载会由 ultralytics 自动下载官方权重；
     离线/镜像环境可用 `--weights` 指向本地 .pt。取不到就退出码 2，不用随机初始化顶替。
  2. **训练**：在你提供的率土标注集（ultralytics data.yaml 格式）上微调 11 类。
     类别顺序必须与 YoloDetector.DetectionClass 一致（见 tools/train_yolo/data.yaml）。
  3. **导出 ncnn + INT8**：`model.export(format="ncnn", imgsz=640)`；若找到 ncnn 工具链
     （ncnn2table + ncnn2int8），用校准图做训练后量化产出 INT8；找不到则如实保留 fp32
     ncnn 并告警（fp32 能跑、只是更大），**绝不写假字节**。

诚实边界（务必读懂再跑）：
  * 通用 COCO 预训练检不出"出征/驻守/行军红线"等游戏专属类别，**必须用率土截图标注**训练。
  * `--demo` 仅用合成小图把"训练→导出→量化→落位"整条管线跑通一遍，验证环境无误，
    **它产出的模型对真实游戏画面无效**，只用于自检，不可入包当成品。

用法
----
    # 正式：用你标注好的率土数据集训练（推荐）
    python tools/a_plus_plus/train_yolo26.py \
        --data tools/train_yolo/data.yaml --weights yolo26s.pt --epochs 200 --imgsz 640

    # 自检管线（不产出可用成品）
    python tools/a_plus_plus/train_yolo26.py --demo --epochs 2

产物（正式训练）
--------------
    client/app/src/main/assets/models/yolo26s_stzb.param
    client/app/src/main/assets/models/yolo26s_stzb.bin
    （INT8 时另存 *_int8；默认把最终选用的一份写成上面这对名字，供 App 直接加载）

退出码：0=成功；1=训练/校验失败；2=缺基座/依赖/数据集。
"""

import argparse
import os
import shutil
import subprocess
import sys

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS_MODELS = os.path.join(REPO, "client", "app", "src", "main", "assets", "models")

# 与 YoloNcnn.cpp / YoloDetector 对齐的硬约束
IMGSZ = 640
NC = 11
NAMES = ["attack", "defend", "retreat", "confirm", "cancel",
         "mail_alert", "radar_alert", "enemy_tile", "resource_tile",
         "march_redline", "city_gate"]


def log(msg):
    print(msg, flush=True)


def die(code, msg):
    log("❌ " + msg)
    sys.exit(code)


# --------------------------------------------------------------------- 依赖
def ensure_ultralytics():
    try:
        from ultralytics import YOLO  # noqa: F401
        import ultralytics
        return ultralytics.__version__
    except ImportError:
        die(2, "未安装 ultralytics。请先：pip install ultralytics>=8.3  （YOLO26 需要较新版本）")


def find_ncnn_tools(ncnn_bin):
    """返回 (ncnn2table, ncnn2int8) 绝对路径；找不到返回 (None, None)。"""
    dirs = []
    if ncnn_bin:
        dirs.append(ncnn_bin)
    env = os.environ.get("STZB_NCNN_BIN")
    if env:
        dirs.append(env)
    exe = (lambda n: n + ".exe") if os.name == "nt" else (lambda n: n)
    for base in dirs + [os.getcwd()]:
        t = os.path.join(base, exe("ncnn2table"))
        i = os.path.join(base, exe("ncnn2int8"))
        if os.path.isfile(t) and os.path.isfile(i):
            return t, i
    t = shutil.which("ncnn2table")
    i = shutil.which("ncnn2int8")
    if t and i:
        return t, i
    return None, None


# ----------------------------------------------------------------- 合成自检集
def make_demo_dataset(root):
    """造一批极小的合成图 + 标签，仅用于把训练/导出管线跑通；成品无效。"""
    import random
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        die(2, "--demo 需要 Pillow：pip install pillow")
    random.seed(7)
    img_dir = os.path.join(root, "images", "train")
    lbl_dir = os.path.join(root, "labels", "train")
    os.makedirs(img_dir, exist_ok=True)
    os.makedirs(lbl_dir, exist_ok=True)
    for i in range(16):
        im = Image.new("RGB", (IMGSZ, IMGSZ), (30, 30, 40))
        d = ImageDraw.Draw(im)
        cls = i % NC
        cx, cy = random.randint(80, 560), random.randint(80, 560)
        w, h = random.randint(60, 160), random.randint(40, 90)
        color = [(220, 60, 60), (230, 180, 60), (80, 180, 220)][cls % 3]
        d.rectangle([cx - w // 2, cy - h // 2, cx + w // 2, cy + h // 2], fill=color)
        im.save(os.path.join(img_dir, "demo_%02d.jpg" % i))
        # yolo txt: cls xc yc w h（归一化）
        with open(os.path.join(lbl_dir, "demo_%02d.txt" % i), "w") as fh:
            fh.write("%d %.5f %.5f %.5f %.5f\n" % (
                cls, cx / IMGSZ, cy / IMGSZ, w / IMGSZ, h / IMGSZ))
    demo_yaml = os.path.join(root, "demo.yaml")
    with open(demo_yaml, "w", encoding="utf-8") as fh:
        fh.write("path: %s\ntrain: images/train\nval: images/train\n\nnc: %d\nnames: [%s]\n"
                 % (root.replace("\\", "/"), NC, ", ".join("'%s'" % n for n in NAMES)))
    return demo_yaml


# ------------------------------------------------------------------ 量化落位
def quantize_ncnn(ncnn_dir, tools, calib_images, stem):
    """ncnn fp32 → INT8：ncnn2table 校准 + ncnn2int8。成功返回 (param,bin)，失败返回 None。"""
    table_exe, int8_exe = tools
    param = os.path.join(ncnn_dir, "model.ncnn.param")
    binf = os.path.join(ncnn_dir, "model.ncnn.bin")
    if not (os.path.isfile(param) and os.path.isfile(binf)):
        return None
    txtlist = os.path.join(ncnn_dir, "calib.txt")
    imgs = [p for p in (calib_images or []) if os.path.isfile(p)][:64]
    if len(imgs) < 8:
        log("⚠️ 校准图不足 8 张，跳过 INT8（保留 fp32 ncnn）。请给 --calib <含若干率土截图的目录>。")
        return None
    with open(txtlist, "w", encoding="utf-8") as fh:
        fh.write("\n".join(os.path.abspath(p) for p in imgs) + "\n")
    table = os.path.join(ncnn_dir, "table.txt")
    r = subprocess.run([table_exe, param, binf, txtlist, table,
                        "2112", "128", "fp32", "1", "1", "mean_norm"],
                       capture_output=True, text=True, errors="replace")
    if r.returncode != 0:
        log("⚠️ ncnn2table 失败（保留 fp32）：%s" % r.stderr[-500:])
        return None
    ip = os.path.join(ncnn_dir, "%s_int8.param" % stem)
    ib = os.path.join(ncnn_dir, "%s_int8.bin" % stem)
    r2 = subprocess.run([int8_exe, param, binf, ip, ib, table],
                        capture_output=True, text=True, errors="replace")
    if r2.returncode != 0 or not os.path.isfile(ib):
        log("⚠️ ncnn2int8 失败（保留 fp32）：%s" % r2.stderr[-500:])
        return None
    return ip, ib


def place_assets(src_param, src_bin, stem):
    os.makedirs(ASSETS_MODELS, exist_ok=True)
    dp = os.path.join(ASSETS_MODELS, "%s_stzb.param" % stem)
    db = os.path.join(ASSETS_MODELS, "%s_stzb.bin" % stem)
    shutil.copy2(src_param, dp)
    shutil.copy2(src_bin, db)
    # 校验 param 首行是 ncnn 魔数
    with open(dp, "r", encoding="utf-8", errors="replace") as fh:
        magic = fh.readline().strip()
    if magic != "7767517":
        die(1, "落位的 param 首行不是 ncnn 魔数 7767517（实际 %r），疑似未真正导出。" % magic)
    if os.path.getsize(db) < 64 * 1024:
        die(1, "落位的 bin 仅 %d 字节，过小，疑似空壳导出。" % os.path.getsize(db))
    log("[OK] 已入包：\n     %s\n     %s  (%d 字节)" % (dp, db, os.path.getsize(db)))
    return dp, db


# ---------------------------------------------------------------------- main
def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser()
    ap.add_argument("--weights", default="yolo26s.pt",
                    help="基座 .pt（yolo26n.pt 更小 / yolo26s.pt 更准）；首次加载自动下载")
    ap.add_argument("--data", default="tools/train_yolo/data.yaml",
                    help="率土标注数据集 data.yaml（相对仓库根或绝对路径）")
    ap.add_argument("--epochs", type=int, default=200)
    ap.add_argument("--imgsz", type=int, default=IMGSZ)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--device", default="0", help="GPU 编号；CPU 填 cpu")
    ap.add_argument("--name", default="stzb_yolo26")
    ap.add_argument("--stem", default="yolo26s", help="入包文件基名前缀（如 yolo26s/yolo26n）")
    ap.add_argument("--ncnn-bin", default=None, help="ncnn2table/ncnn2int8 所在目录")
    ap.add_argument("--calib", default=None, help="INT8 校准图目录（放若干率土真实截图）")
    ap.add_argument("--no-int8", action="store_true", help="跳过 INT8，直接入包 fp32 ncnn")
    ap.add_argument("--demo", action="store_true", help="合成小数据仅跑通管线（成品无效，勿入包）")
    args = ap.parse_args()

    ver = ensure_ultralytics()
    log("ultralytics=%s  目标输入=%d  类别数=%d" % (ver, args.imgsz, NC))
    if args.imgsz != IMGSZ:
        die(1, "--imgsz 必须是 %d（与 native YoloNcnn.cpp 的 inputSize 一致），否则框会整体错位。" % IMGSZ)

    from ultralytics import YOLO

    work_root = os.path.join(REPO, ".a_plus_plus", "yolo")
    os.makedirs(work_root, exist_ok=True)

    # ---- 数据：正式用 --data；--demo 合成 ----
    if args.demo:
        log("🧪 --demo：合成数据自检管线，产出的模型对真实游戏无效，请勿入包当成品。")
        data = make_demo_dataset(os.path.join(work_root, "demo_ds"))
    else:
        data = args.data if os.path.isabs(args.data) else os.path.join(REPO, args.data)
        if not os.path.isfile(data):
            die(2, "找不到数据集 %s。率土专属类别必须自标注训练；仅自检请用 --demo。" % data)

    # ---- 基座（自动下载 or 本地）----
    try:
        model = YOLO(args.weights)
    except Exception as exc:
        die(2, "取基座 %s 失败：%s\n   离线环境请 pip 装好后手动放置 .pt 并用 --weights 指向本地路径。"
            % (args.weights, exc))

    # ---- 训练 ----
    log("▶ 训练 %s on %s ..." % (args.weights, data))
    model.train(
        data=data, epochs=args.epochs, imgsz=args.imgsz, batch=args.batch,
        name=args.name, device=args.device, exist_ok=True,
        box=7.5, cls=0.5, dfl=1.5,  # 率土 UI 小目标多，提高定位/分类权重
    )

    # ---- 找 best.pt ----
    run_dir = os.path.join("runs", "detect", args.name, "weights", "best.pt")
    best = run_dir if os.path.isabs(run_dir) else os.path.join(REPO, run_dir)
    if not os.path.isfile(best):
        # ultralytics 有时落在项目 cwd，做一次兜底搜索
        for cand in [os.path.join(os.getcwd(), "runs", "detect", args.name, "weights", "best.pt")]:
            if os.path.isfile(cand):
                best = cand
                break
    if not os.path.isfile(best):
        die(1, "训练结束但找不到 best.pt（期望 %s）。" % best)

    if not args.demo:
        metrics = model.val(data=data, imgsz=args.imgsz, device=args.device)
        log("📊 mAP50-95=%.3f  mAP50=%.3f" % (metrics.box.map, metrics.box.map50))

    # ---- 导出 ncnn（Ultralytics 官方路径；对 ncnn 回落标准 [1,4+nc,anchors] 布局）----
    log("▶ 导出 ncnn（format=ncnn, imgsz=%d）..." % args.imgsz)
    exported = model.export(format="ncnn", imgsz=args.imgsz, simplify=True, opset=12)
    ncnn_dir = exported if os.path.isdir(exported) else os.path.splitext(exported)[0]
    log("   ncnn 目录：%s" % ncnn_dir)

    src_param = os.path.join(ncnn_dir, "model.ncnn.param")
    src_bin = os.path.join(ncnn_dir, "model.ncnn.bin")
    if not (os.path.isfile(src_param) and os.path.isfile(src_bin)):
        die(1, "导出目录缺 model.ncnn.param/.bin，请升级 ultralytics 或检查 export 日志。")

    # ---- INT8 ----
    final_param, final_bin = src_param, src_bin
    if not args.no_int8:
        tools = find_ncnn_tools(args.ncnn_bin)
        if tools:
            calib = []
            if args.calib and os.path.isdir(args.calib):
                calib = [os.path.join(args.calib, f) for f in os.listdir(args.calib)
                         if f.lower().endswith((".jpg", ".jpeg", ".png"))]
            q = quantize_ncnn(ncnn_dir, tools, calib, args.stem)
            if q:
                final_param, final_bin = q
                log("✅ 已完成 ncnn INT8 量化。")
        else:
            log("⚠️ 未找到 ncnn2table/ncnn2int8（--ncnn-bin 或 PATH）。将入包 fp32 ncnn（能跑、体积更大）。")

    # ---- 落位 ----
    dp, db = place_assets(final_param, final_bin, args.stem)

    if args.demo:
        log("\n🧪 这是 --demo 产物，仅为管线自检。真实成品请用 --data 训练后重跑，并删除本 demo 文件。")
    else:
        log("\n✅ YOLO26 成品已入包。下一步：git add 这两个文件 → 推 CI 重新出 APK；"
            "装机后 logcat 过滤 YoloDetector 应见『YOLO 主通道就绪：ncnn 已加载』。")
    log("   （若 App 里 ModelAssetManager/YoloDetector 尚未列出 %s_stzb 命名，本改造已一并加上。）"
        % args.stem)
    return 0


if __name__ == "__main__":
    sys.exit(main())
