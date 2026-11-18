#!/usr/bin/env bash
# PM mutation checks for desktop DS1. Run only when no gate/worker runs on the tree.
set -uo pipefail
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd /home/justcallmesu/project/personal/Aristotle/server
LOG=../docs/desktop/runs/DS1/pm-mutants.log
: > "$LOG"
run() { echo "=== $1" >> "$LOG"; shift; bash "$M" "$@" >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "M1 qr_payload dropped" internal/app/cli.go '220s#out.QRPayload = text#out.QRPayload = "" + "" // M1#' '"" + "" // M1' go test ./internal/app/
run "M2 remote-terminal refusal disabled" internal/app/servicecmds.go '309s#verb != "start" \&\&#verb == "M2zz" \&\&#' 'M2zz' go test ./internal/app/
run "M3 stop runs start" internal/service/service.go '144s#m.ctl("stop", unitArg)#m.ctl("start" /*M3*/, unitArg)#' '/*M3*/' go test ./internal/service/ ./internal/app/
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED|exit=|--- FAIL' "$LOG"
git status --short -- internal/
