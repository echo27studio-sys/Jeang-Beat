# Host (desktop) build of the portable engine for unit tests.
# The Android build uses src/main/cpp/CMakeLists.txt instead.
cmake_minimum_required(VERSION 3.18)
project(JeangBeatEngineTests C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp/engine)

add_executable(engine_test
    engine_test.cpp
    ${ENGINE_DIR}/DrumKit.cpp
    ${ENGINE_DIR}/Voice.cpp
    ${ENGINE_DIR}/Effects.cpp
    ${ENGINE_DIR}/Engine.cpp)
target_include_directories(engine_test PRIVATE ${ENGINE_DIR})
target_compile_options(engine_test PRIVATE -O2 -Wall -Wextra -Werror -fsanitize=address,undefined)
target_link_options(engine_test PRIVATE -fsanitize=address,undefined)

enable_testing()
add_test(NAME engine_test COMMAND engine_test)

# LAME (MP3) smoke test
set(LAME_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../src/main/cpp/third_party/lame)
file(GLOB LAME_SOURCES ${LAME_DIR}/libmp3lame/*.c)
add_library(mp3lame_host STATIC ${LAME_SOURCES})
set_target_properties(mp3lame_host PROPERTIES LINKER_LANGUAGE C)
target_include_directories(mp3lame_host PRIVATE ${LAME_DIR} ${LAME_DIR}/libmp3lame PUBLIC ${LAME_DIR}/include)
target_compile_definitions(mp3lame_host PRIVATE HAVE_CONFIG_H)
target_compile_options(mp3lame_host PRIVATE -O2 -w)
add_executable(mp3_test mp3_test.cpp)
target_link_libraries(mp3_test PRIVATE mp3lame_host m)
add_test(NAME mp3_test COMMAND mp3_test)
