# V1 Plan: Working AI-First Android Phone

This document defines the first usable OpenPhone milestone: a physical Android
phone that boots OpenPhone, exposes an OS-level AI assistant, can understand the
current screen at a basic level, can perform scoped actions across apps, and
keeps the user in control through policy, confirmation, and auditability.

V1 is not a polished consumer release. It is the first end-to-end engineering
proof that OpenPhone is an AI-first phone OS, not just an app running on top of
Android.

## Executive Summary

The goal is to produce the first working OpenPhone device: a Pixel 9a running a
custom Android/Lineage-derived OS where the assistant is a privileged OS
component, not a normal Android app. The assistant should be able to ask the OS
for screen context, request cross-app actions, and rely on OS-level policy,
confirmation, and audit logging.

What we have proven so far:

- The purchased Pixel 9a can be unlocked, flashed, recovered, and booted.
- Official LineageOS 23.2 boots on the device.
- An OpenPhone smoke OTA boots on the device.
- The first serious boot-chain failure was diagnosed to a generated
  `vendor_kernel_boot.img` with an empty DTB.
- The DTB extraction fix produces a bootable OpenPhone smoke OTA.
- The repository already contains the first OS-level OpenPhone framework
  patches, privileged assistant app, policy seed, action executor, and audit
  logger.

What we have not proven yet:

- The full `openphone_tegu` product has not yet been accepted as V1 until the
  assistant and `OpenPhoneAgentManagerService` are verified on the physical
  phone.
- The phone is not yet a working AI phone. It is currently a custom OS bring-up
  with the first AI-control substrate under construction.
- The model loop, natural language task runner, Settings integration, richer
  screen understanding, and hardware smoke tests remain to be completed.

The right next move is not more device shopping and not an app-only prototype.
It is to finish the Pixel 9a full-product loop:

```text
build full OpenPhone OTA -> flash -> boot -> verify OS service ->
verify assistant -> execute actions -> audit -> add model loop
```

## V1 Product Promise

V1 should demonstrate one clear thing:

```text
This is an Android phone where the AI is part of the operating system and acts
through OS-controlled capabilities, not through fragile per-app hacks.
```

For V1, "AI-first" means:

- The assistant is installed as a privileged, persistent system component.
- The assistant has a formal OS API for context, actions, policy, and audit.
- The OS mediates every action.
- The user grants task capabilities before action execution.
- Sensitive actions require explicit confirmation.
- The system records what the assistant saw, requested, and did.

For V1, "AI-first" does not yet mean:

- A fully local model.
- Perfect visual understanding of every app.
- Autonomous background operation without user-visible controls.
- Passing Play Integrity.
- A consumer-ready app store, OTA updater, or production signing pipeline.

## Proposed V1 Architecture

OpenPhone V1 is split into layers so that the AI can become more capable
without giving it raw, unbounded control of the phone.

```text
User
  |
  v
OpenPhoneAssistant
  - visible task UI
  - model adapter
  - user grant and confirmation UI
  - audit viewer
  |
  v
android.openphone.OpenPhoneAgentManager
  - hidden framework manager API
  - trusted client boundary
  |
  v
OpenPhoneAgentManagerService in system_server
  - task registry
  - screen context provider
  - policy engine
  - action executor
  - confirmation queue
  - durable audit log
  |
  v
Android system services
  - ActivityTaskManager
  - WindowManager/InputManager
  - Launcher/PackageManager
  - ClipboardManager
  - Sharesheet
  - Settings/SystemUI later
```

### Component Responsibilities

`OpenPhoneAssistant`

- Owns the user-facing assistant experience.
- Starts tasks and requests capabilities.
- Calls `OpenPhoneAgentManager`.
- Displays screen context, action results, pending confirmations, and audit
  history.
- Hosts the first model adapter and structured agent loop.

`OpenPhoneAgentManager`

- Is the framework-facing Java API exposed to trusted OpenPhone components.
- Keeps apps from talking directly to raw system internals.
- Uses signature permissions so only platform-trusted packages can call it.

`OpenPhoneAgentManagerService`

- Runs in `system_server`.
- Owns the authority to read OS context and execute mediated actions.
- Applies policy before actions.
- Creates one-shot pending action IDs for confirmation-required actions.
- Writes durable audit events under `/data/system/openphone/`.

Policy layer

