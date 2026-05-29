#!/usr/bin/env bash

set -euo pipefail

install_dir="${OPENPHONE_REPO_BIN_DIR:-$HOME/.local/bin}"
repo_url="${OPENPHONE_REPO_URL:-https://storage.googleapis.com/git-repo-downloads/repo}"

mkdir -p "$install_dir"

if command -v curl >/dev/null 2>&1; then
  curl -L "$repo_url" -o "$install_dir/repo"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$install_dir/repo" "$repo_url"
else
  printf 'error: curl or wget is required to install repo\n' >&2
  exit 1
fi

chmod +x "$install_dir/repo"

cat <<MSG
repo installed at:
  $install_dir/repo

Add this to your shell profile if it is not already present:
  export PATH="\$HOME/.local/bin:\$PATH"
MSG

