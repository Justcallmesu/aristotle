# DS3x fix brief — DS3w review findings, before the views (DS4-DS6)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Input: `docs/desktop/runs/DS3w/review-report.md` (read in
full: file:line + fixes), spec `docs/desktop/spec.md` P15, P17, **P18** (new), `docs/desktop/design.md`. DS3w is committed (feea3ae).

## Fix (each with a test that fails on the current code; RED first against a scratch copy, never git)
1. **MAJOR-1** `desktop/app/src/logs.rs:90-91`: `LogStream::stop()` waits at most ~2 s (`try_wait` loop), then `child.kill()`, then
   hands the child + reader to a detached reaper thread; never blocks the caller unbounded. The test runs `stop()` on a thread with a
   3 s deadline and FAILS (not hangs) when the group is not killed. Gate: wrap `cargo test --workspace` (and the npm test step) in
   `timeout 900` in `docs/desktop/gates.sh`, recording a timeout as FAIL.
2. **MINOR-1** `logs.rs:53,69`: concurrent `logs_start` swaps the slot atomically and stops the previous stream (no orphan, no
   duplicate lines). Test with two starts.
3. **MINOR-2** `gates.sh`: `MIN_TESTS` = the real new total; the per-binary check includes the tray's unit tests and the app's tests.
4. **MINOR-3** `desktop/app/src/service.rs:46-50`: keep the stamp when the CLI call TIMES OUT (the stop may still finish; the stamp
   expires after 60 s); delete it only on a definite failure (error reply, non-ok result, binary missing). Tests for both.
5. **MINOR-4** shared UI pieces DS4-DS6 will all use (so they never edit shared folders): a `NeedsServer` empty state (design.md
   "needs the running server" state), a `CommandFailed` banner (design.md `act_failed_t`/`act_failed_b`, server message below in
   mono), and a `useCommand` hook (runs an IPC command, tracks working/succeeded/failed, disables while working, returns the error
   kind + message). Under `desktop/ui/src/components/` and `desktop/ui/src/state/`, with tests.
6. **MINOR-5** carry terminal `enabled` (and anything else the sidebar needs) in the core snapshot (`desktop/core/src/snapshot.rs`
   ~264-268) and drop the sidebar's extra `terminal status` call (`desktop/ui/src/.../Sidebar.tsx:28-46`).
7. **P18**: on a version mismatch the status footer is red with the mismatch word (banner stays). Test.
8. NITs from the review that are one-liners in files you touch (pin the single-instance plugin version; hand-off arriving while the
   window loads is queued, not lost; snapshot events carry a sequence number and the UI ignores older ones). List the rest.

## Files you may create/edit
`desktop/app/**`, `desktop/ui/**`, `desktop/core/**`, `desktop/Cargo.toml`, `desktop/Cargo.lock`, `docs/desktop/gates.sh`.

## Verify
`bash docs/desktop/gates.sh docs/desktop/runs/DS3x/self-gate` all PASS; paste the summary verbatim. Wrap any test command you run
by hand in `timeout`.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No system installs. No app/tray launch this round. Never run
aristotle-server against the real service. No skill/memory edits.

## Report
`docs/desktop/runs/DS3x/build-report.md`: per item change (file:line), test names, RED evidence, gate summary, NITs left.
