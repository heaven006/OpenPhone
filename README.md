# OpenPhone

OpenPhone is an Android-based operating system project where an AI agent is a
first-class system capability instead of a normal app layered on top of Android.

This repository is the canonical OpenPhone entry point. It contains the
OpenPhone-owned source, manifests, patch stack, scripts, documentation, and
product overlay needed to build an OpenPhone-flavored Android/LineageOS tree.
It intentionally does not vendor the full Android source tree.

## Current State

This repo currently implements the project scaffold described in
[SPEC.md](SPEC.md):

- LineageOS upstream sync workflow.
- OpenPhone local manifest hook.
- Patch-stack application workflow.
- Generic OpenPhone product overlay.
- Minimal privileged `OpenPhoneAssistant` system app skeleton.
- Verified `OpenPhoneAssistant` module build inside a synced LineageOS tree.
- Device support and licensing documentation.

The deep OS integration work is still ahead: framework Binder APIs, screen
understanding, action execution, SELinux policy, policy enforcement, audit log,
OTA, full product image completion, and validated device ports.

## Repository Layout

```text
docs/       Architecture, build, licensing, and device docs.
devices/    Device support matrix and per-device notes.
manifests/  Local repo manifests for OpenPhone overrides.
overlay/    Files copied into the Android source tree.
patches/    Patch stacks applied on top of upstream LineageOS repos.
scripts/    Sync, patch, build, flash, and validation helpers.
SPEC.md     Product and architecture specification.
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

Validate the repository scaffold:

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

The default Android checkout lives at:

```text
.worktree/android
```

Override it with:

```bash
OPENPHONE_ANDROID_DIR=/path/to/android/tree ./scripts/sync.sh
```

## Licensing

OpenPhone-owned code is intended to be source-available for non-commercial use,
with commercial licensing available separately. Upstream Android, LineageOS,
Linux kernel, device trees, vendor extraction scripts, and third-party code keep
their original licenses.

See [docs/LICENSING.md](docs/LICENSING.md) and [LICENSE](LICENSE).
