# Device Support

OpenPhone supports exact device models, not generic Android phones.

## Device States

```text
candidate
  Device appears technically viable but is not validated.

bringup
  Builds are being attempted; hardware may be broken.

experimental
  Boots and basic hardware works; not suitable for daily users.

supported
  Passes the device acceptance checklist and has release/OTA coverage.

retired
  No longer receiving supported OpenPhone builds.
```

## Acceptance Checklist

```text
boot
recovery
adb
display
touch
Wi-Fi
Bluetooth
cellular data
calls
SMS
IMS/VoLTE where applicable
camera
microphone
speaker
fingerprint/biometric if present
accelerometer/gyroscope
GPS
NFC if present
battery reporting
suspend/resume
encryption
OTA update
factory reset
agent screen read
agent action execution
agent background task
audit log
policy confirmation flow
```

## First Target

The first real target device is intentionally not hard-coded yet. The project
should select one device with:

- Unlockable bootloader.
- Active LineageOS support.
- Available device tree and kernel source.
- Known vendor blob extraction flow.
- Recoverable flashing path.

Until that selection is made, `openphone_arm64` is the generic bootstrap product
for validating the OpenPhone product layer.

