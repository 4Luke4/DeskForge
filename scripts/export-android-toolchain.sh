#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/android/toolchain.properties"
destination="${GITHUB_ENV:?GITHUB_ENV must identify the GitHub Actions environment file}"

property_mappings=(
  "javaVersion:JAVA_VERSION"
  "compileSdkPackage:ANDROID_PLATFORM"
  "buildToolsVersion:ANDROID_BUILD_TOOLS"
  "ndkVersion:ANDROID_NDK"
  "cmakeVersion:ANDROID_CMAKE"
  "abi:ANDROID_ABI"
)

for mapping in "${property_mappings[@]}"; do
  property="${mapping%%:*}"
  exported_name="${mapping#*:}"
  value="$(sed -n "s/^${property}=//p" "${manifest}")"
  if [[ -z "${value}" || "${value}" == *$'\n'* ]]; then
    printf 'Android toolchain property is missing or duplicated: %s\n' "${property}" >&2
    exit 1
  fi
  printf '%s=%s\n' "${exported_name}" "${value}" >> "${destination}"
done
