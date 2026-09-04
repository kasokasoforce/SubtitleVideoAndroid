cmake_minimum_required(VERSION 3.22.1)
project(subtitle_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/whispercpp")
if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp source is missing. Run scripts/fetch_whisper.sh before building.")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_DIR}" "${CMAKE_BINARY_DIR}/whisper")

add_library(subtitle_whisper SHARED whisper_jni.cpp)
target_include_directories(subtitle_whisper PRIVATE "${WHISPER_DIR}/include")
find_library(log-lib log)
target_link_libraries(subtitle_whisper PRIVATE whisper ${log-lib} android)
target_compile_options(subtitle_whisper PRIVATE -O3 -fvisibility=hidden)
