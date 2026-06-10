# Implementation Status

This document tracks current implementation evidence against `SPEC.md`.

## Current Snapshot

As of the current repository manifest, the assistant package is
`versionCode=111`, `versionName=0.1.75-dev`.

**Tree state (2026-06-11):** the working tree compiles and
`./scripts/check.sh` is fully green, including the Java compile gate added
in Phase 0 (54 assistant files against android-35). Phases 0, A, and B of
the spine-first plan are code-complete and device-validated, and eight
Phase C connector slices (calendar-from-message, model-backed semantic
watcher evaluation, browser/page context deepening, model-backed AI Sheet
screen answers, message-reply watchers, the AI Island 8-state machine
with YOLO visual state, calendar depth —
update/delete/check-availability — and phone depth — missed-call
follow-ups + call-back watchers) passed reviewed device smokes, plus the
first Phase 10 hardening slice (OTA-safe store migrations). See
"Architecture Audit and Revised Direction" below for the full record.

The last fully validated slice is OTA-safe store migrations
(`.worktree/artifacts/tegu/OpenPhoneAssistant-store-migrations-v1.apk`,
`sha256=836386006f674184142de377861cc3582ebda4a0755449e84c6e4c46cd74baad`,
v111 0.1.75-dev, installed), with the device reset to
`openphone_autonomy_mode=reviewed`.

Current physically validated Pixel 9a baseline:

- `openphone_tegu` boots on the purchased Pixel 9a and exposes the
  `openphone_agent` framework Binder service.
- The privileged assistant APK can be rebuilt on the Linux Android build host,
  pushed into `/system_ext/priv-app/OpenPhoneAssistant/` with
  `scripts/push-assistant-apk.sh`, and validated without a full OTA loop for
  assistant-only changes.
- The latest assistant UI pass is installed on the connected Pixel 9a as
  `.worktree/artifacts/tegu/OpenPhoneAssistant-ai-sheet-screen-answer-v1.apk`
  (`sha256=26fd4733ec20a422843e6dd472ee0365cf9a6f4a1979f4ed437d6a3cb1ee30f0`).
  Visual smoke screenshots for compact tap, expanded swipe, and in-place
  screen answer states are under `.worktree/artifacts/tegu/screens/`.
- The latest assistant substrate pass is installed on the connected Pixel 9a as
  `.worktree/artifacts/tegu/OpenPhoneAssistant-notification-index-v1b.apk`
  (`sha256=985d5cb68eed7fc275b05c56c4d21da0ee2ed5fde7e2bcb4a3fe522acfac660b`).
- The latest assistant connector pass is installed on the connected Pixel 9a as
  `.worktree/artifacts/tegu/OpenPhoneAssistant-phone-context-v1.apk`
  (`sha256=5bef9ef275d8a0433f53f0aa292e6bc0ba3a003d6cca33ef4eeb032ff99f0359`).
- The latest assistant browser connector pass is installed on the connected
  Pixel 9a as
  `.worktree/artifacts/tegu/OpenPhoneAssistant-browser-fetch-v1.apk`
  (`sha256=66e08a1e19876c9982d8c02b031bf64e75591d9f90be99965b2ef4d8d40fcad2`).
- The assistant main screen is now a Jetpack Compose / Material 3 chat-style
  surface with:
  - one text composer;
  - a stateful icon action button: mic when empty, send when text is present,
    stop while listening or running;
  - a profile icon for the advanced/model/settings surface;
  - keyboard-aware bottom insets so the composer stays above the keyboard;
  - outside-tap keyboard dismissal;
  - the service/dynamic island hidden while the app itself is foregrounded to
    avoid competing assistant surfaces.
- On-device screenshots were captured for home, keyboard-open/send-state,
  Advanced, and Back-to-chat states under
  `.worktree/reports/compose-smoke/`; no assistant `FATAL EXCEPTION`,
  `AndroidRuntime` fatal, or ANR signatures appeared in logcat/process dumps
  after the UI exercise and agent evals.
- User-supplied MindTheGapps sideload after the OpenPhone OTA has been
  documented and validated as a developer-device path. OpenPhone still does not
  redistribute Google packages.

## Implemented in This Repository

- Canonical OpenPhone project structure.
- Dual-license source-available licensing boundary for OpenPhone-owned
  materials: PolyForm Noncommercial for non-commercial use and separate written
  license for commercial use.
- LineageOS upstream sync script.
- OpenPhone local manifest hook.
- Patch-stack directory layout and patch application script.
- Generic `openphone_arm64` product definition.
- `vendor/openphone` common product layer.
- Initial capability and policy JSON, with repo validation that the assistant
  fallback `PolicyEngine` covers every registered capability at the same risk
  class.
- Initial model-tool registry, with repo validation that registered tools map
  to known capabilities and are covered by the framework tool executor and
  OpenAI adapter. The tool executor now rejects reason-required model tools
  that omit a non-empty model-visible reason.
- Privileged permission allowlist seed file.
- Persistent privileged `OpenPhoneAssistant` app with a user-facing chat
  surface, task entry, voice/text start path, stop control, model settings,
  input-grant controls, OTA-preview controls, trace/audit export, and advanced
  developer diagnostics.
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
  long press, scroll, text input, web-link launch, clipboard write, clipboard
  paste, and confirmed share chooser launch through mediated OS APIs.
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
- Assistant-owned cursor overlay now renders action-specific feedback: tap
  ripples, long-press emphasis, swipe trails, typing indication, and transient
  action labels while the agent is controlling the phone.
- AI Sheet v1 is now an OS-native assistant overlay attached to the AI Island:
  tapping the left idle island half opens a compact sheet, swiping down opens
  an expanded sheet, and the right idle island half remains the voice entry.
  The sheet provides Talk, Stop, Chat, Screen, Summarize, Search,
  Notifications, and Settings actions. Screen/Summarize now run a transient
  screen-read task from the persistent assistant service and render the answer
  in the sheet itself instead of launching the chat app; the first local answer
  card uses accessibility-visible text and foreground context, with OpenPhone
  overlay controls filtered from the summary.
- JSON contracts for tasks, screen context, action requests, and audit events.
- Framework integration contract and first nine `frameworks/base` patch sets.
- Framework `getScreen(...)`, `watchScreen(...)`, `stopTask(...)`, and
  `getPointerEvents(...)` manager APIs with active-task enforcement.
- Active screen observation. The framework now gates screen reads on active
  tasks, returns current foreground/visible activity context, and can return an
  opt-in downscaled JPEG screenshot payload as base64 from `getScreen(...)`.
- Pointer-event publishing for tap, long press, swipe, and typing actions.
- Assistant task console controls for starting/stopping a task, refreshing
  screen state, running a proof-of-loop agent, navigating Back, requesting a
  confirmable action, approving/denying pending actions, and refreshing audit.
- Assistant-owned visible cursor/status overlay for the current task.
- Persistent assistant notification with start/stop actions.
- Quick Settings tile for opening/starting the assistant.
- Model adapter boundary with a local heuristic development adapter and a
  development OpenAI Responses vision adapter.
- Assistant-side trajectory logging for agent runs. Each run records task
  start, tool calls, tool results, final agent result, and stores screenshot
  payloads as files when returned by the framework. Task start events include
  the selected provider, model, cloud/local mode, and disclosure text.
  `events.jsonl` records now include the `openphone.trajectory_event.v1`
  schema marker, and `docs/contracts/trajectory-event.schema.json` defines the
  first trajectory event contract.
- Assistant trajectory export to `Downloads/OpenPhone` as a zip file, so
  physical eval evidence can be retrieved without ADB root or a debuggable
  assistant package. `scripts/validate-trajectory-export.sh` validates exported
  trajectory directories or zips before they are used as eval/release evidence.
- Assistant audit evidence export to `Downloads/OpenPhone` is now backed by
  `docs/contracts/audit-evidence.schema.json` and
  `scripts/validate-audit-evidence-export.sh`.
- Assistant-side accessibility screen tree capture. The privileged assistant
  now declares an OpenPhone accessibility service, auto-enables it with
  `WRITE_SECURE_SETTINGS`, records visible text and interactive elements, and
  merges that UI tree into model `get_screen` results when requested. The first
  privacy guardrail now flags password/payment/account-like UI, redacts
  password-field labels from the UI tree, and blocks screenshot tool results on
  flagged screens while still returning redacted UI-tree context. The screen
  context schema now covers the assistant UI-tree snapshot fields for source,
  timestamp, windows, element state, view/window IDs, sensitivity, and risk
  hints.
- Framework tool executor now covers the initial plan vocabulary including
  `get_screen`, bounded `watch_screen`, `wait`, and explicit
  `ask_user_confirmation` handoff results. The first machine-readable
  `openphone_model_tools.json` registry now maps those model-visible tools to
  product capabilities, and reason-required tools are enforced at execution
  time.
- Framework tool execution now supports semantic UI targets through
  `tap_element` and `long_press_element`. The model can select an
  accessibility `interactive_elements[].id`, the assistant resolves that ID
  against the current UI-tree snapshot, validates that the element is enabled
  and bounded, and dispatches the action through the same OS-mediated input
  path as normal taps/long presses. This was physically validated on Pixel 9a
  by opening Settings and navigating to the Apps page through a labeled
  `Apps | Recent apps, default apps` element instead of raw coordinate choice.
- The development OpenAI loop now records lightweight after-action progress
  verification for state-changing tools. After an action, it captures the next
  screen, records before/after foreground app, activity, visible-text
  signature, and interactive-element signature in the step record, and stops
  with `no_progress` after repeated unchanged screens. This verifier is
  repo-checked but still needs a fresh assistant APK build and Pixel eval before
  it is treated as physically validated.
- Model `open_url` tools now route through the framework `open_url` action and
  `network.use` capability instead of direct assistant-side intent launches.
- Repo checks now verify that action types emitted by `FrameworkToolExecutor`
  are included in `docs/contracts/action-request.schema.json` and have matching
  framework patch-stack handling.
- Repo checks now verify that framework `recordAudit(...)` event names are
  represented in `docs/contracts/audit-event.schema.json`.
- Repo checks now verify that assistant trajectory event names emitted by
  `TrajectoryRecorder` are represented in
  `docs/contracts/trajectory-event.schema.json`.
- Repo checks now exercise `scripts/validate-trajectory-export.sh` against
  both a sample unpacked trajectory directory and zip.
- Repo checks now exercise `scripts/validate-audit-evidence-export.sh` against
  a sample framework audit evidence export.
- Agent eval reporting now has a public evidence contract at
  `docs/contracts/agent-eval-report.schema.json`.
  `scripts/validate-agent-eval-report.sh` validates eval report structure,
  checks assistant package metadata against the repo manifest, rejects absolute
  or parent-traversing evidence paths, and can validate referenced trajectory
  and framework audit evidence together.
- `scripts/collect-agent-eval.sh` now provides the host-side physical eval
  bridge: after the assistant exports trace/audit files, it pulls the newest
  evidence from `Downloads/OpenPhone`, records device/build/model metadata,
  writes `agent-eval.json`, and runs the eval evidence validator.
- `scripts/diagnose-device-connection.sh` now captures the current host/device
  connection state for bringup blockers. It records macOS USB visibility when
  available, ADB device state, fastboot visibility, shell/logcat probes, and a
  concrete diagnosis such as no USB enumeration, fastboot-visible,
  ADB unauthorized, ADB-shell-unusable, partial ADB, or ready.
- Repo checks now verify that the screen-context schema covers the key
  assistant accessibility UI-tree fields and risk flags.
- The assistant now shows a user-facing approval surface for pending framework
  actions and model confirmation requests. It summarizes risk, capability, and
  requested action outside the raw developer JSON view and keeps approve/deny
  controls visible in the main task flow.
- The development OpenAI loop now has a deterministic pre-action guardrail for
  risky screen/action combinations. If a model tries to act on install/update,
  payment/subscription, destructive data, messaging/sharing/calling, or
  account/login/password screens, the assistant asks for confirmation before
  executing the tool.
- The assistant task surface now has per-task grant controls for
  input/navigation, screenshots, clipboard, sharing, and web links. The
  selected grants are sent to the framework task request, and the assistant
  locally stops model tools that exceed the selected grants before they reach
  framework execution. Grant defaults are now persisted through Settings-owned
  `Settings.Secure` keys with app-private fallback for migration/development.
- A first per-app capability policy seed exists at
  `overlay/vendor/openphone/config/openphone_app_policy.json` and is installed
  into `system_ext`. The assistant-side accessibility screen tree now reports
  `foreground_package` and `root_packages`; assistant model-tool preflight
  first checks durable `Settings.Secure` app-policy overrides under
  `openphone_app_policy_overrides`, then uses the seed plus package context to
  require confirmation or deny sensitive package/capability combinations before
  framework execution. The seed covers Settings, permission prompts, Play
  Store, Google account/payment surfaces, and lock-credential/password surfaces
  as a conservative v1 baseline. `scripts/generate-app-policy-override.sh`
  generates and can install valid override JSON for development/eval use, and
  repo checks validate the generated payload against known capabilities. The
  Settings-owned full per-app editor is deliberately deferred until the core
  agent loop has stronger physical evidence.
- The assistant fallback policy now includes `share.content` as a high-risk
  capability, matching the product capability registry and framework share
  action path.
- Assistant model runs now have stop/cancel wiring: the active adapter can be
  cancelled, the model thread is interrupted, stale run generations cannot
  update the UI, and disconnected OpenAI calls return `cancelled` instead of a
  misleading network failure.
- The development OpenAI loop now has first-pass retry/failure handling: screen
  capture is retried once and repeated tool failures end the run with an
  explicit `action_failed` status instead of silently spinning.
- The local heuristic development adapter now returns structured JSON results
  with `task.finished` and step records, so offline evals can be interpreted by
  the user-facing result surface.
- Assistant model adapters now expose provider/model/cloud metadata and
  disclosure text. The user-facing model panel and active task surface disclose
  when OpenPhone is using the development OpenAI cloud path, what model is in
  use, what screen/task data may be sent, and that the dev API key is kept only
  in memory.
- Assistant cloud model transport now supports a broker/proxy mode. In broker
  mode, the assistant sends the same task-scoped Responses and transcription
  request shapes to an OpenPhone-controlled endpoint with a session token, so
  provider API keys can stay server-side. Direct phone-to-OpenAI remains as a
  development option.
- The assistant now has an `OpenPhoneOrchestrator` entry point for text, mic,
  and debug sends. The deterministic development fallback no longer treats
  ordinary greetings or screen questions as phone-control tasks, while explicit
  action requests still route into the task path.
- The assistant now has a local context index at
  `/data/user/0/org.openphone.assistant/databases/openphone_context_index.db`.
  It records assistant conversation messages and agent lifecycle events, backs
  up existing chat history once, exposes FTS search, and registers a
  `context_search` model tool.
- The assistant now has a privileged notification listener path for context
  indexing. `OpenPhoneNotificationListenerService` grants listener access using
  `MANAGE_NOTIFICATION_LISTENERS` on OpenPhone builds, indexes active/posted/
  removed notification metadata into `openphone_context_index.db`, redacts
  secret notification title/text, and preserves source package plus
  notification key provenance. Notification retrieval is now first-class:
  `notifications_list`, `notifications_search`, and `notifications_summary`
  are registered model tools mapped to `notifications.read`, and direct chat
  prompts such as "show notifications" and "what did I miss?" read from the
  notification-only context index. `notifications_summary` groups recent
  indexed notifications by app/title and returns both structured groups and a
  concise summary. The direct chat path was physically validated on the Pixel
  9a by sending `show notifications` and receiving a `Recent notifications:`
  reply containing indexed `org.openphone.assistant` and `android`
  notifications. The listener itself was physically validated on the Pixel 9a:
  `dumpsys notification` showed
  `org.openphone.assistant/.OpenPhoneNotificationListenerService` as allowed,
  enabled, and live; a due commitment posted `Open loop due` with text
  `notification index smoke unique`; and FTS search in
  `openphone_context_index.db` returned that notification row.
  Notification summary v1 was physically validated with
  `.worktree/artifacts/tegu/OpenPhoneAssistant-notification-summary-v1.apk`
  (`sha256=29b99c8a4b7144d7c099c207eb2d30a659eb26623075438005098590e09954f2`):
  in reviewed mode, `scripts/run-assistant-task.sh --local --goal 'What did I
  miss?' --wait 8` stopped at a Medium-risk approval card for
  `notifications.summarize`; after switching to YOLO, the same goal completed
  with a grouped notification summary. The YOLO trajectory
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-220946-task-65387222728/events.jsonl`
  records `notifications_summary` with `status=notifications.summary` and final
  `task.finished`. The device was reset to reviewed mode after validation.
- Notification-derived commitments are now implemented. The model/action
  catalog includes `notification_commitment_create` /
  `notifications.create_commitment`, mapped to `commitments.write`; the
  framework executor selects a matching indexed notification, writes a durable
  commitment with notification key/package/time evidence, and the local,
  Responses, Realtime, reviewed/YOLO, and dry-run paths know about the tool.
  This was physically validated on the Pixel 9a with
  `.worktree/artifacts/tegu/OpenPhoneAssistant-notification-commitment-v1.apk`
  (`sha256=6ed204edc5a293408e6df0be92f0cb4214bcad9ed739974267e71eb7c19f4d0d`):
  in reviewed mode, `scripts/run-assistant-task.sh --local --goal 'Create a
  reminder from notification about OpenPhone Assistant' --wait 8` stopped at a
  Medium-risk approval card for `notifications.create_commitment`; after
  switching to YOLO, the same goal completed with `Created a commitment from
  the matching notification.` The YOLO trajectory
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-223138-task-42888115661/events.jsonl`
  records `notification_commitment_create` with
  `status=notification.commitment_created`, `commitment_id=2`, and
  notification provenance from `org.openphone.assistant`. A root SQLite check
  of `openphone_commitments.db` showed
  `2|Follow up: OpenPhone Assistant|pending|manual`. The device was reset to
  reviewed mode after validation, and `scripts/check.sh` passed.
- The assistant now has a local memory store at
  `/data/user/0/org.openphone.assistant/databases/openphone_memory.db`. It
  supports explicit "remember that..." saves, recall questions, FTS search,
  dedupe/update by normalized text, and `memory_search` / `memory_save` model
  tools.
- The assistant now has a local commitments store at
  `/data/user/0/org.openphone.assistant/databases/openphone_commitments.db`.
  It supports explicit commitment creation, listing, and completion from chat,
  FTS search, readable due-date display, and
  `commitment_search` / `commitment_create` /
  `commitment_update_status` model tools. This was physically validated on the
  Pixel 9a by creating "follow up with Sarah tomorrow", listing it as active,
  and marking commitment `#1` complete.
