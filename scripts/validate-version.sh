#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version="$(tr -d '[:space:]' < "${repository_root}/VERSION")"

if [[ ! "${version}" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?$ ]]; then
  echo "VERSION is not a valid Semantic Version: ${version}" >&2
  exit 1
fi

if ! rg -F "${version}" "${repository_root}/CHANGELOG.md" >/dev/null; then
  echo "CHANGELOG.md does not mention VERSION ${version}" >&2
  exit 1
fi
