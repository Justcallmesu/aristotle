# DS3 build report

Built the `desktop/` Rust workspace (`core` lib + `aristotle-tray` bin) and `docs/desktop/gates.sh`. **Gate GREEN (exit 0), 67 tests passed.** No git write command, no system install, no stop condition hit. All processes I started are gone (`pgrep -x aristotle-tray` empty).

## Crate layout
```
desktop/Cargo.toml          workspace (members core, tray; "app" comes in DS3w), shared deps, resolver 2
desktop/Cargo.lock          seeded from spikes/ksni-tray (ksni 0.3.7, notify-rust 4.19.0, zbus 5.19/6.3, tokio 1.54.1) + serde
desktop/.gitignore          target/
desktop/core/               package aristotle-core, lib aristotle_core  (a crate named `core` clashes with std)
  src/types.rs      section 22 shapes (serde); Pair has a redacting Debug (P11)
  src/runner.rs     trait ServerCli, RealCli (abs path, no shell, 10 s timeout + kill), query<T> (appends --json last), parse_output, CliError
  src/snapshot.rs   Snapshot, Snapshotter (poll + P5 version check), assess/tray_state (Green/Amber/Red + Reason), compare_versions, desktop_version()
  src/diff.rs       diff(prev, next, expected_stop, now) -> Vec<Notice>, server_stopped()
  src/stamp.rs      ExpectedStop read/write/delete ($XDG_RUNTIME_DIR/aristotle-desktop/expected-stop)
  src/strings.rs    Key enum (exhaustive en()/id() matches), Lang::from_values/from_env, tooltip(), notice_text()
  src/poller.rs     Poller::tick: snapshot + diff against last *answered* snapshot + single-use stamp deletion
  tests/            common/mod.rs (builders, FakeCli) fixtures.rs runner.rs state.rs diff.rs strings.rs poller_stamp.rs
desktop/tray/               package aristotle-tray, bin aristotle-tray (deps: core, ksni, notify-rust only)
  src/main.rs (poll thread, 5 s) tray.rs (ksni Tray) menu.rs (pure menu plan) launcher.rs (child PID + reaper, window path)
  src/notifier.rs (notify-rust, default action opens the app) icons.rs (generated 22x22 pixmaps)
desktop/packaging/aristotle-tray.desktop, aristotle-desktop.desktop   (@BIN_DIR@ placeholder for the DS7 installer)
docs/desktop/gates.sh
```

## Tests (67 pass: 58 in core, 9 in tray)
- **fixtures**: every_fixture_decodes, no_fixture_is_left_uncovered (fails on a fixture without a decoder AND a decoder without a fixture), status_full_fields, status_sparse_has_absent_fields, status_not_installed_and_not_running, terminal_optional_fields, unknown_fields_are_ignored, error_body_decodes, pair_debug_hides_the_code_and_payload
- **runner** (fake sh scripts in a temp dir): success_object_is_parsed_with_exit_code, success_object_with_exit_1_is_still_success, error_object_and_exit_1_is_a_server_error, garbage_stdout_is_bad_output_without_content, wrong_shape_is_bad_output, timeout_kills_the_process (checks /proc/<pid> gone), missing_binary_is_not_installed, semicolon_argument_is_passed_literally, query_appends_json_as_the_last_argument, default_bin_prefers_the_env_override
- **state**: tray_state_table (12 cases), compare_versions_rules, poll_running_fetches_version_devices_terminals, poll_stopped_skips_devices_and_terminals, poll_version_asked_once_and_again_after_restart, poll_version_mismatch_is_red, poll_dev_build_skips_the_check, poll_failed_version_call_is_retried_next_poll, poll_failures_map_to_red_states, poll_devices_failure_keeps_status
- **diff**: first_poll_never_notifies, new_device_is_announced_by_name, device_list_missing_on_one_side_is_silent, new_terminal_names_the_device, new_terminal_with_unknown_device_has_no_name, existing_terminal_is_not_new, hermes_flap_notifies_once_per_transition, hermes_going_absent_counts_as_unreachable, hermes_notice_is_not_added_when_the_server_stopped, failed_poll_is_not_a_transition, stop_without_stamp_notifies, stop_with_fresh_stamp_is_silent, stop_with_stale_stamp_notifies (61 s), stop_with_future_stamp_counts_as_fresh, already_stopped_does_not_notify_again, notices_carry_only_names_ids_and_listeners
- **strings**: locale_order_is_lc_all_then_lc_messages_then_lang, locale_defaults_to_english, every_key_has_text_in_both_tables_with_the_same_placeholders, texts_differ_between_languages_except_the_brand_like_ones, board_texts_are_exact, notice_texts_fill_names_and_fall_back, no_text_names_a_pairing_code_and_no_placeholder_is_left_in_notices, every_reason_has_a_tooltip
- **poller/stamp**: stamp_write_read_delete_roundtrip, stamp_garbage_reads_as_none, stamp_without_runtime_dir_is_none, poller_stop_with_fresh_stamp_is_silent_and_consumes_the_stamp, poller_stop_without_stamp_notifies_and_failed_poll_keeps_the_baseline
- **tray unit**: icons (2), launcher (4: window path next to tray, env override, missing binary reported, second open deduplicated), menu (3)

