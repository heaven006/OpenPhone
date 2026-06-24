#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/common.sh
source "$root/scripts/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/repair-gms-location-permissions.sh [--wait-device] [--timeout <seconds>]

Grants Google Play Services the runtime location permissions and app-op modes
needed for fused/network location providers on OpenPhone developer devices.

Options:
  --wait-device       Wait for Android/ADB after a recovery sideload reboot.
  --timeout <seconds> Maximum wait with --wait-device. Default: 600.
  -h, --help          Show this help.
EOF
}

wait_device="false"
timeout_seconds=600

while [[ $# -gt 0 ]]; do
  case "$1" in
    --wait-device)
      wait_device="true"
      shift
      ;;
    --timeout)
      [[ $# -ge 2 ]] || die "--timeout requires a value"
      timeout_seconds="$2"
      [[ "$timeout_seconds" =~ ^[0-9]+$ ]] || die "--timeout must be seconds"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown argument: $1"
      ;;
  esac
done

need_cmd adb

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

adb_shell() {
  adb_cmd shell "$@"
}

device_state() {
  adb_cmd get-state 2>/dev/null || true
}

wait_for_booted_device() {
  local start now state boot_completed
  start="$(date +%s)"

  info "Waiting for Android ADB device"
  while true; do
    state="$(device_state)"
    if [[ "$state" == "device" ]]; then
      boot_completed="$(adb_shell 'getprop sys.boot_completed' 2>/dev/null | tr -d '\r' || true)"
      if [[ "$boot_completed" == "1" ]]; then
        return 0
      fi
    fi

    now="$(date +%s)"
    if (( now - start >= timeout_seconds )); then
      die "timed out waiting for booted ADB device"
    fi
    sleep 2
  done
}

run_or_warn() {
  local description="$1"
  shift
  if "$@"; then
    return 0
  fi
  printf 'warning: failed to %s\n' "$description" >&2
  return 0
}

if [[ "$wait_device" == "true" ]]; then
  wait_for_booted_device
fi

state="$(device_state)"
[[ "$state" == "device" ]] || die "ADB state is '${state:-none}', expected 'device'"

if ! adb_shell 'echo shell-ok' >/dev/null 2>&1; then
  die "ADB shell is not usable. Finish setup, enable USB debugging, and accept the host prompt."
fi

if ! adb_shell 'pm path com.google.android.gms' >/dev/null 2>&1; then
  die "Google Play Services package not found: com.google.android.gms"
fi

info "Granting Google Play Services location permissions"
run_or_warn "grant coarse location" \
  adb_shell 'pm grant com.google.android.gms android.permission.ACCESS_COARSE_LOCATION'
run_or_warn "grant fine location" \
  adb_shell 'pm grant com.google.android.gms android.permission.ACCESS_FINE_LOCATION'
run_or_warn "grant background location" \
  adb_shell 'pm grant com.google.android.gms android.permission.ACCESS_BACKGROUND_LOCATION'

info "Allowing Google Play Services location app-ops"
run_or_warn "allow coarse location app-op" \
  adb_shell 'appops set com.google.android.gms COARSE_LOCATION allow'
run_or_warn "allow fine location app-op" \
  adb_shell 'appops set com.google.android.gms FINE_LOCATION allow'

cat <<'EOF'

Google Play Services location state:
EOF
adb_shell 'appops get com.google.android.gms | grep -i location || true'
adb_shell "dumpsys package com.google.android.gms | grep -E 'User 0:|ACCESS_(FINE|COARSE|BACKGROUND)_LOCATION|runtime permissions' || true"

cat <<'EOF'

If rideshare or other fused-location apps were already open, fully close and
reopen them, or reboot once.
EOF
