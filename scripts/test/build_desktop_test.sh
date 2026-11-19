#!/usr/bin/env bash
# Tests for scripts/build-desktop.sh on a scratch tree with stub npm, cargo and tauri on PATH (they log their arguments and
# create the binaries). Nothing is built; the real desktop/ tree is never touched.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/build-desktop-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
S="$WORK/repo"
mkdir -p "$S/scripts" "$S/desktop/ui/node_modules/.bin" "$S/desktop/packaging" "$S/desktop/app/icons" "$WORK/stubs" "$WORK/out"
cp "$ROOT/scripts/build-desktop.sh" "$S/scripts/"
cp "$ROOT/desktop/packaging/"*.desktop "$S/desktop/packaging/"
echo png > "$S/desktop/app/icons/icon.png"
export STUB_LOG="$WORK/calls.log"
: > "$STUB_LOG"

cat > "$WORK/stubs/npm" <<'STUB'
#!/usr/bin/env bash
echo "npm $*" >> "$STUB_LOG"
STUB
cat > "$WORK/stubs/cargo" <<'STUB'
#!/usr/bin/env bash
echo "cargo $*" >> "$STUB_LOG"
mkdir -p "$CARGO_TARGET_DIR/release"
echo bin > "$CARGO_TARGET_DIR/release/aristotle-tray"
chmod +x "$CARGO_TARGET_DIR/release/aristotle-tray"
STUB
cat > "$S/desktop/ui/node_modules/.bin/tauri" <<'STUB'
#!/usr/bin/env bash
echo "tauri $*" >> "$STUB_LOG"
mkdir -p "$CARGO_TARGET_DIR/release"
echo bin > "$CARGO_TARGET_DIR/release/aristotle-desktop"
chmod +x "$CARGO_TARGET_DIR/release/aristotle-desktop"
STUB
chmod +x "$WORK/stubs/npm" "$WORK/stubs/cargo" "$S/desktop/ui/node_modules/.bin/tauri"

echo "case: build-desktop.sh builds with --locked (a stale Cargo.lock fails the build, it is not rewritten)"
CODE=0
OUT="$(PATH="$WORK/stubs:$PATH" CARGO_TARGET_DIR="$WORK/target" bash "$S/scripts/build-desktop.sh" --version 9.9.9 "$WORK/out" 2>&1)" || CODE=$?
expect_code 0 "$CODE" "build-desktop.sh"
CALLS="$(cat "$STUB_LOG")"
expect_contains "$CALLS" "npm ci" "npm ci"
expect_contains "$CALLS" "cargo build --release --locked -p aristotle-tray" "the tray is built with --locked"
expect_contains "$CALLS" "tauri build --no-bundle -- --locked" "the app is built with --locked"
expect_file "$WORK/out/aristotle-tray" "tray copied to the output"
expect_file "$WORK/out/aristotle-desktop" "app copied to the output"
expect_file "$WORK/out/aristotle-tray.desktop" "template copied"

finish build_desktop_test
