#!/usr/bin/env bash
# PM gate + mutants for DS8b (sequential).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DS8b
cd "$ROOT"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate" > "$OUT/pm-gate.log" 2>&1; echo "gate exit=$?"
cat "$OUT/pm-gate/gate-summary.txt"
LOG=$OUT/pm-mutants.log; : > "$LOG"
export CARGO_TARGET_DIR=$ROOT/desktop/target
run() { local name="$1" dir="$2"; shift 2; echo "=== $name" >> "$LOG"; (cd "$dir" && timeout 600 bash "$M" "$@") >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "B1 grant notice uses the D7 title" "$ROOT/desktop" core/src/strings.rs '266s#text(lang, Key::GrantTitle)#text(lang, Key::TermOnTitle /*B1*/)#' '/*B1*/' cargo test -p aristotle-core
run "B2 failed grant read treated as empty list" "$ROOT/desktop" core/src/diff.rs 's#if let (Some(before), Some(after)) = (\&prev.terminal_granted, \&next.terminal_granted) {#if let (Some(before), Some(after)) = (\&Some(prev.terminal_granted.clone().unwrap_or_default()) /*B2*/, \&next.terminal_granted) {#' '/*B2*/' cargo test -p aristotle-core
grep -rn '/\*B[0-9]\*/' desktop/core/src && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED' "$LOG"
