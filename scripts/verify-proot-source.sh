#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/proot/version.json"
work_directory="$(mktemp -d "${RUNNER_TEMP:-/tmp}/deskforge-proot-verify.XXXXXX")"
trap 'rm -rf -- "${work_directory}"' EXIT

while IFS=$'\t' read -r name url expected_sha256; do
  archive="${work_directory}/${name}.tar.gz"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "${url}" --output "${archive}"
  printf '%s  %s\n' "${expected_sha256}" "${archive}" | sha256sum --check --strict
  tar --extract --gzip --file "${archive}" --directory "${work_directory}" \
    --no-same-owner --no-same-permissions
done < <(
  jq -er '["proot-" + .proot.version, .proot.sourceUrl, .proot.sha256] | @tsv' "${manifest}"
  jq -er '["talloc-" + .buildDependencies.talloc.version, .buildDependencies.talloc.sourceUrl, .buildDependencies.talloc.sha256] | @tsv' "${manifest}"
)

# PRoot is intentionally verified as a separately distributed GPL executable source tree.
test -f "${work_directory}/proot-$(jq -er '.proot.version' "${manifest}")/COPYING"
test -f "${work_directory}/talloc-$(jq -er '.buildDependencies.talloc.version' "${manifest}")/LICENSE"

while IFS=$'\t' read -r patch_path expected_sha256; do
  patch_file="${repository_root}/${patch_path}"
  test -f "${patch_file}"
  printf '%s  %s\n' "${expected_sha256}" "${patch_file}" | sha256sum --check --strict
done < <(jq -er '.patches[]? | [.path, .sha256] | @tsv' "${manifest}")
