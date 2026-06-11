# OpenPhone Master Plan

Last updated: 2026-06-09 (architecture audit + spine-first reprioritization)

## Purpose

OpenPhone is an Android-based operating system where AI is a first-class
system layer, not a chatbot app sitting on top of Android.

The product should let a user talk to the phone, ask about anything on the
phone, ask about the world, ask the phone to act across apps, and rely on the
phone to follow up proactively when the user has left an open loop. The agent
must be useful in the exact place where intent appears: the screen, the
keyboard, a message thread, a notification, a call, a browser tab, a screenshot,
the camera, or the lock screen.

The key architectural decision is that OpenPhone should not become a pile of
hardcoded command routers. It needs a real OS agent stack:

```text
Hardware / OS Privileges
  -> Model Runtime
  -> Context Index
  -> Long-Term Memory
  -> Commitments and Watchers
  -> Action Registry
  -> Screen Awareness
  -> System Orchestrator
  -> Agent Runtime
  -> Systemwide UI
  -> App Integrations
```

The assistant app is one surface. It is not the system.

Quality comes first. For the first real product, use the best available remote
models, cloud inference, hosted indexing/reranking, and broker services whenever
they make the OS dramatically better. Local-first and private-by-default
execution remain architectural goals, but they must not slow down the path to a
great agentic phone. The right sequence is:

```text
make it work amazingly well
  -> make it reliable
  -> make it fast
  -> make it private/local where practical
  -> make it cheaper
```

## Background

We have already built the first useful OpenPhone base:

- A LineageOS-derived OpenPhone product layer.
- A privileged `OpenPhoneAssistant` app.
- Compose chat UI and system overlay surfaces.
- Initial OpenAI Responses/Realtime adapter work.
- Screenshot/screen-context plumbing.
- OS-mediated pointer/action execution.
- Policy, confirmation, task grants, audit export, and trace logging.
- A model broker direction that lets the phone use the best available models
  without hardcoding provider API keys into the device image.
- System branding, boot animation, wallpaper, and an active-agent glow.
- Volume Up + Volume Down hardware trigger and island triggers for
  starting/stopping the agent.

The current system proves that we can ship a custom Android build with a
privileged assistant and OS action path. It does not yet prove the final product
thesis, because too much behavior is still inside a single assistant flow, and
the agent is still mostly reactive.

The next stage is to turn this from "an assistant that can sometimes operate
the phone" into "an OS intelligence layer."

## Current Implementation Checkpoint

As of June 9, 2026, the bottom-up substrate work has started moving from plan
to code:

- `OpenPhoneOrchestrator` exists in the assistant and is now the entry point for
  text, mic, and debug sends. The deterministic development fallback keeps
  greetings and ordinary chat in conversation, routes screen questions away
  from generic phone-control tasks, and still lets explicit action requests
  start the task path.
- `ContextIndexStore` persists assistant conversations and agent lifecycle
  events into SQLite/FTS and exposes a `context_search` model tool.
- `OpenPhoneNotificationListenerService` now indexes active/posted/removed
  notifications into the context index. The privileged assistant grants its own
  notification-listener access on the OpenPhone build, redacts secret
  notifications, preserves source app/package/key provenance, and makes
  notification text searchable through `context_search`,
  `notifications_list`, and `notifications_search`. The assistant can also
  answer direct chat requests such as "show notifications" from the local
  notification index.
- `MemoryStore` persists explicit user memories, supports recall/search, and
  exposes `memory_search` / `memory_save` model tools.
- `CommitmentStore` persists explicit open loops, supports create/list/complete
  from chat, exposes commitment model tools, and has been physically validated
  on the Pixel 9a with a create/list/complete smoke test.
- `OpenPhoneWatcherScheduler` is the first assistant-local watcher slice. It
  checks due time-based commitments at startup/boot, schedules the next due
  check with `AlarmManager`, fires due commitments into user-visible
  notification cards, and supports Complete/Snooze/Dismiss notification
  actions.
- `WatcherStore` adds the durable watcher schema from this plan, plus
  `watcher_create`, `watcher_list`, and `watcher_stop` model tools. The first
  user-facing path supports explicit time watchers such as "tell me in 1 minute
  to..." with list/stop chat commands and notification delivery when fired.
- Notification-pattern watchers now run from the notification listener path:
  `type=notification` watcher records match posted notification package/title/
  text conditions, fire once, and deliver `openphone_watchers` notification
  cards. Direct chat can create simple notification watchers such as "tell me
  when a notification mentions ...".
- Watcher runtime now uses `running_at`, `failure_count`, `last_result_hash`,
  and `failure_alert_at`: stale running watchers are repaired after service
  death/reboot, failed watcher checks are rescheduled with exponential backoff,
  no-op checks do not notify, and repeated failures surface "Watcher needs
  attention" cards.
- Browser/page watcher work has its first concrete slice:
  `type=web_change` watchers can fetch an HTTP(S) URL asynchronously, hash a
  bounded page body, establish a quiet first baseline, reschedule unchanged
  pages without notification spam, and fire an `openphone_watchers` card when
  the page hash changes. Direct chat recognizes prompts such as "watch
  https://... for changes". A localhost-only network security exception exists
  for deterministic device smoke tests over `adb reverse`; normal external
  page watching should use HTTPS.
- Generic watcher evaluator v2 keeps the watcher surface as one smart primitive
  instead of one tool per domain. `watcher_create` now accepts source/evaluator
  vocabulary such as `source=web`, `evaluator=hash_change|text_contains|
  semantic_match`, `url`, `query`, and `interval_ms`, then normalizes that into
  durable watcher records. The evaluator runtime supports web hash changes,
  deterministic text-condition matching, and model-backed `semantic_match`:
  semantic watchers strip the fetched page to bounded plain text and ask the
  Responses model a strict yes/no judgment in the background
  (`OpenAiResponsesAgentAdapter.judgeWatcherCondition`); model failures route
  through the standard watcher backoff rather than any keyword fallback.
  Device-validated 2026-06-10
  (`OpenPhoneAssistant-semantic-watcher-v1.apk`,
  `sha256=5a3db48df3bbeadc1c0768062dc47bbda99ff1072ce6cd08466c32390918575e`).
- Message-reply watchers make "tell me when X replies" and "remind me if X
  does not reply by <deadline>" real triggers instead of dead commitments:
  `watcher_create` accepts `source=message` with `address`/`thread_id`,
  optional `deadline_at` and `notify_on=reply|no_reply`, normalized into
  `type=message_reply` records with a creation-time `baseline_ms`. The
  scheduler polls the SMS inbox for inbound messages newer than the baseline
  (substring or `PhoneNumberUtils.compare` address matching): a reply fires
  the alert (or silently resolves a no-reply reminder), a passed deadline
  with `notify_on=no_reply` fires the reminder, and errors route through the
  standard watcher backoff. Device-validated 2026-06-10
  (`OpenPhoneAssistant-message-reply-v1.apk`,
  `sha256=f2fb71cd9fa189d4dc5780a42618efcc5d7ea370eb548dc2ae979d6f60ca9af6`,
  v103).
- The AI Island is a canonical 8-state machine
  (`idle`/`listening`/`thinking`/`answer_ready`/`action_running`/
  `watching`/`needs_review`/`error`) rendered by
  `PointerOverlayController.setIslandState`, replacing ad-hoc status-text
  matching. YOLO autonomy is always visible as an amber chip stroke plus a
  "⚡" label prefix, the watching state shows the active watcher count
  published from the watcher scheduler, and a single-island invariant
  prevents stacked overlay windows across service/activity controller
  instances. Device-validated 2026-06-10
  (`OpenPhoneAssistant-island-states-v2.apk`,
  `sha256=a45c612fccc7e0382043bb12257e56f2ddbcc85c4a379c47b78b9c87158c03aa`,
  v105).
- Browser/page context now has a first semantic connector:
  `browser_fetch_page` / `browser.fetch_page` fetches a URL through the
  assistant action layer, extracts bounded title/text metadata plus a heading
  outline and resolved outgoing links, and returns it to the model without
  opening a browser UI or using pointer fallback. The model can chain
  follow-up `browser_fetch_page` calls on returned links to reach the page
  that answers the question (device-validated 2026-06-10,
  `OpenPhoneAssistant-browser-context-v1.apk`,
  `sha256=b93e29f94e3f9c9a80bdd14657a64ce58dd6374303b15492030b9108b2c131ff`).
  It is registered in the model catalog and action registry, exposed to
  Responses/Realtime adapters, routed by the orchestrator/local fallback for
  URL summarization questions, and enforced through the `network.use`
  capability.
