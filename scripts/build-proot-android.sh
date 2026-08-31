#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/proot/version.json"
toolchain_manifest="${repository_root}/config/android/toolchain.properties"

if [[ $# -ne 3 ]]; then
  echo "Usage: $0 OUTPUT_BINARY OUTPUT_LOADER WORK_DIRECTORY" >&2
  exit 2
fi

output_binary="$1"
output_loader="$2"
work_directory="$3"
android_sdk_root="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must identify the Android SDK}"

case "$(realpath --canonicalize-missing "${work_directory}")" in
  /|"${repository_root}")
    echo "Refusing to use a broad PRoot work directory" >&2
    exit 1
    ;;
esac

read_property() {
  local name="$1"
  local value
  value="$(sed -n "s/^${name}=//p" "${toolchain_manifest}")"
  if [[ -z "${value}" || "${value}" == *$'\n'* ]]; then
    printf 'Android toolchain property is missing or duplicated: %s\n' "${name}" >&2
    exit 1
  fi
  printf '%s' "${value}"
}

ndk_version="$(read_property ndkVersion)"
minimum_api="$(read_property minSdk)"
android_abi="$(read_property abi)"
ndk_root="${android_sdk_root}/ndk/${ndk_version}"
llvm_root="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin"

if [[ "${android_abi}" != "arm64-v8a" ]]; then
  echo "PRoot packaging is intentionally restricted to arm64-v8a" >&2
  exit 1
fi
if [[ ! -x "${llvm_root}/aarch64-linux-android${minimum_api}-clang" ]]; then
  echo "The repository-pinned Android NDK toolchain is unavailable" >&2
  exit 1
fi

proot_version="$(jq -er '.proot.version' "${manifest}")"
proot_url="$(jq -er '.proot.sourceUrl' "${manifest}")"
proot_sha256="$(jq -er '.proot.sha256' "${manifest}")"
talloc_version="$(jq -er '.buildDependencies.talloc.version' "${manifest}")"
talloc_url="$(jq -er '.buildDependencies.talloc.sourceUrl' "${manifest}")"
talloc_sha256="$(jq -er '.buildDependencies.talloc.sha256' "${manifest}")"
manifest_minimum_api="$(jq -er '.binary.minimumApi' "${manifest}")"

if [[ "${manifest_minimum_api}" != "${minimum_api}" ]]; then
  echo "PRoot binary API does not match the Android minimum API" >&2
  exit 1
fi

rm -rf -- "${work_directory}"
mkdir -p -- "${work_directory}/downloads" "${work_directory}/sources" \
  "${work_directory}/build/proot" "$(dirname "${output_binary}")" "$(dirname "${output_loader}")"

download_and_verify() {
  local url="$1"
  local expected_sha256="$2"
  local destination="$3"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
    "${url}" --output "${destination}"
  printf '%s  %s\n' "${expected_sha256}" "${destination}" | sha256sum --check --strict
}

proot_archive="${work_directory}/downloads/proot-${proot_version}.tar.gz"
talloc_archive="${work_directory}/downloads/talloc-${talloc_version}.tar.gz"
download_and_verify "${proot_url}" "${proot_sha256}" "${proot_archive}"
download_and_verify "${talloc_url}" "${talloc_sha256}" "${talloc_archive}"

tar --extract --gzip --file "${proot_archive}" --directory "${work_directory}/sources" \
  --no-same-owner --no-same-permissions
tar --extract --gzip --file "${talloc_archive}" --directory "${work_directory}/sources" \
  --no-same-owner --no-same-permissions

proot_source="${work_directory}/sources/proot-${proot_version}"
talloc_source="${work_directory}/sources/talloc-${talloc_version}"
test -f "${proot_source}/COPYING"
test -f "${talloc_source}/LICENSE"

while IFS= read -r patch_path; do
  patch_file="${repository_root}/${patch_path}"
  test -f "${patch_file}"
  patch --directory "${proot_source}" --strip=1 --forward < "${patch_file}"
done < <(jq -er '.patches[]?.path' "${manifest}")

cc="${llvm_root}/aarch64-linux-android${minimum_api}-clang"
ar="${llvm_root}/llvm-ar"
ranlib="${llvm_root}/llvm-ranlib"
strip="${llvm_root}/llvm-strip"
objcopy="${llvm_root}/llvm-objcopy"
objdump="${llvm_root}/llvm-objdump"

common_cflags=(
  -O2
  -fPIC
  -ffunction-sections
  -fdata-sections
  "-ffile-prefix-map=${work_directory}=."
  "-fdebug-prefix-map=${work_directory}=."
)
common_ldflags=(
  -Wl,--build-id=none
  -Wl,-z,noexecstack
)

cat > "${talloc_source}/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "aarch64"
Checking uname release type: "Android"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
getconf large file support flags work: OK
EOF

(
  cd "${talloc_source}"
  if ! env \
    AR="${ar}" \
    CC="${cc}" \
    CFLAGS="${common_cflags[*]}" \
    LDFLAGS="${common_ldflags[*]}" \
    PYTHON=python3 \
    RANLIB="${ranlib}" \
    STRIP="${strip}" \
    ./configure \
      --prefix="${work_directory}/build/talloc-prefix" \
      --disable-python \
      --disable-rpath \
      --disable-rpath-install \
      --cross-compile \
      --cross-answers=cross-answers.txt; then
    # Preserve Waf's detailed probe evidence when a future source update needs new answers.
    sed -n '/UNKNOWN/p' cross-answers.txt
    find bin -type f -name config.log -exec tail -n 200 {} \;
    exit 1
  fi
  env SOURCE_DATE_EPOCH=1683936000 make --jobs=2
)

talloc_archive_output="${work_directory}/build/libtalloc.a"
mapfile -t talloc_objects < <(
  find "${talloc_source}/bin/default" -maxdepth 1 -type f -name 'talloc*.o' -print | sort
)
if [[ ${#talloc_objects[@]} -eq 0 ]]; then
  echo "The talloc build did not produce object files" >&2
  exit 1
fi
"${ar}" rcsD "${talloc_archive_output}" "${talloc_objects[@]}"
"${ranlib}" -D "${talloc_archive_output}"

proot_cppflags=(
  -D_FILE_OFFSET_BITS=64
  -D_GNU_SOURCE
  -D__ANDROID__
  -I.
  "-I${proot_source}/src"
  "-I${proot_source}/lib/uthash/include"
  "-I${talloc_source}"
)
proot_cflags=(
  "${common_cflags[@]}"
  -fPIE
)
proot_ldflags=(
  "${talloc_archive_output}"
  -pie
  -Wl,--gc-sections
  "${common_ldflags[@]}"
)

env SOURCE_DATE_EPOCH=1683936000 make \
  --directory "${work_directory}/build/proot" \
  --file "${proot_source}/src/GNUmakefile" \
  --jobs=2 \
  V=1 \
  VERSION="v${proot_version}" \
  CC="${cc}" \
  LD="${cc}" \
  STRIP="${strip}" \
  OBJCOPY="${objcopy}" \
  OBJDUMP="${objdump}" \
  CPPFLAGS="${proot_cppflags[*]}" \
  CFLAGS="${proot_cflags[*]}" \
  LDFLAGS="${proot_ldflags[*]}" \
  proot

cp -- "${work_directory}/build/proot/proot" "${output_binary}"
cp -- "${work_directory}/build/proot/loader/loader" "${output_loader}"
"${strip}" --strip-unneeded "${output_binary}"
"${strip}" --strip-unneeded "${output_loader}"
chmod 0755 "${output_binary}"
chmod 0755 "${output_loader}"
sha256sum "${output_binary}" > "${output_binary}.sha256"
stat --format='%s' "${output_binary}" > "${output_binary}.size"
sha256sum "${output_loader}" > "${output_loader}.sha256"
stat --format='%s' "${output_loader}" > "${output_loader}.size"
