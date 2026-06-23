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

## AI-Native Phone Runtime

OpenPhone already boots on a real Pixel 9a as a LineageOS 23.2 / Android 16
based ROM. It is a developer preview, not a consumer-ready daily driver, but
the core product shape is in place: the agent is a privileged system component,
the dynamic island is its always-available activity surface, and actions are
mediated through OpenPhone framework services instead of an app trying to
automate Android from the outside.

The agent can read structured screen context, use model-visible phone tools,
and operate across apps: launch, inspect, tap, scroll, type, open links, use
clipboard/share flows, react to notifications, and work with messaging paths
under policy. Sensitive actions are reviewable, and behavior can be inspected
through audit logs, trajectories, screenshots, policy decisions, and release
validators.

OpenPhone is also built for proactive work. Heartbeats quietly check whether
anything needs attention. Scheduled jobs run exact workflows. Watchers monitor
phone context such as missed calls, messages, notifications, foreground app
state, visible screen state, calendar changes, location, battery, connectivity,
and commitments the user made in conversation. Background runs keep working
after the current chat turn, while the dynamic island shows what is running,
why it started, what it last said, and what needs review.

See [docs/SHOWCASE.md](docs/SHOWCASE.md) for the current demo surface and
[docs/ROADMAP.md](docs/ROADMAP.md) for what is still unfinished.

## Use Cases

- "Catch me up on everything important from overnight" - consume missed calls,
  messages, notifications, calendar changes, and reminders, then return a short
  morning gist.
- "Order me an Uber to the office" - open the right app, set the destination,
  select a ride, and stop for review before booking.
- "Play something random on Spotify" - open Spotify, choose music, and continue
  until playback actually starts.
- "If I miss a call from this number, send them 'I'll call you back soon'" -
  create a watcher tied to future call context and message policy.
- "Watch for delivery updates and only bother me if something changes" - turn
  notification noise into a targeted background monitor.
- "Help me finish this screen" - inspect the visible app state, identify the
  next control, and act through OS-mediated taps or text input.
- "Remind me when this conversation becomes relevant" - turn a commitment into
  durable state that can resurface later based on time, app, or phone context.
- "Keep working on this after I leave" - continue a multi-step task as a
  visible background run with approval where needed.

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

## Community

Contributions, issues, and device validation reports are welcome under the
terms in [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md).

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
