cmake_minimum_required(VERSION 3.22.1)

project("handwriting_engine")

# Enforce C++17 standard for modern features (std::clamp, std::vector, thread-safe statics)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------------
# ABI & NEON Configuration
# -----------------------------------------------------------------------------
# - arm64-v8a: NEON is built-in by default. No extra compile flags needed!
# - armeabi-v7a: Needs -mfpu=neon to enable SIMD intrinsics.
# -----------------------------------------------------------------------------
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_compile_options(-mfpu=neon)
    add_definitions(-DENABLE_NEON)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    add_definitions(-DENABLE_NEON)
endif()

# Declare the native shared library
add_library(
    handwriting_engine
    SHARED
    handwriting_engine.cpp
    handwriting_jni.cpp
)

# -----------------------------------------------------------------------------
# NDK Libraries Setup
# -----------------------------------------------------------------------------
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

# Link NDK libraries for logging and high-performance bitmap locking
target_link_libraries(
    handwriting_engine
    ${log-lib}
    ${jnigraphics-lib}
)
