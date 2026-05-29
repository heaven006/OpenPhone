# OpenPhone Specification

## 1. Purpose

OpenPhone is an open, Android-based operating system where an AI agent is a first-class system capability rather than a normal app layered on top of Android.

The goal is to build a phone OS where the assistant can understand and operate the device across applications, with explicit user control, auditability, and system-level integration. The assistant should be able to see what is on screen, understand app and task state, perform user-approved actions, run background work, and coordinate with apps and OS services without depending on fragile app-level automation alone.

OpenPhone is not intended to be a thin Android app using Accessibility APIs. That path has already been explored and is not sufficient for the product we want. OpenPhone is a custom ROM/distribution that integrates the agent into the OS build.

## 2. Product Thesis

Modern mobile assistants are constrained by app sandboxing and limited OS integration. A normal Android app can request Accessibility, notification access, screen capture, and other user-granted permissions, but it still remains an app outside the core operating system. It is fragile, permission-heavy, policy-constrained, and often unable to act reliably across the full device.

OpenPhone takes a different approach:

- The AI agent is shipped as a privileged system component.
- Screen understanding and device control are mediated by OS services.
- Risky actions are governed by explicit user consent and policy.
- Device support is maintained like a ROM project: exact models are tested and supported over time.
- OpenPhone-specific code is source-available for non-commercial use, with commercial licensing available separately.

The practical target is not "flash any Android phone." The target is a high-quality source-available AI OS for a growing list of verified devices.

## 3. Non-Goals

OpenPhone will not initially attempt to:

- Support every Android phone.
- Ship Google Mobile Services without a license.
- Use LineageOS branding or represent itself as LineageOS.
- Depend on a normal app-only Accessibility prototype as the primary product.
- Build a full Android source monorepo containing all upstream code.
- Hide all user actions behind an opaque autonomous agent.
- Bypass user consent for sensitive actions such as purchases, messaging, payments, account changes, or data sharing.

## 4. Base OS Strategy

OpenPhone should initially use LineageOS as the upstream base instead of raw AOSP.

LineageOS is already structured as an aftermarket Android distribution. It provides practical infrastructure that raw AOSP does not provide as directly:

- Device trees for real phones.
- Kernel repositories and known build targets.
- Vendor blob extraction workflows.
- Recovery and OTA packaging patterns.
- Existing examples for hardware bring-up.
- Product configuration patterns for custom ROM distributions.
- A large body of device-specific community knowledge.

AOSP remains the upstream Android foundation, but starting directly from raw AOSP would force OpenPhone to rebuild a large amount of distribution and device enablement machinery before delivering the AI-native OS layer.

OpenPhone should treat LineageOS as upstream infrastructure, not as product identity.

## 5. Repository Strategy

OpenPhone should have one canonical public repository that contains the OpenPhone project definition and source-owned diff, while using Android's standard `repo`-based multi-repository workflow for the full OS source tree.

The canonical repository should be:

```text
OpenPhoneOS/OpenPhone
```

This repository should contain:

```text
README.md
SPEC.md
LICENSE
NOTICE
docs/
devices/
manifests/
patches/
overlay/
scripts/
```

The full Android/Lineage source tree should not be vendored into this repository. Instead, OpenPhone should pin upstream repositories and apply OpenPhone-owned changes on top.

### 5.1 Initial Single-Diff Repo Model

Early development should keep the OpenPhone diff in one repository:

```text
OpenPhone/
  manifests/
    lineage.xml
    openphone.xml
    devices/
      pixel-target.xml
  patches/
    frameworks_base/
    packages_apps_Settings/
    packages_apps_SystemUI/
    frameworks_native/
  overlay/
    vendor/openphone/
    packages/apps/OpenPhoneAssistant/
    packages/apps/OpenPhonePolicy/
  scripts/
    sync.sh
    apply-patches.sh
    build.sh
    flash.sh
```

The intended workflow:

```bash
git clone https://github.com/OpenPhoneOS/OpenPhone
cd OpenPhone

./scripts/sync.sh
./scripts/apply-patches.sh
./scripts/build.sh <device>
```

This makes the project easy to understand:

- One repository for project docs.
- One repository for OpenPhone-owned code.
- One repository for the patch stack.
- One reproducible build entry point.
- Upstream Android/Lineage remains external and pinned.

### 5.2 When to Promote a Patch to a Fork

Patch files are appropriate for small or early changes. A component should be promoted to an OpenPhone fork when:

- The patch touches many files.
- The patch changes frequently.
- Review of `.patch` files becomes harder than review of normal commits.
- Developers need normal Git history, blame, branches, and IDE navigation inside that component.
- Rebase conflicts become frequent.

Examples of future forked repositories:

```text
OpenPhoneOS/frameworks_base
OpenPhoneOS/frameworks_native
OpenPhoneOS/packages_apps_Settings
OpenPhoneOS/packages_apps_SystemUI
OpenPhoneOS/vendor_openphone
OpenPhoneOS/packages_apps_OpenPhoneAssistant
```