- The assistant now has the first local watcher substrate:
  `OpenPhoneWatcherScheduler` checks due time-based commitments at assistant
  startup/boot/package replacement, schedules the next due check with
  `AlarmManager`, marks due commitments as `fired`, and publishes
  `openphone_commitments` notification cards with Complete, Snooze, and Dismiss
  actions. This was physically validated on the Pixel 9a by creating a
  commitment, setting its due time into the past, starting the assistant
  service, verifying the commitment changed from `pending` to `fired`, and
  confirming Android's notification service held an `Open loop due`
  notification for that commitment.
- The assistant now also has a local durable watcher store at
  `/data/user/0/org.openphone.assistant/databases/openphone_watchers.db`, plus
  `watcher_create`, `watcher_list`, and `watcher_stop` model tools mapped to
  `watchers.write` / `watchers.read`. Time-based watcher records are included
  in the scheduler's next-run calculation and fire into `openphone_watchers`
  notification cards. This was physically validated on the Pixel 9a by asking
  chat to create "Tell me in 1 minute to test watcher chat", listing active
  watchers, forcing the watcher's `next_run_at` into the past, broadcasting a
  watcher check, verifying the watcher changed from `active` to `fired`,
  confirming a `Watcher fired` notification, and then stopping watcher `#1`
  through chat.
- Notification-pattern watchers now run through the notification listener path.
  Active `type=notification` watcher records match posted notification package,
  title, and text constraints, refuse empty conditions, fire once, and publish
  `openphone_watchers` cards. Direct chat parsing can create simple watcher
  records from prompts such as "tell me when a notification mentions ...".
  This was physically validated on the Pixel 9a by seeding an active
  notification watcher for `org.openphone.assistant` text `Assistant ready`,
  reposting the assistant foreground notification, verifying the watcher row
  changed to `status=fired` with a notification result hash, and confirming
  Android held an `openphone_watchers` notification record.
- The first formal action registry now exists at
  `overlay/vendor/openphone/config/openphone_action_registry.json`, with schema
  `docs/contracts/action-registry.schema.json`. It maps every current
  model-visible tool to a canonical action name, input/output schema, risk
  class, required capability, authorization policy, allowed callers, executor
  service, and audit event type. Repo checks enforce one-to-one model-tool
  coverage plus risk/policy consistency with `openphone_capabilities.json`, and
  `FrameworkToolExecutor` loads the installed registry from
  `/system_ext/etc/openphone/action_registry.json` to reject unregistered tools
  when the registry is present.
- The assistant now has the first explicit autonomy-mode implementation:
  `reviewed`, `yolo`, and `dry_run`. Advanced settings exposes a mode selector
  through the Compose UI, the backend persists the selected mode under
  `Settings.Secure.openphone_autonomy_mode` with app-private fallback, and
  model-tool preflight consults `openphone_action_registry.json` before
  execution. In reviewed mode, registered `confirm` / `explicit_confirm`
  actions stop with the existing structured approval card. In YOLO mode,
  registered medium-risk `confirm` actions in the bounded v1 profile
  (`memory.write`, `commitments.write`, `watchers.write`,
  `notifications.read`, `notifications.act`, `messages.read`,
  `messages.draft`, `calls.read`, `settings.write`, `background.run`,
  `network.use`) can execute without repeated review; high-risk /
  `explicit_confirm`, denied app-policy, and disabled task-grant paths still
  stop. This was physically
  validated on the Pixel 9a with
  `.worktree/artifacts/tegu/OpenPhoneAssistant-yolo-policy-v1.apk`
  (`sha256=aded51263712fe3e98bcc93fd9e71c54b4000bbb2db70b2b8e19d51ac975ee73`):
  with `openphone_autonomy_mode=reviewed`,
  `scripts/run-assistant-task.sh --local --goal 'please open notification
  about Assistant ready' --wait 8` stopped at `Needs review` and showed an
  approval card for `notifications.open` with `Risk: Medium`; with
  `openphone_autonomy_mode=yolo`, the same command completed with `Done`
  without an approval card. The device was reset to reviewed mode after the
  smoke, and logcat showed no assistant fatal exception or
  `action_registry_missing_tool`.
- Dry-run mode is also implemented and physically validated on the Pixel 9a
  with `.worktree/artifacts/tegu/OpenPhoneAssistant-dry-run-v2.apk`
  (`sha256=2797726ee40ad01171e39937207e8fc3d1bf3a3be74901587e185323e2363cc3`).
  The backend reloads `Settings.Secure.openphone_autonomy_mode` at task start
  so an external mode change is honored by an already-running assistant
  process. In `dry_run`, action/control tools return structured
  `dry_run.preview` results before execution; observation tools such as
  `get_screen`, search, list, and wait remain allowed. The cloud Responses and
  Realtime adapters stop on `dry_run.preview`, and the local heuristic fallback
  now also treats `dry_run.preview` / `confirmation_required` as terminal
  instead of claiming a successful task. Pixel smoke: after clearing
  `openphone_dev_openai_api_key`, setting
  `openphone_autonomy_mode=dry_run`, and running
  `scripts/run-assistant-task.sh --local --goal 'Press Back' --wait 8`, the
  assistant stayed focused and displayed "Dry run previewed the next action
  without executing it." The trajectory
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-183234-task-13556934536/events.jsonl`
  recorded `press_key` with `status=dry_run.preview`, `capability=input.perform`,
  `risk=Medium`, and final agent result `status=dry_run.preview`. The device
  was reset to reviewed mode after the smoke, and logcat showed no assistant
  fatal exception or `NetworkOnMainThreadException`.
- The first semantic notification connector is implemented:
  `notifications_open` / `notifications.open`. The notification listener keeps
  a live service instance, searches active notifications by key/package/query,
  and sends the matching notification's content intent. This lets the assistant
  open a notification without pulling down the shade or tapping coordinates;
  direct chat supports prompts such as "open notification about ...", and the
  local development agent loop now maps the same phrasing to the
  `notifications_open` tool when a matching goal reaches task execution. This slice
  was installed on the Pixel 9a as
  `.worktree/artifacts/tegu/OpenPhoneAssistant-notifications-open-v2.apk`
  (`sha256=73f018291d407661847dc9d9ae9736baf9cd123cfd92f9a2b5b96feacb68d439`);
  on-device hashes matched the local APK, `model_tools.json`
  (`e49bd800888a704ebb1940cff2c05d68fb06a5dde9eca9f5812bc0ad54254084`), and
  `action_registry.json`
  (`984109b4ebe37e4f5a544e9f031646bff46d12a866b16d132b35fd45dcaea6de`).
  `dumpsys notification` showed
  `org.openphone.assistant/.OpenPhoneNotificationListenerService` as allowed,
  enabled, and live; the normal debug harness with
  `scripts/run-assistant-task.sh --local --goal 'Open notification about
  Assistant ready' --wait 15` exercised the installed user-facing route and
  produced an on-device assistant reply of `Opened notification matching:
  Assistant ready`, with no assistant fatal exception, registry-load failure, or
  `action_registry_missing_tool` in logcat.
- Calendar connector v1 is implemented and physically validated on the Pixel
  9a. The assistant now declares/grants `READ_CALENDAR` and `WRITE_CALENDAR`,
  registers `calendar_search` / `calendar.search` and
  `calendar_create_event` / `calendar.create_event` in
  `openphone_model_tools.json` and `openphone_action_registry.json`, exposes the
  tools to both Responses and Realtime adapters, and executes them through
  `FrameworkToolExecutor` using Android `CalendarContract`. `calendar_search`
  reads bounded `CalendarContract.Instances` windows with optional text
  filtering; `calendar_create_event` inserts into a writable
  `CalendarContract.Events` calendar with title, description, location,
  start/end, duration, all-day flag, and optional calendar id. The local
  heuristic fallback and `OpenPhoneOrchestrator` now route show/list/search
  calendar requests to `calendar_search` and create/add/schedule calendar event
  requests to `calendar_create_event`.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-calendar-v1f.apk`
    (`sha256=7445b3dfa3aee0444dccfe3f067fb55ef40b49dc049c3005d00544cdb1f0665e`).
  - Reviewed-mode create smoke:
    `scripts/run-assistant-task.sh --local --goal 'Create calendar event
    OpenPhone calendar smoke' --wait 8` stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-185750-task-35962846493/events.jsonl`
    recorded `calendar_create_event` with `status=confirmation_required`,
    `action_name=calendar.create_event`, `capability=calendar.write`,
    `risk=Medium`, and `autonomy_mode=reviewed`.
  - Dry-run create smoke:
    `scripts/run-assistant-task.sh --local --goal 'Create calendar event
    OpenPhone calendar dry run smoke' --wait 8` displayed "Dry run previewed
    the next action without executing it"; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-185803-task-48728817487/events.jsonl`
    recorded `calendar_create_event` with `status=dry_run.preview`,
    `capability=calendar.write`, `risk=Medium`, and
    `action_name=calendar.create_event`.
  - Reviewed-mode read smoke:
    `scripts/run-assistant-task.sh --local --goal 'Show my calendar' --wait 8`
    stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-190637-task-34658527359/events.jsonl`
    recorded `calendar_search` with `status=confirmation_required`,
    `action_name=calendar.search`, `capability=calendar.read`, `risk=Medium`,
    and `autonomy_mode=reviewed`.
  The device was reset to reviewed mode after validation. `./scripts/check.sh`
  passed, EC2 focused app builds passed, and logcat showed no assistant fatal
  exception, `NetworkOnMainThreadException`, or
  `action_registry_missing_tool`.
- Contacts connector v1 is implemented and physically validated on the Pixel
  9a. The assistant now declares/grants `READ_CONTACTS`, registers
  `contacts_search` / `contacts.search` in `openphone_model_tools.json` and
  `openphone_action_registry.json`, exposes the tool to both Responses and
  Realtime adapters, and executes it through `FrameworkToolExecutor` using
  Android `ContactsContract`. The executor can search all contacts or a
  filtered contact URI and can include bounded phone/email details when
  requested. The local heuristic fallback and `OpenPhoneOrchestrator` route
  show/list/search/find contact requests to `contacts_search`.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-contacts-v1.apk`
    (`sha256=1f7bd2729d9d5c42bf71805e00418f2b66e7b95195625148cb4506b50ddfc97c`).
  - Reviewed-mode read smoke:
    `scripts/run-assistant-task.sh --local --goal 'Show my contacts' --wait 8`
    stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-191612-task-26301521699/events.jsonl`
    recorded `contacts_search` with `status=confirmation_required`,
    `action_name=contacts.search`, `capability=contacts.read`, `risk=Medium`,
    and `autonomy_mode=reviewed`.
  The device was reset to reviewed mode after validation. `./scripts/check.sh`
  passed, the EC2 focused app build passed, and logcat showed no assistant
  fatal exception, `NetworkOnMainThreadException`, or
  `action_registry_missing_tool`.
- Messages connector v1 is implemented and physically validated on the Pixel
  9a. The assistant now declares/grants `READ_SMS` and `SEND_SMS`, registers
  `messages_search` / `messages.search`, `messages_draft` /
  `messages.draft`, and `messages_send` / `messages.send` in
  `openphone_model_tools.json` and `openphone_action_registry.json`, exposes
  the tools to both Responses and Realtime adapters, and executes them through
  `FrameworkToolExecutor` using Android `Telephony.Sms` and `SmsManager`.
  Message reads are medium-risk confirmed (`messages.read`), drafts are
  medium-risk confirmed (`messages.draft`), and sends are high-risk
  explicit-confirm (`messages.send`). The local heuristic fallback and
  `OpenPhoneOrchestrator` route show/list/search message requests to
  `messages_search`, draft/compose text requests to `messages_draft`, and
  explicit send/text requests to `messages_send`.
  Message summary v1 adds `messages_summary` / `messages.summarize` for
  bounded thread/contact summaries over recent SMS rows. It groups by
  `thread_id`, returns counts, inbox/sent/unread metadata, latest time, sample
  messages, and a concise empty-state/human summary. The local heuristic
  fallback routes "summarize my messages" and related prompts to this tool.
  Message commitment v1 adds `message_commitment_create` /
  `messages.create_commitment`, which selects the latest matching SMS by
  optional query/thread id and creates a durable OpenPhone commitment with
  message id, thread id, address, date, type, read state, and body evidence.
  The local heuristic fallback routes prompts such as "Create a reminder from
  message about ..." to this tool.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-message-commitment-v1.apk`
    (`sha256=9efb6039c46bba606df2039d2427d0d5fde998d0d89c206e9a558fe4a3a7fae2`).
  - Reviewed-mode read smoke:
    `scripts/run-assistant-task.sh --local --goal 'Show my messages' --wait 8`
    stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-193459-task-69482642000/events.jsonl`
    recorded `messages_search` with `status=confirmation_required`,
    `action_name=messages.search`, `capability=messages.read`, `risk=Medium`,
    and `autonomy_mode=reviewed`.
  - Reviewed-mode summary smoke:
    `scripts/run-assistant-task.sh --local --goal 'Summarize my messages'
    --wait 8` stopped at `Needs review` for `messages.summarize` with
    `Risk: Medium`.
  - YOLO summary smoke:
    with `openphone_autonomy_mode=yolo`, the same summary goal completed with
    the valid empty-state answer `No recent SMS messages were available.`
    Trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-224723-task-75863605179/events.jsonl`
    records `messages_summary` with `status=messages.summary`, `threads=[]`,
    and final `task.finished`.
  - Reviewed-mode message commitment smoke:
    `scripts/run-assistant-task.sh --local --goal 'Create a reminder from
    message about OpenPhone' --wait 8` stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-225839-task-26229792573/events.jsonl`
    recorded `message_commitment_create` with `status=confirmation_required`,
    `action_name=messages.create_commitment`, `capability=commitments.write`,
    `risk=Medium`, and `autonomy_mode=reviewed`.
  - YOLO message commitment smoke:
    after seeding a temporary SMS row with body `OpenPhone message commitment
    smoke`, `scripts/run-assistant-task.sh --local --goal 'Create a reminder
    from message about OpenPhone message commitment smoke' --wait 12`
    completed with `Created a commitment from the matching message.`
    Trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-230043-task-149704915680/events.jsonl`
    records `message_commitment_create` with
    `status=message.commitment_created`, `commitment_id=3`, and SMS evidence
    for `message_id=1`, `thread_id=1`, address `+15550001111`, and body
    `OpenPhone message commitment smoke`. A root SQLite check of
    `openphone_commitments.db` showed
    `3|Follow up: +15550001111|pending|manual`. The temporary SMS seed row was
    deleted after validation.
  - Dry-run draft smoke:
    `scripts/run-assistant-task.sh --local --goal 'Draft a text to Alice
    saying Hello from OpenPhone' --wait 8` displayed "Dry run previewed the
    next action without executing it"; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-193414-task-25274393688/events.jsonl`
    recorded `messages_draft` with `status=dry_run.preview`,
    `capability=messages.draft`, `risk=Medium`, and
    `action_name=messages.draft`.
  - Reviewed-mode send smoke:
    `scripts/run-assistant-task.sh --local --goal 'Send a text to 15551234567
    saying Hello from OpenPhone' --wait 8` stopped at `Needs review`;
    trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-193530-task-100978348152/events.jsonl`
    recorded `messages_send` with `status=confirmation_required`,
    `action_name=messages.send`, `capability=messages.send`, `risk=High`,
    `reason=action_policy_explicit_confirm`, and `autonomy_mode=reviewed`.
  The device was reset to reviewed mode after validation. `./scripts/check.sh`
  passed, the EC2 focused app build passed, and logcat showed no assistant
  fatal exception, `NetworkOnMainThreadException`, or
  `action_registry_missing_tool`.
- Phone connector v1 is implemented and physically validated on the Pixel 9a.
  The assistant now declares/grants `READ_CALL_LOG` and `CALL_PHONE`, registers
  `calls_search` / `phone.search_calls`, `phone_context` / `phone.context`,
  and `calls_place` / `phone.call` in `openphone_model_tools.json` and
  `openphone_action_registry.json`, exposes the tools to both Responses and
  Realtime adapters, and executes them through `FrameworkToolExecutor` using
  Android `CallLog.Calls`, `ContactsContract`, `Telephony.Sms`,
  `CalendarContract`, and `TelecomManager.placeCall`. Call-log/context reads
  are medium-risk confirmed (`calls.read`) and call placement is high-risk
  explicit-confirm (`calls.place`). `phone_context` gathers a call context card
  from recent calls, contacts, messages, and calendar events, and extracts
  confirmation-code candidates from message/calendar text. The local heuristic
  fallback and `OpenPhoneOrchestrator` route recent/missed/call-history
  requests to `calls_search`, phone/call-context and confirmation-code
  requests to `phone_context`, and explicit call/dial requests to `calls_place`.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-phone-context-v1.apk`
    (`sha256=5bef9ef275d8a0433f53f0aa292e6bc0ba3a003d6cca33ef4eeb032ff99f0359`).
  - Reviewed-mode call-log smoke:
    `scripts/run-assistant-task.sh --local --goal 'Show recent calls' --wait 8`
    stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-194649-task-37882369443/events.jsonl`
    recorded `calls_search` with `status=confirmation_required`,
    `action_name=phone.search_calls`, `capability=calls.read`, `risk=Medium`,
    and `autonomy_mode=reviewed`.
  - Reviewed-mode phone context smoke:
    `scripts/run-assistant-task.sh --local --goal 'Show phone context for
    OpenPhone' --wait 8` stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-231758-task-24240323904/events.jsonl`
    recorded `phone_context` with `status=confirmation_required`,
    `action_name=phone.context`, `capability=calls.read`, `risk=Medium`, and
    `autonomy_mode=reviewed`.
  - YOLO phone context smoke:
    with `openphone_autonomy_mode=yolo`, the same goal executed
    `phone_context` and returned `status=phone.context` with source statuses
    for calls, contacts, messages, and calendar. Trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-231841-task-67142899568/events.jsonl`
    recorded final `task.finished`. A temporary SMS smoke row visible in the
    phone-context result was deleted after validation, and the device was reset
    to reviewed mode.
  - Reviewed-mode place-call smoke:
    `scripts/run-assistant-task.sh --local --goal 'Call 15551234567' --wait 8`
    stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-194702-task-50867553979/events.jsonl`
    recorded `calls_place` with `status=confirmation_required`,
    `action_name=phone.call`, `capability=calls.place`, `risk=High`,
    `reason=action_policy_explicit_confirm`, and `autonomy_mode=reviewed`.
  The device was reset to reviewed mode after validation. `./scripts/check.sh`
  passed, the EC2 focused app build passed, and logcat showed no assistant
  fatal exception, `NetworkOnMainThreadException`, or
  `action_registry_missing_tool`.
