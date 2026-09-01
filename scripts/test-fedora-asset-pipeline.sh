#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lib/fedora-asset-filesystem.sh
source "${repository_root}/scripts/lib/fedora-asset-filesystem.sh"

working_directory="$(mktemp --directory "${RUNNER_TEMP:-/tmp}/deskforge-fedora-test.XXXXXX")"
lower="${working_directory}/mount/lower"
upper="${working_directory}/mount/upper"
work="${working_directory}/mount/work"
merged="${working_directory}/mount/merged"

cleanup() {
  deskforge_unmount_erofs_overlay "${merged}" "${lower}"
  sudo rm -rf -- "${working_directory}"
}
trap cleanup EXIT

mkdir -p "${working_directory}/base/usr/bin" "${working_directory}/base/etc"
printf 'base-xvnc\n' > "${working_directory}/base/usr/bin/Xvnc"
printf 'base-marker\n' > "${working_directory}/base/etc/fedora-release"
chmod 0555 "${working_directory}/base/usr/bin/Xvnc"
mkfs.erofs -T 0 "${working_directory}/rootfs.erofs" "${working_directory}/base" > /dev/null

deskforge_mount_erofs_overlay \
  "${working_directory}/rootfs.erofs" "${lower}" "${upper}" "${work}" "${merged}"

mkdir -p "${working_directory}/rpm-payload/usr/bin"
printf 'overlay-xvnc\n' > "${working_directory}/rpm-payload/usr/bin/Xvnc"
chmod 0755 "${working_directory}/rpm-payload/usr/bin/Xvnc"
(
  cd "${working_directory}/rpm-payload"
  find . -print0 | cpio --null --create --format=newc --quiet
) > "${working_directory}/payload.cpio"
deskforge_apply_cpio_overlay "${working_directory}/payload.cpio" "${merged}"
printf '#!/usr/bin/env sh\nexit 0\n' > "${working_directory}/desktop-session"
sudo install -D --mode=0755 \
  "${working_directory}/desktop-session" "${merged}/usr/libexec/deskforge/desktop-session"

test "$(cat "${merged}/usr/bin/Xvnc")" = "overlay-xvnc"
test "$(cat "${merged}/etc/fedora-release")" = "base-marker"
test "$(stat --format='%a' "${merged}/usr/bin/Xvnc")" = "755"

deskforge_stream_deterministic_archive \
  "${merged}" "${working_directory}/first.tar.gz" "${working_directory}/first.size" 2
deskforge_stream_deterministic_archive \
  "${merged}" "${working_directory}/second.tar.gz" "${working_directory}/second.size" 2
cmp "${working_directory}/first.tar.gz" "${working_directory}/second.tar.gz"
cmp "${working_directory}/first.size" "${working_directory}/second.size"
test "$(pigz --decompress --stdout "${working_directory}/first.tar.gz" | wc --bytes)" = \
  "$(tr -d '[:space:]' < "${working_directory}/first.size")"

deskforge_unmount_erofs_overlay "${merged}" "${lower}"
mkdir -p "${working_directory}/extracted"
tar --extract --gzip --file="${working_directory}/first.tar.gz" \
  --directory="${working_directory}/extracted"
test "$(cat "${working_directory}/extracted/usr/bin/Xvnc")" = "overlay-xvnc"
test "$(cat "${working_directory}/extracted/etc/fedora-release")" = "base-marker"
test -x "${working_directory}/extracted/usr/libexec/deskforge/desktop-session"

fixture_repository="${working_directory}/repository"
mkdir -p "${fixture_repository}/config/distros"
for pack in fedora_xfce_44 fedora_xfce_44_1 fedora_xfce_44_2 fedora_xfce_44_3; do
  mkdir -p "${fixture_repository}/${pack}/src/main/assets"
done
cp "${working_directory}/first.tar.gz" \
  "${fixture_repository}/fedora_xfce_44/src/main/assets/rootfs.part00"
archive_size="$(stat --format='%s' "${working_directory}/first.tar.gz")"
archive_sha256="$(sha256sum "${working_directory}/first.tar.gz" | cut --delimiter=' ' --fields=1)"
uncompressed_size="$(tr -d '[:space:]' < "${working_directory}/first.size")"
cp "${repository_root}/config/distros/fedora-xfce-44.json" \
  "${fixture_repository}/config/distros/fedora-xfce-44.json"
jq --null-input \
  --arg archiveSha256 "${archive_sha256}" \
  --argjson archiveSizeBytes "${archive_size}" \
  --argjson uncompressedSizeBytes "${uncompressed_size}" \
  '{
    schemaVersion: 2,
    distroId: "fedora-xfce-44",
    release: "44",
    desktopHostVersion: "1.16.2-4.fc44",
    archiveSha256: $archiveSha256,
    archiveSizeBytes: $archiveSizeBytes,
    uncompressedSizeBytes: $uncompressedSizeBytes,
    parts: [{
      packName: "fedora_xfce_44",
      fileName: "rootfs.part00",
      sizeBytes: $archiveSizeBytes,
      sha256: $archiveSha256
    }]
  }' > "${fixture_repository}/fedora_xfce_44/src/main/assets/payload-manifest.json"
"${repository_root}/scripts/verify-fedora-payload.sh" "${fixture_repository}"

manifest="${fixture_repository}/fedora_xfce_44/src/main/assets/payload-manifest.json"
cp "${manifest}" "${manifest}.valid"
jq '.parts[0].packName = "fedora_xfce_44_1"' "${manifest}.valid" > "${manifest}"
if "${repository_root}/scripts/verify-fedora-payload.sh" "${fixture_repository}"; then
  echo "Fedora verifier accepted an out-of-order pack" >&2
  exit 1
fi
cp "${manifest}.valid" "${manifest}"
jq '.archiveSizeBytes = 4000000001' "${manifest}.valid" > "${manifest}"
if "${repository_root}/scripts/verify-fedora-payload.sh" "${fixture_repository}"; then
  echo "Fedora verifier accepted an oversized archive" >&2
  exit 1
fi
cp "${manifest}.valid" "${manifest}"
printf 'corrupt' >> "${fixture_repository}/fedora_xfce_44/src/main/assets/rootfs.part00"
if "${repository_root}/scripts/verify-fedora-payload.sh" "${fixture_repository}"; then
  echo "Fedora verifier accepted a corrupted part" >&2
  exit 1
fi