- Browser search now has a first semantic action:
  `browser_search` / `browser.search` turns a query into a browser search URL
  through the framework `open_url` action path, avoiding fragile address-bar
  typing or pointer fallback. It is registered in the model catalog and action
  registry, exposed to Responses/Realtime adapters, routed by the local
  fallback for "search the web..." goals, and enforced through the same
  medium-risk `network.use` policy as opening web links.
- AI Sheet v1 now exists as an assistant-owned system overlay behind the AI
  Island. On the Pixel 9a, tapping the left idle island half opens a compact
  sheet, swiping down opens the expanded sheet, and the right idle island half
  still starts voice. The sheet exposes Talk, Stop, Chat, Screen, Summarize,
  Search, Notifications, and Settings actions. The Screen/Summarize actions
  answer in place, and as of sheet-screen v1 they are model-backed: the sheet
  requests the screen with a task-scoped screenshot + UI tree and answers via
  the Responses vision adapter (`answerScreenQuestion`), with the old local
  text-fragment summarizer kept only as the explicit unconfigured fallback
  (metadata-only capture, no screenshot leaves the framework). Device-validated
  artifact:
  `.worktree/artifacts/tegu/OpenPhoneAssistant-sheet-screen-v1.apk`
  (`sha256=cf7f57af32a6b524330980aee2b1e842128335d84c95fa17a43fc6580cac5bef`,
  v102). Earlier sheet UI smoke screenshots:
  `.worktree/artifacts/tegu/screens/ai-sheet-v1-compact.png` and
  `.worktree/artifacts/tegu/screens/ai-sheet-v1-expanded.png`, plus
  `.worktree/artifacts/tegu/screens/ai-sheet-screen-answer-v1.png`.
- `openphone_action_registry.json` now provides the first formal action
  catalog. It maps every model-visible tool to a canonical action name,
  input/output schema, risk class, required capability, authorization policy,
  allowed callers, executor service, and audit event type. Repo checks enforce
  one-to-one coverage with `openphone_model_tools.json`, and the assistant
  runtime loader rejects unregistered tools when the registry is installed.
- Reviewed, YOLO, and dry-run autonomy modes now have a first concrete
  implementation. The Advanced settings surface exposes an explicit mode
  selector, persists it through `Settings.Secure` with app-private fallback,
  and model-tool preflight consults the installed action registry before
  execution. In reviewed mode, registered medium/high actions stop with an
  approval card. In YOLO mode, a bounded medium-risk allowlist can run without
  repeated review, while `explicit_confirm`, high-risk, denied app-policy, and
  disabled task-grant paths still stop. In dry-run mode, action/control tools
  are recorded as `dry_run.preview` without executing the device change, across
  cloud adapters and the local heuristic fallback.
- The first notification semantic connector is implemented: `notifications_open`
  / `notifications.open` resolves an active notification by key, package, or
  text query and sends its content intent through the notification listener,
  avoiding pointer fallback for opening notifications. Direct chat and the
  deterministic local development agent loop both recognize "open notification
  about/matching/for ..." so this path can be validated without relying on
  brittle coordinate taps.
