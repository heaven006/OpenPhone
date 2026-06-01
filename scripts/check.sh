#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

required=(
  README.md
  SPEC.md
  COMMERCIAL.md
  CONTRIBUTING.md
  LICENSE
  LICENSE.noncommercial
  NOTICE
  SECURITY.md
  THIRD_PARTY_NOTICES.md
  CHANGELOG.md
  docs/ARCHITECTURE.md
  docs/BUILD.md
  docs/BRINGUP_LOG.md
  docs/CAPABILITIES.md
  docs/DEVICE_SUPPORT.md
  docs/FRAMEWORK_PLAN.md
  docs/IMPLEMENTATION_STATUS.md
  docs/LICENSING.md
  docs/PLAN.md
  docs/RELEASE_PROCESS.md
  docs/ROADMAP.md
  docs/releases/0.0.1.md
  docs/contracts/action-request.schema.json
  docs/contracts/agent-task.schema.json
  docs/contracts/audit-event.schema.json
  docs/contracts/screen-context.schema.json
  .github/workflows/ci.yml
  .github/ISSUE_TEMPLATE/bug_report.md
  .github/ISSUE_TEMPLATE/feature_request.md
  .github/pull_request_template.md
  devices/MATRIX.md
  devices/tegu.md
  manifests/openphone.xml
  scripts/prepare-tegu-dtb.sh
  scripts/generate-release-manifest.sh
  scripts/prepare-github-release.sh
  scripts/validate-release-artifacts.sh
  scripts/smoke-test-tegu-hardware.sh
  scripts/verify-tegu-device.sh
  scripts/verify-tegu-bootchain.sh
  overlay/vendor/openphone/AndroidProducts.mk
  overlay/vendor/openphone/products/openphone_common.mk
  overlay/vendor/openphone/products/openphone_arm64.mk
  overlay/vendor/openphone/products/openphone_tegu.mk
  overlay/packages/apps/OpenPhoneAssistant/Android.bp
  overlay/packages/apps/OpenPhoneAssistant/AndroidManifest.xml
  overlay/packages/apps/OpenPhoneAssistant/LICENSE
  overlay/packages/apps/OpenPhoneAssistant/res/drawable/ic_openphone_tile.xml
  overlay/packages/apps/OpenPhoneAssistant/res/xml/openphone_accessibility_service.xml
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/OpenPhoneAccessibilityService.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/OpenPhoneNotificationController.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/OpenPhoneQuickSettingsTileService.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/OpenPhoneTriggerReceiver.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/PointerOverlayController.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/IOpenPhoneAssistant.aidl
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/agent/FrameworkToolExecutor.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/agent/TrajectoryRecorder.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/model/ModelAdapter.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/model/LocalHeuristicModelAdapter.java
  overlay/packages/apps/OpenPhoneAssistant/src/org/openphone/assistant/model/OpenAiRealtimeAdapter.java
  patches/frameworks_base/0001-OpenPhone-add-agent-manager-framework-service.patch
  patches/frameworks_base/0002-OpenPhone-add-foreground-context-and-audit-mediation.patch
  patches/frameworks_base/0003-OpenPhone-add-task-scoped-input-action-execution.patch
  patches/frameworks_base/0004-OpenPhone-persist-agent-audit-log.patch
  patches/frameworks_base/0005-OpenPhone-add-pending-action-confirmation-flow.patch
  patches/frameworks_base/0006-OpenPhone-add-clipboard-action-execution.patch
  patches/frameworks_base/0007-OpenPhone-add-confirmed-share-action.patch
  patches/frameworks_base/0008-OpenPhone-add-task-screen-and-pointer-APIs.patch
  patches/frameworks_base/0009-OpenPhone-add-opt-in-screenshot-payloads.patch
  patches/frameworks_base/0010-OpenPhone-capture-screenshots-as-system-server.patch
  patches/packages_apps_Settings/0001-OpenPhone-add-About-phone-version-surface.patch
  patches/packages_apps_Settings/0002-OpenPhone-add-settings-dashboard.patch
  patches/packages_apps_Settings/0003-OpenPhone-add-Settings-hosted-audit-and-grant-pages.patch
  patches/system_sepolicy/0001-OpenPhone-label-agent-manager-service.patch
)

for file in "${required[@]}"; do
  [[ -f "$root/$file" ]] || {
    printf 'missing required file: %s\n' "$file" >&2
    exit 1
  }
done

for script in "$root"/scripts/*.sh; do
  bash -n "$script"
done

if command -v xmllint >/dev/null 2>&1; then
  xmllint --noout "$root/manifests/openphone.xml"
  xmllint --noout "$root/overlay/packages/apps/OpenPhoneAssistant/AndroidManifest.xml"
  xmllint --noout "$root/overlay/vendor/openphone/config/privapp-permissions-openphone.xml"
  xmllint --noout "$root/overlay/vendor/openphone/config/sysconfig-openphone.xml"
fi

if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY' "$root"
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
scan_roots = [
    root / "docs",
    root / "overlay",
]
for scan_root in scan_roots:
    for path in sorted(scan_root.rglob("*.json")):
        with path.open("r", encoding="utf-8") as handle:
            json.load(handle)
PY
fi

if grep -R "SPDX-license-identifier-Apache-2.0" \
    "$root/overlay/vendor/openphone" \
    "$root/overlay/packages/apps/OpenPhoneAssistant" >/dev/null 2>&1; then
  printf 'OpenPhone-owned overlay modules must not be marked Apache-2.0\n' >&2
  exit 1
fi

printf 'OpenPhone repo checks passed.\n'
