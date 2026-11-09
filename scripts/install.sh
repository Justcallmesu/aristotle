#!/usr/bin/env bash
# Install or update aristotle-server from this release bundle (run it from anywhere; it works in its own directory).
# Usage: ./install.sh [--pair | --no-pair]
#   0. refuses inside the app's remote terminal (the restart would end that session mid-update)
#   1. checks every file against SHA256SUMS (refuses before touching anything when one does not match, when the
#      bundle's APK is not listed there, or when its file name has no x.y.z version)
#   2. copies the bundle's APK + a generated aristotle.json to $XDG_DATA_HOME/aristotle/app (the server serves them to
#      the phone), then aristotle-server to ~/.local/bin (atomic; the previous binary is kept as aristotle-server.prev).
#      The app pair goes first: if its copy fails nothing else changed, and a new server never runs without its APK pair.
#   3. aristotle-server install --force --start: writes/updates the systemd user unit, restarts a running service or starts it
#   4. waits until `aristotle-server status` exits 0 (at most ARISTOTLE_INSTALL_TIMEOUT seconds, default 30)
#   5. prints the version, where the APK is, and offers the pairing QR (--pair, or a y/N question when interactive)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="$HOME/.local/bin"
TARGET="$BIN_DIR/aristotle-server"
PREV="$TARGET.prev"
UNIT=aristotle-server
TIMEOUT="${ARISTOTLE_INSTALL_TIMEOUT:-30}"

CGROUP_FILE="${ARISTOTLE_CGROUP_FILE:-/proc/self/cgroup}" # overridable for the tests only

usage() { echo "usage: install.sh [--pair | --no-pair]" >&2; exit 2; }

PAIR=ask
case "${1:-}" in
  "") ;;
  --pair) PAIR=yes ;;
  --no-pair) PAIR=no ;;
  *) usage ;;
esac
[[ $# -le 1 ]] || usage
[[ "$TIMEOUT" =~ ^[0-9]+$ ]] || { echo "ARISTOTLE_INSTALL_TIMEOUT must be a number of seconds" >&2; exit 2; }

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

refuse_in_app_terminal
verify_bundle
install_app_update
install_binary
"$TARGET" install --force --start || { on_failure "aristotle-server install failed (see above)"; exit 1; }
wait_for_status
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