- Notification summary v1 is implemented: `notifications_summary` /
  `notifications.summarize` groups recent indexed notifications by app/title
  and returns both a human-readable "what did I miss?" summary and structured
  groups. It is registered in the model catalog/action registry, exposed to
  Responses/Realtime/local adapters, routed by the orchestrator/local fallback,
  and enforced as medium-risk `notifications.read`. Reviewed mode stops at a
  confirmation card; YOLO now allows `notifications.read` and executes the
  summary. Built artifact:
  `.worktree/artifacts/tegu/OpenPhoneAssistant-notification-summary-v1.apk`
  (`sha256=29b99c8a4b7144d7c099c207eb2d30a659eb26623075438005098590e09954f2`).
  YOLO trajectory:
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-220946-task-65387222728/events.jsonl`.
- Notification-to-commitment v1 is implemented. `notification_commitment_create`
  / `notifications.create_commitment` selects a recent indexed notification by
  optional query, creates a durable `CommitmentStore` row with notification
  provenance in trigger/evidence metadata, is mapped to `commitments.write`,
  and is exposed through local, Responses, Realtime, reviewed/YOLO/dry-run, and
  registry validation paths. Built artifact:
  `.worktree/artifacts/tegu/OpenPhoneAssistant-notification-commitment-v1.apk`
  (`sha256=6ed204edc5a293408e6df0be92f0cb4214bcad9ed739974267e71eb7c19f4d0d`).
  Reviewed mode stops at a Medium-risk approval card for
  `notifications.create_commitment`; YOLO creates commitment `#2` with
  `status=notification.commitment_created`. YOLO trajectory:
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-223138-task-42888115661/events.jsonl`.
- Calendar connector v1 is implemented in the assistant action layer.
  `calendar_search` / `calendar.search` reads Android calendar instances in a
  bounded window, and `calendar_create_event` / `calendar.create_event` creates
  events through `CalendarContract.Events` after choosing a writable calendar.
  Both tools are registered in the model tool catalog and action registry,
  exposed to Responses/Realtime adapters, routed by the orchestrator/local
  fallback, and enforced by the reviewed/YOLO/dry-run policy layer. Calendar
  read/write remain medium-risk confirmed capabilities.
- Calendar depth v2 is implemented and device-validated (2026-06-11):
  `calendar_update_event` / `calendar.update_event` (partial updates keyed by
  `event_id`, before/after snapshots), `calendar_delete_event` /
  `calendar.delete_event` (high-risk, new `calendar.delete` capability with
  `explicit_confirm` — YOLO always stops for deletes), and
  `calendar_check_availability` / `calendar.check_availability` (merged
  busy intervals + free gaps over a bounded window). All calendar tool
  outputs carry `start_local`/`end_local` alongside unix-ms, and every model
  prompt (orchestrator, Responses agent, Realtime initial prompt) now
  includes live device-time context so the model computes calendar windows
  instead of guessing epoch milliseconds. Validated on the Pixel 9a as
  `OpenPhoneAssistant-calendar-depth-v3.apk` (v108 0.1.72-dev,
  `sha256=62fcf0047ab2784e0d088e21d67f2ee56172ff25f3c19da288732cac29a5e3f3`).
- Contacts connector v1 is implemented in the assistant action layer.
  `contacts_search` / `contacts.search` searches Android contacts through
  `ContactsContract`, optionally returning phone and email details. The tool is
  registered in the model tool catalog and action registry, exposed to
  Responses/Realtime adapters, routed by the orchestrator/local fallback, and
  enforced as the medium-risk `contacts.read` capability.
- Messages connector v1 is implemented in the assistant action layer.
  `messages_search` / `messages.search` reads Android SMS history through
  `Telephony.Sms` after reviewed permission/policy gating,
  `messages_summary` / `messages.summarize` groups recent SMS messages by
  thread/contact with bounded sample messages and an empty-state summary,
  `message_commitment_create` / `messages.create_commitment` creates durable
  OpenPhone commitments from a matching SMS with message id/thread/address/date
  provenance,
  `messages_draft` / `messages.draft` prepares a structured SMS draft without
  sending it, and `messages_send` / `messages.send` sends through `SmsManager`
  only after the high-risk explicit-confirm policy allows execution. The tools
  are registered in the model catalog and action registry, exposed to
  Responses/Realtime adapters, routed by the orchestrator/local fallback, and
  integrated with reviewed, YOLO, and dry-run autonomy modes. Message summary
  v1 was physically validated on the Pixel 9a with
  `.worktree/artifacts/tegu/OpenPhoneAssistant-messages-summary-v1.apk`
  (`sha256=8b07d9c871e882975548bee9ed91fb35f4144c5060afcd0f60439573a452c52b`):
  reviewed mode stops at a Medium-risk approval card for
  `messages.summarize`; YOLO executes `messages_summary` and returns
  `status=messages.summary`. YOLO trajectory:
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-224723-task-75863605179/events.jsonl`.
  Message commitment v1 was physically validated with
  `.worktree/artifacts/tegu/OpenPhoneAssistant-message-commitment-v1.apk`
  (`sha256=9efb6039c46bba606df2039d2427d0d5fde998d0d89c206e9a558fe4a3a7fae2`):
  reviewed mode stops at a Medium-risk approval card for
  `messages.create_commitment`; YOLO creates commitment `#3` with
  `status=message.commitment_created` from a seeded SMS smoke row. YOLO
  trajectory:
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-230043-task-149704915680/events.jsonl`.
- Phone connector v1 is implemented in the assistant action layer.
  `calls_search` / `phone.search_calls` reads Android call history through
  `CallLog.Calls` after reviewed permission/policy gating, `phone_context` /
  `phone.context` gathers call context from call log, contacts, SMS, and
  calendar and extracts confirmation-code candidates, and `calls_place` /
  `phone.call` places calls through `TelecomManager.placeCall` only after the
  high-risk explicit-confirm policy allows execution. The tools are registered
  in the model catalog and action registry, exposed to Responses/Realtime
  adapters, routed by the orchestrator/local fallback, and enforced as
  `calls.read` and `calls.place` capabilities. Phone context v1 was physically
  validated with
  `.worktree/artifacts/tegu/OpenPhoneAssistant-phone-context-v1.apk`
  (`sha256=5bef9ef275d8a0433f53f0aa292e6bc0ba3a003d6cca33ef4eeb032ff99f0359`):
  reviewed mode stops at a Medium-risk approval card for `phone.context`;
  YOLO executes `phone_context` and returns `status=phone.context`. YOLO
  trajectory:
  `/data/user/0/org.openphone.assistant/files/openphone-trajectories/20260609-231841-task-67142899568/events.jsonl`.
- Phone depth v2 is implemented and device-validated (2026-06-11):
  `calls_search` gains a call-type filter (missed/incoming/outgoing/…) and
  joins contact names via `ContactsContract.PhoneLookup` when the call log
  has no cached name, matching numbers across formatting variants with
  `PhoneNumberUtils.compare`; `phone_context` surfaces
  `missed_call_follow_ups` (missed calls with no later call or sent SMS back
  to the same number); and a new `call_back` watcher type closes call-back
  loops — `notify_on=call` alerts when a call from the watched number
  appears, `notify_on=no_call` + `deadline_at` reminds only if no call
  happened in time (mirroring message-reply watcher semantics). Validated on
  the Pixel 9a as `OpenPhoneAssistant-phone-depth-v2.apk` (v110 0.1.74-dev,
  `sha256=a5ca805bb278bfcda6f8d80c46fc8815c3ec6bfe6cfd79f11c7d025138f45ac8`).
- Apps connector v1 is implemented in the assistant action layer.
  `apps_search` / `apps.search` searches launchable installed apps by label,
  package, or activity through `PackageManager`, and `open_app` now resolves
  real installed app labels/packages before falling back to legacy aliases. The
  tool is registered in the model catalog and action registry, exposed to
  Responses/Realtime adapters, routed by the local fallback, and enforced as
  low-risk `apps.read`.
- Source-linked calendar creation (`message_calendar_event_create` /
  `calendar.create_event_from_message`) is **in flight and incomplete**: it is
  wired into `openphone_model_tools.json`, `openphone_action_registry.json`,
  `FrameworkToolExecutor`, `AssistantActivityBackend`, and both cloud
  adapters, but the local-adapter helpers are unimplemented (the tree does
  not compile), no EC2 build has run, and no device smoke has been done. See
  the Architecture Audit section — this slice will be completed on the new
  registry-driven path in Phase C rather than by adding more keyword
  heuristics.

These are assistant-local implementations, not the final system services. They
are intentionally useful vertical slices first. The next hardening step is to
promote the same contracts into OS-owned services, add Settings surfaces, add
richer background delivery, and connect notification/messages/calendar/browser
sources.

## Architecture Audit (2026-06-09)

A full repository review was done after the connector-slice sprint. The
verdict: the substrate (stores, policy, autonomy modes, audit, agent task
loop) is sound and device-validated, but the routing layer has drifted into
exactly the failure mode this plan warns against — "a pile of hardcoded
command routers." Before any new connector slice ships, the spine must be
fixed.

### What is sound and must be preserved

- The action registry + capability + risk-class + autonomy-mode
  (reviewed/YOLO/dry-run) + audit/trajectory layer sits below the model and
  enforces policy correctly. This matches Non-Negotiable Principle 1.
- The device task loop in `OpenAiResponsesAgentAdapter.runTask` is a real
  observe -> decide -> act -> verify loop with a step budget, wall-clock
  limit, no-progress detection, screen-changed verification, and
  finish-evidence checks before `finish_task`.
- `ContextIndexStore`, `MemoryStore`, `CommitmentStore`, and `WatcherStore`
  are durable, reboot-safe, and physically validated on the Pixel 9a.
- The build/validation discipline (focused EC2 APK builds, artifact SHAs,
  trajectory files, reviewed/YOLO smoke evidence) is working and should
  continue unchanged.

### Drift 1: three stacked keyword routers

The plan calls for one model-driven orchestrator. The implementation has
three independent keyword-matching layers, each of which can decide behavior
before the model is consulted:

1. `AssistantActivityBackend.routeMessageFromCurrentMessage()` runs
   `handleExplicitWatcherMessage`, `handleExplicitCommitmentMessage`,
   `handleExplicitMemoryMessage`, and `handleExplicitNotificationMessage`
   **before** `mOrchestrator.decide(...)` is ever called. For those four
   domains the model never gets a say.
2. `OpenPhoneOrchestrator` is ~200 lines of `startsWithAny`/`containsAny`
   keyword guards. It only delegates to the model route call when no keyword
   matches, and `guardDecision(...)` can then override the model's answer
   with the same keyword checks. It supports only three modes
   (`reply`, `answer_screen`, `run_task`) — the plan's
   retrieve/watch/memory/clarify modes were never implemented.
3. `LocalHeuristicModelAdapter` (~1,400 lines) was intended as an offline
   development fallback but became a shadow router: ~18 `isXxxGoal()`
   keyword classifiers and a ~570-line if/else cascade dispatching 30+
   tools. New use cases keep being bolted on here, which is why each slice
   feels tailored instead of emergent from one smart agent.

Consequence: "smart omni AI" behavior — the model choosing tools from a
catalog, chaining them, and asking clarifying questions — only exists inside
the device-task loop. Everything else is keyword dispatch.

### Drift 2: tool surfaces are hand-maintained instead of registry-generated

`openphone_action_registry.json` already contains the name, description,
input/output schemas, risk class, capability, and policy for all 47 tools.
But nothing consumes it to generate the model-facing surfaces. Adding one
tool currently requires editing 5–6 places:

```text
openphone_model_tools.json            tool metadata entry
openphone_action_registry.json        action entry (schemas, risk, policy)
FrameworkToolExecutor.java            case in a ~92-case switch
OpenAiResponsesAgentAdapter.java      hand-written ~200-line prompt string
                                      listing every tool and its arguments
OpenAiRealtimeAdapter.java            isAllowedTool() ~50-clause if-chain
                                      plus realtimeTools() definitions
LocalHeuristicModelAdapter.java       new isXxxGoal() keyword heuristic
```

This O(N x 6) growth is the root cause of the tailored-use-case feel and of
the current compile break. The registry must become the single source of
truth: prompt catalog sections, Realtime tool definitions, and allowlists
must be generated from it at runtime.

### Drift 3: the repo is currently broken and check.sh cannot see it

`LocalHeuristicModelAdapter.java` (lines ~318–321) calls
`isMessageCalendarEventGoal(...)` and `messageCalendarEventQuery(...)`,
which do not exist. `./scripts/check.sh` passes because it validates
JSON/config consistency and cross-file coverage, not Java compilation. The
in-flight `message_calendar_event_create` slice is wired into the configs,
executor, and both cloud adapters but has never been built or
device-validated. check.sh needs a Java syntax/compile gate.

### Decision

Stop adding connectors until the spine is fixed. The revised sequence is in
Near-Term Priorities below: fix the build, make tool surfaces
registry-driven, implement the real model-first Orchestrator v1, demote all
keyword routing to safety rails, and only then resume connector slices —
starting with the in-flight calendar-from-message slice as proof that a new
tool costs 2 JSON entries + 1 executor case.

## Lessons So Far

### Apple AI Direction

The useful takeaway from Apple's 2026 AI architecture is not "copy Siri." The
useful takeaway is the layering:

```text
Devices
  -> Foundation models for text, image, and voice
  -> Personal context
  -> World knowledge
  -> App actions
  -> On-screen awareness
  -> System orchestrator
  -> Systemwide experiences
