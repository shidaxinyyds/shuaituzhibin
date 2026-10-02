# 率土之滨 YOLO 检测模型 · 训练与导出指南

本目录把"从零到能把权重放进 APK"的每一步固化下来。**代码侧（ncnn 推理 + JNI +
Kotlin 集成）已经全部就绪**，缺的只有一份针对率土之滨训练出来的权重。

## 0. 为什么必须自己训练
`YoloDetector.DetectionClass` 里的 11 个类（出征/驻守/撤退/确定/取消/军令红点/
敌袭告警/敌对红地/资源地块/行军红线/关卡要塞）是**率土专属**，COCO 等公开预训练权重
一个都检不出。所以没有"下载即用"这条路，只能：采集 → 标注 → 训练 → 导出 ncnn → 放入 assets。

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
