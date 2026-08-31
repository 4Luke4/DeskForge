#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 OUTPUT_ARCHIVE" >&2
  exit 2
fi

output_archive="$1"
manifest="${repository_root}/config/proot/version.json"
temporary_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/deskforge-proot-source.XXXXXX")"
staging_directory="${temporary_directory}/corresponding-source"
trap 'rm -rf -- "${temporary_directory}"' EXIT

rm -rf -- "${staging_directory}"
mkdir -p -- "${staging_directory}/archives" "${staging_directory}/config/android" \
  "${staging_directory}/config/proot" \
  "${staging_directory}/scripts"
while IFS=$'\t' read -r name url expected_sha256; do
  destination="${staging_directory}/archives/${name}.tar.gz"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "${url}" --output "${destination}"
  printf '%s  %s\n' "${expected_sha256}" "${destination}" | sha256sum --check --strict
done < <(
  jq -er '["proot-" + .proot.version, .proot.sourceUrl, .proot.sha256] | @tsv' "${manifest}"
  jq -er '["talloc-" + .buildDependencies.talloc.version, .buildDependencies.talloc.sourceUrl, .buildDependencies.talloc.sha256] | @tsv' "${manifest}"
)
cp -- "${repository_root}/config/proot/version.json" "${staging_directory}/config/proot/"
cp -- "${repository_root}/config/android/toolchain.properties" "${staging_directory}/config/android/"
if [[ -d "${repository_root}/config/proot/patches" ]]; then
  cp -R -- "${repository_root}/config/proot/patches" "${staging_directory}/config/proot/"
fi
cp -- "${repository_root}/scripts/build-proot-android.sh" \
  "${repository_root}/scripts/verify-proot-binary.sh" "${staging_directory}/scripts/"
proot_version="$(jq -er '.proot.version' "${manifest}")"
talloc_version="$(jq -er '.buildDependencies.talloc.version' "${manifest}")"
tar --extract --gzip --to-stdout --file "${staging_directory}/archives/proot-${proot_version}.tar.gz" \
  "proot-${proot_version}/COPYING" > "${staging_directory}/COPYING.PRoot"
tar --extract --gzip --to-stdout --file "${staging_directory}/archives/talloc-${talloc_version}.tar.gz" \
  "talloc-${talloc_version}/LICENSE" > "${staging_directory}/LICENSE.talloc"

cat > "${staging_directory}/README.md" <<'EOF'
# DeskForge PRoot corresponding source

This bundle contains the exact upstream archives, DeskForge patches, manifest, license texts, and
build scripts used for the separately executed PRoot runtime. Place the repository at the matching
DeskForge revision, install the Android toolchain declared in the included
`config/android/toolchain.properties`, and run
`scripts/build-proot-android.sh OUTPUT_BINARY OUTPUT_LOADER WORK_DIRECTORY` with `ANDROID_SDK_ROOT`
set. The build script verifies fresh downloads against the included manifest; the `archives`
directory retains the corresponding source independently of upstream availability.

PRoot and talloc retain their upstream identities and licenses. They are not DeskForge proprietary
code and are not linked into the proprietary DeskForge native engine.
EOF

mkdir -p -- "$(dirname "${output_archive}")"
tar --directory "${staging_directory}" --sort=name --mtime='@1683936000' \
  --owner=0 --group=0 --numeric-owner --format=posix --create --file=- . | gzip -n > "${output_archive}"
