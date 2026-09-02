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

# The probe is deliberately completed before XFCE inherits a renderer selection.
if [[ -S "${runtime_directory}/virgl.sock" ]] &&
    LC_ALL=C LIBGL_ALWAYS_SOFTWARE=true GALLIUM_DRIVER=virpipe \
      VTEST_SOCKET_NAME="${runtime_directory}/virgl.sock" \
      /usr/bin/timeout 10s /usr/bin/glxinfo -B > "${runtime_directory}/graphics-probe.txt" 2>&1 &&
    LC_ALL=C /usr/bin/grep --ignore-case --extended-regexp --quiet \
      '^OpenGL renderer string:.*virgl' "${runtime_directory}/graphics-probe.txt"; then
    export LIBGL_ALWAYS_SOFTWARE=true
    export GALLIUM_DRIVER=virpipe
    export VTEST_SOCKET_NAME="${runtime_directory}/virgl.sock"
    graphics_mode=virgl
else
    export LIBGL_ALWAYS_SOFTWARE=true
    export GALLIUM_DRIVER=llvmpipe
    unset VTEST_SOCKET_NAME
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