- Treats unknown actions as denied.
- Allows low-risk actions only inside an approved task.
- Requires task-scoped grants for pointer/text input.
- Requires explicit confirmation for clipboard paste, share, and later
  messaging/payment-style actions.

Audit layer

- Records task creation, context reads, policy decisions, action requests,
  confirmations, denials, and action results.
- Avoids logging sensitive payloads by default.
- Must survive reboot and be inspectable without adb before V1 is done.

Model layer

- Starts as a remote development adapter.
- Must produce structured action requests, not free-form shell commands.
- Must receive only the minimum context needed for the task.
- Must route every action through the OS manager and policy service.

## Current Progress Snapshot

Status as of 2026-05-29:

| Track | Status | Evidence / Notes |
| --- | --- | --- |
| Repo structure | pass | OpenPhone repo contains manifests, overlays, patches, scripts, docs, contracts, and device docs. |
| Legal/licensing docs | started | `LICENSE`, `NOTICE`, and `docs/LICENSING.md` exist; commercial policy still needs final legal review. |
| Upstream base | pass | LineageOS selected as practical AOSP base for device support. |
| First device | pass | Pixel 9a `tegu`, verified SKU `GTF7P`. |
| Bootloader unlock | pass | Device is unlocked and reports verified boot state `orange`. |
| Official Lineage boot | pass | Official Lineage 23.2 booted successfully. |
| OpenPhone smoke boot | pass | DTB-fixed smoke OTA booted and reported OpenPhone properties. |
| Boot-chain diagnosis | pass | Empty generated DTB isolated and documented. |
| Full OpenPhone product | pass | SELinux-fixed `openphone_tegu` OTA boots on Pixel 9a and reports `ro.openphone.version=0.1.0-dev`. |
| Privileged assistant | pass | `org.openphone.assistant` is installed as a privileged persistent system app, its service is running, and its activity launches. |
| Framework manager/service | pass | `service list` reports `openphone_agent: [android.openphone.IOpenPhoneAgentService]` and `service check openphone_agent` reports `found`. |
| Screen context | partial | Focused/visible activity metadata implemented; no OCR/UI tree yet. |
| Action execution | partial | Launch/navigation/pointer/text/clipboard/share actions implemented; needs physical tests. |
| Policy/confirmation | partial | Seed policy and pending actions exist; UX needs hardening. |
| Audit log | partial | Durable framework audit exists; needs physical validation and Settings surface. |
| Model loop | not started | No real natural-language agent loop yet. |
| Settings/SystemUI | not started | Needed for discoverability and product quality. |
| Hardware validation | pending | Wi-Fi, cellular, camera, microphone, GPS, fingerprint still need pass/fail evidence. |
| Reproducible release | pending | DTB fix must be automated and release checklist added. |

## Flashing Runbook for Pixel 9a

This is the current development flashing flow. It assumes the bootloader is
already unlocked and the device is allowed to be wiped.

### 1. Build on Linux

Use a Linux x86_64 build host. The current EC2 layout is:

```text
/home/ubuntu/OpenPhone
/home/ubuntu/OpenPhone/.worktree/android
```

Apply OpenPhone patches:

```bash
cd /home/ubuntu/OpenPhone
./scripts/apply-patches.sh
```

Prepare the Pixel 9a DTB until this is automated:

```bash
cd /home/ubuntu/OpenPhone/.worktree/android
rm -rf /tmp/vkb-prebuilt
mkdir -p /tmp/vkb-prebuilt
out/host/linux-x86/bin/unpack_bootimg \
  --boot_img device/google/tegu-kernels/6.1/vendor_kernel_boot.img \
  --out /tmp/vkb-prebuilt
cp /tmp/vkb-prebuilt/dtb device/google/tegu-kernels/6.1/tegu.dtb
sha256sum device/google/tegu-kernels/6.1/tegu.dtb
```

Expected DTB hash:

```text
f1aed2bc4c07d3cb1e610f5227a566f22e995dfe05341ca6bf14805be6928688
```

Build full target-files:

```bash
cd /home/ubuntu/OpenPhone
OPENPHONE_RELEASE=bp4a \
OPENPHONE_BUILD_GOAL=target-files-package \
./scripts/build.sh openphone_tegu
```

Generate the OTA:

