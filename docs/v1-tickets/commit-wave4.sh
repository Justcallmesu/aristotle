#!/usr/bin/env bash
# Commits wave 4: T04b (connector fixes) and T05+T05b (listeners, pairing, device auth). Run from anywhere.
set -euo pipefail
cd "$(dirname "$0")/../.."
commit() { local msg=$1; shift; git add -- "$@"; if git diff --cached --quiet; then echo "skip: $msg"; return; fi; git commit -q -m "$msg"; echo "committed: $(git log -1 --format='%h %s')"; }
commit "T04b: connector review fixes (per-profile REST tickets, no redirects, header strip, handshake limits, close codes, socket discovery)" \
  server/internal/hermes docs/v1-tickets/runs/T04b
commit "T05+T05b: LAN/Tailscale listeners, TLS pinning cert, pairing codes, device tokens, devices list/revoke" \
  server docs/protocol.md docs/v1-tickets/runs/T05 docs/v1-tickets/runs/T05b docs/v1-tickets/runs/pm-gate-4 docs/v1-tickets/runs/review-brief-wave4.md
commit "docs: board after wave 4" docs/v1-tickets/tickets.md docs/v1-tickets/commit-wave4.sh
echo "--- left uncommitted:"; git status --short
