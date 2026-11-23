# Host (desktop) build of the portable engine for unit tests.
# The Android build uses src/main/cpp/CMakeLists.txt instead.
cmake_minimum_required(VERSION 3.18)
project(JeangBeatEngineTests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp/engine)

add_executable(engine_test
    engine_test.cpp
    ${ENGINE_DIR}/DrumKit.cpp
    ${ENGINE_DIR}/Voice.cpp
    ${ENGINE_DIR}/Engine.cpp)
target_include_directories(engine_test PRIVATE ${ENGINE_DIR})
target_compile_options(engine_test PRIVATE -O2 -Wall -Wextra -Werror -fsanitize=address,undefined)
target_link_options(engine_test PRIVATE -fsanitize=address,undefined)

enable_testing()
add_test(NAME engine_test COMMAND engine_test)