Even after forks exist, `OpenPhoneOS/OpenPhone` remains the canonical entry point.

### 5.3 Pinning Upstream

OpenPhone should pin all upstream repositories to exact revisions or stable branches. Reproducibility matters.

Pinning solves:

- Build reproducibility.
- Auditability.
- Reliable debugging.
- Clear source attribution.

Pinning does not eliminate the need to maintain diffs. Security updates, Android version upgrades, and Lineage updates will still require moving the pinned base forward and resolving conflicts.

## 6. Licensing Strategy

OpenPhone must separate upstream licenses from OpenPhone-owned code.

### 6.1 Upstream Code

AOSP, LineageOS, Linux kernel code, device trees, and third-party dependencies keep their original licenses.

OpenPhone cannot relicense code it did not write.

### 6.2 OpenPhone-Owned Code

OpenPhone-owned modules may use a source-available non-commercial license, with separate commercial licensing available from OpenPhone.

This can apply to:

- OpenPhone AI system service.
- OpenPhone assistant app.
- Policy and consent engine.
- Agent orchestration runtime.
- OpenPhone-specific Settings surfaces.
- Branding and assets.
- OpenPhone update client.
- OpenPhone cloud connectors.
- Documentation specific to OpenPhone.

This should not be described as "open source" in the strict OSI sense if commercial use is restricted. The accurate language is:

```text
source-available for non-commercial use
commercial license available separately
```

### 6.3 GPL Boundaries

Linux kernel code and GPL components require special care. If OpenPhone distributes binaries that include GPL-covered modifications, OpenPhone must satisfy the corresponding source obligations.

OpenPhone must not add non-commercial restrictions to GPL-covered code.

### 6.4 Vendor Blobs

Many supported devices require proprietary vendor blobs for camera, modem, GPU, fingerprint, radio, and sensor functionality.

OpenPhone must define a vendor blob policy:

- Prefer extraction scripts when redistribution rights are unclear.
- Only host blobs when redistribution is permitted.
- Document blob source and version per device.
- Keep a per-device compliance record.

### 6.5 Branding

OpenPhone must not ship as LineageOS. It should use its own:

- Product name.
- Build properties.
- Boot animation.
- Update service.
- Settings branding.
- Legal notices.

Google apps and Google Play services must not be bundled unless OpenPhone has appropriate licenses.

## 7. High-Level System Architecture

OpenPhone is composed of the upstream Android/Lineage base plus an OpenPhone AI layer.

```text
Applications
  third-party apps
  system apps
  OpenPhone assistant UI

OpenPhone AI Layer
  Assistant UX
  Agent Orchestrator
  Screen Understanding Service
  Action Execution Service
  Policy and Consent Engine
  Audit Log
  Model Runtime Adapter
  App/Task Context Service

Android Framework Layer
  ActivityTaskManager
  WindowManager
  InputManager
  NotificationManager
  PackageManager
  PermissionManager
  Content providers
  MediaProjection / screenshot internals

Native/System Layer
  init
  system_server
  Binder services
  SELinux policy
  HAL/vendor interfaces
  kernel

Device Layer
  device tree
  kernel config
  vendor blobs
  firmware expectations
  boot/recovery/AVB packaging
```

### 7.1 Source and Build Architecture

OpenPhone uses a small canonical repository plus a pinned Android source tree.
The OpenPhone repository is the source of truth for:

- Upstream manifest pins.
- Device support overlays.
- OpenPhone product definitions.
- Patch stacks against upstream Android/Lineage repositories.
- OpenPhone-owned applications and configuration.
- Build, flash, validation, and release scripts.
- Project specification, contracts, and device documentation.

The Android checkout is generated by `repo` and treated as a build workspace.
OpenPhone changes are replayed into that workspace by `scripts/apply-patches.sh`.
This keeps the public project understandable while still using Android's
native multi-repository build model.

The expected layout is:

```text
OpenPhone/
  SPEC.md
  README.md
  docs/
  devices/
  manifests/
  overlay/
    packages/apps/OpenPhoneAssistant/
    vendor/openphone/
  patches/
    frameworks_base/
    packages_apps_Settings/
    packages_apps_SystemUI/
  scripts/

.worktree/OpenPhoneAndroid/android/
  frameworks/base/
  packages/apps/Settings/
  packages/SystemUI/
  vendor/openphone/
  packages/apps/OpenPhoneAssistant/
```

The canonical repository should remain useful without checking in the full
Android tree. A contributor should be able to inspect the OpenPhone-owned diff,
read the architecture, sync the upstream tree, apply patches, and reproduce the
same build artifacts from the pinned manifest.

### 7.2 Runtime Process Architecture

The runtime architecture should be split across separate trust zones:

