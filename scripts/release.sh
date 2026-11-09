#!/usr/bin/env bash
# Build the release bundle dist/aristotle-<x.y.z>/. Usage: scripts/release.sh [--tag] <x.y.z>
# Refuses unless: <x.y.z> is semver (minor and patch <= 99, see versionCode), the git tree is clean,
# android/gradle.properties aristotle.version == <x.y.z>, CHANGELOG.md has `## [<x.y.z>] - YYYY-MM-DD`, tag v<x.y.z> does not exist.
# Then: full gate (docs/v1-tickets/gates.sh, logs in dist/gate-<x.y.z>/), assembleRelease, server build (linux-amd64),
# bundle = APK, aristotle-server, install.sh, THIRD_PARTY_NOTICES.txt, RELEASE-NOTES.md, SHA256SUMS.
# Verifies the APK signer against the pinned certificate and the binary's `version` line, else refuses.
# --tag: afterwards create the local annotated tag v<x.y.z>. Never pushes, publishes or installs anything.
# Signing: Gradle reads ~/.config/aristotle/signing.properties, or the file named by ARISTOTLE_SIGNING.
set -euo pipefail

# Release signing certificate SHA-256 (docs/release/spec.md, "Release signing key"). The one place it is written.
PINNED_SIGNER_SHA256=5af32f639d8c98e12c18e0f093e8dac26b909d0fbd4c593dcfa120245fde70bb

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { echo "release: $*" >&2; exit 1; }

