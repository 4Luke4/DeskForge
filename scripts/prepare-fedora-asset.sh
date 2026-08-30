#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/distros/fedora-xfce-44.json"
working_directory="${RUNNER_TEMP:-/tmp}/deskforge-fedora-asset"
uncompressed_archive="${working_directory}/rootfs.tar"
output_archive="${repository_root}/fedora_xfce_44/src/main/assets/rootfs.tar.gz"
output_checksum="${repository_root}/fedora_xfce_44/src/main/assets/rootfs.tar.gz.sha256"

image_url="$(jq -r '.imageUrl' "${manifest}")"
image_name="$(jq -r '.fileName' "${manifest}")"
expected_sha256="$(jq -r '.sha256' "${manifest}")"
checksum_url="$(jq -r '.checksumUrl' "${manifest}")"

mkdir -p "${working_directory}"
curl --fail --location --retry 3 "${checksum_url}" --output "${working_directory}/CHECKSUM"
curl --fail --location --retry 3 https://fedoraproject.org/fedora.gpg --output "${working_directory}/fedora.gpg"
gpgv --keyring "${working_directory}/fedora.gpg" "${working_directory}/CHECKSUM"
rg -F "SHA256 (${image_name}) = ${expected_sha256}" "${working_directory}/CHECKSUM" >/dev/null

curl --fail --location --retry 3 "${image_url}" --output "${working_directory}/${image_name}"
echo "${expected_sha256}  ${working_directory}/${image_name}" | sha256sum --check --strict

# The live ISO contains a squashfs image whose rootfs is an ext filesystem image.
xorriso -osirrox on -indev "${working_directory}/${image_name}" \
  -extract /LiveOS/squashfs.img "${working_directory}/squashfs.img"
unsquashfs -no-progress -d "${working_directory}/squashfs" "${working_directory}/squashfs.img"
root_image="$(find "${working_directory}/squashfs" -type f -name rootfs.img -print -quit)"
if [[ -z "${root_image}" ]]; then
  echo "Fedora image does not contain LiveOS/rootfs.img" >&2
  exit 1
fi

# guestfish reads the filesystem without root privileges and retains Linux metadata in the tar.
guestfish --ro -a "${root_image}" -m /dev/sda tar-out / "${uncompressed_archive}"
tar --list --file "${uncompressed_archive}" | rg '(^|/)usr/bin/startxfce4$' >/dev/null
gzip --best --no-name --stdout "${uncompressed_archive}" > "${output_archive}"

# Fail before AAB assembly if the compressed payload cannot fit the current single-pack prototype.
archive_size="$(stat --format='%s' "${output_archive}")"
if (( archive_size > 1500000000 )); then
  echo "Generated rootfs.tar.gz exceeds the 1.5 GB single asset-pack gate: ${archive_size}" >&2
  exit 1
fi

# The application verifies this digest again before atomically activating the root filesystem.
(cd "$(dirname "${output_archive}")" && sha256sum "$(basename "${output_archive}")" > "$(basename "${output_checksum}")")
