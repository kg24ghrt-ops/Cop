cmake_minimum_required(VERSION 3.22)
project(native_renderer)

add_library(native_renderer SHARED
    PaperRenderer.cpp
    jni_paper.cpp
)

target_include_directories(native_renderer PRIVATE .)
target_compile_features(native_renderer PRIVATE cxx_std_17)
target_link_libraries(native_renderer log GLESv3)