# OpenPhone

![OpenPhone GitHub hero](docs/assets/github_hero.png)

[![CI](https://github.com/secondly-com/OpenPhone/actions/workflows/ci.yml/badge.svg)](https://github.com/secondly-com/OpenPhone/actions/workflows/ci.yml)
[![Release](https://github.com/secondly-com/OpenPhone/actions/workflows/release.yml/badge.svg)](https://github.com/secondly-com/OpenPhone/actions/workflows/release.yml)
![License](https://img.shields.io/badge/license-PolyForm%20Noncommercial-blue)
![Status](https://img.shields.io/badge/status-developer%20preview-orange)

OpenPhone is an Android-based phone OS where the AI agent is a first-class
system capability, not a normal app layered on top of Android.

The goal is a phone that can understand the current screen, operate apps through
OS-mediated tools, keep background commitments, ask for review when risk
requires it, and leave an auditable trail of what happened.

This repository is the canonical OpenPhone entry point. It contains the
OpenPhone-owned Android overlay, privileged assistant app, framework patches,
model/tool policy configuration, build scripts, device notes, contracts, and
release tooling. It intentionally does not vendor the full Android source tree.

## Current Preview

OpenPhone already boots on a real Pixel 9a as a LineageOS 23.2 / Android 16
based ROM. It is still a developer preview, not a consumer-ready daily driver,
but the core OS shape is in place: the assistant is installed as a privileged
system component, and phone control is mediated through OpenPhone framework
services instead of a standalone app trying to automate Android from the
outside.

The assistant has a persistent system presence through the dynamic island. It
can listen, answer, show recent conversation state, expose active watchers and
background runs, request approval for sensitive actions, and switch between a
bounded regular agent session and an optional realtime voice session for
back-and-forth demos.

The agent can use structured screen context and model-visible tools to operate
the phone: launch apps, inspect the visible UI, tap, scroll, type, open links,
use clipboard/share flows, react to notifications, and work with messaging
paths under policy. The point is not a chat app; it is a phone-level loop that
can observe, decide, act, and continue across app boundaries.

OpenPhone also includes the beginning of proactivity. Watchers, commitments,
and background runs give the agent durable state so it can monitor future phone
events and continue work after the current chat turn. These surfaces are still
early, but they are part of the OS layer rather than prompt-only behavior.

Every serious action path is designed around review, policy, and evidence.
OpenPhone has declarative capability registries, OS-owned data services,
hash-chained framework audit logs, trajectory exports, and validators so agent
behavior can be debugged, evaluated, and released with evidence.

See [docs/SHOWCASE.md](docs/SHOWCASE.md) for the current demo surface and
[docs/ROADMAP.md](docs/ROADMAP.md) for what is still unfinished.

## Use Cases

OpenPhone is being built for phone-level AI workflows that need OS context,
durable state, and mediated action:

- Screen-aware help: ask what is visible, what can be tapped, or what state an
  app is in.
- Cross-app task execution: open apps, navigate settings, search, compose,
  share, and complete bounded UI tasks.
- Proactive monitoring: create watchers for future calls, messages,
  notifications, app states, or other device events.
- Background follow-through: keep working on queued agent runs after the
  current chat turn ends, with visible status and review where needed.
- Voice-first control: use a bounded regular agent session for traceable tasks
  or realtime voice for back-and-forth demos.
- Auditable automation: export trajectories, screenshots, actions, policy
  decisions, and framework audit evidence for debugging and release validation.

## Why This Exists

Mobile assistants built as normal apps hit the same ceiling: limited OS
authority, fragile accessibility automation, weak background continuity, and no
clear place for consent, policy, or audit.

OpenPhone explores a different architecture:

- The assistant is a privileged OS component.
- The model sees structured screen and task context rather than raw app access.
- Actions go through policy and framework mediation.
- Sensitive operations require explicit review.
- Background work is represented as durable jobs, watchers, and commitments.
- Device support is handled like a ROM project, with exact hardware targets and
  validation evidence.

## How It Works

```text
User
  volume chord, touch, text, voice, realtime voice

OpenPhone Assistant
  dynamic island UI, chat, voice capture, model adapter, tool loop

OpenPhone OS Services
  openphone_agent
  openphone_context
  openphone_assistant_data

Android Framework
  ActivityTaskManager, WindowManager, InputManager, PackageManager,
  NotificationManager, Settings, SELinux, system_server

LineageOS / Android / Device
  Pixel 9a target, kernel/boot chain, vendor blobs supplied by the builder
```

The high-level architecture is documented in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The capability model is in
[docs/CAPABILITIES.md](docs/CAPABILITIES.md), and machine-readable contracts
live under [docs/contracts](docs/contracts).

## Repository Layout

```text
.github/       CI, release, eval, contribution, security, issue, and PR files.
devices/       Device matrix and per-device bringup notes.
docs/          Product docs, spec, legal docs, contracts, releases, and archive.
examples/      Demo tasks, watcher examples, and eval starting points.
manifests/     Android repo local manifests.
overlay/       OpenPhone-owned files copied into the Android tree.
patches/       Patch stacks applied on top of upstream LineageOS repos.
scripts/       Sync, patch, build, flash, validation, and release helpers.
services/      Reference services, including the development model broker.
```

Start with [docs/README.md](docs/README.md) if you are looking for a specific
document.

## Quick Start

Validate the repository:

```bash
./scripts/check.sh
git diff --check
```

Install `repo` if needed:

```bash
./scripts/install-repo.sh
```

Sync and patch the Android tree:

```bash
./scripts/sync.sh
./scripts/apply-patches.sh
```

Build the generic OpenPhone ARM64 product for validation:

```bash
./scripts/build.sh openphone_arm64
```

Build the Pixel 9a target on a Linux Android build host:

```bash
OPENPHONE_BUILD_GOAL="droid target-files-package otapackage" \
  ./scripts/build.sh openphone_tegu
```

For assistant-only iteration on an already flashed development device:

```bash
OPENPHONE_BUILD_GOAL=OpenPhoneAssistant ./scripts/build.sh openphone_tegu
scripts/push-assistant-apk.sh /path/to/OpenPhoneAssistant.apk
```

Full build instructions are in [docs/BUILD.md](docs/BUILD.md). Testing and
physical eval guidance is in [docs/TESTING.md](docs/TESTING.md).

## Device Support

The first physical target is Google Pixel 9a (`tegu`). Generic ARM64 builds are
useful for product graph validation, but they are not a supported phone target.

OpenPhone does not redistribute Google apps, Google Mobile Services, vendor
blobs, signing keys, private firmware, or restricted device material. Local
developer GMS sideload notes are in [docs/GMS.md](docs/GMS.md).

See [devices/MATRIX.md](devices/MATRIX.md) and
[devices/tegu.md](devices/tegu.md).

## Releases And Validation

Public releases are developer previews until `1.0.0`.

- Release dashboard: [docs/releases/README.md](docs/releases/README.md)
- Release notes: [docs/releases/0.0.1.md](docs/releases/0.0.1.md)
- Changelog: [docs/releases/CHANGELOG.md](docs/releases/CHANGELOG.md)
- Release process: [docs/RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md)
- CI: [`.github/workflows/ci.yml`](.github/workflows/ci.yml)
- GitHub release workflow: [`.github/workflows/release.yml`](.github/workflows/release.yml)
- Device eval workflow: [`.github/workflows/eval.yml`](.github/workflows/eval.yml)

Every release should publish checksums, known issues, supported device notes,
and validation evidence for any device artifact.

## Community

Contributions are welcome from people who accept the contribution terms in
[.github/CONTRIBUTING.md](.github/CONTRIBUTING.md). Good first areas are:

- Agent eval tasks that expose real phone-control failures.
- Pixel 9a validation reports and reproducible bug reports.
- Device-port research for exact Android models and codenames.
- Dynamic island and assistant UI polish.
- Capability registry, policy, and audit contract improvements.
- Build/release documentation that makes the ROM workflow easier to reproduce.

Use GitHub issues for reproducible bugs and scoped feature proposals. Do not
post secrets, private keys, personal device data, proprietary vendor files, or
Google package files.

## Commercial Use

OpenPhone-owned materials are source-available for non-commercial use under the
PolyForm Noncommercial License 1.0.0. Commercial use requires a separate written
license from Dafdef, inc.

Contributions are accepted only under terms that allow Dafdef, inc. to own,
modify, sublicense, redistribute, and commercialize the submitted work. See
[.github/CONTRIBUTING.md](.github/CONTRIBUTING.md),
[docs/legal/COMMERCIAL.md](docs/legal/COMMERCIAL.md), [LICENSE](LICENSE),
[docs/legal/LICENSE.noncommercial](docs/legal/LICENSE.noncommercial), and
[docs/legal/THIRD_PARTY_NOTICES.md](docs/legal/THIRD_PARTY_NOTICES.md).
