# CMakeLists.txt – Handwriting Engine (Native C++)
# Place this file in the same directory as handwriting_engine.cpp
cmake_minimum_required(VERSION 3.10)

project("handwriting_engine")

# Enable ARM NEON intrinsics (required for the engine)
if(${ANDROID_ABI} STREQUAL "armeabi-v7a" OR ${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
endif()

# Add the shared library
add_library(
    handwriting_engine
    SHARED
    handwriting_engine.cpp
)

# Link against the Android log library (optional, for debugging)
find_library(log-lib log)
target_link_libraries(handwriting_engine ${log-lib})

# Optional: enable C++17 for std::clamp etc.
target_compile_features(handwriting_engine PRIVATE cxx_std_17)

# Ensure NEON is enabled globally
target_compile_options(handwriting_engine PRIVATE -DENABLE_NEON)