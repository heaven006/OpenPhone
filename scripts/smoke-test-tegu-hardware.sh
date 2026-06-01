#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/common.sh
source "$root/scripts/common.sh"

need_cmd adb

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
report="${OPENPHONE_SMOKE_REPORT:-$root/.worktree/reports/tegu-hardware-smoke-$timestamp.txt}"
mkdir -p "$(dirname "$report")"

log() {
  printf '%s\n' "$*"
}

section() {
  printf '\n== %s ==\n' "$*"
}

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

record_shell() {
  local title="$1"
  local command="$2"
  section "$title"
  log "\$ adb shell $command"
  if ! adb_shell "$command"; then
    log "RESULT: command_failed"
  fi
}

record_manual() {
  local title="$1"
  local instructions="$2"
  section "$title"
  log "RESULT: manual_required"
  log "$instructions"
}

run_smoke() {
  section "ADB"
  adb_cmd devices -l

  local state
  state="$(adb_cmd get-state 2>/dev/null || true)"
  log "adb_state=$state"
  if [[ "$state" != "device" ]]; then
    die "ADB state is '$state', expected 'device'"
  fi

  local shell_probe
  if ! shell_probe="$(adb_shell 'echo shell-ok' 2>&1)"; then
    printf '%s\n' "$shell_probe" >&2
    die "ADB shell is not usable. Finish onboarding, enable USB debugging, and accept the host prompt."
  fi
  log "$shell_probe"

  record_shell "Device Identity" \
    'printf "model="; getprop ro.product.model; printf "device="; getprop ro.product.device; printf "sku="; getprop ro.boot.hardware.sku; printf "openphone="; getprop ro.openphone.version; printf "lineage="; getprop ro.lineage.version; printf "slot="; getprop ro.boot.slot_suffix; printf "boot_completed="; getprop sys.boot_completed; printf "verified_boot="; getprop ro.boot.verifiedbootstate'

  record_shell "Wi-Fi" \
    'cmd wifi status 2>/dev/null || dumpsys wifi | sed -n "1,80p"'

  record_shell "Bluetooth" \
    'service check bluetooth_manager; dumpsys bluetooth_manager 2>/dev/null | sed -n "1,80p" || true'

  record_shell "Cellular And SIM" \
    'printf "baseband="; getprop gsm.version.baseband; printf "operator="; getprop gsm.operator.alpha; printf "sim_state="; getprop gsm.sim.state; service check phone; dumpsys telephony.registry 2>/dev/null | sed -n "1,80p" || true'

  record_shell "Camera Service" \
    'service check media.camera; dumpsys media.camera 2>/dev/null | sed -n "1,120p" || true'

  record_shell "Location And GPS" \
    'service check location; cmd location is-location-enabled 2>/dev/null || true; dumpsys location 2>/dev/null | sed -n "1,120p" || true'

  record_shell "Fingerprint" \
    'service check fingerprint; dumpsys fingerprint 2>/dev/null | sed -n "1,120p" || true'

  record_shell "Audio Services" \
    'service check audio; dumpsys audio 2>/dev/null | sed -n "1,120p" || true'

  record_shell "Sensors" \
    'dumpsys sensorservice 2>/dev/null | sed -n "1,160p" || true'

  record_shell "Encryption And Lock State" \
    'printf "crypto_state="; getprop ro.crypto.state; printf "crypto_type="; getprop ro.crypto.type; printf "vold_decrypt="; getprop vold.decrypt; dumpsys lock_settings 2>/dev/null | sed -n "1,120p" || true'

  record_shell "Battery And Thermal" \
    'dumpsys battery; dumpsys thermalservice 2>/dev/null | sed -n "1,120p" || true'

  record_shell "OpenPhone Runtime" \
    'service check openphone_agent; pm path org.openphone.assistant; cmd package list packages --show-versioncode org.openphone.assistant; settings get secure enabled_accessibility_services; settings get secure accessibility_enabled'

  record_manual "Calls And SMS" \
    "Insert an active SIM, place an outbound call, receive an inbound call, send an SMS, and receive an SMS. Record pass/fail, carrier, SIM type, and whether VoLTE/IMS is visible."

  record_manual "Microphone And Speaker" \
    "Record a short voice memo or video, play it back on speaker, then test earpiece audio during a call. Record pass/fail for microphone, speaker, earpiece, and volume keys."

  record_manual "Camera Capture" \
    "Open the camera app, capture rear/front photos and rear/front videos, then verify saved media opens. Record pass/fail for focus, preview, capture, and playback."

  record_manual "Fingerprint Enrollment" \
    "Set a screen lock, enroll a fingerprint, lock the phone, unlock with fingerprint, then reboot and unlock again. Record pass/fail."

  record_manual "Reboot And Factory Reset" \
    "Reboot normally three times and verify OpenPhone assistant/framework availability after each boot. For release candidates, perform recovery factory reset and repeat setup/verification."

  section "Summary"
  log "Automated probes completed. Manual-required sections must be filled in before changing hardware checklist items from pending to pass."
}

run_smoke | tee "$report"
printf '\nHardware smoke report written to %s\n' "$report"
