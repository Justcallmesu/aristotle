#!/usr/bin/env bash
# Desktop gates. Usage: gates.sh <run-dir>. Never modifies source files.
# Writes <run-dir>/gate-summary.txt (PASS/FAIL lines) and one gate-*.log per step.
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: gates.sh <run-dir>" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUN_DIR="$(mkdir -p "$1" && cd "$1" && pwd)"
SUMMARY="$RUN_DIR/gate-summary.txt"
: > "$SUMMARY"
FAILED=0
export CARGO_TARGET_DIR="$ROOT/desktop/target"
# Floor for the desktop test count (0 tests = FAIL). Raised by each ticket that adds tests.
MIN_TESTS=94

record() { # status step code
  echo "$1 $2 ($3)" >> "$SUMMARY"
  [[ "$1" == FAIL ]] && FAILED=1
  return 0
}

run_step() { # step log command...
  local step="$1" log="$2"; shift 2
  local code=0
  "$@" > "$log" 2>&1 || code=$?
  if [[ $code -eq 0 ]]; then record PASS "$step" 0; else record FAIL "$step" "$code"; fi
}

# The tray must stay free of GUI toolkits (spec P16, D18): libc-only like the DS2b spike.
tray_ldd() {
  local bin="$CARGO_TARGET_DIR/release/aristotle-tray" out
  out="$(ldd "$bin")" || return $?
  echo "$out"
  if echo "$out" | grep -Eiq 'gtk|webkit'; then
    echo "FORBIDDEN: aristotle-tray links gtk or webkit" >&2
    return 1
  fi
  echo "ldd: no gtk/webkit"
}

if [[ ! -f "$ROOT/desktop/Cargo.toml" ]]; then
  record FAIL desktop-missing 1
else
  cd "$ROOT/desktop"
  run_step desktop-fmt "$RUN_DIR/gate-desktop-fmt.log" cargo fmt --check
  run_step desktop-clippy "$RUN_DIR/gate-desktop-clippy.log" \
    cargo clippy --workspace --all-targets -- -D warnings
  run_step desktop-test "$RUN_DIR/gate-desktop-test.log" cargo test --workspace
  tests=$(awk '/^test result: ok/ {n += $4} END {print n + 0}' "$RUN_DIR/gate-desktop-test.log")
  fails=$(grep -E '^test .* \.\.\. FAILED$' -c "$RUN_DIR/gate-desktop-test.log" || true)
  echo "rust-tests passed=$tests failed=$fails" >> "$SUMMARY"
  # A run that executed (almost) nothing must not pass: each ticket raises MIN_TESTS.
  if [[ "$tests" -ge "$MIN_TESTS" ]]; then record PASS desktop-test-count "$tests"; else record FAIL desktop-test-count "$tests<$MIN_TESTS"; fi
  run_step desktop-tray-build "$RUN_DIR/gate-desktop-tray-build.log" \
    cargo build --release -p aristotle-tray
  run_step desktop-tray-ldd "$RUN_DIR/gate-desktop-tray-ldd.log" tray_ldd
fi

cat "$SUMMARY"
exit "$FAILED"
