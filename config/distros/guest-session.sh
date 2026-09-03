#!/usr/bin/env bash
set -euo pipefail

runtime_directory=/run/deskforge
export XDG_RUNTIME_DIR="${runtime_directory}"
export PIPEWIRE_RUNTIME_DIR="${runtime_directory}"

pipewire_pid=
wireplumber_pid=
pulse_pid=
xfce_pid=
graphics_mode=software
renderer_preference="${DESKFORGE_RENDERER:-auto}"
cleanup() {
    trap - EXIT INT TERM
    for pid in "${xfce_pid}" "${pulse_pid}" "${wireplumber_pid}" "${pipewire_pid}"; do
        [[ -z "${pid}" ]] || kill "${pid}" 2>/dev/null || true
    done
    for pid in "${xfce_pid}" "${pulse_pid}" "${wireplumber_pid}" "${pipewire_pid}"; do
        [[ -z "${pid}" ]] || wait "${pid}" 2>/dev/null || true
    done
    rm -f "${runtime_directory}/audio.ready" "${runtime_directory}/graphics.ready"
}
trap cleanup EXIT INT TERM

/usr/bin/pipewire &
pipewire_pid=$!
/usr/bin/wireplumber &
wireplumber_pid=$!
/usr/bin/pipewire-pulse &
pulse_pid=$!

for _ in $(seq 1 200); do
    if /usr/bin/pactl info > /dev/null 2>&1; then
        break
    fi
    kill -0 "${pipewire_pid}" "${wireplumber_pid}" "${pulse_pid}" 2>/dev/null
    sleep 0.05
done
/usr/bin/pactl info > /dev/null
/usr/bin/pactl set-default-sink deskforge_output
/usr/bin/pactl set-default-source deskforge_microphone
: > "${runtime_directory}/audio.ready"
chmod 0600 "${runtime_directory}/audio.ready"

# Renderer qualification completes before XFCE inherits an immutable session-wide selection.
case "${renderer_preference}" in
    auto|venus|virgl|llvmpipe) ;;
    *) exit 2 ;;
esac

probe_venus() {
    [[ -S "${runtime_directory}/virgl.sock" ]] &&
        [[ -f /usr/share/vulkan/icd.d/virtio_icd.aarch64.json ]] &&
        LC_ALL=C LIBGL_ALWAYS_SOFTWARE=true GALLIUM_DRIVER=zink \
          VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.aarch64.json \
          VN_DEBUG=vtest VTEST_SOCKET_NAME="${runtime_directory}/virgl.sock" \
          /usr/bin/timeout 10s /usr/bin/glxinfo -B > "${runtime_directory}/graphics-probe.txt" 2>&1 &&
        LC_ALL=C /usr/bin/grep --ignore-case --extended-regexp --quiet \
          '^OpenGL renderer string:.*(zink|venus|virtio)' "${runtime_directory}/graphics-probe.txt"
}

probe_virgl() {
    [[ -S "${runtime_directory}/virgl.sock" ]] &&
        LC_ALL=C LIBGL_ALWAYS_SOFTWARE=true GALLIUM_DRIVER=virpipe \
          VTEST_SOCKET_NAME="${runtime_directory}/virgl.sock" \
          /usr/bin/timeout 10s /usr/bin/glxinfo -B > "${runtime_directory}/graphics-probe.txt" 2>&1 &&
        LC_ALL=C /usr/bin/grep --ignore-case --extended-regexp --quiet \
          '^OpenGL renderer string:.*virgl' "${runtime_directory}/graphics-probe.txt"
}

if [[ "${renderer_preference}" != virgl && "${renderer_preference}" != llvmpipe ]] && probe_venus; then
    export LIBGL_ALWAYS_SOFTWARE=true
    export GALLIUM_DRIVER=zink
    export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.aarch64.json
    export VN_DEBUG=vtest
    export VTEST_SOCKET_NAME="${runtime_directory}/virgl.sock"
    graphics_mode=venus
elif [[ "${renderer_preference}" != venus && "${renderer_preference}" != llvmpipe ]] && probe_virgl; then
    export LIBGL_ALWAYS_SOFTWARE=true
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME="${runtime_directory}/virgl.sock"
    unset VK_DRIVER_FILES VN_DEBUG
    graphics_mode=virgl
elif [[ "${renderer_preference}" == auto || "${renderer_preference}" == llvmpipe ]]; then
    export LIBGL_ALWAYS_SOFTWARE=true
    export GALLIUM_DRIVER=llvmpipe
    unset VTEST_SOCKET_NAME VK_DRIVER_FILES VN_DEBUG
else
    # Forced accelerated selections fail closed instead of silently changing renderer semantics.
    exit 3
fi
printf '%s\n' "${graphics_mode}" > "${runtime_directory}/graphics.ready"
chmod 0600 "${runtime_directory}/graphics.ready"

DISPLAY=:0 /usr/bin/startxfce4 &
xfce_pid=$!

# Audio and desktop processes share one lifecycle; any component exit tears down the session.
while kill -0 "${pipewire_pid}" "${wireplumber_pid}" "${pulse_pid}" "${xfce_pid}" 2>/dev/null; do
    sleep 1
done
exit 1
