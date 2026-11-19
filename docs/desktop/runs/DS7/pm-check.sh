#!/usr/bin/env bash
# PM gates + mutants for DF11 + DS7 (sequential). Full project gate (server, android, licences, scripts) + desktop gate.
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DS7
cd "$ROOT"
timeout 3000 bash docs/v1-tickets/gates.sh "$OUT/pm-gate-full" > "$OUT/pm-gate-full.log" 2>&1; echo "full gate exit=$?"
cat "$OUT/pm-gate-full/gate-summary.txt"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate-desktop" > "$OUT/pm-gate-desktop.log" 2>&1; echo "desktop gate exit=$?"
cat "$OUT/pm-gate-desktop/gate-summary.txt"
LOG=$OUT/pm-mutants.log; : > "$LOG"
export CARGO_TARGET_DIR=$ROOT/desktop/target
run() { local name="$1" dir="$2"; shift 2; echo "=== $name" >> "$LOG"; (cd "$dir" && timeout 900 bash "$M" "$@") >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "S1 webkit check inverted" "$ROOT" scripts/install.sh '517s#\[\[ "$out" == \*libwebkit2gtk-4.1.so.0\* \]\]#[[ "$out" != *libwebkit2gtk-4.1.so.0* ]] \# S1MUT#' 'S1MUT' bash scripts/test/run.sh
run "S2 replaced tray (deleted exe) not found" "$ROOT" scripts/install.sh '567s#"${exe% (deleted)}"#"$exe" \# S2MUT#' 'S2MUT' bash scripts/test/run.sh
run "S3 release skips the desktop version check" "$ROOT" scripts/release.sh '148s#^verify_desktop_versions#true \# S3MUT verify_desktop_versions#' 'S3MUT' bash scripts/test/run.sh
grep -n 'S[123]MUT' scripts/install.sh scripts/release.sh && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED' "$LOG"