```

The strong idea is AI as an OS-native layer. It appears as small contextual
surfaces inside the user's current workflow, not as a separate app window first.

Patterns worth adopting:

- AI Island / top system handle.
- Compact answer first, expandable conversation second.
- Screen selection and "ask about this."
- AI attached to keyboard and text selection.
- Contextual chips inside Messages, Mail, Calendar, Browser, Phone, Gallery,
  Notifications, and Files.
- Persistent conversation and action history.
- Personal context retrieval across messages, notifications, calendar, contacts,
  photos, files, browser, and prior assistant sessions.

Where Apple-style systems are vulnerable is not only model choice or delayed
shipping. The bigger product gap is agentic and proactive behavior: actually
getting things done, monitoring open loops, and returning when something changes.

### Google AI Direction

Google's 2026 Android/Gemini direction is more explicitly agentic and proactive:
Android is framed as becoming an intelligence system, Gemini is pushed toward
agents that can act, and product examples include daily briefs, monitoring,
shopping/search agents, and cross-device help.

The lesson for OpenPhone is that "personal context search" is table stakes.
The differentiating layer should be:

- Durable background watchers.
- User-visible commitments.
- Agent sessions that can survive interruptions.
- Proactive notifications that explain why they exist.
- OS actions that move from understanding to decision support to execution.

### OpenClaw and Hermes Agent

The local `../openclaw` and `../hermes-agent` repos are useful because they show
how proactive agents are engineered in practice.

Relevant OpenClaw files:

- `../openclaw/src/cron/service.ts`
- `../openclaw/src/cron/types.ts`
- `../openclaw/src/cron/service/timer.ts`
- `../openclaw/src/cron/service/jobs.ts`
- `../openclaw/src/cron/heartbeat-policy.ts`
- `../openclaw/src/commitments/runtime.ts`
- `../openclaw/src/commitments/store.ts`

Relevant Hermes files:

- `../hermes-agent/AGENTS.md`
- `../hermes-agent/cron/scheduler.py`
- `../hermes-agent/cron/jobs.py`
- `../hermes-agent/tools/cronjob_tools.py`
- `../hermes-agent/gateway/run.py`
- `../hermes-agent/agent/conversation_loop.py`

Practical lessons:

- Proactivity is not "keep an LLM running forever."
- Proactivity is durable triggers, durable jobs, heartbeats, resumable sessions,
  hard timeouts, state repair, failure alerts, and delivery rules.
- Background jobs need ownership: which session receives output, which surface
  displays it, and whether it interrupts the user.
- Heartbeats should be mostly silent. Successful no-op checks should not spam.
- Commitments are separate from chat history. They are structured open loops
  extracted from conversations, messages, notifications, and actions.
- Stuck-running repair matters. Phones reboot, services die, radios disappear,
  model calls hang, and users interrupt sessions.

The OpenPhone equivalent should be a platform service that stores triggers,
watchers, commitments, and execution state durably, then wakes the orchestrator
only when there is work to do.

### Coding Agent Runtime Lessons

OpenClaw, Hermes, Claude Code-style agents, Codex-style agents, and similar
coding CLI agents are also relevant because they demonstrate what a serious
agent loop needs once it is allowed to operate a real environment.

Practical lessons to bring to OpenPhone:

- A strong agent has a loop, not a router: observe, reason, plan, act, verify,
  recover, and summarize.
- Tool calls should be typed, audited, and constrained by a registry.
- The runtime needs a visible task list or progress model, even if the user does
  not see every internal step.
- The user must be able to interrupt, steer, approve, or switch to YOLO mode.
- Long tasks need checkpoints and resumability.
- Context compaction matters. The agent should keep durable task state instead
  of relying on one giant prompt.
- Agents need explicit "done" criteria. They should not say "Done" unless they
  actually completed or intentionally skipped the task with a reason.
- The runtime should distinguish:
  - conversational answer
  - one-shot tool action
  - multi-step task
  - background watcher
  - blocked/needs-review state
  - completed state
- The model should be allowed to choose the next step, but policy, capabilities,
  and audit must be enforced below the model.

For OpenPhone, the right abstraction is not "Claude Code on Android." It is a
phone-native task runtime with the same discipline: typed tools, state machine,
interrupts, checkpoints, resumable sessions, review modes, and clear completion
criteria.

### TrueMemory

TrueMemory is relevant as a memory-system reference, not as a drop-in dependency
yet.

Useful ideas:

- Preserve raw events instead of immediately reducing everything into extracted
  facts.
- Treat retrieval as the center of memory.
- Prefer user-owned durable storage with a clear path to local-first operation.
  For the first excellent product, hosted indexing/reranking is acceptable when
  it materially improves quality.
- Combine keyword, semantic, salience, entity/personality, consolidation, and
  reranking.
- Capture automatically after sessions.
- Deduplicate and update memories instead of accumulating endless snippets.
- Make memory editable, inspectable, and deletable.

Important licensing note: the public TrueMemory repo is AGPL-3.0 at the time of
review. Do not vendor or link it into OpenPhone product code unless we either
comply with AGPL obligations or obtain a separate commercial license. We can
still learn from the architecture and build our own implementation.

## Product Thesis

OpenPhone should feel like:

```text
Ask anything.
Show me what matters.
Do it.
Remember what I care about.
Come back when something changes.
```

The user should not need to decide whether they are "chatting," "searching,"
"using an agent," "using screen awareness," or "creating an automation." The
orchestrator should decide:

- Reply directly.
- Ask a clarifying question.
- Search world knowledge.
- Search personal context.
- Inspect the screen.
- Open a compact answer card.
- Start a task session.
- Ask for confirmation when the current operating mode requires it.
- Create a watcher.
- Save a memory.
- Update a commitment.
- Finish silently.

## Non-Negotiable Principles

1. The agent is not the authority for sensitive actions.

   The model may propose actions. Policy and system services decide whether the
   action is allowed, requires confirmation, or is blocked.

2. Raw personal data is not all "memory."

   Notifications, messages, screenshots, and browser pages first go into a
   context index with provenance and retention rules. Only durable learned facts,
   preferences, routines, relationships, corrections, and standing instructions
   become long-term memory.

3. Proactivity must be explainable.

   Every proactive notification or chip must be able to answer: "Why did you
   show this?" and "What source did this come from?"

4. Background work must be bounded.

   Watchers and jobs need schedules, quotas, timeouts, retry policy, failure
   alerts, and user controls.

5. User control must be first-class.

   The user needs Settings surfaces for data sources, memories, commitments,
   active watchers, action grants, audit logs, and model/broker settings.

6. Prefer OS integration over Accessibility when we control the ROM.

   Accessibility is acceptable as a development bridge, but production
   extraction and action should move into framework/system services.

7. Build bottom-up.

   Do not start with dozens of app-specific magic demos. Build the substrate:
   context, memory, actions, watchers, orchestrator, policy, and UI primitives.

## Target Architecture

```text
OpenPhone System UI
  AI Island
  AI Sheet
  Screen Selection Layer
  Active Agent Glow
  AI Notification Cards

OpenPhoneAssistant
  Chat archive
  Memories UI
  Developer settings
  Conversation surface
  Realtime voice surface

OpenPhone Orchestrator
  Intent/route decision
  Context retrieval
  Tool planning
  Confirmation planning
  Agent session lifecycle

OpenPhone Agent Runtime
  Observe/reason/act loop
  Realtime voice loop
  Responses/CUA loop
  Session interruption
  Trace and audit

OpenPhone Context Layer
  ContextIndexService
  MemoryService
  CommitmentService
  WatcherService

OpenPhone Action Layer
  ActionRegistry
  PolicyEngine
  ConfirmationService
  ToolExecutor
  App connectors

Android Framework / system_server
  Screen capture and UI tree
  Window/app state
  Notification access
  Input mediation
  App action mediation
  Audit log
  Permission and grant enforcement
```

## Core Services To Build

### 1. OpenPhoneContextIndexService

Purpose: durable, searchable, provenance-rich record of user-visible context.

Required sources:

- Assistant conversations.
- Agent actions and outcomes.
- Notifications.
- SMS/MMS/RCS if OpenPhone owns the default messaging role or ships a default
  messaging app.
- Contacts.
- Calendar.
- Call log.
- Browser tabs/history for the OpenPhone browser.
- Screenshots and OCR from explicit user actions.
- Photos metadata and selected visual embeddings.
- Files and documents.
- Email, if OpenPhone ships or integrates an email app.
- Maps places, trips, and navigation events.
- App-specific semantic indexes exposed by app connectors.

Storage model:

```text
context_event
  id
  source_type
  source_app
  source_uri
  source_record_id
  user_id
  created_at
  observed_at
  title
  text
  structured_payload_json
  entities_json
  sensitivity
  retention_policy
  expires_at
  hash
  embedding_ref
  deleted_at

context_relation
  id
  from_event_id
  to_event_id
  relation_type
  confidence

context_acl
  event_id
  allowed_purpose
  allowed_surface
  policy_json
