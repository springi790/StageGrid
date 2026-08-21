cmake_minimum_required(VERSION 3.22.1)
project(stagegrid_audio LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

include(FetchContent)

# Signalsmith Stretch 1.3.2 (MIT). Pin the exact upstream bugfix commit so local and CI
# builds use the same DSP implementation. Git clone is used instead of a GitHub archive
# because Stretch depends on its signalsmith-linear submodule.
FetchContent_Declare(
    signalsmith_stretch
    GIT_REPOSITORY https://github.com/Signalsmith-Audio/signalsmith-stretch.git
    GIT_TAG 222093b4cc13ddb4d07c826bc3c1559326091731
    GIT_SHALLOW FALSE
    GIT_SUBMODULES_RECURSE TRUE
)
FetchContent_MakeAvailable(signalsmith_stretch)

add_library(stagegrid_audio SHARED
    jni_bridge.cpp
    NativeAudioEngine.cpp
    WavReader.cpp
)

target_compile_features(stagegrid_audio PRIVATE cxx_std_20)
target_compile_options(stagegrid_audio PRIVATE -Wall -Wextra -Wpedantic)
target_include_directories(stagegrid_audio PRIVATE ${signalsmith_stretch_SOURCE_DIR})

target_link_libraries(stagegrid_audio
    oboe::oboe
    android
    log
)
