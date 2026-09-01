#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bridge_config="${repository_root}/config/audio/bridge.json"
output="${1:?output path is required}"

jq --exit-status '
  .schemaVersion == 1 and
  .bufferDurationMs >= 100 and .bufferDurationMs <= 1000 and
  .playbackIdleTimeoutMs >= 500 and .playbackIdleTimeoutMs <= 10000 and
  .playback.fileName == "playback.pcm" and
  .microphone.fileName == "microphone.pcm" and
  .playback.format == "s16le" and .microphone.format == "s16le" and
  .playback.sampleRateHz == 48000 and .microphone.sampleRateHz == 48000 and
  .playback.channels == 2 and .microphone.channels == 1 and
  .playback.channelMap == "front-left,front-right" and
  .microphone.channelMap == "mono"
' "${bridge_config}" > /dev/null

playback_file="$(jq -r '.playback.fileName' "${bridge_config}")"
playback_format="$(jq -r '.playback.format' "${bridge_config}")"
playback_rate="$(jq -r '.playback.sampleRateHz' "${bridge_config}")"
playback_channels="$(jq -r '.playback.channels' "${bridge_config}")"
playback_map="$(jq -r '.playback.channelMap' "${bridge_config}")"
microphone_file="$(jq -r '.microphone.fileName' "${bridge_config}")"
microphone_format="$(jq -r '.microphone.format' "${bridge_config}")"
microphone_rate="$(jq -r '.microphone.sampleRateHz' "${bridge_config}")"
microphone_channels="$(jq -r '.microphone.channels' "${bridge_config}")"
microphone_map="$(jq -r '.microphone.channelMap' "${bridge_config}")"

mkdir -p "$(dirname "${output}")"
{
  printf '%s\n' 'pulse.cmd = ['
  printf '  { cmd = "load-module" args = "module-pipe-sink sink_name=deskforge_output file=/run/deskforge/%s format=%s rate=%s channels=%s channel_map=%s sink_properties=device.description=DeskForge_Output" flags = [ ] }\n' \
    "${playback_file}" "${playback_format}" "${playback_rate}" "${playback_channels}" "${playback_map}"
  printf '  { cmd = "load-module" args = "module-pipe-source source_name=deskforge_microphone file=/run/deskforge/%s format=%s rate=%s channels=%s channel_map=%s source_properties=device.description=DeskForge_Microphone" flags = [ ] }\n' \
    "${microphone_file}" "${microphone_format}" "${microphone_rate}" "${microphone_channels}" "${microphone_map}"
  printf '%s\n' ']'
} > "${output}"
