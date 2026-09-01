#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/distros/fedora-xfce-44.json"
working_directory="${RUNNER_TEMP:-/tmp}/deskforge-fedora-asset"
uncompressed_archive="${working_directory}/rootfs.tar"
rootfs_directory="${working_directory}/rootfs"
output_archive="${working_directory}/rootfs.tar.gz"
source_output="${working_directory}/corresponding-source"

image_url="$(jq -r '.imageUrl' "${manifest}")"
image_name="$(jq -r '.fileName' "${manifest}")"
expected_sha256="$(jq -r '.sha256' "${manifest}")"
checksum_url="$(jq -r '.checksumUrl' "${manifest}")"
part_size="$(jq -r '.assetDelivery.partSizeBytes' "${manifest}")"
maximum_parts="$(jq -r '.assetDelivery.maximumParts' "${manifest}")"
maximum_archive_size="$(jq -r '.assetDelivery.maximumArchiveSizeBytes' "${manifest}")"
mapfile -t pack_names < <(jq -r '.assetDelivery.packNames[]' "${manifest}")

rm -rf "${working_directory}"
mkdir -p "${working_directory}" "${rootfs_directory}" "${source_output}"
curl --fail --location --retry 3 "${checksum_url}" --output "${working_directory}/CHECKSUM"
curl --fail --location --retry 3 https://fedoraproject.org/fedora.gpg --output "${working_directory}/fedora.gpg"
gpgv --keyring "${working_directory}/fedora.gpg" "${working_directory}/CHECKSUM"
grep --fixed-strings --quiet "SHA256 (${image_name}) = ${expected_sha256}" "${working_directory}/CHECKSUM"
mkdir -p "${working_directory}/rpmdb"
# RPM 6 accepts armored public keys, while Fedora publishes the verified multi-key GPG keyring.
gpg --batch --no-options --no-default-keyring \
  --keyring "${working_directory}/fedora.gpg" \
  --armor --export > "${working_directory}/fedora-rpm.asc"
test -s "${working_directory}/fedora-rpm.asc"
rpmkeys --dbpath "${working_directory}/rpmdb" --import "${working_directory}/fedora-rpm.asc"

curl --fail --location --retry 3 "${image_url}" --output "${working_directory}/${image_name}"
echo "${expected_sha256}  ${working_directory}/${image_name}" | sha256sum --check --strict

# Fedora 44 retains the historical squashfs.img name for its EROFS live filesystem.
xorriso -osirrox on -indev "${working_directory}/${image_name}" \
  -extract /LiveOS/squashfs.img "${working_directory}/squashfs.img"
# fsck.erofs validates every inode and compressed extent while extracting the signed, pinned image.
sudo fsck.erofs --extract="${rootfs_directory}" "${working_directory}/squashfs.img"
sudo chown --recursive --no-dereference "$(id -u):$(id -g)" "${rootfs_directory}"
test -x "${rootfs_directory}/usr/bin/startxfce4"

# Overlay only immutable Fedora packages. Dependencies must already be present in the signed spin;
# missing runtime libraries are detected below rather than resolved from mutable repository state.
while IFS=$'\t' read -r package_name package_url package_size package_sha256; do
  package_path="${working_directory}/${package_name}.rpm"
  curl --fail --location --retry 3 "${package_url}" --output "${package_path}"
  test "$(stat --format='%s' "${package_path}")" = "${package_size}"
  echo "${package_sha256}  ${package_path}" | sha256sum --check --strict
  rpmkeys --dbpath "${working_directory}/rpmdb" --checksig "${package_path}" | grep --fixed-strings --quiet "digests signatures OK"
  (cd "${rootfs_directory}" && rpm2cpio "${package_path}" | cpio --extract --make-directories --preserve-modification-time)
done < <(jq -r '.desktopHost.packages[] | [.name, .url, .sizeBytes, .sha256] | @tsv' "${manifest}")

source_url="$(jq -r '.desktopHost.source.url' "${manifest}")"
source_size="$(jq -r '.desktopHost.source.sizeBytes' "${manifest}")"
source_sha256="$(jq -r '.desktopHost.source.sha256' "${manifest}")"
source_path="${source_output}/tigervnc.src.rpm"
curl --fail --location --retry 3 "${source_url}" --output "${source_path}"
test "$(stat --format='%s' "${source_path}")" = "${source_size}"
echo "${source_sha256}  ${source_path}" | sha256sum --check --strict
rpmkeys --dbpath "${working_directory}/rpmdb" --checksig "${source_path}" | grep --fixed-strings --quiet "digests signatures OK"

install -D --mode=0755 \
  "${repository_root}/config/distros/desktop-session.sh" \
  "${rootfs_directory}/usr/libexec/deskforge/desktop-session"
test -x "${rootfs_directory}/usr/bin/Xvnc"
test -x "${rootfs_directory}/usr/bin/startxfce4"
test -x "${rootfs_directory}/usr/libexec/deskforge/desktop-session"
for guest_executable in env bash dbus-run-session mkdir chmod rm seq sleep; do
  test -x "${rootfs_directory}/usr/bin/${guest_executable}"
done
while read -r library; do
  find "${rootfs_directory}/usr/lib64" "${rootfs_directory}/usr/lib" \
    -name "${library}" -print -quit | grep --quiet .
done < <(readelf --dynamic "${rootfs_directory}/usr/bin/Xvnc" | \
  sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
interpreter="$(readelf --program-headers "${rootfs_directory}/usr/bin/Xvnc" | \
  sed -n 's/.*Requesting program interpreter: \([^]]*\)].*/\1/p')"
test -n "${interpreter}"
test -e "${rootfs_directory}${interpreter}"

# Repack deterministically after applying the verified desktop-host overlay.
tar \
  --create \
  --file "${uncompressed_archive}.prepared" \
  --directory "${rootfs_directory}" \
  --format=pax \
  --sort=name \
  --mtime='@0' \
  --owner=0 \
  --group=0 \
  --numeric-owner \
  .
mv "${uncompressed_archive}.prepared" "${uncompressed_archive}"
gzip --best --no-name --stdout "${uncompressed_archive}" > "${output_archive}"

archive_size="$(stat --format='%s' "${output_archive}")"
archive_sha256="$(sha256sum "${output_archive}" | cut --delimiter=' ' --fields=1)"
uncompressed_size="$(stat --format='%s' "${uncompressed_archive}")"
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
  --arg archiveSha256 "${archive_sha256}" \
  --argjson archiveSizeBytes "${archive_size}" \
  --argjson uncompressedSizeBytes "${uncompressed_size}" \
  --argjson parts "${parts_json}" \
  '{
    schemaVersion: 2,
    distroId: $distroId,
    release: $release,
    desktopHostVersion: $desktopHostVersion,
    archiveSha256: $archiveSha256,
    archiveSizeBytes: $archiveSizeBytes,
    uncompressedSizeBytes: $uncompressedSizeBytes,
    parts: $parts
  }' > "${repository_root}/fedora_xfce_44/src/main/assets/payload-manifest.json"
