#!/usr/bin/env bash
# Install or update aristotle-server from this release bundle (run it from anywhere; it works in its own directory).
# Usage: ./install.sh [--pair | --no-pair]
#   1. checks every file against SHA256SUMS (refuses before touching anything when one does not match)
#   2. copies aristotle-server to ~/.local/bin (atomic; the previous binary is kept as aristotle-server.prev)
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

verify_bundle() {
  cd "$HERE"
  [[ -f SHA256SUMS ]] || { echo "error: SHA256SUMS missing in $HERE; not installing" >&2; exit 1; }
  grep -Eq '^[0-9a-f]{64}  aristotle-server$' SHA256SUMS || { echo "error: SHA256SUMS does not list aristotle-server; not installing" >&2; exit 1; }
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

# wait_for_status polls `status` (it exits 1 until the control socket is up, right after a start or restart).
wait_for_status() {
  local waited=0 out=""
  while true; do
    if out="$("$TARGET" status 2>&1)"; then
      echo "$out"
      return 0
    fi
    if (( waited >= TIMEOUT )); then break; fi
    sleep 1
    waited=$((waited + 1))
  done
  echo "$out"
  echo "error: the server did not come up within ${TIMEOUT}s. Last service log lines:" >&2
  journalctl --user -u "$UNIT" -n 30 --no-pager >&2 || echo "(journalctl failed; try: aristotle-server logs)" >&2
  if [[ -f "$PREV" ]]; then
    echo "To go back to the previous binary: mv -f \"$PREV\" \"$TARGET\" && \"$TARGET\" install --force --start" >&2
  fi
  return 1
}

tell_about_app() {
  local apk
  apk="$(ls "$HERE"/aristotle-*.apk 2>/dev/null | head -n 1 || true)"
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

verify_bundle
install_binary
"$TARGET" install --force --start || { echo "error: aristotle-server install failed (see above)" >&2; exit 1; }
wait_for_status
echo
echo "Running: $("$TARGET" version)"
if [[ -f "$PREV" ]]; then
  echo "Rollback, if needed: mv -f \"$PREV\" \"$TARGET\" && \"$TARGET\" install --force --start"
fi
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "Note: $BIN_DIR is not on your PATH; run $TARGET or add it to PATH." ;;
esac
tell_about_app
echo
offer_pairing