```bash
cd /home/ubuntu/OpenPhone/.worktree/android
TF=out/target/product/tegu/obj/PACKAGING/target_files_intermediates/openphone_tegu-target_files.zip
out/host/linux-x86/bin/ota_from_target_files \
  "$TF" \
  out/target/product/tegu/openphone_tegu-bp4a-v1-dev-ota.zip
```

Validate the generated `vendor_kernel_boot.img`:

```bash
rm -rf /tmp/vkb-check-full
mkdir -p /tmp/vkb-check-full
out/host/linux-x86/bin/unpack_bootimg \
  --boot_img out/target/product/tegu/obj/PACKAGING/target_files_intermediates/openphone_tegu-target_files/IMAGES/vendor_kernel_boot.img \
  --out /tmp/vkb-check-full
ls -lh /tmp/vkb-check-full/dtb
sha256sum /tmp/vkb-check-full/dtb
```

Required result:

- DTB is not zero bytes.
- DTB SHA-256 matches the known-good Pixel 9a DTB hash above.

### 2. Copy Artifact to Local Machine

```bash
mkdir -p .worktree/artifacts/tegu
scp -i "$OPENPHONE_BUILD_SSH_KEY" \
  "$OPENPHONE_BUILD_HOST":/home/ubuntu/OpenPhone/.worktree/android/out/target/product/tegu/openphone_tegu-bp4a-v1-dev-ota.zip \
  .worktree/artifacts/tegu/
shasum -a 256 .worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-ota.zip
```

### 3. Sideload OTA

From a booted phone:

```bash
adb reboot recovery
```

On the phone:

```text
Apply update -> Apply from ADB
```

From the host:

```bash
adb sideload .worktree/artifacts/tegu/openphone_tegu-bp4a-v1-dev-ota.zip
```

Expected prompts:

- If recovery says signature verification failed, choose `Yes`.
- If recovery asks to install additional packages, choose `No`.

For clean V1 validation after a product switch:

```text
Factory reset -> Format data / factory reset -> confirm
Reboot system now
```

### 4. Verify Boot and OS Identity

```bash
adb wait-for-device
adb devices -l
adb shell 'printf "slot="; getprop ro.boot.slot_suffix'
adb shell 'printf "product="; getprop ro.product.name'
adb shell 'printf "model="; getprop ro.product.model'
adb shell 'printf "openphone="; getprop ro.openphone.version'
adb shell 'printf "smoke="; getprop ro.openphone.smoke_build'
adb shell 'printf "lineage="; getprop ro.lineage.version'
adb shell 'printf "security_patch="; getprop ro.build.version.security_patch'
adb shell 'printf "vbstate="; getprop ro.boot.verifiedbootstate'
```

Expected V1 direction:

- Product/model should identify as OpenPhone Pixel 9a, not smoke.
- `ro.openphone.version` should be present.
- `ro.openphone.smoke_build` should be absent or false for the full product.
- Verified boot state is expected to remain `orange` on the unlocked device.

### 5. Verify OpenPhone Runtime

```bash
adb shell pm list packages | grep -i openphone
adb shell service list | grep -i openphone
adb shell dumpsys activity services | grep -i openphone
adb shell ls -l /data/system/openphone/ || true
adb shell logcat -d | grep -i openphone | tail -200
```

Minimum pass:

- `org.openphone.assistant` or equivalent assistant package is installed.
- `OpenPhoneAgentManagerService` appears in service diagnostics.
- Assistant can launch.
- Audit directory/log appears after task activity.

### 6. Manual AI Substrate Test

The first manual test is intentionally not model-driven. We need to verify the
OS substrate before asking a model to use it.

```text
open assistant -> create task -> grant input.perform ->
read screen context -> open Settings -> scroll -> Back -> Home ->
read audit log
```

Only after this passes should we wire the first model adapter.

## Definition of V1

V1 is achieved when a Pixel 9a can be flashed with an OpenPhone OTA and pass
this demo:

1. Boot into OpenPhone without manual partition repair.
2. Show OpenPhone identity in system properties and Settings/About.
3. Start the OpenPhone assistant as a privileged system component.
4. Let the user start a visible assistant task.
5. Read the current foreground app/screen context through an OpenPhone
   framework service.
6. Perform low-risk actions across apps, such as launch app, Back, Home,
   Recents, scroll, tap, and text input, through OS mediation.