```

Implementation notes:

- Start with SQLite plus FTS5 for local text search.
- Add embeddings behind an interface so the storage backend can change.
- Consider Android AppSearch for structured on-device indexing where it fits,
  but do not depend on app-owned indexing for OS-owned context.
- Every indexed item must have provenance and retention metadata.
- Sensitive sources need per-source toggles in Settings.
- Deletions must tombstone and remove retrievable content from search.

### 2. OpenPhoneMemoryService

Purpose: long-term learned knowledge about the user.

Memory types:

```text
preference
standing_instruction
personal_fact
relationship
routine
correction
decision
project
style
boundary
```

Schema:

```text
memory
  id
  user_id
  type
  subject
  predicate
  value
  natural_text
  confidence
  evidence_event_ids_json
  created_at
  updated_at
  last_used_at
  expires_at
  status            # active, proposed, archived, rejected
  sensitivity
  user_editable
  source_session_id
```

Rules:

- Memories are proposed by a background review worker, not blindly written on
  every message.
- High-confidence low-risk memories can auto-save if the user enables it.
- Sensitive memories should be proposed for review.
- Corrections should update existing memory, not create contradictory duplicates.
- The user can edit, delete, disable, or export memories.
- Model prompts should receive only the smallest relevant memory set for the
  current request.

Do not store every notification or message as memory. Store those as context
events. Promote only durable signal.

### 3. OpenPhoneCommitmentService

Purpose: track open loops that may require proactive follow-up.

Examples:

- "Remind me if Sarah does not reply by tomorrow."
- "Check when this product comes back in stock."
- "Follow up with the contractor next week."
- "Tell me when registration opens."
- "When I get to the airport, show my confirmation code."

Schema:

```text
commitment
  id
  user_id
  title
  description
  source_event_ids_json
  trigger_type       # time, location, notification, message, web_change, app_state
  trigger_spec_json
  due_at
  expires_at
  status             # pending, active, fired, dismissed, snoozed, expired, failed
  confidence
  delivery_surface   # notification, island, sheet, chat, silent
  created_at
  updated_at
  last_checked_at
  failure_count
```

Rules:

- Commitments may be explicit or inferred.
- Inferred commitments should be reviewed until confidence and UX are good.
- Every fired commitment must show source and reason.
- Add max-per-day and cooldown limits.
- Users can see all commitments in the OpenPhone app and Settings.

### 4. OpenPhoneWatcherService

Purpose: durable background monitoring.

Watcher types:

- Time schedule.
- Notification pattern.
- Message/contact pattern.
- Web page condition.
- Calendar proximity.
- Location condition.
- App state condition.
- System state condition.

Schema:

```text
watcher
  id
  user_id
  type
  title
  condition_json
  schedule_json
  session_target
  delivery_json
  status             # active, paused, fired, failed, expired
  created_at
  updated_at
  next_run_at
  running_at
  last_run_at
  last_result_hash
  failure_count
  failure_alert_at
```

Operational requirements:

- Persist `running_at` and repair stuck watchers after reboot/service death.
- Use jitter/staggering for scheduled checks.
- Use exponential backoff on repeated failures.
- Suppress no-op heartbeat output.
- Hard timeout every run.
- Audit every action and every delivered notification.

### 5. OpenPhoneActionRegistry

Purpose: formal catalog of what the model can ask the phone to do.

Action definition:

```text
action
  name
  description
  input_schema_json
  output_schema_json
  risk_class
  required_capabilities
  authorization_policy
  allowed_callers
  executor_service
  audit_event_type
```

Initial action families:

```text
assistant.reply
assistant.ask_clarifying_question
screen.describe
screen.select_region
screen.extract_text
apps.open
apps.search
notifications.list
notifications.summarize
notifications.open
messages.search
messages.draft
messages.send
contacts.search
calendar.search
calendar.create_event
phone.search_calls
phone.call
browser.open
browser.search
browser.watch_page
settings.open
memory.search
memory.propose
memory.update
commitments.create
commitments.update
watchers.create
watchers.stop
device.back
device.home
device.input_text
device.tap_element
device.scroll
```

Rules:

- The model sees schemas, not Java/Kotlin internals.
- Every action maps to a capability and risk class.
- Risky actions require authorization according to the active operating mode:
  confirmation in reviewed mode, scoped execution in trusted autopilot, and
  YOLO-profile execution in YOLO mode.
- Silent destructive actions are forbidden.
- App connectors should expose semantic actions before falling back to pointer
  automation.

### 6. OpenPhoneOrchestrator

Purpose: decide what kind of help is needed and coordinate the services.

The orchestrator replaces the hardcoded router.

Input:

- User utterance/text.
- Current surface: chat, island, keyboard, screenshot, notification, app.
- Current screen context.
- Recent conversation.
- Relevant memories.
- Relevant context events.
- Active commitments/watchers.
- Policy/grants.

Output:

```text
orchestrator_decision
  mode              # answer, clarify, retrieve, inspect_screen, act, watch, memory, stop
  user_visible_text
  required_context_queries
  proposed_actions
  confirmation_plan
  session_plan
  delivery_surface
```

Behavior:

- "Hello" should answer conversationally.
- "What's on my screen?" should inspect the screen and answer.
- "Open settings" should perform a small action and report the outcome.
- "Send Adam I'll be late" should draft and confirm before sending.
- "Tell me when this changes" should create a watcher.
- "Remember that I prefer short direct replies" should save/update memory.
- "Stop" should stop the active agent or watcher, depending on context.

Implementation:

- Start model-driven with a strict JSON decision schema.
- Add deterministic guardrails around stop/cancel, emergency, policy failures,
  and unavailable data sources.
- Keep small fast local heuristics only as safety rails, not as the primary
  command router.

### 7. Agent Runtime

Purpose: execute longer tasks safely.

Loops:

- Chat answer loop: no device action.
- Screen question loop: observe -> answer.
- Device task loop: observe -> plan -> act -> observe.
- Realtime voice loop: low-latency audio with tool calls.
- Background watcher loop: check condition -> maybe act/deliver.

Runtime requirements:

- Session IDs.
- Max step count.
- Max wall time.
- Interrupt/cancel.
- Pause/resume.
- Review-required state.
- Tool-call audit.
- Screenshot/UI-tree trace.
- Failure reason.
- User-visible status.

The current "I need review before continuing" behavior should become a formal
state with a card explaining what review is needed and a button to continue,
edit, or stop.

## Systemwide UI Primitives

### AI Island

Persistent top-edge affordance.

States:

```text
idle
listening
thinking
answer_ready
action_running
watching
needs_review
error
```

Interactions:

- Press Volume Up + Volume Down together: primary hardware trigger to start the
  agent, stop the active agent, or interrupt the current session.
- Tap: compact prompt.
- Long press: screen awareness.
- Swipe down: AI sheet.
- Island trigger: start, stop, or expand the active agent surface.

### AI Sheet

System overlay that can move through:

```text
compact answer
expanded conversation
task workspace
```

It should be available from the island, screenshot flow, keyboard, notification
cards, and app chips.

### Screen Selection Layer

Flow:

```text
dim current screen
select region/text/object
ask about this
show answer card
offer actions
```

Suggested actions:

```text
summarize
translate
extract text
add to calendar
search web
find similar
save to note
compare
ask follow-up
```

### Action Confirmation Cards

In reviewed mode, sensitive actions get a structured confirmation:

```text
Intent
Target app/contact/account
Content to send/change
Source context
Risk
Buttons: edit, approve, cancel
```

Confirmation cards should not make the product timid. They are one operating
mode, not the whole product.

### Operating Modes

OpenPhone should support explicit agent operating modes:

```text
reviewed
  Ask before medium/high/critical actions.
  Best for normal users and new integrations.

trusted_autopilot
  User grants scoped autonomy for a task, app, contact, time window, or action
  family.
  Example: "For the next 10 minutes, handle this travel booking flow."

yolo
  Codex-style autonomy.
  The agent can execute within the user's configured YOLO policy without asking
  every time.
  The UI stays visibly active, all actions are audited, and Volume Up + Volume
  Down is a hard interrupt.

dry_run
  The agent plans and previews actions but does not execute them.
