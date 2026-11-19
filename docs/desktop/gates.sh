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
# Floors for the Rust and Vitest test counts (0 tests = FAIL). Set to the real total; each ticket that adds
# tests raises them, so a deleted test cannot pass silently.
MIN_TESTS=211
MIN_UI_TESTS=301
# A step that runs past this many seconds is killed and recorded as FAIL (exit 124), never left hanging.
STEP_TIMEOUT=900

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

# The tray must stay libc-only (spec P16, D18): every linked library must be on this allowlist (DF2).
TRAY_LDD_ALLOW='^(linux-vdso\.so\.1|libc\.so\.6|libm\.so\.6|libgcc_s\.so\.1|ld-linux[^ /]*\.so\.[0-9]+)$'
tray_ldd() {
  local bin="$CARGO_TARGET_DIR/release/aristotle-tray" out line name bad=0
  out="$(ldd "$bin")" || return $?
  echo "$out"
  while read -r line; do
    [[ -z "$line" ]] && continue
    name="$(basename "${line%% *}")"
    if [[ ! "$name" =~ $TRAY_LDD_ALLOW ]]; then
      echo "NOT ALLOWED: aristotle-tray links $name" >&2
      bad=1
    fi
  done <<< "$out"
  [[ $bad -eq 0 ]] || return 1
  echo "ldd: only linux-vdso, libc, libm, libgcc_s, ld-linux"
}

# Test binaries that must each run at least one test (DF1): a binary that silently lost its tests
# keeps the total above MIN_TESTS, so the total alone cannot catch it. Format: <label>|<binary>.
REQUIRED_TEST_BINARIES=(
  "tests/diff.rs|diff" "tests/fixtures.rs|fixtures" "tests/poller_stamp.rs|poller_stamp"
  "tests/runner.rs|runner" "tests/state.rs|state" "tests/strings.rs|strings" "tests/instance.rs|instance"
  "tests/capabilities.rs|capabilities" "unittests src/lib.rs|aristotle_desktop"
  "unittests src/main.rs|aristotle_tray"
)
check_test_binaries() {
  local log="$1" entry label bin n bad=0
  for entry in "${REQUIRED_TEST_BINARIES[@]}"; do
    label="${entry%%|*}"; bin="${entry##*|}"
    n=$(awk -v label="$label" -v bin="$bin" '
      /^ *Running / { cur = ($0 ~ "Running " label " " && $0 ~ ("/" bin "-[0-9a-f]+\\)")) }
      cur && /^running [0-9]+ test/ { total += $2; cur = 0 }
      END { print total + 0 }' "$log")
    echo "test binary $bin ($label): $n tests"
    [[ "$n" -ge 1 ]] || { echo "FAIL: $bin ran no tests" >&2; bad=1; }
  done
  return $bad
}

# UI gates run from desktop/ui; each is one step so a failure names itself.
ui_step() { (cd "$ROOT/desktop/ui" && "$@"); }
# A bounded step: `timeout` exits 124, which `run_step` records as FAIL.
bounded() { timeout "$STEP_TIMEOUT" "$@"; }
# `tauri build` finds the app by searching for tauri.conf.json below the current directory.
app_build() { (cd "$ROOT/desktop" && ui/node_modules/.bin/tauri build --no-bundle); }
app_binary() { [[ -x "$CARGO_TARGET_DIR/release/aristotle-desktop" ]] && ls -l "$CARGO_TARGET_DIR/release/aristotle-desktop"; }

if [[ ! -f "$ROOT/desktop/Cargo.toml" ]]; then
  record FAIL desktop-missing 1
else
  cd "$ROOT/desktop"
  # The Tauri app embeds ui/dist, so the frontend steps (and its build) come before any cargo step.
  run_step ui-ci "$RUN_DIR/gate-ui-ci.log" ui_step npm ci
  run_step ui-lint "$RUN_DIR/gate-ui-lint.log" ui_step npm run lint
  run_step ui-typecheck "$RUN_DIR/gate-ui-typecheck.log" ui_step npm run typecheck
  run_step ui-test "$RUN_DIR/gate-ui-test.log" ui_step bounded npm test
  ui_tests=$(sed -n 's/^ *Tests  *\([0-9]*\) passed.*/\1/p' "$RUN_DIR/gate-ui-test.log" | tail -1)
  echo "ui-tests passed=${ui_tests:-0}" >> "$SUMMARY"
  if [[ "${ui_tests:-0}" -ge "$MIN_UI_TESTS" ]]; then record PASS ui-test-count "${ui_tests:-0}"; else record FAIL ui-test-count "${ui_tests:-0}<$MIN_UI_TESTS"; fi
  run_step ui-build "$RUN_DIR/gate-ui-build.log" ui_step npm run build
  run_step desktop-fmt "$RUN_DIR/gate-desktop-fmt.log" cargo fmt --check
  run_step desktop-clippy "$RUN_DIR/gate-desktop-clippy.log" \
    cargo clippy --workspace --all-targets -- -D warnings
  run_step desktop-test "$RUN_DIR/gate-desktop-test.log" bounded cargo test --workspace
  tests=$(awk '/^test result: ok/ {n += $4} END {print n + 0}' "$RUN_DIR/gate-desktop-test.log")
  fails=$(grep -E '^test .* \.\.\. FAILED$' -c "$RUN_DIR/gate-desktop-test.log" || true)
  echo "rust-tests passed=$tests failed=$fails" >> "$SUMMARY"
  # A run that executed (almost) nothing must not pass: each ticket raises MIN_TESTS.
  if [[ "$tests" -ge "$MIN_TESTS" ]]; then record PASS desktop-test-count "$tests"; else record FAIL desktop-test-count "$tests<$MIN_TESTS"; fi
  run_step desktop-test-binaries "$RUN_DIR/gate-desktop-test-binaries.log" \
    check_test_binaries "$RUN_DIR/gate-desktop-test.log"
  run_step desktop-tray-build "$RUN_DIR/gate-desktop-tray-build.log" \
    cargo build --release -p aristotle-tray
  run_step desktop-tray-ldd "$RUN_DIR/gate-desktop-tray-ldd.log" tray_ldd
  run_step desktop-app-build "$RUN_DIR/gate-desktop-app-build.log" app_build
  run_step desktop-app-binary "$RUN_DIR/gate-desktop-app-binary.log" app_binary
fi

cat "$SUMMARY"
exit "$FAILED"