7. Require explicit task grants for input actions.
8. Require confirmation for medium/high-risk actions such as clipboard paste
   and share.
9. Persist an audit log showing task creation, context reads, policy decisions,
   action requests, confirmations, and action results.
10. Survive reboot with the assistant service and framework service available.

V1 should feel like this:

```text
The phone is running OpenPhone.
The assistant is part of the OS.
The assistant can see what app/screen is active.
The assistant can act across apps after user-approved grants.
The user can inspect what happened.
```

## Current Context

OpenPhone is based on LineageOS/AOSP, with OpenPhone-owned code kept in this
repository as overlay files, patches, scripts, manifests, and docs. The full
Android tree is synced externally through `repo`.

Current device target:

- Device: Google Pixel 9a
- Codename: `tegu`
- Upstream: LineageOS `lineage-23.2`
- Android release: Android 16 / `bp4a`
- OpenPhone smoke product: `openphone_tegu_smoke`
- Full OpenPhone product target: `openphone_tegu`

Current proven state:

- Official LineageOS boots on the Pixel 9a.
- OpenPhone smoke OTA boots on the Pixel 9a.
- The DTB-fixed OpenPhone smoke OTA reports:
  - `ro.openphone.smoke_build=true`
  - `ro.openphone.version=0.1.0-smoke`
  - `ro.product.model=OpenPhone Smoke Pixel 9a`
  - `ro.boot.slot_suffix=_a`
  - `ro.boot.verifiedbootstate=orange`
- The SELinux-fixed full OpenPhone OTA boots on the Pixel 9a and reports:
  - `ro.openphone.version=0.1.0-dev`
  - `ro.openphone.smoke_build=` empty
  - `ro.product.model=OpenPhone Pixel 9a`
  - `sys.boot_completed=1`
  - `dev.bootcomplete=1`
- The first OpenPhone smoke OTA failed because generated
  `vendor_kernel_boot.img` had a zero-byte DTB.
- The DTB-fixed OpenPhone smoke OTA boots successfully.
- The first full OpenPhone OTA failed because SELinux denied registration of
  `openphone_agent`; the fix is
  `patches/system_sepolicy/0001-OpenPhone-label-agent-manager-service.patch`.
- The full OpenPhone OTA now verifies:
  - `pm list packages` includes `org.openphone.assistant`
  - `service list` includes
    `openphone_agent: [android.openphone.IOpenPhoneAgentService]`
  - `dumpsys activity services` shows
    `org.openphone.assistant/.OpenPhoneAssistantService`
- The root cause and fix are documented in
  [TEGU_BOOTCHAIN.md](TEGU_BOOTCHAIN.md).

Important distinction:

- `openphone_tegu_smoke` proves bootability and product identity with minimal
  OpenPhone runtime changes.
- `openphone_tegu` includes the full OpenPhone assistant, framework patches,
  policy, audit, and system integrations. It now boots and exposes the assistant
  and framework service on the physical Pixel 9a.

## Flashing and Recovery Workflow

### Build Host

Use a Linux x86_64 host for full device OTAs. The current EC2 workflow uses:

```text
/home/ubuntu/OpenPhone
/home/ubuntu/OpenPhone/.worktree/android
```

Local macOS is useful for repository work, adb/fastboot, and artifact
management, but full phone images should be produced on Linux.

### Prepare Android Tree

From the OpenPhone repository on the build host:

```bash
./scripts/sync.sh
./scripts/apply-patches.sh
```

For Pixel 9a target-files, apply the current DTB preparation step before
building:

```bash
cd "$OPENPHONE_ANDROID_DIR"
rm -rf /tmp/vkb-prebuilt
mkdir -p /tmp/vkb-prebuilt
out/host/linux-x86/bin/unpack_bootimg \
  --boot_img device/google/tegu-kernels/6.1/vendor_kernel_boot.img \
  --out /tmp/vkb-prebuilt
cp /tmp/vkb-prebuilt/dtb device/google/tegu-kernels/6.1/tegu.dtb
sha256sum device/google/tegu-kernels/6.1/tegu.dtb
```

Expected DTB SHA-256:

```text
f1aed2bc4c07d3cb1e610f5227a566f22e995dfe05341ca6bf14805be6928688
```

This should become automated before V1 is considered reproducible.

### Build Smoke OTA

