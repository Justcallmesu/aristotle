#!/usr/bin/env bash
# Tests for scripts/release.sh, run on a scratch copy of the working tree (its own git repo, temp HOME,
# throwaway keystore via ARISTOTLE_SIGNING). The real repo is only read (git ls-files); no tag or dist is made there.
# In the scratch repo the gate is a stub (the real gate runs this test: no recursion) and the pinned signer is
# swapped for the throwaway one only for the last, successful run.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
source "$HERE/lib.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/release-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
S="$WORK/repo"
KEYS="$WORK/keys"
REAL_TAGS="$(git -C "$ROOT" tag -l)"

# Caches stay the real ones (offline builds); everything else HOME-relative goes to a temp HOME.
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GOCACHE="$(go env GOCACHE)"; GOMODCACHE="$(go env GOMODCACHE)"; GOPATH="$(go env GOPATH)"
export GOCACHE GOMODCACHE GOPATH
if [[ -z "${ANDROID_HOME:-}" && -f "$ROOT/android/local.properties" ]]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT/android/local.properties")"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export HOME="$WORK/home"
mkdir -p "$HOME" "$S" "$KEYS"

make_scratch() {
  (cd "$ROOT" && git ls-files -z --cached --others --exclude-standard |
    tar --null --ignore-failed-read -T - -cf - 2> /dev/null) | tar -xf - -C "$S"
  printf '#!/usr/bin/env bash\necho "stub gate (release_test)"\nexit "${RELEASE_TEST_GATE_EXIT:-0}"\n' > "$S/docs/v1-tickets/gates.sh"
  git -C "$S" init -q
  git -C "$S" config user.email release-test@example.invalid
  git -C "$S" config user.name release-test
  git -C "$S" config commit.gpgsign false
  git -C "$S" config tag.gpgsign false
  commit "scratch copy"
}

commit() { git -C "$S" add -A && git -C "$S" commit -q -m "$1"; }

make_keystore() {
  keytool -genkeypair -keystore "$KEYS/throwaway.jks" -storetype PKCS12 -alias throwaway -keyalg RSA -keysize 2048 \
    -validity 2 -dname CN=Throwaway -storepass throwaway-pass -keypass throwaway-pass -noprompt > /dev/null 2>&1
  printf 'storeFile=%s\nstorePassword=throwaway-pass\nkeyAlias=throwaway\nkeyPassword=throwaway-pass\n' \
    "$KEYS/throwaway.jks" > "$KEYS/signing.properties"
  export ARISTOTLE_SIGNING="$KEYS/signing.properties"
  THROWAWAY_SHA256="$(keytool -exportcert -keystore "$KEYS/throwaway.jks" -alias throwaway -storepass throwaway-pass 2> /dev/null |
    sha256sum | cut -d' ' -f1)"
}

run_release() { # args... ; sets CODE, OUT
  CODE=0
  OUT="$(bash "$S/scripts/release.sh" "$@" 2>&1)" || CODE=$?
}

expect_no_bundle() {
  if compgen -G "$S/dist/aristotle-*" > /dev/null || compgen -G "$S/dist/.aristotle-*" > /dev/null; then
    fail "$1: a bundle was left in dist/"
  else
    pass "$1: no bundle"
  fi
}

refused() { # what needle args...
  local what="$1" needle="$2"; shift 2
  run_release "$@"
  expect_code 1 "$CODE" "$what"
  expect_contains "$OUT" "$needle" "$what: says why"
  expect_no_bundle "$what"
}

set_app_version() { sed -i "s/^aristotle\.version=.*/aristotle.version=$1/" "$S/android/gradle.properties"; }

make_scratch
make_keystore

echo "case: refusals before anything is built"
run_release; expect_code 2 "$CODE" "no version argument"
refused "not semver" "is not a release version" 1.0
refused "leading zero" "is not a release version" 1.01.0
touch "$S/stray-file"
refused "dirty tree (untracked file)" "the git tree is not clean" 1.0.0
rm "$S/stray-file"
cp "$S/CHANGELOG.md" "$WORK/changelog.orig"
echo "edit" >> "$S/CHANGELOG.md"
refused "dirty tree (modified file)" "the git tree is not clean" 1.0.0
cp "$WORK/changelog.orig" "$S/CHANGELOG.md"
refused "version mismatch" "aristotle.version=1.0.0, not 1.0.1" 1.0.1
set_app_version 1.0.1; commit "bump to 1.0.1"
refused "missing CHANGELOG heading" "CHANGELOG.md has no released heading '## [1.0.1] - YYYY-MM-DD'" 1.0.1
set_app_version 1.0.0; commit "back to 1.0.0"
git -C "$S" tag v1.0.0
refused "existing tag" "tag v1.0.0 already exists" 1.0.0
git -C "$S" tag -d v1.0.0 > /dev/null
RELEASE_TEST_GATE_EXIT=1 refused "gate fails" "the gate failed" 1.0.0

