# OpenPhone

## License And Contributions

This repository is proprietary, source-available software owned by Dafdef, inc.
and Adam Cohen Hillel. It is not open source. Repository access is for review,
local evaluation, and contributions only. Commercial use, redistribution,
hosting, sublicensing, or competing use requires written permission from
Dafdef, inc. See [LICENSE](./LICENSE).

Contributions are accepted only under the contributor terms in
[CONTRIBUTING.md](./CONTRIBUTING.md), which grant Dafdef, inc. and Adam Cohen
Hillel broad rights to use, modify, relicense, and commercialize submitted
contributions and make clear that contributions do not create any right to
money, equity, shares, revenue, employment, or ownership.


![OpenPhone GitHub hero](docs/assets/github_hero.png)

OpenPhone is an Android-based operating system project where an AI agent is a
first-class system capability instead of a normal app layered on top of Android.

This repository is the canonical OpenPhone entry point. It contains the
OpenPhone-owned source, manifests, patch stack, scripts, documentation, and
product overlay needed to build an OpenPhone-flavored Android/LineageOS tree.
It intentionally does not vendor the full Android source tree.

## Current State

This repo currently implements the OpenPhone OS bringup described in
[SPEC.md](SPEC.md):

- LineageOS upstream sync workflow.
- OpenPhone local manifest hook.
- Patch-stack application workflow.
- Generic OpenPhone product overlay.
- Pixel 9a `openphone_tegu` product overlay.
- Privileged persistent `OpenPhoneAssistant` system app with a consumer-facing
  chat surface, text/voice task entry, task stop control, model settings,
  evidence export, OTA-preview controls, and developer tools behind an
  advanced panel.
- Hidden framework API and `system_server` Binder service for OpenPhone agent
  capabilities.
- Policy seed, screen context plumbing, mediated action execution, confirmation
  flow, and persistent audit log patches.
- Durable assistant data for memories, commitments, watchers, and the first
  assistant-side Agent Runtime V1 background job layer.
- Verified full-product boot on a physical Pixel 9a.
- Fast assistant iteration path that rebuilds only `OpenPhoneAssistant`, pushes
  the privileged APK into `/system_ext`, reboots, and validates the UI/agent on
  the physical Pixel 9a without a full OTA loop.

The active plan is tracked in [docs/PLAN.md](docs/PLAN.md). The agent runtime
direction is captured in [docs/AGENT_RUNTIME_V1.md](docs/AGENT_RUNTIME_V1.md).
The current hardware baseline is tracked in [devices/tegu.md](devices/tegu.md),
and the implementation evidence ledger is in
[docs/IMPLEMENTATION_STATUS.md](docs/IMPLEMENTATION_STATUS.md). The next deep
OS integration work is stronger background-agent reliability, richer screen
understanding, production model transport, SystemUI-owned active-agent
presence, OTA hardening, production signing, and validated device ports beyond
Pixel 9a.

## Repository Layout

```text
docs/       Architecture, build, licensing, and device docs.
devices/    Device support matrix and per-device notes.
manifests/  Local repo manifests for OpenPhone overrides.
overlay/    Files copied into the Android source tree.
patches/    Patch stacks applied on top of upstream LineageOS repos.
scripts/    Sync, patch, build, flash, and validation helpers.
SPEC.md     Product and architecture specification.
CHANGELOG.md Public release history.
```

## Quick Start

Prerequisites:

- Linux x86_64 build host for full flashable device images.
- macOS is useful for sync, patching, extraction, and focused validation builds,
  but this Android branch only wires host modules into the default Darwin
  `droid` target.
- `repo` installed and available in `PATH`.
- `git-lfs` installed and initialized.
- GNU coreutils on macOS.
- Case-sensitive filesystem for the Android checkout.
- Enough disk space for a full Android source checkout.

Install `repo` into `~/.local/bin` if needed:

```bash
./scripts/install-repo.sh
```

On macOS, create a case-sensitive Android build volume:

```bash
./scripts/create-macos-build-volume.sh
export OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android"
```

Bootstrap the Android tree:

```bash
./scripts/sync.sh
./scripts/apply-patches.sh
```

Validate the repository:

```bash
./scripts/check.sh
```

Build the generic OpenPhone ARM64 product for validation:

```bash
./scripts/build.sh openphone_arm64
```

By default this builds the Android `droid` goal. Override it with:

```bash
OPENPHONE_BUILD_GOAL=OpenPhoneAssistant ./scripts/build.sh openphone_arm64
```

For a real phone port, set a device-specific lunch target:

```bash
OPENPHONE_BUILD_GOAL="droid target-files-package otapackage" ./scripts/build.sh openphone_tegu
```

Optional Google Play / Google Mobile Services installation is documented in
[docs/GMS.md](docs/GMS.md). OpenPhone does not redistribute Google packages, but
for local Pixel 9a testing the helper can download and verify the public
MindTheGapps Android 16 arm64 release, then sideload it from recovery:

```bash
scripts/download-mindthegapps.sh
scripts/sideload-user-gms.sh \
  --package .worktree/downloads/gms/MindTheGapps-16.0.0-arm64-*.zip
```

The default Android checkout lives at:

```text
.worktree/android
```

Override it with:

```bash
OPENPHONE_ANDROID_DIR=/path/to/android/tree ./scripts/sync.sh
```

## Licensing

OpenPhone-owned materials are source-available for non-commercial use under the
PolyForm Noncommercial License 1.0.0, with commercial licensing available
separately. Upstream Android, LineageOS, Linux kernel, device trees, vendor
extraction scripts, and third-party code keep their original licenses.

See [docs/LICENSING.md](docs/LICENSING.md), [LICENSE](LICENSE),
[LICENSE.noncommercial](LICENSE.noncommercial), [COMMERCIAL.md](COMMERCIAL.md),
and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
