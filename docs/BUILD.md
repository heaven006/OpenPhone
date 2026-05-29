# Build

OpenPhone uses Android's normal `repo` workflow. This repository stays small
and contains only OpenPhone-owned code, manifests, scripts, and patches.

## Prerequisites

- Linux Android build host dependencies installed for full device images.
- macOS can sync, patch, extract blobs, and build host/module validation
  targets, but this Android branch does not emit full phone images on Darwin.
- `repo` available in `PATH`.
- `git-lfs` installed and initialized.
- GNU coreutils on macOS.
- Java version required by the selected Android/Lineage branch.
- Large case-sensitive filesystem required.
- At least several hundred GB of free disk space.

Install `repo` into `~/.local/bin`:

```bash
./scripts/install-repo.sh
```

Install Git LFS on macOS:

```bash
brew install git-lfs
git lfs install
```

Install GNU coreutils on macOS:

```bash
brew install coreutils
```

On macOS, create a case-sensitive APFS sparse image before syncing:

```bash
./scripts/create-macos-build-volume.sh
export OPENPHONE_ANDROID_DIR="$PWD/.worktree/OpenPhoneAndroid/android"
```

For a flashable Pixel 9a build, use a Linux x86_64 host or VM with enough disk
and RAM. Docker Desktop on Apple Silicon can run Linux/amd64 containers, but a
full Android build through emulation is usually too slow and too memory-limited
for practical ROM work.

## Environment

Useful variables:

```bash
OPENPHONE_ANDROID_DIR=/path/to/android/tree
LINEAGE_BRANCH=lineage-23.2
LINEAGE_MANIFEST_URL=https://github.com/LineageOS/android.git
OPENPHONE_LUNCH_TARGET=openphone_arm64-userdebug
```

Defaults:

```text
OPENPHONE_ANDROID_DIR=.worktree/android
LINEAGE_BRANCH=lineage-23.2
LINEAGE_MANIFEST_URL=https://github.com/LineageOS/android.git
OPENPHONE_RELEASE=bp2a
```

## Sync

```bash
./scripts/sync.sh
```

This initializes or updates the Android checkout and installs the OpenPhone
local manifest.

## Apply OpenPhone Overlay and Patches

```bash
./scripts/apply-patches.sh
```

This copies `overlay/` into the Android tree and applies any patch files under
`patches/`.

## Build

Generic OpenPhone ARM64 build:

```bash
./scripts/build.sh openphone_arm64
```

Default build goal:

```text
OPENPHONE_BUILD_GOAL=droid
OPENPHONE_SKIP_SOONG_TESTS=true
```

Build only the assistant module:

```bash
OPENPHONE_BUILD_GOAL=OpenPhoneAssistant ./scripts/build.sh openphone_arm64
```

The script maps `OpenPhoneAssistant` to the generated
`OpenPhoneAssistant-outputs` target used by this Android branch.

Build only the framework service validation target:

```bash
OPENPHONE_BUILD_GOAL=MODULES-IN-frameworks-base-services-core ./scripts/build.sh openphone_arm64
```

This validates the hidden `OpenPhoneAgentManager` framework API,
`OpenPhoneAgentManagerService`, and `SystemServer` startup wiring without
building the full product graph.

Current generic-target status:

- `OPENPHONE_BUILD_GOAL=droid ./scripts/build.sh openphone_arm64` has been
  validated on the prepared macOS build host for host/module graph coverage.
- `OPENPHONE_BUILD_GOAL=MODULES-IN-frameworks-base-services-core ./scripts/build.sh openphone_arm64`
  has been validated for the first OpenPhone framework service patch.
- `OPENPHONE_BUILD_GOAL=OpenPhoneAssistant ./scripts/build.sh openphone_arm64`
  has been validated for the privileged assistant bridge to the framework
  service.
- The generic target validates the OpenPhone product graph and packages, but it
  is not yet a device-flashable OpenPhone release.
- In this branch, `systemimage` is not a valid build goal for the generic
  `openphone_arm64` target. Device-specific image output starts after a real
  device target is added.

Device-specific OpenPhone Pixel 9a build on Linux:

```bash
OPENPHONE_ANDROID_DIR=/path/to/android/tree \
OPENPHONE_RELEASE=bp4a \
OPENPHONE_BUILD_GOAL="droid target-files-package otapackage" \
./scripts/build.sh openphone_tegu
```

The Pixel 9a product is `openphone_tegu-bp4a-userdebug`. Native macOS builds
will only build host-side targets because `build/make/core/main.mk` restricts
Darwin to host modules.

For the current Pixel 9a prebuilts, run the DTB preparation step documented in
[TEGU_BOOTCHAIN.md](TEGU_BOOTCHAIN.md) before producing target-files. Without
that step, target-files can generate a `vendor_kernel_boot.img` with a zero-byte
DTB, which falls back to fastboot before Android starts.

## Flash

```bash
./scripts/flash.sh /path/to/image-or-ota.zip
```

The flash script currently refuses to guess destructive steps. Device-specific
flash procedures belong in `devices/<codename>.md`.
