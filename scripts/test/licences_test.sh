#!/usr/bin/env bash
# Tests for scripts/licences_desktop.py (P25-1, P26 (5), L4, L6) against a scratch repo: a two-crate cargo workspace named like
# the real one, path dependencies outside it with the licence shapes under test, a one-package npm lock, and a stub npm/cargo
# on PATH. Never touches the real desktop/ tree.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=lib.sh
source "$HERE/lib.sh"

SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/licences-test.XXXXXX")"
trap 'rm -rf "$SCRATCH"' EXIT

crate() { # dir name manifest-extra [LICENSE text]
  mkdir -p "$1/src"
  printf '[package]\nname = "%s"\nversion = "0.1.0"\nedition = "2021"\n%s\n' "$2" "$3" > "$1/Cargo.toml"
  echo 'pub fn f() {}' > "$1/src/lib.rs"
  if [[ -n "${4:-}" ]]; then echo "$4" > "$1/LICENSE"; fi
}

# new_repo <name> <deps...>: scratch repo <name> whose binaries depend on the given dependency crates (name=path-relative toml lines)
new_repo() {
  local repo="$SCRATCH/$1/repo" deps="$SCRATCH/$1/deps" extra="${2:-}"
  mkdir -p "$repo/scripts" "$repo/desktop/tray/src" "$repo/desktop/app/src" "$repo/desktop/ui/src/assets/fonts" "$deps"
  cp "${LICENCES_SCRIPT:-$ROOT/scripts/licences_desktop.py}" "$repo/scripts/licences_desktop.py"
  cp -r "$ROOT/scripts/licence-texts" "$repo/scripts/"
  printf '[workspace]\nmembers = ["tray", "app"]\nresolver = "2"\n' > "$repo/desktop/Cargo.toml"
  printf '[package]\nname = "aristotle-tray"\nversion = "0.1.0"\nedition = "2021"\n[dependencies]\nshipped = { path = "../../../deps/shipped" }\n%s\n' "$extra" > "$repo/desktop/tray/Cargo.toml"
  printf '[package]\nname = "aristotle-desktop"\nversion = "0.1.0"\nedition = "2021"\n[dependencies]\ndual = { path = "../../../deps/dual" }\n' > "$repo/desktop/app/Cargo.toml"
  echo 'fn main() {}' > "$repo/desktop/tray/src/main.rs"
  echo 'fn main() {}' > "$repo/desktop/app/src/main.rs"
  crate "$deps/shipped" shipped 'license = "MIT"' "SHIPPED LICENCE TEXT"
  crate "$deps/dual" dual 'license = "MIT OR Apache-2.0"'
  printf 'OFL TEXT\n' > "$repo/desktop/ui/src/assets/fonts/OFL.txt"
  printf 'OFL LITERATA\n' > "$repo/desktop/ui/src/assets/fonts/OFL-Literata.txt"
  printf '{"name":"ui","lockfileVersion":3,"packages":{"":{"name":"ui"},"node_modules/leftpad":{"version":"1.0.0","license":"MIT"},"node_modules/devtool":{"version":"2.0.0","dev":true,"license":"MIT"}}}\n' > "$repo/desktop/ui/package-lock.json"
  echo "$repo"
}

add_dep() { # repo name expr-line [authors-line]  (a dependency of aristotle-tray)
  local repo="$1" deps; deps="$(dirname "$repo")/deps"
  crate "$deps/$2" "$2" "$3"
  printf '%s = { path = "../../../deps/%s" }\n' "$2" "$2" >> "$repo/desktop/tray/Cargo.toml"
}

lock() { (cd "$1/desktop" && cargo generate-lockfile --offline > /dev/null 2>&1); }

