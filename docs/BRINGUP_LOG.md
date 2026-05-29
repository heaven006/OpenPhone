# Bringup Log

This is the running field log for physical device work. Keep it factual:
record commands, observed device behavior, artifact hashes, and conclusions.

## 2026-05-29: Pixel 9a `tegu`

Device:

- Google Pixel 9a, codename `tegu`
- Hardware revision observed in fastboot: `MP1.0 A1`
- Bootloader version observed in fastboot: `tegu-16.4-14097580`
- Baseband observed in fastboot: `g5300+250909-251024-B-14326967`
- Active experiment slot: `_b`
- Bootloader state: unlocked

Artifacts:

- Official Lineage package:
  `.worktree/artifacts/tegu/lineage-23.2-20260526-nightly-tegu-signed.zip`
- First OpenPhone smoke OTA:
  `.worktree/artifacts/tegu/openphone_tegu_smoke-bp4a-full-ota.zip`
- First OpenPhone smoke OTA SHA-256:
  `e933d97f501a01f64bebc9d9e30624c32c151dbfcf9a2835180d171e2d4d74bd`
- Known-good official Lineage boot-chain images:
  `.worktree/artifacts/tegu/official-lineage/`
- Pulled OpenPhone slot `_b` boot-chain images:
  `.worktree/artifacts/tegu/slot-b/`
- Empty disabled vbmeta image:
  `.worktree/artifacts/tegu/empty-vbmeta-disabled.img`
- Empty disabled vbmeta SHA-256:
  `8f70c5b73592e5797c4fedf0d847550151d18f40810222dcec0a8e3209a6be33`
- DTB-fixed OpenPhone smoke OTA:
  `.worktree/artifacts/tegu/openphone_tegu_smoke-bp4a-dtbfix-ota.zip`
- DTB-fixed OpenPhone smoke OTA SHA-256:
  `4100c3001f7175c60579c2ce99b8823b75a75e512ee80354de267c995c53144c`
- Full OpenPhone OTA:
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-ota.zip`
- Full OpenPhone OTA SHA-256:
  `d49de1f6e11b83b4ceedb4cced0f13bbcf5cbea2c20793a29dfde68fc6b6a687`
- SELinux-fixed full OpenPhone OTA:
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-selinuxfix-ota.zip`
- SELinux-fixed full OpenPhone OTA SHA-256:
  `d6a6a6153af8c37fd03ddd2e4144aa51c9a79662cf936210bd0b4c9d546316f8`

Successful state:

- Official Lineage boots.
- OpenPhone smoke userspace boots when slot `_b` uses the official Lineage
  `vendor_kernel_boot.img`.
- DTB-fixed OpenPhone smoke OTA boots cleanly on slot `_a` without manually
  replacing `vendor_kernel_boot`.
- In that mixed state, the running system reports:
  - `ro.openphone.smoke_build=true`
  - `ro.openphone.version=0.1.0-smoke`
  - `ro.lineage.version=23.2-20260529-UNOFFICIAL-tegu`
  - `ro.boot.slot_suffix=_b`
  - `ro.product.model=OpenPhone Smoke Pixel 9a`
  - `ro.boot.verifiedbootstate=orange`
  - `ro.build.version.security_patch=2026-05-01`

Failed state:

- The first generated OpenPhone smoke OTA falls back to fastboot before Android
  starts when its generated `vendor_kernel_boot_b` is used.
- Disabling AVB with an empty `vbmeta_b` did not fix the fastboot fallback.
- Replacing only `vendor_kernel_boot_b` with the official Lineage image fixed
  boot, which isolated the failure to that partition image.

Root cause:

- Official Lineage `vendor_kernel_boot.img` contains a DTB:
  - DTB size: `1546258`
  - DTB SHA-256:
    `f1aed2bc4c07d3cb1e610f5227a566f22e995dfe05341ca6bf14805be6928688`
- The first OpenPhone-generated `vendor_kernel_boot.img` had:
  - DTB size: `0`
  - Same vendor ramdisk content as official Lineage
