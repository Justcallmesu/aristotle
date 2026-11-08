# Releasing and installing

## Releasing (on the dev PC)
1. Set `aristotle.version=<x.y.z>` in `android/gradle.properties` and move the `[Unreleased]` entries in `CHANGELOG.md` under
   `## [<x.y.z>] - <YYYY-MM-DD>`. Commit; the tree must be clean.
2. `scripts/release.sh <x.y.z>` refuses unless the version is `x.y.z` (minor and patch at most 99), the tree is clean, the two
   versions match, the CHANGELOG heading exists and tag `v<x.y.z>` does not. It runs the full gate (`docs/v1-tickets/gates.sh`,
   logs in `dist/gate-<x.y.z>/`), then builds `dist/aristotle-<x.y.z>/`:
   `aristotle-<x.y.z>.apk` (assembleRelease), `aristotle-server` (linux-amd64, version and commit embedded), `install.sh`,
   `THIRD_PARTY_NOTICES.txt`, `RELEASE-NOTES.md` (that version's CHANGELOG section) and `SHA256SUMS`.
3. It refuses (and leaves no bundle) when the APK signer is not the pinned release certificate (`docs/release/spec.md`,
   `5af32f63…70bb`) or the binary's `version` line is not `aristotle-server <x.y.z> (<commit>) …`.
4. `scripts/release.sh --tag <x.y.z>` also creates the local annotated tag `v<x.y.z>` after everything passed. It never pushes,
   publishes or installs; push the tag yourself.

Signing: Gradle reads `~/.config/aristotle/signing.properties` (or the file named by `ARISTOTLE_SIGNING`). `dist/` is git-ignored.
Tests: `bash scripts/test/run.sh` (also a line of the gate).

## Installing on a PC (Linux, systemd user session)
Copy the whole `aristotle-<x.y.z>/` folder to the PC and run `./install.sh` in it (`--pair` shows the pairing QR at the end,
`--no-pair` skips the question). It:
- checks every file against `SHA256SUMS` and installs nothing if one does not match;
- copies the binary to `~/.local/bin/aristotle-server` (temp file + rename) and keeps the one it replaces as
  `aristotle-server.prev`;
- runs `aristotle-server install --force --start`: writes or updates the systemd user unit, restarts a running service or
  starts a stopped one;
- waits up to 30 s for `aristotle-server status` to exit 0; if it does not, it prints the last lines of
  `journalctl --user -u aristotle-server` and exits 1;
- prints the running version, the rollback command and where the APK is.

Rollback: `mv -f ~/.local/bin/aristotle-server.prev ~/.local/bin/aristotle-server && ~/.local/bin/aristotle-server install --force --start`.

The phone: copy `aristotle-<x.y.z>.apk` to it and open it. The first time you switch from the debug build, uninstall the debug
app first (another signing key, so Android refuses the update), then pair again (`aristotle-server pair`).
