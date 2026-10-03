#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""标定辅助：给截图叠 100px 像素网格并标数字，便于精确读取头像槽位边界。
用法: python _calibrate_grid.py <输入图> <输出图>
"""
import sys
import cv2

def main():
    src, dst = sys.argv[1], sys.argv[2]
    img = cv2.imread(src)
    if img is None:
        print("READ_FAIL", src); sys.exit(1)
    h, w = img.shape[:2]
    print("SIZE", w, "x", h)
    for x in range(0, w, 100):
        cv2.line(img, (x, 0), (x, h), (0, 255, 0), 1)
        cv2.putText(img, str(x), (x + 2, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
    for y in range(0, h, 100):
        cv2.line(img, (0, y), (w, y), (0, 255, 0), 1)
        cv2.putText(img, str(y), (4, y + 16), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 0, 255), 2)
    cv2.imwrite(dst, img)
    print("WROTE", dst)

if __name__ == "__main__":
    main()