```

YOLO mode is a real feature, not a hidden debug flag. It needs:

- A clear visual state in the island/glow/sheet.
- Per-session and persistent profiles.
- Scope controls by app, capability, contact/account, and time.
- A hard hardware stop using Volume Up + Volume Down.
- A complete audit trail.
- Post-run summary of what changed.
- Easy rollback/undo where the underlying app supports it.

The policy engine still exists in YOLO mode. It enforces the user's chosen YOLO
profile instead of repeatedly prompting. The point is to make autonomy explicit
and controllable, not to remove the OS action layer.

Current implementation checkpoint:

- `reviewed`, `yolo`, and `dry_run` are exposed in Advanced settings and
  persisted.
- Registered action tools now use `openphone_action_registry.json`
  authorization policy before execution.
- The first YOLO profile is intentionally bounded to medium-risk action
  families such as memory, commitments, watchers, notification actions,
  settings/background/network operations. High-risk send/share/call/account
  classes still require explicit approval.
- Dry-run mode now intercepts action/control tools before execution and returns
  a structured `dry_run.preview`, including the tool name, capability, risk,
  arguments, and registered action metadata. This is useful for policy testing,
  agent debugging, and showing users what the agent would do before they grant
  autonomy.

### AI Notification Cards

For proactive events:

```text
Title
Short reason
Source
Suggested actions
Snooze / stop / settings
```

## App Integrations

### Notifications

What OpenPhone should support:

- Index notification title/text/app/time.
- Cluster related notifications.
- Summarize noisy threads.
- Extract commitments and reminders.
- Surface relevant context during calls or tasks.
- Let the user ask "what did I miss?"

Implementation:

- Prototype with a privileged notification listener.
- Move deeper integration into framework/SystemUI where needed.
- Respect sensitive notification redaction.
- Add per-app indexing toggles.

### Messages

What OpenPhone should support:

- Search messages by natural language.
- Summarize a thread.
- Extract commitments.
- Draft replies.
- Send with confirmation, trusted autopilot authorization, or YOLO-profile
  authorization depending on the active operating mode.
- Contextual chips like "Find photos", "Create reminder", "Add to calendar",
  "Draft reply."

Implementation:

- Ship or own the default OpenPhone Messages surface so SMS/MMS/RCS-style
  workflows have first-class context, drafting, sending, chips, and indexing.
- Add connector APIs for messaging apps that want deeper integration.
- For third-party encrypted apps that do not integrate, use notification
  context, share sheets, and screen awareness as fallback behavior, but do not
  treat that fallback as the finished product.

### Calendar

Capabilities:

- Natural-language event creation.
- Search upcoming events.
- Attach source messages/emails/screenshots.
- Proactive context cards near relevant times.

### Contacts

Capabilities:

- Resolve people across messages, call log, calendar, and assistant memory.
- Maintain relationship facts only as editable memories with evidence.
- Avoid inventing contact relationships.

### Phone

Capabilities:

- Call context cards.
- Pull confirmation numbers from messages/email/calendar.
- Show relevant notes before/during a call.
- Draft follow-up after a call.

Sensitive area:

- Call recording/transcription needs explicit jurisdiction-aware policy. Do not
  ship silent recording.

### Browser

Capabilities:

- Ask about current page.
- Summarize tabs.
- Topic-group tabs.
- Watch page for changes.
- Fill forms with review.
- Extract products/events/addresses.

Implementation:

- Build or adopt an OpenPhone browser surface where we own tab/page context,
  semantic page extraction, page watching, tab grouping, and form review.
- For third-party browsers, use screen awareness and share intents as fallback
  behavior, not as the target experience.

### Keyboard and Text Fields

Capabilities:

- Rewrite selected text.
- Draft in current text field.
- Tone controls.
- Proofread.
- "Ask about this text."

Implementation:

- Build an OpenPhone keyboard/IME or integrate with the default keyboard.
- Add selection-toolbar actions where possible.
- Never replace text without preview/undo.

### Screenshots

Capabilities:

- Ask AI from screenshot preview.
- OCR and summarize.
- Translate.
- Extract event/contact/address.
- Save to memory or note.

### Photos and Camera

Full capabilities:

- Ask about an image.
- Visual search.
- Object selection.
- Cleanup/extend/edit via model tools.
- Camera AI mode.

Build this after screen, messages, notifications, and browser because it depends
on the same context, memory, tool, and confirmation substrate. It is not
optional; it is a full system integration once the substrate can support it
properly.

### Maps and Location

Capabilities:

- Location-triggered commitments.
- "When I arrive..." reminders.
- Trip context.
- Address extraction from messages/calendar.

Privacy:

- Location memory must be opt-in with clear retention rules.

## Data Access Reality

Because OpenPhone is a ROM, we can build privileged/system services that normal
apps cannot. That does not mean we should indiscriminately read everything.

Practical access model:

```text
System-owned sources
  notifications, app/window state, screenshots, input, settings, audit

OpenPhone-owned default app sources
  SMS, phone, browser, launcher, keyboard, camera, gallery

App-integrated sources
  apps that expose OpenPhone connectors or Android search/index APIs

Fallback sources
  share sheet, document picker, screenshot/OCR, accessibility during development
```

For encrypted third-party apps, the phone may see notifications and current
screen state, but it should not claim direct database access unless the app
integrates or the user exports data.

The product answer is to own the core default apps where AI integration matters:
Messages, Browser, Launcher/Search, Keyboard, Gallery/Camera, Phone, Calendar,
and eventually Email/Files. Third-party fallback keeps the OS useful, but the
excellent experience comes from OpenPhone-owned or OpenPhone-integrated apps.

## Privacy, Policy, And Safety

### Required Settings Surfaces

- Data Sources: toggles for notifications, messages, calendar, contacts, calls,
  browser, screenshots, photos, files, location.
- Memories: view/edit/delete/export.
- Commitments: active/past/snoozed/dismissed.
- Watchers: active page watches, notification watches, recurring checks.
- Action Grants: per-app/per-capability permissions.
- Autonomy Modes: reviewed, trusted autopilot, YOLO, dry run, default profile,
  per-app scopes, and hardware interrupt behavior.
- Audit Log: model calls, tool calls, confirmations, actions, proactive events.
- Model Provider: broker endpoint, session state, model choices.

### Risk Classes

```text
low
  answer, summarize, search local context, open app

medium
  draft, navigate, create reminder, create calendar event, fill form

high
  send message, place call, share file, modify account/app setting

critical
  purchase, payment, delete data, security setting, credential change
