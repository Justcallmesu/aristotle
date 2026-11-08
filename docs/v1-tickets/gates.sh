#!/usr/bin/env bash
# Project gates. Usage: [GATE_ONLY=server|android] gates.sh <run-dir>. Never modifies source files.
# GATE_ONLY skips the other side (recorded as SKIP) for tickets that do not touch it.
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
ONLY="${GATE_ONLY:-}"
if [[ -n "$ONLY" && "$ONLY" != server && "$ONLY" != android ]]; then
  echo "GATE_ONLY must be server or android" >&2
  exit 2
fi

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

server_fmt() {
  local out
  out="$(gofmt -l .)" || return $?
  if [[ -n "$out" ]]; then echo "gofmt: unformatted files:"; echo "$out"; return 1; fi
  echo "gofmt: clean"
}

if [[ "$ONLY" == android ]]; then
  record SKIP server 0
elif [[ -d "$ROOT/server" ]]; then
  cd "$ROOT/server"
  run_step server-gofmt "$RUN_DIR/gate-server-gofmt.log" server_fmt
  run_step server-vet "$RUN_DIR/gate-server-vet.log" go vet ./...
  run_step server-test "$RUN_DIR/gate-server-test.log" go test -count=1 -v ./...
  tests=$(grep -c '^\s*--- PASS' "$RUN_DIR/gate-server-test.log" || true)
  fails=$(grep -c '^\s*--- FAIL' "$RUN_DIR/gate-server-test.log" || true)
  if grep -Eq '^FAIL\s.*\[(build|setup) failed\]' "$RUN_DIR/gate-server-test.log"; then
    echo "go-tests: unknown (build failed)" >> "$SUMMARY"
  else
    echo "go-tests passed=$tests failed=$fails" >> "$SUMMARY"
  fi
else
  record SKIP server 0
fi

if [[ "$ONLY" == server ]]; then
  record SKIP android 0
elif [[ -f "$ROOT/android/gradlew" ]]; then
  cd "$ROOT/android"
  # Not executable -> the run fails (FAIL), never a silent skip.
  run_step android "$RUN_DIR/gate-android.log" ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
  # D45: the licence notices must match the dependency set (a bump leaves them stale otherwise).
  cd "$ROOT"
  run_step licences "$RUN_DIR/gate-licences.log" bash scripts/licences.sh --check
else
  record SKIP android 0
fi

cat "$SUMMARY"
exit "$FAILED"
