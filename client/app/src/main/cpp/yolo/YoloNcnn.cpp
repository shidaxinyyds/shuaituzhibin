// ============================================================================
// 率土全能管家 · 真实 ncnn YOLOv8 目标检测 + JNI 桥接 (YoloNcnn.cpp)
//
// 编译条件：仅在 CMakeLists.txt 找到 ncnn + OpenCV 时，随 RapidOcr 一起编译。
//          空桩分支（缺 ncnn/OpenCV）不含本文件，因此 Kotlin 侧调用会抛
//          UnsatisfiedLinkError —— YoloNative 捕获后诚实回退到几何色度检测，
//          绝不伪装成"检测成功"。
//
// 设计取舍（诚实声明）：
//   * 本文件实现的是 **标准 YOLOv8 ncnn 推理**：letterbox 前处理 → ncnn 前向 →
//     解码 [channels=4+numClasses, anchors] 输出 → 全局 NMS → 坐标还原到原图。
//   * 它需要一份**针对率土之滨训练并导出为 ncnn** 的权重
//     （`assets/models/yolov8n_stzb.param` + `.bin`，见 tools/train_yolo/）。
//     通用 COCO 权重检不出"出征/驻守/红线"这些游戏专属类别，故权重必须自训练。
//   * ultralytics 的 `yolo export format=ncnn` 默认输出层名为 `output0`、
//     输入层名为 `images`、布局为 [1, 4+nc, anchors]。若你的导出工具用了别的
//     层名/布局，改 kInputBlob / kOutputBlob 两处常量即可（本文件会打印实际形状）。
// ============================================================================

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <algorithm>
#include <cmath>

#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>

#include "net.h"          // ncnn

#include "BitmapUtils.h"  // bitmapToMat（来自 ocr_lite，同分支编译）
#include "OcrUtils.h"     // jstringTostring（来自 ocr_lite，同分支编译）

#define YLOGI(...) __android_log_print(ANDROID_LOG_INFO, "YoloNcnn", __VA_ARGS__)
#define YLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "YoloNcnn", __VA_ARGS__)

namespace {

    constexpr const char *kInputBlob = "images";
    constexpr const char *kOutputBlob = "output0";

    struct Detection {
        int cls;
        float x1, y1, x2, y2, score;
    };

    float computeIoU(const Detection &a, const Detection &b) {
        float xx1 = std::max(a.x1, b.x1);
        float yy1 = std::max(a.y1, b.y1);
        float xx2 = std::min(a.x2, b.x2);
        float yy2 = std::min(a.y2, b.y2);
        float w = std::max(0.0f, xx2 - xx1);
        float h = std::max(0.0f, yy2 - yy1);
        float inter = w * h;
        float areaA = (a.x2 - a.x1) * (a.y2 - a.y1);
        float areaB = (b.x2 - b.x1) * (b.y2 - b.y1);
        float uni = areaA + areaB - inter;
        return uni > 0.0f ? inter / uni : 0.0f;
    }

    class YoloNcnn {
    public:
        bool load(const std::string &paramPath, const std::string &binPath,
                  int inputSize, int numThreads) {
            net_opt().num_threads = numThreads > 0 ? numThreads : 4;
            net_opt().use_vulkan_compute = false;

            int r1 = net.load_param(paramPath.c_str());
            if (r1 != 0) {
                YLOGE("load_param 失败 rc=%d path=%s", r1, paramPath.c_str());
                return false;
            }
            int r2 = net.load_model(binPath.c_str());
            if (r2 != 0) {
                YLOGE("load_model 失败 rc=%d path=%s", r2, binPath.c_str());
                return false;
            }
            this->inputSize = inputSize > 0 ? inputSize : 640;
            this->loaded = true;
            YLOGI("YOLO ncnn 权重加载成功: %s (inputSize=%d)", paramPath.c_str(), this->inputSize);
            return true;
        }

        bool isLoaded() const { return loaded; }

