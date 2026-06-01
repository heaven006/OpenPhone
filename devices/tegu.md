# Google Pixel 9a

## Identity

| Field | Value |
| --- | --- |
| Device | Google Pixel 9a |
| Codename | `tegu` |
| OpenPhone product | `openphone_tegu` |
| Verified model/SKU | `GTF7P` |
| Verified hardware revision | `MP1.0` |
| Upstream branch | LineageOS `lineage-23.2` |
| Android release | Android 16 / `bp4a` |
| Bringup state | unlocked, Lineage booted, full OpenPhone OTA booted |

Observed stock build on first OpenPhone device:

```text
google/tegu/tegu:16/BP4A.260105.004.E1/14587043:user/release-keys
```

## Sources

- Device tree: downloaded by Lineage `breakfast tegu`.
- Kernel source: downloaded by Lineage `breakfast tegu`.
- Vendor blobs: extract from a matching Lineage installable zip or from a
  device already running the matching Lineage branch.
- Lineage build/install docs:
  `https://blob.lineageos.org/preview/wiki/471381/devices/tegu/build/`

## Host Checks

From the OpenPhone repository:

```bash
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.product.device
adb shell getprop ro.boot.hardware.sku
adb shell getprop ro.build.fingerprint
adb shell getprop ro.boot.flash.locked
```

Expected before unlock:

```text
model: Pixel 9a
device: tegu
sku: GTF7P
flash locked: 1
```

## Prepare Device Repositories

After syncing and applying the OpenPhone overlay/patches:

```bash
export OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android"
cd "$OPENPHONE_ANDROID_DIR"
source build/envsetup.sh
breakfast tegu
```

If proprietary vendor files are missing, extract them according to the upstream
Lineage `tegu` instructions.

## Build

From the OpenPhone repository:

```bash
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
./scripts/apply-patches.sh

OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
OPENPHONE_BUILD_GOAL=bacon \
./scripts/build.sh openphone_tegu
```

For Pixel 9a OTA-producing build goals, `scripts/build.sh` automatically
prepares `device/google/tegu-kernels/6.1/tegu.dtb` from the upstream prebuilt
`vendor_kernel_boot.img` and verifies the generated `vendor_kernel_boot.img`
contains the known-good DTB. The same checks can be run manually with:

```bash
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
./scripts/prepare-tegu-dtb.sh

OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
./scripts/verify-tegu-bootchain.sh openphone_tegu
```

Expected output directory:

```text
.worktree/OpenPhoneAndroid/android/out/target/product/tegu/
```

Expected release artifacts after a successful device build:

```text
vendor_boot.img
lineage-*-UNOFFICIAL-tegu.zip
```

The zip name may still use Lineage's package naming until OpenPhone release
packaging is customized.

## Unlock and Flash

Unlocking the bootloader wipes the device.

Before unlocking:

- Finish initial Android setup.
- Enable Developer Options.
- Enable `OEM unlocking`.
- Enable `USB debugging`.
- Verify `adb devices -l` shows `device:tegu`.

Reboot to bootloader:

```bash
adb reboot bootloader
fastboot devices -l
```

Unlock:

```bash
fastboot flashing unlock
```

Confirm the unlock on the device with the hardware buttons. The device will
wipe data.

Flashing instructions currently follow the upstream Lineage `tegu` install flow
until OpenPhone has its own release package and installer documentation.

## Bringup Notes

The first OpenPhone smoke OTA installed and produced valid OpenPhone userspace,
but its generated `vendor_kernel_boot.img` had a zero-byte DTB and fell back to
fastboot before Android could start. The DTB-fixed OpenPhone smoke OTA booted
successfully on slot `_a`. See
[../docs/TEGU_BOOTCHAIN.md](../docs/TEGU_BOOTCHAIN.md) for the root cause and
the DTB extraction fix.

The first full `openphone_tegu` OTA booted far enough to expose full product
identity but crashed during `system_server` startup because SELinux denied
registration of the new `openphone_agent` Binder service. The fix is captured in
`patches/system_sepolicy/0001-OpenPhone-label-agent-manager-service.patch`.

The SELinux-fixed full OTA boots to lock screen and reports:

