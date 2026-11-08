#!/usr/bin/env bash
# Build aristotle-server. Usage: scripts/build-server.sh [--version X.Y.Z] [output-path]
# Version: --version when given (release.sh), else `git describe --tags` (leading v stripped), else 0.0.0-dev+<short sha>.
# GOOS/GOARCH from the environment are honoured (release.sh sets linux/amd64).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=""
if [[ "${1:-}" == --version ]]; then
  [[ $# -ge 2 && -n "$2" ]] || { echo "usage: build-server.sh [--version X.Y.Z] [output-path]" >&2; exit 2; }
  VERSION="$2"
  shift 2
fi
OUT="${1:-$ROOT/server/bin/aristotle-server}"
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
if [[ -z "$VERSION" ]]; then
  if VERSION="$(git -C "$ROOT" describe --tags 2>/dev/null)"; then
    VERSION="${VERSION#v}"
  else
    VERSION="0.0.0-dev+$COMMIT"
  fi
fi
PKG=github.com/justcallmesu/aristotle/server/internal/version
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")" # go build runs in server/
cd "$ROOT/server"
go build -ldflags "-X $PKG.Version=$VERSION -X $PKG.Commit=$COMMIT" -o "$OUT" ./cmd/aristotle-server
echo "built $OUT ($VERSION, $COMMIT)"
