# DS3b build report (fix round 1 of 2)

Scope: `desktop/core`, `desktop/tray`, `docs/desktop/gates.sh`. No git write command, no tray launch, no server command, no system install.

## RED method
The original DS3 tree was copied to a scratch dir (never git). The new tests were copied in. Symbols that did not exist (new `Reason` variants, `Key`s, `stamp_in_window`, `CliError::kind`) got behaviour-less stubs, so the tests fail on behaviour, not on a compile error.
Result on the old code: 5 test binaries failed, 16 new tests RED (list below). Tray unit tests (`menu.rs`, `launcher.rs`) cannot run on the old API (`MenuInput`, the `Option` signature of `window_bin`): their RED is a compile error.

## Per item
| # | Change | Tests | RED on old code |
|---|---|---|---|
| 1 | `core/src/snapshot.rs` `assess_status`: order mismatch, not installed, no-answer, not-running, running and `service != active` (new `Reason::RunningOutsideService`, Red), then Hermes reachable (Green) / other state (Amber `HermesDown`) / absent (Amber new `Reason::HermesUnknown`). | `state.rs` `tray_state_table`: every design 4.1 row, plus outside + hermes down, unknown + absent, failed + unreachable, not installed + running, inactive + no-answer, mismatch + not installed | `tray_state_table` FAILED |
| 2 | `core/src/diff.rs` `stamp_in_window` (`now-60 s <= stamp <= now+60 s`, used by `stop_expected`). `core/src/poller.rs` `tick`: a stamp outside the window is deleted on every poll. | `diff.rs`: `stop_with_stamp_61s_in_the_future_notifies`, `stop_with_stamp_exactly_at_the_window_edges_is_expected`, `stop_with_stamp_61s_old_notifies`, `stamp_in_window_is_symmetric_around_now`. `poller_stamp.rs`: `poller_deletes_a_stale_stamp_even_when_no_stop_was_seen`, `poller_deletes_a_future_stamp_and_the_next_crash_notifies`, `poller_keeps_an_in_window_stamp_until_the_stop_is_seen`, `restart_without_an_observed_stop_leaves_no_stamp_after_60_s` | `stop_with_stamp_61s_in_the_future_notifies`, `stamp_in_window_is_symmetric_around_now`, the stale, future and restart poller tests FAILED. The +61 s old, edge and keep tests pass on old code (guards). |
| 3 | `snapshot.rs`: `check_running_version` compares `status.result.version` on every poll; `combine_checks` (a mismatch on either side wins, the CLI's first). `is_dev`: unset, empty, `dev`, any `-dev` version; `+<sha>` build metadata is ignored first. | `state.rs`: `poll_running_server_version_differs_while_cli_matches_is_red`, `poll_running_server_dev_version_skips_its_side`, `poll_cli_mismatch_wins_over_a_matching_running_version`, `running_server_version_is_checked_on_every_poll_not_only_after_a_restart`, `dev_versions_skip_the_check_on_either_side` | first, fourth, and `dev_versions…` FAILED |
| 4 | `core/src/strings.rs`: `MenuStop` "Stop server…" / "Hentikan server…"; new `TipOutside` (`st_outside`), `TipHermesUnknown` (`sub_hermunk`), `Now` (`now_t`); `TipNotInstalled` = "Service not installed" / "Layanan belum terpasang"; `TipMismatch` = "Server and app don’t match" / "Server dan aplikasi tidak cocok"; tooltips mapped for the 2 new reasons; header now points at design.md. `Key::ALL` 26 -> 29. | `strings.rs`: `design_md_tray_texts_are_exact` (8 design rows, EN and ID), `the_new_reasons_pick_their_own_tooltips`; `every_reason_has_a_tooltip` extended | both new tests FAILED |
| 5 | `tray/src/menu.rs`: `MenuInput::from_snapshot` + `plan(&MenuInput)`. Active or any other word -> Stop…; inactive/failed -> Start; mismatch, not installed, or no status answered -> neither (Open stays; Pair off under mismatch). `tray.rs` stores `MenuInput` and redraws on change. | `menu.rs` unit tests: active, stopped, no-answer + active keeps Stop…, hand-started + inactive shows Start, other words show Stop…, mismatch (3 services), not installed, unknown service | compile error on old API |
| 6 | `tray/src/tray.rs` `start_server`: logs `start ok` or `start failed: <kind>`. New `CliError::kind()` returns a fixed code (`runner.rs`). | `runner.rs` `error_kind_is_a_fixed_code_without_the_message` | FAILED (stub returns "") |
| 7 | `docs/desktop/gates.sh`: missing `desktop/Cargo.toml` -> `FAIL desktop-missing`; new `PASS/FAIL desktop-test-count` with `MIN_TESTS=94` (0 tests fails). | Scratch gate trees (see below) | old script: `SKIP desktop (0)`, exit 0. New: `FAIL desktop-missing (1)`, exit 1. A 0-test workspace: `FAIL desktop-test-count (0<94)`. |
| 8 | `runner.rs`: `process_group(0)`; on timeout `killpg(pgid, SIGKILL)` through a 1-line `extern "C"` (no new crate), then the reader is joined if it ends within 2 s, else detached. | `runner.rs` `timeout_kills_grandchildren_too` (script runs `sleep 30 &`, then `exec sleep 30`) | FAILED: grandchild still alive |
| 9 | `runner.rs` `absolute_env_path` (relative -> ignored, log line "ignoring NAME: not an absolute path"), used for `ARISTOTLE_SERVER_BIN` and `ARISTOTLE_DESKTOP_BIN`. `launcher.rs`: `window_bin` returns `Option`; `current_exe()` failure or a relative dir -> window app is `Missing`. | `runner.rs` `default_bin_prefers_the_env_override` (extended with 3 relative values); `launcher.rs` `window_bin_relative_override_is_ignored`, `window_bin_without_a_usable_exe_path_is_none`, `no_path_is_reported_as_missing` | first FAILED; the launcher tests do not compile on the old API |
| 10 | `poller.rs` `baseline_for`: a failed `devices list` / `terminal status` read (server running) keeps the previous list in the stored baseline. | `poller_stamp.rs`: `a_failed_devices_poll_keeps_the_baseline_so_a_new_phone_still_notifies_once`, `a_failed_terminal_poll_keeps_the_baseline_so_a_new_terminal_still_notifies_once` | both FAILED |
| 11 | NITs done: NIT-1 (comment in `main.rs`), NIT-2 ("Open" action only with a handler), NIT-4 (`Launch::Failed` logged), NIT-8 header. | no separate test | – |

The 16 RED tests: `stop_with_stamp_61s_in_the_future_notifies`, `stamp_in_window_is_symmetric_around_now`, the 3 poller stamp tests, 2 baseline tests, `default_bin_prefers_the_env_override`, `error_kind_is_a_fixed_code_without_the_message`, `timeout_kills_grandchildren_too`, 3 `state.rs` version tests plus `tray_state_table`, 2 `strings.rs` tests.

## Decisions to check
- **Dev version format.** `scripts/build-server.sh` without a tag builds `0.0.0-dev+<sha>`, and the unstamped server prints `0.0.0-dev`. So the "-dev" rule strips `+metadata` first, or the commonest dev build would not skip.
- **Menu.** "Anything else -> Stop…" follows the brief. design.md 4.4 turns all buttons off for other service words in the window. A hand-started server with service `unknown` therefore shows Stop… in the tray, and Stop cannot reach it. Not-installed, mismatch and "no status answered" show neither. The PM should confirm.
- **MIN_TESTS=94** is the current count (was 67 before this round).

## Not fixed (NITs)
- NIT-3 (escape `&<>` in device names): not a one-liner.
- NIT-5 (three `non_empty_env` copies), NIT-6 (`ErrorBody` unused), NIT-7 (move `assess` to `state.rs`): refactors, left for a later round.
- NIT-8 per-`Key` design-key comments: only the header was fixed.
- NIT-9 (10 s timeout on the read-only queries): left.

## Code-only strings (not in design.md, PM adds them as Rust rows)
| Key | EN | ID |
|---|---|---|
| `NoAppTitle` | Window app not found | Aplikasi jendela tidak ditemukan |
| `NoAppBody` | aristotle-desktop is not installed next to the tray. | aristotle-desktop tidak terpasang di samping tray. |
| `ADevice` | A device | Sebuah perangkat |

(`OpenAction` "Open" / "Buka" matches `n_open`.)

## Gate (`bash docs/desktop/gates.sh docs/desktop/runs/DS3b/self-gate`, exit 0)
```
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=94 failed=0
PASS desktop-test-count (94)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
```

## Files
Modified: `desktop/core/src/{snapshot,diff,poller,runner,strings}.rs`, `desktop/core/tests/{state,strings,diff,runner,poller_stamp}.rs`, `desktop/tray/src/{menu,tray,launcher,notifier,main}.rs`, `docs/desktop/gates.sh`. Created: this report and `self-gate/`.
