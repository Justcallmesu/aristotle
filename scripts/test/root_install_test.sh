#!/usr/bin/env bash
# Tests for root install.sh (picks a bundle, runs its installer).
# Nothing here touches the real repo, dist/ or systemd.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
source "$HERE/lib.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/root-install-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# id stubs: install.sh reads the uid via `id -u`, so a stub first on PATH fakes the user
make_id_stub() { # name uid
  mkdir -p "$WORK/$1"
  printf '#!/usr/bin/env bash\necho %s\n' "$2" > "$WORK/$1/id"
  chmod +x "$WORK/$1/id"
}
make_id_stub id_user 1000
make_id_stub id_root 0

# new_case: a temp directory with a fake repo layout
new_case() {
  CASE="$WORK/$1"
  REPO="$CASE/repo"
  mkdir -p "$REPO/dist" "$CASE/elsewhere"
  : > "$CASE/calls.log"
  cp "$ROOT/install.sh" "$REPO/install.sh"
  chmod +x "$REPO/install.sh"
}

# create_stub: dist-relative dir, marker. The stub logs "<marker> <its dir name> args: <args>"
create_stub() {
  local dir="$REPO/dist/$1"
  mkdir -p "$dir"
  cat > "$dir/install.sh" << 'STUB'
#!/usr/bin/env bash
echo "MARKER $(basename "$(dirname "$0")") args: $*" >> "$STUB_CALLS_LOG"
exit "${STUB_BUNDLE_EXIT_CODE:-0}"
STUB
  sed -i "s/MARKER/$2/" "$dir/install.sh"
  chmod +x "$dir/install.sh"
}

create_bundle() { create_stub "aristotle-$1" BUNDLE; } # version
create_gate_bundle() { create_stub "gate-$1" GATE; }   # version; must never be picked

# run_root_install args...; sets CODE, OUT. Env: RUN_CWD (default repo), ID_DIR (default id_user),
# STUB_BUNDLE_EXIT_CODE
run_root_install() {
  CODE=0
  OUT="$(cd "${RUN_CWD:-$REPO}" && PATH="$WORK/${ID_DIR:-id_user}:$PATH" STUB_CALLS_LOG="$CASE/calls.log" \
    bash "$REPO/install.sh" "$@" 2>&1)" || CODE=$?
}

calls() { cat "$CASE/calls.log"; }

echo "case: picks the newest bundle by numeric version order (2.10.0 > 2.9.0 > 2.1.1)"
new_case newest
create_bundle 2.9.0
create_bundle 2.10.0
create_bundle 2.1.1
run_root_install
expect_code 0 "$CODE" "found newest bundle"
expect_eq "BUNDLE aristotle-2.10.0 args: " "$(calls)" "exactly aristotle-2.10.0 ran"

echo "case: ignores gate-, rc, non-version names and bundles without install.sh"
new_case ignore
create_bundle 1.0.0
create_gate_bundle 2.10.0
create_bundle 2.11.0-rc1
create_bundle nightly
create_bundle 2.11
run_root_install
expect_code 0 "$CODE" "ignores junk"
expect_eq "BUNDLE aristotle-1.0.0 args: " "$(calls)" "only the valid bundle ran"

echo "case: newest bundle without a usable install.sh -> exit 1, never an older bundle (IN2c)"
new_case not_exec
create_bundle 2.9.0
create_bundle 2.10.0
chmod -x "$REPO/dist/aristotle-2.10.0/install.sh"
run_root_install
expect_code 1 "$CODE" "non-executable newest bundle"
expect_contains "$OUT" "$REPO/dist/aristotle-2.10.0/install.sh is missing or not executable (an incomplete copy?); fix it (chmod +x) or pass --bundle <dir>" "said which file and the way out"
expect_eq "" "$(calls)" "nothing ran, no older bundle used"
expect_not_contains "$OUT" "Installing" "did not start installing"
new_case no_install_newest
create_bundle 2.9.0
mkdir -p "$REPO/dist/aristotle-3.0.0"
run_root_install
expect_code 1 "$CODE" "newest bundle without install.sh"
expect_contains "$OUT" "aristotle-3.0.0/install.sh is missing or not executable" "said install.sh is missing"
expect_eq "" "$(calls)" "nothing ran"

echo "case: args pass through unchanged and in order"
new_case args
create_bundle 2.10.0
run_root_install --no-pair --no-gateway
expect_code 0 "$CODE" "args passed"
expect_eq "BUNDLE aristotle-2.10.0 args: --no-pair --no-gateway" "$(calls)" "args in order"

echo "case: --bundle is removed and wins over a newer bundle"
new_case bundle_removed
create_bundle 2.10.0
create_bundle 1.0.0
run_root_install --no-pair --bundle "$REPO/dist/aristotle-1.0.0" --gateway
expect_code 0 "$CODE" "--bundle handled"
expect_eq "BUNDLE aristotle-1.0.0 args: --no-pair --gateway" "$(calls)" "chosen bundle ran, --bundle not in args"

echo "case: --bundle first and last"
new_case bundle_first
create_bundle 2.10.0
create_bundle 1.0.0
run_root_install --bundle "$REPO/dist/aristotle-1.0.0" --no-pair
expect_code 0 "$CODE" "--bundle first"
expect_eq "BUNDLE aristotle-1.0.0 args: --no-pair" "$(calls)" "args correct when --bundle first"
new_case bundle_last
create_bundle 2.10.0
create_bundle 1.0.0
run_root_install --no-pair --bundle "$REPO/dist/aristotle-1.0.0"
expect_code 0 "$CODE" "--bundle last"
expect_eq "BUNDLE aristotle-1.0.0 args: --no-pair" "$(calls)" "args correct when --bundle last"

