#!/usr/bin/env bash
set -euo pipefail

base_ref="${1:?base commit is required}"
head_ref="${2:?head commit is required}"
pattern='^(build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test)(\([a-z0-9._/-]+\))?!?: .{1,100}$'

while IFS= read -r subject; do
  if [[ ! "${subject}" =~ ${pattern} ]]; then
    echo "Non-conventional commit subject: ${subject}" >&2
    exit 1
  fi
done < <(git log --format='%s' "${base_ref}..${head_ref}")
