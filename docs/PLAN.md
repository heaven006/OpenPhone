# OpenPhone Plan

This is the canonical working plan for OpenPhone. Older task-specific plans
have been folded into this document, `docs/ROADMAP.md`,
`docs/IMPLEMENTATION_STATUS.md`, `docs/BUILD.md`, and the device notes.

This file should answer three questions for a new contributor:

- What are we building and why?
- What is already proven on real hardware?
- What should be implemented next?

## Goal

Build the first usable AI-first Android phone OS:

```text
user trigger -> active task -> observe screen -> reason -> request action ->
policy check -> visible action -> audit -> repeat
```

OpenPhone is not an app-only assistant. The agent runs through OS-mediated
capabilities exposed by a privileged OpenPhone framework service and a
privileged assistant app.

## Current Baseline

Already proven:

- Pixel 9a `tegu` can boot official LineageOS and the OpenPhone product.
- The verified first device is Google Pixel 9a, codename `tegu`, SKU `GTF7P`,
  on LineageOS `lineage-23.2` / Android 16 `bp4a`.
- `openphone_tegu` includes the privileged `org.openphone.assistant` app.
- `OpenPhoneAgentManagerService` registers as the `openphone_agent` Binder
  service in `system_server`.
- The framework service supports task creation, screen context, opt-in
  screenshot payloads, policy decisions, action execution, pending
  confirmations, pointer events, and durable audit logging.
- The assistant can start tasks, show a user-facing control surface, display an
  active-task indicator, show a cursor overlay, capture voice for a short
  command, transcribe through the development OpenAI path, and run basic
  proof-of-loop actions.
- The first Pixel 9a boot-chain issue was diagnosed: generated
  `vendor_kernel_boot.img` had an empty DTB. The current known-good workaround
  extracts `tegu.dtb` from the upstream prebuilt before target-files/OTA
  generation.
- The first full-product crash was diagnosed as missing SELinux labeling for
  the `openphone_agent` service and fixed in the sepolicy patch stack.
- A development OpenAI vision/tool loop has been physically validated on the
  Pixel 9a for basic observe/action/finish behavior.

Still missing:

- A real agent loop that plans and executes multi-step phone tasks from the
  current screen.
- OCR-style screen understanding and production framework-owned UI hierarchy.
  A first assistant-side accessibility UI tree path now exists for development.
- Production model transport, key handling, retries, rate limits, and privacy
  controls.
- SystemUI-owned active agent surface. First Settings-owned OpenPhone,
  task-grant, and audit surfaces exist; a full durable grant editor remains.
- Reproducible release packaging, changelog discipline, and GitHub automation.

Supporting context:

- Detailed boot-chain history lives in `docs/BRINGUP_LOG.md` and
  `docs/TEGU_BOOTCHAIN.md`.
- Build and flash commands live in `docs/BUILD.md` and `devices/tegu.md`.
- Implementation evidence lives in `docs/IMPLEMENTATION_STATUS.md`.
- Repeatable eval tasks live in `docs/TESTING.md`.

## Architecture Direction

Keep the phone runtime native to Android:

```text
OpenPhoneAssistant
  - user trigger
  - task UI
  - voice/text input
  - model adapters
  - confirmation UI
  - audit viewer

OpenPhoneAgentManager
  - hidden framework API
  - trusted client boundary

OpenPhoneAgentManagerService
  - active task registry
  - screen observer
  - policy engine
  - action executor
  - pointer event publisher
  - audit log

Android system services
  - ActivityTaskManager
  - WindowManager/InputManager
  - ClipboardManager
  - PackageManager
  - SystemUI and Settings later
```

The model never controls Android directly. It emits structured tool requests.
The framework service validates and executes them.

## CUA-Informed Agent Improvements

CUA is useful as a reference for the agent contract, trajectory logging, and
testing mindset. It should not become the OpenPhone runtime because its Android
path is emulator/ADB oriented. OpenPhone should implement the same class of
capabilities inside the phone through privileged OS services.

