#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_directory="${repository_root}/build/audio-transport-test"
mkdir -p "${output_directory}"

"${CXX:-c++}" \
  -std=c++20 \
  -Wall \
  -Wextra \
  -Werror \
  -I"${repository_root}/app/src/main/cpp" \
  "${repository_root}/app/src/test/cpp/audio_ring_buffer_test.cpp" \
  -o "${output_directory}/audio_ring_buffer_test"
"${output_directory}/audio_ring_buffer_test"
