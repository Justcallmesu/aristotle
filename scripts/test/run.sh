#!/usr/bin/env bash
# Runs the shell tests in scripts/test/ (*_test.sh), one PASS/FAIL line each. Usage: scripts/test/run.sh [name ...]
# e.g. `run.sh install` runs install_test.sh only. Exit 1 when any test fails.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

tests=()
if [[ $# -eq 0 ]]; then
  for t in "$HERE"/*_test.sh; do tests+=("$t"); done
else
  for name in "$@"; do tests+=("$HERE/${name}_test.sh"); done
fi

failed=0
for t in "${tests[@]}"; do
  name="$(basename "$t" .sh)"
  echo "=== $name"
  if bash "$t"; then
    echo "PASS $name"
  else
    echo "FAIL $name"
    failed=1
  fi
done
exit "$failed"
