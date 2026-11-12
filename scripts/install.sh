#!/usr/bin/env bash
# Install or update aristotle-server from this release bundle (run it from anywhere; it works in its own directory).
# Usage: ./install.sh [--pair | --no-pair] [--no-gateway] [--no-firewall]
#   0. refuses inside the app's remote terminal (the restart would end that session mid-update)
#      Without --no-gateway it also refuses, before touching anything, when the `hermes` CLI is not installed.
#   1. checks every file against SHA256SUMS (refuses before touching anything when one does not match, when the
#      bundle's APK is not listed there, or when its file name has no x.y.z version)
#   2. copies the bundle's APK + a generated aristotle.json to $XDG_DATA_HOME/aristotle/app (the server serves them to
#      the phone), then aristotle-server to ~/.local/bin (atomic; the previous binary is kept as aristotle-server.prev).
#      The app pair goes first: if its copy fails nothing else changed, and a new server never runs without its APK pair.
#   3. aristotle-server install --force --start: writes/updates the systemd user unit, restarts a running service or starts it
#   4. waits until `aristotle-server status` exits 0 (at most ARISTOTLE_INSTALL_TIMEOUT seconds, default 30)
#   5. reads `aristotle-server status --porcelain`: when Hermes is not reachable it installs + starts the Hermes gateway
#      as a user service (unless --no-gateway) and waits until the server reports Hermes reachable. That wait has its own
#      budget, ARISTOTLE_GATEWAY_TIMEOUT seconds (default 120): a fresh gateway needs 25-45 s to report ready
#   6. turns on systemd linger when it is off (the services then survive logout)
#   7. opens the firewall (ufw or firewalld, via sudo) for the LAN subnet(s) the server serves: <port>/tcp and 5353/udp
#      from that subnet only; --no-firewall only prints the commands. A firewall problem never fails the install.
#   8. prints the version, where the APK is, and offers the pairing QR (--pair, or a y/N question when interactive)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$HOME/.local/bin"
TARGET="$BIN_DIR/aristotle-server"
PREV="$TARGET.prev"
UNIT=aristotle-server
TIMEOUT="${ARISTOTLE_INSTALL_TIMEOUT:-30}"
GATEWAY_TIMEOUT="${ARISTOTLE_GATEWAY_TIMEOUT:-120}" # Hermes publishes ready last, often 25-45 s after the gateway starts

CGROUP_FILE="${ARISTOTLE_CGROUP_FILE:-/proc/self/cgroup}" # overridable for the tests only
SBIN_DIRS="${ARISTOTLE_SBIN_DIRS-/usr/sbin:/sbin}"        # extra dirs has_tool searches; overridable for the tests only

# INTERACTIVE is decided once, here, before anything is redirected: a person can answer prompts (sudo asks for a password).
# Never re-test the tty later: inside $(...) or a here-string loop stdout/stdin are pipes and the test would say "no".
if [[ -t 0 && -t 1 ]]; then INTERACTIVE=yes; else INTERACTIVE=no; fi

usage() { echo "usage: install.sh [--pair | --no-pair] [--no-gateway] [--no-firewall]" >&2; exit 2; }

PAIR=ask
GATEWAY=yes
FIREWALL_STEP=yes
# parse_flags reads the options in any order; --pair and --no-pair exclude each other.
parse_flags() {
  local arg
  for arg in "$@"; do
    case "$arg" in
      --pair) [[ "$PAIR" != no ]] || usage; PAIR=yes ;;
      --no-pair) [[ "$PAIR" != yes ]] || usage; PAIR=no ;;
      --no-gateway) GATEWAY=no ;;
      --no-firewall) FIREWALL_STEP=no ;;
      *) usage ;;
    esac
  done
}
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || { echo "ARISTOTLE_INSTALL_TIMEOUT must be a number of seconds" >&2; exit 2; }
[[ "$GATEWAY_TIMEOUT" =~ ^[0-9]+$ ]] || { echo "ARISTOTLE_GATEWAY_TIMEOUT must be a number of seconds" >&2; exit 2; }

# refuse_in_app_terminal: a shell opened from the phone has ARISTOTLE_TERMINAL set and lives in the service's cgroup;
# the restart below would kill it between the binary swap and the status check.
refuse_in_app_terminal() {
  if [[ -n "${ARISTOTLE_TERMINAL:-}" ]] || grep -q "$UNIT\.service" "$CGROUP_FILE" 2>/dev/null; then
    echo "error: run this in a terminal on the PC, not from the phone: the restart would end this session mid-update" >&2
    exit 1
  fi
}

