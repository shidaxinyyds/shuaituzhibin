// ============================================================================
// 率土全能管家 · RapidOCR 空桩 (OcrStub.cpp) —— 仅在未链接 ncnn/OpenCV 时编译
//
// 作用：
//   在缺少 ncnn / OpenCV 构建依赖时提供 JNI 符号定义，让工程仍能编译出 APK。
//
// ⚠️ 重要行为约定（本次修复的核心）：
//   本文件过去把 `init` 无条件返回 JNI_TRUE，而 `detect` 返回一个"完全合法但内容
//   为空"的 OcrResult（strRes=""、textBlocks=[]）。后果是整条链路表现为：
//     * OcrManager.init() 打印「模型已加载进内存」并返回 true；
//     * StzbUiMatcher.classifyGameState() 恒返回 UNKNOWN；
//     * StzbUiMatcher.findButtons() 恒返回空表；
//     * 所有依赖文字识别的战术流程 100% 失败，但日志里看不出任何异常。
//   也就是说，「OCR 根本不存在」被伪装成了「OCR 正常但暂时没识别到文字」，
//   这正是"识别不到坐标与意图"长期无法定位的根本原因。
//
//   现在改为**响亮失败**：`init` 返回 JNI_FALSE，Java 侧 `OcrEngine` 构造抛异常，
//   `OcrManager.init()` 明确报错并保持 engine 为 null（detect 返回 null）。
//   功能状态与修复前一致（仍然无法识别），但原因变得可诊断——
//   用户在 logcat 里能直接看到「native OCR 未编译 / 缺少 ncnn」。
//
//   要让 OCR 真正可用，需在构建期为 CMake 提供 ncnn 与 OpenCV，
//   使 CMakeLists.txt 走完整引擎分支（本文件即不再参与编译）。
// ============================================================================

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "RapidOcr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("RapidOcr native library loaded (SecurityBridge + OCR STUB).");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_init(JNIEnv *env, jobject thiz, jobject assetManager,
                                               jint numThread, jstring detName, jstring clsName,
                                               jstring recName, jstring keysName) {
    LOGE("================================================================");
    LOGE("native OCR 未编译：本 APK 链接的是 OcrStub 空桩，没有任何推理能力。");
    LOGE("原因：构建期 CMake 未找到 ncnn / OpenCV，走了 CMakeLists.txt 的空桩分支。");
    LOGE("影响：场景判定、按键定位、坐标读取等全部依赖文字的识别都会恒失败。");
    LOGE("修复：为构建环境提供 ncnn 与 OpenCV 后重新打包。");
    LOGE("================================================================");
    // 刻意返回失败，让 Java 层如实报错，而不是返回一个"内容为空的成功结果"。
    return JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_detect(JNIEnv *env, jobject thiz, jobject input, jobject output,
                                                 jint padding, jint maxSideLen, jfloat boxScoreThresh, jfloat boxThresh,
                                                 jfloat unClipRatio, jboolean doAngle, jboolean mostAngle) {
    // 正常情况下 init 已失败、Java 层根本不会持有 engine，因此不会走到这里。
    // 保留一个合法但显式可辨的空结果，作为最后一道兜底。
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID listInit = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject emptyList = env->NewObject(arrayListClass, listInit);

    jclass ocrResultClass = env->FindClass("com/benjaminwan/ocrlibrary/OcrResult");
    jmethodID resultInit = env->GetMethodID(ocrResultClass, "<init>",
                                            "(DLjava/util/ArrayList;Landroid/graphics/Bitmap;DLjava/lang/String;)V");

    jstring emptyStr = env->NewStringUTF("");
    return env->NewObject(ocrResultClass, resultInit, 0.0, emptyList, output, 0.0, emptyStr);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_benchmark(JNIEnv *env, jobject thiz, jobject input, jint loop) {
    // 空桩耗时恒为 0；上层用它区分"真引擎"与"空桩"。
    return 0.0;
}