```text
OpenPhoneAssistant app process
  user UI
  task list
  confirmation prompts
  audit browser
  voice/text entry

OpenPhone agent process
  planner/orchestrator
  model adapter
  task state
  app integration plugins

system_server
  OpenPhoneAgentManagerService
  screen context mediation
  action execution mediation
  policy enforcement entry point
  audit write path

Android framework services
  ActivityTaskManager
  WindowManager
  InputManager
  NotificationManager
  PackageManager
  ClipboardManager
  PermissionManager

Optional native/system processes
  local model runtime
  OCR/runtime helpers
  update client helpers
```

The assistant UI should not be the security authority. It may request actions
and display confirmations, but the framework service must enforce permissions,
policy, grants, and audit logging. The orchestrator should not inject input or
read sensitive state directly; it should call the framework-mediated APIs.

### 7.3 Main Control Flow

The normal task flow is:

```text
user request
  -> OpenPhoneAssistant
  -> Agent Orchestrator creates task
  -> OpenPhoneAgentManagerService records task and grants
  -> Screen Understanding Service returns scoped context
  -> Orchestrator proposes plan/action
  -> Policy and Consent Engine decides allow/confirm/deny
  -> confirmation UI appears when required
  -> Action Execution Service performs OS-mediated action
  -> Audit Log records the decision and result
  -> Assistant/SystemUI updates visible task state
```

Every action should carry:

- Task ID.
- Calling OpenPhone component identity.
- Requested capability.
- Target app/window/resource where known.
- Reason string suitable for user display.
- Policy decision.
- Audit metadata.

### 7.4 Data Minimization Rule

OpenPhone should not build a single unlimited "screen dump" API. Screen,
notification, clipboard, file, account, and app data must be scoped by task and
capability. The framework service should prefer structured metadata and visible
state over raw content. Raw screenshots, OCR text, clipboard values, message
bodies, account data, and file contents should be treated as sensitive data and
exposed only when the active task has a matching grant.

## 8. Core Components

### 8.1 OpenPhone Assistant

The user-facing assistant surface.

Responsibilities:

- Accept user requests through text, voice, or other input surfaces.
- Show agent plans and confirmations.
- Display active background tasks.
- Provide visibility into what the agent can currently access.
- Provide controls to pause, revoke, or constrain agent behavior.

Likely location:

```text
packages/apps/OpenPhoneAssistant/
```

Initial implementation requirements:

- Run as a privileged persistent system app in the OpenPhone product.
- Start after boot and package replacement.
- Bind to the framework manager when available.
- Provide a minimal task creation surface.
- Show current framework service status.
- Show current scoped screen context.
- Provide approve/deny UI for pending actions.
- Provide a basic audit browser.

Mature implementation requirements:

- Voice and text input.
- Active task drawer.
- Per-task capability chips and revocation controls.
- User-readable plan preview.
- Confirmation prompts with exact target/action wording.
- Emergency pause for all background tasks.
- Privacy mode controls for local-only or cloud-disabled operation.

### 8.2 Agent Orchestrator

The central decision-making service that coordinates perception, planning, tool execution, memory, and model calls.

Responsibilities:

- Receive user goals.
- Request screen/app context.
- Decide whether an action is allowed, needs confirmation, or is blocked.
- Route model calls to local or remote providers.
- Invoke OS actions through controlled system APIs.
- Track task state.

This should not directly bypass policy. All sensitive actions must pass through the Policy and Consent Engine.

Implementation shape:

- Starts as part of the privileged OpenPhone package while the project is
  young.
- Moves to a separate package or process when task execution, model runtime,
  and plugin loading become large enough to justify a harder boundary.
- Persists task state in OpenPhone-owned storage.
- Treats the framework manager as the only supported path for privileged
  perception and device control.
- Uses typed internal tool calls even if the first Binder boundary uses JSON.

The orchestrator must never rely on model output as an authorization decision.
Models may recommend actions; policy authorizes actions.

### 8.3 Screen Understanding Service

Provides structured perception of the current device state.

Inputs may include:

- Window hierarchy.
- Accessibility-like node data.
- Current focused app and activity.
- Screenshots or frame captures.
- OCR output.
- Notification state.
- Clipboard state when allowed.
- Recent task metadata.

Outputs should be structured and machine-readable:

```json
{
  "foreground_app": "com.example.app",
  "activity": "ExampleActivity",
  "visible_text": [],
  "interactive_elements": [],
  "notifications": [],
  "risk_flags": []
}
```

The service should expose only the context needed for the active task and current permission scope.

MVP levels:

```text
level 0:
  foreground app/activity and visible activity metadata

level 1:
  focused window, visible windows, bounds, package/activity, display metadata

level 2:
  visible text and interactive UI nodes with stable element IDs

level 3:
  screenshot capture for approved tasks and OCR-derived text

level 4:
  notification metadata and app-provided semantic context
```

The screen context contract must include:

