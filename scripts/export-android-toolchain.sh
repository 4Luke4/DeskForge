#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/android/toolchain.properties"
destination="${GITHUB_ENV:?GITHUB_ENV must identify the GitHub Actions environment file}"
outputs="${GITHUB_OUTPUT:?GITHUB_OUTPUT must identify the GitHub Actions output file}"

property_mappings=(
  "javaVersion:JAVA_VERSION:java_version"
  "compileSdkPackage:ANDROID_PLATFORM:android_platform"
  "buildToolsVersion:ANDROID_BUILD_TOOLS:android_build_tools"
  "ndkVersion:ANDROID_NDK:android_ndk"
  "cmakeVersion:ANDROID_CMAKE:android_cmake"
  "abi:ANDROID_ABI:android_abi"
)

for mapping in "${property_mappings[@]}"; do
  property="${mapping%%:*}"
  remaining="${mapping#*:}"
  exported_name="${remaining%%:*}"
  output_name="${remaining#*:}"
  value="$(sed -n "s/^${property}=//p" "${manifest}")"
  if [[ -z "${value}" || "${value}" == *$'\n'* ]]; then
    printf 'Android toolchain property is missing or duplicated: %s\n' "${property}" >&2
    exit 1
  fi
  printf '%s=%s\n' "${exported_name}" "${value}" >> "${destination}"
  printf '%s=%s\n' "${output_name}" "${value}" >> "${outputs}"
done
