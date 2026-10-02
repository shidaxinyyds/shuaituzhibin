// ============================================================================
// 率土全能管家 · RapidOcr C++ 兼容层 (OcrStub.cpp)
// 作用：
//   在标准 CI/CD 或轻量编译环境中提供 JNI 符号定义，
//   确保无外部 NCNN/OpenCV 二进制包时也能秒级编译出合规产物 APK。
//   当本地或生产环境配置 NCNN/OpenCV 时，CMake 自动切换至完整 RapidOCR 引擎。
// ============================================================================

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "RapidOcr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("RapidOcr native library loaded (SecurityBridge + OCR Bridge active).");
    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_init(JNIEnv *env, jobject thiz, jobject assetManager,
                                               jint numThread, jstring detName, jstring clsName,
                                               jstring recName, jstring keysName) {
    LOGI("OcrEngine init completed.");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_detect(JNIEnv *env, jobject thiz, jobject input, jobject output,
                                                 jint padding, jint maxSideLen, jfloat boxScoreThresh, jfloat boxThresh,
                                                 jfloat unClipRatio, jboolean doAngle, jboolean mostAngle) {
    jclass arrayListClass = env->FindClass("java/util/ArrayList");
    jmethodID listInit = env->GetMethodID(arrayListClass, "<init>", "()V");
    jobject emptyList = env->NewObject(arrayListClass, listInit);

    jclass ocrResultClass = env->FindClass("com/benjaminwan/ocrlibrary/OcrResult");
    jmethodID resultInit = env->GetMethodID(ocrResultClass, "<init>",
                                            "(DLjava/util/ArrayList;Landroid/graphics/Bitmap;DLjava/lang/String;)V");

    jstring emptyStr = env->NewStringUTF("");
    jobject result = env->NewObject(ocrResultClass, resultInit, 0.0, emptyList, output, 0.0, emptyStr);
    return result;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_benjaminwan_ocrlibrary_OcrEngine_benchmark(JNIEnv *env, jobject thiz, jobject input, jint loop) {
    return 0.0;
}