- Foreground package and activity.
- Visible window list.
- Display dimensions and rotation.
- Interactive elements with bounds and role when available.
- Visible text with source and bounds when available.
- Notification summaries when granted.
- Redaction flags for sensitive windows.
- Timestamp and task ID.

Sensitive windows such as password fields, secure surfaces, payment prompts,
and private/incognito contexts should set risk flags. The default behavior for
unknown sensitive surfaces is to hide content while still reporting that a
restricted surface is present.

### 8.4 Action Execution Service

Executes device actions through OS-mediated interfaces.

Initial action set:

- Tap coordinates or UI elements.
- Long press.
- Scroll.
- Type text.
- Press Back, Home, Recents.
- Open app.
- Launch intent.
- Pull notification shade.
- Tap notification actions.
- Copy/paste where allowed.
- Share content.
- Read selected file/media metadata where allowed.

Future action set:

- Call handling.
- SMS/message drafting and sending.
- Calendar actions.
- Contacts actions.
- Payment or purchase flows with high-friction confirmation.
- App-specific integrations through intents or plugins.

Actions should be classified by risk.

The action pipeline is:

```text
action request
  -> schema validation
  -> caller permission check
  -> task grant lookup
  -> policy decision
  -> optional pending confirmation
  -> executor dispatch
  -> result normalization
  -> audit event
```

Action executors should be narrow and boring:

- App launch executor uses `PackageManager` and launch intents.
- Input executor uses framework input injection and task-scoped grants.
- Clipboard executor uses `ClipboardManager` and confirmation.
- Share executor uses Android's chooser and confirmation.
- Notification executor uses `NotificationManagerInternal` and confirmation
  for actions that leave the device or change app state.
- Settings executor uses explicit Settings/provider APIs, not blind coordinate
  tapping, when Android exposes a stable API.

Coordinate input is acceptable for MVP but should not be the long-term primary
interface. The mature system should resolve semantic UI element IDs to current
bounds immediately before execution, so layout changes do not cause stale taps.

### 8.5 Policy and Consent Engine

The Policy and Consent Engine is a required safety boundary.

It decides:

- What the assistant may see.
- What the assistant may do.
- Whether a task can run in the background.
- Whether an action needs confirmation.
- Whether a permission expires after a task.
- Whether an action is blocked entirely.

Policy examples:

```text
low risk:
  open app
  summarize visible screen
  scroll
  search settings

medium risk:
  draft message
  modify calendar event
  change device setting
  download file

high risk:
  send message
  make call
  purchase item
  transfer money
  delete data
  share private content externally
```

High-risk actions require explicit confirmation.

The engine should support:

- Per-task grants.
- Time-limited grants.
- App-specific grants.
- Data-scope grants.
- Always-deny rules.
- Enterprise or parental policy in the future.

Decision outputs:

```json
{
  "decision": "allow",
  "capability": "input.perform",
  "risk": "low",
  "reason": "Task has an active input.perform grant",
  "expires_at": "task_end",
  "confirmation_required": false
}
```

Valid decisions:

```text
allow
confirm
deny
unsupported
```

The engine must be fail-closed. Missing policy, invalid task IDs, unknown
capabilities, stale confirmations, and ambiguous targets must deny or require
confirmation, not silently allow.

Confirmation prompts must be concrete. A prompt should say what will happen,
where it will happen, and what data will leave the device if any. For example:

```text
Share selected text with Gmail
App: Chrome
Content: redacted preview
Destination: Android share sheet
```

### 8.6 Audit Log

OpenPhone must provide a user-visible audit log.

The audit log should record:

- What the assistant accessed.
- What action it proposed.
- What action it performed.
- Whether the action was user-confirmed.
- Which app or OS service was affected.
- Timestamp and task ID.

Audit logs should avoid storing sensitive content by default. For example, record that a message was sent, but avoid storing the message body unless the user explicitly enables detailed logs.

Audit storage requirements:

- Append-only logical event model.
- Bounded local retention by default.
- User-visible export/delete controls.
- Tamper-evident release-build option.
- Separate debug verbosity from user-facing audit records.
- No raw screenshots, clipboard contents, message bodies, or file contents by
  default.

Event classes:

```text
task_created
context_read
policy_decision
action_pending_confirmation
action_confirmed
action_denied
action_executed
grant_revoked
background_task_started
background_task_stopped
model_call_started
model_call_completed
```

### 8.7 Model Runtime Adapter

OpenPhone should not hard-code one model provider.

The model runtime adapter should support:

- Local models.
- Cloud models.
- Hybrid routing.
- User/provider configuration.
- Privacy mode.
- Task-specific model selection.

The agent should be able to run lightweight local classification and policy checks even when cloud inference is unavailable.

Provider requirements:

- Provider configuration must be user-visible.
- Network model calls must be represented in audit metadata.
- Sensitive task categories must support a local-only mode.
- Prompt and context payloads should be assembled through a redaction layer.
- Model adapters should be replaceable without changing OS action APIs.

### 8.8 Settings Integration

