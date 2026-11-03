#!/usr/bin/env bash
# Commits wave 2 (T01b, T02b, T03b) as one commit per ticket, plus a PM docs commit.
set -euo pipefail
cd "$(dirname "$0")/../.."
commit() { local msg=$1; shift; git add -- "$@"; if git diff --cached --quiet; then echo "skip: $msg"; return; fi; git commit -q -m "$msg"; echo "committed: $(git log -1 --format='%h %s')"; }
commit "T01b: protocol doc fixes (session.info shapes, ticket params, relay redaction)" docs/protocol.md docs/spikes/v0-attach.md docs/v1-tickets/runs/T01b
commit "T02b: Android SDK 36, current Compose, no SDK auto-download, light/night launch theme" android docs/v1-tickets/runs/T02b
commit "T03b: state dir 0700, strict config keys, gate fails on non-executable gradlew" server docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03b docs/v1-tickets/runs/pm-gate-2
commit "docs: board after wave 2, spec Q34 wording" docs/spec.md docs/v1-tickets/tickets.md docs/v1-tickets/runs/review-brief-wave2.md docs/v1-tickets/commit-wave2.sh
echo "--- left uncommitted:"; git status --short
