# RS4 (+ RS2b) — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `e4e9b1a`. Review the uncommitted RS4 diff (`git status`; new `scripts/release.sh`,
`scripts/install.sh`, `scripts/test/**`, `docs/release/README.md`; changed `scripts/build-server.sh`, `server/internal/service/**`,
`server/internal/app/servicecmds_test.go`, `docs/protocol.md` §11.1, `docs/v1-tickets/gates.sh`, `.gitignore`) AND the committed RS2b
(`git show b5c7d14`, licence notices fix; not separately reviewed). Inputs: `docs/release/spec.md`, `runs/RS4/{build-brief,build-report}.md`,
`runs/RS2/review-report.md` (MAJOR-1 and the RS4 notes RS2b/RS4 should close).

## Already checked by the PM (`docs/release/runs/pm-gate-rs4/pm-check.log`)
Full gate run; mutants: release.sh accepting any signer; install.sh skipping the checksum check.

## Attack list
install.sh runs on the user's real PC: what happens on (a) the very first install (no service, no `~/.local/bin`), (b) an update while the
phone is connected, (c) a binary that starts and crashes, (d) running it twice, (e) running it from another cwd or a path with spaces,
(f) `--start` on an existing STOPPED install the user stopped on purpose (is starting it acceptable? say so), (g) rollback with `.prev` really
works (service unit points at `~/.local/bin/aristotle-server`?). Atomic copy on the same filesystem? Checksums covering every bundle file
including install.sh itself (and what that check is worth). release.sh: can it produce a bundle from a dirty tree, a stale APK from an earlier
build (does it clean or use the fresh output path), the wrong version in the binary; is the signer pin really compared against the APK in
the bundle; secrets never echoed (`set -x`, error text). Tests: do they guard behaviour or their own stubs; the stub-gate in release_test.
RS2b: Public Suffix List entry + the "unlisted bundled asset" guard (is it robust to an asset nested in a jar, not an aar?).

## Rules
Read-only: write ONLY `docs/release/runs/RS4/review-report.md`. Run scripts only on scratch copies with a temp HOME and stubs; never the real
HOME, systemd, `~/.local/bin`, `~/.config/aristotle` (never read it), adb; never `git tag/push/commit/checkout/restore/stash/reset` in the real
repo. Avoid `bash -c`/heredocs in your own commands. Report: Verified, Findings, `VERDICT: ...`. Under ~35 lines.
