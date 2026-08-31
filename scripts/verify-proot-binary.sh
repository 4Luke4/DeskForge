#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/proot/version.json"

if [[ $# -lt 2 || $# -gt 3 || ( $# -eq 3 && "$3" != "--allow-unpinned" ) ]]; then
  echo "Usage: $0 RUNTIME_BINARY LOADER_BINARY [--allow-unpinned]" >&2
  exit 2
fi

runtime_binary="$1"
loader_binary="$2"
allow_unpinned="${3:-}"
test -f "${runtime_binary}"
test -x "${runtime_binary}"
test -f "${loader_binary}"
test -x "${loader_binary}"

expected_sha256="$(jq -er '.binary.runtime.sha256' "${manifest}")"
expected_size="$(jq -er '.binary.runtime.sizeBytes' "${manifest}")"
expected_loader_sha256="$(jq -er '.binary.loader.sha256' "${manifest}")"
expected_loader_size="$(jq -er '.binary.loader.sizeBytes' "${manifest}")"
actual_sha256="$(sha256sum "${runtime_binary}" | cut -d ' ' -f 1)"
actual_size="$(stat --format='%s' "${runtime_binary}")"
actual_loader_sha256="$(sha256sum "${loader_binary}" | cut -d ' ' -f 1)"
actual_loader_size="$(stat --format='%s' "${loader_binary}")"

if [[ "${allow_unpinned}" != "--allow-unpinned" ]]; then
  if [[ "${expected_sha256}" =~ ^0+$ || "${expected_size}" -le 0 ||
        "${expected_loader_sha256}" =~ ^0+$ || "${expected_loader_size}" -le 0 ]]; then
    echo "The PRoot binary manifest has not been finalized" >&2
    exit 1
  fi
  test "${actual_sha256}" = "${expected_sha256}"
  test "${actual_size}" = "${expected_size}"
  test "${actual_loader_sha256}" = "${expected_loader_sha256}"
  test "${actual_loader_size}" = "${expected_loader_size}"
fi

readelf_tool="${READELF:-readelf}"
"${readelf_tool}" --file-header "${runtime_binary}" | grep --quiet 'Type:.*DYN'
"${readelf_tool}" --file-header "${runtime_binary}" | grep --quiet 'Machine:.*AArch64'
"${readelf_tool}" --file-header "${loader_binary}" | grep --extended-regexp --quiet 'Type:.*(EXEC|DYN)'
"${readelf_tool}" --file-header "${loader_binary}" | grep --quiet 'Machine:.*AArch64'

dynamic_section="$("${readelf_tool}" --dynamic "${runtime_binary}")"
if grep --extended-regexp --quiet '\((RPATH|RUNPATH|TEXTREL)\)' <<< "${dynamic_section}"; then
  echo "The PRoot executable contains a forbidden dynamic-section entry" >&2
  exit 1
fi

mapfile -t needed_libraries < <(
  sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' <<< "${dynamic_section}" | sort -u
)
for library in "${needed_libraries[@]}"; do
  case "${library}" in
    libc.so|libdl.so) ;;
    *)
      printf 'Unexpected PRoot runtime dependency: %s\n' "${library}" >&2
      exit 1
      ;;
  esac
done

for binary in "${runtime_binary}" "${loader_binary}"; do
  gnu_stack_line="$("${readelf_tool}" --program-headers --wide "${binary}" | grep 'GNU_STACK')"
  if [[ "${gnu_stack_line}" == *E* ]]; then
    printf 'The PRoot artifact requests an executable stack: %s\n' "${binary}" >&2
    exit 1
  fi
done
if ! file "${runtime_binary}" | grep --quiet 'stripped' || ! file "${loader_binary}" | grep --quiet 'stripped'; then
  echo "The packaged PRoot executable must be stripped" >&2
  exit 1
fi

printf 'Verified PRoot ELF: sha256=%s size=%s loader_sha256=%s loader_size=%s\n' \
  "${actual_sha256}" "${actual_size}" "${actual_loader_sha256}" "${actual_loader_size}"