# stub tools: npm creates node_modules/leftpad with a LICENSE; both log their calls
STUBS="$SCRATCH/stubs"
mkdir -p "$STUBS"
cat > "$STUBS/npm" <<'EOF'
#!/usr/bin/env bash
echo "npm $*" >> "$STUB_LOG"
mkdir -p node_modules/leftpad node_modules/devtool
echo "LEFTPAD LICENCE TEXT" > node_modules/leftpad/LICENSE
EOF
REAL_CARGO="$(command -v cargo)"
cat > "$STUBS/cargo" <<EOF
#!/usr/bin/env bash
# The first offline call fails (crates not fetched yet); \`cargo fetch\` is logged; everything else is the real cargo.
echo "cargo \$*" >> "\$STUB_LOG"
if [[ "\$1" == fetch ]]; then touch "\$STUB_FETCHED"; exit 0; fi
if [[ -n "\${STUB_FAIL_OFFLINE:-}" && ! -e "\$STUB_FETCHED" && " \$* " == *" --offline "* ]]; then echo "error: no matching package" >&2; exit 101; fi
exec "$REAL_CARGO" "\$@"
EOF
chmod +x "$STUBS/npm" "$STUBS/cargo"

# run_licences <repo> -> OUT (stdout), ERR (stderr), CODE
run_licences() {
  local repo="$1"
  export STUB_LOG="$SCRATCH/stub.log" STUB_FETCHED="$SCRATCH/fetched"
  : > "$STUB_LOG"; rm -f "$STUB_FETCHED"
  OUT="$(PATH="$STUBS:$PATH" python3 "$repo/scripts/licences_desktop.py" 2> "$SCRATCH/err")"
  CODE=$?
  ERR="$(cat "$SCRATCH/err")"
}

mkdir -p "$SCRATCH/ui-nm"

# --- 1: declared licences get the standard text, a copyright line, and only linked crates appear
R="$(new_repo ok)"
add_dep "$R" nolic 'license = "MIT"
authors = ["Jane Doe <jane@example.org>", "Joe"]'
add_dep "$R" noauthors 'license = "BSD-3-Clause"'
add_dep "$R" mpl 'license = "MPL-2.0"'
# not linked: a dev-only, a build-only and a Windows-only dependency
crate "$(dirname "$R")/deps/devonly" devonly 'license = "MIT"'
crate "$(dirname "$R")/deps/buildonly" buildonly 'license = "MIT"'
crate "$(dirname "$R")/deps/winonly" winonly 'license = "MIT"'
{
  printf '[dev-dependencies]\ndevonly = { path = "../../../deps/devonly" }\n'
  printf '[build-dependencies]\nbuildonly = { path = "../../../deps/buildonly" }\n'
  printf '[target.%s.dependencies]\nwinonly = { path = "../../../deps/winonly" }\n' "'cfg(windows)'"
} >> "$R/desktop/tray/Cargo.toml"
echo 'fn main() {}' > "$R/desktop/tray/build.rs"
lock "$R"
mkdir -p "$R/desktop/ui/node_modules/leftpad"
echo "LEFTPAD LICENCE TEXT" > "$R/desktop/ui/node_modules/leftpad/LICENSE"
run_licences "$R"
expect_code 0 "$CODE" "declared licences: generator exit"
MARK="standard text of the declared licence; the crate ships none"
expect_contains "$OUT" "nolic 0.1.0 (Rust crate) - MIT" "nolic section"
expect_contains "$OUT" "--- MIT ($MARK)" "MIT standard text is marked"
expect_contains "$OUT" "Permission is hereby granted, free of charge" "MIT text is included"
expect_contains "$OUT" "Copyright (c) Jane Doe <jane@example.org>, Joe" "copyright line from the crate authors"
expect_contains "$OUT" "Copyright (c) the noauthors authors" "copyright line without authors"
expect_contains "$OUT" "--- BSD-3-Clause ($MARK)" "BSD-3-Clause standard text"
expect_contains "$OUT" "Neither the name of the copyright holder" "BSD-3 text is included"
expect_contains "$OUT" "--- MPL-2.0 ($MARK)" "MPL-2.0 standard text"
expect_contains "$OUT" "Mozilla Public License Version 2.0" "MPL text is included"
expect_contains "$OUT" "--- Apache-2.0 ($MARK)" "an OR expression includes every alternative (Apache)"
expect_contains "$OUT" "Apache License" "Apache text is included"
expect_not_contains "$OUT" "<year>" "no unfilled placeholder in the texts"
expect_not_contains "$OUT" "<copyright holders>" "no unfilled holders placeholder"
expect_contains "$OUT" "SHIPPED LICENCE TEXT" "a shipped licence file is used verbatim"
n_marked="$(grep -cF -- "($MARK)" <<< "$OUT" || true)"
expect_eq 5 "$n_marked" "standard text only for the four crates that ship none (MIT, BSD-3, MPL, MIT+Apache; not the shipped one)"
expect_not_contains "$OUT" "devonly" "dev-only dependency is not listed"
expect_not_contains "$OUT" "buildonly" "build-only dependency is not listed"
expect_not_contains "$OUT" "winonly" "Windows-only dependency is not listed (not linked)"
expect_contains "$OUT" "leftpad 1.0.0 (npm package)" "npm runtime package"
expect_not_contains "$OUT" "devtool" "npm dev package is not listed"
expect_not_contains "$OUT" "Gaps" "no gaps section"
RUSTV="$(rustc -V | awk '{print $2}')"
expect_contains "$OUT" "Rust standard library and runtime (rustc $RUSTV, the toolchain that builds the desktop programs) - MIT OR Apache-2.0" "Rust standard library entry"
expect_contains "$OUT" "Copyright (c) The Rust Project Contributors" "Rust standard library copyright"
n_apache="$(grep -c '^Apache License$' <<< "$OUT" || true)"
if [[ "$n_apache" -ge 2 ]]; then pass "Apache text appears for the std entry and for dual"; else fail "Apache text count $n_apache, want >= 2"; fi
first="$OUT"
run_licences "$R"
expect_eq "$first" "$OUT" "output is deterministic"

