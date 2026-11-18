#!/usr/bin/env bash
# PM mutation checks for desktop DS3 (run only when no gate/worker runs on the tree).
set -uo pipefail
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd /home/justcallmesu/project/personal/Aristotle/desktop
export CARGO_TARGET_DIR=/home/justcallmesu/project/personal/Aristotle/desktop/target
LOG=../docs/desktop/runs/DS3/pm-mutants.log
: > "$LOG"
run() { echo "=== $1" | tee -a "$LOG"; shift; bash "$M" "$@" >> "$LOG" 2>&1; echo "exit=$?" | tee -a "$LOG"; }
run "M1 stale stamp window 60 s -> 6000 s" core/src/diff.rs '9s#from_secs(60)#from_secs(6000 /*M1*/)#' '/*M1*/' cargo test -p aristotle-core
run "M2 Hermes down maps to Green (amber lost)" core/src/snapshot.rs '132s#if !status.hermes_reachable() {#if !status.hermes_reachable() \&\& false /*M2*/ {#' '/*M2*/' cargo test -p aristotle-core
run "M3 expected-stop negation dropped" core/src/diff.rs '46s#\&\& !stop_expected(#\&\& stop_expected( /*M3*/#' '/*M3*/' cargo test -p aristotle-core
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED|exit=|^test .* FAILED' "$LOG" | head -40
git -C .. status --short -- desktop/core/src | head
