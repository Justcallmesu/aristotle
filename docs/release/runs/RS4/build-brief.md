# RS4 — release bundle: `scripts/release.sh` + `install.sh`

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/release/spec.md` (Step 1, signing key section),
`docs/release/runs/RS2/review-report.md` (the RS3 minor + the two "for RS4" notes), `scripts/build-server.sh`, `scripts/licences.sh`,
`server/internal/app/{app,cli,servicecmds}.go`, `server/internal/service/service.go`, `docs/protocol.md` §11 (service/CLI), `CHANGELOG.md`
(format; the What's-new reader needs a released `## [x.y.z] - date` heading), `android/gradle.properties` (`aristotle.version`),
`android/app/build.gradle.kts` (release signing; the signing file lives outside git — NEVER read, print or copy it).

## Do
1. **`scripts/release.sh <x.y.z>`** (bash, `set -euo pipefail`): refuse unless the tree is clean, `<x.y.z>` is semver, `android/gradle.properties`
   `aristotle.version` == `<x.y.z>`, CHANGELOG has `## [<x.y.z>] - <date>`, and tag `v<x.y.z>` does not exist yet. Runs the full gate
   (`docs/v1-tickets/gates.sh`), then builds into `dist/aristotle-<x.y.z>/`: `aristotle-<x.y.z>.apk` (assembleRelease), `aristotle-server`
   (linux-amd64, version `<x.y.z>` + commit embedded; adapt `build-server.sh` to accept an explicit version rather than duplicating it),
   `install.sh`, `THIRD_PARTY_NOTICES.txt`, `RELEASE-NOTES.md` (that version's CHANGELOG section), `SHA256SUMS`. It verifies the APK signer
   SHA-256 equals the pinned `5af32f63…70bb` value from the spec (full value in one constant) and the binary's `version` output, and refuses
   otherwise. It creates the local annotated tag `v<x.y.z>` **only with `--tag`**; it never pushes, never publishes, never installs.
   `dist/` is git-ignored.
2. **`install.sh`** (shipped in the bundle; runs from its own dir): checks `SHA256SUMS`; copies the binary to `~/.local/bin/aristotle-server`
   (atomic: temp file + rename; keeps the previous binary as `aristotle-server.prev` for a manual rollback, and prints the rollback command);
   runs `aristotle-server install --force` (fresh install or update + restart via RS3); polls `aristotle-server status` until exit 0 or 30 s
   (the socket comes up after start), and on timeout prints the last lines of `journalctl --user -u <unit>` and exits non-zero; prints the
   version; then offers the pairing QR (`aristotle-server pair`, only when asked: `--pair` flag or a y/N prompt when interactive). Tells the user
   where the APK is and that the first switch from the debug app needs an uninstall (re-pair).
3. **RS3 minor:** when the restart after a rewritten unit fails, `install` says the unit was updated, that the server is likely down, and the
   journalctl command to look (server code + test).
4. **Tests without the real service:** `install.sh` is tested in a temp `HOME` with a stub `aristotle-server` and stub `journalctl` first on
   `PATH` (cases: fresh install, update keeps `.prev`, bad checksum refuses before copying, status never comes up -> non-zero + journal tail).
   `release.sh`'s refusals are tested (dirty tree / version mismatch / missing CHANGELOG heading / existing tag / wrong signer: use a
   throwaway keystore via `ARISTOTLE_SIGNING` in a scratch copy) — put these shell tests under `scripts/test/` with one runner, and add the
   runner to `docs/v1-tickets/gates.sh` as its own PASS/FAIL line.
5. Docs: a short "Releasing" + "Installing on a PC" section (README or `docs/release/README.md`), and protocol §11 for the install message.

## Hard rules
Files: `scripts/**`, `.gitignore`, `server/internal/app/**`, `server/internal/service/**`, `docs/protocol.md`, `docs/v1-tickets/gates.sh`,
`docs/release/README.md` (or README.md's release section), `docs/release/runs/RS4/build-report.md`. NOT `android/**`, `CHANGELOG.md` (Doc text
line in the report). **Forbidden:** running `release.sh` or `install.sh` against the real repo HEAD/tag or the real HOME; `systemctl`,
`aristotle-server install|uninstall|start|stop|pair` for real, anything writing `~/.local/bin`, `~/.config/systemd`, `~/.config/aristotle`;
reading `~/.config/aristotle/*`; `git tag`/`push` in the real repo; adb. Scratch copies for everything that builds or tags. No commits; never
`git checkout/restore/stash/reset`. Avoid `bash -c`/heredocs/chained `$(...)` in YOUR shell commands (the scripts you write may use what bash
offers). Gate after your LAST edit: `bash docs/v1-tickets/gates.sh docs/release/runs/RS4` (full: server + android + licences + the new script
tests) exits 0.

## Stop and ask if
The pairing QR cannot be printed by a CLI command today; the version embedding needs a server change outside the files above.

## Report
`docs/release/runs/RS4/build-report.md`, ≤ 15 lines: what each script does, test cases, gate line, what was NOT exercised for real.