Borrow from CUA:

- Observation/action loop: screenshot -> model -> action -> screenshot.
- Small action vocabulary: `tap`, `long_press`, `swipe`, `type_text`, `back`,
  `home`, `wait`, `screenshot`, `finish_task`, `fail_task`.
- Trajectory records containing screenshots, model messages, tool calls, action
  results, timing, and policy decisions.
- Repeatable eval tasks with pass/fail evidence.
- Host-side adapters for testing the phone during development.

Do not borrow:

- ADB as the production execution path.
- Emulator assumptions as the product architecture.
- Python/liteLLM as an on-device runtime dependency.
- Default telemetry behavior.
- Optional heavy or license-sensitive dependencies unless explicitly reviewed.

### Active Screen Observation

The agent should see the screen while it is actively doing a user-approved
task. It should not stream the screen while idle or while merely listening.

Default observation policy:

```text
Idle:
  no screen capture

Listening without active task:
  no screen capture

Task active:
  capture at task start
  capture after each action
  capture after app/window changes
  capture when the model asks for current state
  optionally sample at low rate while waiting

High-attention burst:
  short 2-5 fps sampling for loading screens, animations, maps, scrolling
  lists, camera flows, and other rapidly changing UI
```

Default guardrails:

- active task required,
- visible indicator required,
- default watch cadence around 1 fps,
- short burst cadence capped around 5 fps,
- every capture audited,
- sensitive screens blocked or redacted where possible.

### Agent v1 Scope

The first real agent should handle a bounded task set well:

- Start from user speech or text.
- Capture the current screen as a screenshot payload.
- Ask a model to return one structured next action.
- Execute the action through `OpenPhoneAgentManagerService`.
- Capture the next screen.
- Repeat until the task finishes, fails, or asks for confirmation.
- Show a visible cursor/status indication throughout the task.
- Write a trajectory for debugging.

Initial demo tasks:

- Open Settings and toggle a simple setting.
- Open an installed app from the launcher.
- Search in the browser.
- Type into a text field and stop before sending/posting.
- Navigate the Play Store or app marketplace flow up to the confirmation point,
  without silently installing or purchasing.

### Agent Contracts

Initial model tools:

```text
get_screen()
watch_screen(fps, duration_ms, reason)
open_app(package_or_label, reason)
tap(x, y, reason)
long_press(x, y, duration_ms, reason)
swipe(start_x, start_y, end_x, end_y, duration_ms, reason)
type_text(text, reason)
press_key(key, reason)
set_clipboard(text, reason)
paste(reason)
share_text(text, chooser_title, reason)
wait(duration_ms, reason)
ask_user_confirmation(summary, risk, action_json)
finish_task(summary)
fail_task(reason)
```

Every tool request must include:

- active task ID,
- model-visible reason,
- capability mapping,
- policy decision,
- audit result.

Risky tools require confirmation. Unknown tools fail closed.

### Model Provider Order

Keep the OS service model-agnostic. The assistant owns provider adapters; the
framework owns capabilities.

Implementation order:

1. Text prompt -> model -> one structured tool call -> action.
2. Screenshot/vision -> model -> one structured tool call -> action.
3. Bounded multi-step loop using the same tool interface.
4. Realtime voice using the same tool interface.

Provider direction:

- OpenAI text/vision path first because it is easiest to debug.
- OpenAI Realtime for the more impressive voice-first UX once the tool loop is
  reliable.
- Claude/Gemini adapters later for model comparison.
- Local model adapter later for privacy/offline/OEM differentiation.

### Action Execution Validation

The framework already has early support for:

- app launch,
- Back, Home, Recents,
- tap,
- long press,
- scroll/swipe,
- text input,
- clipboard write,
- clipboard paste,
- confirmed share action.

Physical Pixel 9a validation still needs to verify:

- coordinate system,
- tap timing,
- swipe duration,
- keyboard/text entry behavior,
- behavior across Settings, browser, launcher, and common text fields,
- audit events for success and failure cases.

