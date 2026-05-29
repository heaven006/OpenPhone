# Framework Integration Plan

This is the concrete implementation target for the Lineage/AOSP framework
patches. The repository now carries the initial `frameworks/base` patches: a
hidden framework manager API, signature permissions, a `system_server` service,
foreground activity context, seed policy decisions, a mediated `open_app`
action, task-scoped input execution, pending action confirmation, and a durable
audit log, clipboard actions, and confirmed share chooser launch. The remaining
work is to add full UI hierarchy/screenshot perception, richer confirmation
flows, and Settings/SystemUI surfaces.

## New Framework API

Add an OpenPhone manager API in `frameworks/base`:

```text
android.openphone.OpenPhoneAgentManager
android.openphone.OpenPhoneScreenContext
android.openphone.OpenPhoneActionRequest
android.openphone.IOpenPhoneAgentService.aidl
```

Responsibilities:

- Expose task-scoped screen context to the privileged assistant.
- Route action requests to a policy-gated system service.
- Return structured policy decisions for sensitive actions.
- Emit audit events to the OpenPhone audit backend.

Current implemented API:

```text
android.openphone.OpenPhoneAgentManager
android.openphone.IOpenPhoneAgentService.aidl
```

The current methods use JSON strings for task, screen, action, capability, and
audit payloads. This keeps the first service patches small while the contracts
under `docs/contracts/` harden. Typed parcelables should replace the JSON
boundary once the first real device boot and service behavior are validated.

## System Service

Add a service registered from `system_server`:

```text
OpenPhoneAgentManagerService
```

Current implementation:

- Starts from `SystemServer`.
- Publishes `Context.OPENPHONE_AGENT_SERVICE`.
- Enforces OpenPhone signature permissions per Binder method.
- Returns `ready` status for health checks.
- Creates task IDs for accepted tasks.
- Reports focused and visible activities through `ActivityTaskManagerInternal`.
- Evaluates the initial capability classes through a fail-closed seed policy.
- Executes `open_app` by resolving the target package launch intent and
  starting it as the foreground user.
- Executes Back, Home, Recents, tap, long press, scroll, and text input through
  `InputManager` event injection.
- Executes clipboard text write and paste through the framework
  `ClipboardManager` plus mediated text injection.
- Executes confirmed share actions through Android's `ACTION_SEND` chooser.
- Stores task-scoped approved capabilities from task creation and requires a
  task grant for pointer and text input actions.
- Creates pending action IDs for confirmation-required actions.
- Lets trusted OpenPhone components approve or deny pending actions through the
  hidden framework manager.
- Denies unsupported/risky action classes when they are not confirmed or not
  implemented.
- Records task, screen, policy, and action events in a bounded audit log that
  is cached in memory and persisted at `/data/system/openphone/audit-log.json`.

Next dependencies:

- `WindowManagerInternal`
- `PackageManagerInternal`
- `NotificationManagerInternal`

## Security Boundary

Framework APIs must require signature permissions:

```text
org.openphone.permission.READ_SCREEN_CONTEXT
org.openphone.permission.PERFORM_DEVICE_ACTION
org.openphone.permission.MANAGE_AGENT_TASKS
org.openphone.permission.READ_AGENT_AUDIT_LOG
```

The assistant app may request these permissions, but the real authority remains
inside the system service and policy engine.

Current implemented slice:

- `frameworks/base` declares the OpenPhone signature permissions.
- `OpenPhoneAssistant` requests the signature permission contract.
- `OpenPhoneAssistant` is installed as a privileged persistent app.
- `OpenPhoneBootReceiver` starts the assistant service after boot and package
  replacement.
- `OpenPhoneAssistantService` resolves `OpenPhoneAgentManager` and reports the
  framework service status through its own Binder status path.
- `OpenPhoneAssistantService` routes task, screen context, action execution,
  policy evaluation, and audit log reads through `OpenPhoneAgentManager` when
  the framework service is available.
- `privapp-permissions-openphone.xml` allowlists the platform permissions the
  bootstrap assistant currently needs.

## First Patch Set

The first real `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0001-OpenPhone-add-agent-manager-framework-service.patch
```

It:

1. Add the public hidden manager classes.
2. Adds the AIDL interface.
3. Registers the service in `SystemServiceRegistry`.
4. Registers the implementation from `SystemServer`.
5. Adds hidden platform signature permissions.
6. Adds stub service methods that deny unknown capabilities.

Only after that patch builds should input/window integration be added.

## Second Patch Set

The second `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0002-OpenPhone-add-foreground-context-and-audit-mediation.patch
```

It:

1. Adds `getAuditLog(int maxEvents)` to the hidden framework Binder API.
2. Replaces empty screen context stubs with focused/visible activity metadata
   from `ActivityTaskManagerInternal`.
3. Adds seed capability policy decisions.
4. Adds mediated `open_app` action execution through package launch intents.
5. Adds bounded in-memory audit logging for task, context, policy, and action
   events.

## Third Patch Set

The third `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0003-OpenPhone-add-task-scoped-input-action-execution.patch
```

It:

1. Stores explicit task grants from `approved_capabilities` and
   `granted_capabilities`.
2. Allows Back, Home, and Recents as low-risk task-scoped navigation actions.
3. Executes tap, long press, scroll, and text input only when `input.perform`
   was approved for the task.
4. Injects key and motion events through the framework `InputManager`.
5. Audits executed and rejected input actions.

## Fourth Patch Set

The fourth `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0004-OpenPhone-persist-agent-audit-log.patch
```

It:

1. Loads the recent audit ring from `/data/system/openphone/audit-log.json` on
   service startup.
2. Persists the bounded audit ring through `AtomicFile` after new audit events.
3. Reports durable audit storage metadata in service status and audit reads.

## Fifth Patch Set

The fifth `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0005-OpenPhone-add-pending-action-confirmation-flow.patch
```

It:

1. Adds `confirmAction(String pendingActionId, boolean approved)` to the hidden
   framework Binder API.
2. Creates one-shot pending action IDs when policy requires confirmation.
3. Executes a pending action once when approved by a trusted OpenPhone
   component.
4. Records confirmation, rejection, and execution outcomes in the audit log.

## Sixth Patch Set

The sixth `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0006-OpenPhone-add-clipboard-action-execution.patch
```

It:

1. Maps `copy` to `clipboard.write` and `paste` to `clipboard.read`.
2. Requires confirmation for clipboard read/write capabilities.
3. Writes plain text to the system clipboard for confirmed `copy` actions.
4. Reads the current clipboard and injects text for confirmed `paste` actions.
5. Audits clipboard execution and rejection outcomes without recording
   clipboard contents by default.

## Seventh Patch Set

The seventh `frameworks/base` patch is implemented in:

```text
patches/frameworks_base/0007-OpenPhone-add-confirmed-share-action.patch
```

It:

1. Maps `share` to `share.content`.
2. Requires explicit confirmation before sharing content.
3. Launches Android's `ACTION_SEND` chooser for confirmed share actions.
4. Supports optional `mime_type` and `subject` action fields.
5. Audits share execution and rejection outcomes without recording shared body
   contents by default.

Next patch sets should add WindowManager-backed UI node extraction, target
resolution from UI element IDs to coordinates, richer confirmation UX, Settings
audit browsing, notification integrations, and SELinux policy.
