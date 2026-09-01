#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output_directory="${repository_root}/build/rfb-protocol-test"
mkdir -p "${output_directory}"

"${CXX:-c++}" \
  -std=c++20 \
  -Wall \
  -Wextra \
  -Werror \
  -I"${repository_root}/app/src/main/cpp" \
  "${repository_root}/app/src/test/cpp/rfb_protocol_test.cpp" \
  "${repository_root}/app/src/main/cpp/rfb_clipboard.cpp" \
  -lz \
  -o "${output_directory}/rfb_protocol_test"
"${output_directory}/rfb_protocol_test"
