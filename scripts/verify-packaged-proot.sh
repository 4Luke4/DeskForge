#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
approved_directory="${repository_root}/app/src/main/jniLibs/arm64-v8a"

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 APK_OR_AAB [...]" >&2
  exit 2
fi

test -f "${approved_directory}/libproot.so"
test -f "${approved_directory}/libproot-loader.so"
temporary_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/deskforge-packaged-proot.XXXXXX")"
trap 'rm -rf -- "${temporary_directory}"' EXIT

for archive in "$@"; do
  test -f "${archive}"
  for file_name in libproot.so libproot-loader.so; do
    mapfile -t entries < <(
      while IFS= read -r entry; do
        case "${entry}" in
          lib/arm64-v8a/"${file_name}"|*/lib/arm64-v8a/"${file_name}")
            printf '%s\n' "${entry}"
            ;;
        esac
      done < <(unzip -Z1 "${archive}")
    )
    if [[ ${#entries[@]} -ne 1 ]]; then
      printf 'Expected exactly one %s in %s, found %d\n' \
        "${file_name}" "${archive}" "${#entries[@]}" >&2
      exit 1
    fi
    packaged_binary="${temporary_directory}/$(basename "${archive}").${file_name}"
    unzip -p "${archive}" "${entries[0]}" > "${packaged_binary}"
    cmp --silent "${approved_directory}/${file_name}" "${packaged_binary}"
  done
  printf 'Verified packaged PRoot bytes in %s\n' "${archive}"
done