OpenPhone needs a Settings surface for durable control, not just an assistant
screen.

Settings should expose:

- Master enable/disable switch.
- Per-capability defaults.
- Per-app grants and denials.
- Background task permissions.
- Model provider configuration.
- Privacy/local-only mode.
- Audit log browser.
- Export/delete audit data.
- Developer diagnostics for framework service status.

Likely location:

```text
packages/apps/Settings/
```

Early implementation may be a patch against Settings. If the Settings surface
becomes large or frequently changed, it should become an OpenPhone fork.

### 8.9 SystemUI Integration

OpenPhone background work must be visible outside the assistant app.

SystemUI should expose:

- Persistent indicator when a background agent task is active.
- Quick Settings tile to pause/resume OpenPhone.
- Notification or ongoing surface for active tasks.
- Confirmation affordances for time-sensitive actions.
- Clear route into Settings audit and permission controls.

Likely location:

```text
packages/SystemUI/
```

### 8.10 Update and Release Client

OpenPhone eventually needs its own updater because it cannot ship as LineageOS
or depend on Lineage release infrastructure.

The updater should support:

- Device-specific channels.
- Stable, beta, and developer tracks.
- Signed OTA packages.
- Release manifest verification.
- Rollback and failure reporting.
- Clear links to source manifests, notices, and kernel source where required.

### 8.11 App Integration SDK

OpenPhone should not rely only on visual automation forever. Mature third-party
apps should be able to expose safe semantic actions.

The SDK should allow apps to declare:

- Read-only semantic context.
- User-approved actions.
- Risk levels.
- Required confirmation copy.
- Data minimization hints.
- Deep links or intents for stable execution.

App integrations must not bypass the OS policy engine. They provide better
tools; OpenPhone still decides whether the tools may run for the current task.

## 9. Privilege Model

OpenPhone should use system privileges intentionally and narrowly.

Potential mechanisms:

- Privileged system app installed under `system_ext/priv-app` or `product/priv-app`.
- Platform signing for OpenPhone system components.
- Privileged permission allowlists.
- Binder service exposed from `system_server` or a dedicated system process.
- SELinux domains for OpenPhone services.
- Framework APIs guarded by OpenPhone-specific permissions.

OpenPhone should avoid granting broad ambient power to every component. The assistant UI, orchestrator, perception service, and action service should have separate boundaries where practical.

### 9.1 Permission Contract

OpenPhone framework APIs should be guarded by platform signature permissions:

```text
org.openphone.permission.READ_SCREEN_CONTEXT
org.openphone.permission.PERFORM_DEVICE_ACTION
org.openphone.permission.MANAGE_AGENT_TASKS
org.openphone.permission.READ_AGENT_AUDIT_LOG
org.openphone.permission.MANAGE_AGENT_POLICY
```

The initial implementation may grant all of these to the bootstrap assistant,
but the intended mature split is:

```text
OpenPhoneAssistant
  MANAGE_AGENT_TASKS
  READ_AGENT_AUDIT_LOG
  MANAGE_AGENT_POLICY

OpenPhoneOrchestrator
  MANAGE_AGENT_TASKS
  READ_SCREEN_CONTEXT

OpenPhoneActionExecutor or framework service client
  PERFORM_DEVICE_ACTION

Settings/SystemUI integrations
  READ_AGENT_AUDIT_LOG
  MANAGE_AGENT_POLICY
```

### 9.2 Binder API Direction

The framework service should expose a hidden manager API to trusted OpenPhone
components. Early versions may pass JSON strings to move quickly. The stable
direction is typed parcelables:

```text
OpenPhoneAgentTask
OpenPhoneScreenContext
OpenPhoneActionRequest
OpenPhoneActionResult
OpenPhonePolicyDecision
OpenPhoneAuditEvent
OpenPhoneGrant
```

JSON contracts under `docs/contracts/` are the schema source while the Binder
surface is stabilizing. Typed Binder APIs should match those contracts instead
of inventing a second model.

### 9.3 SELinux Model

OpenPhone must add SELinux policy before real device release.

Expected domains:

```text
openphone_assistant_app
openphone_orchestrator_app
openphone_model_runtime
openphone_update_client
```

Policy goals:

- Keep privileged app access narrower than platform app access where possible.
- Allow only the Binder calls and files each component needs.
- Store audit data under a labeled OpenPhone system data path.
- Store model/runtime data under labeled app or system data paths.
- Avoid broad `allow` rules against system files, input devices, or vendor
  nodes.
- Pass neverallow checks on supported release builds.

The framework service in `system_server` should remain the primary mediator for
input, screen context, notification action, and policy-sensitive operations.
Direct native access to input devices or display buffers should not be used for
normal actions.

### 9.4 Signing and Key Ownership

Development builds may use test keys. Release builds must use OpenPhone-owned
keys and keep platform signing material private.

Release key classes:

```text
platform
system_ext/product app keys
OTA package key
AVB keys where device/release flow requires them
```

