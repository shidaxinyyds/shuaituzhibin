cmake_minimum_required(VERSION 3.22.1)
project(stzb_engine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 1. 尝试查找 NCNN 与 OpenCV 预编译库
#
# 允许外部指定 ncnn 位置，便于 CI 或本地把预编译包接进来：
#   ./gradlew assembleRelease -PncnnDir=/path/to/ncnn-android
# （gradle 侧把该属性转成 -Dncnn_DIR 传给 CMake）
if (DEFINED ncnn_DIR AND NOT ncnn_DIR STREQUAL "")
    message(STATUS ">> [RapidOCR] 使用外部指定的 ncnn 目录: ${ncnn_DIR}")
    list(APPEND CMAKE_PREFIX_PATH "${ncnn_DIR}")
endif()

if (DEFINED OpenCV_DIR AND NOT OpenCV_DIR STREQUAL "")
    message(STATUS ">> [RapidOCR] 使用外部指定的 OpenCV 目录: ${OpenCV_DIR}")
    list(APPEND CMAKE_PREFIX_PATH "${OpenCV_DIR}")
endif()

find_package(ncnn QUIET)
find_package(OpenCV QUIET)

if (DEFINED ncnn_DIR AND NOT ncnn_FOUND)
    message(FATAL_ERROR ">> [RapidOCR] 外部指定了 ncnn_DIR=${ncnn_DIR}，但 find_package(ncnn) 未能找到！请检查路径！")
endif()
if (DEFINED OpenCV_DIR AND NOT OpenCV_FOUND)
    message(FATAL_ERROR ">> [RapidOCR] 外部指定了 OpenCV_DIR=${OpenCV_DIR}，但 find_package(OpenCV) 未能找到！请检查路径！")
endif()

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# 2. 判断是否具备完整的 NCNN 与 OpenCV 构建环境
if (ncnn_FOUND AND OpenCV_FOUND)
    message(STATUS ">> [RapidOCR] NCNN & OpenCV found! Compiling complete native OCR vision engine.")

    # ⚠️ OpenCV 的头文件目录必须显式加进来。
    #
    # ocr_lite 的几乎所有头文件都 `#include <opencv2/core.hpp>` / `<opencv2/imgproc.hpp>`，
    # 而下面链接用的是 `${OpenCV_LIBS}` —— 那是**库文件路径的列表**，
    # 不是 imported target，**不会**传递 INTERFACE_INCLUDE_DIRECTORIES。
    # 因此漏掉这一行，构建会以 `'opencv2/core.hpp' file not found` 失败，
    # 表现为"明明 provision 了 ncnn 和 OpenCV 却编不出来"。
    if (NOT OpenCV_INCLUDE_DIRS)
        message(FATAL_ERROR
            "找到了 OpenCV，但没有拿到 OpenCV_INCLUDE_DIRS，无法编译 ocr_lite"
            "（它 #include <opencv2/core.hpp>）。请检查 OpenCV_DIR 是否指向 "
            "opencv-android-sdk/sdk/native/jni。")
    endif()

    include_directories(
        ocr_lite/include
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${OpenCV_INCLUDE_DIRS}
    )

    # ncnn 若提供 include 变量也一并加入（正常情况下 ncnn 是 imported target，
    # 会自动传递头文件目录；这里只是兜底，避免某些打包方式的 config 不带 INTERFACE 目录）。
    if (DEFINED ncnn_INCLUDE_DIRS AND NOT ncnn_INCLUDE_DIRS STREQUAL "")
        include_directories(${ncnn_INCLUDE_DIRS})
    endif()

    # 含 ocr_lite/src/main.cpp —— 它承载 OcrEngine 的三个 JNI 入口
    # （init / detect / benchmark），不是命令行 main，必须参与编译。
    #
    # yolo/YoloNcnn.cpp 承载真实 ncnn YOLOv8 推理与 YoloNative 的两个 JNI 入口
    # （nativeInit / nativeDetect）；它依赖 ncnn + OpenCV + ocr_lite 的
    # BitmapUtils/OcrUtils，因此只能待在 ncnn 分支。
    #
    # 注意：本分支**不能**包含 OcrStub.cpp，否则同名 JNI 符号会重复定义、链接失败。
    file(GLOB OCR_SOURCES ocr_lite/src/*.cpp)
    add_library(RapidOcr SHARED
        ${OCR_SOURCES}
        SecurityBridge.cpp
        yolo/YoloNcnn.cpp
    )

    # ⚠️ 必须显式重新开启 C++ 异常与 RTTI。
    #
    # clipper.cpp / BitmapUtils.cpp / YoloNcnn.cpp 都用了 try/throw/catch。
    # build.gradle 的 cppFlags 虽然写了 -fexceptions -frtti，但 NDK 工具链与
    # ncnn/OpenCV 依赖目标会在命令行更靠后的位置追加 -fno-exceptions -fno-rtti，
    # clang 以最后一个为准 → 异常被关掉，报 "cannot use 'try' with exceptions disabled"。
    # 之前空桩分支不编译这些文件，所以从未暴露。
    #
    # 源级 COMPILE_FLAGS 排在整条编译命令的最末尾，稳赢覆盖；target 级作为兜底。
    set_source_files_properties(
        ${OCR_SOURCES}
        SecurityBridge.cpp
        yolo/YoloNcnn.cpp
        PROPERTIES COMPILE_FLAGS "-fexceptions -frtti"
    )
    target_compile_options(RapidOcr PRIVATE -fexceptions -frtti)

    target_link_libraries(RapidOcr
        ${log-lib}
        ${jnigraphics-lib}
        ncnn
        ${OpenCV_LIBS}
    )
else()
    # 空桩分支：能编译出 APK，但 **OCR 完全不可用**。
    # OcrStub.cpp 的 init 会返回失败，让上层如实报错，而不是静默返回空结果。
    message(WARNING
        ">> [RapidOCR] 未找到 ncnn/OpenCV，将编译 OCR 空桩 (OcrStub.cpp)。"
        "本产物内文字识别不可用：场景判定、按键定位、坐标读取等依赖文字的识别会全部失败。"
        "如需可用 OCR，请为构建环境提供 ncnn 与 OpenCV。")
    add_library(RapidOcr SHARED
        SecurityBridge.cpp
        OcrStub.cpp
    )
    target_link_libraries(RapidOcr
        ${log-lib}
        ${jnigraphics-lib}
    )
endif()
