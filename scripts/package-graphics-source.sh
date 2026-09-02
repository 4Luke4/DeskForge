#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/graphics/virgl.json"
output="${1:?output archive is required}"
working_directory="${RUNNER_TEMP:-/tmp}/deskforge-graphics-source"

rm -rf -- "${working_directory}"
mkdir -p -- "${working_directory}/source/patches" "$(dirname "${output}")"

download() {
  local selector="$1"
  local name="$2"
  local url size sha256
  url="$(jq -er "${selector}.sourceUrl" "${manifest}")"
  size="$(jq -er "${selector}.sizeBytes" "${manifest}")"
  sha256="$(jq -er "${selector}.sha256" "${manifest}")"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "${url}" --output "${working_directory}/source/${name}"
  [[ "$(stat --format='%s' "${working_directory}/source/${name}")" == "${size}" ]]
  printf '%s  %s\n' "${sha256}" "${working_directory}/source/${name}" | \
    sha256sum --check --strict
}

download '.renderer' "virglrenderer-$(jq -r '.renderer.version' "${manifest}").tar.gz"
download '.buildDependencies.libepoxy' \
  "libepoxy-$(jq -r '.buildDependencies.libepoxy.version' "${manifest}").tar.gz"
cp -- "${manifest}" "${working_directory}/source/virgl.json"
cp -- "${repository_root}/config/graphics/deskforge_graphics_jni.c" \
  "${working_directory}/source/deskforge_graphics_jni.c"
while IFS= read -r patch_path; do
  cp -- "${repository_root}/${patch_path}" "${working_directory}/source/patches/$(basename "${patch_path}")"
done < <(jq -r '.patches[]' "${manifest}")

tar --sort=name --mtime='@1683936000' --owner=0 --group=0 --numeric-owner \
  --create --gzip --file "${output}" --directory "${working_directory}" source
