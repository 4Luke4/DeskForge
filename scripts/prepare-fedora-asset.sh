#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/distros/fedora-xfce-44.json"
working_directory="${RUNNER_TEMP:-/tmp}/deskforge-fedora-asset"
output_archive="${working_directory}/rootfs.tar.gz"
uncompressed_size_file="${working_directory}/rootfs.tar.size"
source_output="${working_directory}/corresponding-source"
lower_directory="${working_directory}/mount/lower"
upper_directory="${working_directory}/mount/upper"
overlay_work_directory="${working_directory}/mount/work"
rootfs_directory="${working_directory}/mount/merged"

# shellcheck source=scripts/lib/fedora-asset-filesystem.sh
source "${repository_root}/scripts/lib/fedora-asset-filesystem.sh"

cleanup_mounts() {
  deskforge_unmount_erofs_overlay "${rootfs_directory}" "${lower_directory}"
}
trap cleanup_mounts EXIT

guest_is_executable() {
  local path="$1"
  local mode

  test -f "${path}"
  mode="$(stat --format='%a' "${path}")"
  (( (8#${mode} & 8#111) != 0 ))
}

image_url="$(jq -r '.imageUrl' "${manifest}")"
image_name="$(jq -r '.fileName' "${manifest}")"
expected_image_size="$(jq -r '.sizeBytes' "${manifest}")"
expected_sha256="$(jq -r '.sha256' "${manifest}")"
checksum_url="$(jq -r '.checksumUrl' "${manifest}")"
part_size="$(jq -r '.assetDelivery.partSizeBytes' "${manifest}")"
maximum_parts="$(jq -r '.assetDelivery.maximumParts' "${manifest}")"
maximum_archive_size="$(jq -r '.assetDelivery.maximumArchiveSizeBytes' "${manifest}")"
workspace_integration_version="$(jq -r '.workspaceIntegrationVersion' "${manifest}")"
mapfile -t pack_names < <(jq -r '.assetDelivery.packNames[]' "${manifest}")

rm -rf "${working_directory}"
mkdir -p "${working_directory}" "${source_output}"

stage_started="${SECONDS}"
curl --fail --location --retry 3 --silent --show-error \
  "${checksum_url}" --output "${working_directory}/CHECKSUM"
curl --fail --location --retry 3 --silent --show-error \
  https://fedoraproject.org/fedora.gpg --output "${working_directory}/fedora.gpg"
gpgv --keyring "${working_directory}/fedora.gpg" "${working_directory}/CHECKSUM"
grep --fixed-strings --quiet "SHA256 (${image_name}) = ${expected_sha256}" "${working_directory}/CHECKSUM"
mkdir -p "${working_directory}/rpmdb"
# RPM 6 accepts armored public keys, while Fedora publishes the verified multi-key GPG keyring.
gpg --batch --no-options --no-default-keyring \
  --keyring "${working_directory}/fedora.gpg" \
  --armor --export > "${working_directory}/fedora-rpm.asc"
test -s "${working_directory}/fedora-rpm.asc"
rpmkeys --dbpath "${working_directory}/rpmdb" --import "${working_directory}/fedora-rpm.asc"

curl --fail --location --retry 3 --silent --show-error \
  "${image_url}" --output "${working_directory}/${image_name}"
test "$(stat --format='%s' "${working_directory}/${image_name}")" = "${expected_image_size}"
echo "${expected_sha256}  ${working_directory}/${image_name}" | sha256sum --check --strict
printf 'Fedora verification and download: %ss\n' "$((SECONDS - stage_started))"

# Fedora 44 retains the historical squashfs.img name for its EROFS live filesystem.
stage_started="${SECONDS}"
xorriso -osirrox on -indev "${working_directory}/${image_name}" \
  -extract /LiveOS/squashfs.img "${working_directory}/squashfs.img"
# Avoid materializing the immutable rootfs: OverlayFS redirects only verified package updates and
# the DeskForge bootstrap to the runner's writable filesystem.
deskforge_mount_erofs_overlay \
  "${working_directory}/squashfs.img" \
  "${lower_directory}" \
  "${upper_directory}" \
  "${overlay_work_directory}" \
  "${rootfs_directory}"
guest_is_executable "${rootfs_directory}/usr/bin/startxfce4"
printf 'Fedora image extraction and mount: %ss\n' "$((SECONDS - stage_started))"

# Overlay only immutable Fedora packages. Dependencies must already be present in the signed spin;
# missing runtime libraries are detected below rather than resolved from mutable repository state.
stage_started="${SECONDS}"
while IFS=$'\t' read -r package_name package_url package_size package_sha256; do
  package_path="${working_directory}/${package_name}.rpm"
  package_cpio="${working_directory}/${package_name}.cpio"
  curl --fail --location --retry 3 --silent --show-error \
    "${package_url}" --output "${package_path}"
  test "$(stat --format='%s' "${package_path}")" = "${package_size}"
  echo "${package_sha256}  ${package_path}" | sha256sum --check --strict
  rpmkeys --dbpath "${working_directory}/rpmdb" --checksig "${package_path}" | grep --fixed-strings --quiet "digests signatures OK"
  rpm2cpio "${package_path}" > "${package_cpio}"
  deskforge_apply_cpio_overlay "${package_cpio}" "${rootfs_directory}"
done < <(jq -r '.desktopHost.packages[] | [.name, .url, .sizeBytes, .sha256] | @tsv' "${manifest}")

source_url="$(jq -r '.desktopHost.source.url' "${manifest}")"
source_size="$(jq -r '.desktopHost.source.sizeBytes' "${manifest}")"
source_sha256="$(jq -r '.desktopHost.source.sha256' "${manifest}")"
source_path="${source_output}/tigervnc.src.rpm"
curl --fail --location --retry 3 --silent --show-error "${source_url}" --output "${source_path}"
test "$(stat --format='%s' "${source_path}")" = "${source_size}"
echo "${source_sha256}  ${source_path}" | sha256sum --check --strict
rpmkeys --dbpath "${working_directory}/rpmdb" --checksig "${source_path}" | grep --fixed-strings --quiet "digests signatures OK"

sudo install -D --mode=0755 \
  "${repository_root}/config/distros/desktop-session.sh" \
  "${rootfs_directory}/usr/libexec/deskforge/desktop-session"
sudo install -D --mode=0755 \
  "${repository_root}/config/distros/guest-session.sh" \
  "${rootfs_directory}/usr/libexec/deskforge/guest-session"
audio_config_path="${working_directory}/deskforge-audio.conf"
"${repository_root}/scripts/render-pipewire-audio-config.sh" "${audio_config_path}"
sudo install -D --mode=0644 \
  "${audio_config_path}" \
  "${rootfs_directory}/etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf"
guest_is_executable "${rootfs_directory}/usr/bin/Xvnc"
guest_is_executable "${rootfs_directory}/usr/bin/startxfce4"
guest_is_executable "${rootfs_directory}/usr/libexec/deskforge/desktop-session"
guest_is_executable "${rootfs_directory}/usr/libexec/deskforge/guest-session"
for guest_executable in env bash dbus-run-session mkdir chmod rm seq sleep; do
  guest_is_executable "${rootfs_directory}/usr/bin/${guest_executable}"
done
while IFS= read -r guest_executable; do
  guest_is_executable "${rootfs_directory}${guest_executable}"
done < <(jq -r '.audioHost.requiredExecutables[]' "${manifest}")
test -f "${rootfs_directory}/etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf"
test ! -L "${rootfs_directory}/etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf"
grep --fixed-strings --quiet 'module-pipe-sink' \
  "${rootfs_directory}/etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf"
grep --fixed-strings --quiet 'module-pipe-source' \
  "${rootfs_directory}/etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf"
if grep --extended-regexp --quiet 'tcp:|server.address|native-protocol-tcp' \
  "${rootfs_directory}/etc/pipewire/pipewire-pulse.conf.d/deskforge-audio.conf"; then
  echo "DeskForge audio configuration must not expose a network transport" >&2
  exit 1
fi

audio_packages_json='[]'
while IFS= read -r package_name; do
  package_identity="$(sudo rpm --root "${rootfs_directory}" --query \
    --queryformat '%{NAME}-%{VERSION}-%{RELEASE}.%{ARCH}' "${package_name}")"
  test -n "${package_identity}"
  audio_packages_json="$(jq --arg identity "${package_identity}" '. + [$identity]' \
    <<<"${audio_packages_json}")"
done < <(jq -r '.audioHost.requiredPackages[]' "${manifest}")
jq -r '.[]' <<<"${audio_packages_json}" > "${source_output}/fedora-audio-packages.txt"
while read -r library; do
  find "${rootfs_directory}/usr/lib64" "${rootfs_directory}/usr/lib" \
    -name "${library}" -print -quit | grep --quiet .
done < <(readelf --dynamic "${rootfs_directory}/usr/bin/Xvnc" | \
  sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
interpreter="$(readelf --program-headers "${rootfs_directory}/usr/bin/Xvnc" | \
  sed -n 's/.*Requesting program interpreter: \([^]]*\)].*/\1/p')"
test -n "${interpreter}"
test -e "${rootfs_directory}${interpreter}"
printf 'Fedora verified desktop overlay: %ss\n' "$((SECONDS - stage_started))"

# Stream the merged view directly so the runner never writes an expanded rootfs or intermediate tar.
stage_started="${SECONDS}"
deskforge_stream_deterministic_archive \
  "${rootfs_directory}" "${output_archive}" "${uncompressed_size_file}"
cleanup_mounts
printf 'Fedora deterministic packaging: %ss\n' "$((SECONDS - stage_started))"

archive_size="$(stat --format='%s' "${output_archive}")"
archive_sha256="$(sha256sum "${output_archive}" | cut --delimiter=' ' --fields=1)"
uncompressed_size="$(tr -d '[:space:]' < "${uncompressed_size_file}")"
if (( archive_size > maximum_archive_size || archive_size > part_size * maximum_parts )); then
  echo "Generated rootfs exceeds the configured multi-pack capacity: ${archive_size}" >&2
  exit 1
fi

for pack_name in "${pack_names[@]}"; do
  assets="${repository_root}/${pack_name}/src/main/assets"
  find "${assets}" -maxdepth 1 -type f \( -name 'rootfs.part*' -o -name 'payload-manifest.json' \) -delete
done

split --bytes="${part_size}" --numeric-suffixes=0 --suffix-length=2 "${output_archive}" "${working_directory}/rootfs.part"
mapfile -t parts < <(find "${working_directory}" -maxdepth 1 -type f -name 'rootfs.part*' -print | sort)
if (( ${#parts[@]} == 0 || ${#parts[@]} > ${#pack_names[@]} )); then
  echo "Unexpected Fedora payload part count: ${#parts[@]}" >&2
  exit 1
fi

parts_json='[]'
for index in "${!parts[@]}"; do
  part="${parts[$index]}"
  pack_name="${pack_names[$index]}"
  part_name="$(basename "${part}")"
  destination="${repository_root}/${pack_name}/src/main/assets/${part_name}"
  mv "${part}" "${destination}"
  size="$(stat --format='%s' "${destination}")"
  sha256="$(sha256sum "${destination}" | cut --delimiter=' ' --fields=1)"
  parts_json="$(jq --arg pack "${pack_name}" --arg file "${part_name}" \
    --argjson size "${size}" --arg sha256 "${sha256}" \
    '. + [{packName: $pack, fileName: $file, sizeBytes: $size, sha256: $sha256}]' \
    <<<"${parts_json}")"
done

jq --null-input \
  --arg distroId "$(jq -r '.id' "${manifest}")" \
  --arg release "$(jq -r '.release' "${manifest}")" \
  --arg desktopHostVersion "$(jq -r '.desktopHost.version' "${manifest}")" \
  --argjson workspaceIntegrationVersion "${workspace_integration_version}" \
  --argjson audioHostPackages "${audio_packages_json}" \
  --arg archiveSha256 "${archive_sha256}" \
  --argjson archiveSizeBytes "${archive_size}" \
  --argjson uncompressedSizeBytes "${uncompressed_size}" \
  --argjson parts "${parts_json}" \
  '{
    schemaVersion: 3,
    distroId: $distroId,
    release: $release,
    desktopHostVersion: $desktopHostVersion,
    workspaceIntegrationVersion: $workspaceIntegrationVersion,
    audioHostPackages: $audioHostPackages,
    archiveSha256: $archiveSha256,
    archiveSizeBytes: $archiveSizeBytes,
    uncompressedSizeBytes: $uncompressedSizeBytes,
    parts: $parts
  }' > "${repository_root}/fedora_xfce_44/src/main/assets/payload-manifest.json"

"${repository_root}/scripts/verify-fedora-payload.sh" "${repository_root}"
