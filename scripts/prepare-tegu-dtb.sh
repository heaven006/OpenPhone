#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ -d "$OPENPHONE_ANDROID_DIR" ]] || die "Android tree not found: $OPENPHONE_ANDROID_DIR"
ensure_tegu_dtb
