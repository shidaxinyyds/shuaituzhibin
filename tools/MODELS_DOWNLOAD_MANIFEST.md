# 模型权重下载清单（方案 A · 五引擎全真落地）

> 用途：交给下载方按此清单获取基座/预训练模型。区分：可下载的通用基座 vs 必须训练产出的率土专属权重。
> 本环境禁止联网下载二进制，故仅出清单，不代下。
> 项目根：d:\mj\shuaituzhibin ；资产目录：client/app/src/main/assets/（OCR 放根，其余放 assets/models/）；目标总包 ≤185MB，全部 INT8/INT4。

## 分类
- 可下载：公开通用基座/预训练权重
- 下载基座+需训练：下基座再在率土数据微调/导出
- 无法下载：率土专属成品权重，只能训练产出

## ① 视觉 YOLOv8s（土地等级/免战盾/要塞）
- 状态：成品权重无法下载；基座可下载供训练
- 基座：Ultralytics YOLOv8s COCO 预训练
  - 仓库 https://github.com/ultralytics/assets/releases
  - 文件 yolov8s.pt(约22MB) + yolov8s.yaml ；备选 yolov8n.pt(约6MB)
  - 建议存放 tools/train_yolo/pretrain/yolov8s.pt
- 训练产出(你侧)：率土11类微调 -> 导出 ncnn INT8
  - 入包 client/app/src/main/assets/models/yolov8s_stzb.param + .bin
  - 类别顺序须与 YoloDetector.DetectionClass 一致(见 tools/train_yolo/data.yaml)
  - 脚本 tools/train_yolo/ 已就绪
- 入包体积：ncnn INT8 约12-22MB

## ② 字符识别 PP-OCRv4（生僻字/书法体）
- 状态：基座可下载；率土微调可选；v3 已在仓库可用
- 现有可用(无需下载)：PP-OCRv3 ncnn
  - assets/ch_PP-OCRv3_det_infer.bin/.param, ch_PP-OCRv3_rec_infer.bin/.param,
    ch_ppocr_mobile_v2.0_cls_infer.bin/.param, ppocr_keys_v1.txt
- 升级v4(下载ONNX, 需onnx->ncnn转换, 脚本P1提供)：
  - 仓库 https://github.com/RapidAI/RapidOCR 或 https://github.com/PaddlePaddle/PaddleOCR
  - 文件 ch_PP-OCRv4_det_infer.onnx, ch_PP-OCRv4_rec_infer.onnx,
    ch_ppocr_mobile_v2.0_cls_infer.onnx, 配套中文字典
  - 转换后入包 assets/ch_PP-OCRv4_det_infer.bin/.param, ch_PP-OCRv4_rec_infer.bin/.param
- 入包体积：det约2-3MB + rec约10-11MB 约13MB
- 备注：率土专精字库需训练(无现成)；短期最优=v3/v4+识别后处理约束词典(零训练即提准)

## ③ 战法检索 bge 中文向量（端侧RAG）
- 状态：基座可直接下载(通用中文模型)
- 二选一(看体积)：
  - A 推荐 bge-small-zh-v1.5  https://huggingface.co/BAAI/bge-small-zh-v1.5
    INT8 onnx/model_qint8_avx512_vnni.onnx 约25-30MB
  - B bge-base-zh-v1.5  https://huggingface.co/BAAI/bge-base-zh-v1.5
    INT8 约100MB(会把总包逼到185边缘, 除非必要不选)
- 均需 tokenizer.json, vocab.txt, tokenizer_config.json, config.json, special_tokens_map.json
- 入包 assets/models/bge_zh_int8.onnx + bge_zh_vocab.txt
- 向量索引(无法下载, 需语料+embedding生成)：全量武将/战法语料(公开wiki可抓)->离线算向量->建HNSW
  - 产出 assets/models/slg_knowledge_vector_hnsw.bin (替换现有21KB假索引); 建库脚本P1提供
- 入包体积：small约30MB + 索引约5-10MB 约35-40MB

## ④ 战术认知微脑 中文小BERT（意图+槽位, 替代大LLM）
- 状态：基座可下载；意图+槽位微调成品需训练
- 重要更正：原版 TinyBERT(huawei-noah/TinyBERT_General_4L_312D) 是英文, 不适合率土中文黑话
  应下载中文小BERT基座：
  - 首选 hfl/rbt3 (3层中文RoBERTa) https://huggingface.co/hfl/rbt3
    文件 config.json, model.safetensors, vocab.txt
  - 备选 hfl/rbt4 https://huggingface.co/hfl/rbt4