- Browser fetch/page-context connector v1 is implemented and physically
  validated on the Pixel 9a. The assistant registers `browser_fetch_page` /
  `browser.fetch_page` in `openphone_model_tools.json` and
  `openphone_action_registry.json`, exposes it to both Responses and Realtime
  adapters, and executes it through `FrameworkToolExecutor` using a bounded
  `HttpURLConnection` fetch. The executor reads at most 512 KB, strips
  script/style/noscript blocks, extracts page title and readable text, and
  returns bounded page metadata without opening a browser UI or using pointer
  fallback. The tool is enforced as medium-risk `network.use`; the local
  heuristic fallback and `OpenPhoneOrchestrator` route URL summarization/read/
  fetch requests to `browser_fetch_page`.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-browser-fetch-v1.apk`
    (`sha256=66e08a1e19876c9982d8c02b031bf64e75591d9f90be99965b2ef4d8d40fcad2`).
  - Reviewed-mode URL fetch smoke:
    `scripts/run-assistant-task.sh --local --goal 'Summarize https://example.com' --wait 8`
    stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-195754-task-30693918837/events.jsonl`
    recorded `browser_fetch_page` with `status=confirmation_required`,
    `action_name=browser.fetch_page`, `capability=network.use`, `risk=Medium`,
    and `autonomy_mode=reviewed`.
  - YOLO localhost fetch smoke:
    a deterministic local page was served from `.worktree/browser-smoke/` on
    `127.0.0.1:8765` and forwarded with `adb reverse tcp:8765 tcp:8765`.
    `scripts/run-assistant-task.sh --local --goal 'Summarize
    http://127.0.0.1:8765/index.html' --wait 8` completed; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-195840-task-77142304276/events.jsonl`
    recorded `browser_fetch_page` with `status=browser.page_fetched`,
    `http_status=200`, `title=OpenPhone Browser Smoke`, extracted page text,
    and `truncated=false`.
  The local smoke server was stopped and the device was reset to reviewed mode
  after validation. `./scripts/check.sh` passed, the EC2 focused app build
  passed, and logcat showed no assistant fatal exception,
  `NetworkOnMainThreadException`, or `action_registry_missing_tool`.
- Browser search connector v1 is implemented and physically validated on the
  Pixel 9a. The assistant registers `browser_search` / `browser.search` in
  `openphone_model_tools.json` and `openphone_action_registry.json`, exposes it
  to both Responses and Realtime adapters, and executes it through
  `FrameworkToolExecutor` by building a search URL and delegating to the
  framework `open_url` action. This gives the agent a semantic browser-search
  action without typing into the browser address bar or using pointer fallback.
  The tool is enforced as medium-risk `network.use`; reviewed mode stops at an
  approval card, and YOLO can execute it under the existing bounded
  `network.use` allowlist.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-browser-search-v1b.apk`
    (`sha256=800a8eb87d8fa21645717ea8bcbfd4b50c29f23181d8ba88930a6657dfc553d4`).
  - Reviewed-mode search smoke:
    `scripts/run-assistant-task.sh --local --goal 'Search the web for
    OpenPhone browser search smoke' --wait 8` stopped at `Needs review`;
    trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-205033-task-35963917049/events.jsonl`
    recorded `browser_search` with `status=confirmation_required`,
    `action_name=browser.search`, `capability=network.use`, `risk=Medium`, and
    `autonomy_mode=reviewed`.
  - YOLO search smoke:
    with `openphone_autonomy_mode=yolo`, the same goal opened Jelly to
    `https://duckduckgo.com/?q=OpenPhone%20browser%20search%20smoke`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-205105-task-67389246003/events.jsonl`
    recorded `browser_search` with `state=action.executed`,
    `capability=network.use`, and the generated DuckDuckGo URL. The device was
    reset to reviewed mode after validation. `./scripts/check.sh` passed, the
    EC2 focused app build passed, and logcat showed no assistant fatal
    exception, `NetworkOnMainThreadException`, or
    `action_registry_missing_tool`.
- Apps connector v1 is implemented and physically validated on the Pixel 9a.
  The assistant now registers `apps_search` / `apps.search` in
  `openphone_model_tools.json` and `openphone_action_registry.json`, adds a
  low-risk `apps.read` capability, exposes the tool to both Responses and
  Realtime adapters, and executes it through `FrameworkToolExecutor` using
  `PackageManager` launchable-activity queries. `open_app` now resolves real
  installed app labels/packages before falling back to the legacy alias map, so
  the agent can search apps and then launch by canonical package.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-apps-search-v1.apk`
    (`sha256=7f3568ade5bce689e6f3499fe4143cec9f02c7b1fae3846251a6fc72c9089eb3`).
  - Reviewed-mode read smoke:
    `scripts/run-assistant-task.sh --local --goal 'Search apps for Settings'
    --wait 8` completed without an approval card because `apps.read` is
    low-risk task-scoped; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-203636-task-36435748390/events.jsonl`
    recorded `apps_search` with `status=apps.search.results`, `query=Settings`,
    and an app result `{label=Settings, package=com.android.settings,
    activity=com.android.settings.Settings, system=true, launchable=true}`.
    `./scripts/check.sh` passed, the EC2 focused app build passed, and logcat
    showed no assistant fatal exception, `NetworkOnMainThreadException`, or
    `action_registry_missing_tool`.
- While validating the action-registry runtime path, the Pixel 9a exposed a
  `NetworkOnMainThreadException` in Realtime cancellation during activity
  teardown. `OpenAiRealtimeAdapter.cancel()` now moves WebSocket close I/O to a
  background `OpenPhoneRealtimeClose` thread. The previous crash repro
  (`scripts/run-assistant-task.sh --local --goal 'Press Back' --wait 5`) was
  rerun on-device with no fatal exception, no registry-load failure, no
  `action_registry_missing_tool`, and no `NetworkOnMainThreadException`.
- Watcher runtime now uses its durability fields for basic production hygiene:
  stale `running_at` rows are repaired after service death/reboot, failed
  watcher checks are rescheduled with exponential backoff, no-op checks do not
  notify, and repeated failures surface `Watcher needs attention` cards. This
  was physically validated on the Pixel 9a by seeding a stale running watcher
  and an unsupported `web_change` watcher, broadcasting a watcher check,
  verifying the stale watcher was reset to `running_at=0` with
  `failure_count=1` and `last_result_hash=stuck_running_repaired`, verifying
  the unsupported watcher incremented to `failure_count=3` with
  `last_result_hash=unsupported_watcher_type:web_change`, and confirming
  Android held a `Watcher needs attention` notification for that failure.
- The first browser/page watcher slice is implemented for `type=web_change`.
  `OpenPhoneWatcherScheduler` runs web fetches off the main thread, bounds page
  reads to 512 KB, stores a `web:<sha256>` baseline without notifying on the
  first/no-op check, reschedules unchanged pages, and fires a normal
  `openphone_watchers` card when the fetched content hash changes.
  `AssistantActivityBackend` can create these watchers from direct prompts such
  as "watch https://... for changes", and
  `res/xml/openphone_network_security.xml` allows cleartext only for
  localhost/127.0.0.1 so physical smoke tests can use `adb reverse` without
  enabling global cleartext traffic. This was physically validated on the Pixel
  9a with `.worktree/artifacts/tegu/OpenPhoneAssistant-web-watchers-v1b.apk`
  (`sha256=cec1db749ce6e49ce25f8cfc7b77d0518bb949cd511361e331d5d72b9318065a`):
  a local page served through `adb reverse tcp:8765 tcp:8765` first changed
  watcher `#6` to `status=active`, `failure_count=0`, and
  `last_result_hash=web:ad652b7f7740676d24628033d674839aa9a198300f24594e144e413a991adf3f`
  with no fired watcher notification; after changing the page and forcing a
  second check, watcher `#6` changed to `status=fired` with
  `last_result_hash=web:c3819961b52ec342ba833fe596ec3fe96049461487c219141095ecc736ae9716`,
  and Android held an `openphone_watchers` notification titled `Watcher fired`
- Generic watcher evaluator v2 is in progress as a single watcher primitive,
  not a family of one-off domain tools. `watcher_create` now accepts generic
  `source`, `evaluator`, `url`, `query`, and `interval_ms` inputs and
  normalizes them into the existing durable watcher store. Web watchers support
  both `hash_change` and bounded `text_contains` evaluation; `semantic_match`
  is accepted as semantic-lite and currently uses deterministic page-text
  matching until model-backed background evaluation is added. The Responses and
  Realtime tool descriptions, action registry schema, direct chat shortcut, and
  local heuristic adapter all use the generic vocabulary.
  - Installed artifact:
    `.worktree/artifacts/tegu/OpenPhoneAssistant-watcher-evaluator-v2c.apk`
    (`sha256=a0352394bf0e00392c5961041b9ce325e22c206cffaa90f4fd39e75c2dd186eb`).
  - Reviewed-mode smoke:
    `scripts/run-assistant-task.sh --local --goal 'Set up monitoring
    http://127.0.0.1:8765/THE_MASTER_PLAN.md for Generic watcher evaluator'
    --wait 8` stopped at `Needs review`; trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-202437-task-21067986338/events.jsonl`
    recorded `watcher_create` with `source=web`,
    `evaluator=text_contains`, `query=Generic watcher evaluator`,
    `action_name=watchers.create`, `capability=watchers.write`, and
    `autonomy_mode=reviewed`.
  - YOLO create/evaluate smoke:
    with `openphone_autonomy_mode=yolo`, the same goal created watcher `#7`;
    trajectory
    `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-202500-task-44123775004/events.jsonl`
    recorded `status=watcher.created`, `type=web_change`, and the normalized
    web/text condition. After forcing `next_run_at=1` and broadcasting
    `org.openphone.assistant.action.CHECK_WATCHERS`, watcher `#7` changed to
    `status=fired` with
    `last_result_hash=web_match:83e9418e477dddb92338461e96acb2d108bc47e400f5c0c03805382e3ada8b82`,
    and Android held an `openphone_watchers` notification titled
    `Watcher fired` with text `Watch page for Generic watcher evaluator`.
    The device was reset to reviewed mode after validation.
  with text `Web watcher smoke`.
- A first dependency-free model broker reference server exists under
  `services/model-broker/`. It validates bearer session tokens, applies
  coarse body-size plus per-token/IP request-count and byte-volume rate limits,
  avoids request-body logging,
  proxies `/v1/responses` and `/v1/audio/transcriptions` to OpenAI, and can
  mint signed expiring development session tokens through both a CLI helper and
  an admin-authenticated `/v1/session_tokens` endpoint. It also supports
  structured JSONL request-outcome audit events, a JSON provider/model registry,
  a JSON device-subject registry for token issuance, optional per-subject
  development HMAC device proofs before token minting, and an optional
  environment override for Responses API model allowlisting. First-pass Linux
  deployment hardening artifacts live under `services/model-broker/deploy/`:
  a locked-down systemd unit, an environment template that keeps secrets out of
  the repository, an nginx TLS reverse-proxy template, and installation notes
  for localhost binding behind HTTPS. `scripts/rotate-model-broker-secrets.sh`
  provides a first operational helper for rotating broker token-signing and
  admin-token secrets without modifying provider keys, and for rotating the
  provider key without modifying broker token/admin secrets.
  `scripts/setup-model-broker-tls.sh` provides a first certbot/nginx helper for
  rendering broker-domain TLS config and running or printing certificate
  issuance and renewal-validation commands.
- Automated model broker smoke coverage exists at
  `scripts/smoke-test-model-broker.sh` and is wired into `scripts/check.sh`.
  It verifies local health, admin authorization failure, token minting through
  `/v1/session_tokens`, required/invalid device-attestation rejection, signed
  token acceptance, malformed JSON rejection, device-registry-backed subject
  rejection, registry-backed model allowlist rejection, request-size and
  byte-rate rejection, transcription content-type enforcement,
  OpenPhone metadata requirements, sensitive-screen rejection, image-count
  limits, bounded provider retry on transient 429/5xx failures, body-free
  audit events, and no request-body leakage into audit/server logs.
- Settings now exposes OpenPhone as a first-class OS surface: About phone has
  OpenPhone version/support rows and the Settings homepage has an OpenPhone page
  with assistant, task-grant, audit-evidence, and support entry points. Settings
  also has dedicated OpenPhone task-grant and audit pages; the task-grant page
  stores durable defaults in `Settings.Secure`, and the audit page reads
  framework service status and recent audit events directly through
  `OpenPhoneAgentManager` when the service is available.
- Build, flash, and scaffold validation scripts.
- Pixel 9a hardware smoke-test evidence script. The script captures automated
  diagnostics for identity, Wi-Fi, Bluetooth, cellular/SIM, camera service,
  location/GPS service, fingerprint service, audio, sensors, encryption/lock
  state, battery/thermal state, and OpenPhone runtime, and writes a report under
  `.worktree/reports/`.
- Release artifact manifest generator. `scripts/generate-release-manifest.sh`
  writes `SHA256SUMS` and `ARTIFACTS.md` for a release artifact directory.
  It has been smoke-tested against the local Pixel 9a artifact cache; actual
  releases should use a clean staging directory containing only publishable
  artifacts.
- Release artifact validation gate. `scripts/validate-release-artifacts.sh`
  verifies staged release checksums, OTA ZIP integrity, required manifests, and
  obvious secret/key mistakes before publication.
- OTA feed contract and tooling. `docs/contracts/ota-feed.schema.json` defines
  the first server-side update feed for future updater clients.
  `scripts/generate-ota-feed.sh` writes feed JSON for a staged OTA, and
  `scripts/validate-ota-feed.sh` verifies feed structure plus local artifact
  size/SHA-256 when an artifact directory is provided.
