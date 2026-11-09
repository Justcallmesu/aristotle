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
  echo "0::/user.slice/user-1000.slice/session-1.scope" > "$CASE/cgroup"
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
  OUT="$(env -u ARISTOTLE_TERMINAL -u XDG_DATA_HOME HOME="$T_HOME" XDG_CONFIG_HOME="$T_HOME/.config" PATH="$STUBS:$PATH" \
    ARISTOTLE_CGROUP_FILE="${CGROUP_FILE_FOR_TEST:-$CASE/cgroup}" ${EXTRA_ENV:-} \
    STUB_INSTALL_FAILS="${INSTALL_FAILS:-}" \
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

echo "case: the APK and aristotle.json land in the app dir (default ~/.local/share), json last"
APP="$T_HOME/.local/share/aristotle/app"
expect_same "$BUNDLE/aristotle-9.9.9.apk" "$APP/aristotle.apk" "APK copied to the app dir"
expect_eq "{\"version\":\"9.9.9\",\"version_code\":90909,\"sha256\":\"$(sha256sum "$BUNDLE/aristotle-9.9.9.apk" | cut -d' ' -f1)\",\"size\":$(stat -c %s "$BUNDLE/aristotle-9.9.9.apk")}" \
  "$(cat "$APP/aristotle.json")" "aristotle.json content"
[[ -z "$(ls -A "$APP" | grep -v -e '^aristotle.apk$' -e '^aristotle.json$' || true)" ]] && pass "no temp file left in the app dir" || fail "temp file left in the app dir"
expect_contains "$OUT" "Phone update 9.9.9 copied to $APP" "said where the phone update went"

echo "case: version_code follows major*10000+minor*100+patch; XDG_DATA_HOME wins; a rerun replaces the pair"
new_case appdir
mv "$BUNDLE/aristotle-9.9.9.apk" "$BUNDLE/aristotle-12.3.45.apk"
(cd "$BUNDLE" && sha256sum aristotle-12.3.45.apk aristotle-server install.sh > SHA256SUMS)
mkdir -p "$CASE/old-app"
echo stale > "$CASE/old-app/aristotle.apk"; echo stale > "$CASE/old-app/aristotle.json"
mkdir -p "$CASE/data/aristotle/app" && cp "$CASE/old-app/"* "$CASE/data/aristotle/app/"
EXTRA_ENV="XDG_DATA_HOME=$CASE/data" run_install --no-pair
expect_code 0 "$CODE" "install with XDG_DATA_HOME"
expect_contains "$(cat "$CASE/data/aristotle/app/aristotle.json")" '"version":"12.3.45","version_code":120345,' "version_code 12.3.45"
expect_same "$BUNDLE/aristotle-12.3.45.apk" "$CASE/data/aristotle/app/aristotle.apk" "stale APK replaced"
expect_no_file "$T_HOME/.local/share" "default dir untouched when XDG_DATA_HOME is set"

echo "case: a bad checksum leaves the app dir untouched"
new_case appsum
echo "# tampered" >> "$BUNDLE/aristotle-9.9.9.apk"
run_install --no-pair
expect_code 1 "$CODE" "bad APK checksum"
expect_no_file "$T_HOME/.local/share/aristotle" "nothing copied to the app dir"

echo "case: an APK missing from SHA256SUMS refuses before touching anything (UP2b)"
new_case apkunlisted
old_binary
(cd "$BUNDLE" && sha256sum aristotle-server install.sh > SHA256SUMS)
run_install --no-pair
expect_code 1 "$CODE" "APK not listed"
expect_contains "$OUT" "does not list aristotle-9.9.9.apk" "said which file is not listed"
expect_same "$CASE/old-binary" "$TARGET" "installed binary untouched"
expect_no_file "$TARGET.prev" "no .prev written"
expect_no_file "$T_HOME/.local/share/aristotle" "nothing copied to the app dir"
[[ -z "$CALLS" ]] && pass "nothing was run" || fail "ran: $CALLS"