- AOSP target-files packaging generated `VENDOR_KERNEL_BOOT/dtb` from
  `$(wildcard device/google/tegu-kernels/6.1/*.dtb)`, but that directory only
  had a prebuilt `vendor_kernel_boot.img` and no standalone `*.dtb`.

Current fix under test:

- Extract the DTB from the known-good prebuilt
  `device/google/tegu-kernels/6.1/vendor_kernel_boot.img`.
- Place it at `device/google/tegu-kernels/6.1/tegu.dtb` before building
  target-files.
- Rebuild the OpenPhone smoke target-files and OTA.
- Validated that the generated `vendor_kernel_boot.img` reports DTB size
  `1546258`, not `0`.
- Sideloaded the DTB-fixed OTA through recovery.
- Chose not to install additional packages.
- Rebooted to system successfully.
- Verified the running device over ADB:
  - `ro.boot.slot_suffix=_a`
  - `ro.openphone.smoke_build=true`
  - `ro.openphone.version=0.1.0-smoke`
  - `ro.product.model=OpenPhone Smoke Pixel 9a`
  - `ro.lineage.version=23.2-20260529-UNOFFICIAL-tegu`
  - `ro.build.version.security_patch=2026-05-01`
  - `ro.boot.verifiedbootstate=orange`

Full product bringup:

- Built and sideloaded `openphone_tegu-bp4a-v1-dev-ota.zip`.
- The full product changed identity from smoke to full OpenPhone:
  - `ro.product.model=OpenPhone Pixel 9a`
  - `ro.openphone.version=0.1.0-dev`
  - `ro.openphone.smoke_build=` empty
- First full OTA reached late boot but crashed repeatedly during
  `system_server` startup.
- Root cause:
  - `OpenPhoneAgentManagerService` started from `system_server`.
  - `publishBinderService(Context.OPENPHONE_AGENT_SERVICE, ...)` attempted to
    register Binder service `openphone_agent`.
  - `servicemanager` rejected it with `java.lang.SecurityException: SELinux
    denied for service`.
  - The exception killed `system_server`, causing zygote restart and boot
    animation loop.
- Fix:
  - Added `patches/system_sepolicy/0001-OpenPhone-label-agent-manager-service.patch`.
  - Declared `openphone_agent_service` as a `system_api_service`,
    `system_server_service`, and `service_manager_type`.
  - Labeled `openphone_agent` in `private/service_contexts`.
- Rebuilt and sideloaded
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-selinuxfix-ota.zip`.
- Validated generated `vendor_kernel_boot.img` still contains the known-good
  DTB:
  - DTB size: `1546258`
  - DTB SHA-256:
    `f1aed2bc4c07d3cb1e610f5227a566f22e995dfe05341ca6bf14805be6928688`
- Rebooted successfully to lock screen.
- Verified the running full product over ADB:
  - `ro.boot.slot_suffix=_b`
  - `ro.product.name=tegu`
  - `ro.product.model=OpenPhone Pixel 9a`
  - `ro.openphone.version=0.1.0-dev`
  - `ro.openphone.smoke_build=` empty
  - `ro.lineage.version=23.2-20260529-UNOFFICIAL-tegu`
  - `ro.build.version.security_patch=2026-05-01`
  - `ro.boot.verifiedbootstate=orange`
  - `sys.boot_completed=1`
  - `dev.bootcomplete=1`
  - `init.svc.bootanim=stopped`
- Verified OpenPhone runtime:
  - `pm list packages` reports `package:org.openphone.assistant`.
  - `service list` reports
    `openphone_agent: [android.openphone.IOpenPhoneAgentService]`.
  - `service check openphone_agent` reports `found`.
  - `dumpsys package org.openphone.assistant` shows the app is privileged,
    persistent, direct-boot aware, installed under
    `/system_ext/priv-app/OpenPhoneAssistant`, and granted OpenPhone signature
    permissions.
  - `dumpsys activity services` shows
    `org.openphone.assistant/.OpenPhoneAssistantService` running in process
    `org.openphone.assistant`.
  - Launching the assistant with `monkey -p org.openphone.assistant 1`
    displayed `org.openphone.assistant/.MainActivity`.
