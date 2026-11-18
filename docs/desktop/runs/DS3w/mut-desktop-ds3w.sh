#!/usr/bin/env bash
# PM mutation checks for desktop DS3w (run only when no gate/worker runs on the tree).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd "$ROOT"
cat docs/desktop/runs/DS3w/pm-gate/gate-summary.txt
cd desktop
export CARGO_TARGET_DIR="$ROOT/desktop/target"
LOG=../docs/desktop/runs/DS3w/pm-mutants.log
: > "$LOG"
run() { echo "=== $1" >> "$LOG"; shift; bash "$M" "$@" >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "M1 stamp kept after a failed stop (DF6)" app/src/service.rs '48s#(failed, stamp)#(false \&\& failed /*M1*/, stamp)#' '/*M1*/' cargo test -p aristotle-desktop
run "M2 logs stop no longer kills the group" app/src/logs.rs '87s#killpg(pgid, SIGKILL);#killpg(pgid, 0 /*M2*/);#' '/*M2*/' cargo test -p aristotle-desktop
run "M3 capability grants a webview default" app/capabilities/default.json '22s#"core:event:allow-unlisten"#"core:event:allow-unlisten", "core:webview:default"#' 'core:webview:default' cargo test -p aristotle-desktop --test capabilities
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED|^test .* FAILED' "$LOG" | head -30
