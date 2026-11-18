#!/usr/bin/env bash
# PM gate + mutation for desktop DS1b.
set -uo pipefail
cd /home/justcallmesu/project/personal/Aristotle
GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/desktop/runs/DS1b/pm-gate > /dev/null 2>&1
cat docs/desktop/runs/DS1b/pm-gate/gate-summary.txt
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd server
echo "=== M1 marker name wrong"
bash "$M" internal/app/servicecmds.go '310s#InRemoteTerminal(terminal.MarkerEnv,#InRemoteTerminal("M1_WRONG",#' 'M1_WRONG' go test ./internal/app/ 2>&1 | grep -E -- '--- FAIL|KILLED|SURVIVED|NOT' | head -5
echo "=== M2 stop uses old unit spelling"
bash "$M" internal/service/service.go '141s#m.ctl("stop", UnitName)#m.ctl("stop", "aristotle-server" /*M2*/)#' '/*M2*/' go test ./internal/service/ ./internal/app/ 2>&1 | grep -E -- '--- FAIL|KILLED|SURVIVED|NOT' | head -5
git status --short -- internal/ | wc -l
