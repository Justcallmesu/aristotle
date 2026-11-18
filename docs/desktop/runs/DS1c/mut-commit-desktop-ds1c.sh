#!/usr/bin/env bash
# PM gate + mutation + commit for desktop DS1c.
set -uo pipefail
cd /home/justcallmesu/project/personal/Aristotle
GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/desktop/runs/DS1c/pm-gate > /dev/null 2>&1
cat docs/desktop/runs/DS1c/pm-gate/gate-summary.txt
grep -q '^FAIL' docs/desktop/runs/DS1c/pm-gate/gate-summary.txt && { echo "ABORT: gate red"; exit 1; }
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd server
echo "=== M1 Installed ignores the file"
out="$(bash "$M" internal/service/service.go '138s#return err == nil \&\& !info.IsDir()#return (err == nil \&\& !info.IsDir()) || true /*M1*/#' '/*M1*/' go test ./internal/service/ ./internal/app/ 2>&1)"
echo "$out" | grep -E -- '--- FAIL|KILLED|SURVIVED|NOT' | head -5
echo "$out" | grep -q KILLED || { echo "ABORT: mutant not killed"; exit 1; }
cd ..
git add server/internal/app server/internal/service docs/protocol.md docs/desktop/runs/DS1c
git diff --cached --name-only | grep -qvE '^(server/|docs/protocol.md|docs/desktop/runs/DS1c/)' && { echo "ABORT: stray path"; exit 1; }
git commit -q -m "desktop DS1c: status --json installed (unit file present; is-enabled prints enabled for an absent unit here), P13. Gate GREEN, PM mutant killed"
git push -q origin master
git log -1 --oneline