### Visible Cursor and Agent Presence

OpenPhone should show a cursor whenever the agent controls the phone. Do not
depend on Android's physical mouse cursor for V1.

V1 behavior:

- show active-agent chip or dynamic-island-style status,
- show cursor dot,
- animate cursor movement before taps,
- show tap ripple,
- show swipe trail,
- show typing indicator,
- expose stop control through the app, notification, or SystemUI.

Implementation path:

- V1: assistant-owned overlay using privileged overlay permissions and
  framework pointer events.
- V1.5: move the active-agent surface into SystemUI.

### User Triggers

V1 triggers:

- assistant launcher icon,
- persistent notification,
- Quick Settings tile,
- voice button inside the assistant.

Later triggers:

- long-press power button,
- lockscreen affordance,
- hardware button gesture,
- wake word,
- notification-based suggestions.

### Policy, Confirmation, Audit, and Privacy

Actions should fail closed unless:

- task is active,
- assistant is trusted,
- capability is granted,
- policy allows the action or the user confirmed it,
- display is controllable,
- target screen is not blocked.

Confirmation is required for:

- sending or posting content,
- purchases/payments,
- deleting data,
- sharing files/private text,
- calling or messaging people,
- changing account/security settings,
- installing/uninstalling apps,
- granting permissions.

Every task should record:

- user request,
- start/end timestamps,
- apps observed,
- screen captures or capture references,
- model tool calls,
- policy decisions,
- user confirmations,
- actions executed,
- failures and retries.

Default privacy stance:

- no screen capture outside active tasks,
- clear visible indicator while observing,
- immediate stop control,
- user-visible audit log,
- sensitive screen block/redaction hooks,
- explicit model provider disclosure,
- cloud model use disclosed,
- future controls for blocked apps, always-confirm apps, audit deletion, and
  audit export.

## V1 Done Criteria

V1 is achieved when a Pixel 9a can be flashed with an OpenPhone OTA and pass
this demo:

1. Boot into OpenPhone without manual partition repair.
2. Show OpenPhone identity in system properties and Settings/About.
3. Start the OpenPhone assistant as a privileged system component.
4. Let the user start a visible assistant task.
5. Read foreground app/screen context through the framework service.
6. Capture a task-scoped screenshot payload.
7. Ask a model for a structured next action.
8. Perform low-risk actions across apps through OS mediation.
9. Require explicit task grants for input actions.
10. Require confirmation for medium/high-risk actions.
11. Persist an audit log showing task, context, policy, action, confirmation,
    and result events.
12. Survive reboot with the assistant and framework service available.

V1 should feel like this:

```text
The phone is running OpenPhone.
The assistant is part of the OS.
The assistant can see what app/screen is active.
The assistant can act across apps after user-approved grants.
The user can see and inspect what happened.
```

## Public Project and Release Plan

OpenPhone should now become a proper public GitHub project while remaining
honest about maturity. The first public version is `0.0.1`.

### Version 0.0.1 Definition

`0.0.1` is a developer preview, not a consumer ROM.

It should include:

- Public repository structure and documentation.
- Clear source-available non-commercial licensing.
- Build and flash instructions for Pixel 9a.
- Documented known-good artifact hashes when releases are published.
- Current OpenPhone assistant and framework service source.
- CI checks for repository hygiene.
- Changelog and release notes.
- Device support matrix showing Pixel 9a as the first development target.

It does not need:

- Production signing.
- OTA updater.
- Play Integrity compatibility.
- Broad device support.
- Fully autonomous agent tasks.

### Release Artifacts

For each tagged release:

- GitHub release notes.
- Source archive from GitHub.
- Generated OTA artifact when available.
- SHA-256 checksums.
- Device-specific flashing notes.
- Known issues.
- Upgrade/wipe guidance.

### CI/CD

Start with lightweight CI that can run on GitHub-hosted runners:

- shell syntax checks,
- JSON validation,
- XML validation when `xmllint` is available,
- required-file checks,
- markdown/documentation sanity checks where practical.