## RED evidence
I wrote tests and code together, then proved the tests bite on a scratch copy (`~/.hermes/profiles/pm/cache/scratch/ds3-red/`, not in the repo) with stubs: `diff` returns empty, `assess` returns Red, `compare_versions` returns Skipped, `parse_output` returns BadOutput, `RealCli::run` returns NotInstalled, `Lang::from_values` returns En, `ExpectedStop::read` returns None. `cargo test -p aristotle-core --no-fail-fast`: exit 101, **28 tests FAILED** (diff 9, runner 8, state 8, poller/stamp 2, strings 1; fixtures all pass because the types are not stubbed); the 7 diff tests that still pass are the "silent" ones. Full log: `~/.hermes/profiles/pm/cache/scratch/ds3-red-full.txt`. Real code: 0 failed.

## Gate (`bash docs/desktop/gates.sh docs/desktop/runs/DS3/gates`, exit 0, ~4.5 min cold)
```
PASS desktop-fmt (0)          PASS desktop-clippy (0)       PASS desktop-test (0)
rust-tests passed=67 failed=0 PASS desktop-tray-build (0)   PASS desktop-tray-ldd (0)
```
`ldd target/release/aristotle-tray`: libgcc_s, libm, libc, ld-linux only; the script fails on any gtk/webkit match. Logs in `docs/desktop/runs/DS3/gates/`.

