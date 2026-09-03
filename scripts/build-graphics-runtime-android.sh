#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
manifest="${repository_root}/config/graphics/virgl.json"
toolchain_manifest="${repository_root}/config/android/toolchain.properties"

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 OUTPUT_BINARY WORK_DIRECTORY" >&2
  exit 2
fi

output_binary="$1"
work_directory="$(realpath --canonicalize-missing "$2")"
android_sdk_root="${ANDROID_SDK_ROOT:?ANDROID_SDK_ROOT must identify the Android SDK}"
case "${work_directory}" in
  /|"${repository_root}")
    echo "Refusing to use a broad graphics work directory" >&2
    exit 1
    ;;
esac

read_property() {
  local name="$1"
  local value
  value="$(sed -n "s/^${name}=//p" "${toolchain_manifest}")"
  [[ -n "${value}" && "${value}" != *$'\n'* ]]
  printf '%s' "${value}"
}

ndk_version="$(read_property ndkVersion)"
minimum_api="$(read_property minSdk)"
android_abi="$(read_property abi)"
ndk_root="${android_sdk_root}/ndk/${ndk_version}"
llvm_root="${ndk_root}/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ "${android_abi}" == "arm64-v8a" ]]
[[ -x "${llvm_root}/aarch64-linux-android${minimum_api}-clang" ]]
[[ "$(jq -er '.binary.minimumApi' "${manifest}")" == "${minimum_api}" ]]

rm -rf -- "${work_directory}"
mkdir -p -- "${work_directory}/downloads" "${work_directory}/sources" \
  "${work_directory}/prefix" "$(dirname "${output_binary}")"

download_component() {
  local component="$1"
  local url expected_size expected_sha256 destination
  url="$(jq -er "${component}.sourceUrl" "${manifest}")"
  expected_size="$(jq -er "${component}.sizeBytes" "${manifest}")"
  expected_sha256="$(jq -er "${component}.sha256" "${manifest}")"
  destination="$2"
  curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "${url}" --output "${destination}"
  [[ "$(stat --format='%s' "${destination}")" == "${expected_size}" ]]
  printf '%s  %s\n' "${expected_sha256}" "${destination}" | sha256sum --check --strict
}

virgl_version="$(jq -er '.renderer.version' "${manifest}")"
epoxy_version="$(jq -er '.buildDependencies.libepoxy.version' "${manifest}")"
virgl_archive="${work_directory}/downloads/virglrenderer-${virgl_version}.tar.gz"
epoxy_archive="${work_directory}/downloads/libepoxy-${epoxy_version}.tar.gz"
download_component '.renderer' "${virgl_archive}"
download_component '.buildDependencies.libepoxy' "${epoxy_archive}"

tar --extract --gzip --file "${virgl_archive}" --directory "${work_directory}/sources" \
  --no-same-owner --no-same-permissions
tar --extract --gzip --file "${epoxy_archive}" --directory "${work_directory}/sources" \
  --no-same-owner --no-same-permissions
virgl_source="${work_directory}/sources/virglrenderer-${virgl_version}"
epoxy_source="${work_directory}/sources/libepoxy-${epoxy_version}"
[[ -f "${virgl_source}/COPYING" && -f "${epoxy_source}/COPYING" ]]

while IFS= read -r patch_path; do
  absolute_patch="${repository_root}/${patch_path}"
  # Strict parsing prevents malformed patch tails from being silently ignored by permissive tools.
  git -C "${virgl_source}" apply --check --whitespace=error-all "${absolute_patch}"
  git -C "${virgl_source}" apply --whitespace=error-all "${absolute_patch}"
done < <(jq -er '.patches[]' "${manifest}")
install --mode=0644 "${repository_root}/config/graphics/deskforge_graphics_jni.c" \
  "${virgl_source}/vtest/deskforge_graphics_jni.c"
