cmake_minimum_required(VERSION 3.22.1)
project(jeangbeat_audio CXX)

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
