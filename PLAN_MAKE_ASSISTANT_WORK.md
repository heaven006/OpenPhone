# Plan: Make The OpenPhone Assistant Work

This document defines the next engineering plan: turn the booting
OpenPhoneAssistant and `openphone_agent` framework service into a working
AI-first phone assistant that can observe the screen during an active task,
reason with a model, visibly control the device, and remain governed by OS
policy, confirmation, and audit.

The goal is not a chatbot. The goal is an OS-level agent loop:

```text
user trigger -> active task -> observe screen -> model decides ->
OS validates -> cursor/action preview -> execute action -> audit -> repeat
```

## Current Starting Point

Already working on the Pixel 9a:

- Full `openphone_tegu` product boots.
- `org.openphone.assistant` is installed as a privileged persistent system app.
- `OpenPhoneAgentManagerService` registers as the framework Binder service
  `openphone_agent`.
- The framework service has early task, screen context, action, policy,
  confirmation, and audit plumbing.
- The assistant can launch, create a task, call framework methods, and show
  basic status/audit output.
- The assistant now has a task console, persistent notification trigger, Quick
  Settings tile, visible cursor/status overlay, model adapter boundary, and a
  local heuristic proof-of-loop agent.
- The framework now has active-task-gated `getScreen(...)`,
  `watchScreen(...)`, `stopTask(...)`, and pointer event APIs.
- `getScreen(...)` can now return an opt-in downscaled JPEG screenshot payload
  as base64, guarded by active task state and secure-layer checks.

Not yet working:

- No real cloud/realtime model loop. The OpenAI Realtime adapter is a guarded
  placeholder until transport, session security, and broker design are added.
- No OCR/UI-tree extraction or vision-model integration yet. Screenshot pixels
  are available through `getScreen(...)`, but no model adapter consumes them in
  the checked-in build.
- No realtime voice interface.
- No SystemUI-owned action overlay. The first visible cursor/status surface is
  assistant-owned.
- Action execution and the new agent loop still need physical-device
  verification and hardening.

## Product Target

V1 should demonstrate:

```text
The user asks the phone to do a task.
The AI sees the current screen while the task is active.
The AI operates apps through OS-mediated taps, typing, swipes, app launches,
clipboard, and share actions.
The phone visibly shows what the AI is doing.
The OS can stop, confirm, deny, and audit every action.
```

The first demo target:

```text
User: "Open Settings and turn on dark mode."

OpenPhone:
- starts an active task,
- captures the current screen,
- sends context to the model,
- opens Settings,
- watches the screen while navigating,
- shows a cursor/tap indicator,
- performs the necessary taps,
- asks confirmation if needed,
- records audit events,
- reports completion.
```

## Architecture

### Runtime Components

```text
OpenPhoneAssistant
  - user trigger UI
  - task view
  - model adapter client
  - voice/text session
  - user confirmation UI

OpenPhoneAgentManager
  - hidden framework manager API
  - typed later, JSON for now

OpenPhoneAgentManagerService
  - active task registry
  - screen capture policy
  - screen context provider
  - action executor
  - pointer event publisher
  - policy and confirmation engine
  - durable audit log

OpenPhonePointerOverlay
  - visible cursor
  - tap ripple
  - swipe trail
  - typing indicator
  - active-agent banner/chip

ModelAdapter
  - OpenAI/GPT Realtime adapter
  - Anthropic/Claude adapter
  - Gemini adapter
  - local model adapter later
```

### Control Boundary

The model must not directly control Android. The model only calls structured
tools. The framework service decides whether to execute.

```text
model tool call:
  tap(x, y, reason)

OpenPhoneAgentManagerService:
  validates active task
  validates capability grant
  checks policy
  asks confirmation if needed
  emits cursor preview
  injects input
  records audit
```

This boundary is the product. It is what makes OpenPhone different from an
app-only automation demo.

## Active Task Screen Observation

The agent should see the screen while it is working, but not by sending raw
continuous video by default.

Use adaptive observation:

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
  optional low-rate watch while waiting

High-attention burst:
  short 2-5 fps sampling for loading screens, animations, camera, maps,
  scrolling lists, or other rapidly changing UI
