#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
binary="${1:?graphics runtime path is required}"
manifest="${repository_root}/config/graphics/virgl.json"
toolchain="${repository_root}/config/android/toolchain.properties"

[[ -f "${binary}" && -x "${binary}" ]]
[[ "$(jq -er '.schemaVersion' "${manifest}")" == "1" ]]
[[ "$(jq -er '.binary.abi' "${manifest}")" == "$(sed -n 's/^abi=//p' "${toolchain}")" ]]
[[ "$(jq -er '.binary.minimumApi' "${manifest}")" == "$(sed -n 's/^minSdk=//p' "${toolchain}")" ]]
readelf --file-header "${binary}" | grep --extended-regexp --quiet 'Class:.*ELF64'
readelf --file-header "${binary}" | grep --extended-regexp --quiet 'Machine:.*AArch64'
readelf --dynamic "${binary}" | grep --fixed-strings --quiet 'Shared library: [libEGL.so]'
readelf --dynamic "${binary}" | grep --fixed-strings --quiet 'Shared library: [libGLESv3.so]'
if readelf --dynamic "${binary}" | grep --fixed-strings --quiet 'TEXTREL'; then
  echo "Graphics runtime contains text relocations" >&2
  exit 1
fi
if readelf --program-headers "${binary}" | grep --extended-regexp --quiet 'GNU_STACK.*RWE'; then
  echo "Graphics runtime has an executable stack" >&2
  exit 1
fi