Smoke OTA command:

```bash
OPENPHONE_RELEASE=bp4a \
OPENPHONE_BUILD_GOAL=target-files-package \
./scripts/build.sh openphone_tegu_smoke
```

Then generate an installable OTA from target-files:

```bash
cd "$OPENPHONE_ANDROID_DIR"
TF=out/target/product/tegu/obj/PACKAGING/target_files_intermediates/openphone_tegu_smoke-target_files.zip
out/host/linux-x86/bin/ota_from_target_files \
  "$TF" \
  out/target/product/tegu/openphone_tegu_smoke-bp4a-dtbfix-ota.zip
```

Validate `vendor_kernel_boot.img`:

```bash
rm -rf /tmp/vkb-check
mkdir -p /tmp/vkb-check
out/host/linux-x86/bin/unpack_bootimg \
  --boot_img out/target/product/tegu/obj/PACKAGING/target_files_intermediates/openphone_tegu_smoke-target_files/IMAGES/vendor_kernel_boot.img \
  --out /tmp/vkb-check
ls -lh /tmp/vkb-check/dtb
sha256sum /tmp/vkb-check/dtb
```

Expected:

- DTB size: `1546258`
- DTB SHA-256:
  `f1aed2bc4c07d3cb1e610f5227a566f22e995dfe05341ca6bf14805be6928688`

### Build Full OpenPhone OTA

V1 should move from the smoke product to the full product:

```bash
OPENPHONE_RELEASE=bp4a \
OPENPHONE_BUILD_GOAL=target-files-package \
./scripts/build.sh openphone_tegu
```

Then generate OTA:

```bash
cd "$OPENPHONE_ANDROID_DIR"
TF=out/target/product/tegu/obj/PACKAGING/target_files_intermediates/openphone_tegu-target_files.zip
out/host/linux-x86/bin/ota_from_target_files \
  "$TF" \
  out/target/product/tegu/openphone_tegu-bp4a-v1-dev-ota.zip
```

The full target must pass the same `vendor_kernel_boot` DTB validation before
flashing.

### Flash OTA Through Recovery

From a booted system:

```bash
adb reboot recovery
```

On device:

```text
Apply update -> Apply from ADB
```

From the host:

```bash
adb sideload path/to/openphone_tegu-bp4a-v1-dev-ota.zip
```

On device:

- If signature verification warning appears, choose `Yes`.
- If asked to install additional packages, choose `No`.
- Reboot system.

For clean validation after major product changes:

```text
Factory reset -> Format data / factory reset -> confirm
Reboot system now
```

### Post-Flash Verification

After boot:

```bash
adb devices -l
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.openphone.version
adb shell getprop ro.product.model
adb shell getprop ro.lineage.version
adb shell getprop ro.build.version.security_patch
adb shell getprop ro.boot.verifiedbootstate
```

V1 should also verify:

```bash
adb shell service list | grep -i openphone
adb shell pm list packages | grep -i openphone
adb shell dumpsys activity services | grep -i openphone
adb shell ls -l /data/system/openphone/
```

## V1 Feature Scope

### 1. OpenPhone Product Identity

Required:

- Product name, model, brand, and manufacturer identify as OpenPhone.
- OpenPhone version property exists.
- Smoke/full build distinction is clear.
- Boot animation and default wallpaper are OpenPhone-owned or neutral.
- Settings/About shows OpenPhone identity.

Current status:

- Smoke product identity works.
- Full product identity exists in overlay but needs device boot validation.
- Settings/About integration is not done.

### 2. Privileged Assistant

Required:

- `OpenPhoneAssistant` is installed as a privileged persistent system app.
- Assistant starts after boot.
- Assistant exposes a visible task UI for test/demo.
- Assistant can call OpenPhone framework manager APIs.
- Assistant can show service health, current context, action result, pending
  confirmation, and recent audit events.

Current status:

- Assistant module and service scaffolding exist.
- Privileged permission allowlist exists.
- Assistant bridge to framework manager exists.
- Module-level builds were validated.
- Needs validation inside a booted full Pixel 9a OTA.

### 3. Framework Agent Service

Required:

- `OpenPhoneAgentManagerService` starts from `system_server`.
- Hidden manager API is available to trusted OpenPhone components.
- Signature permissions gate access.
- Service status is observable through assistant and/or shell diagnostics.

