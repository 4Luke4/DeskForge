#!/usr/bin/env bash
set -euo pipefail

allow_x86_test_engine=false
if [[ "${1:-}" == "--allow-x86-test-engine" ]]; then
  allow_x86_test_engine=true
  shift
fi

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 [--allow-x86-test-engine] APK_OR_AAB..." >&2
  exit 2
fi

for package in "$@"; do
  [[ -f "${package}" ]]
  entries="$(unzip -Z1 "${package}")"
  if [[ "${package}" == *.apk ]]; then
    grep --fixed-strings --line-regexp --quiet 'lib/arm64-v8a/libdeskforge_graphics.so' <<<"${entries}"
    grep --fixed-strings --line-regexp --quiet 'lib/arm64-v8a/libdeskforge_venus_server.so' <<<"${entries}"
  else
    grep --fixed-strings --line-regexp --quiet 'base/lib/arm64-v8a/libdeskforge_graphics.so' <<<"${entries}"
    grep --fixed-strings --line-regexp --quiet 'base/lib/arm64-v8a/libdeskforge_venus_server.so' <<<"${entries}"
  fi
  non_arm64_entries="$({
    grep --extended-regexp '(^|/)lib/(armeabi|armeabi-v7a|x86|x86_64)/' <<<"${entries}" || true
  })"
  if [[ -n "${non_arm64_entries}" ]] &&
    { [[ "${allow_x86_test_engine}" != true ]] ||
      [[ "${package}" != *.apk ]] ||
      grep --extended-regexp --invert-match --quiet \
        '^lib/x86_64/libdeskforge_engine\.so$' <<<"${non_arm64_entries}"; }; then
    echo "Unexpected non-ARM64 native library in ${package}" >&2
    exit 1
  fi
done
