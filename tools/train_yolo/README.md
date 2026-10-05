# 率土之滨 YOLO 检测模型 · 训练与导出指南

本目录把"从零到能把权重放进 APK"的每一步固化下来。**代码侧（ncnn 推理 + JNI +
Kotlin 集成）已经全部就绪**，缺的只有一份针对率土之滨训练出来的权重。

## 0. 为什么必须自己训练（契约已重锚：YOLO 只管大地图多目标）
`YoloDetector.DetectionClass` / `data.yaml` 现收敛为 **7 个大地图多目标类**
（红地 / 资源地 / 行军线 / 要塞关卡 / 营寨营地 / 骑兵 / 步兵），全部**率土专属**，
COCO 等公开预训练权重一个都检不出。所以没有"下载即用"这条路，只能：采集 → 标注 → 训练 → 导出 ncnn → 放入 assets。
> **确定性优先原则**：固定 UI 按钮（出征/驻守/撤退/确定/取消）、军令红点、敌袭告警
> **不进 YOLO 契约**，改由确定性通道处理（语义 OCR / HSV 角标 / RaidRadarDetector）。
> 兵种：行军时只有微缩行军模型、**无独立兵种图标**；骑（马上）与步兵肉眼可分→建两类，
> 弓/枪/盾步兵在地图缩放下不可稳定区分→**合并为“步兵”**，不硬拆四类。
> ⚠️ 每类**必须先有足量标注再启用训练**；样本不够时该类会欠拟合、宁缺毋滥。
> 无权重时 `YoloDetector` 对它们如实返回空（几何容灾通道只勉强给出行军线，不伪造其余）。

## 1. 采集数据集
- 用真机在不同分辨率、不同昼夜/季节/界面状态下，截取率土画面（出征面板、大地图、
  雷达告警、行军红线等），建议**每类 ≥ 200 张、总量 ≥ 1500 张**。
- 目录结构：
  ```
  dataset/
    images/train  *.png
    images/val    *.png
    labels/train  *.txt   # YOLO 格式
    labels/val    *.txt
  ```

## 2. 标注
- 用 [LabelImg](https://github.com/labelmeai/labelimg) 或 [Roboflow] 按 **YOLO txt** 格式标注，
  类别 id 必须与 `data.yaml` 完全一致（也与 `DetectionClass.id` 一致，见下）。
- 类别顺序（务必照抄，错位会导致检错目标）：
  | id | 名称 | 含义 |
  |----|------|------|
  | 0 | enemy_tile | 敌对红地 |
  | 1 | resource_tile | 资源地块 |
  | 2 | march_line | 行军线（敌我由端侧颜色通道判） |
  | 3 | fortress | 要塞/关卡/分城/城池等大建筑 |
  | 4 | camp | 营寨/营帐（玩家）/ NPC 营地 |
  | 5 | troop_cavalry | 骑兵部队（微缩行军模型，马上） |
  | 6 | troop_infantry | 步兵部队（弓/枪/盾合并） |
  > 注：按钮/红点/敌袭告警不属 YOLO 契约，走确定性通道；兵种只分骑/步（见第 0 节）。

## 3. 训练
```bash
pip install ultralytics
python tools/train_yolo/train.py --data tools/train_yolo/data.yaml --weights yolov8s.pt --epochs 200 --imgsz 640
```
（`train.py` 已封装好参数，见文件内注释。）

## 4. 导出 ncnn
```bash
# 产出 runs/detect/stzb_yolo/weights/best.pt
yolo export model=runs/detect/stzb_yolo/weights/best.pt format=ncnn imgsz=640
# 得到 best_ncnn_model/model.ncnn.param + model.ncnn.bin
bash tools/train_yolo/export_ncnn.sh path/to/best_ncnn_model
```
`export_ncnn.sh` 会把 `model.ncnn.param/.bin` 重命名并复制到
`client/app/src/main/assets/models/yolov8n_stzb.param` + `.bin`。

## 5. 放入仓库并重打包
- 权重直接提交进仓库（本项目选择"模型二进制入库"，非 LFS）：
  `client/app/src/main/assets/models/yolov8n_stzb.{param,bin}`
- 触发 CI（`build_apk.yml` 已链接 ncnn+OpenCV），产包里 `YoloDetector` 会走**真实主通道**。
- 自检：装机后 logcat 过滤 `YoloDetector`，应出现
  `YOLO 主通道就绪：ncnn 已加载 yolov8n_stzb（真实推理启用）。`
  若看到"回退几何色度通道"，说明权重没进包或维度不符。

## 输入/输出契约（与 native 对齐，改错这里就检不出东西）
- 输入层名 `images`，输出层名 `output0`，输入尺寸 **640×640**，RGB、归一化 1/255、不减均值。
- 输出布局 `[1, 4+numClasses, numAnchors]`（ultralytics ncnn 默认）。
- 若你的导出工具用了别的层名/布局，改 `cpp/yolo/YoloNcnn.cpp` 顶部 `kInputBlob/kOutputBlob`
  与解码段（该文件会打印实际输出形状，便于对照）。

## 6. 大地图多目标采集与标注规格（重锚后 6 类专用）

目标：只训"确定性通道搞不定"的大地图密集/遮挡/跨缩放多目标。

**逐类采集最低量（训练用，每类≥这些才上线）**
- `enemy_tile` / `resource_tile`：每类 ≥200 实例，覆盖不同缩放（近景单块 + 远景成片）。
- `march_line`：≥200，含直线/折线/多线交叠；敌我线都收（端侧按颜色判，标注统一 `march_line`）。
- `fortress` / `camp`：每类 ≥200；`fortress` 覆盖要塞/关隘/分城/城池各外观，`camp` 覆盖玩家营帐与 NPC 营地。
- `troop_cavalry` / `troop_infantry`：各 ≥200，覆盖**密集堆叠/部分遮挡**的行军队群；骑兵=马上模型，步兵=弓/枪/盾等所有下马模型（合成一类）。
- 环景多样性：白天/夜晚、迷雾/浓雾、高对比背色、不同缩放级别各占一定比例。

**标注红线**
- 类别 id 严格照上表；错一位=端侧检错目标。
- **等级不建类**（会爆炸类别空间）：等级交给 `TileStatusDetector` 的 OCR/数字通道，YOLO 只定"类型"。
- **兵种只分骑/步**：行军无独立兵种图标，只能靠微缩模型；弓/枪/盾步兵缩放下不可稳分，合并为 `troop_infantry`。

**确定性优先的取舍（务必先做，能省掉大量训练）**
- 固定布局 UI（按钮/建筑面板图标/兵种旗标，位置固定、外观标准）→ **确定性模板图库**
  （与 Phase C 守军头像 `defender_refs` 同法，用 `OpenCvMatcher`）：零权重、体积小、当天见效。
  代价：需你先裁参考图（每类 ≥5–8 张干净图）。
- 只有大地图**密集/遮挡/跨缩放**的红地/资源地/行军线/要塞/营地/部队群 → 才真正需要 YOLO。
