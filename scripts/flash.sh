#!/usr/bin/env bash

set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

artifact="${1:-}"
[[ -n "$artifact" ]] || die "usage: scripts/flash.sh /path/to/image-or-ota.zip"
[[ -e "$artifact" ]] || die "artifact not found: $artifact"

cat >&2 <<'MSG'
OpenPhone does not guess device-specific flashing commands.

Flashing can wipe data, brick a device, or require exact firmware versions.
Add a device-specific guide under devices/<codename>.md, then implement a
codename-aware flash path here.
MSG

exit 2

