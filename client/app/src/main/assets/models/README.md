# 端侧模型资产目录（如实说明）

> 本目录过去被宣传为"内置四大 85MB AI 模型、打包出 106MB 大包"。**那是假的**——
> 里面从来没有过那些权重，历史 CI 甚至用随机字节伪造过同尺寸的空文件（已删除）。
> 现在这里只写事实。

## 当前真实状态

| 能力 | 权重是否在本目录 | 推理实现 | 说明 |
|---|---|---|---|
| **OCR 文字识别** | ❌ 不在本目录（v3 在 `assets/` 根） | ✅ 真实（`ocr_lite` ncnn） | 真正的权重是 **`assets/` 根目录**下的 PP-OCRv3 三件套（det/cls/rec + `ppocr_keys_v1.txt`，约 12.7MB），**出厂即真实可用**（`build_apk.yml` 已链接 ncnn+OpenCV）。v4 转换产物同样放根目录。 |
| **YOLO 目标检测** | ❌ 待放入 | ✅ 真实（`cpp/yolo/YoloNcnn.cpp`） | 推理代码与 JNI 已就绪，**但游戏专属权重无公开源**，需按 `tools/train_yolo/README.md` 自行采集率土截图训练并导出 ncnn，再把 `yolov8n_stzb.param` + `.bin`（或 `yolov8s_stzb.*`）放进本目录。放入前，`YoloDetector` 如实回退到几何色度检测。 |
| **战法向量检索 (RAG)** | ✅ 已在（`slg_knowledge_vector_hnsw.bin`，31 条 / 64 维 / V1） | ✅ 真实 (`SlgRagEngine.kt`) | 目前是 `tools/generate_rag_asset.py` 产出的 64 维**确定性哈希**向量（不是语义向量，见下）。升级到 bge 语义向量后，文件头会变成 `SLG_HNSW_RAG_V2`、维度变成 512，并带 HNSW 图区。 |
| **③ bge 中文向量器** | ❌ 待放入 (`bge_zh_int8.onnx` + `bge_zh_vocab.txt`) | ✅ 真实 (`BgeEmbedder.kt`, ORT) | 索引升到 V2/512 维后，`BgeEmbedder` 用同一个 bge 模型给查询文本 embedd（否则余弦不可比）。缺资产时 `SlgRagEngine` 如实丢索引、回落 64 维哈希。 |
| **④ 意图+槽位微脑** | ❌ 待放入 (`intent_slot_zh.onnx` + `intent_slot_vocab.txt`) | ⚠️ 推理代码**未**接入 | `EdgeSlmEngine` 目前仍是纯正则实现；权重放进本目录**不会**改变军令解析行为（只会被诚实报告为"资产齐备但无推理后端"）。 |

### ③ 的诚实说明（重要）
`slg_knowledge_vector_hnsw.bin` 现在这份是 **V1 / 64 维确定性哈希向量**，
它的作用是"向量检索骨架可用"，但**没有语义能力**："打李儒"和"李儒很硬"这类查询
靠的是关键词倒排 Boost，不是向量语义。真正的语义检索要等
`python tools/p1/export_bge_int8.py` + `python tools/p1/build_rag_index.py` 跑完，
那一步不做就用不上 bge——这是已知缺口，不是已完成。

## 放进 YOLO 权重（唯一需要你产出的东西）
```bash
# 训练 → 导出 → 落位（详见 tools/train_yolo/README.md）
python tools/train_yolo/train.py --data tools/train_yolo/data.yaml
yolo export model=runs/detect/stzb_yolo/weights/best.pt format=ncnn imgsz=640
bash tools/train_yolo/export_ncnn.sh runs/detect/stzb_yolo/weights/best_ncnn_model
```
放好后应看到本目录出现：`yolov8n_stzb.param` + `yolov8n_stzb.bin`。
装机后 logcat 过滤 `YoloDetector`，出现「YOLO 主通道就绪」即为真实推理生效。

## 五引擎落地顺序（脚本都在 `tools/p1/`，见该目录 README）
```bash
python tools/p1/check_bases.py                        # ① 先核验基座下载齐没齐
python tools/p1/onnx2ncnn.py --input <v4>.onnx --out assets/ch_PP-OCRv4_det_infer   # ②
python tools/p1/export_bge_int8.py --src .models/bge  # ③
python tools/p1/build_rag_index.py --corpus ... --bge assets/models/bge_zh_int8.onnx \
    --vocab assets/models/bge_zh_vocab.txt --out <本目录>/slg_knowledge_vector_hnsw.bin
python tools/p1/gen_intent_slot_data.py --out data/intent_slot_train.jsonl --count 20000   # ④
python tools/p1/train_intent_slot.py --base .models/rbt3 --data data/intent_slot_train.jsonl \
    --out <本目录>/intent_slot_zh.onnx
python tools/p1/check_assets.py                       # 体积 / 索引契约 / 假权重体检
```
每一条缺权重都会**报错退出**（退出码 2），不会用同尺寸空文件冒充。

## 打包约定
- `build.gradle` 已对 `bin/param/gguf/tflite/onnx` 关闭压缩，`ModelAssetManager` 用
  `assets.openFd` 读真实字节数并解压到沙盒供 native 加载。
- 本项目选择"模型二进制直接提交进仓库"（非 git-lfs）。请勿再用任何方式生成随机字节冒充模型。
