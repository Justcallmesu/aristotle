#!/usr/bin/env bash
# Shared helpers for the shell tests in scripts/test/ (sourced, not run).

FAILURES=0

fail() { echo "  FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
pass() { echo "  ok: $*"; }

expect_code() { # want got what
  if [[ "$1" == "$2" ]]; then pass "$3: exit $2"; else fail "$3: exit $2, want $1"; fi
}

expect_eq() { # want got what
  if [[ "$1" == "$2" ]]; then pass "$3"; else fail "$3: got '$2', want '$1'"; fi
}

expect_contains() { # file-or-text needle what
  if grep -qF -- "$2" <<< "$1"; then pass "$3"; else fail "$3: missing '$2' in:"$'\n'"$1"; fi
}

expect_not_contains() { # text needle what
  if grep -qF -- "$2" <<< "$1"; then fail "$3: unexpected '$2' in:"$'\n'"$1"; else pass "$3"; fi
}

expect_file() { if [[ -e "$1" ]]; then pass "$2"; else fail "$2: $1 missing"; fi; }
expect_no_file() { if [[ -e "$1" ]]; then fail "$2: $1 exists"; else pass "$2"; fi; }

expect_same() { # file-a file-b what
  if cmp -s "$1" "$2"; then pass "$3"; else fail "$3: $1 differs from $2"; fi
}

finish() { # test-name
  if [[ $FAILURES -eq 0 ]]; then echo "$1: all passed"; exit 0; fi
  echo "$1: $FAILURES failure(s)" >&2
  exit 1
}
