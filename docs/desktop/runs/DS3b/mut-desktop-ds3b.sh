#!/usr/bin/env bash
# PM gate + mutation checks for desktop DS3 + DS3b + DS3c (uncommitted package).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd "$ROOT"
bash docs/desktop/gates.sh docs/desktop/runs/DS3b/pm-gate > /dev/null 2>&1
cat docs/desktop/runs/DS3b/pm-gate/gate-summary.txt
cd desktop
export CARGO_TARGET_DIR="$ROOT/desktop/target"
LOG=../docs/desktop/runs/DS3b/pm-mutants.log
: > "$LOG"
run() { echo "=== $1" >> "$LOG"; shift; bash "$M" "$@" >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "M1 outside-service rule off" core/src/snapshot.rs '142s#if !status.service_active() {#if !status.service_active() \&\& false /*M1*/ {#' '/*M1*/' cargo test -p aristotle-core
run "M2 future stamp always fresh" core/src/diff.rs '67s#let not_too_new = stamp <= now + EXPECTED_STOP_WINDOW;#let not_too_new = stamp <= now + EXPECTED_STOP_WINDOW || true /*M2*/;#' '/*M2*/' cargo test -p aristotle-core
run "M3 running-server version ignored" core/src/snapshot.rs '195s#let status_check = self.check_running_version(\&status);#let status_check = { let _ = self.check_running_version(\&status); self.version.clone() } /*M3*/;#' '/*M3*/' cargo test -p aristotle-core
run "M4 other service words show Stop" tray/src/menu.rs '73s#Service::Other | Service::NoStatus => None,#Service::NoStatus => None, Service::Other => Some(Entry::Stop) /*M4*/,#' '/*M4*/' cargo test -p aristotle-tray
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED|^test .* FAILED' "$LOG" | head -40
