#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 APK_OR_AAB..." >&2
  exit 2
fi

for package in "$@"; do
  [[ -f "${package}" ]]
  entries="$(unzip -Z1 "${package}")"
  if [[ "${package}" == *.apk ]]; then
    grep --fixed-strings --line-regexp --quiet 'lib/arm64-v8a/libdeskforge_graphics.so' <<<"${entries}"
  else
    grep --fixed-strings --line-regexp --quiet 'base/lib/arm64-v8a/libdeskforge_graphics.so' <<<"${entries}"
  fi
  if grep --extended-regexp --quiet '(^|/)lib/(armeabi|armeabi-v7a|x86|x86_64)/' <<<"${entries}"; then
    echo "Unexpected non-ARM64 native library in ${package}" >&2
    exit 1
  fi
done
