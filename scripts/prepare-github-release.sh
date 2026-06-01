#!/usr/bin/env bash

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/common.sh
source "$root/scripts/common.sh"

usage() {
  cat <<'EOF'
Usage: scripts/prepare-github-release.sh <version> <artifact-dir> <release-notes.md> [output-dir]

Prepares a GitHub release draft command file for a staged OpenPhone release.
Run generate-release-manifest.sh and validate-release-artifacts.sh first.

Example:
  scripts/prepare-github-release.sh 0.0.1 .worktree/releases/v0.0.1 docs/releases/0.0.1.md
EOF
}

version="${1:-}"
artifact_dir="${2:-}"
release_notes="${3:-}"
output_dir="${4:-}"

if [[ -z "$version" || -z "$artifact_dir" || -z "$release_notes" ]]; then
  usage >&2
  exit 2
fi

if [[ "$version" != v* ]]; then
  tag="v$version"
else
  tag="$version"
fi

artifact_dir="$(cd "$artifact_dir" && pwd)"
release_notes="$(cd "$(dirname "$release_notes")" && pwd)/$(basename "$release_notes")"
[[ -f "$release_notes" ]] || die "missing release notes: $release_notes"

if [[ -z "$output_dir" ]]; then
  output_dir="$artifact_dir/release-$tag"
fi
mkdir -p "$output_dir"
output_dir="$(cd "$output_dir" && pwd)"

manifest_dir=""
for candidate in "$artifact_dir"/release-"$tag" "$artifact_dir"; do
  if [[ -f "$candidate/SHA256SUMS" && -f "$candidate/ARTIFACTS.md" ]]; then
    manifest_dir="$(cd "$candidate" && pwd)"
    break
  fi
done
[[ -n "$manifest_dir" ]] || die "missing release manifest; run generate-release-manifest.sh first"

"$root/scripts/validate-release-artifacts.sh" "$artifact_dir" "$manifest_dir" >/dev/null

draft_script="$output_dir/gh-release-draft.sh"
asset_list="$output_dir/release-assets.txt"

: > "$asset_list"
find "$artifact_dir" -maxdepth 1 -type f ! -name '.DS_Store' | sort >> "$asset_list"
printf '%s\n' "$manifest_dir/SHA256SUMS" "$manifest_dir/ARTIFACTS.md" >> "$asset_list"

{
  printf '#!/usr/bin/env bash\n'
  printf 'set -euo pipefail\n\n'
  printf 'gh release create %q \\\n' "$tag"
  printf '  --draft \\\n'
  printf '  --title %q \\\n' "OpenPhone $tag"
  printf '  --notes-file %q' "$release_notes"
  while IFS= read -r asset; do
    printf ' \\\n  %q' "$asset"
  done < "$asset_list"
  printf '\n'
} > "$draft_script"
chmod +x "$draft_script"

printf 'GitHub release draft command written to %s\n' "$draft_script"
printf 'Release asset list written to %s\n' "$asset_list"