        std::vector<Detection> detect(const cv::Mat &bgr, float confThresh, float nmsThresh) {
            std::vector<Detection> out;
            if (!loaded || bgr.empty()) return out;

            const int target = inputSize;
            // ---- letterbox 前处理 ----
            float scale = std::min((float) target / bgr.cols, (float) target / bgr.rows);
            int resizedW = (int) std::lround(bgr.cols * scale);
            int resizedH = (int) std::lround(bgr.rows * scale);
            int padX = (target - resizedW) / 2;
            int padY = (target - resizedH) / 2;

            cv::Mat resized, padded, rgb;
            cv::resize(bgr, resized, cv::Size(resizedW, resizedH));
            cv::copyMakeBorder(resized, padded, padY, target - resizedH - padY,
                               padX, target - resizedW - padX,
                               cv::BORDER_CONSTANT, cv::Scalar(114, 114, 114));
            cv::cvtColor(padded, rgb, cv::COLOR_BGR2RGB);

            std::vector<float> mean_vals;                        // YOLOv8 不减均值
            std::vector<float> norm_vals = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
            ncnn::Mat in = ncnn::Mat::from_pixels(
                    rgb.data, ncnn::Mat::PIXEL_RGB, target, target, mean_vals, norm_vals);

            ncnn::Extractor ex = net.create_extractor();
            ex.input(kInputBlob, in);
            ncnn::Mat pred;
            if (ex.extract(kOutputBlob, pred) != 0) {
                YLOGE("extract('%s') 失败：模型输出层命名与预期不符，请核对导出", kOutputBlob);
                return out;
            }

            // ---- 解码：期望 dims=2, w = 4+numClasses, h = numAnchors ----
            if (pred.dims != 2 && pred.dims != 3) {
                YLOGE("未预期的输出 dims=%d（期望 2 或 3）", pred.dims);
                return out;
            }
            const int channels = pred.w;   // 4 + numClasses
            const int anchors = pred.h;    // numAnchors
            const int numClasses = channels - 4;
            if (numClasses <= 0) {
                YLOGE("输出通道异常 w=%d h=%d（无法推出类别数）", channels, anchors);
                return out;
            }
            YLOGI("输出形状 dims=%d w=%d h=%d → 类别数=%d anchors=%d",
                  pred.dims, channels, anchors, numClasses, anchors);

            std::vector<Detection> cand;
            const float *pdata = (const float *) pred.data;
            for (int a = 0; a < anchors; ++a) {
                const float *p = pdata + (size_t) a * channels;
                float score = p[4];
                int bestCls = 0;
                for (int c = 1; c < numClasses; ++c) {
                    if (p[4 + c] > score) {
                        score = p[4 + c];
                        bestCls = c;
                    }
                }
                if (score < confThresh) continue;

                float cx = p[0], cy = p[1], bw = p[2], bh = p[3];
                float x1 = cx - bw * 0.5f;
                float y1 = cy - bh * 0.5f;
                float x2 = cx + bw * 0.5f;
                float y2 = cy + bh * 0.5f;

                // 还原 letterbox → 原图像素坐标
                x1 = (x1 - padX) / scale;
                y1 = (y1 - padY) / scale;
                x2 = (x2 - padX) / scale;
                y2 = (y2 - padY) / scale;
                x1 = clampf(x1, 0.f, (float) bgr.cols);
                y1 = clampf(y1, 0.f, (float) bgr.rows);
                x2 = clampf(x2, 0.f, (float) bgr.cols);
                y2 = clampf(y2, 0.f, (float) bgr.rows);
                if (x2 <= x1 || y2 <= y1) continue;

                cand.push_back({bestCls, x1, y1, x2, y2, score});
            }

            // ---- 全局 NMS ----
            std::sort(cand.begin(), cand.end(),
                      [](const Detection &l, const Detection &r) { return l.score > r.score; });
            std::vector<bool> removed(cand.size(), false);
            for (size_t i = 0; i < cand.size(); ++i) {
                if (removed[i]) continue;
                out.push_back(cand[i]);
                for (size_t j = i + 1; j < cand.size(); ++j) {
                    if (removed[j]) continue;
                    if (cand[i].cls == cand[j].cls && computeIoU(cand[i], cand[j]) > nmsThresh) {
                        removed[j] = true;
                    }
                }
            }
            return out;
        }

    private:
        ncnn::Net net;
        ncnn::Option &net_opt() { return net.opt; }
        static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

        bool loaded = false;
        int inputSize = 640;
    };

    YoloNcnn *g_yolo = nullptr;

}// namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_stzb_assistant_ai_vision_YoloNative_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring paramPath, jstring binPath,
        jint inputSize, jint numThreads) {
    std::string pp = jstringTostring(env, paramPath);
    std::string bp = jstringTostring(env, binPath);
    if (g_yolo == nullptr) g_yolo = new YoloNcnn();
    bool ok = g_yolo->load(pp, bp, (int) inputSize, (int) numThreads);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_stzb_assistant_ai_vision_YoloNative_nativeDetect(
        JNIEnv *env, jobject /*thiz*/, jobject bitmap, jfloat conf, jfloat nms) {
    if (g_yolo == nullptr || !g_yolo->isLoaded()) {
        return env->NewFloatArray(0);
    }
    cv::Mat rgba, bgr;
    bitmapToMat(env, bitmap, rgba);
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);

    std::vector<Detection> dets = g_yolo->detect(bgr, (float) conf, (float) nms);

    std::vector<float> flat;
    flat.reserve(dets.size() * 6);
    for (const auto &d: dets) {
        flat.push_back((float) d.cls);
        flat.push_back(d.x1);
        flat.push_back(d.y1);
        flat.push_back(d.x2);
        flat.push_back(d.y2);
        flat.push_back(d.score);
    }

    jfloatArray arr = env->NewFloatArray((jsize) flat.size());
    if (arr == nullptr) return nullptr; // NewFloatArray 已在 JVM 侧抛出 OOM
    if (!flat.empty()) {
        env->SetFloatArrayRegion(arr, 0, (jsize) flat.size(), flat.data());
    }
    return arr;
}
