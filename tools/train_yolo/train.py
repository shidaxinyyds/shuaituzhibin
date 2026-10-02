#!/usr/bin/env python3
"""率土之滨 YOLOv8 训练封装。

依赖：pip install ultralytics

用法：
    python tools/train_yolo/train.py \
        --data tools/train_yolo/data.yaml \
        --weights yolov8s.pt --epochs 200 --imgsz 640 --batch 16

产出 runs/detect/<name>/weights/best.pt —— 接着用 export_ncnn.sh 导出 ncnn。
关键约束：imgsz 必须与 native 侧 YoloNcnn.cpp 的 inputSize（640）一致，
否则推理时 letterbox 尺寸对不上，检出的框会整体错位。
"""
import argparse


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="tools/train_yolo/data.yaml")
    ap.add_argument("--weights", default="yolov8s.pt", help="预训练底模（COCO 起步即可，之后学的是率土类别）")
    ap.add_argument("--epochs", type=int, default=200)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--name", default="stzb_yolo")
    ap.add_argument("--device", default="0", help="GPU 编号；CPU 训练填 cpu")
    args = ap.parse_args()

    from ultralytics import YOLO

    model = YOLO(args.weights)
    model.train(
        data=args.data,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        name=args.name,
        device=args.device,
        # 率土 UI 多为小目标（红点/细红线），提高小目标权重
        box=7.5, cls=0.5, dfl=1.5,
    )
    metrics = model.val(data=args.data, imgsz=args.imgsz, device=args.device)
    print(f"\n训练完成。mAP50-95={metrics.box.map:.3f} mAP50={metrics.box.map50:.3f}")
    print("下一步：yolo export model=runs/detect/%s/weights/best.pt format=ncnn imgsz=%d"
          % (args.name, args.imgsz))


if __name__ == "__main__":
    main()