echo "case: a bad APK file name refuses before the binary is replaced (UP2b)"
new_case apkname
old_binary
mv "$BUNDLE/aristotle-9.9.9.apk" "$BUNDLE/aristotle-nightly.apk"
(cd "$BUNDLE" && sha256sum aristotle-nightly.apk aristotle-server install.sh > SHA256SUMS)
run_install --no-pair
expect_code 1 "$CODE" "bad APK name"
expect_contains "$OUT" "cannot read a version x.y.z from aristotle-nightly.apk" "said why"
expect_same "$CASE/old-binary" "$TARGET" "installed binary untouched"
expect_no_file "$T_HOME/.local/share/aristotle" "nothing copied to the app dir"
[[ -z "$CALLS" ]] && pass "nothing was run" || fail "ran: $CALLS"

echo "case: the app pair is in place before the server binary is replaced (UP2b)"
new_case order
old_binary
# The stub's install step runs after both copies; a failing app copy must leave the old binary: make the app dir unwritable.
mkdir -p "$T_HOME/.local/share/aristotle/app"
chmod 0555 "$T_HOME/.local/share/aristotle/app"
if [[ -w "$T_HOME/.local/share/aristotle/app" ]]; then
  pass "(skipped: running as a user that ignores directory modes)"
else
  run_install --no-pair
  expect_code 1 "$CODE" "app copy fails"
  expect_same "$CASE/old-binary" "$TARGET" "the old binary stays when the app copy fails"
  expect_no_file "$TARGET.prev" "no .prev written"
  [[ -z "$CALLS" ]] && pass "service not restarted" || fail "ran: $CALLS"
fi
chmod 0755 "$T_HOME/.local/share/aristotle/app"

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

echo "case: install --force --start fails -> rollback line, journal tail, non-zero"
new_case installfail
old_binary
INSTALL_FAILS=1 run_install --no-pair
expect_code 1 "$CODE" "install failed"
expect_contains "$OUT" "aristotle-server install failed" "said the install failed"
expect_contains "$OUT" "stub journal: last line" "printed the journal tail"
expect_contains "$OUT" "To go back to the previous binary: mv -f \"$TARGET.prev\" \"$TARGET\"" "printed the rollback command"
expect_not_contains "$OUT" "Running:" "no version line after a failed install"
expect_not_contains "$CALLS" "aristotle-server status" "did not poll status"

echo "case: refuses inside the app's terminal"
new_case marker
EXTRA_ENV="ARISTOTLE_TERMINAL=1" run_install --no-pair
expect_code 1 "$CODE" "ARISTOTLE_TERMINAL set"
expect_contains "$OUT" "run this in a terminal on the PC, not from the phone" "said why"
expect_no_file "$TARGET" "nothing installed"
[[ -z "$CALLS" ]] && pass "nothing was run" || fail "ran: $CALLS"
new_case cgroup
echo "0::/user.slice/user-1000.slice/user@1000.service/app.slice/aristotle-server.service/term-1" > "$CASE/cgroup"
run_install --no-pair
expect_code 1 "$CODE" "inside the service cgroup"
expect_contains "$OUT" "run this in a terminal on the PC, not from the phone" "said why"
expect_no_file "$TARGET" "nothing installed"

echo "case: the bundled install.sh keeps its executable bit"
[[ -x "$ROOT/scripts/install.sh" ]] && pass "scripts/install.sh is executable" || fail "scripts/install.sh is not executable"
[[ -x "$ROOT/scripts/release.sh" ]] && pass "scripts/release.sh is executable" || fail "scripts/release.sh is not executable"

echo "case: non-interactive without a flag does not pair"
new_case noninteractive
run_install
expect_code 0 "$CODE" "no flag"
expect_not_contains "$CALLS" "aristotle-server pair" "no pairing without a terminal"
expect_contains "$OUT" "Pair a phone later with: aristotle-server pair" "told how to pair later"

finish install_test
