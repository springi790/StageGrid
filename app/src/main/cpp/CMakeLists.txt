cmake_minimum_required(VERSION 3.22.1)
project(stagegrid_audio LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(stagegrid_audio SHARED
    jni_bridge.cpp
    NativeAudioEngine.cpp
    WavReader.cpp
)

target_compile_features(stagegrid_audio PRIVATE cxx_std_20)
target_compile_options(stagegrid_audio PRIVATE -Wall -Wextra -Wpedantic)

target_link_libraries(stagegrid_audio
    oboe::oboe
    android
    log
)
