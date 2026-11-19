# DFS build report (NIT sweep DF7 + DF8 + DF9)

No git write command, no install, no app/tray launch, `aristotle-server` never run. The only processes started were test children (all gone: `pgrep` empty). Red evidence: tests were run before the fix, or the fix was reverted as a mutant (no git). Plan: `plan.md`.

## Items

| Id | Status | Where | Test (seen failing first) |
|---|---|---|---|
| DF7-a `try_wait` Err zombie | DONE | `core/src/runner.rs:203-208` kill group + `wait` | none: `try_wait` cannot be made to fail |
| DF7-b stamp read/delete race | DONE | `core/src/stamp.rs:63` `delete_if`; `core/src/poller.rs:35-40` | `delete_if_leaves_a_stamp_written_after_the_read` (mutant: unconditional delete -> red) |
| DF7-c unparsable stamp | DONE | `core/src/stamp.rs:39-46` `read` deletes a file with no number | `an_unparsable_stamp_is_deleted_by_the_next_tick` (red first) |
| DF7-d stale strings comments | DONE (comments) | `core/src/strings.rs` header + each `Key` variant names its design key | none |
| DF7-e design.md Surface labels / 3 Rust rows / "189 keys" | NOT DONE | design.md is read-only here | PM, see below |
| DF7-f design.md §6 wording | NOT DONE | same | PM |
| DF7-g Start enabled while starting | DONE | `tray/src/starting.rs` (new), `tray/src/tray.rs` (`start_server`, `item`) | `starting::tests::a_second_start_is_refused_until_the_first_ends` (mutant -> red) |
| DF7-h Hermes swap wording | NOT DONE | `core/src/diff.rs` `hermes_went_down` | PM |
| DF7 NIT-3 markup escape | DONE | `core/src/strings.rs:262` `markup_safe`, `:308` `named` | `device_names_are_escaped_for_the_notification_markup` (red first) |
| DF7 NIT-5 `non_empty_env` dup | DONE | `core/src/runner.rs:163` pub, `stamp.rs:30` uses it (launcher already used `absolute_env_path`) | refactor, existing tests |
| DF7 NIT-6 `ErrorBody` | DONE: type and its only test dropped | `core/src/types.rs`, `tests/fixtures.rs` | `parse_output` tests still cover `{"error"}` |
| DF7 NIT-7 `assess` module | DONE: move only | `core/src/state.rs` (new); imports updated in tray, app, tests | existing state tests |
| DF7 NIT-9 read-query timeouts | NOT DONE | `core/src/snapshot.rs` polls | PM |
| DF8-1 UI re-derives §4.1 | NOT DONE (stopped per brief) | `ui/src/state/world.ts:40-55` | see below |
| DF8-4 logs child orphaned on crash | DONE | `app/src/logs.rs:98` `spawn_child` (long-lived spawner thread, `PR_SET_PDEATHSIG`) | `a_killed_app_does_not_leave_the_log_child` (re-execs the test binary as the "app", SIGKILLs it; red first). Limit: only the child leader gets the signal; a journalctl it started ends on its next write to the closed pipe |
| DF8-5 double-confirm guard | DONE (test only) | `components.test.tsx` "confirm dialog guard" | mutant (guard removed) -> red |
| DF8-6 tray PNG decode per call | DONE | `tray/src/icons.rs:76` `OnceLock` per state | `the_pixmaps_are_decoded_once_per_state` (red first) |
| DF8-8 `Other` serialises as `"Other"` | DONE | `core/src/types.rs:29` `rename = "other"`; `ui/src/ipc/types.ts:39` comment; `world.test.ts` case now sends `"other"` | `a_server_word_a_newer_server_adds_serialises_as_other` (red first) |
| DF8-9 `kill_group` after reap | NOT DOING | `core/src/runner.rs:214` | the review calls it practically impossible; a real fix needs `waitid(WNOWAIT)` |
| DF9 MINOR-1 grant name when devices read failed | DONE | `core/src/diff.rs:60-66` falls back to `prev.devices` (also `TerminalOpened`) | `a_grant_in_a_tick_whose_devices_read_failed_still_has_the_known_name`, `a_terminal_opened_in_a_tick_...` (both red first) |
| DF9 `{ok:false}` text | DONE | `ui/src/components/CommandFailed.tsx:15` `service: <raw word>`, no new key | `shared.test.tsx` (EN + ID, `failed`/`activating`, no-word fallback), `StatusView.test.tsx` (red first) |
| DF9 NIT-1 empty server name | DONE | `ui/src/views/terminal/useTerminal.ts:105-125` (`GrantRow.unpaired`), `TerminalLists.tsx:39` (id shown once) | 3 tests in `TerminalView.test.tsx`; 2 red first, "server's name wins" proven by mutant |
| DF9 NIT-2 stale comments | DONE | `snapshot.rs`, `strings.rs`, `diff.rs`, `TerminalLists.tsx` | none |
| DF9 NIT-4 dead check | DONE | `TerminalView.tsx` `noGrants={granted?.length === 0}` | none |
| DF9 NIT-5 last good once | DONE | `core/src/snapshot.rs:76` `keeping_last_good`, used by `Poller` and `app/src/backend.rs:81`; Devices ref removed (Terminal's own read stays: it is its own read) | `a_failed_read_keeps_the_last_good_lists_only_while_the_server_runs`, two Backend tests (one red first); the old Devices UI test moved to Rust |
| DF9 NIT-6 test tidiness | DONE | `tests/strings.rs` bare block gone; `poller_stamp.rs` `terminal_json_with(enabled, ids)` | n/a |
| EXTRA Service `activating` headline | NOT DONE | | PM |

## Gate
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DFS/self-gate`, floors raised to the new totals (`MIN_TESTS=178`, `MIN_UI_TESTS=292`):

```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=292
PASS ui-test-count (292)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=178 failed=0
PASS desktop-test-count (178)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```
`logs::tests::orphan_helper` counts as one of the 178: it returns at once unless the next test starts it as a child.

## Needs a PM decision
1. **design.md edits (DF7-e, DF7-f):** §5 Surface for `st_notinst`, `st_nocli`, `st_noanswer`, `mm_title` should say "UI + tray tooltip"; the 3 Rust rows use code names (`NoAppTitle`, …) and the "189 keys" total leaves them out; §6 "Start server (stopped) or Stop server… (running)" should point at §4.4 (the code follows the service state, not the server state).
2. **Service `activating` headline (EXTRA):** §4.1 row 4 gives `st_stopped` / `sub_stopped` ("The service is inactive…") for every `server != running`, whatever the service word. design.md has no string for `activating`, `deactivating`, `reloading`, `maintenance`. Fix needs a design choice (a new key, or dropping the sub when the word is not `inactive`/`failed`). Code left as it is.
3. **DF7-h Hermes swap:** a swap to a server that leaves out `hermes` sends "Hermes is not reachable" while the tooltip says "does not report Hermes". Options: stay silent for "absent", or a new text. Only an older server triggers it.
4. **DF7 NIT-9 timeouts:** the server's `status` itself includes a 2 s Hermes probe (`controlcmds.go:153`), so the review's 3 s cap would turn slow polls into red "no answer". Options: 3 s only for `devices list`, `terminal status`, `version`; or a larger cap (5 s) for all reads. Needs a number and the verb list.
5. **DF8-1 (UI re-derives §4.1):** stopped, not started. Reasons: (a) ~124 test uses of `snapshotOf`/`withStatus` patch `status` without touching `assessment`, so every one would need a matching reason or a test-side copy of the table; (b) core's reason hides the row on a version mismatch (the window shows row 6 + banner while core says `VersionMismatch`), so core would also need a "reason ignoring the version" field; (c) core's `Reason` needs `HermesDown` split into not-running/error. Estimated well over 200 lines. Suggest its own ticket.
6. **DF8-9 `kill_group` after reap:** left as is (see table); confirm.
7. Design gap noted earlier (DS8b NIT-3, not in this sweep): the grant notice has no Open button; design.md §6 only names the D7 notice.
