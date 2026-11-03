#!/usr/bin/env bash
# Commits wave 1 of Aristotle as four commits (one per ticket). Run: bash docs/v1-tickets/commit-wave1.sh
set -euo pipefail
cd "$(dirname "$0")/../.."

commit() { # commit "<message>" <paths...>
  local msg=$1; shift
  git add -- "$@"
  if git diff --cached --quiet; then echo "skip (nothing staged): $msg"; return; fi
  git commit -q -m "$msg"
  echo "committed: $(git log -1 --format='%h %s')"
}

commit "docs: Aristotle spec, project rules, v1 board" \
  PROJECT.md docs/spec.md docs/assets docs/v1-tickets/tickets.md docs/v1-tickets/decisions.md \
  docs/v1-tickets/commit-wave1.sh
commit "T01: v0 spike, attach to live Hermes sessions" \
  spikes docs/spikes docs/protocol.md docs/v1-tickets/runs/T01
commit "T02: Android app shell" \
  android docs/v1-tickets/runs/T02
commit "T03: server skeleton and gate script" \
  server docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03 docs/v1-tickets/runs/pm-gate-1 \
  docs/v1-tickets/runs/review-brief-wave1.md

echo "--- left uncommitted:"
git status --short
