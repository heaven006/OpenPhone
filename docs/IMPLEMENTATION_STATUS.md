# Implementation Status

This document tracks current implementation evidence against `SPEC.md`.

## Implemented in This Repository

- Canonical OpenPhone project structure.
- Source-available licensing boundary notice.
- LineageOS upstream sync script.
- OpenPhone local manifest hook.
- Patch-stack directory layout and patch application script.
- Generic `openphone_arm64` product definition.
- `vendor/openphone` common product layer.
- Initial capability and policy JSON.
- Privileged permission allowlist seed file.
- Persistent privileged `OpenPhoneAssistant` app with a basic task, context,
  input-grant, and audit control surface.
- Assistant Binder interface seed.
- In-app bootstrap policy evaluator and audit logger.
- Privileged assistant boot receiver that starts the assistant service after
  `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, and package replacement.
- Signature permission contract declared by the assistant for future OS-level
  screen context, action execution, task management, and audit access.
- Hidden `android.openphone.OpenPhoneAgentManager` framework manager API.
- Hidden `android.openphone.IOpenPhoneAgentService` Binder contract.
- `OpenPhoneAgentManagerService` registered from `system_server`.
- Platform-owned OpenPhone signature permissions declared in
  `frameworks/base`.
- Privileged assistant bridge to the OpenPhone framework manager API.
- Framework screen context now reports focused and visible activities from
  `ActivityTaskManagerInternal`.
- Framework action execution supports `open_app`, Back, Home, Recents, tap,
  long press, scroll, text input, clipboard write, clipboard paste, and
  confirmed share chooser launch through mediated OS APIs.
- Task-scoped `input.perform` grants are stored by the framework service from
  `approved_capabilities` / `granted_capabilities` on task creation. Pointer
  and text input fail closed without that task grant.
- Framework seed policy returns allow/confirmation/deny decisions for the
  initial capability classes.
- Framework action execution now creates one-shot pending action IDs for
  confirmation-required actions and exposes `confirmAction(...)` for trusted
  OpenPhone components to approve or deny them.
- Framework audit endpoint records recent task, screen, policy, and action
  events in a bounded in-memory cache backed by
  `/data/system/openphone/audit-log.json`.
- Assistant Binder methods now route task, context, action, policy, and audit
  calls through the framework manager when available.
- Assistant UI can start an audited visible task, explicitly approve
  `input.perform`, read current screen context, execute a sample Back action,
  request a confirmable action, approve or deny the pending action, and browse
  recent durable audit events from the framework service.
- JSON contracts for tasks, screen context, action requests, and audit events.
- Framework integration contract and first seven `frameworks/base` patch sets.
- Build, flash, and scaffold validation scripts.
- Local JSON/XML/shell scaffold checks.
- macOS case-sensitive Android build volume helper.
- Sync preflight checks for case-sensitive filesystem and Git LFS.
- Build preflight for GNU coreutils on macOS.
- Darwin Soong bootstrap test skip for local macOS builds.
- Verified Lineage `repo sync` on a case-sensitive APFS sparse volume.
- Verified `lunch openphone_arm64 bp2a userdebug` against the synced tree.
- Verified overlay and patch replay against the synced Lineage tree.
- Verified focused `OpenPhoneAssistant` module build inside the Android tree.
- Verified focused `services.core-android_common-checkbuild` build for the
  OpenPhone framework manager and `system_server` service.
- Generated privileged assistant APK at
  `out/soong/.intermediates/packages/apps/OpenPhoneAssistant/OpenPhoneAssistant/android_common/OpenPhoneAssistant.apk`.
- Verified generic `openphone_arm64` `droid` build graph on macOS/Darwin.
- Darwin compatibility patches for host build bootstrap, macOS SDK 26, Lineage
  host tools, optional kernel vars, expresscatalog int64 formatting, f2fs host
  tooling, debuggerd host tooling, SELinux host tooling, fs_config host tooling,
  and Rust `ring` host assembly selection.
- SELinux service label for the OpenPhone framework Binder service
  `openphone_agent`.
- Pixel 9a `openphone_tegu` full OTA build and physical boot.
- Verified physical Pixel 9a runtime for:
  - full OpenPhone product identity,
  - privileged `org.openphone.assistant` package,
  - running `org.openphone.assistant/.OpenPhoneAssistantService`, and
  - registered `openphone_agent` Binder service.
- Device support matrix placeholder.

## Not Yet Implemented

- Hardware validation on the Pixel 9a. Full OpenPhone now boots and the
  assistant/framework service are verified, but Wi-Fi, cellular, camera,
  microphone, GPS, fingerprint, encryption, and reboot stability still need
  pass/fail evidence.
- Fully reproducible device-specific flashable image generation. The current
  Pixel 9a build still requires a manual DTB extraction step before
  target-files/OTA generation.
- Typed framework parcelables for screen context, action requests, policy
  decisions, and audit events. The current Binder contract intentionally uses
  JSON strings while the service boundary is still stabilizing.
- Full screen understanding service with visible text, UI node hierarchy,
  screenshots/OCR, notifications, and scoped data minimization. Current
  framework context is limited to foreground and visible activity metadata.
- Full action execution service for notification actions, app-specific
  integrations, and richer input targeting. Current framework
  action execution supports launcher actions, navigation keys, pointer gestures,
  scroll gestures, keyboard text, clipboard text actions, and confirmed share
  chooser launch, but does not yet have UI-element targeting or notification
  integrations.
- Full confirmation UX and grant lifecycle for medium/high-risk capabilities.
  A basic assistant approve/deny path exists for pending actions, but there is
  no system modal, timeout, per-app grant editor, or high-friction payment/
  messaging flow.
- Settings integration and a dedicated Settings-hosted audit surface. The
  assistant now has a basic audit browser, but Settings does not.
- SystemUI background task surface.
- Remaining SELinux policy for richer action execution and future services.
- OTA client and release signing.
- Kernel source publication flow.
- Vendor blob extraction and redistribution policy per device.

## Current Build Evidence

Validated commands:

```bash
./scripts/check.sh
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" ./scripts/apply-patches.sh
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" OPENPHONE_BUILD_GOAL=droid ./scripts/build.sh openphone_arm64
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" OPENPHONE_BUILD_GOAL=services.core-android_common-checkbuild ./scripts/build.sh openphone_arm64
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" OPENPHONE_BUILD_GOAL=OpenPhoneAssistant ./scripts/build.sh openphone_arm64
```

The generic `openphone_arm64` `droid` graph now completes on the prepared
macOS build host. This validates the OpenPhone overlay, product definition,
privileged assistant package, and current Darwin compatibility patch stack.
The focused `services.core-android_common-checkbuild` target validates the
hidden framework manager API, signature permissions, `SystemServiceRegistry`
registration, `system_server` service startup wiring, foreground activity
context, seed policy, durable audit logging, and the mediated `open_app` and
task-scoped input/clipboard/share action paths, including pending action
confirmation.

One broader `framework-minus-apex-android_common-checkbuild` attempt reached
the OpenPhone framework compile checkpoints but failed later in an unrelated
Darwin host-link step for Cronet protobuf (`ld64.lld` rejected GNU-style linker
flags). The narrower `services.core` check above avoids that unrelated host
tool path and validates the OpenPhone framework/service code directly.

This is not yet a phone-ready release. The generic target currently produces
build metadata under `out/target/product/generic_arm64`, but not a validated
flashable target image. An explicit `systemimage` build goal is not available
for this target in the current tree.

Pixel 9a `tegu` smoke-build evidence:

- Official Lineage 23.2 boots on the purchased Pixel 9a.
- OpenPhone smoke dynamic partitions boot when paired with the official Lineage
  `vendor_kernel_boot.img`.
- The first OpenPhone smoke OTA failed because generated
  `vendor_kernel_boot.img` had a zero-byte DTB.
- The DTB-fixed OpenPhone smoke OTA boots successfully on slot `_a`.
- The root cause and current DTB extraction fix are documented in
  [TEGU_BOOTCHAIN.md](TEGU_BOOTCHAIN.md) and
  [BRINGUP_LOG.md](BRINGUP_LOG.md).

Pixel 9a `tegu` full-product evidence:

- Built full `openphone_tegu` OTA:
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-ota.zip`.
- First full OTA exposed full product identity but crashed in
  `system_server` because SELinux denied the `openphone_agent` service
  registration.
