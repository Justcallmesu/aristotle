#!/usr/bin/env bash
# PM gates + mutants for DS7b (sequential).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DS7b
cd "$ROOT"
timeout 3000 bash docs/v1-tickets/gates.sh "$OUT/pm-gate-full" > "$OUT/pm-gate-full.log" 2>&1; echo "full gate exit=$?"
cat "$OUT/pm-gate-full/gate-summary.txt"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate-desktop" > "$OUT/pm-gate-desktop.log" 2>&1; echo "desktop gate exit=$?"
cat "$OUT/pm-gate-desktop/gate-summary.txt"
LOG=$OUT/pm-mutants.log; : > "$LOG"
export CARGO_TARGET_DIR=$ROOT/desktop/target
run() { local name="$1" dir="$2"; shift 2; echo "=== $name" >> "$LOG"; (cd "$dir" && timeout 900 bash "$M" "$@") >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "U1 uninstall stops desktop programs from any dir" "$ROOT/server" internal/service/desktop.go 's#if !isDesktopProgram(name) || filepath.Dir(exe) != dir {#if !isDesktopProgram(name) /* U1 */ {#' '/* U1 */' go test ./internal/service/
run "T1 second tray instance not detected" "$ROOT/desktop" tray/src/instance.rs 's#if unsafe { flock(file.as_raw_fd(), LOCK_EX | LOCK_NB) } == 0 {#if { let _ = unsafe { flock(file.as_raw_fd(), LOCK_EX | LOCK_NB) }; true } /* T1 */ {#' '/* T1 */' cargo test -p aristotle-tray
grep -rn '/\* [UT]1 \*/' server/internal desktop/tray/src && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED' "$LOG"