Current status:

- Framework service patches exist.
- System service registration patches exist.
- Signature permission declarations exist.
- Needs validation on the physical Pixel 9a full product.

### 4. Screen Context

Required for V1:

- Report focused app/activity.
- Report visible activity list where available.
- Include foreground user and timestamp.
- Avoid raw screenshot/OCR in V1 unless explicitly enabled.

Stretch for V1:

- Basic screenshot capture for assistant-visible confirmation.
- Basic UI hierarchy extraction for clickable target discovery.

Current status:

- Focused and visible activity metadata is implemented in framework patches.
- OCR/UI hierarchy is not implemented.

### 5. Action Execution

Required for V1:

- Launch app by package name.
- Back, Home, Recents.
- Tap coordinate.
- Long press coordinate.
- Scroll.
- Text input.
- Clipboard write.
- Clipboard paste after confirmation.
- Confirmed share chooser.

Current status:

- These action classes are implemented in the framework patch stack.
- Task-scoped input grants exist.
- Pending confirmation flow exists.
- Needs validation on the physical full product.

### 6. Policy and Consent

Required:

- All actions flow through policy.
- Unknown actions fail closed.
- Low-risk actions can run with task approval.
- Input actions require explicit task-scoped `input.perform`.
- Medium/high-risk actions create pending confirmation IDs.
- Confirmed action executes once.
- Denied action does not execute.

Current status:

- Seed policy exists.
- Task-scoped grants exist.
- Pending action confirmation exists.
- Needs UX hardening and device validation.

### 7. Audit Log

Required:

- Audit log persists under `/data/system/openphone/audit-log.json`.
- Events include task, context, policy, action, confirmation, and result.
- Assistant can display recent audit events.
- Sensitive payloads are minimized; clipboard contents are not logged by
  default.

Current status:

- Durable audit log patch exists.
- Assistant audit browser exists.
- Needs physical validation after full product boot.

### 8. SystemUI and Settings

Required for V1:

- Minimal Settings entry for OpenPhone status, audit, and permissions.
- Minimal always-available assistant entry point, either launcher app,
  notification, Quick Settings tile, or SystemUI affordance.

Current status:

- Settings/SystemUI patch directories exist as placeholders.
- Real Settings/SystemUI integration is not implemented.

V1 can ship with a launcher-visible assistant app if the deeper SystemUI entry
point is delayed, but Settings/About identity should still be implemented.

### 9. Model Runtime and Agent Loop

Required for V1:

- A simple agent loop that accepts a user instruction.
- Reads screen context.
- Produces an action request.
- Sends the request through framework policy/action APIs.
- Shows result and audit trail.

Acceptable V1 model strategy:

- Remote model adapter during development.
- Explicit network disclosure.
- No hidden background upload of screenshots or sensitive data.
- Structured tool-call style action requests.

Not required for V1:

- Fully local model inference.
- Offline operation.
- Long-running autonomous planning.
- App-specific semantic integrations.

Current status:

- OS action substrate exists in patches.
- Actual model adapter and end-to-end agent loop are not implemented.

### 10. Hardware Smoke Tests

Required:

- Wi-Fi
- Bluetooth
- Cellular data
- Calls/SMS if SIM is available
- Camera
- Microphone/speaker
- Fingerprint
- GPS
- USB/ADB
- Reboot stability
- Data wipe and fresh boot

Current status:

- USB/ADB, recovery, OTA sideload, and first boot pass.
- Hardware feature matrix is still pending.

## Task Plan

### Milestone A: Freeze the Known-Good Smoke Baseline

Goal: make the current success reproducible.

Tasks:

- [x] Boot official Lineage on Pixel 9a.
- [x] Build OpenPhone smoke target-files.
- [x] Sideload OpenPhone smoke OTA.
- [x] Diagnose first boot failure.
- [x] Isolate `vendor_kernel_boot` as the failing image.
- [x] Identify zero-byte DTB root cause.
- [x] Extract DTB from known-good prebuilt.
- [x] Build DTB-fixed smoke OTA.
- [x] Boot DTB-fixed smoke OTA on slot `_a`.
- [x] Record artifact hash and boot properties.
- [ ] Automate DTB extraction in OpenPhone scripts.
- [ ] Add a build-time assertion that `vendor_kernel_boot` DTB is non-empty.
- [ ] Add smoke OTA build instructions for `tegu` to device docs.

