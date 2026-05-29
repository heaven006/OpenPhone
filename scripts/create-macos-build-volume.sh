#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [[ "$(uname -s)" != "Darwin" ]]; then
  die "this helper is only for macOS"
fi

need_cmd hdiutil

image_path="${OPENPHONE_MACOS_IMAGE:-$OPENPHONE_ROOT/.worktree/OpenPhoneAndroid.sparsebundle}"
volume_name="${OPENPHONE_MACOS_VOLUME_NAME:-OpenPhoneAndroid}"
size="${OPENPHONE_MACOS_IMAGE_SIZE:-300g}"

mkdir -p "$(dirname "$image_path")"

if [[ ! -e "$image_path" ]]; then
  info "creating case-sensitive APFS sparsebundle: $image_path"
  hdiutil create \
    -size "$size" \
    -type SPARSEBUNDLE \
    -fs "Case-sensitive APFS" \
    -volname "$volume_name" \
    "$image_path"
else
  info "sparsebundle already exists: $image_path"
fi

info "mounting $image_path"
mount_output="$(hdiutil attach "$image_path" -mountpoint "$OPENPHONE_ROOT/.worktree/$volume_name" -nobrowse 2>&1 || true)"
printf '%s\n' "$mount_output"

mount_path="$OPENPHONE_ROOT/.worktree/$volume_name"
mkdir -p "$mount_path/android"

cat <<MSG

Case-sensitive Android volume is ready:
  $mount_path

Use:
  export OPENPHONE_ANDROID_DIR="$mount_path/android"
  ./scripts/sync.sh -j4 --fail-fast
MSG
