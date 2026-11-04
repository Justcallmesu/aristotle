#!/usr/bin/env bash
# Build aristotle-server with the version from git. Usage: scripts/build-server.sh [output-path]
# Version: `git describe --tags` (leading v stripped); fallback 0.0.0-dev+<short sha>.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$ROOT/server/bin/aristotle-server}"
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
if VERSION="$(git -C "$ROOT" describe --tags 2>/dev/null)"; then
  VERSION="${VERSION#v}"
else
  VERSION="0.0.0-dev+$COMMIT"
fi
PKG=github.com/justcallmesu/aristotle/server/internal/version
mkdir -p "$(dirname "$OUT")"
cd "$ROOT/server"
go build -ldflags "-X $PKG.Version=$VERSION -X $PKG.Commit=$COMMIT" -o "$OUT" ./cmd/aristotle-server
echo "built $OUT ($VERSION, $COMMIT)"
