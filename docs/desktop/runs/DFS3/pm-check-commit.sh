#!/usr/bin/env bash
# PM gate + mutant + commit for DFS3.
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
OUT=$ROOT/docs/desktop/runs/DFS3
cd "$ROOT"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate" > "$OUT/pm-gate.log" 2>&1; echo "gate exit=$?"
cat "$OUT/pm-gate/gate-summary.txt"
grep -q '^FAIL' "$OUT/pm-gate/gate-summary.txt" && { echo "ABORT: gate red"; exit 1; }
out="$(cd desktop/ui && timeout 600 bash "$M" src/state/world.ts '45s# \&\& service !== "unknown";# /*K1*/;#' '/*K1*/' npx vitest run src/state 2>&1)"
echo "$out" | grep -E 'KILLED|SURVIVED|NOT' | head -2 | tee "$OUT/pm-mutants.log"
grep -rn '/\*K1\*/' desktop/ui/src && { echo "ABORT: marker"; exit 1; }
echo "$out" | grep -q KILLED || { echo "ABORT: K1 not killed"; exit 1; }
git add desktop docs/desktop
staged="$(git diff --cached --name-only)"
if echo "$staged" | grep -qE '(^desktop/target/|/node_modules/|^desktop/ui/dist/)'; then echo "ABORT: build output staged"; exit 1; fi
echo "staged: $(echo "$staged" | wc -l) files"
git commit -q -m "desktop DFS3: status keeps 10 s in the poll (test), unknown = row 4, log child getppid check + spawner thread io error, honest stamp/runner comments, tray Start guard tests, runner limit tests, orphan_helper not counted; floors 187/301. PM gate GREEN, mutant killed"
git push -q origin master
git log -1 --oneline
