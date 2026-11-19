#!/usr/bin/env bash
# PM gate + mutants for the DFS sweep (sequential).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DFS
cd "$ROOT"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate" > "$OUT/pm-gate.log" 2>&1; echo "gate exit=$?"
cat "$OUT/pm-gate/gate-summary.txt"
LOG=$OUT/pm-mutants.log; : > "$LOG"
export CARGO_TARGET_DIR=$ROOT/desktop/target
run() { local name="$1" dir="$2"; shift 2; echo "=== $name" >> "$LOG"; (cd "$dir" && timeout 600 bash "$M" "$@") >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "F1 last-good drops granted ids" "$ROOT/desktop" core/src/snapshot.rs 's#\.or_else(|| prev.terminal_granted.clone());#.or_else(|| None /*F1*/);#' '/*F1*/' cargo test -p aristotle-core
run "F2 second Start not refused" "$ROOT/desktop" tray/src/starting.rs 's#(!was_busy).then(|| StartToken(Arc::clone(\&self.0)))#{ let _ = was_busy; Some(StartToken(Arc::clone(\&self.0))) /*F2*/ }#' '/*F2*/' cargo test -p aristotle-tray
grep -rn '/\*F[0-9]\*/' desktop/core/src desktop/tray/src && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED' "$LOG"
