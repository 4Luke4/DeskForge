#!/usr/bin/env bash
set -euo pipefail

base_file="app/src/main/res/values/strings.xml"
locale_files=(app/src/main/res/values-*/strings.xml)

# Android's resource merger requires every localized file to expose the same translatable keys.
base_keys=$(sed -n '/translatable="false"/!s/.*<string name="\([^"]*\)".*/\1/p' "$base_file" | sort)
for locale_file in "${locale_files[@]}"; do
  locale_keys=$(sed -n 's/.*<string name="\([^"]*\)".*/\1/p' "$locale_file" | sort)
  if ! diff -u <(printf '%s\n' "$base_keys") <(printf '%s\n' "$locale_keys"); then
    printf 'Localized resources do not match %s: %s\n' "$base_file" "$locale_file" >&2
    exit 1
  fi
done

printf 'Validated %d localized resource files.\n' "${#locale_files[@]}"