- 基座fp32(rbt3约60-100MB); 最终入包需在率土军令数据微调意图+槽位头再导出ONNX INT8
  - 训练数据无现成, 用P1的军令->DSL合成数据生成器批量造
  - 入包 assets/models/intent_slot_zh.onnx (INT8约10-20MB) + intent_slot_vocab.txt
- 输出约束：JSON Schema/语法受约束解码, 保证DSL永远合法零坐标幻觉

## ⑤ 启发式容灾 OpenCV几何
- 纯代码, 0MB, 无需下载(复用 YoloDetector 几何通道 + RecognitionHealth 看门狗)

## 运行时依赖(非权重, 构建期自动拉, 无需手动下)
- ONNX Runtime Mobile(跑③④): Gradle依赖 com.microsoft.onnxruntime:onnxruntime-android (ARM64约12MB)
- ncnn/OpenCV: CI build_apk.yml 已 provision

## 下载方照抄清单(可下载基座)
```
yolov8s.pt                         github.com/ultralytics/assets/releases
ch_PP-OCRv4_det_infer.onnx         RapidAI/RapidOCR 或 PaddlePaddle/PaddleOCR
ch_PP-OCRv4_rec_infer.onnx         同上
ch_ppocr_mobile_v2.0_cls_infer.onnx 同上
中文key字典                         同上
BAAI/bge-small-zh-v1.5 -> onnx/model.onnx, onnx/model_qint8_avx512_vnni.onnx, tokenizer.json, vocab.txt, config.json, tokenizer_config.json, special_tokens_map.json
hfl/rbt3 -> model.safetensors, config.json, vocab.txt   (或 hfl/rbt4)
```

## 你侧训练才能产出(无法下载)
```
yolov8s_stzb.param/.bin        率土11类微调 -> ncnn INT8
intent_slot_zh.onnx            军令意图+槽位微调 -> ONNX INT8
slg_knowledge_vector_hnsw.bin  全量语料 + bge向量 -> HNSW索引
(可选) PP-OCRv4-率土 rec 微调   合成字体样本微调
```

## 体积预算(选bge-small时, 最终入包)
基包24.3 + ①22 + ②净0-1 + ③35-40 + ④15 + ORT12 = 约110-115MB ; OTA余量约70MB
若③选bge-base(+约65MB) -> 约175-180MB 逼近上限几乎无余量, 建议bge-small

---

# 修订 v1.1（本轮落地后回写）

原稿是**规划稿**，与代码里的真实契约有三处对不上。以下按**代码为准**回写；
「P1 提供」的三处脚本也已于本轮补齐（`tools/p1/`）。

## 一、三处纠正（不改会直接踩坑）

| # | 原稿 | 工程实际 | 处置 |
|---|---|---|---|
| 1 | 入包 `models/yolov8s_stzb.{param,bin}` | `ModelAssetManager` / `YoloNcnn.cpp` 认 `yolov8n_stzb`（另备 `yolov11s_multiscale_stzb`） | `ModelAssetManager` 已同时接受 `n`/`s` 两种命名；导出时按 `--out` 决定 |
| 2 | PP-OCRv4 转换后放 `assets/models/ch_PP-OCRv4_det.bin` | 工程内 v3 就在 `assets/` **根目录**，`ModelAssetManager` 的 v4 备选项写的也是根目录 | 统一为 `assets/ch_PP-OCRv4_det_infer.{param,bin}`（与清单一致） |
| 3 | 索引用 bge（512 维）直接替换 21KB 假索引 | `SlgRagEngine.kt` 的 `VECTOR_DIM` 是常量 64，维度不符会**丢弃索引**并回落内置知识库 | 索引容器升级 **V2**：magic `SLG_HNSW_RAG_V2`，**维度写进文件头**，尾部带 HNSW 图区；`SlgRagEngine` 改为按头读维度 + 按图近似检索；同时新增 `BgeEmbedder.kt` 用同一个 bge 给查询 embedd（否则 64 维哈希向量与 512 维索引不可比） |

V1（现有这份 31 条 / 64 维哈希向量）**仍可解析**，向后兼容。

## 二、「P1 提供」已补齐（`tools/p1/`）

| 脚本 | 对应原稿哪一处 |
|---|---|
| `check_bases.py` | 清单核验/下载（默认离线只读，`--download` 才联网，`--verify` 校验 sha256） |
| `onnx2ncnn.py` | ②「需 onnx->ncnn 转换，脚本 P1 提供」 |
| `export_bge_int8.py` | ③ bge INT8 + 词表入包 |
| `build_rag_index.py` | ③「建库脚本 P1 提供」（语料 → bge 向量 → 最小 HNSW → V2 容器） |
| `gen_intent_slot_data.py` | ④「军令->DSL 合成数据生成器」（含 6% 空指令/模糊指令负样本，UNKNOWN 不 hallucinate） |
| `train_intent_slot.py` | ④ rbt3 基座 + 意图/槽位双头 → ONNX → INT8（坐标走 10×10 粗桶，物理上不可能超出地图） |
| `check_assets.py` | ⑤ 之后的入包体检：体积预算 / 索引契约 / 假权重，已接进 `verify.yml` |