# bundle_apk prints the bundle's APK path (the first aristotle-*.apk), or nothing when there is none.
bundle_apk() { ls "$HERE"/aristotle-*.apk 2>/dev/null | head -n 1 || true; }

# apk_version prints x.y.z from aristotle-x.y.z.apk; fails when the name has no such version.
apk_version() {
  local version
  version="$(basename "$1" .apk)"
  version="${version#aristotle-}"
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  echo "$version"
}

# verify_apk_listed: an APK in the bundle must have a readable version and its own SHA256SUMS line, so an unlisted
# (unverified) file is never copied for the phone. Runs before anything is touched.
verify_apk_listed() {
  local apk name
  apk="$(bundle_apk)"
  [[ -n "$apk" ]] || return 0
  name="$(basename "$apk")"
  apk_version "$apk" > /dev/null || { echo "error: cannot read a version x.y.z from $name; not installing" >&2; exit 1; }
  grep -Eq "^[0-9a-f]{64}  ${name//./\\.}\$" SHA256SUMS || { echo "error: SHA256SUMS does not list $name; not installing" >&2; exit 1; }
}

verify_bundle() {
  cd "$HERE"
  [[ -f SHA256SUMS ]] || { echo "error: SHA256SUMS missing in $HERE; not installing" >&2; exit 1; }
  grep -Eq '^[0-9a-f]{64}  aristotle-server$' SHA256SUMS || { echo "error: SHA256SUMS does not list aristotle-server; not installing" >&2; exit 1; }
  verify_apk_listed
  if ! sha256sum --check --strict --quiet SHA256SUMS; then
    echo "error: checksum mismatch in $HERE (damaged or altered download); nothing was installed" >&2
    exit 1
  fi
  echo "Checksums OK."
}

# install_binary copies the new binary next to the old one, then renames it into place (atomic on one filesystem).
NEW_BIN=""
install_binary() {
  mkdir -p "$BIN_DIR"
  NEW_BIN="$(mktemp "$BIN_DIR/.aristotle-server.XXXXXX")"
  trap 'rm -f "$NEW_BIN" "$PREV.tmp"' EXIT
  cp "$HERE/aristotle-server" "$NEW_BIN"
  chmod 0755 "$NEW_BIN"
  # An identical binary (a rerun) does not overwrite .prev, so the real previous version stays there.
  if [[ -f "$TARGET" ]] && ! cmp -s "$TARGET" "$NEW_BIN"; then
    cp -p "$TARGET" "$PREV.tmp"
    mv -f "$PREV.tmp" "$PREV"
    echo "Kept the previous binary as $PREV"
  fi
  mv -f "$NEW_BIN" "$TARGET"
  trap - EXIT
  echo "Installed $TARGET"
}

