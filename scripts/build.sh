#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ -d "$OPENPHONE_ANDROID_DIR" ]] || die "Android tree not found: $OPENPHONE_ANDROID_DIR"
[[ -f "$OPENPHONE_ANDROID_DIR/build/envsetup.sh" ]] || die "missing build/envsetup.sh; run scripts/sync.sh first"
need_gnu_coreutils_on_macos

target_arg="${1:-}"
OPENPHONE_LUNCH_PRODUCT=""
OPENPHONE_LUNCH_RELEASE=""
OPENPHONE_LUNCH_VARIANT=""

if [[ -n "$target_arg" ]]; then
  if [[ "$target_arg" == *-*-* ]]; then
    OPENPHONE_LUNCH_TARGET="$target_arg"
  else
    OPENPHONE_LUNCH_TARGET="${target_arg}-${OPENPHONE_RELEASE}-userdebug"
  fi
else
  OPENPHONE_LUNCH_TARGET="${OPENPHONE_LUNCH_TARGET:-openphone_arm64-${OPENPHONE_RELEASE}-userdebug}"
fi

OPENPHONE_LUNCH_VARIANT="${OPENPHONE_LUNCH_TARGET##*-}"
target_without_variant="${OPENPHONE_LUNCH_TARGET%-*}"
OPENPHONE_LUNCH_RELEASE="${target_without_variant##*-}"
OPENPHONE_LUNCH_PRODUCT="${target_without_variant%-*}"

info "Android tree: $OPENPHONE_ANDROID_DIR"
info "Lunch target: $OPENPHONE_LUNCH_TARGET"
OPENPHONE_BUILD_GOAL="${OPENPHONE_BUILD_GOAL:-droid}"
info "Build goal: $OPENPHONE_BUILD_GOAL"

cd "$OPENPHONE_ANDROID_DIR"

# Android's envsetup/lunch scripts intentionally probe missing values and
# handle some failed commands internally. Keep strict mode for our script, but
# do not impose it on Android's shell functions. For the generic OpenPhone
# product we export the target variables directly to avoid Lineage roomservice
# trying to fetch a fake "arm64" device tree.
set +e +u
# shellcheck disable=SC1091
source build/envsetup.sh
export TARGET_PRODUCT="$OPENPHONE_LUNCH_PRODUCT"
export TARGET_RELEASE="$OPENPHONE_LUNCH_RELEASE"
export TARGET_BUILD_VARIANT="$OPENPHONE_LUNCH_VARIANT"
setup_status=0
set -euo pipefail

if [[ $setup_status -ne 0 ]]; then
  die "build environment setup failed for $OPENPHONE_LUNCH_TARGET"
fi

soong_args=(--make-mode)
if [[ "${OPENPHONE_SKIP_SOONG_TESTS:-true}" == "true" ]]; then
  soong_args+=(--skip-soong-tests)
fi
read -r -a openphone_build_goals <<< "$OPENPHONE_BUILD_GOAL"
soong_args+=("${openphone_build_goals[@]}")

build/soong/soong_ui.bash "${soong_args[@]}"
