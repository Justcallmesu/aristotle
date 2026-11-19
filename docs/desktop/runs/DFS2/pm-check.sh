#!/usr/bin/env bash
# PM gate + mutants for DFS + DFS2 (sequential).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DFS2
cd "$ROOT"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate" > "$OUT/pm-gate.log" 2>&1; echo "gate exit=$?"
cat "$OUT/pm-gate/gate-summary.txt"
LOG=$OUT/pm-mutants.log; : > "$LOG"
export CARGO_TARGET_DIR=$ROOT/desktop/target
run() { local name="$1" dir="$2"; shift 2; echo "=== $name" >> "$LOG"; (cd "$dir" && timeout 600 bash "$M" "$@") >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "H1 read timeout 3 s -> 10 s" "$ROOT/desktop" core/src/runner.rs 's#pub const READ_TIMEOUT: Duration = Duration::from_secs(3);#pub const READ_TIMEOUT: Duration = Duration::from_secs(10 /*H1*/);#' '/*H1*/' cargo test -p aristotle-core
run "H2 hermes absent notifies again" "$ROOT/desktop" core/src/diff.rs '159s#\&\& after.hermes_reported()#\&\& (true /*H2*/ || after.hermes_reported())#' '/*H2*/' cargo test -p aristotle-core
run "H3 row 4b uses the stopped headline" "$ROOT/desktop/ui" src/state/world.ts 's#"4b": { title: "st_changing", sub: "sub_changing", light: "err" },#"4b": { title: "st_stopped", sub: "sub_stopped", light: "err" } /*H3*/,#' '/*H3*/' npx vitest run
grep -rn '/\*H[0-9]\*/' desktop/core/src desktop/ui/src && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED' "$LOG"
