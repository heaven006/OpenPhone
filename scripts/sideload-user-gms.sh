#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/common.sh
source "$root/scripts/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/sideload-user-gms.sh --package <gapps.zip> [--reboot-recovery] [--skip-location-repair]

Sideloads a user-supplied Google apps / GMS package from recovery.

This helper does not download, bundle, mirror, recommend, or license Google
Mobile Services. It only runs `adb sideload` against a ZIP that the user
already obtained and is responsible for using.

Expected phone state:
  1. OpenPhone OTA was installed.
  2. Recovery asks whether to install additional packages, or you manually
     entered Recovery -> Apply update -> Apply from ADB.
  3. `adb devices` shows the phone in `sideload` state.

Options:
  --package <zip>          User-supplied GApps/GMS ZIP to sideload.
  --reboot-recovery        Reboot an already-booted ADB device into recovery first.
  --skip-location-repair   Do not wait after sideload to grant Play Services
                           location permissions/app-ops.
  --post-boot-timeout <s>  Maximum post-sideload Android boot wait. Default: 600.
  -h, --help               Show this help.
EOF
}

package=""
reboot_recovery="false"
location_repair="true"
post_boot_timeout=600

while [[ $# -gt 0 ]]; do
  case "$1" in
    --package)
      [[ $# -ge 2 ]] || die "--package requires a value"
      package="$2"
      shift 2
      ;;
    --reboot-recovery)
      reboot_recovery="true"
      shift
      ;;
    --skip-location-repair)
      location_repair="false"
      shift
      ;;
    --post-boot-timeout)
      [[ $# -ge 2 ]] || die "--post-boot-timeout requires a value"
      post_boot_timeout="$2"
      [[ "$post_boot_timeout" =~ ^[0-9]+$ ]] || die "--post-boot-timeout must be seconds"
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

[[ -n "$package" ]] || {
  usage >&2
  die "--package is required"
}
[[ -f "$package" ]] || die "package not found: $package"
case "$package" in
  *.zip) ;;
  *) die "expected a .zip package: $package" ;;
esac

need_cmd adb
need_cmd sha256sum

adb_cmd() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

print_warning() {
  cat >&2 <<'EOF'
OpenPhone does not redistribute Google Mobile Services.

Use only a package that is compatible with this ROM's Android version,
architecture, and installation instructions. For Pixel 9a OpenPhone builds,
that means an arm64 package for the same Android generation as the installed
OpenPhone/Lineage base.

If recovery shows "signature verification failed", review the source of the
ZIP and decide on the phone whether to continue. This script cannot make that
trust decision for you.
EOF
}

device_state() {
  adb_cmd get-state 2>/dev/null || true
}

wait_for_sideload() {
  local state
  state="$(device_state)"
  if [[ "$state" == "sideload" ]]; then
    return 0
  fi

  cat >&2 <<'EOF'

The phone is not in ADB sideload mode yet.

On the phone:
  Recovery -> Apply update -> Apply from ADB

If recovery asks:
  "To install additional packages, you need to reboot recovery first"
choose Yes, then return to:
  Apply update -> Apply from ADB

Waiting for `adb devices` to report sideload...
EOF

  while true; do
    state="$(device_state)"
    [[ "$state" == "sideload" ]] && return 0
    sleep 1
  done
}

print_warning

if [[ "$reboot_recovery" == "true" ]]; then
  state="$(device_state)"
  [[ "$state" == "device" ]] || {
    die "--reboot-recovery requires an already-booted ADB device; current state: ${state:-none}"
  }
  info "Rebooting device into recovery"
  adb_cmd reboot recovery
fi

info "Package: $package"
info "SHA-256: $(sha256sum "$package" | awk '{print $1}')"

wait_for_sideload

cat >&2 <<'EOF'

Starting sideload.

Keep watching the phone. Recovery may ask you to approve a signature warning or
report package-specific errors there before adb prints a final result.
EOF

adb_cmd sideload "$package"

cat >&2 <<'EOF'

Sideload command finished.

If recovery reports status 0 / success:
  - Choose Reboot system now unless the package instructions require another
    package first.
  - This script will then wait for Android and grant Google Play Services the
    location permissions/app-ops required by fused/network location providers.

If recovery reports an error:
  - Do not wipe blindly.
  - Confirm the ZIP matches the ROM Android version and arm64 architecture.
  - Reinstall the OpenPhone OTA and sideload the GMS package immediately after
    the OTA if the package requires a clean first-boot install.
EOF

if [[ "$location_repair" == "true" ]]; then
  cat >&2 <<'EOF'

Waiting for Android to boot so the host can repair Google Play Services
location grants. To defer this step, stop the script and later run:

  scripts/repair-gms-location-permissions.sh --wait-device
EOF

  "$root/scripts/repair-gms-location-permissions.sh" \
    --wait-device \
    --timeout "$post_boot_timeout"
fi