# --- 2: an undeclared licence with no file is an error
R="$(new_repo undeclared)"
add_dep "$R" nolicence ''
lock "$R"
mkdir -p "$R/desktop/ui/node_modules/leftpad"
echo "LEFTPAD LICENCE TEXT" > "$R/desktop/ui/node_modules/leftpad/LICENSE"
run_licences "$R"
expect_code 1 "$CODE" "undeclared licence: generator exit"
expect_contains "$ERR" "nolicence 0.1.0 (Rust crate)" "undeclared licence names the crate"
expect_contains "$ERR" "no licence declared" "undeclared licence says why"

# --- 3: a declared licence without a standard text is an error, never guessed
R="$(new_repo unknown)"
add_dep "$R" weird 'license = "WTFPL"'
lock "$R"
mkdir -p "$R/desktop/ui/node_modules/leftpad"
echo "LEFTPAD LICENCE TEXT" > "$R/desktop/ui/node_modules/leftpad/LICENSE"
run_licences "$R"
expect_code 1 "$CODE" "unknown licence: generator exit"
expect_contains "$ERR" "declared licence 'WTFPL' has no standard text" "unknown licence names the id"
R="$(new_repo withexc)"
add_dep "$R" exc 'license = "Apache-2.0 WITH LLVM-exception"'
lock "$R"
mkdir -p "$R/desktop/ui/node_modules/leftpad"
echo "LEFTPAD LICENCE TEXT" > "$R/desktop/ui/node_modules/leftpad/LICENSE"
run_licences "$R"
expect_code 1 "$CODE" "WITH exception: generator exit"

# --- 4: a fresh clone has no node_modules: the generator runs npm ci itself
R="$(new_repo fresh)"
lock "$R"
run_licences "$R"
expect_code 0 "$CODE" "fresh clone (no node_modules): generator exit"
expect_contains "$(cat "$STUB_LOG")" "npm ci" "npm ci was run"
expect_contains "$OUT" "LEFTPAD LICENCE TEXT" "npm licence text comes from the fresh install"

# --- 5: crates never fetched: the generator runs cargo fetch --locked and retries
R="$(new_repo nofetch)"
lock "$R"
mkdir -p "$R/desktop/ui/node_modules/leftpad"
echo "LEFTPAD LICENCE TEXT" > "$R/desktop/ui/node_modules/leftpad/LICENSE"
STUB_FAIL_OFFLINE=1 run_licences "$R"
expect_code 0 "$CODE" "unfetched crates: generator exit"
expect_contains "$(cat "$STUB_LOG")" "cargo fetch --locked" "cargo fetch --locked was run"

finish licences_test
