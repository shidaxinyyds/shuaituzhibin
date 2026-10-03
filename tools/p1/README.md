# P1 工具集：把《模型权重下载清单》从"清单"变成"可执行的流水线"

> 对应文档：`tools/MODELS_DOWNLOAD_MANIFEST.md`（方案 A · 五引擎全真落地）。
> 清单里三处写着「脚本 P1 提供」，本目录就是把那三处补上：
> ① `onnx2ncnn.py`（ONNX → ncnn）、② `build_rag_index.py`（HNSW 建库）、
> ③ `gen_intent_slot_data.py` + `train_intent_slot.py`（军令→DSL 合成数据与微调导出）。
>
> **铁律（与仓库既有约定一致）**：任何环节只要权重/语料不在，**必须报错退出**，
> 不许生成随机字节或同尺寸空文件来"补齐"。历史 CI 用 `os.urandom()` 伪造过
> "190MB 大模型"，产物是纯噪声，那次事故已写入 `assets/models/README.md`。
> 本目录所有脚本在缺件时退出码 2，并打印缺哪个文件、去哪里下。

## 目录索引

| 脚本 | 干什么 | 输入 → 输出 | 需要联网/依赖 |
|---|---|---|---|
| `check_bases.py` | 按清单核验基座权重是否到位（可选 `--download` 真下载） | 清单 → 表格/退出码 | 默认离线；`--download` 才联网 |
| `onnx2ncnn.py` | ONNX → ncnn param/bin，含产物自检 | `*_v4_det_infer.onnx` 等 → `.param/.bin` | ncnn `onnx2ncnn` 或 `pnnx` |
| `export_bge_int8.py` | bge 中文向量模型入包（INT8 ONNX + 词表） | HF 目录 → `assets/models/bge_zh_int8.onnx` + `bge_zh_vocab.txt` | onnxruntime / 复制 |
| `build_rag_index.py` | 语料 → bge 向量 → 最小 HNSW → 二进制索引 | `corpus.jsonl` + bge ONNX → `slg_knowledge_vector_hnsw.bin` | onnxruntime |
| `gen_intent_slot_data.py` | 军令文本 → DSL 合成语料（带合法性校验） | 规则模板 → `intent_slot_train.jsonl` | 无（纯 stdlib） |
| `train_intent_slot.py` | 中文小 BERT 基座 + 意图/槽位双头 → ONNX → INT8 | `rbt3` 基座 + 上面那份 jsonl → `intent_slot_zh.onnx` | torch + transformers + onnxruntime |
| `check_assets.py` | 入包体积预算 + RAG 索引头部契约 + 假权重体检 | assets 目录 → 表格/退出码 | 无（纯 stdlib） |

## 常速命令

```bash
# 1) 核验下载方是否照清单下齐（离线，只读）
python tools/p1/check_bases.py

# 2) 真下载（下载方在自己的网络环境里跑；hf 镜像用 HF_ENDPOINT 指定）
python tools/p1/check_bases.py --download

# 3) PP-OCRv4 ONNX → ncnn
python tools/p1/onnx2ncnn.py --input .models/ocr/ch_PP-OCRv4_det_infer.onnx \
    --out models/ch_PP-OCRv4_det_infer
python tools/p1/onnx2ncnn.py --input .models/ocr/ch_PP-OCRv4_rec_infer.onnx \
    --out models/ch_PP-OCRv4_rec_infer

# 4) bge-small-zh-v1.5 INT8 入包
python tools/p1/export_bge_int8.py --src .models/bge

# 5) 语料 → 索引（替换现有 21KB V1 索引）
python tools/p1/build_rag_index.py --corpus corpus.jsonl --bge assets/models/bge_zh_int8.onnx \
    --vocab assets/models/bge_zh_vocab.txt --out client/app/src/main/assets/models/slg_knowledge_vector_hnsw.bin

# 6) 军令→DSL 合成语料
python tools/p1/gen_intent_slot_data.py --out data/intent_slot_train.jsonl --count 20000

# 7) 微调 + 导出 INT8
python tools/p1/train_intent_slot.py --base .models/rbt3 --data data/intent_slot_train.jsonl \
    --out client/app/src/main/assets/models/intent_slot_zh.onnx

# 8) 入包体检（CI 会跑，见 verify.yml）
python tools/p1/check_assets.py
```

## 与清单的三处不一致（已按**代码里的真实契约**纠正）

清单是规划稿，工程侧的真实契约在 `client/app/src/main` 里。以下三处以代码为准，
本目录的脚本与清单修订版（`tools/MODELS_DOWNLOAD_MANIFEST.md` 文末「修订 v1.1」）已对齐：

1. **YOLO 文件名**：`ModelAssetManager` / `YoloNcnn.cpp` 认的是
   `models/yolov8n_stzb.{param,bin}`（另有 `models/yolov11s_multiscale_stzb` 备选），
   清单写的 `yolov8s_stzb` 会**永远加载不到**。脚本 `--out models/yolov8n_stzb` 或
   `ModelAssetManager` 已同时接受 `s`/`n` 两种命名。
2. **PP-OCRv4 落位**：清单说放 `assets/` 根目录（`ch_PP-OCRv4_det_infer.{param,bin}`），
   而 `ModelAssetManager` 的备选项写的是 `models/ch_PP-OCRv4_det.bin`。已统一为清单式命名。
3. **RAG 索引维度**：`SlgRagEngine.kt` 的 `VECTOR_DIM` 是常量 64，
   bge-small-zh 的维度是 512，直接替换会让引擎判"参数异常"并回落到内置知识库。
   因此索引容器升级为 **V2**（`magic = SLG_HNSW_RAG_V2`），维度**写在文件头**，
   `SlgRagEngine` 改为按头读维度；V1（64 维哈希向量）仍可解析，向后兼容。

## 体积预算（脚本 `check_assets.py` 会实测，不靠表格估算）

| 项 | 入包路径 | 预算 | 实测口径 |
|---|---|---|---|
| 基包 | — | 24.3 MB | APK 除 assets/models 以外的固定体积 |
| ① YOLO ncnn | `assets/models/yolov8n_stzb.*` | 12–22 MB | param+bin |
| ② OCR（v3 已在包） | `assets/*.param/.bin` | 净增 0–1 MB | 升级 v4 时才 ~13 MB |
| ③ bge-small-zh INT8 + 索引 | `assets/models/bge_zh_int8.onnx`, `slg_knowledge_vector_hnsw.bin` | 35–40 MB | 实测 |
| ④ 意图/槽位微脑 INT8 | `assets/models/intent_slot_zh.onnx` | 10–20 MB | 实测 |
| ORT Mobile 运行时 | Gradle AAR | ~12 MB | 构建期拉取，不计入 assets |

硬上限 **185 MB**（OTA 余量）。`check_assets.py` 只统计 `assets/` 内的权重文件，
并单独打印"扣掉基包后的 assets 占用"，供人工对照。