```

Default policy:

```text
task_active_required: true
visible_indicator_required: true
default_watch_fps: 1
max_burst_fps: 5
audit_each_capture: true
sensitive_screen_redaction: true
```

### Screen APIs

Initial JSON APIs:

```json
{
  "method": "get_screen",
  "params": {
    "task_id": "task-123",
    "include_screenshot": true,
    "include_ocr": true,
    "include_activity": true
  }
}
```

```json
{
  "method": "watch_screen",
  "params": {
    "task_id": "task-123",
    "fps": 1,
    "duration_ms": 10000,
    "reason": "waiting for settings page to load"
  }
}
```

Screen response:

```json
{
  "screen_id": "screen-456",
  "timestamp_ms": 1770000000000,
  "package": "com.android.settings",
  "activity": "com.android.settings.Settings",
  "display_width": 1080,
  "display_height": 2424,
  "screenshot_ref": "content://openphone/screen/task-123/screen-456",
  "ocr": [
    {
      "text": "Display",
      "bounds": [42, 600, 230, 660]
    }
  ],
  "risk_flags": []
}
```

### Implementation Options

Preferred V1 path:

- Add screenshot capture in `OpenPhoneAgentManagerService` using privileged
  framework APIs available to system components.
- Return image references or compressed bytes through a controlled path.
- Start with screenshots plus foreground package/activity.
- Add OCR either:
  - in the assistant process using an embedded OCR library, or
  - in the cloud/model vision path if the selected model supports image input.

Later:

- Add UI tree extraction where available.
- Add notification context.
- Add secure redaction for passwords, payment screens, private notifications,
  incognito contexts, and blocked apps.

## Model Layer

### Principle

The OS service must be model-agnostic.

The assistant owns model adapters. The framework owns capabilities.

```text
OpenPhoneAssistant -> ModelAdapter -> model provider
OpenPhoneAssistant -> OpenPhoneAgentManager -> OS tools
```

### Provider Options

`OpenAIRealtimeAdapter`

- Good for low-latency voice-first demos.
- Supports speech interaction and function/tool calls.
- Use for the most impressive first user experience.

`OpenAITextVisionAdapter`

- Good for simpler observe/act loops.
- Easier to debug than realtime audio.

`ClaudeAdapter`

- Good for computer-use style reasoning.
- Good backup if task planning quality is better.

`GeminiAdapter`

- Relevant because Android ecosystem integration may be strong.
- Keep as adapter, not as architecture dependency.

`LocalModelAdapter`

- Later milestone.
- Useful for privacy, offline, and OEM differentiation.

### V1 Recommendation

Build the model interface as tool-calling JSON first. Start with text input,
then add realtime voice.

Order:

1. Text prompt -> model -> tool call -> action.
2. Screenshot/vision -> model -> tool call -> action.
3. Realtime voice -> same tool interface.

This avoids coupling core OS work to any one model provider.

## Tool Contract

Initial tools exposed to the model through the assistant:

```text
get_screen()
watch_screen(fps, duration_ms, reason)
open_app(package_or_label)
tap(x, y, reason)
long_press(x, y, duration_ms, reason)
swipe(start_x, start_y, end_x, end_y, duration_ms, reason)
type_text(text, reason)
press_key(key, reason)
set_clipboard(text, reason)
paste(reason)
share_text(text, chooser_title, reason)
ask_user_confirmation(summary, risk, action_json)
finish_task(summary)
fail_task(reason)
```

All tools require:

- active task ID,
- user-visible reason,
- capability mapping,
- audit event.

Risky tools require confirmation.

## Action Execution

### Existing Actions To Verify

The framework already has early support for:

- app launch,
- Back,
- Home,
- Recents,
- tap,
- long press,
- scroll/swipe,
- text input,
- clipboard write,
- clipboard paste,
- confirmed share action.

Next work is physical validation:

- verify coordinate system on Pixel 9a,
- verify tap timing,
- verify swipe duration,
- verify keyboard/text entry behavior,
- verify actions across Settings, browser, messages-like apps, and launcher,
- record failures in `docs/TESTING.md`.

### Action Safety

Actions should fail closed unless:

- task is active,
- assistant is trusted,
- capability is granted,
- policy allows the action or user confirmed it,
- display is controllable,
- target screen is not blocked.

Sensitive cases:

- password fields,
- payment flows,
- account deletion,
- sending messages,
- calling contacts,
- purchasing,
- sharing private data,
- installing apps,
- changing security settings.

## Visible Cursor And Agent Presence

Yes, OpenPhone should show a cursor when the agent controls the phone.

Do not rely on Android's physical mouse cursor for V1. Build an OpenPhone
pointer overlay.

### V1 Overlay Behavior

When the agent is active:

- show small active-agent chip,
- show cursor dot,
- animate cursor movement to target before tap,
- show tap ripple,
- show swipe trail,
- show typing indicator near target field,
- show stop button or notification action.

### Implementation Path

Fastest V1:

- Implement overlay inside `OpenPhoneAssistant` using privileged overlay
  permissions.
- Framework service emits pointer events through Binder.
- Assistant renders cursor/tap/swipe visuals.

Better V1.5:

- Move overlay rendering into SystemUI.
- Framework publishes pointer/action state.
- SystemUI owns visual affordance and stop control.

Target API:

```json
{
  "event": "pointer_move",
  "task_id": "task-123",
  "x": 520,
  "y": 1300,
  "duration_ms": 250
}
```

```json
{
  "event": "tap_ripple",
  "task_id": "task-123",
  "x": 520,
  "y": 1300
}
```

## User Trigger

V1 triggers should be practical and visible:

- Assistant launcher icon.
- Persistent notification while agent is available.
- Quick Settings tile.

V1.5 triggers:

- long-press power button integration,
- lockscreen affordance,
- hardware button gesture if device supports it,
- voice activation while assistant session is open.

Later:

- wake word,
- background proactive suggestions,
- notification-based task suggestions.

Implementation order:

1. Improve assistant app entry screen.
2. Add persistent notification with `Start task` and `Stop` actions.
3. Add Quick Settings tile.
4. Add voice session button.

## Voice And Realtime

Realtime voice is a UX layer, not the control architecture.

Architecture:

```text
microphone -> realtime model session -> tool calls -> OpenPhone tools
OpenPhone observations/actions -> tool results -> realtime model -> speech
```

During an active voice task:

- stream microphone audio,
- send screenshots on demand or adaptive cadence,
- return tool results quickly,
- allow interruption,
- expose stop control.

Do not stream screenshots while idle. Do not capture screen while merely
listening unless the user starts a task.

## Policy, Confirmation, And Audit

Every task records:

- user request,
- start/end timestamps,
- apps observed,
- screen captures or capture references,
- model tool calls,
- policy decisions,
- user confirmations,
- actions executed,
- failures and retries.

Every action has:

- task ID,
- capability ID,
- reason,
- target app/activity,
- input coordinates or semantic target,
- policy decision,
- result.

Confirmation should be required for:

- sending or posting content,
- purchases/payments,
- deleting data,
- sharing files/private text,
- calling/messaging people,
- changing account/security settings,
- installing/uninstalling apps,
- granting permissions.

## Data And Privacy

Default privacy stance:

- no screen capture outside active tasks,
- clear visible indicator while observing,
- user can stop immediately,
- audit log is user-visible,
- sensitive screens can block or redact capture,
- model provider is explicit in settings,
- cloud model use is disclosed.

Future settings:

- choose model provider,
- disable cloud vision,
- disable voice,
- blocked apps,
- always-confirm apps,
- delete audit history,
- export audit history.

## Milestones

### Milestone 0: Clean Baseline

Status: mostly done.

Tasks:

- Full Pixel 9a product boots.
- Assistant package installed and privileged.
- Framework service registered.
- Licensing/docs cleaned.
- Build and bringup docs current.

Done criteria:

- `service check openphone_agent` returns `found`.
- Assistant launches.
- Repo checks pass.

### Milestone 1: Local Tool Loop Without Model

Goal: prove the action loop deterministically from the assistant UI.

Tasks:

- Add assistant UI controls for:
  - get current screen,
  - open app,
  - tap coordinates,
  - swipe,
  - type text,
  - Back/Home/Recents.
- Show action result and audit event.
- Add physical Pixel 9a test checklist.

Done criteria:

- From assistant UI, user can open Settings.
- User can tap a known coordinate.
- User can type into a focused field.
- Audit log records each step.

### Milestone 2: Screenshot Observation

Goal: assistant can obtain current screen image during an active task.

Tasks:

- Add framework screenshot capture API.
- Enforce active-task requirement.
- Add visible active-agent indicator.
- Return screenshot reference or compressed image payload.
- Add audit events for screen capture.
- Add basic redaction/blocklist hook.

Done criteria:

- Assistant can display current screenshot.
- Screenshot capture is denied without active task.
- Audit log shows capture event.

### Milestone 3: Cursor Overlay

Goal: user sees what the agent is about to do.

Tasks:

- Add overlay permission/config if needed.
- Implement cursor dot in assistant overlay.
- Render tap ripple before/after tap.
- Render swipe trail.
- Add stop action.
- Connect framework action execution to pointer events.

Done criteria:

- Agent tap visibly shows cursor and ripple.
- Swipe visibly shows trail.
- User can stop the active task.

### Milestone 4: Text Model Adapter

Goal: typed instruction can drive one or more tool calls.

Tasks:

- Define `ModelAdapter` interface.
- Implement one cloud text model adapter.
- Convert OpenPhone tools to model tool schema.
- Add assistant task prompt box.
- Add tool-result loop.
- Add max-steps and timeout limits.

Done criteria:

- User types "open Settings".
- Model calls `open_app`.
- OS executes.
- Audit records model request and action.

### Milestone 5: Vision Model Loop

Goal: model can use screenshot to choose actions.

Tasks:

- Send screenshot/context to model.
- Add coordinate normalization.
- Add post-action observe delay.
- Add retry logic.
- Add simple task planner prompt.

Done criteria:

- User asks a task requiring screen navigation.
- Model observes screenshot, taps visible target, observes again.
- Task completes or asks user for help.

### Milestone 6: Realtime Voice

Goal: voice-driven assistant can perform the same tool loop.

Tasks:

- Add microphone permission and voice session UI.
- Add realtime model adapter.
- Stream audio during active session.
- Map realtime tool calls to existing OpenPhone tools.
- Speak concise status and completion messages.
- Support interruption/stop.

Done criteria:

- User speaks "open Settings".
- Assistant responds by voice and opens Settings.
- Same policy/audit path is used as text model.

### Milestone 7: Quick Settings And Notification Trigger

Goal: assistant is easy to start and stop.

Tasks:

- Add persistent notification.
- Add `Start task` and `Stop` actions.
- Add Quick Settings tile.
- Route triggers to assistant task UI or voice session.

Done criteria:

- User starts assistant from notification.
- User starts assistant from Quick Settings.
- User stops active task from notification.

### Milestone 8: First End-To-End Demo

Goal: credible AI-first phone demo.

Demo tasks:

- Open Settings and change a harmless setting.
- Open browser and search.
- Copy text from one app and paste into another.
- Share text through the chooser with confirmation.

Done criteria:

- Visible cursor.
- Screen observation active only during task.
- Model calls structured tools.
- OS mediates every action.
- User can stop.
- Audit log shows the full trace.

## Engineering Tasks By Area

### Framework

- Extend `IOpenPhoneAgentService` with screen capture APIs.
- Add active task state machine.
- Add capture policy enforcement.
- Add pointer event API or callback.
- Harden input injection.
- Expand action result JSON.
- Add blocked/sensitive screen policy.

### Assistant App

- Add task console UI.
- Add model adapter interface.
- Add provider settings placeholder.
- Add screenshot viewer.
- Add cursor overlay.
- Add notification.
- Add Quick Settings tile.
- Add voice session UI later.

### SystemUI

- V1 optional, V1.5 preferred.
- Render pointer overlay natively.
- Render active-agent chip.
- Provide stop affordance.

### Model

- Define tool schemas.
- Define system prompt.
- Add max-step loop.
- Add screenshot attachment path.
- Add provider abstraction.
- Add model call audit.

### Testing

- Add physical action checklist.
- Add screenshot capture tests.
- Add audit-log verification commands.
- Add blocked-action tests.
- Add recovery instructions for boot loops.

## Immediate Next Steps

1. Implement local tool-loop verification in the assistant UI.
2. Add a framework `getScreen(...)` API with active-task enforcement.
3. Display captured screenshot in the assistant.
4. Add cursor overlay for tap/swipe previews.
5. Add first text model adapter and wire it to OpenPhone tools.
6. Test on Pixel 9a after each milestone.
