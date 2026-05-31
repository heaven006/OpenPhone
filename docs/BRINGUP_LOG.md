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

DTB build requirement:

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

## 2026-05-31: Assistant v1 OTA

- Built and sideloaded:
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-assistant-v1-ota.zip`.
- OTA SHA-256:
  `25983a9f3099e9493c94f4d78b2eb81140ad99688493e8a2e3a8dca4cf2096a5`.
- Validated generated `vendor_kernel_boot.img` before flashing:
  `dtb size: 1546258`.
- Sideload result:
  `Total xfer: 1.00x`.
- Verified after boot:
  - `ro.openphone.version=0.1.0-dev`
  - `ro.lineage.version=23.2-20260531-UNOFFICIAL-tegu`
  - `service check openphone_agent` reports `found`
  - `org.openphone.assistant/.OpenPhoneQuickSettingsTileService` is present
  - assistant requests/grants `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
    `INTERNET`, and `RECORD_AUDIO`
  - persistent OpenPhone notification is posted with `Stop` and `Open`
    actions
- Verified assistant flow:
  - launched `org.openphone.assistant/.MainActivity`
  - `Start` created a task through `OpenPhoneAgentManagerService`
  - `Screen` returned `screen.captured.metadata_only` through the new
    `getScreen(...)` API, including foreground app/activity metadata
  - `Run Agent` with goal `settings` opened `com.android.settings/.Settings`
    through the local heuristic model/tool loop
- Not yet physically validated:
  - opt-in screenshot payload returned by `getScreen(...,
    {"include_screenshot": true})`
  - model vision/realtime transport
  - arbitrary task completion beyond the local heuristic proof loop

## 2026-05-31: Screenshot and OpenAI Vision Test

- Built and sideloaded screenshot-fix OTA:
  `.worktree/artifacts/tegu/openphone_tegu-openai-vision-test-screenshotfix2-ota.zip`.
- OTA SHA-256:
  `65dedb7fe4abad5ca9d4edec4a4e24c54336cb38a3318f84f4eec7e68c2bee40`.
- Root cause fixed:
  - `getScreen(..., {"include_screenshot": true})` initially crashed because
    screenshot capture still ran with the app caller identity and hit
    `READ_FRAME_BUFFER`.
  - Added `patches/frameworks_base/0010-OpenPhone-capture-screenshots-as-system-server.patch`.
  - The framework now clears/restores Binder identity around
    `WindowManagerInternal.takeAssistScreenshot(...)`.
- Physical screenshot payload validation:
  - Sideload result: `Total xfer: 1.00x`.
  - Booted successfully to Android.
  - Assistant `Start` followed by `Shot` returned
    `screen.captured.screenshot_jpeg_base64`.
  - Payload included:
    - `mime_type=image/jpeg`
    - `encoding=base64`
    - `width=228`
    - `height=512`
    - `quality=65`
    - redacted UI display showed `<base64 chars=24208>`.
  - Logcat showed no `FATAL EXCEPTION` and no `READ_FRAME_BUFFER` denial for
    the screenshot path.
- Built and sideloaded background-thread OpenAI OTA:
  `.worktree/artifacts/tegu/openphone_tegu-openai-bgthread-ota.zip`.
- OTA SHA-256:
  `1792d9bcf904146d3de17cf10ecb66e362ca100b19d54ca56b8e1c4cead3a32c`.
- Root cause fixed:
  - First OpenAI test crashed with `NetworkOnMainThreadException` from
    `OpenAiRealtimeAdapter.callResponsesApi(...)`.
  - Moved `Run Agent` model execution to a background thread and posted the
    redacted result back to the UI thread.
- Physical OpenAI vision validation:
  - Sideload result: `Total xfer: 1.00x`.
  - Booted successfully to Android.
  - Phone connected to Wi-Fi network `Avi`.
  - `adb shell ping -c 1 -W 5 api.openai.com` succeeded.
  - Assistant key field was populated in memory only; no key was committed or
    documented.
  - Assistant `Run Agent` with the OpenAI Responses vision test returned:
    - `status=model_response`
    - `provider=openai-responses-vision-dev`
    - `model=gpt-4.1-mini`
    - `openai_response_id=resp_072056aadeb3322d006a1c286f5eb8819fba6ebcf3cd3fed20`
  - Model output described the visible OpenPhone Assistant screen and suggested
    a next safe action.
  - Logcat showed no `FATAL EXCEPTION`, no `NetworkOnMainThreadException`, and
    no screenshot permission failure for the successful run.