## Real-server smoke
**Finding: the installed `~/.local/bin/aristotle-server` (v2.1.1, 41fb0ad) predates DS1 and has no `--json`.** `status --json` and `devices list --json` print the usage text and exit 2 (empty stdout); `version --json` prints the plain text line. So the tray built against the installed binary shows Red / "Server not answering" until DS1 is installed (DS7/PM install step). For the smoke I built the repo's server (`go build` into scratch) and pointed the tray at it with `ARISTOTLE_SERVER_BIN` (same read-only commands, against the real running service).
- `status --json` -> exit 0, `installed:true`, `service:active`, `server:running`, result version 2.1.1, 3 addresses, **no `hermes`, `port` or `listeners` keys** (the running server is the old one; its `status` result has none). `version --json` -> `0.0.0-dev`. `devices list --json` -> 4 devices. `terminal status --json` -> disabled, none open.
- Timing of one `status --json` (new CLI binary, 5 runs, wall ms): 21.2, 24.6, 22.4, 24.8, 19.3 -> **min 19.3 / median 22.4 / max 24.8 ms**. (Old binary's `status` with no `--json` path was ~5 ms but only prints usage.) Far below the 1 s stop condition; polling cost is negligible.
- Tray launched with the GUI env for ~2 minutes, then killed. RSS 6,952 KB, 2 threads. SNI item registered, `Status=Active`. **Icon state: Amber, as the mapping predicts** (server running, Hermes absent from the old status = "not reachable"): `ToolTip` = "Aristotle Server" / "Server running · Hermes not reachable"; `IconPixmap` (22x22) centre pixel = ARGB ff,F2,B3,3D = amber `#F2B33D`. Consequence: **with the currently running v2.1.1 server the tray stays Amber permanently** until the server is upgraded (it never reports Hermes). That is the spec'd rule (Hermes absent -> Amber), noted for DS7.
- **Not verified visually**: I could not see the icon in a panel crop. The HDMI screen is covered by a fullscreen game; the laptop panel (macOS-style, top) showed only Apple logo and clock, with no tray row visible (SNI icons likely collapsed). Colour was verified from the D-Bus pixmap instead. Panel-only crops were saved in scratch and deleted; the full spectacle capture was never viewed whole and is deleted.
- Not run live: notification toast, notification click, left click, menu Start/Stop (not allowed), `watcher_offline`.

## Deviations / decisions to confirm
1. Crate package is `aristotle-core` (lib `aristotle_core`), dir `core`: a lib named `core` shadows std.
2. Menu text "Stop server" / "Hentikan server" follow `mockups.html` exactly (the brief and P14 write "Stop server…"). One-line change in `strings.rs` if the ellipsis is wanted.
3. Terminal notice body: the board text says "(LAN)" hard-coded; I fill `{via}` from the terminal's `listener` (`lan`->LAN, `tailscale`->Tailscale, other raw). Notice carries device name + listener word only.
4. Extra string keys (EN+ID, not on the board): `TipNotInstalled`, `TipNoCli`, `TipNoAnswer`, `TipMismatch`, `NoAppTitle/Body` (window app missing), `ADevice` ("A device"/"Sebuah perangkat", the fallback name), `OpenAction`. Indonesian for these is mine. `TermOn*` (D7) is in the table for the window app, unused by the tray.
5. Version check compares `version --json`'s `version` (the CLI binary) with the build's `ARISTOTLE_VERSION`, as briefed; it does not compare `status.result.version` (the running server). A running old server with a new CLI binary on disk would not be flagged. Skipped when either side is empty/`dev`; retried next poll if the `version` call fails. Asked at start and on every not-running->running edge.
6. "Failed poll is not a transition": the Poller keeps the last poll where `status` answered as the baseline; a timeout, garbage, `{"error"}` or missing CLI neither notifies nor replaces it. A stop reported while the CLI is hanging is therefore only seen when status answers again.
7. Green also requires `service == "active"`; server answering but service not active (e.g. started by hand) is Red/ServerStopped, per "everything else".
8. Launching with arguments (`--pair`, `--confirm stop`) always spawns an untracked process (the DS3w single-instance guard must hand the args to the open window and exit); plain Open/click is deduplicated by the tracked PID. Untested end to end because the window app does not exist yet.
9. Tray palette: placeholder circles use the dark-panel variants from design-notes (`#5FD1AE`, `#F2B33D`, `#FF6B5E`); the real set is ticket DSI.
10. Start server is disabled in the menu when `installed` is false (no unit to start); Pair is disabled when the server is not running (board).
11. The first poll runs before the tray is spawned (no wrong-colour flash), so a hung CLI delays the icon by up to 10 s.
12. `gates.sh` also runs `cargo build --release -p aristotle-tray` for the `ldd` check (that is the 3-4 min part of the gate).

## Open questions
- Real left click / notification click / toast on Plasma still need the user (DS2b check list; plus: does the toast's "default" action fire on a body click in Plasma, or only a button labelled Open).
- Where to see the tray icon on this PC: the laptop panel has no visible tray row; the user may need to expand the system tray to see it.
- Should `aristotle-tray` log to the journal/file in autostart? It prints state transitions and errors (never CLI content) to stderr.
- Install DS1's server before DS7 smoke; the amber-forever case above goes away only once the running server reports `hermes`.
