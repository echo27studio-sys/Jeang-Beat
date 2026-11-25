cmake_minimum_required(VERSION 3.22.1)
project(jeangbeat_audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(jeangbeat_audio SHARED
    engine/DrumKit.cpp
    engine/Voice.cpp
    engine/Effects.cpp
    engine/Engine.cpp
    OboeHost.cpp
    jni_bridge.cpp)

target_include_directories(jeangbeat_audio PRIVATE engine)

# Always optimise the engine, even in debug APKs: unoptimised DSP code causes
# audio dropouts on phones.
target_compile_options(jeangbeat_audio PRIVATE -O2 -Wall -Wextra)

target_link_libraries(jeangbeat_audio PRIVATE oboe::oboe android log)

# ---- MP3 export: LAME (LGPL) as its own shared library, linked dynamically ----
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)
add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PRIVATE ${LAME_DIR} ${LAME_DIR}/include ${LAME_DIR}/libmp3lame)
target_include_directories(mp3lame PUBLIC ${LAME_DIR}/include)
target_compile_definitions(mp3lame PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame PRIVATE -O2 -w)

add_library(jeangbeat_mp3 SHARED mp3_jni.cpp)
target_compile_options(jeangbeat_mp3 PRIVATE -O2 -Wall)
target_link_libraries(jeangbeat_mp3 PRIVATE mp3lame)