Do not attempt full Android image builds in GitHub Actions yet. Full device
builds need a prepared Linux Android build host with substantial disk, RAM, and
cache.

Later CI/CD:

- self-hosted Linux builder for `openphone_tegu`,
- signed development artifacts,
- checksum generation. Status: local release manifest/checksum generator
  implemented for release artifact directories,
- release artifact validation. Status: local staged-artifact validation checks
  SHA-256 entries, OTA ZIP integrity, and obvious secret/key mistakes before
  publication,
- release draft creation. Status: local GitHub CLI draft command generation is
  implemented for staged release artifacts and release notes,
- flash smoke-test checklist,
- optional device-farm/manual validation report upload.

## Task Backlog

### Track A: CUA-Informed Agent v1

Status: in progress.

- Define the final phone-side model tool schema. Status: first version
  implemented; `get_screen` and bounded `watch_screen` are exposed through the
  assistant tool executor.
- Add trajectory logging for screenshots, model messages, tool calls, action
  results, policy decisions, and failures. Status: first assistant-side
  recorder implemented; assistant can export the latest trajectory zip to
  `Downloads/OpenPhone`.
- Implement one-step vision action selection using the existing screenshot
  payload path. Status: development OpenAI vision path implemented; needs
  physical eval evidence.
- Add a bounded multi-step agent loop with max steps, max duration, and stop
  control. Status: max steps, duration, task stop, stale-result suppression,
  and in-flight model cancellation are implemented in the assistant/module
  build.
- Add action retry/failure handling. Status: first pass implemented for the
  development OpenAI loop; screen capture gets one retry and repeated tool
  failures stop the run with `action_failed` evidence.
- Add UI hierarchy/OCR extraction path. Status: first assistant-side
  accessibility UI tree path implemented; framework-owned extraction and OCR
  remain.
- Improve cursor overlay so every action is visible and understandable. Status:
  assistant-owned overlay now shows tap ripples, long-press emphasis, swipe
  trails, typing indication, and action labels for model/developer actions.
- Add safer confirmation UX for app installs, messaging, sharing, payments,
  settings/security changes, and destructive actions. Status: first
  assistant-owned approval surface implemented; framework pending actions and
  model confirmation requests now surface risk, capability, and action summary
  outside the raw developer JSON view. The development OpenAI loop also has a
  deterministic pre-action guardrail that converts install, payment,
  destructive, messaging/sharing, and login/password screen actions into
  confirmation requests before execution.
- Require explicit task grants for input and data-moving actions. Status:
  assistant-owned task grant controls now split input/navigation, task-scoped
  screenshots, clipboard, share sheet, and web-link capabilities; model tools
  that exceed the selected grants are stopped before framework execution.
- Disclose provider/model/cloud behavior in the task UI and debug evidence.
  Status: assistant model adapters expose provider, model, cloud/local mode,
  and disclosure text; the active task surface and trajectory start event now
  include that metadata.
- Add a supported way to export framework audit evidence without ADB root.
  Status: assistant-owned export implemented; it writes a redacted framework
  audit evidence JSON file to `Downloads/OpenPhone`.
- Add eval tasks and record expected outcomes in `docs/TESTING.md`. Status:
  initial eval suite documented; local Eval 2 opens Settings on Pixel 9a.

### Track B: OS Product and Device v1

Status: in progress.

- Automate the Pixel 9a DTB extraction or replace it with a cleaner build-time
  source of truth. Status: first `scripts/build.sh` automation implemented.
- Add a build-time assertion that generated `vendor_kernel_boot.img` contains a
  non-empty DTB with the expected hash for the current Pixel 9a prebuilts.
  Status: first post-build verifier implemented for `openphone_tegu` and
  `openphone_tegu_smoke`.
- Keep `openphone_tegu_smoke` as the minimal bootability target and
  `openphone_tegu` as the full assistant/framework target.
- Verify OpenPhone identity in normal UI, not only system properties. Status:
  Settings/About source integration and a flashable OTA are built; physical UI
  validation is pending.
