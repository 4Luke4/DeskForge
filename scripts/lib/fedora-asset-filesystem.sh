#!/usr/bin/env bash

deskforge_mount_erofs_overlay() {
  local image="$1"
  local lower="$2"
  local upper="$3"
  local work="$4"
  local merged="$5"

  case "${lower}${upper}${work}${merged}" in
    *,*)
      echo "Fedora asset mount paths must not contain commas" >&2
      return 1
      ;;
  esac

  mkdir -p "${lower}" "${upper}" "${work}" "${merged}"
  # The image is digest-verified before this helper is called. Keep guest content non-executable.
  sudo mount --type erofs --options loop,ro,nodev,nosuid,noexec "${image}" "${lower}"
  sudo mount --type overlay overlay \
    --options "lowerdir=${lower},upperdir=${upper},workdir=${work},nodev,nosuid,noexec" \
    "${merged}"
}

deskforge_unmount_erofs_overlay() {
  local merged="$1"
  local lower="$2"

  if mountpoint --quiet "${merged}"; then
    sudo umount "${merged}"
  fi
  if mountpoint --quiet "${lower}"; then
    sudo umount "${lower}"
  fi
}

deskforge_apply_cpio_overlay() {
  local archive="$1"
  local destination="$2"
  local entry
  local listing

  if ! listing="$(cpio --quiet --list < "${archive}")"; then
    echo "Unable to inspect RPM payload: ${archive}" >&2
    return 1
  fi
  while IFS= read -r entry; do
    if [[ -z "${entry}" || "${entry}" == /* || "/${entry}/" == *"/../"* ]]; then
      echo "Unsafe RPM payload path: ${entry}" >&2
      return 1
    fi
  done <<< "${listing}"

  # Only a signature- and digest-verified RPM reaches this boundary. Root is required solely for
  # OverlayFS copy-up when Fedora ships an existing target without an owner-write bit.
  sudo cpio \
    --directory="${destination}" \
    --extract \
    --make-directories \
    --no-absolute-filenames \
    --preserve-modification-time \
    --quiet \
    --unconditional \
    < "${archive}"
}

deskforge_stream_deterministic_archive() {
  local rootfs="$1"
  local output_archive="$2"
  local uncompressed_size_file="$3"
  local processors="${4:-$(nproc)}"
  local counter_pid
  local prepared_archive="${output_archive}.prepared"
  local size_fifo="${uncompressed_size_file}.fifo"

  rm -f "${prepared_archive}" "${uncompressed_size_file}" "${size_fifo}"
  mkfifo "${size_fifo}"
  wc --bytes < "${size_fifo}" > "${uncompressed_size_file}" &
  counter_pid=$!

  if ! sudo tar \
    --create \
    --file=- \
    --directory="${rootfs}" \
    --format=pax \
    --sort=name \
    --mtime='@0' \
    --owner=0 \
    --group=0 \
    --numeric-owner \
    --pax-option=delete=atime,delete=ctime \
    . | tee "${size_fifo}" | \
      pigz --best --no-name --processes "${processors}" > "${prepared_archive}"; then
    kill "${counter_pid}" 2>/dev/null || true
    wait "${counter_pid}" 2>/dev/null || true
    rm -f "${prepared_archive}" "${uncompressed_size_file}" "${size_fifo}"
    return 1
  fi

  wait "${counter_pid}"
  rm -f "${size_fifo}"
  grep --extended-regexp --quiet '^[0-9]+$' "${uncompressed_size_file}"
  pigz --test "${prepared_archive}"
  mv "${prepared_archive}" "${output_archive}"
}
