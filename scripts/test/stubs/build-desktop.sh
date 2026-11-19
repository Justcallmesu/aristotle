#!/usr/bin/env bash
# Test stub of scripts/build-desktop.sh for release_test.sh (copied into the scratch repo only; the real build takes minutes).
# Usage as the real one: build-desktop.sh --version X OUT-DIR. Writes the five desktop files into OUT-DIR: the two programs
# print "<name> $DESKTOP_STUB_VERSION" (default: X) for --version.
set -euo pipefail
version="$2"
out="$3"
for name in aristotle-tray aristotle-desktop; do
  { echo '#!/usr/bin/env bash'; echo "echo '$name ${DESKTOP_STUB_VERSION:-$version}'"; } > "$out/$name"
  chmod 0755 "$out/$name"
done
for name in aristotle-tray.desktop aristotle-desktop.desktop aristotle-server.png; do
  echo "stub $name" > "$out/$name"
  chmod 0600 "$out/$name" # a bundle step must set 644, like the real build's copy would not guarantee
done
