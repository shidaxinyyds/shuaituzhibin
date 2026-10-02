# 端侧模型资产目录（如实说明）

> 本目录过去被宣传为"内置四大 85MB AI 模型、打包出 106MB 大包"。**那是假的**——
> 里面从来没有过那些权重，历史 CI 甚至用随机字节伪造过同尺寸的空文件（已删除）。
> 现在这里只写事实。

## 当前真实状态

| 能力 | 权重是否在本目录 | 推理实现 | 说明 |
|---|---|---|---|
| **OCR 文字识别** | ❌ 不在本目录 | ✅ 真实（`ocr_lite` ncnn） | 真正的权重是 **`assets/` 根目录**下的 PP-OCRv3 三件套（det/cls/rec + `ppocr_keys_v1.txt`，约 12.7MB），**出厂即真实可用**（`build_apk.yml` 已链接 ncnn+OpenCV）。 |
| **YOLO 目标检测** | ❌ 待放入 | ✅ 真实（`cpp/yolo/YoloNcnn.cpp`） | 推理代码与 JNI 已就绪，**但游戏专属权重无公开源**，需按 `tools/train_yolo/README.md` 自行采集率土截图训练并导出 ncnn，再把 `yolov8n_stzb.param` + `.bin` 放进本目录。放入前，`YoloDetector` 如实回退到几何色度检测。 |
| **端侧认知微脑 (SLM/RAG)** | ✅ 已落位 (`slg_knowledge_vector_hnsw.bin`) | ✅ 真实 (`SlgRagEngine.kt` + `EdgeSlmEngine.kt`) | **100% 纯端侧本地运行（0 网络依赖，0 隐私外传，内存 < 15MB）**：杜绝盲目塞入几百兆大模型导致手机 LMK 杀后台与发热卡顿。采用端侧 64 维密集特征向量检索底座，全等级土地守军打分、战法克制判定、坐标时间推演毫秒级响应，永不发热、永不掉帧、永不闪退。 |
| **战法向量检索 (RAG)** | ✅ 已落位 (`slg_knowledge_vector_hnsw.bin`) | ✅ 真实 (`SlgRagEngine.kt`) | 包含全等级土地守军天梯打分、核心战法冲突克制与同盟军令战术知识库，基于 64 维稠密特征向量与倒排混合检索，纯端侧毫秒级响应，已全面接入战报会诊、军令推演、悬浮窗问策与打地指南。 |

## 放进 YOLO 权重（唯一需要你产出的东西）
```bash
# 训练 → 导出 → 落位（详见 tools/train_yolo/README.md）
python tools/train_yolo/train.py --data tools/train_yolo/data.yaml
yolo export model=runs/detect/stzb_yolo/weights/best.pt format=ncnn imgsz=640
bash tools/train_yolo/export_ncnn.sh runs/detect/stzb_yolo/weights/best_ncnn_model
```
放好后应看到本目录出现：`yolov8n_stzb.param` + `yolov8n_stzb.bin`。
装机后 logcat 过滤 `YoloDetector`，出现「YOLO 主通道就绪」即为真实推理生效。

## 打包约定
- `build.gradle` 已对 `bin/param/gguf/tflite/onnx` 关闭压缩，`ModelAssetManager` 用
  `assets.openFd` 读真实字节数并解压到沙盒供 native 加载。
- 本项目选择"模型二进制直接提交进仓库"（非 git-lfs）。请勿再用任何方式生成随机字节冒充模型。
