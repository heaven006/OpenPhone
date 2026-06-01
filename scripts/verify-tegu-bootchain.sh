#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

[[ -d "$OPENPHONE_ANDROID_DIR" ]] || die "Android tree not found: $OPENPHONE_ANDROID_DIR"
product="${1:-openphone_tegu}"
is_tegu_product "$product" || die "unsupported Pixel 9a product: $product"

verify_tegu_vendor_kernel_boot "$product"