- Add Settings/About OpenPhone version and support links. Status: implemented
  as a `packages/apps/Settings` patch; About phone now includes an OpenPhone
  version row backed by `ro.openphone.version` and an OpenPhone support link.
- Add Settings-owned OpenPhone audit and task-grant surfaces. Status: first
  Settings-owned surfaces implemented and built; Settings now has a top-level
  OpenPhone page, a task-grant explainer page, and an audit page that reads
  framework service status/recent audit events when available. A full editable
  per-app/per-capability grant manager remains pending.
- Move active agent chip/stop affordance into SystemUI after the assistant-owned
  overlay is stable.
- Run and document hardware smoke tests for Wi-Fi, Bluetooth, cellular,
  calls/SMS when available, camera, microphone/speaker, fingerprint, GPS,
  screen lock/unlock, USB/ADB, reboot, factory reset, battery, and thermal
  behavior. Status: repeatable Pixel 9a smoke-test script and evidence-report
  workflow implemented; physical results are pending ADB shell recovery.
- Document rollback and recovery from boot loops, recovery sideload failures,
  SPL downgrade errors, and `init_user0_failed` data issues.

### Track C: Public Project v0.0.1

Status: in progress.

- Make `docs/PLAN.md` the canonical plan. Status: done.
- Keep `docs/ROADMAP.md` short and public-facing. Status: done.
- Add `CHANGELOG.md`. Status: done.
- Add release process documentation. Status: done.
- Add GitHub Actions CI for `scripts/check.sh`. Status: done.
- Add GitHub issue templates. Status: done.
- Add pull request template. Status: done.
- Add security policy and vulnerability reporting guidance. Status: done.
- Define `v0.0.1` release checklist. Status: done.
- Tag and publish `v0.0.1` only after the repo passes CI and the current Pixel
  9a state is accurately documented.

## Plan Consolidation Notes

`PLAN_MAKE_ASSISTANT_WORK.md` and `docs/V1_AI_PHONE_PLAN.md` were removed
because they overlapped and were starting to drift. Their durable content now
lives here or in focused docs:

- product goal, OS-level control boundary, active-task screen observation,
  model/tool contracts, visible cursor, triggers, policy, audit, and privacy:
  this file;
- build, flash, recovery, and Linux build-host workflow: `docs/BUILD.md` and
  `devices/tegu.md`;
- Pixel 9a boot-chain diagnosis, DTB handling, SPL/data-wipe issues, and
  recovery notes: `docs/TEGU_BOOTCHAIN.md` and `docs/BRINGUP_LOG.md`;
- implementation evidence and current pass/fail status:
  `docs/IMPLEMENTATION_STATUS.md`;
- repeatable agent and device evals: `docs/TESTING.md`;
- public release process, changelog, and release notes:
  `docs/RELEASE_PROCESS.md`, `CHANGELOG.md`, and `docs/releases/`.

## Immediate Next Steps

1. Recover the Pixel 9a from the current post-wipe ADB state where
   `adb devices` reports `device` but `adb shell` closes immediately. This is
   usually first-run onboarding or USB authorization; complete onboarding,
   re-enable USB debugging, and accept the host prompt.
2. Run `./scripts/verify-tegu-device.sh`. The script now fails if
   PackageManager reports an assistant version different from the repo
   manifest, which catches stale package metadata after development OTAs.
3. If PackageManager still reports the old assistant package after onboarding,
   perform a recovery `Format data / factory reset` and verify again before
   continuing evals.
4. Launch the assistant, enable/verify the accessibility UI-tree path, and
   confirm `openphone_agent` is still registered after reboot.
5. Run Eval 1 and Eval 2 from `docs/TESTING.md` on physical hardware.
6. Inspect trajectory files and framework audit events for screenshots, UI-tree
   context, model tool calls, policy decisions, and action results.
7. Fix action targeting, model prompt, retry handling, or policy issues found by
   the evals.
