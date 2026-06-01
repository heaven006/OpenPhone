#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/common.sh
source "$root/scripts/common.sh"

need_cmd adb

run_adb_shell() {
  adb shell "$@"
}

manifest_value() {
  local attr="$1"
  sed -n "s/.*android:${attr}=\"\\([^\"]*\\)\".*/\\1/p" \
    "$root/overlay/packages/apps/OpenPhoneAssistant/AndroidManifest.xml" |
    head -1
}

section() {
  printf '\n== %s ==\n' "$*"
}

expected_assistant_version_code="${OPENPHONE_EXPECT_ASSISTANT_VERSION_CODE:-$(manifest_value versionCode)}"
expected_assistant_version_name="${OPENPHONE_EXPECT_ASSISTANT_VERSION_NAME:-$(manifest_value versionName)}"

[[ -n "$expected_assistant_version_code" ]] || die "could not determine expected assistant versionCode"
[[ -n "$expected_assistant_version_name" ]] || die "could not determine expected assistant versionName"

section "ADB"
adb devices -l

state="$(adb get-state 2>/dev/null || true)"
if [[ "$state" != "device" ]]; then
  die "ADB state is '$state', expected 'device'"
fi

if ! shell_probe="$(adb shell 'echo shell-ok' 2>&1)"; then
  printf '%s\n' "$shell_probe" >&2
  die "ADB shell is not usable. If this follows a wipe, finish onboarding, enable USB debugging, and accept the prompt."
fi

if [[ "$shell_probe" != *"shell-ok"* ]]; then
  die "ADB shell probe returned unexpected output: $shell_probe"
fi

section "Device identity"
run_adb_shell 'printf "model="; getprop ro.product.model; printf "device="; getprop ro.product.device; printf "openphone="; getprop ro.openphone.version; printf "lineage="; getprop ro.lineage.version; printf "slot="; getprop ro.boot.slot_suffix; printf "boot_completed="; getprop sys.boot_completed; printf "verified_boot="; getprop ro.boot.verifiedbootstate'

section "OpenPhone framework"
run_adb_shell 'service check openphone_agent'

section "Assistant package"
run_adb_shell 'pm path org.openphone.assistant'
package_line="$(run_adb_shell 'cmd package list packages --show-versioncode org.openphone.assistant')"
printf '%s\n' "$package_line"

package_dump="$(run_adb_shell 'dumpsys package org.openphone.assistant')"
printf '%s\n' "$package_dump" |
  grep -E "versionCode|versionName|OpenPhoneAccessibilityService|AccessibilityService|BIND_ACCESSIBILITY_SERVICE|WRITE_SECURE_SETTINGS" -n || true

if [[ "$package_line" != *"versionCode:$expected_assistant_version_code"* ]]; then
  die "PackageManager reports unexpected assistant versionCode. got: $package_line expected: $expected_assistant_version_code"
fi

if ! printf '%s\n' "$package_dump" | grep -q "versionName=$expected_assistant_version_name"; then
  die "PackageManager reports unexpected assistant versionName. expected: $expected_assistant_version_name"
fi

if ! printf '%s\n' "$package_dump" | grep -q "OpenPhoneAccessibilityService"; then
  die "OpenPhoneAccessibilityService is not registered in PackageManager diagnostics"
fi

section "Assistant APK bytes"
run_adb_shell 'ls -l /system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk'
run_adb_shell 'sha256sum /system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk'

section "Accessibility settings"
run_adb_shell 'printf "enabled_accessibility_services="; settings get secure enabled_accessibility_services; printf "accessibility_enabled="; settings get secure accessibility_enabled'

section "Recent OpenPhone logs"
run_adb_shell 'logcat -d -t 400 | grep -i openphone | tail -120 || true'

printf '\nOpenPhone Pixel 9a device verification completed.\n'
