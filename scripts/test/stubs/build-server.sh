#!/usr/bin/env bash
# Test stub of scripts/build-server.sh for release_test.sh (copied into the scratch repo only).
# Usage as the real one: build-server.sh --version X OUT. OUT becomes a script printing $BUILD_STUB_VERSION.
# BUILD_STUB_DIRTY=1 also edits a tracked file, as a build that dirtied the tree would.
set -euo pipefail
out="${*: -1}"
{ echo '#!/usr/bin/env bash'; echo "echo '${BUILD_STUB_VERSION:-nope}'"; } > "$out"
chmod +x "$out"
[[ -z "${BUILD_STUB_DIRTY:-}" ]] || echo dirty >> "$(dirname "${BASH_SOURCE[0]}")/../CHANGELOG.md"
