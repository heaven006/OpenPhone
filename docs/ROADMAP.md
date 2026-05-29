# Roadmap

The detailed first-device execution plan lives in
[V1_AI_PHONE_PLAN.md](V1_AI_PHONE_PLAN.md).

## Phase 0: Project Foundation

Status: in progress.

- Canonical repo.
- SPEC.
- Documentation.
- License boundary.
- Script skeleton.
- Overlay skeleton.

## Phase 1: Reproducible Base Build

- Sync LineageOS upstream.
- Build generic OpenPhone product.
- Select first physical device.
- Build and flash the base OS.

## Phase 2: OpenPhone Product Layer

- Product branding.
- Build properties.
- Settings entry.
- Privileged assistant package.
- Initial permission allowlists.

## Phase 3: Minimal Agent Privilege Path

Status: partially implemented.

- Basic screen context service: focused and visible activity metadata.
- Basic action execution path: open app, navigation keys, pointer gestures,
  scroll, text input, clipboard text actions, and confirmed share chooser.
- Policy checks: seed capability policy, task-scoped input grants, and
  one-shot pending action confirmation.
- Audit log: durable framework log plus basic assistant audit browser.

## Phase 4: Framework Integration

- Binder APIs.
- System service registration.
- SystemUI and Settings integration.
- SELinux policy.

## Phase 5: Device and Release Hardening

- OTA.
- Signing.
- License/notice bundle.
- Kernel source publication flow.
- Automated device smoke tests.