```text
ro.product.model=OpenPhone Pixel 9a
ro.openphone.version=0.1.0-dev
ro.openphone.smoke_build=
sys.boot_completed=1
dev.bootcomplete=1
```

OpenPhone runtime verification:

```text
pm list packages -> package:org.openphone.assistant
service list -> openphone_agent: [android.openphone.IOpenPhoneAgentService]
service check openphone_agent -> found
dumpsys activity services -> org.openphone.assistant/.OpenPhoneAssistantService running
```

## Recovery and ADB Notes

After a clean data wipe or recovery `Format data / factory reset`, Android may
boot to first-run onboarding with stale host-side ADB visibility:

```text
adb devices -l -> device
adb get-state -> device
adb shell -> error: closed
adb logcat -> waiting for device
adb install -> abb_exec failed: closed
```

Treat this as an authorization/onboarding state, not proof that Android failed
to boot. Complete or skip onboarding until the home screen, then re-enable
Developer Options and USB debugging. Accept the USB debugging prompt on the
phone before running package or service verification.

Useful post-onboarding verification:

```bash
./scripts/verify-tegu-device.sh
```

The script runs the ADB shell probe, device identity checks, framework service
check, assistant package diagnostics, accessibility settings, and recent
OpenPhone logs. The underlying manual commands are:

```bash
adb shell 'getprop sys.boot_completed'
adb shell 'service check openphone_agent'
adb shell 'dumpsys package org.openphone.assistant | grep -E "versionCode|versionName|OpenPhoneAccessibilityService" -n'
adb shell 'settings get secure enabled_accessibility_services'
adb shell 'settings get secure accessibility_enabled'
```

For the current repository manifest, the assistant APK should report
`versionCode=48`, `versionName=0.1.12-dev`, and
`.OpenPhoneAccessibilityService`. The current local Pixel 9a artifact is:

```text
.worktree/artifacts/tegu/openphone_tegu-model-disclosure-sepolicy-v51-ota.zip
SHA-256: b93db84907523b8e37816abab0a315b1f62b82321755612e82d057fd8f80e866
```

Trust `scripts/verify-tegu-device.sh` over stale example output. The script
derives the expected assistant package version from the repository manifest and
fails if PackageManager still reports stale metadata.

## Acceptance Checklist

Run the automated portion of the hardware checklist with:

```bash
./scripts/smoke-test-tegu-hardware.sh
```

The script writes an evidence report under `.worktree/reports/`. Automated
service probes are not enough to mark user-facing hardware as passing; fill in
the manual sections for calls/SMS, microphone/speaker, camera capture,
fingerprint enrollment, reboot stability, and factory reset before moving those
rows out of `pending`.

| Area | Status |
| --- | --- |
| USB detection | pass |
| ADB authorization | pass |
| Exact codename | pass |
| Model/SKU | pass |
| Bootloader unlock | pass |
| Device repositories | pass |
| Vendor blobs | pass |
| OpenPhone device build | pass |
| Recovery boot | pass |
| OpenPhone install | pass |
| First boot | pass |
| Wi-Fi | pending |
| Bluetooth | pending |
| Cellular data | pending |
| Calls/SMS | pending |
| Camera | pending |
| Microphone/speaker | pending |
| Fingerprint | pending |
| GPS | pending |
| Encryption | pending |
| OpenPhone assistant | pass |
| Framework agent service | pass |
| Action execution | pending |
| Audit log | pending |
| Policy confirmation | pending |

## Known Issues

- Play Integrity behavior is expected to change after bootloader unlock.
- OTA/release signing is not implemented yet.
- Pixel 9a OpenPhone target-files/OTA builds rely on the automated DTB
  extraction and verification in `scripts/build.sh`.
- Full product boot requires the OpenPhone `system/sepolicy` service label for
  `openphone_agent`.
- After a data wipe, ADB may list the device before shell/logcat/install
  channels work. Finish onboarding and re-authorize USB debugging before
  treating `adb shell: error: closed` as a lower-level transport failure.
- A development OTA can update
  `/system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk` while
  PackageManager still reports an older assistant `versionCode` from persisted
  package metadata. Verify both the APK hash and PackageManager version after
  each OTA. If PackageManager stays stale after onboarding, wipe data from
  recovery and verify again before running agent evals.