```

Policy must live below UI and below the model adapter.

### Audit Requirements

Every action should record:

- Requesting surface.
- User input.
- Model decision ID.
- Context sources used.
- Action proposed.
- Authorization result: confirmed, autopilot-allowed, YOLO-allowed, denied,
  blocked, or expired.
- Executor result.
- Timestamp.
- Failure reason.

## Model Strategy

The first product principle is capability. Use remote frontier models, hosted
tools, and cloud-side orchestration when they make the phone more intelligent.
Keep the broker boundary so providers can change and device secrets stay off the
phone, but do not constrain v1 to local-only inference.

### Realtime

Use OpenAI Realtime for low-latency voice and interruption:

- Live voice conversation.
- Start/stop via island and volume chord.
- Tool calls routed through the same ActionRegistry and PolicyEngine.
- Server/broker-minted ephemeral credentials.

Do not put provider API keys on the phone. Use broker-issued session or
ephemeral credentials.

### Responses / CUA

Use the Responses API for:

- Structured orchestrator decisions.
- Screen-question answering.
- Computer-use style action selection.
- Longer tasks where latency is less important than correctness.

### Local Models

Use local models when they improve latency, cost, privacy, or offline behavior:

- Lightweight classification.
- Embeddings.
- OCR cleanup.
- Sensitive memory review.
- Fast small routing fallback.

Do not block product quality on local model quality. The first principle is the
best agentic OS experience; local execution is an optimization path that should
be added where it improves the product.

## Implementation Roadmap

The phases below are dependency order, not permission to ship half-baked
features. Each phase should reach a product-quality vertical slice before the
next phase depends on it. If a phase is too large, split the engineering work
inside the phase, but keep the user-facing target complete.

### Phase 0: Stabilize Current Agent Surface

Goal: stop obvious UX/runtime failures before building new substrate.

Tasks:

- Remove remaining hardcoded routing that starts tasks for ordinary chat.
- Ensure "hello" is chat, "what's on my screen" is screen answer, and "open X"
  is an action.
- Make mic input follow the orchestrator instead of always starting a task.
- Formalize agent states: idle, listening, thinking, acting, needs_review,
  stopped, failed.
- Ensure active-agent glow and island always clear after stop/failure.
- Add smoke tests for island trigger, volume trigger, mic chat, text chat, and
  stop.

Exit criteria:

- User can chat normally.
- User can ask about the screen.
- User can start and stop a task.
- No app flash before task start.
- No stale active-agent UI after stop.

### Phase 1: Orchestrator v1

Status 2026-06-09: **not actually done.** A class named
`OpenPhoneOrchestrator` exists, but it is a keyword guard around a 3-mode
model route call, backend keyword handlers run before it, and the local
heuristic adapter became the de facto task router. The Architecture Audit
and Near-Term Priorities Phase B define the real implementation.

Goal: replace the brittle command router with a model-driven, coding-agent-style
decision and task runtime.

Tasks:

- Add `OpenPhoneOrchestrator` package in `OpenPhoneAssistant` first.
- Define `OrchestratorInput`, `OrchestratorDecision`, and JSON schema.
- Implement model-backed decision call via the existing broker/Responses path.
- Keep deterministic fallback only for stop/cancel and offline development.
- Route text and mic through the same orchestrator.
- Add task-state primitives: goal, current step, completed steps, pending
  actions, verification result, blocked reason, and final summary.
- Add explicit completion criteria so the agent cannot reply "Done" unless it
  has verified completion or clearly reports why it stopped.
- Add interrupt/steer handling for Volume Up + Volume Down and island stop.
- Add operating mode handling: reviewed, trusted_autopilot, yolo, dry_run.
- Add tests for:
  - chat-only request
  - screen question
  - app open request
  - sensitive message send request
  - YOLO-scoped task
  - watcher creation request
  - memory request

Exit criteria:

- No keyword router decides core behavior.
- The model can choose answer/clarify/screen/action/watch/memory/task-step.
- Multi-step tasks have visible state, verification, interruption, and final
  summaries.
- The UI displays the decision state clearly.

### Phase 2: Context Index v1

Goal: build the searchable personal context substrate.

Tasks:

- Create `OpenPhoneContextIndexService`.
- Add SQLite schema and migrations.
- Index assistant conversations and agent action traces.
- Add notification ingestion.
- Add contact and calendar ingestion where permissions/privileges are ready.
- Add FTS search API.
- Add `context.search` tool.
- Add Settings toggles for indexed sources.
- Add audit events for indexing and retrieval.

Exit criteria:

- User can ask about recent assistant history and notifications.
- Orchestrator can retrieve context before answering.
- Context results include source and timestamp.

### Phase 3: Memory v1

Goal: durable user memory without polluting it with raw events.

Tasks:

- Create `OpenPhoneMemoryService`.
- Add memory schema, CRUD APIs, and search.
- Add `MemoryReviewWorker` after assistant turns.
- Extract candidate memories from assistant conversations first.
- Add Memories UI in the side menu.
- Add user edit/delete/disable.
- Add `memory.search`, `memory.propose`, `memory.update` tools.
- Add memory injection into orchestrator/model prompts.

Exit criteria:

- User can say "remember that..."
- The system can retrieve relevant saved preferences.
- The user can inspect and delete memory.
- Raw notifications/messages do not automatically become memory.

### Phase 4: Commitments v1

Goal: track open loops.

Tasks:

- Create `OpenPhoneCommitmentService`.
- Add commitment schema and status transitions.
- Extract explicit commitments from assistant turns.
- Add proposed commitments from messages/notifications only behind review.
- Add Commitments UI.
- Add notification delivery for due commitments.
- Add snooze/dismiss/complete.

Exit criteria:

- User can create, list, complete, snooze, and delete commitments.
- Fired commitments explain their source.
- Commitments survive reboot.

### Phase 5: Watchers v1

Goal: background proactivity.

Tasks:

- Create `OpenPhoneWatcherService`.
- Add durable watcher schema.
- Implement time-based watchers.
- Implement notification-pattern watchers. The assistant-local version is done;
  the OS-owned service version still needs to promote the same contract.
- Implement browser page watchers as part of the OpenPhone browser integration.
- Add scheduler with jitter, timeout, retry, stuck repair, and failure alerts.
- Add `watchers.create`, `watchers.stop`, `watchers.list`.
- Add AI notification cards for watcher output.

Exit criteria:

- User can ask "tell me when..." and see an active watcher.
- Watchers survive reboot.
- No-op checks do not spam.
- Failed watchers surface actionable errors.

### Phase 6: Action Registry And Connectors v1

Goal: move from ad hoc tools to formal OS/app actions.

Tasks:

- Define action registry JSON/schema.
- Map every model-visible tool to a capability/risk class. The first registry
  version covers current model tools; notification, calendar, contacts,
  messages, phone, and browser page fetch now have first semantic executors,
  while richer browser/app actions still need deeper executors.
- Add executor interfaces per action family.
- Implement semantic actions for notifications, calendar, contacts, messages,
  phone, apps, and browser before using pointer fallback. Notification,
  calendar, contacts, messages, phone, and browser page fetch now have first
  semantic executors.
- Add confirmation cards for medium/high/critical actions.
- Add contract tests for action registry coverage.

Exit criteria:

- The model cannot call an unregistered action.
- Every action has policy, audit, and tests.
- Sensitive actions require review.

### Phase 7: System UI v1

Goal: make the OS-native surfaces real.

Tasks:

- Move AI Island deeper into SystemUI where appropriate.
- Add swipe-down AI sheet.
- Add compact answer cards.
- Add action confirmation cards.
- Add screen selection layer.
- Add AI notification card style.
- Keep OpenPhoneAssistant as history/settings/deep workspace, not the only
  interface.

Exit criteria:

- Asking from island does not feel like opening an app.
- Screen questions can be answered in place.
- Actions can be confirmed without jumping through the chat app.

### Phase 8: Messages, Notifications, Calendar, Phone

Goal: complete the first core app integrations.

Tasks:

- Notifications:
  - cluster/summarize
  - commitments from notifications
  - "what did I miss?"
- Messages:
  - default SMS role or OpenPhone Messages app
  - thread search
  - draft/send with confirmation
  - contextual chips
- Calendar:
  - natural-language create
  - event search
  - source-linked creation
- Phone:
  - call context cards
  - relevant confirmation codes
  - post-call follow-up drafts

Exit criteria:

- The assistant can answer and act using actual phone context, not only screen
  pixels.
- User-visible integrations are narrow, useful, and reliable.

### Phase 9: Browser, Keyboard, Screenshots

Goal: turn common intent points into full AI surfaces.

Tasks:

- Browser:
  - ask about page
  - summarize tabs
  - watch page
  - form-fill review
- Keyboard:
  - rewrite/proofread/draft
  - tone chips
  - text replacement preview
- Screenshots:
  - Ask AI from screenshot preview
  - OCR/extract
  - event/contact/address extraction

Exit criteria:

- User can invoke AI where they already are.
- The chat app becomes the archive/deep workspace, not the default entry point.

### Phase 10: Framework Hardening

Goal: productionize privileged integration.

Tasks:

- Move screen/UI hierarchy extraction from assistant-side development path to
  framework-owned services.
- Harden SELinux domains.
- Separate assistant UI, orchestrator, and executors.
- Strengthen broker identity/session handling.
- Add production-grade audit storage and export.
- Add OTA-safe migrations for context/memory/commitment databases.
  (DONE 2026-06-11, v111: all four stores — watcher, commitment, memory,
  context index — use stepwise additive `onUpgrade` instead of
  DROP TABLE; validated on device with a real v1→v2 upgrade over live
  data.)
- Add release/eval test suites.

Exit criteria:

- OpenPhone can survive normal phone lifecycle events: reboot, network loss,
  app crash, model failure, rotation, lock/unlock, and source deletion.

## Initial File/Module Layout

Short-term inside `OpenPhoneAssistant`:

```text
overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/
  orchestrator/
    OpenPhoneOrchestrator.kt
    OrchestratorInput.kt
    OrchestratorDecision.kt
    OrchestratorSchemas.kt
  context/
    ContextIndexStore.kt
    ContextIndexService.kt
    ContextSearchTool.kt
  memory/
    MemoryStore.kt
    MemoryService.kt
    MemoryReviewWorker.kt
    MemoryTools.kt
  commitments/
    CommitmentStore.kt
    CommitmentService.kt
    CommitmentTools.kt
  watchers/
    WatcherStore.kt
    WatcherScheduler.kt
    WatcherService.kt
    WatcherTools.kt
  actions/
    ActionRegistry.kt
    ActionDefinition.kt
    ActionExecutor.kt
    ConfirmationPlanner.kt
```

Medium-term framework/system modules:

```text
frameworks/base/openphone/
  java/android/openphone/
    OpenPhoneContextManager.java
    OpenPhoneMemoryManager.java
    OpenPhoneActionManager.java
    OpenPhoneWatcherManager.java

frameworks/base/services/core/java/com/android/server/openphone/
  OpenPhoneContextService.java
  OpenPhoneActionService.java
  OpenPhoneWatcherService.java
  OpenPhoneAuditService.java
```

Contracts:

```text
docs/contracts/
  orchestrator-decision.schema.json
  context-event.schema.json
  memory.schema.json
  commitment.schema.json
  watcher.schema.json
  action-registry.schema.json
