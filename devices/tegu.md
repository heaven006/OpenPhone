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

## Acceptance Checklist

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
- Current Pixel 9a OpenPhone builds require the DTB extraction fix before
  target-files/OTA generation.
- Full product boot requires the OpenPhone `system/sepolicy` service label for
  `openphone_agent`.
