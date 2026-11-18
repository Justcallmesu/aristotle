#!/usr/bin/env bash
# PM: desktop gate on the combined tree, then mutants (sequential: mutants must not overlap the gate).
set -uo pipefail
cd /home/justcallmesu/project/personal/Aristotle
OUT=docs/desktop/runs/views-wave
timeout 1800 bash docs/desktop/gates.sh "$OUT/pm-gate-desktop-2" > "$OUT/pm-gate-desktop-2.log" 2>&1; echo "desktop gate exit=$?"
cat "$OUT/pm-gate-desktop-2/gate-summary.txt"
bash "$OUT/mut-views.sh"
