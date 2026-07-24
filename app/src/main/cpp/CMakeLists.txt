cmake_minimum_required(VERSION 3.22.1)

project("thepowerofmind")

add_library(
    native-lib
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_compile_features(native-lib PRIVATE cxx_std_17)

target_link_libraries(
    native-lib
    ${log-lib}
)
