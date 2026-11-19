#!/usr/bin/env bash
# Build the desktop programs. Usage: scripts/build-desktop.sh [--version X.Y.Z] <outdir>
# Builds the UI (npm ci + build), then aristotle-tray and aristotle-desktop in release mode (plain binaries, no Tauri
# bundler; the same two commands as docs/desktop/gates.sh) with ARISTOTLE_VERSION set, and copies into <outdir>:
#   aristotle-tray, aristotle-desktop, aristotle-tray.desktop, aristotle-desktop.desktop (templates, @BIN_DIR@ left in),
#   aristotle-server.png (the 256 px app icon, desktop/app/icons/icon.png).
# Version: --version when given (release.sh), else `git describe --tags` (leading v stripped), else 0.0.0-dev+<short sha>.
# CARGO_TARGET_DIR defaults to desktop/target, the gate's directory, so a gate run's build cache is reused.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() { echo "usage: build-desktop.sh [--version X.Y.Z] <outdir>" >&2; exit 2; }

VERSION=""
if [[ "${1:-}" == --version ]]; then
  [[ $# -ge 2 && -n "$2" ]] || usage
  VERSION="$2"
  shift 2
fi
[[ $# -eq 1 && -n "$1" ]] || usage
mkdir -p "$1"
OUT="$(cd "$1" && pwd)"

if [[ -z "$VERSION" ]]; then
  if VERSION="$(git -C "$ROOT" describe --tags 2> /dev/null)"; then
    VERSION="${VERSION#v}"
  else
    VERSION="0.0.0-dev+$(git -C "$ROOT" rev-parse --short HEAD 2> /dev/null || echo unknown)"
  fi
fi
export ARISTOTLE_VERSION="$VERSION"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/desktop/target}"

build_ui() { (cd "$ROOT/desktop/ui" && npm ci && npm run build); }
# `tauri build` finds the app by searching for tauri.conf.json below the current directory; --no-bundle = plain binary.
# --locked: a stale Cargo.lock fails here, at once, instead of being rewritten and caught after a long build (L5).
build_binaries() {
  (cd "$ROOT/desktop" && cargo build --release --locked -p aristotle-tray && ui/node_modules/.bin/tauri build --no-bundle -- --locked)
}

install_outputs() {
  local rel="$CARGO_TARGET_DIR/release" name
  for name in aristotle-tray aristotle-desktop; do
    [[ -x "$rel/$name" ]] || { echo "build-desktop: $rel/$name was not built" >&2; exit 1; }
    cp "$rel/$name" "$OUT/$name"
    chmod 0755 "$OUT/$name"
  done
  for name in aristotle-tray.desktop aristotle-desktop.desktop; do
    cp "$ROOT/desktop/packaging/$name" "$OUT/$name"
    chmod 0644 "$OUT/$name"
  done
  cp "$ROOT/desktop/app/icons/icon.png" "$OUT/aristotle-server.png"
  chmod 0644 "$OUT/aristotle-server.png"
}

build_ui
build_binaries
install_outputs
echo "built the desktop programs in $OUT ($VERSION)"
