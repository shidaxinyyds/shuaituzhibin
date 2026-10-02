cmake_minimum_required(VERSION 3.22.1)
project(stzb_engine)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 1. 尝试查找 NCNN 与 OpenCV 预编译库
find_package(ncnn QUIET)
find_package(OpenCV QUIET)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# 2. 判断是否具备完整的 NCNN 与 OpenCV 构建环境
if (ncnn_FOUND AND OpenCV_FOUND)
    message(STATUS ">> [RapidOCR] NCNN & OpenCV found! Compiling complete native OCR vision engine.")
    include_directories(
        ocr_lite/include
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
    file(GLOB OCR_SOURCES ocr_lite/src/*.cpp)
    add_library(RapidOcr SHARED
        ${OCR_SOURCES}
        SecurityBridge.cpp
    )
    target_link_libraries(RapidOcr
        ${log-lib}
        ${jnigraphics-lib}
        ncnn
        ${OpenCV_LIBS}
    )
else()
    message(STATUS ">> [RapidOCR] Standard CI/Minimal mode: Compiling RapidOcr with SecurityBridge + Native Bridge Stub.")
    add_library(RapidOcr SHARED
        SecurityBridge.cpp
        OcrStub.cpp
    )
    target_link_libraries(RapidOcr
        ${log-lib}
        ${jnigraphics-lib}
    )
endif()
