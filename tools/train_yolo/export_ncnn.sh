#!/usr/bin/env bash
# 把 ultralytics `yolo export format=ncnn` 的产物重命名并放进 assets/models。
#
# 用法：
#   bash tools/train_yolo/export_ncnn.sh runs/detect/stzb_yolo/weights/best_ncnn_model
#
# 期望输入目录里有：model.ncnn.param 与 model.ncnn.bin
# 产出：client/app/src/main/assets/models/yolov8n_stzb.param / .bin
# 之后重新触发 CI 即可把权重打进 APK（build_apk.yml 已链接 ncnn）。
set -euo pipefail

SRC="${1:?请传入 ncnn 导出目录，例如 runs/detect/stzb_yolo/weights/best_ncnn_model}"
# 脚本所在项目根
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEST="$ROOT/client/app/src/main/assets/models"

PARAM=""
BIN=""
for cand in "$SRC/model.ncnn.param" "$SRC/best.ncnn.param" "$SRC/*.param"; do
  [ -f "$cand" ] && PARAM="$cand" && break
done
for cand in "$SRC/model.ncnn.bin" "$SRC/best.ncnn.bin" "$SRC/*.bin"; do
  [ -f "$cand" ] && BIN="$cand" && break
done

if [ -z "$PARAM" ] || [ -z "$BIN" ]; then
  echo "❌ 在 $SRC 找不到 ncnn 的 .param/.bin。请先执行："
  echo "   yolo export model=<best.pt> format=ncnn imgsz=640"
  exit 1
fi

mkdir -p "$DEST"
cp -v "$PARAM" "$DEST/yolov8n_stzb.param"
cp -v "$BIN"   "$DEST/yolov8n_stzb.bin"

echo
echo "✅ 已放入：$DEST/yolov8n_stzb.{param,bin}"
echo "   param=$(du -h "$DEST/yolov8n_stzb.param" | cut -f1)  bin=$(du -h "$DEST/yolov8n_stzb.bin" | cut -f1)"
echo "下一步：提交这两个文件并重跑 CI；装机后 logcat 过滤 YoloDetector 应见『主通道就绪』。"
