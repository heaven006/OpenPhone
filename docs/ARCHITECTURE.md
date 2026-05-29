# Architecture

OpenPhone is built as an Android/LineageOS-derived ROM with an OpenPhone-owned
AI layer added as privileged OS components.

## Layers

```text
Applications
  third-party apps
  system apps
  OpenPhoneAssistant

OpenPhone AI Layer
  assistant UI
  agent orchestrator
  screen understanding
  action execution
  policy and consent
  audit log
  model runtime adapter

Android Framework
  ActivityTaskManager
  WindowManager
  InputManager
  NotificationManager
  PackageManager
  PermissionManager

System
  system_server
  Binder
  init
  SELinux
  HAL/vendor interfaces
  kernel

Device
  device tree
  kernel config
  vendor blobs
  firmware expectations
```

## Initial Implementation

The current repo implements the first OpenPhone product layer:

- `vendor/openphone` product config.
- `OpenPhoneAssistant` privileged app with task, grant, screen context, and
  audit controls.
- Initial capability and policy config files.
- Local manifest and patch-stack workflow.
- Hidden OpenPhone framework manager and Binder service.
- `system_server` OpenPhone agent manager service.
- Foreground/visible activity context from ActivityTaskManager.
- First mediated action path for opening apps.
- Task-scoped input action execution for navigation, pointer gestures, scroll,
  and text.
- Confirmed clipboard write and paste actions.
- Confirmed share chooser actions.
- One-shot pending action confirmation through the assistant control surface.
- Durable framework audit log under `/data/system/openphone/`.

This is enough to start producing OpenPhone-flavored builds once the Android
tree is synced and to validate the first OS-level agent capability path. It is
not yet the final AI-native OS integration.

## Required Framework Work

The following components still need real Android framework implementation:

- Window/UI hierarchy extraction and screenshot/OCR perception.
- UI-element target resolution for input actions.
- Notification and app-specific action integrations.
- Rich confirmation and grant lifecycle UI.
- Privilege separation between assistant UI, orchestrator, and executors.
- SELinux domains and neverallow-compatible policy.
- Dedicated Settings-hosted audit surface.
- Background task visibility in SystemUI.

The detailed framework target is tracked in
[FRAMEWORK_PLAN.md](FRAMEWORK_PLAN.md).

## Design Rule

All sensitive actions must pass through policy below the assistant UI layer.
The assistant UI may request an action, but it must not be the authority that
decides whether the action is allowed.