OpenPhone must document key rotation and lost-key recovery per device before
shipping to non-developer users.

## 10. Proposed Capability Matrix

The first version should define capabilities as explicit grants.

```text
screen.read.visible
  Read structured visible UI and visible text for the active task.

screen.capture
  Capture screen image for OCR or visual reasoning.

ui.target.resolve
  Resolve a visible UI element ID to current display bounds.

input.perform
  Perform taps, typing, scrolling, and navigation.

apps.launch
  Open installed apps and launch intents.

tasks.observe
  Observe foreground app, activity, and recent task state.

notifications.read
  Read notification metadata and content.

notifications.act
  Trigger notification actions.

clipboard.read
  Read clipboard content when task-scoped.

clipboard.write
  Write clipboard content.

share.content
  Send text or selected content through Android's share sheet.

files.read.scoped
  Read user-selected files or media.

contacts.read
  Read contacts for a task.

calendar.read
  Read calendar events.

calendar.write
  Create or modify calendar events.

messages.draft
  Draft message content.

messages.send
  Send message only after explicit confirmation.

calls.place
  Place call only after explicit confirmation.

settings.read
  Read OS settings.

settings.write
  Modify OS settings with confirmation depending on risk.

background.run
  Continue an approved task while not foregrounded.

network.use
  Use network for model calls or web/API access.

account.access
  Access account-scoped data only through explicit user grant.
```

Each capability should have:

- Scope.
- Risk level.
- Default state.
- Confirmation requirement.
- Expiration behavior.
- Audit behavior.

### 10.1 MVP Capability Defaults

The first working OS image should use conservative defaults:

```text
apps.launch
  low risk
  allowed inside active task
  audited

tasks.observe
  low risk for app/activity metadata
  allowed inside active task
  audited

screen.read.visible
  medium risk until redaction is strong
  task confirmation required
  audited

screen.capture
  medium/high risk depending on app
  explicit confirmation required
  audited without storing image by default

input.perform
  low/medium risk depending on target
  explicit task grant required
  audited

clipboard.read
  medium/high risk
  confirmation required
  audited without storing clipboard value by default

clipboard.write
  medium risk
  confirmation required
  audited without storing full content by default

share.content
  high risk when content leaves the current app/device boundary
  confirmation required
  audited without storing body by default

notifications.read
  medium/high risk
  task or app-specific grant required
  audited

notifications.act
  medium/high risk
  confirmation required unless pre-approved for that app/action
  audited

settings.write
  medium/high risk
  confirmation required
  audited
```

The policy engine may lower friction only after the system can describe the
target and consequence accurately to the user.

## 11. Device Support Strategy

OpenPhone will support exact device models, not generic Android phones.

Each supported device needs:

- Unlockable bootloader.
- Working Lineage/AOSP build target.
- Device tree.
- Kernel source or compatible kernel repo.
- Vendor blobs or extraction flow.
- Flash/recovery path.
- Working core hardware checklist.
- Known issues document.
- OTA compatibility strategy.

Initial device support should focus on a small set of well-supported unlockable devices.

Suggested first class:

- Recent Google Pixel model with strong aftermarket support.

Other possible future classes:

- Fairphone devices.
- Select OnePlus devices.
- Other devices with active Lineage support and unlockable bootloaders.

### 11.1 Device Acceptance Checklist

A device is not supported until the following pass:

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

### 11.2 Device Directory Contract

Every supported device must have a document under:

```text
devices/<codename>.md
```

The device document must include:

- Marketing name and codename.
- Exact upstream Lineage target.
- Supported OpenPhone branch.
- Bootloader unlock instructions.
- Required firmware version.
- Vendor blob extraction source and commands.
- Build command.
- Flash command.
- Recovery instructions.
- Known hardware status.
- Known agent feature status.
- OTA status.
- Kernel source location.
- Blob redistribution notes.

No device should be advertised as supported until this file exists and the
acceptance checklist has been run on hardware.

### 11.3 First Device Selection Criteria

The first device should optimize for engineering certainty, not market size.

Selection criteria:

- Bootloader unlock is officially available.
- Active Lineage support exists for the Android branch OpenPhone uses.
- Kernel source is available and buildable.
- Vendor blob extraction is documented.
- Fastboot/recovery flashing is well understood.
- Hardware replacement units are easy to buy.
- IMS, camera, and fingerprint issues are known and manageable.
- The device has enough RAM and storage for local model experiments later.

The first device should be locked before building deep product UX. Otherwise
framework work may pass on a generic target while failing on real vendor
constraints.

## 12. Build and Release Flow

### 12.1 Developer Build

Expected flow:

```bash
git clone https://github.com/OpenPhoneOS/OpenPhone
cd OpenPhone
./scripts/sync.sh
./scripts/apply-patches.sh
./scripts/build.sh <device>
```

The scripts should:

