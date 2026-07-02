cmake_minimum_required(VERSION 3.22.1)
project(airus_native VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =============================================================
# 1. Tambahkan Oboe sebagai dependency (fetch dari GitHub)
# =============================================================
include(FetchContent)
FetchContent_Declare(
        oboe
        GIT_REPOSITORY https://github.com/google/oboe.git
        GIT_TAG        1.9.3
)
FetchContent_MakeAvailable(oboe)

# =============================================================
# 1B. libFLAC (Fetch from GitHub)
# =============================================================
FetchContent_Declare(
        flac
        GIT_REPOSITORY https://github.com/xiph/flac.git
        GIT_TAG        1.5.0
)

# Konfigurasi FLAC: Disable extra components
set(BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING  OFF CACHE BOOL "" FORCE)
set(BUILD_DOCS     OFF CACHE BOOL "" FORCE)
set(WITH_OGG       OFF CACHE BOOL "" FORCE)
set(BUILD_CXXLIBS  OFF CACHE BOOL "" FORCE)

# Fix for Android stdio.h conflicts with libFLAC compat.h
add_definitions(-DHAVE_FSEEKO -DHAVE_FTELLO)

FetchContent_MakeAvailable(flac)

# =============================================================
# 2. Source files Native Engine
# =============================================================
add_library(
        airus_engine
        SHARED
        airus_engine.cpp
        audio/OboeEngine.cpp
        audio/GaplessPlayer.cpp
        audio/UsbAudioDriver.cpp
        dsp/ParametricEQ.cpp
        dsp/ReplayGain.cpp
        dsp/Crossfeed.cpp
        decoder/FlacDecoder.cpp
        decoder/WavDecoder.h
        decoder/DsdDecoder.cpp
        decoder/WavDecoder.cpp
)

# =============================================================
# 3. Include directories
# =============================================================
target_include_directories(airus_engine PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${oboe_SOURCE_DIR}/include
)

# =============================================================
# 4. Link libraries
# =============================================================
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        airus_engine
        oboe                # Oboe audio engine
        FLAC                # libFLAC
        ${log-lib}          # Android logging
        ${android-lib}      # Android native APIs
        OpenSLES            # fallback audio (Android < 8)
)

# =============================================================
# 5. Compiler flags untuk DSP performance
# =============================================================
target_compile_options(airus_engine PRIVATE
        -O2
        -ffast-math
        -fno-exceptions     # reduce binary size
)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(airus_engine PRIVATE -mfpu=neon)
endif()
