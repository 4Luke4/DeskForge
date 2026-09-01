#!/usr/bin/env bash
set -euo pipefail

width="${1:?desktop width is required}"
height="${2:?desktop height is required}"
dpi="${3:?desktop density is required}"

[[ "${width}" =~ ^[0-9]+$ && "${height}" =~ ^[0-9]+$ && "${dpi}" =~ ^[0-9]+$ ]]
(( width >= 640 && width <= 4096 ))
(( height >= 480 && height <= 4096 ))
(( dpi >= 120 && dpi <= 640 ))

runtime_directory=/run/deskforge
rfb_socket="${runtime_directory}/rfb.sock"
mkdir -p "${runtime_directory}" /tmp/.X11-unix
chmod 700 "${runtime_directory}"
rm -f "${rfb_socket}" /tmp/.X11-unix/X0
umask 077

xvnc_pid=
xfce_pid=
cleanup() {
    trap - EXIT INT TERM
    [[ -z "${xfce_pid}" ]] || kill "${xfce_pid}" 2>/dev/null || true
    [[ -z "${xvnc_pid}" ]] || kill "${xvnc_pid}" 2>/dev/null || true
    [[ -z "${xfce_pid}" ]] || wait "${xfce_pid}" 2>/dev/null || true
    [[ -z "${xvnc_pid}" ]] || wait "${xvnc_pid}" 2>/dev/null || true
    rm -f "${rfb_socket}"
}
trap cleanup EXIT INT TERM

/usr/bin/Xvnc :0 \
    -geometry "${width}x${height}" \
    -depth 24 \
    -dpi "${dpi}" \
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

DISPLAY=:0 dbus-run-session /usr/bin/startxfce4 &
xfce_pid=$!

# Either component ending invalidates the desktop session; the trap reaps its peer.
while kill -0 "${xvnc_pid}" 2>/dev/null && kill -0 "${xfce_pid}" 2>/dev/null; do
    sleep 1
done
exit 1
