#!/usr/bin/env bash
# PM check for DF10: 3 parallel loops x 20 runs of the file; count failures.
cd /home/justcallmesu/project/personal/Aristotle/desktop/ui
OUT=/home/justcallmesu/project/personal/Aristotle/docs/desktop/runs/DF10/pm-loop
mkdir -p "$OUT"
loop() { local n=0 f=0; for i in $(seq 20); do n=$((n+1)); timeout 300 npx vitest run src/views/service/ServiceView.test.tsx > "$OUT/l$1-$i.log" 2>&1 || f=$((f+1)); done; echo "loop $1: runs=$n failed=$f"; }
loop 1 & loop 2 & loop 3 & wait