- Added `patches/system_sepolicy/0001-OpenPhone-label-agent-manager-service.patch`.
- Rebuilt and sideloaded:
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-selinuxfix-ota.zip`.
- SELinux-fixed OTA SHA-256:
  `d6a6a6153af8c37fd03ddd2e4144aa51c9a79662cf936210bd0b4c9d546316f8`.
- Verified on the physical Pixel 9a:
  - `ro.product.model=OpenPhone Pixel 9a`
  - `ro.openphone.version=0.1.0-dev`
  - `ro.openphone.smoke_build=` empty
  - `sys.boot_completed=1`
  - `pm list packages` includes `org.openphone.assistant`
  - `service list` includes
    `openphone_agent: [android.openphone.IOpenPhoneAgentService]`
  - `service check openphone_agent` reports `found`
  - `dumpsys activity services` shows
    `org.openphone.assistant/.OpenPhoneAssistantService`
  - `monkey -p org.openphone.assistant 1` displays
    `org.openphone.assistant/.MainActivity`

## Next Engineering Step

Move from boot/runtime verification to capability validation on the physical
Pixel 9a:

1. Run the assistant UI task flow on-device.
2. Validate screen context reads from `OpenPhoneAgentManagerService`.
3. Validate one low-risk action flow such as open Settings, Back, Home, and
   scroll.
4. Validate task-scoped input grant behavior.
5. Validate pending confirmation for clipboard/share.
6. Confirm durable audit events under `/data/system/openphone/audit-log.json`.
7. Automate the Pixel 9a DTB extraction/build assertion so the full OTA is
   reproducible from a clean tree.
