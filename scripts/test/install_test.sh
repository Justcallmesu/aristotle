#!/usr/bin/env bash
# Tests for scripts/install.sh, run in a temp HOME with a stub aristotle-server and stub journalctl/systemctl first on PATH.
# Nothing here touches the real HOME, ~/.local/bin or systemd.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
source "$HERE/lib.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/install-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# new_case: a fresh temp HOME, stub dir and bundle (install.sh + stub binary + a dummy APK + SHA256SUMS).
new_case() {
  CASE="$WORK/$1"
  T_HOME="$CASE/home"
  BUNDLE="$CASE/bundle"
  STUBS="$CASE/stubs"
  mkdir -p "$T_HOME" "$BUNDLE" "$STUBS" "$CASE/state"
  : > "$CASE/calls.log"
  cp "$HERE/stubs/journalctl" "$HERE/stubs/systemctl" "$STUBS/"
  chmod +x "$STUBS"/*
  cp "$ROOT/scripts/install.sh" "$BUNDLE/install.sh"
  cp "$HERE/stubs/aristotle-server" "$BUNDLE/aristotle-server"
  echo "not really an apk" > "$BUNDLE/aristotle-9.9.9.apk"
  write_sums
  TARGET="$T_HOME/.local/bin/aristotle-server"
}

write_sums() { (cd "$BUNDLE" && sha256sum aristotle-9.9.9.apk aristotle-server install.sh > SHA256SUMS); }

old_binary() { # an installed binary that differs from the bundle's
  mkdir -p "$(dirname "$TARGET")"
  cp "$HERE/stubs/aristotle-server" "$TARGET"
  echo "# old build" >> "$TARGET"
  chmod +x "$TARGET"
  cp "$TARGET" "$CASE/old-binary"
}

run_install() { # args... ; sets CODE, OUT, CALLS
  CODE=0
  OUT="$(env HOME="$T_HOME" XDG_CONFIG_HOME="$T_HOME/.config" PATH="$STUBS:$PATH" \
    STUB_LOG="$CASE/calls.log" STUB_STATE="$CASE/state" STUB_STATUS_FAILS="${STATUS_FAILS:-0}" \
    ARISTOTLE_INSTALL_TIMEOUT="${INSTALL_TIMEOUT:-5}" bash "$BUNDLE/install.sh" "$@" < /dev/null 2>&1)" || CODE=$?
  CALLS="$(cat "$CASE/calls.log")"
}

echo "case: fresh install (status comes up on the 3rd poll)"
new_case fresh
STATUS_FAILS=2 run_install --no-pair
expect_code 0 "$CODE" "fresh install"
expect_same "$BUNDLE/aristotle-server" "$TARGET" "binary copied to ~/.local/bin"
expect_no_file "$TARGET.prev" "no .prev on a fresh install"
expect_contains "$CALLS" "aristotle-server install --force --start" "ran install --force --start"
expect_contains "$(cat "$CASE/state/status-calls")" "3" "polled status until it exited 0"
expect_contains "$OUT" "Running: aristotle-server 9.9.9 (stub) protocol 1" "printed the version"
expect_contains "$OUT" "$BUNDLE/aristotle-9.9.9.apk" "said where the APK is"
expect_contains "$OUT" "uninstall the debug app first" "said the debug app must be uninstalled"
expect_not_contains "$CALLS" "aristotle-server pair" "--no-pair: no pairing"
expect_not_contains "$CALLS" "UNEXPECTED" "never ran systemctl itself"
[[ -z "$(ls -A "$T_HOME/.local/bin" | grep -v '^aristotle-server$' || true)" ]] && pass "no temp file left in ~/.local/bin" || fail "temp file left in ~/.local/bin"

echo "case: update keeps the previous binary as .prev"
new_case update
old_binary
run_install --pair
expect_code 0 "$CODE" "update"
expect_same "$BUNDLE/aristotle-server" "$TARGET" "new binary in place"
expect_same "$CASE/old-binary" "$TARGET.prev" "previous binary kept as .prev"
expect_contains "$OUT" "Rollback, if needed: mv -f \"$TARGET.prev\" \"$TARGET\"" "printed the rollback command"
expect_contains "$CALLS" "aristotle-server pair" "--pair: printed the pairing QR"
expect_contains "$OUT" "PAIRING QR (stub)" "pairing output shown"

echo "case: rerun with the same binary keeps the older .prev"
run_install --no-pair
expect_code 0 "$CODE" "rerun"
expect_same "$CASE/old-binary" "$TARGET.prev" ".prev still the older binary"

echo "case: bad checksum refuses before copying"
new_case badsum
old_binary
echo "# tampered" >> "$BUNDLE/aristotle-server"
run_install --no-pair
expect_code 1 "$CODE" "bad checksum"
expect_contains "$OUT" "checksum mismatch" "said checksum mismatch"
expect_same "$CASE/old-binary" "$TARGET" "installed binary untouched"
expect_no_file "$TARGET.prev" "no .prev written"
[[ -z "$CALLS" ]] && pass "nothing was run" || fail "ran: $CALLS"

echo "case: SHA256SUMS without the binary refuses"
new_case nolisting
(cd "$BUNDLE" && sha256sum install.sh > SHA256SUMS)
run_install --no-pair
expect_code 1 "$CODE" "binary not listed"
expect_no_file "$TARGET" "nothing installed"

echo "case: status never comes up -> non-zero and the journal tail"
new_case down
STATUS_FAILS=always INSTALL_TIMEOUT=2 run_install --no-pair
expect_code 1 "$CODE" "server never came up"
expect_contains "$OUT" "did not come up within 2s" "said it timed out"
expect_contains "$CALLS" "journalctl --user -u aristotle-server -n 30 --no-pager" "read the unit's journal"
expect_contains "$OUT" "stub journal: last line" "printed the journal tail"
expect_not_contains "$OUT" "Running:" "no version line after a failed start"
expect_not_contains "$CALLS" "aristotle-server pair" "no pairing after a failed start"

echo "case: non-interactive without a flag does not pair"
new_case noninteractive
run_install
expect_code 0 "$CODE" "no flag"
expect_not_contains "$CALLS" "aristotle-server pair" "no pairing without a terminal"
expect_contains "$OUT" "Pair a phone later with: aristotle-server pair" "told how to pair later"

finish install_test
