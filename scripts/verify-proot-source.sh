#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/proot/version.json"
archive="${RUNNER_TEMP:-/tmp}/proot-source.tar.gz"
source_url="$(jq -r '.sourceUrl' "${manifest}")"
expected_sha256="$(jq -r '.sha256' "${manifest}")"

curl --fail --location --retry 3 "${source_url}" --output "${archive}"
echo "${expected_sha256}  ${archive}" | sha256sum --check --strict
tar --extract --gzip --file "${archive}" --directory "${RUNNER_TEMP:-/tmp}"

# PRoot is intentionally verified as a separately distributed GPL executable source tree.
test -f "${RUNNER_TEMP:-/tmp}/proot-$(jq -r '.version' "${manifest}")/COPYING"