- Install or validate Android build prerequisites.
- Initialize `repo`.
- Sync pinned upstream sources.
- Apply OpenPhone patch stack.
- Copy or link OpenPhone overlay modules.
- Select build target.
- Build image/OTA package.

### 12.1.1 Patch Stack Workflow

OpenPhone patches should be replayable in numeric order per upstream repo:

```text
patches/frameworks_base/0001-...
patches/frameworks_base/0002-...
patches/packages_apps_Settings/0001-...
```

Patch rules:

- Each patch should build or be part of a small buildable series.
- Commit messages should start with `OpenPhone:`.
- Patches should avoid unrelated upstream cleanup.
- Patches should include the smallest practical framework surface.
- Rebase conflicts should be resolved in the Android workspace, then exported
  back to `patches/`.

Patches may remain patches indefinitely while review stays readable. A fork is
only required when normal Git history inside that component becomes materially
better than reviewing patch files from the canonical repo. Even when forks are
introduced, this repository remains the entry point and should pin the exact
fork revisions.

### 12.1.2 Validation Commands

Every meaningful change should be validated with the narrowest relevant build,
then with broader builds before release.

Required local checks:

```bash
./scripts/check.sh
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" ./scripts/apply-patches.sh
```

Framework service changes:

```bash
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
OPENPHONE_BUILD_GOAL=services.core-android_common-checkbuild \
./scripts/build.sh openphone_arm64
```

Assistant changes:

```bash
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
OPENPHONE_BUILD_GOAL=OpenPhoneAssistant \
./scripts/build.sh openphone_arm64
```

Product graph changes:

```bash
OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android" \
OPENPHONE_BUILD_GOAL=droid \
./scripts/build.sh openphone_arm64
```

Device release changes require a device-specific build and hardware smoke test.

### 12.2 Release Build

Release builds require:

- Dedicated signing keys.
- Reproducible source manifest.
- Versioned release metadata.
- Device-specific changelog.
- OTA package.
- Recovery/flash package.
- License and notice bundle.
- Kernel source publication where required.
- Vendor blob policy for the device.
- Device acceptance checklist result.
- Known issues.
- Audit of included OpenPhone capabilities.

### 12.3 OTA

OpenPhone should eventually provide its own updater.

OTA design requirements:

- Device-specific update channels.
- Stable and developer channels.
- Signed update packages.
- Rollback handling.
- Clear failure recovery path.
- Auditability of the source manifest used for each build.

## 13. Security and Safety Principles

OpenPhone grants the agent deep OS capabilities, so safety cannot be an afterthought.

Principles:

- User intent must be explicit.
- Sensitive actions require confirmation.
- Capabilities are scoped and revocable.
- Background work must be visible and controllable.
- Audit logs are user-accessible.
- Privileges are split across components where practical.
- Policy is enforced below the UI layer.
- The agent should fail closed when policy is uncertain.
- Local-only/private mode should be supported for sensitive contexts.

The objective is not to make a powerless assistant. The objective is to make a powerful assistant that is inspectable and governable.

### 13.1 Hard Safety Rules

The following rules are product requirements, not suggestions:

- No sensitive action may execute only because a model requested it.
- No payment, purchase, transfer, message send, call placement, account change,
  destructive file action, or external data share may complete without an
  explicit user confirmation describing the target and consequence.
- Background tasks must have a visible SystemUI surface and a stop control.
- Capabilities must expire at task end unless the user explicitly chooses a
  longer-lived grant.
- Unknown capabilities deny by default.
- Unknown target apps or stale UI element references require revalidation.
- Secure windows and password-like fields must be redacted by default.
- Clipboard reads and screenshots require clear task scope and audit records.
- Debug logging must not silently capture sensitive content in release builds.

### 13.2 Confirmation UX Requirements

A confirmation prompt must include:

- Action verb.
- Target app or system service.
- Destination or recipient when known.
- Data class being used or shared.
- Whether content leaves the device.
- The task that requested it.
- Approve and deny actions.

For high-risk actions, confirmation should be high-friction enough to prevent
accidental approval. Examples include requiring the user to review the generated
message before sending, requiring biometric/PIN for payments, or routing to the
app's own final confirmation screen.

### 13.3 Privacy Modes

OpenPhone should support:

```text
normal mode:
  local and configured cloud model providers allowed

local-only mode:
  no cloud model calls for active task

private app mode:
  selected apps hidden from screen context and OCR unless explicitly granted

audit-minimal mode:
  metadata-only audit records, no content previews
```

Privacy mode choices must be visible in Settings and should be reflected in
task/audit metadata.

## 14. Development Phases

### Phase 0: Project Foundation

Deliverables:

- Canonical OpenPhone repository.
- `SPEC.md`.
- License decision for OpenPhone-owned code.
- Initial docs.
- Device selection.
- Upstream branch selection.

Current status:

- Canonical repository exists.
- Initial spec/docs/scripts/overlays exist.
- Upstream branch is currently Lineage `lineage-23.2`.
- Device selection remains open.

