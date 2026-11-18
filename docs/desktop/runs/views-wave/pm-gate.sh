#!/usr/bin/env bash
# PM gate for the views wave (DS4, DS5, DS6, DS1d) on the combined tree. No commit here.
set -uo pipefail
cd /home/justcallmesu/project/personal/Aristotle
OUT=docs/desktop/runs/views-wave
mkdir -p "$OUT"
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate-desktop" > "$OUT/pm-gate-desktop.log" 2>&1; echo "desktop gate exit=$?"
cat "$OUT/pm-gate-desktop/gate-summary.txt"
GATE_ONLY=server timeout 1800 bash docs/v1-tickets/gates.sh "$OUT/pm-gate-server" > "$OUT/pm-gate-server.log" 2>&1; echo "server gate exit=$?"
tail -5 "$OUT/pm-gate-server.log"
grep -rhE '(FAIL|✗|×) ' "$OUT"/pm-gate-desktop/*ui-test*.log 2>/dev/null | head -20
git status --short | head -60
