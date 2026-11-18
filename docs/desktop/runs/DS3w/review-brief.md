# DS3w + DSI review brief (deep, fresh context)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D3, D6, D7, D13-D19, P3-P17),
`docs/desktop/design.md`, `docs/protocol.md` §22, the briefs and reports `docs/desktop/runs/DS3w/{build-brief,build-report}.md`
and `docs/desktop/runs/DSI/{build-brief,build-report}.md`, the deferred table DF1-DF6 in `docs/desktop/tickets.md`.
Scope: the uncommitted working tree under `desktop/**`, `docs/desktop/gates.sh`, `docs/design/desktop/tray-icons*`
(`git status --short`; `git diff` for tracked files). DS3w was built by two workers (the first died mid-gate; the second finished
it and also edited `desktop/tray/src/icons.rs` for clippy, outside its whitelist).

## Already proven by the PM (hunt elsewhere)
PM gate GREEN: `docs/desktop/runs/DS3w/pm-gate/gate-summary.txt` (135 Rust tests, UI ci/lint/typecheck/test/build, tray ldd
allowlist, app binary). PM mutants: M1 stamp kept after a failed stop -> KILLED (`a_failed_stop_deletes_the_stamp`, ...);
M3 webview default permission added -> KILLED (capabilities tests).

## PM finding to confirm (MAJOR candidate)
M2 (`desktop/app/src/logs.rs:87` `killpg(pgid, SIGKILL)` -> signal 0, i.e. the group is not killed) did NOT fail the test: it
**hung** >10 min until the PM killed the run. `LogStream::stop()` waits on the child with no bound (`running.child.wait()`), so
`stop_kills_the_child_and_its_group_and_reaps_it` never reaches its "< 3 s" assertion. Two problems: (a) a test that hangs instead
of failing makes the gate hang; (b) in production a child that ignores the kill would freeze the window's close path. Confirm,
and propose the fix (bounded wait in `stop()` + the test runs `stop()` on a thread with a deadline). The PM restored the mutated
line (verify `logs.rs:87` reads `killpg(pgid, SIGKILL);`).

## Check (highest stakes first)
1. **P4 security:** capabilities only the app's commands + event listen; CSP strict (no remote, no unsafe-eval/inline); no
   shell/fs/http/opener plugins anywhere in Cargo.toml or package.json; `withGlobalTauri` off; the frontend cannot reach arbitrary
   commands. The two commands added beyond the brief (`start_intent`, `terminal_status`): justified, read-only?
2. **P11:** the pairing code / `qr_payload` is never logged, stored, put in an event other than the `pair` reply, or kept in Rust
   state; CLI output never logged.
3. **D18 lifecycle:** the process exits when the window closes (read the code path; the worker could not test it live); poller only
   while the window is open; logs child killed on stop, window close and app exit; single-instance hand-off delivers `--pair` /
   `--confirm stop` to the running window (code path).
4. **P14/P15/DF6:** stamp before stop/restart, deleted on failure; actions refused while version-mismatched or not installed.
5. DF1-DF5 closed (gate per-binary check, ldd allowlist, grandchild stdout case, version re-ask, PermissionDenied).
6. UI shell vs design.md: tokens, fonts (Literata licence file present, size sane), shell 2, state light (shape + word), confirm
   dialog (focus on Cancel, Esc, focus trap), mismatch banner, not-installed / no-CLI states, EN/ID parity; the typed IPC client
   matches `command_list.rs`. Is everything DS4-DS6 need shared and reusable (so they can work in parallel on disjoint folders)?
7. **DSI:** icons.rs decode/convert correctness (ARGB order), the clippy edit by the DS3w worker (`as_chunks`) is correct; the tray
   stays libc-only; `make_icons.py` reproducible.
8. Gate cannot pass falsely (npm steps, per-binary counts, app binary present).
9. Code shape per PROJECT.md; app-side tests without recorded RED: are they meaningful (would they fail if the guarded code broke)?

## Output
`docs/desktop/runs/DS3w/review-report.md`: verdict APPROVED / APPROVED-WITH-FINDINGS / CHANGES-REQUIRED; findings
BLOCKER/MAJOR/MINOR/NIT with file:line + fix; a short "user live checks still needed" list. Read-only: never edit code, no git write
commands, do not launch the app or tray, never touch the server service. You may run cargo/npm tests (CARGO_TARGET_DIR=desktop/target).
