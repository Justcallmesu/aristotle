#!/usr/bin/env bash
# Tests for scripts/install.sh, run in a temp HOME with a stub aristotle-server and stub journalctl/systemctl/hermes/loginctl/
# sudo/ufw/firewall-cmd first on PATH. Nothing here touches the real HOME, ~/.local/bin, systemd, Hermes or a firewall.
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
  cp "$HERE/stubs/journalctl" "$HERE/stubs/systemctl" "$HERE/stubs/hermes" "$HERE/stubs/loginctl" \
    "$HERE/stubs/sudo" "$HERE/stubs/ufw" "$HERE/stubs/firewall-cmd" "$STUBS/"
  chmod +x "$STUBS"/*
  cp "${INSTALL_SH:-$ROOT/scripts/install.sh}" "$BUNDLE/install.sh"
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

# clean_path prints a PATH that has every program of the real PATH except hermes, ufw and firewall-cmd (a symlink farm, built
# once), so "hermes missing" and "no firewall tool" are testable on a PC that has them, and no real firewall tool is reachable. Earlier PATH directories win, like a normal lookup.
NOHERMES="$WORK/path-without-hermes"
clean_path() {
  if [[ ! -d "$NOHERMES" ]]; then
    local dir rest="$PATH:"
    mkdir -p "$NOHERMES"
    while [[ -n "$rest" ]]; do
      dir="${rest%%:*}"; rest="${rest#*:}"
      [[ -d "$dir" && "$dir" != "$NOHERMES" ]] || continue
      find "$dir" -maxdepth 1 \( -type f -o -type l \) ! -name hermes ! -name ufw ! -name firewall-cmd -exec ln -sn -t "$NOHERMES" {} + 2> /dev/null || true
    done
  fi
  echo "$NOHERMES"
}

run_install() { # args... ; sets CODE, OUT, CALLS (STUB_* variables given as prefix assignments reach the stubs)
  CODE=0
  OUT="$(env -u ARISTOTLE_TERMINAL -u XDG_DATA_HOME HOME="$T_HOME" XDG_CONFIG_HOME="$T_HOME/.config" PATH="$STUBS:$(clean_path)" \
    ARISTOTLE_CGROUP_FILE="${CGROUP_FILE_FOR_TEST:-$CASE/cgroup}" ARISTOTLE_SBIN_DIRS= ${EXTRA_ENV:-} \
    STUB_INSTALL_FAILS="${INSTALL_FAILS:-}" \
    STUB_LOG="$CASE/calls.log" STUB_STATE="$CASE/state" STUB_STATUS_FAILS="${STATUS_FAILS:-0}" \
    ARISTOTLE_INSTALL_TIMEOUT="${INSTALL_TIMEOUT:-5}" ARISTOTLE_GATEWAY_TIMEOUT="${GATEWAY_TIMEOUT:-5}" bash "$BUNDLE/install.sh" "$@" < /dev/null 2>&1)" || CODE=$?
  CALLS="$(cat "$CASE/calls.log")"
}

# run_install_pty runs the installer with a terminal on stdin and stdout (util-linux `script`), like a person at the PC.
# TTY_INPUT is typed into the terminal (sudo "passwords"). Sets CODE, OUT, CALLS. Needs STUB_* values without newlines.
run_install_pty() { # args...
  local wrapper="$CASE/pty-run.sh" var
  {
    echo "#!/usr/bin/env bash"
    printf 'exec env -u ARISTOTLE_TERMINAL -u XDG_DATA_HOME HOME=%q XDG_CONFIG_HOME=%q PATH=%q ARISTOTLE_CGROUP_FILE=%q ARISTOTLE_SBIN_DIRS= \\\n' \
      "$T_HOME" "$T_HOME/.config" "$STUBS:$(clean_path)" "$CASE/cgroup"
    for var in $(compgen -v STUB_); do printf '  %s=%q \\\n' "$var" "${!var}"; done
    printf '  STUB_LOG=%q STUB_STATE=%q ARISTOTLE_INSTALL_TIMEOUT=%q ARISTOTLE_GATEWAY_TIMEOUT=%q bash %q' "$CASE/calls.log" "$CASE/state" "${INSTALL_TIMEOUT:-5}" "${GATEWAY_TIMEOUT:-5}" "$BUNDLE/install.sh"
    printf ' %q' "$@"; echo
  } > "$wrapper"
  CODE=0
  OUT="$( (printf '%s' "${TTY_INPUT:-}"; sleep 1) | script -qec "bash $wrapper" /dev/null 2>&1)" || CODE=$?
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

echo "case: flag parsing"
new_case flags
run_install --pair --no-pair
expect_code 2 "$CODE" "--pair --no-pair"
run_install --no-pair --pair
expect_code 2 "$CODE" "--no-pair --pair"
run_install --bogus
expect_code 2 "$CODE" "unknown flag"
run_install --no-pair --bogus
expect_code 2 "$CODE" "unknown flag after a good one"
expect_no_file "$TARGET" "usage errors install nothing"
run_install --no-gateway --no-firewall --no-pair
expect_code 0 "$CODE" "--no-gateway --no-firewall --no-pair"
run_install --no-pair --no-firewall --no-gateway
expect_code 0 "$CODE" "--no-pair --no-firewall --no-gateway"
run_install --no-firewall --pair --no-gateway
expect_code 0 "$CODE" "--no-firewall --pair --no-gateway"

echo "case: hermes CLI missing refuses before anything is touched"
new_case nohermes
rm "$STUBS/hermes"
run_install --no-pair
expect_code 1 "$CODE" "hermes missing"
expect_contains "$OUT" "Hermes Agent is not installed" "said Hermes is missing"
expect_contains "$OUT" "--no-gateway" "named the --no-gateway way out"
expect_no_file "$TARGET" "no binary installed"
expect_no_file "$T_HOME/.local/share/aristotle" "no app copy"
expect_not_contains "$CALLS" "aristotle-server install" "no install call"
[[ -z "$CALLS" ]] && pass "nothing was run" || fail "ran: $CALLS"

echo "case: hermes missing is fine with --no-gateway; ~/.local/bin/hermes is found"
run_install --no-pair --no-gateway
expect_code 0 "$CODE" "--no-gateway without hermes"
new_case localhermes
mv "$STUBS/hermes" "$CASE/hermes-stub"
mkdir -p "$T_HOME/.local/bin" && cp "$CASE/hermes-stub" "$T_HOME/.local/bin/hermes"
STUB_PORCELAIN_HERMES=not-running STUB_HERMES_AFTER_POLLS=1 run_install --no-pair
expect_code 0 "$CODE" "hermes found in ~/.local/bin"
expect_contains "$CALLS" "hermes -p default gateway install" "used ~/.local/bin/hermes"

echo "case: hermes reachable -> no gateway call"
new_case reachable
run_install --no-pair
expect_code 0 "$CODE" "reachable"
expect_contains "$OUT" "Hermes: reachable" "said reachable"
expect_not_contains "$CALLS" "hermes gateway" "no hermes gateway call"
expect_contains "$CALLS" "aristotle-server status --porcelain" "read the porcelain"

echo "case: not reachable -> gateway install with HERMES_HOME unset, then reachable after polls"
new_case gateway
HERMES_HOME=/x STUB_PORCELAIN_HERMES=not-running STUB_HERMES_AFTER_POLLS=3 run_install --no-pair
expect_code 0 "$CODE" "gateway comes up"
expect_contains "$OUT" "Hermes gateway is not running; installing it as a user service" "said what it does"
expect_contains "$CALLS" "hermes -p default gateway install" "ran hermes -p default gateway install (not the sticky profile)"
expect_contains "$CALLS" "hermes HERMES_HOME=<unset>" "HERMES_HOME was unset for hermes"
expect_not_contains "$CALLS" "HERMES_HOME=/x" "the inherited HERMES_HOME did not leak"
expect_eq "3" "$(cat "$CASE/state/after-polls")" "polled until reachable"
expect_contains "$OUT" "Hermes: reachable" "said reachable"

echo "case: gateway never reachable -> exit 1 with the journal tail"
new_case gatewaydown
STUB_PORCELAIN_HERMES=not-running STUB_HERMES_REASON_LATE="late reason after the gateway step" GATEWAY_TIMEOUT=2 run_install --no-pair
expect_code 1 "$CODE" "never reachable"
expect_contains "$OUT" "still cannot reach Hermes after 2s" "said why"
expect_contains "$OUT" "Waiting for Hermes to become ready (up to 2s; a fresh gateway takes about half a minute)..." "said how long it waits"
expect_contains "$OUT" "Hermes:   late reason after the gateway step" "printed the server's Hermes line read AFTER the gateway step"
expect_contains "$OUT" "aristotle-server 9.9.9 is installed and running; linger, firewall and pairing were NOT done; fix Hermes, then run the installer again." "said what state the PC is in"
expect_not_contains "$CALLS" "loginctl" "linger step not reached"
expect_not_contains "$CALLS" "ufw" "firewall step not reached"
expect_contains "$CALLS" "journalctl --user -u hermes-gateway -n 30 --no-pager" "read the gateway journal"
expect_contains "$OUT" "stub journal: last line" "printed the journal tail"
expect_not_contains "$OUT" "Running:" "no version line"
new_case gatewayfail
STUB_PORCELAIN_HERMES=not-running STUB_HERMES_FAILS=1 run_install --no-pair
expect_code 1 "$CODE" "hermes gateway install fails"
expect_contains "$OUT" "hermes gateway install failed" "said the install failed"
expect_contains "$OUT" "Hermes:   not reachable: stub reason" "printed the server's own Hermes line"
expect_contains "$OUT" "linger, firewall and pairing were NOT done" "said what was skipped"
expect_contains "$OUT" "stub journal: last line" "printed the journal tail"

echo "case: the gateway wait has its own budget: slow Hermes passes with a 1s server timeout (IN2c)"
new_case gatewayslow
STUB_PORCELAIN_HERMES=not-running STUB_HERMES_AFTER_POLLS=4 INSTALL_TIMEOUT=1 GATEWAY_TIMEOUT=10 run_install --no-pair
expect_code 0 "$CODE" "Hermes reachable after more than ARISTOTLE_INSTALL_TIMEOUT"
expect_contains "$OUT" "Waiting for Hermes to become ready (up to 10s; a fresh gateway takes about half a minute)..." "printed the wait line"
expect_contains "$OUT" "Hermes: reachable" "said reachable"
new_case gatewaybad
for bad in abc -5 1.5 "5 s"; do
  GATEWAY_TIMEOUT="$bad" run_install --no-pair
  expect_code 2 "$CODE" "ARISTOTLE_GATEWAY_TIMEOUT='$bad'"
  expect_contains "$OUT" "ARISTOTLE_GATEWAY_TIMEOUT must be a number of seconds" "said why"
done
expect_no_file "$TARGET" "bad value installs nothing"

echo "case: hermes error (a gateway answered) -> no gateway install, reason and advice, exit 1"
new_case gatewayerror
STUB_PORCELAIN_HERMES=error GATEWAY_TIMEOUT=2 run_install --no-pair
expect_code 1 "$CODE" "hermes error"
expect_not_contains "$CALLS" "hermes -p" "never ran hermes gateway install"
expect_not_contains "$CALLS" "hermes gateway" "never ran any hermes gateway command"
expect_contains "$OUT" "Hermes:   not reachable: stub reason" "printed the reason"
expect_contains "$OUT" "is installed and running; linger, firewall and pairing were NOT done; fix Hermes" "printed the advice"
expect_contains "$CALLS" "journalctl --user -u hermes-gateway" "read the gateway journal"
new_case gatewayerrorfixed
STUB_PORCELAIN_HERMES=error STUB_HERMES_AFTER_POLLS=1 run_install --no-pair --no-gateway
expect_code 0 "$CODE" "hermes error with --no-gateway"

echo "case: the server is not running at the gateway step -> fails like wait_for_status"
new_case serverdead
STUB_PORCELAIN_SERVER=not-running run_install --no-pair
expect_code 1 "$CODE" "server not running at the gateway step"
expect_contains "$OUT" "cannot read the server status" "said why"
expect_contains "$CALLS" "journalctl --user -u aristotle-server" "read the server journal"
expect_not_contains "$CALLS" "hermes gateway" "no gateway call"
expect_not_contains "$CALLS" "hermes -p" "no gateway install"
expect_not_contains "$OUT" "cannot check Hermes" "did not blame the server version"
new_case serverdied
STUB_SERVER_DIES_AFTER=1 STUB_UFW_STATUS=active run_install --no-pair
expect_code 1 "$CODE" "server stopped before the firewall step"
expect_contains "$OUT" "cannot read the server status" "said why"
expect_not_contains "$OUT" "no rule" "did not claim no rule is needed"
expect_not_contains "$CALLS" "ufw" "no firewall call"

echo "case: --no-gateway and not reachable -> warning only"
new_case nogateway
STUB_PORCELAIN_HERMES=error run_install --no-pair --no-gateway
expect_code 0 "$CODE" "--no-gateway"
expect_contains "$OUT" "hermes gateway install" "printed the command to run"
expect_contains "$OUT" "Hermes is not reachable" "warned"
expect_not_contains "$CALLS" "hermes gateway" "no gateway call"

echo "case: old server without a hermes line -> warning, exit 0"
new_case oldserver
STUB_PORCELAIN_HERMES=none run_install --no-pair
expect_code 0 "$CODE" "no hermes line"
expect_contains "$OUT" "cannot check Hermes" "warned"
expect_not_contains "$CALLS" "hermes gateway" "no gateway call"

echo "case: linger"
new_case lingerno
STUB_LINGER=no run_install --no-pair
expect_code 0 "$CODE" "linger off"
expect_contains "$CALLS" "loginctl enable-linger" "enabled linger"
new_case langeryes
STUB_LINGER=yes run_install --no-pair
expect_code 0 "$CODE" "linger on"
expect_not_contains "$CALLS" "enable-linger" "did not touch linger"
new_case lingerfail
STUB_LINGER=no STUB_LOGINCTL_FAILS=1 run_install --no-pair
expect_code 0 "$CODE" "loginctl fails"
expect_contains "$OUT" "could not enable linger" "warned"

echo "case: ufw active, two LAN subnets -> four rules with the porcelain port, none for Tailscale"
new_case ufw
STUB_UFW_STATUS=active STUB_PORCELAIN_PORT=8799 STUB_PORCELAIN_LAN=$'wlan0 192.168.1.20 192.168.1.0/24\neth0 10.0.5.7 10.0.5.0/24' run_install --no-pair
expect_code 0 "$CODE" "ufw"
expect_eq "4" "$(grep -c '^ufw allow' "$CASE/calls.log")" "exactly four allow rules"
expect_contains "$CALLS" "ufw allow from 192.168.1.0/24 to any port 8799 proto tcp comment aristotle" "tcp rule, subnet 1, porcelain port"
expect_contains "$CALLS" "ufw allow from 192.168.1.0/24 to any port 5353 proto udp comment aristotle" "mDNS rule, subnet 1"
expect_contains "$CALLS" "ufw allow from 10.0.5.0/24 to any port 8799 proto tcp comment aristotle" "tcp rule, subnet 2"
expect_contains "$CALLS" "ufw allow from 10.0.5.0/24 to any port 5353 proto udp comment aristotle" "mDNS rule, subnet 2"
expect_not_contains "$CALLS" "100.64" "no rule for the Tailscale address"
expect_not_contains "$CALLS" "8760" "the default port is not hard-coded"
expect_contains "$CALLS" "sudo -n env LC_ALL=C ufw allow" "non-interactive uses sudo -n"
expect_contains "$OUT" "Firewall: added 8799/tcp from 192.168.1.0/24" "listed an added rule"
expect_contains "$OUT" "On another network, run the installer again." "told how to cover another network"

echo "case: ufw rule that already exists is reported as kept"
new_case ufwkept
STUB_UFW_STATUS=active STUB_UFW_EXISTING=1 run_install --no-pair
expect_code 0 "$CODE" "ufw rerun"
expect_contains "$OUT" "Firewall: kept 8760/tcp from 192.168.1.0/24 (already there)" "kept"
expect_not_contains "$OUT" "Firewall: added" "added nothing"

echo "case: firewalld running -> rich rules + reload; an existing rule is not re-added"
new_case fwd
STUB_FWD_STATE=running STUB_PORCELAIN_PORT=8799 run_install --no-pair
expect_code 0 "$CODE" "firewalld"
expect_contains "$CALLS" 'firewall-cmd --permanent --add-rich-rule=rule family="ipv4" source address="192.168.1.0/24" port port="8799" protocol="tcp" accept' "tcp rich rule"
expect_contains "$CALLS" 'port port="5353" protocol="udp" accept' "udp rich rule"
expect_contains "$CALLS" "firewall-cmd --reload" "reloaded"
expect_not_contains "$CALLS" "ufw allow" "no ufw rule"
new_case fwdkept
STUB_FWD_STATE=running STUB_FWD_EXISTING=1 run_install --no-pair
expect_code 0 "$CODE" "firewalld rerun"
expect_not_contains "$CALLS" "--add-rich-rule" "existing rules not re-added"
expect_not_contains "$CALLS" "firewall-cmd --reload" "no reload without a change"
expect_contains "$OUT" "Firewall: kept 8760/tcp from 192.168.1.0/24" "kept"
new_case fwd6
STUB_FWD_STATE=running STUB_PORCELAIN_LAN="eth0 fd00::5 fd00::/64" run_install --no-pair
expect_contains "$CALLS" 'rule family="ipv6" source address="fd00::/64"' "ipv6 subnet gets an ipv6 rule"

echo "case: no active firewall -> nothing opened"
new_case nofw
run_install --no-pair
expect_code 0 "$CODE" "no active firewall"
expect_contains "$OUT" "no active firewall detected" "said so"
expect_not_contains "$CALLS" "allow" "no rule"
expect_not_contains "$CALLS" "add-rich-rule" "no rich rule"

echo "case: no LAN listener (after re-reading) -> no rule, said honestly"
new_case nolan
STUB_PORCELAIN_LAN= STUB_UFW_STATUS=active INSTALL_TIMEOUT=2 run_install --no-pair
expect_code 0 "$CODE" "no LAN"
expect_not_contains "$CALLS" "ufw allow" "no rule"
expect_contains "$OUT" "the server did not report a LAN listener" "worded as a fact, not as 'no rule needed'"
expect_not_contains "$OUT" "no rule is needed" "old wording gone"
[[ "$(grep -c 'status --porcelain' "$CASE/calls.log")" -ge 3 ]] && pass "re-read the porcelain before giving up" || fail "porcelain read only once or twice"

echo "case: the first LAN scan is not done yet -> re-read until the lan line shows, then open the firewall"
new_case lanlate
STUB_LAN_AFTER_CALLS=3 STUB_UFW_STATUS=active run_install --no-pair
expect_code 0 "$CODE" "late LAN listener"
expect_eq "2" "$(grep -c '^ufw allow' "$CASE/calls.log")" "both rules (one subnet) once the lan line appeared"
expect_not_contains "$OUT" "did not report a LAN listener" "no false 'no LAN'"

echo "case: non-interactive and sudo -n refused -> commands printed, exit 0"
new_case sudorefused
STUB_SUDO_REFUSE=1 STUB_PORCELAIN_PORT=8799 run_install --no-pair
expect_code 0 "$CODE" "sudo refused"
expect_contains "$OUT" "ufw allow from 192.168.1.0/24 to any port 8799 proto tcp comment 'aristotle'" "printed the ufw command"
expect_contains "$OUT" "firewall-cmd --permanent --add-rich-rule=" "printed the firewalld command"
expect_contains "$OUT" "warning: could not check or change the firewall" "warned"
expect_not_contains "$CALLS" "ufw allow" "ran no rule"

echo "case: --no-firewall prints the commands and never calls sudo"
new_case nofirewallflag
STUB_UFW_STATUS=active run_install --no-pair --no-firewall
expect_code 0 "$CODE" "--no-firewall"
expect_contains "$OUT" "sudo ufw allow from 192.168.1.0/24 to any port 8760 proto tcp comment 'aristotle'" "printed the commands"
expect_not_contains "$CALLS" "sudo" "no sudo call"
expect_not_contains "$CALLS" "ufw" "no ufw call"

echo "case: sudo refused at detection never fails the install"
new_case sudodetect
STUB_UFW_STATUS=active STUB_SUDO_REFUSE=1 run_install --no-pair
expect_code 0 "$CODE" "sudo refused at detection"
expect_contains "$OUT" "Running: aristotle-server 9.9.9" "install finished"

echo "case: ufw and firewall-cmd run under LC_ALL=C through sudo (a translated ufw would say 'Status: aktiv')"
new_case locale
STUB_UFW_STATUS=active run_install --no-pair
expect_code 0 "$CODE" "ufw under a translated-ufw stub"
expect_contains "$CALLS" "sudo -n env LC_ALL=C ufw status" "status through sudo env LC_ALL=C"
expect_contains "$CALLS" "ufw-env LC_ALL=C" "the ufw stub saw LC_ALL=C"
expect_not_contains "$CALLS" "LC_ALL=<unset>" "no ufw call without LC_ALL"
expect_eq "2" "$(grep -c '^ufw allow' "$CASE/calls.log")" "the active firewall was recognised, both rules (one subnet)"
new_case localefwd
STUB_FWD_STATE=running run_install --no-pair
expect_contains "$CALLS" "firewall-cmd-env LC_ALL=C" "the firewall-cmd stub saw LC_ALL=C"
expect_not_contains "$CALLS" "LC_ALL=<unset>" "no firewall-cmd call without LC_ALL"
expect_contains "$CALLS" "sudo -n env LC_ALL=C firewall-cmd --permanent" "rules through sudo env LC_ALL=C"

echo "case: no firewall tool at all -> warning and the commands for both firewalls, exit 0"
new_case notool
rm -f "$STUBS/ufw" "$STUBS/firewall-cmd"
run_install --no-pair
expect_code 0 "$CODE" "no firewall tool"
expect_contains "$OUT" "warning: could not check or change the firewall" "warned"
expect_contains "$OUT" "sudo ufw allow from 192.168.1.0/24 to any port 8760 proto tcp comment 'aristotle'" "printed the ufw command"
expect_contains "$OUT" "firewall-cmd --permanent --add-rich-rule=" "printed the firewalld command"
expect_not_contains "$CALLS" "sudo" "no sudo call"

echo "case: ufw refuses a rule -> warning, the exact ufw commands, exit 0"
new_case ufwrefuse
STUB_UFW_STATUS=active STUB_UFW_REFUSE=1 run_install --no-pair
expect_code 0 "$CODE" "ufw refuses"
expect_contains "$CALLS" "ufw status" "detection worked"
expect_contains "$OUT" "warning: ufw refused the rule for 8760/tcp" "warned"
expect_contains "$OUT" "  sudo ufw allow from 192.168.1.0/24 to any port 8760 proto tcp comment 'aristotle'" "printed the ufw commands"
expect_contains "$OUT" "  sudo ufw allow from 192.168.1.0/24 to any port 5353 proto udp comment 'aristotle'" "printed the mDNS command"
expect_not_contains "$OUT" "sudo firewall-cmd" "no firewalld commands for a ufw user"
expect_contains "$OUT" "Running: aristotle-server 9.9.9" "install finished"

echo "case: firewalld refuses a rule -> warning and the firewalld commands (they were missing), exit 0"
new_case fwdrefuse
STUB_FWD_STATE=running STUB_FWD_REFUSE=1 run_install --no-pair
expect_code 0 "$CODE" "firewalld refuses"
expect_contains "$OUT" "warning: firewalld refused the rule for 8760/tcp" "warned"
expect_contains "$OUT" "  sudo firewall-cmd --permanent --add-rich-rule='rule family=\"ipv4\" source address=\"192.168.1.0/24\" port port=\"8760\" protocol=\"tcp\" accept'" "printed the firewalld command"
expect_contains "$OUT" "  sudo firewall-cmd --reload" "printed the reload"
expect_not_contains "$OUT" "sudo ufw" "no ufw commands for a firewalld user"
new_case fwdreload
STUB_FWD_STATE=running STUB_FWD_RELOAD_FAILS=1 run_install --no-pair
expect_code 0 "$CODE" "reload fails"
expect_contains "$OUT" "warning: firewall-cmd --reload failed" "warned"
expect_contains "$OUT" "  sudo firewall-cmd --reload" "printed the reload command"

echo "case: interactive (pty) -> plain sudo, no -n, for detection and for every rule; the password is read inside the loop"
if ! command -v script > /dev/null 2>&1; then
  echo "  skip: script (util-linux) is not installed"
else
  new_case pty
  TTY_INPUT=$'pw\npw\npw\npw\npw\npw\npw\npw\n' STUB_SUDO_ASKTTY=1 STUB_UFW_STATUS=active STUB_PORCELAIN_PORT=8799 run_install_pty --no-pair
  expect_code 0 "$CODE" "interactive install"
  expect_contains "$OUT" "sudo may ask for your password" "printed the notice"
  expect_not_contains "$CALLS" "sudo -n" "no sudo -n call at all"
  expect_contains "$CALLS" "sudo env LC_ALL=C ufw status" "plain sudo for detection"
  expect_contains "$CALLS" "sudo env LC_ALL=C ufw allow from 192.168.1.0/24 to any port 8799 proto tcp comment aristotle" "plain sudo for the tcp rule"
  expect_contains "$CALLS" "sudo env LC_ALL=C ufw allow from 192.168.1.0/24 to any port 5353 proto udp comment aristotle" "plain sudo for the mDNS rule"
  expect_eq "3" "$(grep -c '^sudo password-read-from-tty' "$CASE/calls.log")" "sudo read a password from the terminal for each of its 3 calls"
  expect_eq "2" "$(grep -c '^ufw allow' "$CASE/calls.log")" "both rules went through"
  new_case ptyfwd
  TTY_INPUT=$'pw\npw\npw\npw\npw\npw\npw\npw\n' STUB_SUDO_ASKTTY=1 STUB_FWD_STATE=running run_install_pty --no-pair
  expect_code 0 "$CODE" "interactive firewalld install"
  expect_not_contains "$CALLS" "sudo -n" "no sudo -n call (firewalld)"
  expect_contains "$CALLS" "sudo env LC_ALL=C firewall-cmd --permanent --add-rich-rule=" "plain sudo for a firewalld rule"
  expect_contains "$CALLS" "sudo env LC_ALL=C firewall-cmd --reload" "plain sudo for the reload"
  new_case ptyrefused
  STUB_SUDO_REFUSE=1 STUB_FWD_STATE=running STUB_UFW_STATUS=active run_install_pty --no-pair
  expect_code 0 "$CODE" "interactive, sudo refuses"
  expect_contains "$OUT" "sudo ufw allow from 192.168.1.0/24" "printed the commands"
fi

finish install_test
