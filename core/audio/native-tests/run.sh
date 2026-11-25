#!/usr/bin/env bash
# Builds and runs the audio engine unit tests on the host machine.
set -euo pipefail
cd "$(dirname "$0")"
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release > /dev/null
cmake --build build
./build/engine_test
echo "MP3 (LAME)"
./build/mp3_test
