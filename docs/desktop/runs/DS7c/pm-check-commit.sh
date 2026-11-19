#!/usr/bin/env bash
# PM: gates + tray loops + commit for DS7 + DF11 + DS7b + DS7c. Commits only when everything is green.
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
OUT=$ROOT/docs/desktop/runs/DS7c
cd "$ROOT"
pgrep -f 'gradle|cargo test' > /dev/null && echo "note: other gradle/cargo processes are running"
timeout 3000 bash docs/v1-tickets/gates.sh "$OUT/pm-gate-full" > "$OUT/pm-gate-full.log" 2>&1; echo "full gate exit=$?"
cat "$OUT/pm-gate-full/gate-summary.txt"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate-desktop" > "$OUT/pm-gate-desktop.log" 2>&1; echo "desktop gate exit=$?"
cat "$OUT/pm-gate-desktop/gate-summary.txt"
export CARGO_TARGET_DIR=$ROOT/desktop/target
cd desktop
loop() { local f=0; for i in $(seq 13); do timeout 300 cargo test -q -p aristotle-tray > "$OUT/pm-loop-$1-$i.log" 2>&1 || f=$((f+1)); done; echo "loop $1: runs=13 failed=$f"; }
loop 1 & loop 2 & loop 3 & loop 4 & wait
cd "$ROOT"
if grep -q '^FAIL' "$OUT/pm-gate-full/gate-summary.txt" "$OUT/pm-gate-desktop/gate-summary.txt"; then echo "NOT COMMITTING: gate red"; exit 1; fi
if grep -l 'test result: FAILED\|error' "$OUT"/pm-loop-*.log > /dev/null 2>&1; then echo "NOT COMMITTING: loop failures:"; grep -l 'FAILED' "$OUT"/pm-loop-*.log; exit 1; fi
rm -f "$OUT"/pm-loop-*.log
git add CHANGELOG.md desktop docs/desktop docs/release scripts server/internal/app server/internal/service
staged="$(git diff --cached --name-only)"
if echo "$staged" | grep -qE '(^desktop/target/|/node_modules/|^desktop/ui/dist/|^dist/)'; then echo "ABORT: build output staged"; exit 1; fi
echo "staged: $(echo "$staged" | wc -l) files"
git commit -q -m "desktop DS7 + DF11 + DS7b + DS7c: release bundle with aristotle-tray/aristotle-desktop (build-desktop.sh, version-stamped, --version checked), installer desktop step (webkit check, atomic + .prev, entries, tray replace/start, truthful output), uninstall removes entries and stops only installed programs, desktop licence notices (linked crates + std + npm + fonts), tray single instance + exits when the panel host is gone (P26/P27), window-failed notice (DF11). PM gates GREEN (full + desktop), tray 52 loop runs 0 failures; reviews: DS7 + DS7b changes-required -> fixed in DS7b/DS7c"
git push -q origin master
git log -1 --oneline
git status --short | head -5
