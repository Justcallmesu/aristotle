#!/usr/bin/env bash
# PM gate + mutants + commit for desktop DS3x + DS3y. Every mutant runs under timeout (a hang = finding).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
cd "$ROOT"
timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS3x/pm-gate > /dev/null 2>&1
cat docs/desktop/runs/DS3x/pm-gate/gate-summary.txt
grep -q '^FAIL' docs/desktop/runs/DS3x/pm-gate/gate-summary.txt && { echo "ABORT: gate red"; exit 1; }
export CARGO_TARGET_DIR="$ROOT/desktop/target"
cd desktop
echo "=== M2 logs stop does not kill the group (must FAIL now, not hang)"
out="$(timeout 600 bash "$M" app/src/logs.rs '105s#killpg(pgid, SIGKILL);#killpg(pgid, 0 /*M2*/);#' '/*M2*/' cargo test -p aristotle-desktop logs 2>&1)"; rc=$?
echo "$out" | grep -E -- 'FAILED|KILLED|SURVIVED|NOT' | head -5; echo "rc=$rc"
grep -q '/\*M2\*/' app/src/logs.rs && { echo "ABORT: M2 left in tree"; exit 1; }
echo "$out" | grep -q KILLED || { echo "ABORT: M2 not killed"; exit 1; }
echo "=== M4 ok:false counted as success"
out="$(cd ui && timeout 600 bash "$M" src/state/useCommand.ts '59s#result !== null \&\&#result !== null \&\& false /*M4*/ \&\&#' '/*M4*/' npx vitest run src/state/useCommand.test.tsx 2>&1)"
echo "$out" | grep -E -- 'FAIL|KILLED|SURVIVED|NOT' | head -5
grep -q '/\*M4\*/' ui/src/state/useCommand.ts && { echo "ABORT: M4 left in tree"; exit 1; }
echo "$out" | grep -q KILLED || { echo "ABORT: M4 not killed"; exit 1; }
cd "$ROOT"
git add desktop docs/desktop/gates.sh docs/desktop/runs/DS3x docs/desktop/runs/DS3y docs/desktop/tickets.md
staged="$(git diff --cached --name-only)"
if echo "$staged" | grep -qE '(^desktop/target/|/node_modules/|^desktop/ui/dist/|^server/)'; then echo "ABORT: build output staged"; exit 1; fi
echo "staged: $(echo "$staged" | wc -l) files"
git commit -q -m "desktop DS3x + DS3y: bounded log-stream stop + gate timeouts, concurrent logs_start, stamp kept on CLI timeout, shared NeedsServer/CommandFailed/useCommand (ok:false = failure), terminal_enabled in snapshot, P18 footer, pinned single-instance, queued hand-off, snapshot seq. PM gate GREEN, PM mutants killed (log-kill now fails instead of hanging)"
git push -q origin master
git log -1 --oneline
