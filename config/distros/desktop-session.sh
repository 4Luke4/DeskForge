#!/usr/bin/env bash
set -euo pipefail

width="${1:?desktop width is required}"
height="${2:?desktop height is required}"
dpi="${3:?desktop density is required}"
refresh_rate="${DESKFORGE_REFRESH_RATE:-60}"

[[ "${width}" =~ ^[0-9]+$ && "${height}" =~ ^[0-9]+$ && "${dpi}" =~ ^[0-9]+$ ]]
[[ "${refresh_rate}" =~ ^[0-9]+$ ]]
(( width >= 640 && width <= 4096 ))
(( height >= 480 && height <= 4096 ))
(( dpi >= 120 && dpi <= 640 ))
(( refresh_rate >= 30 && refresh_rate <= 240 ))

runtime_directory=/run/deskforge
rfb_socket="${runtime_directory}/rfb.sock"
mkdir -p "${runtime_directory}" /tmp/.X11-unix
chmod 700 "${runtime_directory}"
rm -f "${rfb_socket}" /tmp/.X11-unix/X0
umask 077

xvnc_pid=
guest_pid=
cleanup() {
    trap - EXIT INT TERM
    [[ -z "${guest_pid}" ]] || kill "${guest_pid}" 2>/dev/null || true
    [[ -z "${xvnc_pid}" ]] || kill "${xvnc_pid}" 2>/dev/null || true
    [[ -z "${guest_pid}" ]] || wait "${guest_pid}" 2>/dev/null || true
    [[ -z "${xvnc_pid}" ]] || wait "${xvnc_pid}" 2>/dev/null || true
    rm -f "${rfb_socket}"
}
trap cleanup EXIT INT TERM

/usr/bin/Xvnc :0 \
    -geometry "${width}x${height}" \
    -depth 24 \
    -dpi "${dpi}" \
    -FrameRate "${refresh_rate}" \
    -rfbport -1 \
    -rfbunixpath "${rfb_socket}" \
    -rfbunixmode 0600 \
    -SecurityTypes None \
    -AcceptCutText=1 \
    -SendCutText=1 \
    -MaxCutText=1052672 \
    -SendPrimary=0 \
    -SetPrimary=0 \
    -nolisten tcp \
    -localhost=yes &
xvnc_pid=$!

for _ in $(seq 1 200); do
    [[ -S "${rfb_socket}" ]] && break
    kill -0 "${xvnc_pid}" 2>/dev/null
    sleep 0.05
done
[[ -S "${rfb_socket}" ]]

dbus-run-session /usr/libexec/deskforge/guest-session &
guest_pid=$!

# Either component ending invalidates the desktop session; the trap reaps its peer.
while kill -0 "${xvnc_pid}" 2>/dev/null && kill -0 "${guest_pid}" 2>/dev/null; do
    sleep 1
done
exit 1