### Phase 1: Reproducible Base Build

Deliverables:

- `repo` manifest pinned to Lineage upstream.
- Build scripts.
- One target device.
- Clean Lineage-derived build.
- Flash instructions.
- Device acceptance checklist started.

Success criteria:

- A developer can build and flash the base OS using OpenPhone scripts.

Current status:

- Generic `openphone_arm64` product graph has been validated.
- Device-flashable output is not validated yet.

### Phase 2: OpenPhone Product Layer

Deliverables:

- `vendor/openphone`.
- OpenPhone product config.
- Branding.
- Build properties.
- OpenPhone Settings entry.
- Placeholder privileged assistant app.

Success criteria:

- The flashed OS identifies as OpenPhone, not LineageOS.
- OpenPhone-owned code builds as part of the OS.

Current status:

- `vendor/openphone` product layer exists.
- Privileged assistant package builds.
- Branding and Settings identity work remain incomplete.

### Phase 3: Minimal Agent Privilege Path

Deliverables:

- Privileged assistant app.
- Privileged permission allowlist.
- Basic screen context service.
- Basic action execution path.
- Initial policy checks.
- Initial audit log.

Success criteria:

- User can ask the assistant to perform simple device actions.
- The action passes through policy and appears in audit log.

Current status:

- Hidden framework manager API exists.
- `OpenPhoneAgentManagerService` starts from `system_server`.
- Task creation, foreground/visible activity context, seed policy, durable
  audit, pending confirmation, launcher actions, navigation keys, pointer
  gestures, text input, clipboard actions, and confirmed share chooser launch
  are implemented in the current patch stack.
- Full visible text/UI hierarchy, screenshots/OCR, notification actions, and
  rich consent UX remain incomplete.

### Phase 4: Framework Integration

Deliverables:

- Framework hooks where needed.
- Binder APIs for perception/action.
- SystemUI/Settings integration.
- SELinux policy.
- Structured capability model.

Success criteria:

- Agent can operate across apps more reliably than a normal Accessibility app.
- Sensitive actions require confirmation.
- Background tasks are visible and controllable.

Current status:

- Framework service foundation exists.
- Settings, SystemUI, SELinux, and typed parcelables remain incomplete.

### Phase 5: Device and Release Hardening

Deliverables:

- OTA update path.
- Release signing.
- License/notice generation.
- Kernel source publication flow.
- Device support matrix.
- Automated smoke tests.

Success criteria:

- OpenPhone can ship a tested build to early users on one supported device.

Current status:

- Not started beyond generic build validation.

### Phase 6: Expansion

Deliverables:

- Additional devices.
- Better local model support.
- App-specific integrations.
- Developer SDK for app/action integrations.
- Commercial licensing package.

Success criteria:

- OpenPhone has a repeatable process for adding devices and capabilities.

Current status:

- Not started.

## 15. MVP Acceptance Criteria

The first credible OpenPhone MVP is not a generic emulator image. It is a
device build that proves the OS-level AI premise.

MVP must demonstrate on one physical supported device:

- OpenPhone-branded booting ROM.
- Reproducible source sync and build from the canonical repository.
- Documented unlock, build, flash, and recovery flow.
- Privileged assistant installed as part of the OS image.
- Framework manager service available from `system_server`.
- Task creation with task-scoped grants.
- Foreground app/activity context.
- At least one structured UI context source beyond package/activity metadata.
- OS-mediated app launch and input actions.
- Clipboard and share actions behind confirmation.
- Audit log visible to the user.
- Basic Settings surface for enable/disable and audit access.
- Background task indicator or notification.
- SELinux policy passing for the OpenPhone components used in the build.
- License/notice bundle.
- Device support checklist recorded.

Anything less can be a valuable development checkpoint, but it should not be
called the OpenPhone MVP.

## 16. Open Questions

The project still needs decisions on:

- First target device.
- Exact non-commercial source-available license.
- Whether OpenPhone cloud services are required for MVP.
- Local model minimum requirements.
- Whether commercial builds use the same source license or a private addendum.
- Whether to support MicroG or remain fully Google-free initially.
- Whether device builds should start from official Lineage support only.
- Whether typed parcelables should land before or after first physical boot.
- Whether the orchestrator remains inside the assistant package for MVP.

## 17. Initial Recommendation

Start with the simplest architecture that proves the OS-level premise:

1. Use LineageOS as upstream.
2. Keep one canonical OpenPhone repo containing manifests, scripts, overlays, docs, and patches.
3. Target one well-supported unlockable Pixel.
4. Build a reproducible OpenPhone-flavored ROM before adding deep AI hooks.
5. Add the assistant as a privileged OpenPhone component.
6. Add policy and audit before expanding agent power.
7. Promote patches to forks only when the patch stack becomes painful.

This gives OpenPhone a credible path from concept to booting OS without pretending that universal Android support or app-only automation is enough.
