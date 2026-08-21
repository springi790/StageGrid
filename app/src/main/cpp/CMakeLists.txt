cmake_minimum_required(VERSION 3.22.1)
project(stagegrid_audio LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

include(FetchContent)

# Signalsmith Stretch 1.3.2 (MIT), pinned to an exact upstream commit. We intentionally
# populate its sources without adding its CMake project because upstream currently requires
# CMake 3.24 while StageGrid's Android toolchain is pinned to CMake 3.22.1.
FetchContent_Declare(
    signalsmith_stretch
    GIT_REPOSITORY https://github.com/Signalsmith-Audio/signalsmith-stretch.git
    GIT_TAG 222093b4cc13ddb4d07c826bc3c1559326091731
    GIT_SHALLOW FALSE
)
FetchContent_GetProperties(signalsmith_stretch)
if(NOT signalsmith_stretch_POPULATED)
    FetchContent_Populate(signalsmith_stretch)
endif()

# Stretch 1.3.2 includes Signalsmith Linear as "signalsmith-linear/...". Fetch the exact
# dependency release and mirror it into a stable include-directory name instead of executing
# either upstream CMakeLists.txt.
FetchContent_Declare(
    signalsmith_linear
    GIT_REPOSITORY https://github.com/Signalsmith-Audio/linear.git
    GIT_TAG 0.1.2
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(signalsmith_linear)
if(NOT signalsmith_linear_POPULATED)
    FetchContent_Populate(signalsmith_linear)
endif()
set(STAGEGRID_SIGNALSMITH_INCLUDE_ROOT "${CMAKE_CURRENT_BINARY_DIR}/signalsmith-deps")
file(MAKE_DIRECTORY "${STAGEGRID_SIGNALSMITH_INCLUDE_ROOT}/signalsmith-linear")
file(COPY "${signalsmith_linear_SOURCE_DIR}/" DESTINATION "${STAGEGRID_SIGNALSMITH_INCLUDE_ROOT}/signalsmith-linear")

add_library(stagegrid_audio SHARED
    jni_bridge.cpp
    NativeAudioEngine.cpp
    WavReader.cpp
)

target_compile_features(stagegrid_audio PRIVATE cxx_std_20)
target_compile_options(stagegrid_audio PRIVATE -Wall -Wextra -Wpedantic)
target_include_directories(stagegrid_audio PRIVATE
    ${signalsmith_stretch_SOURCE_DIR}
    ${STAGEGRID_SIGNALSMITH_INCLUDE_ROOT}
)

target_link_libraries(stagegrid_audio
    oboe::oboe
    android
    log
)
