# 率土之滨端侧 85MB AI 核心模型资产目录 (Mode A)

本目录由 `ModelAssetManager.kt` 进行统一装配管理，内置四大核心模型：

1. **`yolov8n_stzb.bin`** (~2.1 MB)
   - 算法：YOLOv8-Nano INT8 量化模型
   - 职责：率土全场景核心按键（出征、驻守、撤退、确定、取消）、邮件军令红点、雷达警报、行军红线与关隘城池毫秒级感知。
   - 触控增强：内置 2D 高斯抖动与三次贝塞尔拟人触控点映射。

2. **`ch_PP-OCRv4_det.bin`** (~3.8 MB)
   - 算法：PaddleOCR / RapidOCR NCNN DBNet 文本检测模型
   - 职责：全屏文字几何多边形外接框检测。

3. **`ch_PP-OCRv4_rec.bin`** (~4.5 MB)
   - 算法：PaddleOCR / RapidOCR NCNN CRNN 文本识别模型
   - 职责：离线毫秒级文字识别，提取武将体力、大地图坐标 X/Y、免战倒计时时钟与战报关键词。

4. **`slm_microbrain_135m.bin`** (~75.0 MB)
   - 算法：SmolLM2-135M / RWKV-v5-160M INT4 GGUF 端侧生成式大模型
   - 职责：长文军令自然语言因果推理、多阶段压秒日程生成、战报战法克制深度诊断与诸葛军师实时 HUD 思考流。

---

## 本地开发与生产打包：
在执行 `./gradlew assembleRelease` 时，构建系统会自动读取本目录的四个模型权重文件打包进 APK 的 `assets/` 区域，产出真正的 ~106MB 模式 A 一体化独立商业大包。
