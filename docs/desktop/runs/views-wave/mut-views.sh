#!/usr/bin/env bash
# PM mutants for the views wave + DS1d. Each under timeout; a killed run leaves no marker (checked).
set -uo pipefail
ROOT=/home/justcallmesu/project/personal/Aristotle
M=/home/justcallmesu/project/personal/pm-orchestrator-skills/orchestrator/thin-orchestrator/scripts/mutate.sh
LOG=$ROOT/docs/desktop/runs/views-wave/pm-mutants.log
: > "$LOG"
cd "$ROOT/desktop/ui"
run() { local name="$1"; shift; echo "=== $name" >> "$LOG"; timeout 600 bash "$M" "$@" >> "$LOG" 2>&1; echo "exit=$?" >> "$LOG"; }
run "V1 confirm sends revoke twice" src/views/devices/DevicesView.tsx \
  's#setAsking(null);#setAsking(null); void revoke.run(asking?.id ?? "x"); /*V1*/#' '/*V1*/' npx vitest run src/views/devices
run "V2 pair code kept after expiry" src/views/pair/PairView.tsx \
  's#? { kind: "expired", addresses: current.pair.addresses, expiresAt: current.expiresAt }#? current /*V2*/#' '/*V2*/' npx vitest run src/views/pair
run "V3 turn-off never confirms" src/views/terminal/TerminalView.tsx \
  's#if (open.length > 0)#if (false /*V3*/)#' '/*V3*/' npx vitest run src/views/terminal
run "V4 Start allowed on any non-active word" src/views/service/serviceButtons.ts \
  's#if (service === "inactive" || service === "failed")#if (service !== "active" /*V4*/)#' '/*V4*/' npx vitest run src/views/service src/views/status
run "V5 mismatch no longer disables service buttons" src/views/service/serviceButtons.ts \
  's#if (world.mismatch) return off("dis_mismatch");#/*V5*/#' '/*V5*/' npx vitest run src/views/service src/views/status
cd "$ROOT/server"
run "G1 qr_modules built from a different payload" internal/app/cli.go \
  '230s#termqr.Encode(text)#termqr.Encode(text + "x" /*G1*/)#' '/*G1*/' go test ./internal/app/
cd "$ROOT"
grep -rn '/\*[VG][0-9]\*/' desktop/ui/src server/internal && echo "MARKER LEFT" || echo "no markers left"
grep -E '^===|KILLED|SURVIVED|NOT APPLIED|NOT COMPILED|^exit=' "$LOG"
