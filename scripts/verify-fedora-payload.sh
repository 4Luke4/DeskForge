#!/usr/bin/env bash
set -euo pipefail

repository_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
distro_manifest="${repository_root}/config/distros/fedora-xfce-44.json"
payload_manifest="${repository_root}/fedora_xfce_44/src/main/assets/payload-manifest.json"

test -f "${distro_manifest}"
test -f "${payload_manifest}"

jq --exit-status --slurpfile distro "${distro_manifest}" '
  $distro[0] as $config |
  .schemaVersion == 2 and
  .distroId == $config.id and
  .release == $config.release and
  .desktopHostVersion == $config.desktopHost.version and
  (.parts | length) > 0 and
  (.parts | length) <= $config.assetDelivery.maximumParts and
  (.parts | length) <= ($config.assetDelivery.packNames | length) and
  .archiveSizeBytes > 0 and
  .archiveSizeBytes <= $config.assetDelivery.maximumArchiveSizeBytes and
  .uncompressedSizeBytes > .archiveSizeBytes and
  .uncompressedSizeBytes <= (24 * 1024 * 1024 * 1024) and
  (.archiveSha256 | test("^[a-f0-9]{64}$")) and
  all(.parts | to_entries[];
    .value.packName == $config.assetDelivery.packNames[.key] and
    .value.fileName == ("rootfs.part" + (if .key < 10 then "0" else "" end) + (.key | tostring)) and
    .value.sizeBytes > 0 and
    .value.sizeBytes <= $config.assetDelivery.partSizeBytes and
    (.value.sha256 | test("^[a-f0-9]{64}$"))) and
  .archiveSizeBytes == ([.parts[].sizeBytes] | add)
' "${payload_manifest}" > /dev/null

declared_part_count=0
actual_archive_size=0
while IFS=$'\t' read -r pack file size sha256; do
  part="${repository_root}/${pack}/src/main/assets/${file}"
  test -f "${part}"
  test ! -L "${part}"
  test "$(stat --format='%s' "${part}")" = "${size}"
  echo "${sha256}  ${part}" | sha256sum --check --strict > /dev/null
  declared_part_count=$((declared_part_count + 1))
  actual_archive_size=$((actual_archive_size + size))
done < <(jq -r '.parts[] | [.packName, .fileName, .sizeBytes, .sha256] | @tsv' "${payload_manifest}")

actual_part_count=0
while IFS= read -r pack; do
  while IFS= read -r -d '' part; do
    actual_part_count=$((actual_part_count + 1))
  done < <(find "${repository_root}/${pack}/src/main/assets" \
    -maxdepth 1 -name 'rootfs.part*' -print0)
done < <(jq -r '.assetDelivery.packNames[]' "${distro_manifest}")
test "${actual_part_count}" = "${declared_part_count}"

test "${actual_archive_size}" = "$(jq -r '.archiveSizeBytes' "${payload_manifest}")"
actual_archive_sha256="$({
  while IFS=$'\t' read -r pack file; do
    cat "${repository_root}/${pack}/src/main/assets/${file}"
  done < <(jq -r '.parts[] | [.packName, .fileName] | @tsv' "${payload_manifest}")
} | sha256sum | cut --delimiter=' ' --fields=1)"
test "${actual_archive_sha256}" = "$(jq -r '.archiveSha256' "${payload_manifest}")"