### Milestone B: Boot the Full OpenPhone Product

Goal: move from `openphone_tegu_smoke` to `openphone_tegu`.

Tasks:

- [x] Build `openphone_tegu` target-files with DTB fix.
- [x] Generate `openphone_tegu-bp4a-v1-dev-ota.zip`.
- [x] Validate generated `vendor_kernel_boot.img` DTB.
- [x] Sideload full OTA.
- [x] Diagnose full OTA `system_server` crash from missing SELinux service
  label.
- [x] Add `openphone_agent` SELinux service label.
- [x] Rebuild and sideload SELinux-fixed full OTA.
- [x] Verify boot.
- [x] Verify OpenPhone product properties.
- [x] Verify assistant package is installed.
- [x] Verify framework service starts.
- [x] Capture logs from first boot.
- [x] Update `docs/BRINGUP_LOG.md`.

Exit criteria:

- Full product boots without manual partition repair.
- `adb shell getprop ro.openphone.version` reports expected V1 dev version.
- Assistant and framework service are present after boot.

### Milestone C: Validate OS-Level Assistant Substrate

Goal: prove the assistant can call the OS service on the device.

Tasks:

- [x] Launch OpenPhoneAssistant.
- [x] Show framework service health through shell diagnostics.
- [ ] Create a task from assistant UI.
- [ ] Read focused/visible activity context.
- [ ] Display context in assistant UI.
- [ ] Read recent audit events.
- [ ] Reboot and confirm assistant/service availability persists.

Exit criteria:

- User can see current app/activity context through OpenPhoneAssistant.
- Audit events appear for task creation and context read.

### Milestone D: Validate Cross-App Actions

Goal: prove OS-mediated action execution works on the phone.

Tasks:

- [ ] `open_app`: launch Settings.
- [ ] `open_app`: launch Browser or another installed app.
- [ ] Back action.
- [ ] Home action.
- [ ] Recents action.
- [ ] Scroll action in Settings.
- [ ] Tap action on a known coordinate.
- [ ] Text input into a safe text field.
- [ ] Clipboard write after confirmation.
- [ ] Clipboard paste after confirmation.
- [ ] Confirmed share chooser.
- [ ] Audit each action.
- [ ] Verify action denial without required grant.
- [ ] Verify pending action cannot execute twice.

Exit criteria:

- At least one complete cross-app workflow succeeds:

```text
User starts task -> grants input.perform -> assistant opens Settings ->
reads context -> scrolls -> goes Home -> audit log shows all steps.
```

### Milestone E: Add First Real Agent Loop

Goal: move from button-driven test actions to an AI-controlled loop.

Tasks:

- [ ] Define local JSON contract for model requests and action responses.
- [ ] Add model adapter interface in assistant.
- [ ] Add initial remote model adapter.
- [ ] Add a prompt that consumes screen context and allowed capabilities.
- [ ] Force model output into structured action requests.
- [ ] Send model actions through `OpenPhoneAgentManager`.
- [ ] Render assistant reasoning summary and action result.
- [ ] Add stop/cancel button.
- [ ] Add task timeout.
- [ ] Add audit event for model-request and model-action metadata without
  logging sensitive prompt contents by default.

Exit criteria:

- User can type: `Open Settings and show me the battery page`.
- Assistant reads context, opens Settings, navigates with allowed actions, and
  shows audit trail.

### Milestone F: User Control UX

Goal: make the safety model understandable enough for a V1 demo.

Tasks:

- [ ] Add a task grant screen.
- [ ] Show requested capabilities before task starts.
- [ ] Add confirmation UI for pending actions.
- [ ] Add deny path with clear result.
- [ ] Add recent audit viewer.
- [ ] Add "stop task" action.
- [ ] Add background-running indicator if background tasks are enabled.

Exit criteria:

- User can see what the assistant is allowed to do before it acts.
- User can approve/deny medium-risk actions.
- User can inspect what happened after the task.

### Milestone G: Settings and Branding

Goal: make the phone visibly OpenPhone, not only property-level OpenPhone.

Tasks:

