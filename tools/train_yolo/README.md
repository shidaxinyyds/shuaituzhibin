# 率土之滨 YOLO 检测模型 · 训练与导出指南

本目录把"从零到能把权重放进 APK"的每一步固化下来。**代码侧（ncnn 推理 + JNI +
Kotlin 集成）已经全部就绪**，缺的只有一份针对率土之滨训练出来的权重。

## 0. 为什么必须自己训练
`YoloDetector.DetectionClass` / `data.yaml` 共 18 个类：基础 11 类（出征/驻守/撤退/确定/取消/
军令红点/敌袭告警/敌对红地/资源地块/行军红线/关卡要塞）+ **Phase D 新增 7 类**
（骑/盾/弓/枪 4 兵种 + 营寨/箭塔/屯田 3 建筑）都是**率土专属**，COCO 等公开预训练权重
一个都检不出。所以没有"下载即用"这条路，只能：采集 → 标注 → 训练 → 导出 ncnn → 放入 assets。
> ⚠️ Phase D 新类**必须先有足量标注再启用训练**；样本不够时该类会欠拟合、宁缺毋滥。
> 无权重时 `YoloDetector` 对它们如实返回空（几何容灾通道不伪造）。

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
- 类别顺序（务必照抄，错位会导致点到错误按钮）：
  | id | 名称 | 含义 |
  |----|------|------|
  | 0 | attack | 出征按钮 |
  | 1 | defend | 驻守按钮 |
  | 2 | retreat | 撤退按钮 |
  | 3 | confirm | 确定按钮 |
  | 4 | cancel | 取消按钮 |
  | 5 | mail_alert | 军令红点 |
  | 6 | radar_alert | 敌袭告警 |
  | 7 | enemy_tile | 敌对红地 |
  | 8 | resource_tile | 资源地块 |
  | 9 | march_redline | 敌军行军红线 |
  | 10 | city_gate | 关卡要塞 |
  | 11 | troop_cavalry | 骑兵 |
  | 12 | troop_shield | 盾兵 |
  | 13 | troop_archer | 弓兵 |
  | 14 | troop_spearman | 枪兵 |
  | 15 | building_camp | 营寨/营帐 |
  | 16 | building_tower | 箭塔 |
  | 17 | building_farm | 屯田 |

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

## 6. Phase D：兵种 / 建筑 采集与标注规格（新增类专用）

目标：把“多兵种混合部队”、“不同等级/外观建筑”、“动态 UI”纳入多目标检测。

**逐类采集最低量（训练用，每类≥这些才上线）**
- 兵种 `troop_*`：每兵种 ≥200 个实例（含单独行军队 + 多兵种混编叠加队）；不分辨率/昼夜。
- 建筑 `building_*`：每类 ≥200；**必须覆盖 Lv3–Lv7 各等级外观、且含施工中/已建成两态**。
- 环景多样性：白天/夜晚、迷雾/浓雾、高对比背色、不同缩放级别（地图平移/缩放）各占一定比例。

**标注红线**
- 类别 id 严格照上表；错一位=端侧点错目标。混编部队按**可见子图标逐个框**，不要整堆一大框。
- 等级不单独建类（会爆炸类别空间）：等级交给 TileStatusDetector 的 OCR/数字通道，
  YOLO 只定建筑“类型”。

**确定性优先的取舍（auto_label.py 本意）**
- 固定布局 UI（兵种图标/建筑图标位置固定、外观标准）→ 优先**确定性模板图库**（与 Phase C
  守军头像 `defender_refs` 同法，用 `OpenCvMatcher`）：零权重、体积小、不依赖训练。
  代价：需你先裁参考图（每类/每等级 ≥5–8 张干净图）。
- 大地图**密集/遮挡/跨缩放**的部队与建筑→ 才真正需要 YOLO（方案 B）。