```

## Testing And Evaluation

### Unit Tests

- Orchestrator decision parsing.
- Context indexing/search.
- Memory dedupe/update.
- Commitment status transitions.
- Watcher scheduling/backoff/stuck repair.
- Action registry policy coverage.

### Device Smoke Tests

- Text chat says hello.
- Mic chat says hello.
- Ask "what is on my screen?"
- Open Settings.
- Start/stop with island.
- Start/stop with volume chord.
- Create reminder/commitment.
- Reboot and verify commitment/watcher survives.
- Stop agent and verify glow clears.

### Evals

Create repeatable eval tasks:

```text
chat_general
screen_describe
screen_extract_text
open_app
settings_change_with_confirmation
message_draft_with_confirmation
notification_summary
calendar_create
memory_recall
commitment_fire
watcher_noop_suppression
watcher_fire
```

Metrics:

- Task success.
- Wrong-action rate.
- Confirmation correctness.
- Time to first useful response.
- Number of unnecessary app switches.
- Number of stale UI states.
- Retrieval precision.
- Proactive notification usefulness.
- Battery/network cost.

## Build And Deployment Discipline

Use whatever build environment gets us to a working phone fastest. For full
Android builds, the cloud EC2 tree is currently the practical default. For app
changes, local iteration is fine when it is faster. The product priority is a
working, excellent agentic OS; build locality is an optimization detail.

Current practical workflow:

```bash
rsync app overlay to EC2 when using the Android tree there
build focused APK or framework target on EC2 when needed
pull artifact into .worktree/artifacts
install with scripts/push-assistant-apk.sh
run scripts/check.sh locally
capture screenshots / device smoke tests
```

Do not mix large Android build artifacts into this repo.

## Near-Term Priorities

Revised 2026-06-09 after the architecture audit. The previous sequence built
substrate stores and connector slices but skipped the orchestrator spine,
which let keyword routing metastasize. The new sequence fixes the spine
before resuming connectors.

### Phase 0: Restore a buildable, honestly-checked tree (hours)

1. Implement or remove the dangling
   `isMessageCalendarEventGoal`/`messageCalendarEventQuery` references in
   `LocalHeuristicModelAdapter.java` so the assistant compiles.
2. Add a Java syntax/compile gate to `./scripts/check.sh` so unresolved
   references can never pass repo checks again.

### Phase A: Registry-driven tool surfaces (days)

Goal: `openphone_action_registry.json` becomes the single source of truth
for everything the model sees.

1. Build a `ToolCatalog` loader that reads the installed action registry and
   generates:
   - the tool list + argument documentation injected into the Responses
     agent prompt (replacing the hand-written ~200-line string),
   - the Realtime session tool definitions,
   - the allowed-tool checks in both cloud adapters (replacing the
     ~50-clause `isAllowedTool()` if-chain).
2. Keep `FrameworkToolExecutor` as the one hand-written dispatch point; its
   switch is acceptable because execution logic is inherently per-tool.
3. Extend check.sh: every registry action must have an executor case, and no
   adapter may contain a hardcoded tool-name allowlist.
4. Acceptance: adding a tool = 2 JSON entries + 1 executor case, nothing
   else.

### Phase B: Orchestrator v1, model-first (days)

Goal: one decision point, model-driven, with deterministic logic demoted to
safety rails — exactly as specified in "OpenPhoneOrchestrator" above.

1. Implement the full `orchestrator_decision` schema:
   `answer | clarify | retrieve | inspect_screen | act | watch | memory |
   stop`, with `proposed_actions`, `confirmation_plan`, and
   `delivery_surface`.
2. The model route call sends the registry-generated tool catalog plus
   recent conversation, and returns a structured decision. One-shot tool
   decisions (e.g., "what did I miss?") execute directly through the policy
   layer without spinning up the full screenshot task loop.
3. Move `AssistantActivityBackend`'s `handleExplicit*` watcher/commitment/
   memory/notification handlers behind the orchestrator: the model decides
   `watch`/`memory`/`act`; deterministic parsing only assists argument
   extraction afterward.
4. Shrink `OpenPhoneOrchestrator`'s keyword guards to true safety rails:
   stop/cancel interception and offline fallback only. Remove the
   `guardDecision` keyword override of model decisions.
5. Demote `LocalHeuristicModelAdapter` to genuinely offline-dev-only. No new
   `isXxxGoal()` heuristics, ever. Existing heuristics remain only where
   deterministic device smoke tests need them, and each one is marked as a
   smoke-test shim.
6. Acceptance (device-validated): "hello" chats; "what's on my screen?"
   answers in place; "what did I miss?" runs one tool; "remind me if Sarah
   doesn't reply" creates a watcher/commitment via model decision; "send
   Adam I'll be late" drafts then stops at a high-risk confirmation; "stop"
   always stops — all without any pre-orchestrator keyword route firing.

### Phase C: Resume connector slices on the new spine

1. Finish `message_calendar_event_create` / 
   `calendar.create_event_from_message` as the first tool shipped the new
   way (EC2 build, install, reviewed + YOLO smokes, docs updated). It is the
   proof that a new tool now costs 2 JSON entries + 1 executor case.
2. Then continue the previous connector backlog, re-ranked:
   - model-backed semantic watcher evaluation,
   - browser/page context deepening,
   - AI Sheet and screen selection layer polish,
   - messages/calendar/phone integration depth,
   - promotion of assistant-local stores into OS-owned services
     (Phase 10 hardening track). (DONE 2026-06-11: the context index
     is the OS-owned `openphone_context` system service — patches
     0014/sepolicy-0002, OTA sideloaded, 1297 legacy events migrated,
     end-to-end `context_search` smoke green. The memory, commitment,
     and watcher stores are now the OS-owned `openphone_assistant_data`
     system service — patches 0015/sepolicy-0003, OTA sideloaded;
     2 memories + 4 commitments + 9 watchers migrated 1:1 with row
     ids preserved; end-to-end `memory_save` binder write smoke green
     under YOLO and autonomy gate verified under `reviewed`.)

This order matters. The substrate stores exist and work; the missing piece
is the intelligence spine that uses them. If we keep building app demos on
keyword routers, every new feature makes the eventual orchestrator migration
more expensive. Fix the spine once, then every connector gets cheaper.

## Open Questions

- Should OpenPhone ship its own default SMS app immediately, or start with
  notification/message-lite context and defer full messaging ownership?
- Should context indexing use AppSearch, SQLite/FTS, or a hybrid? The default
  should be SQLite/FTS first because we own the data model, with AppSearch
  evaluated for structured cross-type search.
- Which memories auto-save versus require review?
- How much proactive behavior is enabled by default?
- What is the first OpenPhone browser strategy: fork existing browser, build a
  minimal WebView browser, or integrate with a chosen open-source browser?
- Which model calls are direct-to-broker Realtime versus Responses?
- Which YOLO profiles should exist by default, and which actions should be
  allowed in each profile?
- Which cloud-side services should be built first: hosted context search,
  reranking, memory review, web watching, or task execution support?

## Reference Links

- OpenPhone architecture: `docs/ARCHITECTURE.md`
- OpenPhone roadmap: `docs/ROADMAP.md`
- OpenPhone specification: `SPEC.md`
- OpenAI Realtime API: https://platform.openai.com/docs/guides/realtime/
- OpenAI Realtime WebRTC: https://platform.openai.com/docs/guides/realtime-webrtc
- OpenAI Responses API: https://platform.openai.com/docs/api-reference/responses
- OpenAI computer use guide: https://platform.openai.com/docs/guides/tools-computer-use
- Android AppSearch: https://developer.android.com/guide/topics/search/appsearch
- Android NotificationListenerService:
  https://developer.android.com/reference/android/service/notification/NotificationListenerService
- Android AccessibilityService:
  https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Android RoleManager SMS role:
  https://developer.android.com/reference/androidx/core/role/RoleManagerCompat#ROLE_SMS
- Google Gemini / Android AI announcements reviewed during planning. Re-check
  current official Google sources before implementing feature-specific claims.
- TrueMemory repo: https://github.com/buildingjoshbetter/TrueMemory
- TrueMemory paper: https://arxiv.org/abs/2605.04897
- Local OpenClaw reference repo: `../openclaw`
- Local Hermes Agent reference repo: `../hermes-agent`

## Definition Of Success

OpenPhone is working when the user can naturally say:

```text
"What am I looking at?"
"What did I miss?"
"Find the message where Sarah sent the address."
"Remind me if he does not reply."
"Tell me when this page changes."
"Draft a reply in my usual tone."
"Create the calendar event from this screenshot."
"Call the restaurant and show my reservation info."
"Stop."
```

And the phone correctly decides whether to answer, retrieve, inspect, ask,
confirm, act, watch, remember, or stop.

That is the product.
