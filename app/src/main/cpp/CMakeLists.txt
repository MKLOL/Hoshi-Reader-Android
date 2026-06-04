cmake_minimum_required(VERSION 3.22.1)

project(hoshi_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_BRIDGE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hoshidicts-kotlin-bridge")
set(HOSHIDICTS_ROOT "${HOSHIDICTS_BRIDGE_ROOT}/app/src/main/cpp/hoshidicts")

add_subdirectory(
    "${HOSHIDICTS_ROOT}"
    "${CMAKE_CURRENT_BINARY_DIR}/hoshidicts"
    EXCLUDE_FROM_ALL
)

add_library(hoshidicts_jni SHARED
    "${HOSHIDICTS_BRIDGE_ROOT}/app/src/main/cpp/hoshidicts_jni.cpp"
)

target_include_directories(hoshidicts_jni PRIVATE
    "${HOSHIDICTS_ROOT}/include"
)

target_link_libraries(hoshidicts_jni PRIVATE
    hoshidicts
)

# --------------------------------------------------------------------------
# Offline on-device translation (fork addition)
#
# llama.cpp powers the "translate without internet" feature. It is fetched at
# configure time, pinned to a release tag, and built CPU-only — no curl, no
# examples/tests/tools/server — so the native build stays self-contained and
# the resulting .so is just the core inference engine.
# --------------------------------------------------------------------------
include(FetchContent)

set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Use ggml's own pthread threadpool. The NDK ships no system libgomp, and
# static-linking OpenMP is an extra failure mode we don't need for a phone CPU.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# ARM-only KleidiAI int8 matmul micro-kernels — a large speedup for quantized inference on CPUs
# with i8mm/dotprod (Snapdragon 8 Gen 5 etc.), with a runtime fallback when the features are
# absent. Only valid for arm64; the x86_64 (emulator) ABI must keep it off.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b9496
    GIT_SHALLOW TRUE
)
# llama.cpp is a C++17 codebase. This project sets a global CMAKE_CXX_STANDARD=23 (for the
# dictionary engine), which leaks into the fetched targets and makes some llama.cpp sources
# (e.g. src/models/t5.cpp) fail to compile under C++23. Build llama at C++17, then restore the
# project standard for our own targets. The llama.cpp boundary we use is pure C (extern "C"),
# so mixing C++ standards across the link is safe.
set(HOSHI_SAVED_CXX_STANDARD ${CMAKE_CXX_STANDARD})
set(CMAKE_CXX_STANDARD 17)
FetchContent_MakeAvailable(llama)
set(CMAKE_CXX_STANDARD ${HOSHI_SAVED_CXX_STANDARD})

add_library(hoshi_llama_jni SHARED
    "${CMAKE_CURRENT_SOURCE_DIR}/hoshi_llama_jni.cpp"
)

target_link_libraries(hoshi_llama_jni PRIVATE
    llama
    android
    log
)
