cmake_minimum_required(VERSION 3.22.1)
project(privatevoice_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Vendored by tools/setup_whispercpp.py (gitignored). Unlike upstream's example
# CMakeLists — which walks seven directories up assuming it lives inside the
# whisper.cpp repo — this points at our own vendored copy.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whispercpp)

if(NOT EXISTS ${WHISPER_DIR}/src/whisper.cpp)
    message(FATAL_ERROR
        "whisper.cpp sources missing at ${WHISPER_DIR}.\n"
        "Run:  python tools/setup_whispercpp.py")
endif()

# Always optimize the native code, including in debug builds. An unoptimized
# ggml is 10-50x slower — slow enough that a warm-up decode looked like a hang
# on-device (two threads pegged at 100% for minutes on 30s of audio) before
# this was in place. Appending to the _DEBUG flags rather than overriding
# CMAKE_BUILD_TYPE keeps -g, so native crashes are still debuggable; the last
# -O flag on the command line wins.
set(CMAKE_C_FLAGS_DEBUG   "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

# ggml ships its own CMake project; build it as a subdirectory so we inherit
# its ARM feature detection (dotprod/i8mm/fp16) rather than reimplementing it.
set(GGML_BUILD_TESTS  OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_DIR}/ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml)

add_library(whispercpp_jni SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    jni_whisper.c
)

target_include_directories(whispercpp_jni PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
)

target_compile_definitions(whispercpp_jni PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION="vendored"
)

# The A35's Cortex-A78 cores are ARMv8.2-A with fp16 arithmetic. Upstream ships
# a separate _v8fp16_va library plus runtime /proc/cpuinfo sniffing to decide
# between variants; we target one known device, so we compile for it directly
# and skip the dispatch machinery. Revisit if device support widens.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(whispercpp_jni PRIVATE -march=armv8.2-a+fp16)
endif()

if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whispercpp_jni PRIVATE
        -O3 -ffunction-sections -fdata-sections
        -fvisibility=hidden -fvisibility-inlines-hidden)
    target_link_options(whispercpp_jni PRIVATE
        -Wl,--gc-sections -Wl,--exclude-libs,ALL)
endif()

find_library(LOG_LIB log)
target_link_libraries(whispercpp_jni ${LOG_LIB} android ggml)