- Release draft preparation. `scripts/prepare-github-release.sh` validates a
  staged release directory and writes an inspectable GitHub CLI draft-release
  command plus asset list for the release.
- Current v0.0.1 preview release staging evidence:
  - Clean local staging directory:
    `.worktree/releases/v0.0.1-preview`.
  - Staged OTA:
    `openphone_tegu-settings-grants-v55-ota.zip`.
  - Staged OTA SHA-256:
    `c2f08cad2b5247eb88982c4799901fb5f70d451ffde8cb3fde0e0b463f95a443`.
  - Generated manifest:
    `.worktree/releases/v0.0.1-preview/release-v0.0.1-preview/ARTIFACTS.md`.
  - Generated checksums:
    `.worktree/releases/v0.0.1-preview/release-v0.0.1-preview/SHA256SUMS`.
  - Generated GitHub draft helper:
    `.worktree/releases/v0.0.1-preview/release-v0.0.1-preview/gh-release-draft.sh`.
  - `scripts/validate-release-artifacts.sh .worktree/releases/v0.0.1-preview`
    passed.
- Local JSON/XML/shell scaffold checks.
- macOS case-sensitive Android build volume helper.
- Sync preflight checks for case-sensitive filesystem and Git LFS.
- Build preflight for GNU coreutils on macOS.
- Darwin Soong bootstrap test skip for local macOS builds.
- Verified Lineage `repo sync` on a case-sensitive APFS sparse volume.
- Verified `lunch openphone_arm64 bp4a userdebug` against the synced tree.
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
- Device support matrix and Pixel 9a bringup notes.

## Architecture Audit and Revised Direction (2026-06-09)

A full repository review after the connector-slice sprint found that the
substrate layer is sound but the routing/orchestration layer has drifted into
hardcoded keyword routers, against the explicit direction in
`THE_MASTER_PLAN.md`. Full details live in that file's "Architecture Audit"
section; this is the implementation-status summary.

### Confirmed sound (keep)

- Action registry + capability + risk-class enforcement below the model.
- Reviewed/YOLO/dry-run autonomy modes with policy preflight.
- Audit log, trajectory recorder, and evidence-export flow.
- Observe/decide/act/verify device task loop with step budget, wall-clock
  limit, no-progress detection, and finish-evidence checks.
- Durable, reboot-safe SQLite stores: context index, memory, commitments,
  watchers (all physically validated on the Pixel 9a).
- The EC2-build / artifact-SHA / device-smoke validation discipline.

### Confirmed drift (fix before new connectors)

1. **Three stacked keyword routers.**
   `AssistantActivityBackend.routeMessageFromCurrentMessage()` runs four
   `handleExplicit*` keyword handlers before the orchestrator;
   `OpenPhoneOrchestrator` is keyword guards around a 3-mode route call and
   can override model decisions with the same keywords; and
   `LocalHeuristicModelAdapter` (~1,400 lines, ~18 `isXxxGoal()` classifiers,
   ~570-line dispatch cascade) became the de facto task router instead of an
   offline dev fallback.
2. **Hand-maintained tool surfaces.** The model tool catalog is duplicated
   as a ~200-line hand-written prompt string in
   `OpenAiResponsesAgentAdapter`, a ~50-clause `isAllowedTool()` if-chain
   plus tool definitions in `OpenAiRealtimeAdapter`, and a ~92-case switch
   in `FrameworkToolExecutor` — even though
   `openphone_action_registry.json` already holds names, descriptions,
   schemas, risk, and policy for all 47 tools. Adding one tool costs 5–6
   file edits.
3. **check.sh blind spot.** Repo checks validate config/coverage
   consistency but not Java compilation, which is how the current
   broken-tree state passed.

### Revised engineering sequence

The spine-first plan (mirrors `THE_MASTER_PLAN.md` Near-Term Priorities):

- **Phase 0 — restore a buildable tree.** Implement or remove the dangling
  calendar-heuristic references; add a Java syntax/compile gate to
  `./scripts/check.sh`.
- **Phase A — registry-driven tool surfaces.** Generate the Responses agent
  prompt tool catalog, Realtime tool definitions, and allowed-tool checks
  from the installed action registry at runtime. Delete the hand-maintained
  prompt string and if-chain. Acceptance: a new tool costs 2 JSON entries +
  1 executor case.
- **Phase B — Orchestrator v1, model-first.** Implement the full decision
  schema (`answer | clarify | retrieve | inspect_screen | act | watch |
  memory | stop`), move backend `handleExplicit*` handlers behind the
  orchestrator, reduce keyword logic to stop/cancel safety rails and offline
  fallback, and demote `LocalHeuristicModelAdapter` to offline-dev-only.
- **Phase C — resume connectors on the new spine.** Finish
  `calendar.create_event_from_message` as the first registry-driven tool:
  EC2 focused APK build, install via `scripts/push-assistant-apk.sh`,
  reviewed-mode smoke (expect Medium-risk approval card), YOLO smoke
  (expect `status=calendar.event_created_from_message`, may need a seeded
  SMS row and writable calendar), verify the calendar row, reset the device
  to reviewed mode, delete temporary SMS seeds, and record artifact
  SHA/trajectory evidence here.

### Phase B implementation record (2026-06-10)

Phase B is code-complete and `./scripts/check.sh` is green (54 Java files
compile against android-35). What changed:

- `OrchestratorDecision` now carries the full master-plan schema: modes
  `answer | clarify | retrieve | inspect_screen | act | watch | memory |
  stop`, plus `proposed_actions` (`[{tool, arguments}]`),
  `delivery_surface`, `reason`, and the operating mode.
- `OpenPhoneOrchestrator.decide(adapter, message, hasActiveTask,
  operatingMode, recentConversationJson)` is the single decision point.
  Deterministic logic is reduced to true safety rails: exact-match
  stop/cancel interception and the empty-message case. `guardDecision` and
  all keyword routing (greeting/screen-question/task/URL classifiers) are
  deleted; the model's structured decision is authoritative.
- `ModelAdapter.routeMessage` was replaced by
  `decideOrchestration(userMessage, hasActiveTask, recentConversationJson)`.
  The Responses adapter builds the orchestrator prompt from
  `ToolCatalog.promptToolCatalog()` (registry-generated) plus recent
  conversation from the context index
  (`ContextIndexStore.recentConversationJson`). The Realtime adapter
  delegates to the Responses fallback; the local adapter is a documented
  offline-dev shim.
- `AssistantActivityBackend` lost 931 lines of pre-orchestrator keyword
  handlers (`handleExplicitWatcherMessage`, `handleExplicitCommitment...`,
  `handleExplicitMemory...`, `handleExplicitNotification...`, and ~30
  parsing helpers). No keyword route runs before the orchestrator anymore.
- One-shot execution path: orchestrator decisions with `proposed_actions`
  run inside a transient framework task through the same policy chain as
  the agent loop — dry-run preview, task-grant preflight, app policy,
  action policy (`runOneShotActions`) — with trajectory recording, then a
  model-written one-or-two-sentence summary to chat. `confirmation_required`
  results surface the standard approval card; approval executes the tool
  and summarizes, denial stops the task
  (`finishOneShotConfirmation`). No screenshot task loop is spun up.
- `currentOperatingMode()` now maps the persisted autonomy mode
  (`reviewed`/`yolo`/`dry_run`) instead of hardcoding REVIEWED.
- Assistant manifest bumped to `versionCode=95`, `versionName=0.1.59-dev`.

**Phase B device acceptance: PASSED (2026-06-10, Pixel 9a).** Artifact
`.worktree/artifacts/tegu/OpenPhoneAssistant-orchestrator-v1.apk`
(`sha256=80db81b898cc54b0164a5a14cc22a09a8514e7a9f5b818660acf482c76452c9a`,
`versionCode=95` / `0.1.59-dev`), built on EC2, installed with
`scripts/push-assistant-apk.sh`, device in `reviewed` mode, dev OpenAI key
via the debug harness. All six acceptance smokes passed with zero
pre-orchestrator keyword routes (they no longer exist in the code):

1. "hello" → answer mode, "Hello! How can I help?" in chat. No task started.
2. "what's on my screen?" → inspect_screen mode; correct in-place
   description of the assistant chat surface via transient task.
3. "what did I miss?" → retrieve mode, one-shot `notifications_summary`
   through the policy chain; Medium-risk "Approve notifications.summarize"
   card; on Approve, model-written summary of real notifications appeared in
   chat and the transient task was stopped.
4. "remind me if Sarah doesn't reply in the next hour" → watch mode,
   one-shot `watcher_create`; Medium-risk "Approve watchers.create" card;
   on Approve, watcher row id=8 (`type=time`, `title=Remind me if Sarah
   doesn't reply`, `status=active`) verified in
   `openphone_watchers.db`, and the chat confirmed "I'll remind you if
   Sarah doesn't reply within the next hour."
5. "send Adam a message saying I'll be late" → act mode (multi-step agent
   task); the agent stopped at confirmation cards and never sent anything —
   the SMS store contains only the pre-existing inbound seed row
   (type=1). No `messages_send` executed without approval.
6. "stop" → stop rail fired with a confirmation card pending; the pending
   approval was cleared, the task stopped, island returned to "Ready".

Trajectories for the smokes are under
`/data/user/0/org.openphone.assistant/files/openphone-trajectories/`
(`20260610-09*`). Phase B is complete.

### Phase C calendar-from-message slice (2026-06-10)

**Implementation.** `calendar.create_event_from_message` /
`message_calendar_event_create` runs end-to-end on the registry-driven
spine: the model picks the tool itself (no keyword routing), the action
registry drives the Realtime tool schema, the policy chain produces the
Medium-risk confirm card in reviewed mode, and YOLO mode auto-executes
because `calendar.write` is in the YOLO capability set. Bugs found and
fixed on-device during validation:

- **Realtime empty-arguments bug.** `waitForTurn` accepted
  `response.output_item.added` function-call items, whose `arguments`
  field is empty at add-time; the first (empty) call won de-dup against
  the completed call, so approved actions executed with only the injected
  `reason`. Fix: drop `output_item.added` as a call source and de-dup by
  call_id keeping the variant with non-empty arguments
  (`addOrUpgradeCall`).
- **Approval-resume context loss.** `confirmPending` resumed the agent
  with an empty goal after an approved mid-task action. Fix: resume with
  `mActiveTaskGoal` plus the approved tool's result JSON.
- **SMS phrase matching.** `firstMatchingSms`/`messagesSearch` used a
  single `LIKE %query%`, so the natural query "team dinner Luigi
  Trattoria" missed "Team dinner at Luigi Trattoria". Fix: shared
  `smsSelection()` matches each whitespace token against address/body.
- **Stale device registry.** `/system_ext/etc/openphone/*.json` predated
  the slice, so the adapter rejected `message_calendar_event_create` as
  `unknown_model_tool`. Updated registry configs pushed; they ship in the
  product makefile for OTA builds.

**Device acceptance: PASSED (2026-06-10, Pixel 9a).** Artifact
`.worktree/artifacts/tegu/OpenPhoneAssistant-calendar-from-message-v4.apk`
(`sha256=d915602735220deff6d74fda7c795517799b00be32f51101bb5b0f758c34e281`,
`versionCode=99` / `0.1.63-dev`), built on EC2, installed with
`scripts/push-assistant-apk.sh`. Seeded inbound SMS: "Team dinner at
Luigi Trattoria this Friday at 7pm, see you there!" from +15550002222;
writable local calendar id=1.

1. **Reviewed mode:** goal "create a calendar event from the text message
   about the team dinner at Luigi Trattoria" → agent chained
   `messages_search` (Medium card, approved) →
   `message_calendar_event_create` (Medium
   "Approve calendar.create_event_from_message" card, approved) → event
   row created: `title=Team Dinner at Luigi Trattoria`,
   `eventLocation=Luigi Trattoria`, description carrying the full
   "Source message:" provenance block (body, sender, message id 2). Chat
   ended with "Done." Trajectories `20260610-1058*`/`20260610-1059*`.
2. **YOLO mode** (`openphone_autonomy_mode=yolo`): same goal →
   `messages_search` → `message_calendar_event_create` →
   `status=calendar.event_created_from_message` (event id 2) →
   `finish_task`, all auto-executed with no approval card, finishing with
   `task.finished` in 6.4s. Trajectory `20260610-110059-task-233463674348`.

Cleanup: test events and the seeded SMS deleted, device reset to
`openphone_autonomy_mode=reviewed`.

### Phase C semantic watcher slice (2026-06-10)

**Implementation.** `semantic_match` web watchers now use a real model
judgment instead of the previous keyword-contains alias.
`OpenPhoneWatcherScheduler.runWebChangeWatcher` splits `semantic_match`
from `text_contains`: the deterministic evaluator keeps the normalized
substring check, while semantic watchers strip the fetched page to bounded
plain text (script/style/tag removal, 24k char cap on the 512KB fetch) and
ask the Responses model a strict yes/no question via a new
`OpenAiResponsesAgentAdapter.judgeWatcherCondition` (prompt: condition
counts as satisfied only if the page actually states or clearly implies
it; reply exactly `yes` or `no`). Any model failure (unconfigured,
HTTP error, unparseable verdict) throws `IOException` into the existing
`failWatcher` backoff path — no silent keyword fallback, per the
no-heuristic-routing rule. Fired semantic watchers record
`web_semantic_match:<sha>` result hashes; a repeat check against
byte-identical content with a previous `web_no_match:` hash skips the
model call. Because watchers run from a BroadcastReceiver with no
activity, the background credential is `Settings.Secure
openphone_dev_openai_api_key`, read only on `userdebug`/`eng` builds
(`watcherModelEndpointConfig`).

**Device acceptance: PASSED (2026-06-10, Pixel 9a).** Artifact
`.worktree/artifacts/tegu/OpenPhoneAssistant-semantic-watcher-v1.apk`
(`sha256=5a3db48df3bbeadc1c0768062dc47bbda99ff1072ce6cd08466c32390918575e`,
`versionCode=100` / `0.1.64-dev`), built on EC2, installed with
`scripts/push-assistant-apk.sh`. Smoke fixture: local
`python3 -m http.server 8093` served to the device over
`adb reverse tcp:8093`; negative page says the shoes are "sold out and
cannot be ordered" while inviting stock-alert sign-ups (a keyword trap:
it contains "back in stock"), positive page says sneakers in the "azure
colorway have been restocked and are available to order" (no literal
"back in stock"/"buy" phrasing).

1. **Reviewed mode:** goal "Create a watcher on
   http://127.0.0.1:8093/page.html using semantic matching: notify me
   when the running shoes are back in stock and available to buy" →
   Medium "Approve watchers.create" card → approved → watcher row id=9
   with `evaluator=semantic_match`, `interval_ms=60000`. Negative page:
   model judged **no** (`web_no_match:` hash, no notification) even
   though the page contains the literal phrase "back in stock" — the old
   keyword alias would have false-fired here. Unchanged content on the
   next check skipped the model call. Positive page: model judged
   **yes** → `status=fired`, `last_result_hash=web_semantic_match:b8ea…`,
   "Running shoes back in stock" notification posted.
2. **YOLO mode:** goal "alert me when the shoes can actually be purchased
   again" → `watcher_create` auto-executed with no approval card
   (trajectory `20260610-114247-task-493524850134`, watcher id=10,
   `evaluator=semantic_match`). Negative page → no verdict/noop;
   positive page → `fired` with `web_semantic_match:4253…` and the
   "Shoes available to purchase" notification.

Also exercised on-device: the model-failure path (`web_error:
ConnectException` → `failWatcher` backoff, `failure_count=1`) when the
adb reverse forward briefly dropped, followed by clean recovery on the
next due check.

Cleanup: smoke watchers 9–10 deleted, notifications dismissed, dev key
removed from Settings.Secure, device reset to
`openphone_autonomy_mode=reviewed`, local server and reverse forward
torn down.

### Phase C browser/page context slice (2026-06-10)

**Implementation.** `browser.fetch_page` / `browser_fetch_page` now
returns structured page context instead of flat text only:

- `headings`: page outline as `{level, text}` for `<h1>`–`<h4>` (up to
  24 entries, 160 chars each), extracted before tag stripping.
- `links`: up to 40 de-duplicated outgoing links as `{text, url}`, with
  relative hrefs resolved against the final URL and `javascript:`,
  `mailto:`, `tel:`, and `data:` schemes dropped.
- `truncated` is now computed against the full extracted text rather
  than raw HTML length.

The action registry output schema documents the new fields, and both
Realtime prompts (initial task prompt and session instructions) tell the
model it can chain another `browser_fetch_page` call on a returned link
when the answer lives on a linked page — model-decided navigation, no
keyword routing. The registry description changed, so
`/system_ext/etc/openphone/action_registry.json` was pushed alongside
the APK per the standing registry-push rule.

