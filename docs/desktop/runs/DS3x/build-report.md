# DS3x build report

All 8 brief items done; gate green. RED method: tests written first against the unchanged code in the real tree (no git, no
stash), output seen failing, then the fix. Where the unit under test did not exist yet (new components, `PendingIntent`) RED is the
missing module. Two tests that passed on first run were re-checked by mutating the fix (listed). Every test run was wrapped in `timeout`.

## Items
1. **MAJOR-1** `desktop/app/src/logs.rs` (`stop_running`, `wait_until`, `STOP_WAIT` 2 s): killpg, `try_wait` loop in 10 ms steps,
   then `child.kill()`, then the child and reader go to a detached reaper thread. `stop()` never blocks unbounded.
   - Test `stop_returns_in_bounded_time_when_a_process_outside_the_group_holds_the_pipe`: the child starts a `setsid sleep` that keeps
     stdout open; `stop()` runs on a thread, `recv_timeout(3 s)` FAILS rather than hangs.
   - RED: `panicked at app/src/logs.rs:244: stop() did not return within 3 s` (old code blocked in `reader.join()`). The scratch
     `setsid` process is killed by the test.
   - Gate (`docs/desktop/gates.sh`): `bounded()` = `timeout $STEP_TIMEOUT(900)` around `cargo test --workspace` and `npm test`; a
     timeout is recorded `FAIL <step> (124)`. Checked by running `run_step` with `STEP_TIMEOUT=1` on `sleep 30`: `FAIL hang (124)`.
2. **MINOR-1** `logs.rs` `start`: `lock(&running).replace(new)` and the previous stream goes through `stop_running`.
   - Test `two_starts_at_once_leave_exactly_one_stream_running` (barrier, 2 threads, 5 rounds, counts live pids).
   - RED (old code): `timed out waiting for exactly one child alive`. Re-checked after a rewrite of the assertion by putting the plain
     assignment back: `round 0: pids ["26675", "26676"]` (both alive).
3. **MINOR-2** `gates.sh`: `MIN_TESTS=145` (real total, counted), new `MIN_UI_TESTS=67` with a `ui-test-count` step (from
   "Tests N passed"), and `unittests src/main.rs|aristotle_tray` (19) added to `REQUIRED_TEST_BINARIES`. The app's `aristotle_desktop`
   (39) was already listed. Not added: `aristotle_core` unit tests (0 tests there).
4. **MINOR-3** `desktop/app/src/service.rs` `definitely_failed()`: the stamp is deleted on `Server`, `BadOutput`, `NotInstalled`, `Io`,
   `ok:false`; kept on `Timeout`.
   - Tests: `a_stop_that_times_out_keeps_the_stamp` (RED: panic at service.rs:134, stamp gone), plus `a_stop_that_cannot_run_the_binary_deletes_the_stamp`
     (now `NotInstalled`), `a_stop_with_unreadable_output_deletes_the_stamp`, `a_stop_that_answers_not_ok_deletes_the_stamp`,
     `a_failed_stop_deletes_the_stamp` (kept). The three new "deletes" tests pass on old code by design (they pin the definite-failure side).
5. **MINOR-4** shared UI, one folder each so DS4-DS6 edit only `views/<view>/`:
   - `components/NeedsServer.tsx`: needs_title/needs_body; Start only when installed, no mismatch and service != active; Start runs through
     `useCommand`, shows `Progress` + `working_start` and the failure banner.
   - `components/CommandFailed.tsx`: `role=alert` banner, `act_failed_t/_b`, `CodeBlock` `error: <message ?? kind>`.
   - `state/useCommand.ts`: `{run, working, succeeded, failed, result, error, reset}`; ignores a second run while working; maps non-IPC throws to
     `internal`; clears on the next run and on a view change; no state update after unmount. A resolved `{ok:false}` counts as success (the view reads `result`).
   - Tests: `components/shared.test.tsx` (7), `state/useCommand.test.tsx` (8). RED: both modules missing.
6. **MINOR-5** `core/src/snapshot.rs`: `Snapshot.terminal_enabled: Option<bool>` from the same `terminal status` call (`fetch_terminal`);
   `core/src/poller.rs` keeps the last value on a failed read like `terminals`; `app/src/dto.rs` `terminal_enabled`; UI `ipc/types.ts`;
   `Sidebar.tsx` no longer calls `terminal_status` (`useTerminalEnabled` deleted); unknown (`null`) shows no count.
   - Tests: core `poll_running_fetches_version_devices_terminals` (RED: `left: None, right: Some(true)`), `poll_terminal_failure_leaves_the_switch_unknown`,
     app `the_snapshot_carries_the_terminal_switch` (RED same), UI "takes the Terminal count from the snapshot and asks nothing extra" and
     "shows no Terminal count when the switch is unknown".
7. **P18** `state/world.ts` `footerState()` used by `StatusFooter.tsx`: mismatch = red light + `mm_title` word (no new string key: the strings
   test compares to design.md, which is outside my whitelist). Banner unchanged. Test "turns the status footer red with the mismatch word (P18)";
   RED: footer text stayed "Server running". `world.light`/`row` stay per design.md rows (headline in views unchanged).
8. **NITs fixed**
   - NIT 7: `tauri-plugin-single-instance = "=2.5.5"` (`app/Cargo.toml`; `Cargo.lock` already at 2.5.5, unchanged).
   - NIT 2: `app/src/args.rs` `PendingIntent::{new,take,hand_off}`; a hand-off before the window has called `start_intent` is queued
     (newest wins), after that it is emitted and not kept; `main.rs` and `ipc.rs` use it. Tests `a_hand_off_before_the_window_reads_its_intent_is_queued_not_emitted`,
     `a_hand_off_after_the_window_read_its_intent_is_emitted_and_not_kept`.
   - NIT 3: `SnapshotDto.seq` taken in `Backend::refresh` under the snapshotter lock, `latest` stored with it; `useSnapshot` keeps the higher
     `seq` (events and the first read). Tests `each_poll_gets_a_higher_sequence_number_and_current_repeats_the_last` (RED by mutation: `1 then 1`),
     UI `snapshot.test.tsx` (2; RED: stayed at seq 3). The existing "updates the footer from a snapshot event" test now emits `seq: 2`.

## Gate (`bash docs/desktop/gates.sh docs/desktop/runs/DS3x/self-gate`, exit 0)
```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=67
PASS ui-test-count (67)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=145 failed=0
PASS desktop-test-count (145)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```
Totals: Rust 145 (was 135), Vitest 67 (was 46).

## NITs left
- NIT 1 (UI re-derives the 4.1 table instead of reading `assessment`): left; a PM design question, P18 handled only at the footer.
- NIT 4 (logs child orphaned on app SIGKILL; needs `PR_SET_PDEATHSIG` from a dedicated thread), NIT 5 (confirm-once test does not hit the guard),
  NIT 6 (tray decodes PNGs per call; tray files outside this brief's whitelist), NIT 8 (stale comment/test about `Other`), NIT 9 (`kill_group` after reap): not touched.

## Notes
- No git writes, no app/tray launch, server not touched. `desktop/target` and `desktop/ui/node_modules` rebuilt by the gate only.
- Still open from the review, user live checks (window close, hand-off focus, tray icons) are unchanged by this round.