TAG=no
if [[ "${1:-}" == --tag ]]; then TAG=yes; shift; fi
[[ $# -eq 1 ]] || { echo "usage: release.sh [--tag] <x.y.z>" >&2; exit 2; }
VERSION="$1"
BUNDLE="$ROOT/dist/aristotle-$VERSION"
STAGE="$ROOT/dist/.aristotle-$VERSION.partial" # built here, renamed to BUNDLE only when every check passed
GATE_DIR="$ROOT/dist/gate-$VERSION"

check_semver() {
  [[ "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] ||
    die "'$VERSION' is not a release version x.y.z (no leading zeros, no suffix; minor and patch at most 99)"
}

check_clean_tree() {
  local dirty
  dirty="$(git -C "$ROOT" status --porcelain --untracked-files=normal)"
  [[ -z "$dirty" ]] || die "the git tree is not clean; commit or remove these first:"$'\n'"$dirty"
}

check_app_version() {
  local have
  have="$(sed -n 's/^aristotle\.version=//p' "$ROOT/android/gradle.properties" | tr -d '[:space:]')"
  [[ "$have" == "$VERSION" ]] || die "android/gradle.properties has aristotle.version=$have, not $VERSION"
}

changelog_heading() { # prints the line number of `## [VERSION] - date`, or nothing
  grep -nE "^## \[${VERSION//./\\.}\] - [0-9]{4}-[0-9]{2}-[0-9]{2}[[:space:]]*$" "$ROOT/CHANGELOG.md" | head -n 1 | cut -d: -f1
}

check_changelog() {
  [[ -n "$(changelog_heading)" ]] || die "CHANGELOG.md has no released heading '## [$VERSION] - YYYY-MM-DD'"
}

check_no_tag() {
  if git -C "$ROOT" rev-parse -q --verify "refs/tags/v$VERSION" > /dev/null; then
    die "tag v$VERSION already exists; a released version is never rebuilt (bump the version)"
  fi
}

find_apksigner() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" tool
  if [[ -z "$sdk" && -f "$ROOT/android/local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/android/local.properties")"
  fi
  sdk="${sdk:-$HOME/Android/Sdk}"
  tool="$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -n 1 || true)"
  [[ -n "$tool" ]] || die "apksigner not found under $sdk/build-tools (set ANDROID_HOME)"
  echo "$tool"
}

run_gate() {
  echo "release: running the full gate (logs in $GATE_DIR)"
  bash "$ROOT/docs/v1-tickets/gates.sh" "$GATE_DIR" || die "the gate failed; see $GATE_DIR/gate-summary.txt"
}

build_apk() {
  (cd "$ROOT/android" && ./gradlew --no-daemon -q assembleRelease) || die "assembleRelease failed"
  cp "$ROOT/android/app/build/outputs/apk/release/app-release.apk" "$STAGE/aristotle-$VERSION.apk"
}

build_server() {
  GOOS=linux GOARCH=amd64 bash "$ROOT/scripts/build-server.sh" --version "$VERSION" "$STAGE/aristotle-server"
}

write_release_notes() { # the CHANGELOG section from its heading up to the next `## [`
  local start
  start="$(changelog_heading)"
  awk -v start="$start" 'NR == start { print; next } NR > start && /^## \[/ { exit } NR > start { print }' \
    "$ROOT/CHANGELOG.md" > "$STAGE/RELEASE-NOTES.md"
}

verify_signer() {
  local apksigner="$1" out digests
  out="$("$apksigner" verify --print-certs "$STAGE/aristotle-$VERSION.apk" 2>&1)" || die "apksigner verify failed:"$'\n'"$out"
  digests="$(sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: *//p' <<< "$out" | tr 'A-F' 'a-f' | tr -d ': ')"
  [[ "$digests" == "$PINNED_SIGNER_SHA256" ]] ||
    die "APK signer is not the release key: got '${digests//$'\n'/, }', want $PINNED_SIGNER_SHA256. Check ARISTOTLE_SIGNING / ~/.config/aristotle/signing.properties"
  echo "release: APK signer OK ($PINNED_SIGNER_SHA256)"
}

verify_server_version() {
  local commit line
  commit="$(git -C "$ROOT" rev-parse --short HEAD)"
  line="$("$STAGE/aristotle-server" version)" || die "aristotle-server version failed"
  [[ "$line" == "aristotle-server $VERSION ($commit) protocol "* ]] ||
    die "aristotle-server version says '$line', want 'aristotle-server $VERSION ($commit) protocol N'"
  echo "release: $line"
}

write_checksums() {
  (cd "$STAGE" && sha256sum "aristotle-$VERSION.apk" aristotle-server install.sh THIRD_PARTY_NOTICES.txt RELEASE-NOTES.md > SHA256SUMS)
}

check_semver
check_clean_tree
check_app_version
check_changelog
check_no_tag
APKSIGNER="$(find_apksigner)"
run_gate
rm -rf "$STAGE"
mkdir -p "$STAGE"
trap 'rm -rf "$STAGE"' EXIT # a refused build leaves no bundle (no wrongly signed APK lying around)
build_apk
verify_signer "$APKSIGNER"
build_server
verify_server_version
cp "$ROOT/scripts/install.sh" "$STAGE/install.sh"
chmod 0755 "$STAGE/install.sh"
cp "$ROOT/server/THIRD_PARTY_NOTICES.txt" "$STAGE/THIRD_PARTY_NOTICES.txt"
chmod 0644 "$STAGE/THIRD_PARTY_NOTICES.txt"
write_release_notes
chmod 0644 "$STAGE/RELEASE-NOTES.md"
write_checksums
chmod 0644 "$STAGE/SHA256SUMS"
# Bundle modes: executables 755, everything else 644 (cp keeps a source's restrictive mode, e.g. a 600 notices file).
chmod 0755 "$STAGE/aristotle-server"
chmod 0644 "$STAGE/aristotle-$VERSION.apk"
check_clean_tree # the build must not have changed tracked files
rm -rf "$BUNDLE"
mv "$STAGE" "$BUNDLE"
trap - EXIT
echo "release: bundle ready in $BUNDLE"
if [[ "$TAG" == yes ]]; then
  git -C "$ROOT" tag -a "v$VERSION" -m "Aristotle $VERSION"
  echo "release: created the local tag v$VERSION (not pushed)"
else
  echo "release: no tag created (rerun with --tag to tag v$VERSION locally)"
fi