# app_version_code turns x.y.z into major*10000+minor*100+patch, the same formula as android/app/build.gradle.kts.
app_version_code() {
  local major minor patch
  IFS=. read -r major minor patch <<< "$1"
  echo $((10#$major * 10000 + 10#$minor * 100 + 10#$patch))
}

# install_app_update puts the APK where the server serves it from (spec P17): the APK first, aristotle.json last,
# each as temp file + rename, so the server never sees a json that names a half-copied APK. Runs after the checksum
# check, before the server binary is replaced. A bundle without an APK leaves the previous one in place.
install_app_update() {
  local apk version size sha dir base="${XDG_DATA_HOME:-}"
  apk="$(bundle_apk)"
  [[ -n "$apk" ]] || { echo "No APK in the bundle: the phone-update copy was left as it is."; return 0; }
  version="$(apk_version "$apk")" # checked by verify_bundle
  [[ "$base" == /* ]] || base="$HOME/.local/share"
  dir="$base/aristotle/app"
  mkdir -p "$dir"
  size="$(stat -c %s "$apk")"
  sha="$(sha256sum "$apk" | cut -d' ' -f1)"
  trap 'rm -f "$dir"/.aristotle.apk.* "$dir"/.aristotle.json.*' EXIT
  local tmp_apk tmp_json
  tmp_apk="$(mktemp "$dir/.aristotle.apk.XXXXXX")"
  cp "$apk" "$tmp_apk"
  chmod 0644 "$tmp_apk"
  mv -f "$tmp_apk" "$dir/aristotle.apk"
  tmp_json="$(mktemp "$dir/.aristotle.json.XXXXXX")"
  printf '{"version":"%s","version_code":%s,"sha256":"%s","size":%s}\n' \
    "$version" "$(app_version_code "$version")" "$sha" "$size" > "$tmp_json"
  chmod 0644 "$tmp_json"
  mv -f "$tmp_json" "$dir/aristotle.json"
  trap - EXIT
  echo "Phone update $version copied to $dir"
}

# wait_for_status polls `status` (it exits 1 until the control socket is up, right after a start or restart).
# The deadline is wall time (SECONDS), so a hanging `status` cannot stretch the wait.
wait_for_status() {
  local deadline=$((SECONDS + TIMEOUT)) out=""
  while true; do
    if out="$("$TARGET" status 2>&1)"; then
      echo "$out"
      return 0
    fi
    if (( SECONDS >= deadline )); then break; fi
    sleep 1
  done
  echo "$out"
  on_failure "the server did not come up within ${TIMEOUT}s"
  return 1
}

rollback_command() { echo "mv -f \"$PREV\" \"$TARGET\" && \"$TARGET\" install --force --start"; }

# on_failure prints the reason, the service journal tail and the rollback command (when a .prev exists). Both failure paths use it.
on_failure() {
  echo "error: $1. Last service log lines:" >&2
  journalctl --user -u "$UNIT" -n 30 --no-pager >&2 || echo "(journalctl failed; try: aristotle-server logs)" >&2
  if [[ -f "$PREV" ]]; then
    echo "To go back to the previous binary: $(rollback_command)" >&2
  fi
}

tell_about_app() {
  local apk
  apk="$(bundle_apk)"
  echo
  if [[ -n "$apk" ]]; then
    echo "The phone app: $apk"
    echo "  Copy it to the phone and open it to install (allow installs from that app when Android asks)."
  fi
  echo "  Switching from the debug app for the first time: uninstall the debug app first (it is signed with another key,"
  echo "  so Android refuses to update it), then install this APK and pair again."
}

offer_pairing() {
  local answer=""
  case "$PAIR" in
    yes) ;;
    no) echo "Pair a phone later with: aristotle-server pair"; return 0 ;;
    ask)
      if [[ -t 0 && -t 1 ]]; then
        read -r -p "Show the pairing QR code now? [y/N] " answer || true
      fi
      if [[ ! "$answer" =~ ^[Yy]([Ee][Ss])?$ ]]; then
        echo "Pair a phone later with: aristotle-server pair"
        return 0
      fi ;;
  esac
  "$TARGET" pair
}

# warn prints a non-fatal problem on stderr; the install carries on.
warn() { echo "warning: $*" >&2; }

# priv runs a command as root: plain sudo when INTERACTIVE (sudo itself reads the password from /dev/tty, so it prompts
# even inside $(...) or a loop), else `sudo -n` (fails instead of hanging).
priv() { if [[ "$INTERACTIVE" == yes ]]; then sudo "$@"; else sudo -n "$@"; fi; }

# has_tool is true when a program exists; SBIN_DIRS (/usr/sbin, /sbin) count: ufw lives there, often missing from a user's PATH.
has_tool() { PATH="$PATH${SBIN_DIRS:+:$SBIN_DIRS}" command -v "$1" > /dev/null 2>&1; }

# check_hermes_cli finds the hermes CLI (PATH, then ~/.local/bin) into HERMES_BIN, or refuses before anything is touched.
HERMES_BIN=""
check_hermes_cli() {
  [[ "$GATEWAY" == yes ]] || return 0
  if HERMES_BIN="$(command -v hermes 2> /dev/null)" && [[ -n "$HERMES_BIN" ]]; then return 0; fi
  if [[ -x "$HOME/.local/bin/hermes" ]]; then HERMES_BIN="$HOME/.local/bin/hermes"; return 0; fi
  echo "error: Hermes Agent is not installed (no 'hermes' on PATH or in ~/.local/bin): install it first, then run this again (or pass --no-gateway)" >&2
  exit 1
}

# read_porcelain stores `aristotle-server status --porcelain` in PORCELAIN (empty when the binary cannot print it).
# Every later step reads it through porcelain_value / porcelain_lan, so the format (spec P22) is parsed in one place.
PORCELAIN=""
read_porcelain() { PORCELAIN="$("$TARGET" status --porcelain 2> /dev/null)" || true; }

# porcelain_value prints the second field of the first PORCELAIN line that starts with the given key.
porcelain_value() { awk -v key="$1" '$1 == key { print $2; exit }' <<< "$PORCELAIN"; }

# porcelain_lan prints the subnet (4th field) of each `lan <iface> <ip> <subnet>` line, once per subnet.
porcelain_lan() { awk '$1 == "lan" && NF >= 4 && !seen[$4]++ { print $4 }' <<< "$PORCELAIN"; }

# wait_for_hermes polls the porcelain `hermes` line until it says reachable (wall-clock deadline of GATEWAY_TIMEOUT, like wait_for_status).
wait_for_hermes() {
  local deadline=$((SECONDS + GATEWAY_TIMEOUT))
  while true; do
    read_porcelain
    [[ "$(porcelain_value hermes)" == reachable ]] && return 0
    if (( SECONDS >= deadline )); then return 1; fi
    sleep 1
  done
}

# require_server_running ends the install like wait_for_status when the PORCELAIN snapshot does not say `server running`
# (the server stopped after it came up, e.g. a crash loop): every other porcelain fact is then missing, not "absent".
require_server_running() {
  [[ "$(porcelain_value server)" == running ]] && return 0
  on_failure "cannot read the server status: aristotle-server stopped answering after it was started"
  exit 1
}

# gateway_failed ends the install after the gateway step failed: the reason (the server's own Hermes line), the gateway
# service journal tail, and what state the PC is left in.
gateway_failed() {
  local version
  version="$(porcelain_value version)"
  echo "error: $1." >&2
  "$TARGET" status 2> /dev/null | grep '^Hermes:' >&2 || true
  echo "Last Hermes gateway log lines:" >&2
  journalctl --user -u hermes-gateway -n 30 --no-pager >&2 || echo "(journalctl failed; try: hermes gateway status)" >&2
  echo "aristotle-server ${version:-?} is installed and running; linger, firewall and pairing were NOT done; fix Hermes, then run the installer again." >&2
  exit 1
}

# ensure_gateway makes Hermes reachable for the server (Q137, P24). `hermes gateway install` (stdin from /dev/null, so it
# never prompts) writes + enables the user unit and starts it; it also starts an already-installed one. It runs only for
# `not-running`: for `error` a gateway answered but is not usable (still starting, wrong protocol...), so we only wait.
# The server dials the root Hermes home (~/.hermes), so the gateway must live there too: HERMES_HOME is unset and
# `-p default` is given because hermes_cli/main.py reads ~/.hermes/active_profile when no -p is given (a sticky
# `hermes profile use x` would install hermes-gateway-x for ~/.hermes/profiles/x); -p default = the root home.
ensure_gateway() {
  local state
  read_porcelain
  require_server_running
  state="$(porcelain_value hermes)"
  case "$state" in
    reachable) echo "Hermes: reachable"; return 0 ;;
    "") warn "cannot check Hermes: this aristotle-server does not report it"; return 0 ;;
  esac
  if [[ "$GATEWAY" == no ]]; then
    warn "Hermes is not reachable ($state); start its gateway with: hermes gateway install"
    return 0
  fi
  if [[ "$state" == error ]]; then
    echo "Hermes answered but is not usable yet; waiting for it"
  else
    echo "Hermes gateway is not running; installing it as a user service"
    env -u HERMES_HOME "$HERMES_BIN" -p default gateway install < /dev/null || gateway_failed "hermes gateway install failed"
  fi
  echo "Waiting for Hermes to become ready (up to ${GATEWAY_TIMEOUT}s; a fresh gateway takes about half a minute)..."
  wait_for_hermes || gateway_failed "the server still cannot reach Hermes after ${GATEWAY_TIMEOUT}s"
  echo "Hermes: reachable"
}

# ensure_linger turns on systemd linger so the user services keep running after logout (P25); failure only warns.
ensure_linger() {
  local user="${USER:-$(id -un)}"
  if [[ "$(loginctl show-user "$user" -p Linger 2> /dev/null)" == Linger=yes ]]; then return 0; fi
  if loginctl enable-linger "$user"; then
    echo "Linger enabled for $user (the services keep running after logout)"
  else
    warn "could not enable linger; run: loginctl enable-linger $user"
  fi
}

# firewall_rules prints one "proto port subnet" line per rule: <port>/tcp and 5353/udp for each LAN subnet.
firewall_rules() {
  local port="$1" subnet
  while read -r subnet; do
    [[ -n "$subnet" ]] || continue
    echo "tcp $port $subnet"
    echo "udp 5353 $subnet"
  done < <(porcelain_lan)
}

# rich_rule prints the firewalld rich rule for "proto port subnet" (ipv6 when the subnet has a colon).
rich_rule() {
  local family=ipv4
  [[ "$3" == *:* ]] && family=ipv6
  printf 'rule family="%s" source address="%s" port port="%s" protocol="%s" accept' "$family" "$3" "$2" "$1"
}

# print_firewall_commands shows the exact commands for one firewall kind (ufw | firewalld) so the user can run them.
print_firewall_commands() {
  local kind="$1" proto port subnet
  while read -r proto port subnet <&3; do
    if [[ "$kind" == ufw ]]; then
      echo "  sudo ufw allow from $subnet to any port $port proto $proto comment 'aristotle'"
    else
      echo "  sudo firewall-cmd --permanent --add-rich-rule='$(rich_rule "$proto" "$port" "$subnet")'"
    fi
  done 3<<< "$RULES"
  [[ "$kind" == ufw ]] || echo "  sudo firewall-cmd --reload"
}

# print_all_firewall_commands is the fallback: the commands for both firewalls, for the user to run by hand.
print_all_firewall_commands() {
  echo "If ufw is your firewall, run:"
  print_firewall_commands ufw
  echo "If firewalld is your firewall, run:"
  print_firewall_commands firewalld
  echo "(Another firewall: allow the same TCP/UDP ports from the same subnets there.)"
}

# detect_firewall sets FIREWALL to ufw | firewalld | none (tools exist, none active) | missing (no tool) | unknown (could not ask).
FIREWALL=""
detect_firewall() {
  local out=""
  FIREWALL=none
  if ! has_tool ufw && ! has_tool firewall-cmd; then FIREWALL=missing; return 0; fi
  if has_tool ufw; then
    out="$(priv env LC_ALL=C ufw status 2> /dev/null || true)"
    out="${out%%$'\n'*}"
    case "$out" in
      "Status: active") FIREWALL=ufw; return 0 ;;
      "Status: "*) ;;
      *) FIREWALL=unknown; return 0 ;;
    esac
  fi
  if has_tool firewall-cmd; then
    out="$(LC_ALL=C firewall-cmd --state 2> /dev/null || true)"
    case "$out" in
      running) FIREWALL=firewalld ;;
      "not running") ;;
      *) FIREWALL=unknown ;;
    esac
  fi
}

# ADDED / KEPT list the rules this run created and the ones that already existed.
ADDED=()
KEPT=()
RULE_FAILED=no # a rule or the reload failed: the exact commands are printed afterwards

# add_ufw_rule adds one rule; ufw itself skips an identical existing rule ("Skipping adding existing rule",
# ufw/backend_iptables.py), so a rerun adds no duplicate.
add_ufw_rule() {
  local proto="$1" port="$2" subnet="$3" out
  if out="$(priv env LC_ALL=C ufw allow from "$subnet" to any port "$port" proto "$proto" comment aristotle 2>&1)"; then
    if [[ "$out" == *"existing rule"* ]]; then KEPT+=("$port/$proto from $subnet"); else ADDED+=("$port/$proto from $subnet"); fi
  else
    warn "ufw refused the rule for $port/$proto from $subnet: $out"
    RULE_FAILED=yes
  fi
}

# add_firewalld_rule adds one permanent rich rule unless --query-rich-rule already finds it.
FIREWALLD_CHANGED=no
add_firewalld_rule() {
  local proto="$1" port="$2" subnet="$3" rule
  rule="$(rich_rule "$proto" "$port" "$subnet")"
  if priv env LC_ALL=C firewall-cmd --permanent --query-rich-rule="$rule" > /dev/null 2>&1; then
    KEPT+=("$port/$proto from $subnet")
  elif priv env LC_ALL=C firewall-cmd --permanent --add-rich-rule="$rule" > /dev/null 2>&1; then
    ADDED+=("$port/$proto from $subnet"); FIREWALLD_CHANGED=yes
  else
    warn "firewalld refused the rule for $port/$proto from $subnet"
    RULE_FAILED=yes
  fi
}

# apply_firewall_rules adds every rule to the detected firewall, then reloads firewalld when it changed. The loop reads its
# rules on fd 3 so that stdin stays the terminal. Any failure sets RULE_FAILED.
apply_firewall_rules() {
  local proto port subnet
  while read -r proto port subnet <&3; do
    if [[ "$FIREWALL" == ufw ]]; then add_ufw_rule "$proto" "$port" "$subnet"; else add_firewalld_rule "$proto" "$port" "$subnet"; fi
  done 3<<< "$RULES"
  if [[ "$FIREWALL" == firewalld && "$FIREWALLD_CHANGED" == yes ]]; then
    priv env LC_ALL=C firewall-cmd --reload > /dev/null 2>&1 || { warn "firewall-cmd --reload failed"; RULE_FAILED=yes; }
  fi
}

# report_firewall_rules says which rules were added and which were already there.
report_firewall_rules() {
  local rule
  for rule in "${ADDED[@]+"${ADDED[@]}"}"; do echo "Firewall: added $rule"; done
  for rule in "${KEPT[@]+"${KEPT[@]}"}"; do echo "Firewall: kept $rule (already there)"; done
  echo "Rules from an older network stay in place. On another network, run the installer again."
}

# read_lan_porcelain re-reads the porcelain until the server lists a LAN listener: the control socket answers before the
# listen manager's first scan, so an early answer can have no `lan` line. Waits at most 5s (and never longer than the
# install timeout); ends the install when the server is not running.
read_lan_porcelain() {
  local limit=$(( TIMEOUT < 5 ? TIMEOUT : 5 )) deadline
  deadline=$((SECONDS + limit))
  while true; do
    read_porcelain
    require_server_running
    [[ -z "$(porcelain_value port)" || -n "$(porcelain_lan)" ]] && return 0
    if (( SECONDS >= deadline )); then return 0; fi
    sleep 1
  done
}

# open_firewall lets the phone reach the server over the LAN (Q136, P23): only from the LAN subnet(s) the server serves,
# nothing for Tailscale. sudo is used here only; a failure prints the exact commands. Never fails the install.
RULES=""
open_firewall() {
  local port
  read_lan_porcelain
  port="$(porcelain_value port)"
  if [[ -z "$port" || -z "$(porcelain_lan)" ]]; then
    echo "Firewall: the server did not report a LAN listener, so no rule was added (Tailscale needs none)."
    return 0
  fi
  RULES="$(firewall_rules "$port")"
  if [[ "$FIREWALL_STEP" == no ]]; then
    echo "Firewall step skipped (--no-firewall). To let the phone connect over the LAN, run the commands for your firewall:"
    print_all_firewall_commands
    return 0
  fi
  if [[ "$INTERACTIVE" == yes ]]; then echo "sudo may ask for your password to open the firewall."; fi
  detect_firewall
  case "$FIREWALL" in
    ufw|firewalld)
      apply_firewall_rules
      report_firewall_rules
      if [[ "$RULE_FAILED" == yes ]]; then
        warn "not every firewall rule could be added; run these commands yourself:"
        print_firewall_commands "$FIREWALL"
      fi ;;
    none) echo "Firewall: no active firewall detected; nothing to open." ;;
    *)
      warn "could not check or change the firewall (no tool found, or sudo needs a password that cannot be asked)"
      print_all_firewall_commands ;;
  esac
}

parse_flags "$@"
refuse_in_app_terminal
check_hermes_cli
verify_bundle
install_app_update
install_binary
"$TARGET" install --force --start || { on_failure "aristotle-server install failed (see above)"; exit 1; }
wait_for_status
ensure_gateway
ensure_linger
open_firewall
echo
echo "Running: $("$TARGET" version)"
if [[ -f "$PREV" ]]; then
  echo "Rollback, if needed: $(rollback_command)"
fi
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "Note: $BIN_DIR is not on your PATH; run $TARGET or add it to PATH." ;;
esac
tell_about_app
echo
offer_pairing