echo "case: --bundle=<dir> works like --bundle <dir>, anywhere (IN2c)"
new_case bundle_equals
create_bundle 2.10.0
create_bundle 1.0.0
run_root_install --bundle="$REPO/dist/aristotle-1.0.0" --no-pair
expect_code 0 "$CODE" "--bundle=dir first"
expect_eq "BUNDLE aristotle-1.0.0 args: --no-pair" "$(calls)" "--bundle=dir removed from the args"
: > "$CASE/calls.log"
run_root_install --no-pair --bundle="$REPO/dist/aristotle-1.0.0" --no-gateway
expect_code 0 "$CODE" "--bundle=dir in the middle"
expect_eq "BUNDLE aristotle-1.0.0 args: --no-pair --no-gateway" "$(calls)" "other args kept in order"
: > "$CASE/calls.log"
run_root_install --bundle "$REPO/dist/aristotle-1.0.0" --bundle="$REPO/dist/aristotle-2.10.0"
expect_code 2 "$CODE" "--bundle and --bundle="
expect_eq "" "$(calls)" "bundle installer did not run"
run_root_install --bundle=/nonexistent
expect_code 1 "$CODE" "--bundle= missing dir"
expect_contains "$OUT" "does not exist" "said dir missing"
run_root_install --bundle=
expect_code 1 "$CODE" "--bundle= empty value"

echo "case: --bundle relative path from another cwd"
new_case relative
create_bundle 2.10.0
create_bundle 1.0.0
RUN_CWD="$CASE" run_root_install --bundle repo/dist/aristotle-1.0.0 --no-pair
expect_code 0 "$CODE" "relative --bundle"
expect_eq "BUNDLE aristotle-1.0.0 args: --no-pair" "$(calls)" "relative bundle ran"

echo "case: auto-detect works from another cwd"
new_case other_cwd
create_bundle 2.10.0
RUN_CWD="$CASE/elsewhere" run_root_install
expect_code 0 "$CODE" "from another cwd"
expect_eq "BUNDLE aristotle-2.10.0 args: " "$(calls)" "newest bundle ran"

echo "case: missing --bundle dir -> exit 1 with message"
new_case missing_bundle
run_root_install --bundle /nonexistent
expect_code 1 "$CODE" "missing bundle dir"
expect_contains "$OUT" "does not exist" "said dir missing"

echo "case: --bundle dir without install.sh -> exit 1 with message"
new_case no_install_in_bundle
mkdir -p "$REPO/dist/aristotle-2.10.0"
run_root_install --bundle "$REPO/dist/aristotle-2.10.0"
expect_code 1 "$CODE" "no install.sh"
expect_contains "$OUT" "install.sh is missing or not executable" "said install.sh missing"

echo "case: no bundle found -> exit 1 with message naming both ways out"
new_case no_bundle
run_root_install
expect_code 1 "$CODE" "no bundle"
expect_contains "$OUT" "scripts/release.sh needs the signing key" "said release.sh needs the signing key"
expect_contains "$OUT" "copy a release bundle folder to this PC and pass --bundle <dir>" "said to copy a bundle"
expect_contains "$OUT" "--bundle" "mentioned --bundle"

echo "case: --bundle twice -> exit 2, bundle not run"
new_case bundle_twice
create_bundle 2.10.0
run_root_install --bundle "$REPO/dist/aristotle-2.10.0" --bundle /other
expect_code 2 "$CODE" "duplicate --bundle"
expect_contains "$OUT" "usage" "usage error"
expect_eq "" "$(calls)" "bundle installer did not run"

echo "case: --bundle without value -> exit 2, bundle not run"
new_case bundle_no_value
create_bundle 2.10.0
run_root_install --bundle
expect_code 2 "$CODE" "--bundle no value"
expect_contains "$OUT" "usage" "usage error"
expect_eq "" "$(calls)" "bundle installer did not run"

echo "case: --help and -h -> exit 0 with usage"
new_case help
create_bundle 2.10.0
run_root_install --help
expect_code 0 "$CODE" "--help"
expect_contains "$OUT" "usage" "showed usage"
expect_contains "$OUT" "--pair, --no-pair, --no-gateway, --no-firewall" "listed the flags the bundle installer takes"
run_root_install -h
expect_code 0 "$CODE" "-h"
expect_contains "$OUT" "usage" "showed usage"
expect_eq "" "$(calls)" "bundle installer did not run"

echo "case: bundle installer's exit code is returned"
new_case exit_code
create_bundle 2.10.0
STUB_BUNDLE_EXIT_CODE=3 run_root_install
expect_code 3 "$CODE" "exit 3 from bundle"

echo "case: bundle dir is printed before exec"
new_case print_installing
create_bundle 2.10.0
run_root_install
expect_contains "$OUT" "Installing $REPO/dist/aristotle-2.10.0" "printed Installing line"

echo "case: running as root is refused"
new_case as_root
create_bundle 2.10.0
ID_DIR=id_root run_root_install
expect_code 1 "$CODE" "root refused"
expect_contains "$OUT" "not as root" "message says run as your user"
expect_eq "" "$(calls)" "bundle installer did not run"

finish root_install_test
