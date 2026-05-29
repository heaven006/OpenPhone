#!/usr/bin/env bash

set -euo pipefail

export PATH="$HOME/.local/bin:$PATH"
export PATH="/opt/homebrew/opt/coreutils/libexec/gnubin:/usr/local/opt/coreutils/libexec/gnubin:$PATH"

OPENPHONE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OPENPHONE_ANDROID_DIR="${OPENPHONE_ANDROID_DIR:-$OPENPHONE_ROOT/.worktree/android}"
LINEAGE_MANIFEST_URL="${LINEAGE_MANIFEST_URL:-https://github.com/LineageOS/android.git}"
LINEAGE_BRANCH="${LINEAGE_BRANCH:-lineage-23.2}"
OPENPHONE_RELEASE="${OPENPHONE_RELEASE:-bp4a}"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

info() {
  printf '==> %s\n' "$*"
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

need_gnu_coreutils_on_macos() {
  if [[ "$(uname -s)" == "Darwin" ]] && ! date -d @0 +%s >/dev/null 2>&1; then
    die "GNU coreutils are required on macOS. Install with: brew install coreutils"
  fi
}

android_path_for_patch_dir() {
  case "$1" in
    frameworks_base) printf 'frameworks/base' ;;
    frameworks_native) printf 'frameworks/native' ;;
    frameworks_proto_logging) printf 'frameworks/proto_logging' ;;
    packages_apps_Settings) printf 'packages/apps/Settings' ;;
    packages_apps_SystemUI) printf 'packages/apps/SystemUI' ;;
    vendor_lineage) printf 'vendor/lineage' ;;
    *)
      printf '%s' "${1//_//}"
      ;;
  esac
}

ensure_android_dir() {
  mkdir -p "$OPENPHONE_ANDROID_DIR"
}

is_case_sensitive_dir() {
  local dir="$1"
  local probe
  probe="$(mktemp -d "$dir/.openphone-case-test.XXXXXX")"
  touch "$probe/case"
  if [[ -e "$probe/CASE" ]]; then
    rm -rf "$probe"
    return 1
  fi
  rm -rf "$probe"
  return 0
}