**Device acceptance: PASSED (2026-06-10, Pixel 9a).** Artifact
`.worktree/artifacts/tegu/OpenPhoneAssistant-browser-context-v1.apk`
(`sha256=b93e29f94e3f9c9a80bdd14657a64ce58dd6374303b15492030b9108b2c131ff`,
`versionCode=101` / `0.1.65-dev`), built on EC2, installed with
`scripts/push-assistant-apk.sh`. Smoke fixture: three-page local site
(index → events/contact links, plus `mailto:`/`javascript:` link traps
and script/style noise) served over `adb reverse tcp:8094`.

1. **Reviewed mode:** goal "Look at http://127.0.0.1:8094/index.html and
   tell me what time the pottery workshop is and what I should bring" →
   Medium "Approve browser.fetch_page" card (approved) → index fetch
   returned headings + links → second "Approve browser.fetch_page" card
   (approved) for the linked `/events.html` → `task.finished` with "The
   pottery workshop is on Saturday, June 13 at 10am in Studio B. The
   user should bring an apron; clay is provided. The cost is $12."
   Trajectory `20260610-120428-task-38255070697` shows the headings
   array (`Upcoming Events` / `Pottery Workshop` / `Community Potluck`)
   and the link-resolved events URL; `mailto:`/`javascript:` traps were
   excluded.
2. **YOLO mode:** goal "Fetch http://127.0.0.1:8094/index.html and tell
   me the front desk phone number. Follow links if needed." →
   auto-executed chained fetches across `index.html` → `contact.html`
   (events.html also probed) with no approval cards → `task.finished`
   with "The front desk phone number for the Northgate Community Center
   is 555-0188." Trajectory `20260610-120936-task-413263781573`.

Note: one YOLO goal submission produced no orchestrator response (no
trajectory written); an immediate identical resubmission worked
end-to-end. Logged as a transient to watch, not reproduced.

Cleanup: smoke servers and reverse forwards torn down, device reset to
`openphone_autonomy_mode=reviewed`.

### Phase C AI Sheet model-backed screen answers (2026-06-10)

**Implementation.** The AI Sheet's Screen / Summarize actions previously
answered with a purely local heuristic: `readScreenAnswer` in
`OpenPhoneAssistantService` called `getScreen` with
`include_screenshot:false, include_ui_tree:false` and then
`summarizeScreen(...)`, which concatenates up to six de-duplicated
accessibility `visible_text` fragments — heuristic-quality output and
exactly the under-engineering the spine-first plan exists to remove.
Sheet screen answers are now model-backed:

- When a model endpoint is configured, `readScreenAnswer` requests the
  screen with `include_screenshot:true, include_ui_tree:true,
  max_dimension:512, quality:65` (same shape as the chat screen-question
  path) and answers via
  `OpenAiResponsesAgentAdapter.answerScreenQuestion(prompt, screenJson)`
  — the Responses vision call, with a prompt suffix telling the model to
  ignore the assistant's own sheet overlay and answer about the app
  underneath.
- The sheet runs in the assistant service with no activity-held dev key,
  so `sheetModelEndpointConfig()` reads Settings.Secure
  `openphone_dev_openai_api_key` on `userdebug`/`eng` builds only — the
  same background-credential pattern as semantic watcher evaluation.
- The local `summarizeScreen` heuristic is retained **only** as the
  explicit unconfigured fallback (no key → metadata-only `getScreen`,
  local summary), not as a routing alternative.

No registry change was needed (this is the in-sheet answer surface, not
a model tool), so no `/system_ext/etc/openphone/*.json` push applied.

**Device acceptance: PASSED (2026-06-10, Pixel 9a).** Artifact
`.worktree/artifacts/tegu/OpenPhoneAssistant-sheet-screen-v1.apk`
(`sha256=cf7f57af32a6b524330980aee2b1e842128335d84c95fa17a43fc6580cac5bef`,
`versionCode=102` / `0.1.66-dev`), built on EC2, installed with
`scripts/push-assistant-apk.sh`. The device's saved Wi-Fi networks were
out of range after reboot, so device-side OpenAI reachability was
provided by a local HTTP CONNECT proxy
(`.worktree/smoke/sheet-screen/connect_proxy.py` on port 8123 +
`adb reverse tcp:8123` + Settings.Global `http_proxy=127.0.0.1:8123`),
torn down after the smoke.

1. **Clock app:** island swipe-down → AI Sheet → Screen →
   "Reading screen" → model answer card: "You're in the Android Clock
   app on the Alarm tab. There's one alarm set for 9:00 AM on Sun and
   Sat, with its toggle appearing off. A plus button is at the bottom to
   add a new alarm, and the bottom navigation shows Alarm, Clock, Timer,
   and Stopwatch." — correct screenshot-grounded detail (toggle state,
   FAB, tab bar) far beyond the old text-fragment concatenation.
