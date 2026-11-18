#!/usr/bin/env bash
# PM gate + mutants for DS8 (sequential).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DS8
cd "$ROOT"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate" > "$OUT/pm-gate.log" 2>&1; echo "gate exit=$?"
cat "$OUT/pm-gate/gate-summary.txt"
LOG=$OUT/pm-mutants.log; : > "$LOG"
export CARGO_TARGET_DIR=$ROOT/desktop/target
run() { local name="$1" dir="$2"; shift 2; echo "=== $name" >> "$LOG"; (cd "$dir" && timeout 600 bash "$M" "$@") >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "D1 switch off->on not notified" "$ROOT/desktop" core/src/diff.rs 's#if !was_on \&\& is_on {#if false /*D1*/ \&\& is_on {#' '/*D1*/' cargo test -p aristotle-core
run "D2 every granted id notified (known set ignored)" "$ROOT/desktop" core/src/diff.rs 's#.filter(|id| !known.contains(id.as_str()))#.filter(|_id| true /*D2*/)#' '/*D2*/' cargo test -p aristotle-core
run "D3 failed read not silent (None treated as off)" "$ROOT/desktop" core/src/diff.rs 's#let (Some(was_on), Some(is_on)) = (prev.terminal_enabled, next.terminal_enabled) else {#let (Some(was_on), Some(is_on)) = (Some(prev.terminal_enabled.unwrap_or(false)) /*D3*/, next.terminal_enabled) else {#' '/*D3*/' cargo test -p aristotle-core
run "U1 Start allowed while activating" "$ROOT/desktop/ui" src/state/serviceButtons.ts 's#return status.service === "inactive" || status.service === "failed";#return status.service !== "active" /*U1*/;#' '/*U1*/' npx vitest run
grep -rn '/\*[DU][0-9]\*/' desktop/core/src desktop/ui/src && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED' "$LOG"