echo "case: wrong signer (throwaway key, real pin) is refused after the build"
refused "wrong signer" "APK signer is not the release key: got '$THROWAWAY_SHA256'" 1.0.0
expect_contains "$OUT" "stub gate (release_test)" "the gate ran before the build"
expect_no_file "$S/dist/aristotle-1.0.0" "no bundle after a wrong signer"

echo "case: refusals after the build (pin swapped to the throwaway cert in the scratch copy only)"
sed -i "s/^PINNED_SIGNER_SHA256=.*/PINNED_SIGNER_SHA256=$THROWAWAY_SHA256/" "$S/scripts/release.sh"
commit "test: pin the throwaway signer"
# A fake SDK: the real one, symlinked, plus an apksigner that reports two signers (always the newest build-tools).
FAKE_SDK="$WORK/fake-sdk"
mkdir -p "$FAKE_SDK/build-tools/99.0.0"
for e in "$ANDROID_HOME"/*; do [[ "$(basename "$e")" == build-tools ]] || ln -s "$e" "$FAKE_SDK/"; done
for e in "$ANDROID_HOME"/build-tools/*; do ln -s "$e" "$FAKE_SDK/build-tools/"; done
printf '#!/usr/bin/env bash\necho "Signer #1 certificate SHA-256 digest: %s"\necho "Signer #2 certificate SHA-256 digest: %s"\n' \
  "$THROWAWAY_SHA256" "$THROWAWAY_SHA256" > "$FAKE_SDK/build-tools/99.0.0/apksigner"
chmod +x "$FAKE_SDK/build-tools/99.0.0/apksigner"
REAL_SDK="$ANDROID_HOME"
ANDROID_HOME="$FAKE_SDK" refused "two signers" "APK signer is not the release key" 1.0.0
ANDROID_HOME="$REAL_SDK"

# The scratch repo builds the server with a stub (see stubs/build-server.sh).
cp "$HERE/stubs/build-server.sh" "$S/scripts/build-server.sh"
commit "test: stub server build"
BUILD_STUB_VERSION="aristotle-server 0.0.0 (abc) protocol 1" refused "wrong server version line" "aristotle-server version says 'aristotle-server 0.0.0 (abc) protocol 1'" 1.0.0
SHORT_STUB="$(git -C "$S" rev-parse --short HEAD)"
BUILD_STUB_VERSION="aristotle-server 1.0.0 ($SHORT_STUB) protocol 1" BUILD_STUB_DIRTY=1 refused "build dirtied the tree" "the git tree is not clean" 1.0.0
cp "$ROOT/scripts/build-server.sh" "$S/scripts/build-server.sh"
commit "test: real server build again"

echo "case: a good run (throwaway pin) builds the bundle and tags"
SHORT="$(git -C "$S" rev-parse --short HEAD)"
run_release --tag 1.0.0
expect_code 0 "$CODE" "good run"
B="$S/dist/aristotle-1.0.0"
expect_eq "RELEASE-NOTES.md SHA256SUMS THIRD_PARTY_NOTICES.txt aristotle-1.0.0.apk aristotle-server install.sh " \
  "$(LC_ALL=C ls -A "$B" | tr '\n' ' ')" "bundle has exactly the six files"
expect_eq 5 "$(wc -l < "$B/SHA256SUMS")" "SHA256SUMS lists the five other files"
expect_contains "$(cd "$B" && sha256sum --check --strict SHA256SUMS 2>&1)" "aristotle-server: OK" "SHA256SUMS verifies"
expect_contains "$("$B/aristotle-server" version)" "aristotle-server 1.0.0 ($SHORT) protocol " "binary carries version and commit"
expect_contains "$(head -n 1 "$B/RELEASE-NOTES.md")" "## [1.0.0] - 2026-11-04" "release notes start at the version heading"
expect_not_contains "$(tail -n +2 "$B/RELEASE-NOTES.md")" "## [" "release notes hold that one section only"
expect_same "$S/scripts/install.sh" "$B/install.sh" "install.sh shipped"
[[ -x "$B/install.sh" ]] && pass "bundled install.sh is executable" || fail "bundled install.sh is not executable"
expect_same "$S/server/THIRD_PARTY_NOTICES.txt" "$B/THIRD_PARTY_NOTICES.txt" "server notices shipped"
expect_contains "$(git -C "$S" cat-file -t v1.0.0 2>&1)" "tag" "annotated tag v1.0.0 created"
[[ -z "$(git -C "$S" status --porcelain)" ]] && pass "tree still clean after the build" || fail "the build changed the tree"
run_release 1.0.0
expect_code 1 "$CODE" "rerun after tagging"
expect_contains "$OUT" "tag v1.0.0 already exists" "rerun after tagging: says why"
expect_file "$B/aristotle-1.0.0.apk" "the tagged bundle is left alone"

[[ "$(git -C "$ROOT" tag -l)" == "$REAL_TAGS" ]] && pass "real repo tags unchanged" || fail "real repo tags changed"
finish release_test