2. **Settings home:** Refresh on the answer card → fresh capture →
   answer correctly listed the visible settings categories
   (Network & internet through Battery, "partially visible at the
   bottom") plus the status-bar clock — screenshot-grounded, not
   accessibility-text-only.
3. **Summarize action:** expanded-sheet Summarize over Settings →
   "The app underneath is Android Settings on the main Settings
   homepage. Visible options include: …" — the overlay-suppression
   prompt worked: the model answered about the app under the sheet, not
   the sheet itself.
4. **Framework audit evidence:** `/data/system/openphone/audit-log.json`
   shows `screen_capture` events with
   `detail=screenshot_jpeg_base64:228x512` for each model-backed sheet
   answer (task-scoped `screen.read.visible` grants).
5. **Unconfigured fallback:** after deleting the Secure key, Refresh
   produced the local heuristic answer ("Visible screen summary: Search
   Settings; Network & internet; …") and the corresponding audit entry
   reverted to `detail=metadata_only` — confirming no screenshot leaves
   the framework when the model path is unavailable, and no silent
   model/heuristic mixing.
6. **Failure path (organically exercised):** before connectivity was
   restored the sheet showed "Screen question failed: Unable to resolve
   host api.openai.com" — the configured-but-unreachable case surfaces
   the model error honestly instead of silently falling back to the
   heuristic.

This slice is sheet-surface work in reviewed mode only; the sheet's
Screen/Summarize answers perform no Medium-risk actions, so there is no
separate YOLO behavior to validate (autonomy mode does not gate
`screen.read.visible` task grants).

Cleanup: dev key deleted from Settings.Secure, `http_proxy` cleared,
reverse forwards removed, proxy process stopped, device left at
`openphone_autonomy_mode=reviewed`.

### Phase C message-reply watchers (2026-06-10)

**Implementation.** The master plan's "Remind me if Sarah does not
reply" scenario previously had no working trigger: the watcher runtime
supported only `web_change`, `time`, and `notification`, so any
message-type watcher failed as `unsupported_watcher_type`. The watcher
subsystem now has a first-class `message_reply` type:

- `FrameworkToolExecutor.normalizeWatcherArguments` maps
  source `message`/`messages`/`sms`/`text` (and type `message`/`sms`)
  to `type=message_reply`, normalizes `address`/`phone`/`sender`/`from`
  and `thread_id` into the condition, records a `baseline_ms`
  (defaulting to creation time) so only messages newer than the request
  count as replies, and passes through optional `deadline_at` and
  `notify_on` (`reply` default, or `no_reply`). Message watchers get the
  same `now+15s` first poll as web watchers.
- `OpenPhoneWatcherScheduler.fireWatcher` routes `message_reply` to
  `runMessageReplyWatcher`, which queries the SMS inbox
  (`Telephony.Sms.CONTENT_URI`, `TYPE=MESSAGE_TYPE_INBOX`,
  `DATE > baseline_ms`, optional thread filter) and matches the watched
  address via substring or `PhoneNumberUtils.compare`. Reply found →
  `markFired` with `message_reply:<id>:<date>`; with
  `notify_on=no_reply` the reminder is moot, so the watcher resolves
  silently. No reply and `deadline_at` passed → `notify_on=no_reply`
  fires the reminder notification, otherwise the watcher stops. No reply
  and no deadline → `markNoop` and repoll (default 5 min interval,
  deadline-clamped). Missing address+thread or SMS query errors go
  through the standard `failWatcher` backoff
  (`missing_message_watcher_target`, `messages_permission_denied:read`).
- The registry `watchers.create` schema documents `source=message`,
  `type=message_reply`, `address`, `thread_id`, `deadline_at`, and
  `notify_on`; both Realtime prompts and the orchestrator `watch` mode
  guidance describe the reply/no-reply forms. The model decides when to
  create these watchers — no keyword routing was added.

**Device acceptance: PASSED (2026-06-10, Pixel 9a).** Artifact
`.worktree/artifacts/tegu/OpenPhoneAssistant-message-reply-v1.apk`
(`sha256=f2fb71cd9fa189d4dc5780a42618efcc5d7ea370eb548dc2ae979d6f60ca9af6`,
`versionCode=103` / `0.1.67-dev`), built on EC2, installed with
`scripts/push-assistant-apk.sh`; registry/model-tools/capabilities
pushed to `/system_ext/etc/openphone/{action_registry,model_tools,capabilities}.json`
per the registry-push rule. The device has no SIM, so inbound replies
were simulated with root `content insert --uri content://sms/inbox`.

1. **Reviewed mode, reply alert:** goal "Tell me when +15550001111
   replies to my last text" → orchestrator `watch` mode → Medium
   "Approve watchers.create" card → approved → watcher id=11
   `type=message_reply` with `address`, `baseline_ms`,
   `notify_on=reply` (screenshots
   `msgreply-0-unlock.png`/`msgreply-2-approved.png`). Forced check
   before any reply → `message_reply:no_reply_yet` noop. Simulated
   inbound SMS, forced due check → `status=fired`,
   `last_result_hash=message_reply:2:1781091425100`, "Watcher fired /
   Reply from +15550001111" notification posted.
2. **YOLO mode, no-reply deadline:** goal "I texted +15550002222 about
   dinner. Remind me if they do not reply by <unix ms>" →
   `watcher_create` auto-executed with no approval card → watcher id=12
   with `deadline_at`, `notify_on=no_reply`, and `next_run_at` clamped
   to the deadline. No reply inserted; after the deadline the forced
   check fired `message_reply:no_reply_by_deadline:<deadline>` and
   posted "Remind if +15550002222 doesn't reply about dinner"
   (screenshot `msgreply-4-noreply-create.png`).
3. **Silent resolve:** seeded `notify_on=no_reply` watcher id=13 for
   +15550003333, then inserted a reply *before* the deadline → watcher
   `fired` with `message_reply:<id>:<date>` and zero notifications
   posted — the reminder correctly dissolved when the reply arrived.
4. **Failure rail:** seeded a `message_reply` watcher with an empty
   condition → `failWatcher` backoff with
   `missing_message_watcher_target`, `failure_count=1`, future
   `next_run_at` — no crash, no notification.

Cleanup: smoke watchers 11–14 soft-deleted, simulated SMS rows removed,
watcher notifications dismissed, dev key deleted from Settings.Secure,
device reset to `openphone_autonomy_mode=reviewed`.

### Phase C AI Island state machine + YOLO visual state (2026-06-10)

Implements the master plan's AI Island spec: the island is now a
canonical 8-state machine instead of ad-hoc status strings, with a
distinct visual treatment per state and an always-visible YOLO
indicator.

Implementation:

- `PointerOverlayController` (now `public`): replaced the legacy
  text-matching `setStatus(String)` with a canonical
  `setIslandState(state, detail)` API covering the plan's eight states
  — `idle`, `listening`, `thinking`, `answer_ready`, `action_running`,
  `watching`, `needs_review`, `error` — plus the existing `transcript`
  interstitial. Each state renders distinct island text/glyph/colors:
  idle "AI ◉" (teal), watching "AI ◎ N" (blue, N = active watcher
  count when >1), thinking "Thinking …" (blue), action_running
  "Talk/Stop" (red) with pointer layer + watchdog, answer_ready "OK ✓"
  (green, auto-decays to idle/watching after 2.2s), needs_review
  "Review !" (amber), error "Error !" (red), listening
  "Listening/Stop" (red). Tap targets per state: running/thinking →
  left=voice right=stop; listening → stop; needs_review/error → opens
  the assistant; idle/watching → left=AI sheet right=voice. The AI
  sheet status line mirrors all states incl. `mStateDetail` for
  review/error.
- YOLO visual state: `setYoloActive(boolean)` adds a 3px amber stroke
  (`0xffffd166`) around the island chip and a "⚡ " prefix on the state
  label in idle/watching/thinking/action_running. The backend pushes
  it from every `mAutonomyMode` change (compose toggle, defaults load,
  reload) and `OpenPhoneAssistantService` re-reads
  `Settings.Secure openphone_autonomy_mode` on create/start.
- Watching count: `OpenPhoneWatcherScheduler.scheduleNext` publishes
  `WatcherStore.active(50).size()` through the static
  `PointerOverlayController.publishWatchingCount`, so the island flips
  idle↔watching as watchers are created/fired/stopped.
- `AssistantActivityBackend.updateIsland` maps its existing status
  strings to canonical states via `islandStateForStatus` (model
  decides what runs; this mapping is presentation-only).
- Single-island invariant: `ensureIslandWindow` now removes any island
  owned by *other* controller instances (service vs activity) before
  adding its own window — fixes a leak where up to three stacked
  620x96 islands accumulated across force-stop/recreate cycles and a
  stale YOLO stroke could outlive a mode switch.

Artifacts: `OpenPhoneAssistant-island-states-v1.apk` (v104,
sha256 `7078f9f951972064f9db5eb5e51c99820bc6aead1be518ad4d7c1d6562e52e31`)
and the leak fix `OpenPhoneAssistant-island-states-v2.apk` (v105
0.1.69-dev, sha256
`a45c612fccc7e0382043bb12257e56f2ddbcc85c4a379c47b78b9c87158c03aa`,
installed). Screenshots under `.worktree/artifacts/tegu/screens/`
(`island-*.png`, `fast/montage.png`).

Device acceptance (Pixel 9a, v104 visuals + v105 invariant):

1. **Idle / watching / YOLO chrome:** reviewed idle island shows
   "AI ◉"; with 4 active watchers it renders "AI ◎ 4"; switching
   `openphone_autonomy_mode` to `yolo` adds the amber stroke + "⚡ "
   prefix (`island-1-yolo-watching.png`, `island-7-pure-idle.png`).
2. **Live state sequence:** a YOLO harness goal produced the full
   visible arc — "⚡ Thinking …" → "⚡ Talk/Stop" (action_running with
   pointer dot) → "OK ✓" → auto-decay back to "⚡ AI ◎ 4" — captured by
   a 0.5s screencap loop (`fast/montage.png`); a calculator goal that
   ended in review showed amber "Review !" (`island-6-running.png`).
3. **Error rail:** voice capture without a configured key landed the
   island in red "Error !" and tap opened the assistant
   (`island-2-error.png`).
4. **Single-island invariant (v105):** after
   activity launch → home → service refresh and a
   force-stop → service+activity recreate cycle, `dumpsys window`
   shows exactly one `org.openphone.assistant` overlay window (was 3
   on v104); YOLO→reviewed flip drops amber stroke pixels in the
   island region from 2812 to 0 with no stale chrome.

Cleanup: autonomy mode reset to `reviewed`, dev key deleted from
Settings.Secure, smoke screenshots removed from `/data/local/tmp`,
watcher table back to its pre-smoke census (4 active / 5 fired /
3 stopped), adb unrooted.

### Phase C calendar depth: update / delete / availability (2026-06-11)

Deepens the calendar connector beyond search/create: the model can now
move, change, or cancel existing events and answer free/busy questions,
and every model prompt carries the real device time so unix-ms windows
are computed instead of guessed.

Implementation (registry-driven — 3 JSON entries + 3 executor cases,
no schema duplication anywhere else):

- `openphone_action_registry.json` + `openphone_model_tools.json`:
  `calendar.update_event` / `calendar_update_event` (action, medium,
  `calendar.write`, confirm; partial updates of title/description/
  location/start_at/end_at/duration_minutes/all_day keyed by required
  `event_id`), `calendar.delete_event` / `calendar_delete_event`
  (action, **high**, new `calendar.delete` capability,
  `explicit_confirm`), and `calendar.check_availability` /
  `calendar_check_availability` (observe, medium, `calendar.read`,
  confirm; busy/free interval computation over a bounded window).
- `openphone_capabilities.json` + `PolicyEngine`: new `calendar.delete`
  capability at risk `high` / default `explicit_confirm`. Because
  `yoloAllows()` requires `confirm`+`medium`+yolo-eligible capability,
  YOLO auto-runs updates but **always** stops for deletes — a
  deliberate safety rail, not a keyword route.
- `FrameworkToolExecutor`: `calendarUpdateEvent` (before/after
  snapshots via `eventById`, preserved duration when only the start
  moves, `calendar_end_before_start` /
  `no_calendar_fields_to_update` errors), `calendarDeleteEvent`
  (snapshot then delete, returns the deleted event),
  `calendarCheckAvailability` (queries `CalendarContract.Instances`,
  skips `AVAILABILITY_FREE`, merges overlapping busy intervals, emits
  free gaps ≥ the requested slot, window clamped ≤31d). All calendar
  tool outputs now carry human-readable `start_local`/`end_local`
  alongside unix-ms — added after a v106 smoke showed the model
  misconverting epoch ms into wrong clock times.
- Device-time context: `deviceTimeContext()` ("EEE yyyy-MM-dd HH:mm
  zzz (unix_ms N)") is injected into the orchestrator prompt, the
  Responses multi-step agent prompt, and the Realtime
  `initialTaskPrompt`/instructions. Root cause evidence: a v107
  trajectory (`openphone-trajectories/.../events.jsonl`) showed the
  Realtime agent guessing `start_at=1778150400000` (weeks in the past)
  for "today", finding no events, and finishing with a false "Done".
  With v108 the same goals compute correct windows.
- `AssistantActivityBackend`: `calendar_check_availability` joins the
  read-only dry-run preview list; update/delete join the high-risk
  fallback action list; `capabilityForTool` maps the three new tools
  (delete → `calendar.delete`).

Artifacts: `OpenPhoneAssistant-calendar-depth-v1.apk` (v106, sha256
`615028952641e35e0c3557189bdd0b949100213ce70ceb851f03cf7eef8a2505`),
`-v2.apk` (v107 local-time fields, sha256
`67b46ce88a07d90845efc84ca1ba29351acb1cbd6a1b1d9640764d596fe67f60`),
`-v3.apk` (v108 0.1.72-dev device-time context, sha256
`62fcf0047ab2784e0d088e21d67f2ee56172ff25f3c19da288732cac29a5e3f3`,
installed). Screenshots `calendar-1-update-approval.png` …
`calendar-9-reviewed-time-move-done.png` under
`.worktree/artifacts/tegu/screens/`.

Device acceptance (Pixel 9a, seeded local-calendar fixtures, dev key
via harness intent only):

1. **Reviewed update:** "move my Dentist appointment one hour later"
   stopped at a "Risk: Medium / Approve calendar.update_event" card;
   after approval `content query` showed the event row moved
   (`calendar-1..3`).
2. **Availability:** "when am I free today for an hour?" ran
   `calendar_check_availability` behind confirm and (v108) answered
   exactly right in local time — busy 14:00–14:30 and 15:00–16:00,
   free gaps 08:00–14:00 and 16:00–18:00 (`calendar-6`).
3. **YOLO update auto-runs:** in `yolo` mode a rename goal executed
   `calendar_update_event` without an action approval card; the row's
   title changed ("Team standup (moved rooms)", `calendar-7`).
4. **YOLO delete blocked:** in the same mode a delete goal stopped at
   a "Risk: High / Approve calendar.delete_event" `explicit_confirm`
   card (`calendar-8-yolo-delete-blocked.png`); after manual approval
   the row was really deleted.
5. **Device-time correctness (v108):** reviewed goal "move my Team
   standup to 5:00pm local time, keep 30 minutes" computed
   `dtstart=1781186400000` / `dtend=1781188200000` (17:00–17:30
   Asia/Jerusalem, exactly right) and the row updated after the Medium
   approval (`calendar-9-reviewed-time-move-done.png`).

Cleanup: fixture calendar events deleted, autonomy mode reset to
`reviewed`, dev key confirmed null in Settings.Secure, adb unrooted.

### Phase C phone depth: missed-call follow-ups + call-back watchers (2026-06-11)

Deepens the phone connector: the model can filter the call log by type,
call-log rows join contact names even when the log's cached name is
empty, `phone_context` surfaces missed calls not yet returned, and a
new `call_back` watcher type closes the call-back loop ("remind me to
call her back", "tell me when she calls").

Implementation:

- `FrameworkToolExecutor.callsSearch`: new optional `type` argument
  (missed/incoming/outgoing/voicemail/rejected/blocked →
  `unknown_call_type` error otherwise); the free-text query now
  post-filters in Java instead of SQL LIKE so contact names join via
  `ContactsContract.PhoneLookup` when the cached name is empty and
  numbers match across formatting variants
  (`PhoneNumberUtils.compare` against the queried contact's numbers);
  rows carry `date_local`.
- `FrameworkToolExecutor.phoneContext`: new `missed_call_follow_ups`
  output — missed calls from a fresh unfiltered recent-log scan with
  no later call to/from or sent SMS to the same number — plus a
  summary suffix ("N missed call(s) not yet returned"). The follow-up
  scan is deliberately unfiltered because the user's free-text query
  rarely matches call-log rows (found on-device in v109, fixed v110).
- `OpenPhoneWatcherScheduler`: new `call_back` watcher type. Polls the
  call log for a call matching the condition number after
  `baseline_ms` (direction filter any/incoming/outgoing; missed counts
  as incoming). `notify_on=call` (default) alerts when the call
  appears; `notify_on=no_call` + `deadline_at` reminds only if no call
  happened in time and dissolves silently if one did — same
  semantics as message-reply watchers.
- `watcherCreate` normalization maps `source=call|calls|phone` and
  `type=call|call_back|callback`, hoisting number/direction/
  baseline_ms/deadline_at/notify_on into the stored condition.
- Registry/model_tools: `phone.search_calls` gains the `type` input,
  `phone.context` documents `missed_call_follow_ups`,
  `watchers.create` gains `number`/`direction` inputs and call
  guidance. Orchestrator watch-mode prompt and Realtime
  instructions/initial prompt teach the source=call watcher forms. No
  new capabilities: calls.read/watchers.write cover everything.

Artifacts: `OpenPhoneAssistant-phone-depth-v1.apk` (v109, sha256
`50bfef88183fa7cbe4de5cf5babc65c679902a132e7ad6b97937ea05512f5b04`)
and the follow-up-scan fix `-v2.apk` (v110 0.1.74-dev, sha256
`a5ca805bb278bfcda6f8d80c46fc8815c3ec6bfe6cfd79f11c7d025138f45ac8`,
installed); registry configs pushed. Screenshots
`phone-1-missed-follow-ups.png` … `phone-5-call-watcher-fired.png`.

Device acceptance (Pixel 9a, seeded call-log/contact fixtures: contact
"Sarah Levin" +972521234567 with a missed call and no cached name,
returned + unreturned calls from "Pizza Roma", unknown missed
+14155550100):

1. **Missed-call follow-ups (reviewed):** "which missed calls have I
   not returned yet?" ran `phone_context` behind a Medium confirm and
   answered exactly right: "You have 2 missed calls not yet returned:
   Sarah Levin (+972521234567) at Thu 2026-06-11 00:08, and
   +14155550100 at Wed 2026-06-10 22:08" — the returned Pizza Roma
   missed call correctly excluded, Sarah's name joined from contacts
   (`phone-1`).
2. **Type filter + contact join:** "did I miss any calls from Sarah
   today?" produced `calls_search` with `type=missed`, a correct
   device-time `since/until` window, and the name-joined answer
   "missed 1 call from Sarah Levin today at 00:08" (trajectory
   evidence + `phone-2`).
3. **No-call deadline reminder (reviewed):** "if Sarah has not called
   me back within 3 minutes, remind me to call her" stopped at the
   `watchers.create` Medium card, then stored a `call_back` watcher
   with `notify_on=no_call`, `direction=incoming`, the right
   `deadline_at`; at the deadline it fired
   (`call_back:no_call_by_deadline:…`) and posted the reminder
   notification (`phone-3`, `phone-4`).
4. **Call-arrival watcher (YOLO):** "tell me when Sarah calls" was
   auto-created in yolo mode without an approval card
   (`notify_on=call`); after inserting an incoming call from
   `0521234567` (different formatting than the stored
   `+972521234567`), the next check fired the watcher
   (`call_back:9:…` — `PhoneNumberUtils.compare` matched the
   variants) and posted the alert (`phone-5`).

Cleanup: call log, contact, SMS fixture rows and smoke watchers
deleted; watcher census back to 4 active; autonomy mode reset to
`reviewed`; dev key confirmed null; adb unrooted.

### Phase 10 hardening: OTA-safe store migrations (2026-06-11)

First slice of the Phase 10 promotion/hardening track. All four
assistant SQLite stores (`WatcherStore`, `CommitmentStore`,
`MemoryStore`, `ContextIndexStore`) previously had `DB_VERSION=1` with
a destructive `onUpgrade` (`DROP TABLE` + recreate) — any future schema
bump would have silently wiped durable user data (watchers,
commitments, memories, the context index) on OTA. This directly
violated the Phase 10 spec item "Add OTA-safe migrations for
context/memory/commitment databases".

Implementation (each store):

- `DB_VERSION` 1 → 2; `onUpgrade` rewritten to stepwise additive
  migrations (`if (oldVersion < 2) { … }`) with the invariant comment
  "Durable user data: migrations must be additive and stepwise, never
  DROP TABLE."
- Each v2 migration adds a genuinely useful index derived from that
  store's hot query pattern (also added to `onCreate` for fresh
  installs):
  - `watcher_type_idx ON watcher(type, status)` — `activeByType`
    drives message-reply/call-back baseline scans.
  - `commitment_updated_idx ON commitment(deleted_at, updated_at)` —
    FTS-join and active listings order by `updated_at` with
    `deleted_at IS NULL`.
  - `memory_normalized_idx ON memory(normalized_text)` — the dedupe
    lookup `SELECT id FROM memory WHERE normalized_text = ?` ran
    unindexed on every memory save (UNIQUE constraint exists, but the
    explicit index keeps the lookup path covered if the constraint is
    ever relaxed in a migration).
  - `context_event_recent_idx ON context_event(deleted_at,
    observed_at)` — every recents/search query filters
    `deleted_at IS NULL ORDER BY observed_at DESC`.

Artifact: `OpenPhoneAssistant-store-migrations-v1.apk` (v111
0.1.75-dev, sha256
`836386006f674184142de377861cc3582ebda4a0755449e84c6e4c46cd74baad`,
installed). No registry/config changes.

Device acceptance (Pixel 9a, real upgrade path v110→v111 over live v1
databases):

1. Pre-push under v110: `PRAGMA user_version` = 1 in all four DBs;
   marker rows (`MIGRATION_MARKER_V1`) inserted into each table
   alongside real data (13 watcher rows, 1271 context events).
2. Pushed v111 (device reboot), exercised each store (watcher check
   broadcast; an assistant memory-search task for the lazily-opened
   `MemoryStore`): `PRAGMA user_version` = 2 in all four DBs — the
   stepwise `onUpgrade` ran in place, no recreate.
3. All four marker rows survived; row counts preserved (13 watchers;
   context index grew 1271 → 1282 from the new session's own events —
   nothing lost); all four new indices present in `sqlite_master`;
   FTS still functional post-migration (`watcher_fts MATCH` found the
   marker before cleanup).

Cleanup: marker rows deleted; dev key confirmed null; autonomy mode
`reviewed`; adb unrooted.

## Not Yet Implemented

- Hardware validation on the Pixel 9a. Full OpenPhone now boots and the
  assistant/framework service are verified, and a repeatable smoke-test report
  script exists, but Wi-Fi, cellular, camera, microphone, GPS, fingerprint,
  encryption, and reboot stability still need physical pass/fail evidence.
- Fully reproducible device-specific flashable image generation. The Pixel 9a
  build path now has first automation for extracting the known-good DTB before
  target-files/OTA-producing goals and verifying generated
  `vendor_kernel_boot.img` afterward, and first private release-signing
  workspace/signing wrapper support exists. Actually producing and validating a
  signed release OTA and clean-room reproducibility still need work.
- Typed framework parcelables for screen context, action requests, policy
  decisions, and audit events. The current Binder contract intentionally uses
  JSON strings while the service boundary is still stabilizing.
- Full screen understanding service with OCR, notifications, content-provider
  image references, framework-owned UI hierarchy, and scoped data
  minimization. Current screen observation supports foreground/visible activity
  metadata, opt-in JPEG screenshot payloads, and a first assistant-side
  accessibility UI tree for visible text and interactive elements.
- Production model transport. A development OpenAI Responses vision adapter is
  physically validated and the assistant now has a first broker/proxy transport
  option plus a reference broker with signed session-token minting and a first
  provider/model registry and device-subject registry, but a production build
  still needs stronger device attestation, retry policy, stronger rate limits,
  and stronger privacy controls.
- Vision-based action selection. The development OpenAI adapter can now run a
  bounded screenshot/action loop, but this remains a dev path that needs
  stronger physical eval evidence, production transport, and safer confirmation
  UX before it is release-grade.
- Full action execution service for notification actions, app-specific
  integrations, and richer input targeting. Current framework/assistant action
  execution supports launcher actions, navigation keys, pointer gestures,
  scroll gestures, keyboard text, clipboard text actions, confirmed share
  chooser launch, and first semantic accessibility-element targeting through
  `tap_element` / `long_press_element`, but does not yet have notification
  integrations or production-grade app-specific actions.
- Full confirmation UX and grant lifecycle for medium/high-risk capabilities.
  A basic assistant approve/deny path exists for pending actions, but there is
  no system modal, timeout, per-app grant editor, or high-friction payment/
  messaging flow.
- Full Settings-hosted per-app/per-capability grant editor. Settings now
  exposes top-level OpenPhone, task-grant, and audit pages, and it can edit
  global durable task-grant defaults. Per-app/per-capability policy remains a
  seed plus development override contract until the core agent loop is
  physically stronger.
- SystemUI background task surface. A first native `openphone_agent` Quick
  Settings tile is implemented and builds; it shows agent availability, opens
  the assistant when idle, and stops the active framework task when running.
  The assistant-owned island/cursor overlay exists for development and active
  task feedback, but a production SystemUI-owned status-bar/dynamic-island
  surface remains pending.
- Remaining SELinux policy for richer action execution and future services.
- On-device OTA client and actual signed release artifact validation.
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

Post-plan implementation evidence on the EC2 Linux build host:

- `./scripts/check.sh` passes after adding the assistant task console,
  notification trigger, Quick Settings tile, model adapter boundary, cursor
  overlay, and framework task-screen/pointer APIs.
- `OPENPHONE_RELEASE=bp4a OPENPHONE_BUILD_GOAL=OpenPhoneAssistant ./scripts/build.sh openphone_tegu`
  generates:
  `out/target/product/tegu/system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk`.
- `OPENPHONE_RELEASE=bp4a OPENPHONE_BUILD_GOAL=MODULES-IN-frameworks-base-services-core ./scripts/build.sh openphone_tegu`
  validates the new framework manager/service API additions, including the
  opt-in screenshot payload path.
- Built v44 development OTA after adding retry/failure handling, exposing
  bounded `watch_screen` as a model tool, adding the assistant-owned
  confirmation review surface, and improving the assistant-owned cursor overlay
  with action labels, tap ripples, long-press emphasis, swipe trails, and a
  typing indicator:
  `.worktree/artifacts/tegu/openphone_tegu-action-overlay-v44-ota.zip`.
- v44 OTA SHA-256:
  `b439308f518e2fa30ffc7d33ed923b4961b323ca02e9d6911f75ca62874781b5`.
- v44 assistant APK metadata from the EC2 output tree:
  `versionCode=44`, `versionName=0.1.8-dev`.
- v44 assistant APK SHA-256:
  `d248cfd7d439d0c0c7cbdc5c14c00d80eca3aec7cddb1cbd5c715c4e00330e54`.
- v44 physical Pixel 9a sideload/runtime validation is still pending because
  the current post-wipe device state lists over ADB but closes `adb shell`
  immediately.
- Built v45 development OTA after adding assistant-owned redacted framework
  audit evidence export to `Downloads/OpenPhone`:
  `.worktree/artifacts/tegu/openphone_tegu-audit-export-v45-ota.zip`.
- v45 OTA SHA-256:
  `9602bb786d81bf412fe2115f62448cc86e56061f7f491b26db3a666e9c6a4111`.
- v45 assistant APK metadata from the EC2 output tree:
  `versionCode=45`, `versionName=0.1.9-dev`.
- v45 assistant APK SHA-256:
  `89e5fee729cff2fab1b6d87598d3fc942e2d90fbb8c2683e2fee7dec9fc7a5ee`.
- v45 OTA zip integrity check passes locally with `unzip -tq`.
- v45 physical Pixel 9a sideload/runtime validation is still pending because
  the current post-wipe device state lists over ADB but closes `adb shell`
  immediately.
- Built v46 development OTA after adding the deterministic risky-action
  pre-execution guardrail to the OpenAI development loop:
  `.worktree/artifacts/tegu/openphone_tegu-risk-guardrail-v46-ota.zip`.
- v46 OTA SHA-256:
  `15fefaac1139867e733edd53f4215a9d2ac9bd1c8d19234dbd53e9be094c1421`.
- v46 assistant APK metadata from the EC2 output tree:
  `versionCode=46`, `versionName=0.1.10-dev`.
- v46 assistant APK SHA-256:
  `ca35d8020d7ddc11b0d6c06f288472691d7ef1630f004c299bf5cd8a72d048de`.
- v46 OTA zip integrity check passes locally with `unzip -tq`.
- v46 physical Pixel 9a sideload/runtime validation is still pending because
  the current post-wipe device state lists over ADB but closes `adb shell`
  immediately.
- Built v47 development OTA after adding assistant-owned per-task grant
  controls for input/navigation, screenshot capture, clipboard, sharing, and
  web links, plus local pre-framework denial when a model tool exceeds the
  selected task grants:
  `.worktree/artifacts/tegu/openphone_tegu-task-grants-v47-ota.zip`.
- v47 OTA SHA-256:
  `798124756751aa5f164393fcdabd1c02e97870382269f0729b6f89e6ee823d47`.
- v47 assistant APK metadata from the EC2 output tree:
  `versionCode=47`, `versionName=0.1.11-dev`.
- v47 assistant APK SHA-256:
  `fad48c8d6ef484cccbe377bd3cfbf1e72fa8e490684117ee17b0bb2c3afb6122`.
- v47 OTA zip integrity check passes locally with `unzip -tq`.
- v47 physical Pixel 9a sideload/runtime validation is still pending because
  the current device state lists over ADB but closes `adb shell` immediately.
- Added `patches/packages_apps_Settings/0001-OpenPhone-add-About-phone-version-surface.patch`
  so Settings/About phone exposes OpenPhone identity in normal UI:
  - `OpenPhone version`, backed by `ro.openphone.version`
  - `OpenPhone support`, linking to the public project repository
- Focused `Settings` module build passed on the EC2 Linux Android tree and
  generated:
  `out/target/product/tegu/system_ext/priv-app/Settings/Settings.apk`.
- Settings APK SHA-256:
  `e7ab8fb153177e4e41710a3165b9e83b8a04efb01cd09689862c88ef57516a48`.
- Built v48 development OTA after adding the Settings/About OpenPhone identity
  surface:
  `.worktree/artifacts/tegu/openphone_tegu-settings-about-v48-ota.zip`.
- v48 OTA SHA-256:
  `cdb717dc27bca5786c85cd7dd3cf9a3e43092fb6fa1077ee64df84958b289cbf`.
- v48 assistant APK metadata from the EC2 output tree remains:
  `versionCode=47`, `versionName=0.1.11-dev`.
- v48 assistant APK SHA-256:
  `fad48c8d6ef484cccbe377bd3cfbf1e72fa8e490684117ee17b0bb2c3afb6122`.
- v48 OTA zip integrity check passes locally with `unzip -tq`.
- v48 physical Pixel 9a sideload/runtime validation is pending; specifically,
  the Settings/About row still needs to be verified on-device.
- Added `patches/packages_apps_Settings/0002-OpenPhone-add-settings-dashboard.patch`
  so Settings exposes OpenPhone as a top-level page with rows for:
  - Assistant
  - Task grants
  - Audit evidence
  - OpenPhone support
- Focused `Settings` module build passed on the EC2 Linux Android tree after
  adding the OpenPhone dashboard patch.
- Built v49 development OTA after adding the Settings homepage OpenPhone
  dashboard:
  `.worktree/artifacts/tegu/openphone_tegu-settings-dashboard-v49-ota.zip`.
- v49 OTA SHA-256:
  `6fcd646f90f83d954924b86e4ab421e41ce1ad57cbbac9835ed0be901e438f83`.
- v49 Settings APK SHA-256:
  `376e16651b2e07e4e3b69f086a40bc0905fd77baec5f48fe47a406bfe0cac958`.
- v49 assistant APK metadata from the EC2 output tree remains:
  `versionCode=47`, `versionName=0.1.11-dev`.
- v49 assistant APK SHA-256:
  `fad48c8d6ef484cccbe377bd3cfbf1e72fa8e490684117ee17b0bb2c3afb6122`.
- v49 OTA zip integrity check passes locally with `unzip -tq`.
- v49 physical Pixel 9a sideload/runtime validation is pending; ADB still lists
  the device but closes shell sessions with `error: closed`.
- Added `patches/packages_apps_Settings/0003-OpenPhone-add-Settings-hosted-audit-and-grant-pages.patch`
  so Settings hosts dedicated OpenPhone subpages:
  - Task grants explains active-task-scoped input/navigation, screenshot,
    clipboard, sharing, and link grants.
  - Audit evidence reads `OpenPhoneAgentManager.getServiceStatus()` and
    `getAuditLog(10)` directly from Settings, with assistant export retained as
    a handoff for writing evidence files.
- Focused `Settings` module build passed on the EC2 Linux Android tree after
  adding the Settings-hosted audit/grant pages.
- Built v50 development OTA after adding the Settings-hosted audit/grant pages:
  `.worktree/artifacts/tegu/openphone_tegu-settings-audit-grants-v50-ota.zip`.
- v50 OTA SHA-256:
  `29302a533e25a97dbfc856c37d13b1fb30b8125a53af12126bd929fb1bdb13f8`.
- v50 Settings APK SHA-256:
  `a10f539872d5ed9afda1519a8c2675c4860c6ab7b42d2b1e60dd6639dcddfd75`.
- v50 assistant APK metadata from the EC2 output tree remains:
  `versionCode=47`, `versionName=0.1.11-dev`.
- v50 assistant APK SHA-256:
  `fad48c8d6ef484cccbe377bd3cfbf1e72fa8e490684117ee17b0bb2c3afb6122`.
- v50 OTA zip integrity check passes locally with `unzip -tq`.
- v50 physical Pixel 9a sideload/runtime validation is pending; ADB still lists
  the device but closes shell sessions with `error: closed`.
- Updated `patches/system_sepolicy/0001-OpenPhone-label-agent-manager-service.patch`
  after the full Pixel 9a build exposed release hygiene gates. The OpenPhone
  service label is now private platform policy, avoiding public SELinux API
  freeze/compat failures, and `openphone_agent` is listed in
  `service_fuzzer_bindings.go` with `EXCEPTION_NO_FUZZER` until the Java
  service has a dedicated fuzz target.
- Built v51 target-files and OTA on the EC2 Linux host after adding model
  disclosure metadata and the sepolicy hygiene fix:
  `.worktree/artifacts/tegu/openphone_tegu-model-disclosure-sepolicy-v51-ota.zip`.
- v51 OTA SHA-256:
  `b93db84907523b8e37816abab0a315b1f62b82321755612e82d057fd8f80e866`.
- v51 assistant APK metadata from the EC2 output tree:
  `versionCode=48`, `versionName=0.1.12-dev`.
- v51 assistant APK SHA-256:
  `bf3120926a087fb8c9e29acf910b7027e446c57737c678e79a15581549fae681`.
- v51 Settings APK SHA-256 remains:
  `a10f539872d5ed9afda1519a8c2675c4860c6ab7b42d2b1e60dd6639dcddfd75`.
- v51 OTA zip integrity check passes locally with `unzip -tq`.
- v51 physical Pixel 9a sideload/runtime validation is pending; ADB still lists
  the device but closes shell sessions with `error: closed`.
- v52 SystemUI agent tile build evidence:
  - EC2 focused `SystemUI` build passed for `openphone_tegu-bp4a-userdebug`.
  - EC2 full `otapackage` build passed for `openphone_tegu-bp4a-userdebug`.
  - Local OTA staged at
    `.worktree/artifacts/tegu/openphone_tegu-systemui-agent-tile-v52-ota.zip`.
  - OTA SHA-256:
    `97a08c5bceb062f53769988b432d64aebd51cf5e9217c9eb9db55d076b38f2b2`.
  - `unzip -tq` passed for the local OTA copy.
  - Added `patches/frameworks_base/0011-OpenPhone-add-SystemUI-agent-QS-tile.patch`.
  - Physical sideload/runtime validation is pending. Earlier post-wipe checks
    reached an `adb shell` `error: closed` state; the latest local retry does
    not enumerate the Pixel over USB at all.
- v53 assistant broker transport evidence:
  - Added assistant-side `ModelEndpointConfig` and broker/proxy mode for
    `/v1/responses` and `/v1/audio/transcriptions` request shapes.
  - Assistant direct OpenAI mode remains available for development.
  - EC2 focused `OpenPhoneAssistant` build passed for
    `openphone_tegu-bp4a-userdebug`.
  - EC2 full `otapackage` build passed for `openphone_tegu-bp4a-userdebug`.
  - Local OTA staged at
    `.worktree/artifacts/tegu/openphone_tegu-broker-systemui-v53-ota.zip`.
  - OTA SHA-256:
    `f72000529942ab728512a8c8a49a7e42ed311c0db861c79237abdbd5bab25a9a`.
  - `unzip -tq` passed for both the EC2 OTA and local OTA copy.
  - EC2 APK metadata:
    `versionCode=49`, `versionName=0.1.13-dev`.
  - EC2 APK SHA-256:
    `e5dd7d5cc26c052aa792670142f3c1e24bedc5ccf5eeedd3413f005aee2d5020`.
  - Added `services/model-broker/openphone_model_broker.py`; local repo checks
    compile it with `python3 -m py_compile`.
  - Local broker smoke test passed for `GET /healthz`, unauthorized
    `/v1/responses` rejection, signed-token minting/authentication, and
    `/v1/audio/transcriptions` content-type validation.
  - Local broker hardening smoke test passed for malformed JSON rejection,
    disallowed response-model rejection, and JSONL audit event writing without
    request bodies.
  - Added `scripts/smoke-test-model-broker.sh` and wired it into
    `scripts/check.sh` so the broker smoke coverage runs as part of normal repo
    validation.
  - Added admin-authenticated `POST /v1/session_tokens` to the broker so
    hosted development services can mint signed, expiring device/session tokens
    through HTTP instead of using only the CLI helper. The smoke test now
    verifies unauthorized issuer rejection and successful issuer minting before
    using the minted token against model endpoints.
  - Added `services/model-broker/providers.example.json` and registry loading
    through `OPENPHONE_BROKER_PROVIDER_REGISTRY`; the broker smoke test now
    uses the registry-backed model allowlist before provider forwarding.
  - Added `services/model-broker/devices.example.json` and registry loading
    through `OPENPHONE_BROKER_DEVICE_REGISTRY`; when configured, the token
    issuer rejects unknown subjects before minting session tokens. Registry
    entries can also reference a per-subject attestation secret env var; when
    present, `/v1/session_tokens` requires a fresh HMAC proof from that
    subject before minting. The broker smoke test now verifies allowed,
    rejected, missing-attestation, and invalid-attestation paths.
  - Added first-pass deployment artifacts under
    `services/model-broker/deploy/`: hardened systemd unit, environment
    template, and deployment README for running the broker as a restricted
    Linux service behind TLS.
  - Added `services/model-broker/deploy/nginx-openphone-model-broker.conf`, an
    nginx HTTPS reverse-proxy template that redirects HTTP, exposes `/healthz`
    and `/v1/`, sets no-store/security headers, aligns body-size limits, and
    proxies to the localhost-bound broker.
  - Added `scripts/rotate-model-broker-secrets.sh`, which can print fresh
    broker secrets or atomically rotate `OPENPHONE_BROKER_TOKEN_SECRET` and
    `OPENPHONE_BROKER_ADMIN_TOKENS` in a deployed env file while preserving
    provider keys. The helper can also rotate `OPENAI_API_KEY` while
    preserving broker token/admin secrets. `scripts/check.sh` validates both
    modes against temporary env files.
  - Added `scripts/setup-model-broker-tls.sh`, which renders the nginx broker
    TLS template for a domain/email and prints or applies the certbot/nginx
    commands for certificate issuance and renewal dry-run validation.
    `scripts/check.sh` validates the render path.
  - Added `scripts/prepare-release-signing.sh`, which creates a private
    release-signing workspace outside the repository with a `.gitignore`,
    `README.md`, and Android releasetools key map. `scripts/check.sh` validates
    the helper against a temporary directory.
  - Added `scripts/sign-release-ota.sh`, a private-build-environment wrapper
    around Android `sign_target_files_apks` and `ota_from_target_files`.
    It refuses in-repo key directories, verifies required key material before
    real signing, writes a signed OTA checksum, and has a `--dry-run` mode
    covered by `scripts/check.sh`.
  - Added `docs/contracts/ota-feed.schema.json`,
    `scripts/generate-ota-feed.sh`, and `scripts/validate-ota-feed.sh` for the
    first server-side OTA feed contract. `scripts/check.sh` validates feed
    generation and local artifact matching against a temporary OTA file.
  - Added first assistant-side sensitive-screen handling for the accessibility
    UI-tree path. Password fields are redacted in model-visible UI-tree
    context; password/payment/account-like risk flags cause screenshot capture
    tools to return `screen.blocked` instead of a base64 screenshot.
  - Added repo validation that `PolicyEngine` stays in sync with
    `openphone_capabilities.json`, and fixed the missing `share.content`
    high-risk mapping in the assistant fallback policy.
  - Added `overlay/vendor/openphone/config/openphone_model_tools.json` and
    `docs/contracts/model-tool.schema.json`. Repo checks now verify every
    registered model tool maps to a known capability and is covered by
    `FrameworkToolExecutor` plus the OpenAI adapter's allowed/terminal tool
    handling. Fixed stale OpenAI adapter capability IDs for share and text
    input.
  - Added `patches/frameworks_base/0012-OpenPhone-add-mediated-open-url-action.patch`
    so model web-link launches are mediated by `system_server`, require the
    `network.use` capability path, and write framework audit events. The
    assistant tool executor no longer starts web intents directly.
  - Extended repo checks so assistant-emitted framework action types must be
    listed in `docs/contracts/action-request.schema.json` and present in the
    framework patch stack with expected capability mappings.
  - Enforced `requires_reason` from the model-tool contract in
    `FrameworkToolExecutor`; local heuristic and OpenAI development paths now
    send model-visible reasons for `get_screen`, app/web launches, confirmation
    requests, and other reason-required tools.
  - Expanded `docs/contracts/audit-event.schema.json` to cover framework screen
    capture/watch and task stop events, and added CI validation that the schema
    stays aligned with framework `recordAudit(...)` event names.
  - Added `docs/contracts/trajectory-event.schema.json` and schema markers for
    assistant trajectory JSONL events. CI validates that trajectory recorder
    event names stay aligned with the contract.
  - Added `scripts/validate-trajectory-export.sh` for exported assistant trace
    evidence. It checks trajectory JSONL schema markers, event order, required
    task/result events, screenshot file references, and obvious secret/raw
    base64 leakage. `scripts/check.sh` exercises both directory and zip inputs.
  - Added `docs/contracts/audit-evidence.schema.json` and
    `scripts/validate-audit-evidence-export.sh` for framework audit evidence
    exports. The validator checks the schema marker, service status, audit event
    names, redaction, and obvious secret/raw-base64 leakage.
  - Expanded `docs/contracts/screen-context.schema.json` to cover the
    assistant accessibility UI-tree snapshot shape, and added CI checks for key
    emitted root/window/element fields plus sensitive-screen risk flags.
  - Physical sideload/runtime validation is pending. Earlier post-wipe checks
    reached an `adb shell` `error: closed` state; the latest local retry does
    not enumerate the Pixel over USB at all.
- v54 persisted assistant task-grant defaults evidence:
  - Added assistant app-private persistence for input/navigation, screenshot,
    clipboard, share-sheet, and web-link grant defaults.
  - Assistant task requests now include `grant_defaults_source` so trajectory
    and framework evidence can distinguish persisted defaults from one-off UI
    state.
  - EC2 focused `OpenPhoneAssistant` build passed for
    `openphone_tegu-bp4a-userdebug`.
  - EC2 full `otapackage` build passed for `openphone_tegu-bp4a-userdebug`.
  - EC2 APK metadata:
    `versionCode=50`, `versionName=0.1.14-dev`.
  - EC2 APK SHA-256:
    `5205949d1c6060fdecb25c88bd28cff4f02aa6daf3b86b5a87a3c5c0981fb191`.
  - Local OTA staged at
    `.worktree/artifacts/tegu/openphone_tegu-persisted-grants-v54-ota.zip`.
  - OTA SHA-256:
    `23dcd90f8ab2d532fe5732311a6828a1a6165f64f173881d73ef65255f226132`.
  - `unzip -tq` passed for both the EC2 OTA and local OTA copy.
  - Local APK staged at
    `.worktree/artifacts/tegu/OpenPhoneAssistant-persisted-grants-v54.apk`.
  - Physical install/runtime validation is pending while ADB service channels
    still close with `error: closed`.
- v55 Settings-owned durable task-grant defaults evidence:
  - Added `patches/packages_apps_Settings/0004-OpenPhone-add-durable-task-grant-defaults.patch`.
  - Settings task grants are now editable switches backed by `Settings.Secure`
    keys:
    `openphone_task_grant_input`,
    `openphone_task_grant_screenshot`,
    `openphone_task_grant_clipboard`,
    `openphone_task_grant_share`, and
    `openphone_task_grant_network`.
  - The assistant reads the same secure keys, keeps app-private fallback for
    migration/development, and marks new task requests with
    `grant_defaults_source=settings_secure`.
  - EC2 focused `Settings OpenPhoneAssistant` build passed for
    `openphone_tegu-bp4a-userdebug`.
  - EC2 APK metadata:
    `versionCode=51`, `versionName=0.1.15-dev`.
  - EC2 assistant APK SHA-256:
    `311d6bab821573b1654ddb73bf33278937d483efac11f2ee0c8e474688fa9027`.
  - EC2 Settings APK SHA-256:
    `d4e296a5af8742c211ad54c5ac025bd8e5326d76af9f10ae03ffb3ccbe7d0c4e`.
  - EC2 full `otapackage` build passed for `openphone_tegu-bp4a-userdebug`.
  - Local OTA staged at
    `.worktree/artifacts/tegu/openphone_tegu-settings-grants-v55-ota.zip`.
  - OTA SHA-256:
    `c2f08cad2b5247eb88982c4799901fb5f70d451ffde8cb3fde0e0b463f95a443`.
  - `unzip -tq` passed for the local OTA copy.
  - Current clean v0.0.1 preview staging was regenerated from this OTA and
    passed `scripts/validate-release-artifacts.sh`.
  - Physical install/runtime validation is pending. After onboarding and USB
    debugging were re-enabled locally, the device previously reported as
    `device` while `adb shell` returned `error: closed`; the latest local retry
    no longer enumerates the Pixel over USB, so cable/port/device USB state must
    be recovered first.
- v56 preview OTA client implementation evidence:
  - Added `OtaUpdateClient` to the privileged assistant app.
  - The assistant Advanced panel now has a Preview OTA Updates surface with OTA
    feed URL input, feed check, and verified download actions.
  - Feed checks require `schema_version=1`, target the current `Build.DEVICE`,
    and parse the first update from `docs/contracts/ota-feed.schema.json`.
  - OTA downloads write to `Downloads/OpenPhone` through `MediaStore`, remain
    pending until complete, and are deleted if the downloaded size or SHA-256
    does not match the feed.
  - Installation is intentionally still manual for the preview; recovery
    sideload or the host flashing flow remains the supported installation path.
  - Assistant package metadata is bumped to `versionCode=52`,
    `versionName=0.1.16-dev`.
  - Physical install/runtime validation is pending until host USB/ADB
    enumeration is recovered.

Post-plan physical Pixel 9a evidence:

- Built fresh target-files and OTA on the EC2 Linux host:
  `.worktree/artifacts/tegu/openphone_tegu-bp4a-assistant-v1-ota.zip`.
- OTA SHA-256:
  `25983a9f3099e9493c94f4d78b2eb81140ad99688493e8a2e3a8dca4cf2096a5`.
- Validated generated `vendor_kernel_boot.img` DTB before sideload:
  `dtb size: 1546258`.
- Sideloaded the OTA to the physical Pixel 9a and booted successfully.
- Verified over ADB after boot:
  - `ro.openphone.version=0.1.0-dev`
  - `ro.lineage.version=23.2-20260531-UNOFFICIAL-tegu`
  - `service check openphone_agent` reports `found`
  - `org.openphone.assistant/.OpenPhoneQuickSettingsTileService` is registered
  - assistant has new `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `INTERNET`,
    and `RECORD_AUDIO` permissions
  - persistent OpenPhone notification posts with `Stop` and `Open` actions
- Verified assistant UI task flow on-device:
  - `Start` creates a framework task with `screen.read.visible` and
    `tasks.observe`
  - `Screen` calls the new `getScreen(...)` framework API and returns
    `screen.captured.metadata_only` with foreground app/activity metadata
  - `Run Agent` with goal `settings` executes the local heuristic model loop
    and opens `com.android.settings/.Settings`

Compose cutover physical Pixel 9a evidence:

- Migrated the privileged `OpenPhoneAssistant` activity UI from programmatic
  Java Views to Jetpack Compose / Material 3 while preserving the Java backend
  for task, agent, voice, OTA, grant, confirmation, trajectory, audit, service,
  notification, quick settings, and accessibility behavior.
- `MainActivity.java` and `GlassPanel.java` are removed. `MainActivity.kt`
  subclasses `AssistantActivityBackend.java`; Compose owns the Chat and
  Advanced rendering through `AssistantComposeHost.kt`.
- Assistant package metadata is bumped to `versionCode=58`,
  `versionName=0.1.22-dev`.
- `./scripts/check.sh` passed from the repo root.
- Built the focused assistant APK on the EC2 Linux Android build host, not on
  the local machine, using the `openphone_tegu-bp4a-userdebug` build graph.
- Final EC2-built APK:
  `.worktree/artifacts/tegu/OpenPhoneAssistant-compose.apk`.
- Final APK SHA-256:
  `71836a3a86d10a959210a449b91816d74725fee904ec6135beaeb08caf6366b5`.
- Pushed the APK to the physical Pixel 9a with
  `scripts/push-assistant-apk.sh`; after reboot,
  `/system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk` had the same
  SHA-256 and PackageManager reported `versionCode=58`,
  `versionName=0.1.22-dev`.
- Verified `service check openphone_agent` reports `found`.
- UI smoke evidence under `.worktree/reports/compose-smoke/` verifies:
  - chat-style home screen opens with ComposeView content;
  - profile icon opens a Material 3 `Developer settings` dropdown;
  - composer focus opens the IME and shows send-state text;
  - header action opens Advanced / Developer settings;
  - hardware Back from Advanced returns to Chat;
  - final logcat/process checks have no assistant fatal crash or ANR entries.
- Required agent evals passed on the physical Pixel 9a with the development
  OpenAI Responses path:
  - `scripts/run-assistant-task.sh --goal "Open Settings." --wait 90` ended
    with focus on `com.android.settings/.Settings`; trajectory
    `.worktree/evals/compose-open-settings-v58-final2/20260607-183026-task-121301688290`
    validates and records `task.finished` with `open_app` and `finish_task`.
  - `scripts/run-assistant-task.sh --goal "Open Settings, open the Apps settings page, then finish when the Apps page is visible." --wait 120`
    ended with focus on `com.android.settings/.SubSettings` and visible Apps
    page content. Validated eval report:
    `.worktree/evals/compose-apps-settings-v58-final2-20260607T153300Z/agent-eval.json`;
    trajectory records `task.finished` with `open_app`, `tap_element`, and
    `finish_task`.

Additional Pixel 9a screenshot and OpenAI evidence:

- Built and sideloaded screenshot-fix OTA:
  `.worktree/artifacts/tegu/openphone_tegu-openai-vision-test-screenshotfix2-ota.zip`.
- OTA SHA-256:
  `65dedb7fe4abad5ca9d4edec4a4e24c54336cb38a3318f84f4eec7e68c2bee40`.
- Verified `getScreen(..., {"include_screenshot": true})` on the physical
  Pixel 9a from the assistant `Shot` button.
- The returned screen payload included a downscaled JPEG screenshot:
  `width=228`, `height=512`, `quality=65`, and redacted UI display
  `<base64 chars=24208>`.
- Fixed screenshot capture permission failure by applying
  `patches/frameworks_base/0010-OpenPhone-capture-screenshots-as-system-server.patch`.
- Built and sideloaded background-thread OpenAI OTA:
  `.worktree/artifacts/tegu/openphone_tegu-openai-bgthread-ota.zip`.
- OTA SHA-256:
  `1792d9bcf904146d3de17cf10ecb66e362ca100b19d54ca56b8e1c4cead3a32c`.
- Fixed `NetworkOnMainThreadException` by running model calls on an assistant
  background thread.
- Verified OpenAI Responses vision path on the physical Pixel 9a over Wi-Fi:
  - `status=model_response`
  - `provider=openai-responses-vision-dev`
  - `model=gpt-4.1-mini`
  - OpenAI response id:
    `resp_072056aadeb3322d006a1c286f5eb8819fba6ebcf3cd3fed20`
  - Model output described the visible OpenPhone Assistant screen from the
    screenshot and suggested a next safe action.
- Built and sideloaded agent-loop OTA:
  `.worktree/artifacts/tegu/openphone_tegu-agent-loop-ota.zip`.
- OTA SHA-256:
  `4839a81c151f2bbff1c3218389c69f2e405196e8db201b0e834e684f98b82016`.
- Verified closed-loop model/tool execution on the physical Pixel 9a:
  - goal: `Open Settings and finish when Settings is visible`
  - model step 1 selected `open_app`
  - framework executed `apps.launch` for `com.android.settings`
  - model step 2 saw `foreground_app=com.android.settings`
  - model step 2 selected `finish_task`
  - final status: `task.finished`

Pixel 9a build reproducibility evidence:

- Added `scripts/build.sh` automation for `openphone_tegu` and
  `openphone_tegu_smoke` target-files/OTA-producing goals:
  - prepares `device/google/tegu-kernels/6.1/tegu.dtb` from the upstream
    prebuilt `vendor_kernel_boot.img`
  - verifies generated `vendor_kernel_boot.img` contains a non-empty DTB with
    SHA-256
    `f1aed2bc4c07d3cb1e610f5227a566f22e995dfe05341ca6bf14805be6928688`
- Added manual helpers:
  - `scripts/prepare-tegu-dtb.sh`
  - `scripts/verify-tegu-bootchain.sh`
- Validated on the EC2 Linux Android tree:
  - `./scripts/check.sh`
  - `OPENPHONE_ANDROID_DIR=$PWD/.worktree/android ./scripts/prepare-tegu-dtb.sh`
  - `OPENPHONE_ANDROID_DIR=$PWD/.worktree/android ./scripts/verify-tegu-bootchain.sh openphone_tegu`
  - `OPENPHONE_ANDROID_DIR=$PWD/.worktree/android OPENPHONE_RELEASE=bp4a OPENPHONE_BUILD_GOAL=target-files-package ./scripts/build.sh openphone_tegu`

Pixel 9a assistant accessibility/UI-tree OTA evidence:

- Built full `openphone_tegu` OTA on the EC2 Linux Android tree after adding
  the assistant accessibility service, UI-tree context path, trajectory
  improvements, and package manifest version bump.
- Copied local artifact:
  `.worktree/artifacts/tegu/openphone_tegu-agent-ui-tree-v37-ota.zip`.
- OTA SHA-256:
  `db4867c90acde0294ce81ce8e890df39219184a1ab80ee9825171d95c880dbd2`.
- Sideload completed successfully with `Total xfer: 1.00x`.
- Device booted afterward with:
  - `ro.product.model=OpenPhone Pixel 9a`
  - `ro.openphone.version=0.1.0-dev`
  - `ro.lineage.version=23.2-20260601-UNOFFICIAL-tegu`
  - `service check openphone_agent` reporting `found`
- Pulled on-device APK from
  `/system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk`.
- On-device APK SHA-256 matched the EC2 build output:
  `4c53c3aae5aa1e7793b6f2c5a311d7067fe25a1b0e5acc62e657f505123c5881`.
- EC2 `aapt2 dump badging` verified the built APK manifest contains:
  - `versionCode='37'`
  - `versionName='0.1.1-dev'`
  - `.OpenPhoneAccessibilityService`
  - `android.permission.BIND_ACCESSIBILITY_SERVICE`
- Post-wipe PackageManager reparse is verified on the Pixel 9a:
  - `cmd package list packages --show-versioncode org.openphone.assistant`
    reports `versionCode:37`
  - `dumpsys package org.openphone.assistant` lists
    `.OpenPhoneAccessibilityService`
  - `service check openphone_agent` reports `found`
- A command-driven recovery wipe temporarily left ADB in a half-connected
  fresh-onboarding state. After onboarding and USB debugging authorization,
  shell/logcat/install channels recovered.
- Added `scripts/verify-tegu-device.sh` to make Pixel 9a post-flash validation
  repeatable. The script now passes on the Pixel 9a v37 OTA with
  `enabled_accessibility_services=org.openphone.assistant/org.openphone.assistant.OpenPhoneAccessibilityService`
  and `accessibility_enabled=1`.
- Eval 2 physical result:
  - local adapter goal: `settings`
  - task opened Settings through framework-mediated `open_app`
  - foreground UI after the run was `com.android.settings`
- Eval 1 physical result:
  - local adapter observed the screen and produced a trajectory path
  - current flashed v37 build displayed it as "Needs review" because the local
    adapter returned a transcript, not JSON
  - source now fixes this by returning structured `task.finished` JSON; module
    build passed, but a new OTA/install is needed before retesting the UI result

Pixel 9a assistant cancellation/local-JSON OTA evidence:

- Built and sideloaded full `openphone_tegu` OTA:
  `.worktree/artifacts/tegu/openphone_tegu-agent-cancel-v38-ota.zip`.
- OTA SHA-256:
  `b4643a7d68620818b9dd59ada577d2f4392f4c2a21f74a5668b6608fdc2a2f02`.
- The build host APK reported:
  - `versionCode='38'`
  - `versionName='0.1.2-dev'`
- Sideload completed successfully with `Total xfer: 1.00x`.
- The phone booted and `service check openphone_agent` still reported `found`.
- The live `/system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk`
  bytes matched the EC2 build output, but PackageManager still reported the
  previous `versionCode=37` / `versionName=0.1.1-dev`.

Pixel 9a assistant package-cache-buster OTA evidence:

- Built and sideloaded full `openphone_tegu` OTA:
  `.worktree/artifacts/tegu/openphone_tegu-agent-cachebuster-v39-ota.zip`.
- OTA SHA-256:
  `8ea2451c2b1a6ce0a98884f7aac1e57fb180088f564131e1e20bc855f50ad346`.
- Added `res/raw/package_parse_marker.txt` so the packaged APK size changes
  across the development OTA.
- The build host APK reported:
  - `versionCode='39'`
  - `versionName='0.1.3-dev'`
  - size `103061`
  - SHA-256
    `57b4781ff3265425c06651942fbc9cdd11b26b13cddf393a8c75f08f8a9899b0`
- Sideload completed successfully with `Total xfer: 1.00x`.
- The live phone APK at
  `/system_ext/priv-app/OpenPhoneAssistant/OpenPhoneAssistant.apk` matched the
  v39 SHA-256 above, proving the OTA updated the system partition.
- PackageManager still reported `versionCode=37` / `versionName=0.1.1-dev`
  after the v39 OTA, proving the remaining issue is persisted package metadata
  rather than stale system partition bytes.
- `scripts/verify-tegu-device.sh` now derives the expected assistant package
  version from the repo manifest and fails if PackageManager reports stale
  metadata.
- A command-driven recovery wipe left the phone in a state where
  `adb devices -l` and `adb get-state` report `device`, but `adb shell`,
  `adb exec-out`, and `adb logcat` do not provide usable channels. This matches
  the documented fresh-onboarding/authorization state and requires physical
  onboarding plus USB debugging reauthorization before the next verification.

Pixel 9a assistant trajectory-export OTA evidence:

- Built full `openphone_tegu` OTA on the EC2 Linux Android tree after adding
  the assistant trajectory export path and bumping the assistant package to
  `0.1.4-dev` / `40`.
- Copied local artifact:
  `.worktree/artifacts/tegu/openphone_tegu-agent-export-v40-ota.zip`.
- OTA SHA-256:
  `50b175d95ce57139824c7c7bc896e8391c8543ea979891f12ae6c99eb9efa50e`.
- EC2 `aapt2 dump badging` verified the built APK manifest contains:
  - `versionCode='40'`
  - `versionName='0.1.4-dev'`
- EC2 APK SHA-256:
  `f9dc7cd063d8321457ab1a55e9fc6d9205189c5c3284d36ed2f0003b30ab1c7f`.
- EC2 APK size: `107157`.
- Build script verified generated Pixel 9a `vendor_kernel_boot.img` contains
  the known-good DTB.
- This historical OTA was built but not physically sideloaded at the time
  because the phone was in the post-wipe ADB shell-closed state. The phone later
  recovered ADB shell/logcat and now supports privileged assistant APK
  fast-iteration for assistant/model-loop changes.

## Next Engineering Step

Phases 0, A, and B plus eight Phase C slices
(`calendar.create_event_from_message`, model-backed semantic watcher
evaluation, browser/page context deepening, model-backed AI Sheet
screen answers, message-reply watchers, the AI Island 8-state machine
with YOLO visual state, calendar depth —
update/delete/check-availability — and phone depth — missed-call
follow-ups, contact-name joins, call-back watchers) are complete and
device-validated (see the Phase C slice sections above), and the first
Phase 10 hardening slice (OTA-safe store migrations: all four stores
now use stepwise additive `onUpgrade`, validated with a real v1→v2
upgrade on device) is done. Continue the Phase 10 promotion track:
promote assistant-local stores into OS-owned services — one slice at a
time, each closing with the standard EC2 build / install / reviewed +
YOLO smoke / evidence flow (framework changes need full OTA).

Standing workflow rules remain unchanged: use the privileged assistant APK
push for assistant-only changes and full OTA only for framework/sepolicy/
SystemUI/boot-chain changes; collect audit evidence with the assistant's
Export Audit control; record artifact SHAs and trajectory paths here after
every validated slice.