铁律不变：任何脚本缺权重**报错退出（码 2）**，不用随机字节或同尺寸空文件补位。

## 三、改动到的工程文件

- `client/app/src/main/java/.../ai/rag/SlgRagEngine.kt`：维度动态化、V2 + HNSW 图区解析、图近似检索、bge 查询向量。
- `client/app/src/main/java/.../ai/rag/BgeEmbedder.kt`：**新增**，ORT 跑 `bge_zh_int8.onnx`，字符 bigram 分词与 `build_rag_index.py` 对齐。
- `client/app/src/main/java/.../ai/assets/ModelAssetManager.kt`：OCR v4 落位改正、YOLO 增加 `s` 命名、`BGE`/`INTENT` 两项能力如实登记。
- `client/app/build.gradle`：新增 `com.microsoft.onnxruntime:onnxruntime-android:1.22.0`。
- `tools/run_all_checks.py` + `.github/workflows/verify.yml`：新增 `asset-budget` / `model-bases` 两项静态检查。

## 四、仍然缺（下载方 + 训练，任一项不到就别假装齐备）

### 4.1 可下载基座 —— 已于 2026-10-03 实测下载完毕（7/8 到位）

落地目录 `.models/`（已加进 `.gitignore`，不入库，可 `--download` 重取）：

| 基座 | 实到体积 | 实测有效源（原稿的地址多处 404） |
|---|---|---|
| `yolov8/yolov8s.pt` | 21.5MB | **`https://ultralytics.com/assets/yolov8s.pt`**（`github.com/ultralytics/assets/releases`那个 tag 404） |
| `ocr/ch_PP-OCRv4_det_infer.onnx` | 4.5MB | `https://huggingface.co/cycloneboy/ch_PP-OCRv4_det_infer/resolve/main/model.onnx` |
| `ocr/ch_PP-OCRv4_rec_infer.onnx` | 10.3MB | `https://huggingface.co/cycloneboy/ch_PP-OCRv4_rec_infer/resolve/main/model.onnx` |
| `ocr/ch_ppocr_mobile_v2.0_cls_infer.onnx` | 572KB | `https://huggingface.co/Desperado-JT/CH-PP-OCRv4/resolve/main/ch_ppocr_mobile_v2.0_cls_infer.onnx` |
| `ocr/ppocrv4_rec_ch_dict.txt` | 32KB（6623行） | `https://huggingface.co/cycloneboy/ch_PP-OCRv4_rec_infer/resolve/main/ch_dict.txt` |
| `bge/*`（6 个文件） | 91.9MB | `hf-mirror.com/BAAI/bge-small-zh-v1.5`（`huggingface.co` 直连慢/不稳，优先 hf-mirror） |
| `rbt3/*`（6 个文件） | 149.5MB | `hf-mirror.com/hfl/rbt3`。**注意：只有 `pytorch_model.bin`，没有 `model.safetensors`** |

**PaddleOCR 官方 CDN `paddleocr.bj.bcebos.com` 的 PP-OCRv4 路径全部 404**，v2/v3/v4 与各变体都试过，
不要在它上面耗时间。

`yolov8s.yaml` 已标为**可选**：官方从 v8.0.0 起就不再发布该文件（`cfg/models/v8/` 只剩 `yolov8.yaml` +
`scales`，训练时由 `.pt` 自举结构）。要本地副本装 ultralytics 后自导：
`python -c "from ultralytics import YOLO; print(YOLO('.models/yolov8/yolov8s.pt').model.yaml)"`

校验：`python tools/p1/check_bases.py` → `齐备 7 / 共 8（另有 1 项可选）`。
该脚本按**魔数判真实格式**（ONNX 走 protobuf ir_version、safetensors 走 u64 头长+JSON 张量表、
torch 走 `PK`/legacy `80 02`），体积够但内容是错误页或 LFS 指针会直接判「格式异常」，
不会再被当成模型放行。

### 4.2 仍需训练产出（没有任何下载渠道）

`yolov8n_stzb.{param,bin}`、`intent_slot_zh.onnx`、
`slg_knowledge_vector_hnsw.bin`（bge版）。
入包现状：`python tools/p1/check_assets.py` → assets 内权重 12.83MB（PP-OCRv3七件套），上限 185MB。
