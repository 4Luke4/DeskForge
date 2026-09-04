#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/display/runtime.json"

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 OUTPUT_ARCHIVE WORK_DIRECTORY" >&2
  exit 2
fi

output_archive="$(realpath --canonicalize-missing "$1")"
work_directory="$(realpath --canonicalize-missing "$2")"
runner_temporary_root="${RUNNER_TEMP:-/tmp}"
case "${work_directory}" in
  "${repository_root}/build/"*|"${runner_temporary_root}/"*) ;;
  *)
    echo "Display-source work directory must remain under the repository build or runner temp directory" >&2
    exit 1
    ;;
esac
case "${output_archive}" in
  "${work_directory}"|"${work_directory}/"*)
    echo "Display-source output must be outside its disposable work directory" >&2
    exit 1
    ;;
esac

rm -rf -- "${work_directory}"
mkdir -p -- "${work_directory}/corresponding-source/archives" \
  "${work_directory}/corresponding-source/config/display" \
  "${work_directory}/corresponding-source/scripts" "$(dirname "${output_archive}")"

download_component() {
  local selector="$1"
  local archive_name="$2"
  local destination url expected_size expected_sha256
  destination="${work_directory}/corresponding-source/archives/${archive_name}"
  url="$(jq -er "${selector}.sourceUrl" "${manifest}")"
  expected_size="$(jq -er "${selector}.sizeBytes" "${manifest}")"
  expected_sha256="$(jq -er "${selector}.sha256" "${manifest}")"

  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "${url}" --output "${destination}"
  [[ "$(stat --format='%s' "${destination}")" == "${expected_size}" ]]
  printf '%s  %s\n' "${expected_sha256}" "${destination}" | sha256sum --check --strict
}

xorg_server_version="$(jq -er '.server.version' "${manifest}")"
xorgproto_version="$(jq -er '.buildDependencies.xorgproto.version' "${manifest}")"
download_component '.server' "xorg-server-${xorg_server_version}.tar.xz"
download_component '.buildDependencies.xorgproto' "xorgproto-${xorgproto_version}.tar.xz"

cp -- "${manifest}" "${work_directory}/corresponding-source/config/display/"
cp -- "${repository_root}/config/display/README.md" \
  "${work_directory}/corresponding-source/config/display/"
cp -- "${repository_root}/scripts/package-display-source.sh" \
  "${work_directory}/corresponding-source/scripts/"
if [[ -d "${repository_root}/config/display/patches" ]]; then
  cp -R -- "${repository_root}/config/display/patches" \
    "${work_directory}/corresponding-source/config/display/"
fi

tar --extract --xz --to-stdout \
  --file "${work_directory}/corresponding-source/archives/xorg-server-${xorg_server_version}.tar.xz" \
  "xorg-server-${xorg_server_version}/COPYING" \
  > "${work_directory}/corresponding-source/COPYING.Xorg-server"

tar --directory "${work_directory}/corresponding-source" --sort=name --mtime='@1683936000' \
  --owner=0 --group=0 --numeric-owner --format=posix --create --file=- . | \
  gzip -n > "${output_archive}"