- [ ] Add OpenPhone entry in Settings.
- [ ] Add About-phone OpenPhone version.
- [ ] Add OpenPhone audit page or link to assistant audit page.
- [ ] Add OpenPhone permission/grant page or link.
- [ ] Replace remaining Lineage-only user-facing naming where appropriate.
- [ ] Add OpenPhone boot animation or neutral boot animation.
- [ ] Add OpenPhone default launcher label and icon.

Exit criteria:

- A user can identify the device as OpenPhone from normal UI surfaces.
- OpenPhone controls are discoverable without adb.

### Milestone H: Hardware and Stability Pass

Goal: ensure the phone can be used for demos without basic hardware surprises.

Tasks:

- [ ] Wi-Fi test.
- [ ] Bluetooth test.
- [ ] Cellular data test.
- [ ] Calls/SMS test.
- [ ] Camera photo/video test.
- [ ] Microphone/speaker test.
- [ ] Fingerprint enrollment/unlock test.
- [ ] GPS/location test.
- [ ] Screen lock/unlock test.
- [ ] Reboot test.
- [ ] Factory reset and re-setup test.
- [ ] Battery/thermal sanity test during assistant actions.

Exit criteria:

- `devices/tegu.md` hardware checklist is updated with pass/fail evidence.
- No critical hardware issue blocks V1 demo.

### Milestone I: Reproducible Release Artifact

Goal: make V1 buildable and flashable by another developer.

Tasks:

- [ ] Automate DTB extraction or prebuilt DTB placement.
- [ ] Add `scripts/build-tegu-v1.sh` or documented equivalent.
- [ ] Add build artifact naming convention.
- [ ] Add SHA-256 generation.
- [ ] Add install instructions with recovery prompts.
- [ ] Add rollback/restoration notes.
- [ ] Add known issues.
- [ ] Add release checklist.

Exit criteria:

- A developer can follow docs from a clean checkout and produce the same class
  of OTA.
- A developer can flash a Pixel 9a without relying on chat history.

## V1 Acceptance Test Script

Run this after flashing the V1 full product:

```bash
adb wait-for-device
adb shell getprop ro.boot.slot_suffix
adb shell getprop ro.openphone.version
adb shell getprop ro.product.model
adb shell pm list packages | grep -i openphone
adb shell service list | grep -i openphone
adb shell dumpsys activity services | grep -i openphone
adb shell ls -l /data/system/openphone/ || true
```

Manual acceptance:

- Phone reaches lock screen.
- Assistant app is visible or launchable.
- Assistant reports framework service ready.
- Assistant can create a task.
- Assistant can read current context.
- Assistant can launch Settings.
- Assistant can perform Back/Home.
- Assistant can execute one granted input action.
- Assistant asks for confirmation before clipboard/share action.
- Audit log shows all major events.

## Risks

### Boot Chain Fragility

Pixel 9a packaging currently requires an extracted standalone DTB. If this is
not automated, future builds can regress to fastboot fallback.

Mitigation:

- Automate DTB extraction.
- Fail build if `VENDOR_KERNEL_BOOT/dtb` is missing or zero bytes.

### Smoke Product Drift

The smoke product booted, but the full product includes additional OpenPhone
patches and privileged apps.

Mitigation:

- Move to `openphone_tegu` quickly.
- Validate one integration layer at a time.
- Keep smoke product as known-good fallback.

### SELinux

Framework and privileged app features may work in permissive/userdebug contexts
but fail under stricter SELinux.

Mitigation:

- Capture denials during full product boot.
- Add minimal policy for OpenPhone services and assistant.
- Keep permissions explicit.

### User Trust

An AI-first phone can become dangerous if it acts opaquely.

Mitigation:

- Task-scoped grants.
- Confirmation for sensitive actions.
- Durable audit log.
- Visible stop control.
- No hidden background autonomy in V1.

### Model Privacy

Remote model use may expose sensitive screen or task data.

Mitigation:

- Start with metadata-only context.
- Make remote model use explicit.
- Do not send screenshots/OCR by default.
- Add redaction before richer screen context.

## Immediate Next Actions

1. Automate the Pixel 9a DTB extraction fix.
2. Run the first manual action tests from the assistant UI.
3. Verify focused/visible activity context on the physical phone.
4. Verify audit log persistence after task creation and context/action calls.
5. Add a minimal model adapter and structured agent loop.
6. Add Settings/About OpenPhone identity.
7. Complete hardware smoke tests in `devices/tegu.md`.