maximum_clients="$(jq -er '.transport.maximumClients' "${manifest}")"
maximum_resources="$(jq -er '.transport.maximumResourcesPerClient' "${manifest}")"
maximum_command_bytes="$(jq -er '.transport.maximumCommandBytes' "${manifest}")"
maximum_resource_bytes="$(jq -er '.transport.maximumResourceBytes' "${manifest}")"
maximum_aggregate_bytes="$(jq -er '.transport.maximumAggregateBytes' "${manifest}")"
address_space_bytes="$(jq -er '.processLimits.addressSpaceBytes' "${manifest}")"
open_files="$(jq -er '.processLimits.openFiles' "${manifest}")"
core_bytes="$(jq -er '.processLimits.coreBytes' "${manifest}")"
cat > "${virgl_source}/vtest/deskforge_graphics_config.h" <<EOF
#ifndef DESKFORGE_GRAPHICS_CONFIG_H
#define DESKFORGE_GRAPHICS_CONFIG_H
#define DESKFORGE_MAX_CLIENTS ${maximum_clients}u
#define DESKFORGE_MAX_RESOURCES ${maximum_resources}u
#define DESKFORGE_MAX_COMMAND_BYTES ${maximum_command_bytes}u
#define DESKFORGE_MAX_RESOURCE_BYTES ${maximum_resource_bytes}u
#define DESKFORGE_MAX_AGGREGATE_BYTES ${maximum_aggregate_bytes}u
#define DESKFORGE_ADDRESS_SPACE_BYTES ${address_space_bytes}u
#define DESKFORGE_OPEN_FILES ${open_files}u
#define DESKFORGE_CORE_BYTES ${core_bytes}u
#endif
EOF

cross_file="${work_directory}/android-arm64.ini"
cc="${llvm_root}/aarch64-linux-android${minimum_api}-clang"
cat > "${cross_file}" <<EOF
[binaries]
c = '${cc}'
ar = '${llvm_root}/llvm-ar'
strip = '${llvm_root}/llvm-strip'
pkg-config = 'pkg-config'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'

[built-in options]
c_args = ['-O2', '-fPIC', '-fstack-protector-strong', '-ffile-prefix-map=${work_directory}=.']
c_link_args = ['-Wl,--build-id=none', '-Wl,-z,relro,-z,now', '-Wl,-z,noexecstack']
EOF

export SOURCE_DATE_EPOCH=1683936000
meson setup "${work_directory}/build-epoxy" "${epoxy_source}" \
  --cross-file "${cross_file}" --prefix "${work_directory}/prefix" \
  --default-library static -Degl=yes -Dglx=no -Dx11=false -Dtests=false -Ddocs=false
meson compile --jobs 2 --verbose -C "${work_directory}/build-epoxy"
meson install -C "${work_directory}/build-epoxy"

export PKG_CONFIG_LIBDIR="${work_directory}/prefix/lib/pkgconfig"
meson setup "${work_directory}/build-virgl" "${virgl_source}" \
  --cross-file "${cross_file}" --prefix "${work_directory}/prefix" \
  --default-library static -Dplatforms=egl -Dtests=false -Dvenus=true -Dvulkan-dload=true \
  -Drender-server-worker=thread -Dvideo=false \
  -Ddrm-renderers=[] -Dtracing=none -Dunstable-apis=false
meson compile --jobs 2 --verbose -C "${work_directory}/build-virgl" \
  deskforge_graphics virgl_render_server

cp -- "${work_directory}/build-virgl/vtest/libdeskforge_graphics.so" "${output_binary}"
render_server="$(dirname "${output_binary}")/$(jq -er '.binary.renderServerFileName' "${manifest}")"
cp -- "${work_directory}/build-virgl/server/virgl_render_server" "${render_server}"
"${llvm_root}/llvm-strip" --strip-unneeded "${output_binary}"
"${llvm_root}/llvm-strip" --strip-unneeded "${render_server}"
chmod 0755 "${output_binary}"
chmod 0755 "${render_server}"
sha256sum "${output_binary}" > "${output_binary}.sha256"
stat --format='%s' "${output_binary}" > "${output_binary}.size"
