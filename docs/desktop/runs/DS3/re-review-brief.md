# DS3 re-review brief (deep, fresh context) — DS3 + DS3b + DS3c as one uncommitted package

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/desktop/spec.md` (D4, D5, D17-D19, P1-P17),
`docs/desktop/design.md` §4.1 and its string table, the first review `docs/desktop/runs/DS3/review-report.md`, the fix briefs and
reports `docs/desktop/runs/DS3b/{build-brief,build-report}.md` and `docs/desktop/runs/DS3c/{build-brief,build-report}.md`.
Scope: everything under `desktop/**` (uncommitted) and `docs/desktop/gates.sh`.

**This is the review after the LAST allowed fix round's input.** A CHANGES-REQUIRED now goes to the user, so separate real
BLOCKER/MAJOR (wrong user-visible state or notification, a leak, a lost or silent alert, a gate that can pass falsely) from what can
be deferred (MINOR/NIT -> a deferred table).

## Already proven by the PM (hunt elsewhere)
PM gate GREEN (94 tests, tray ldd libc-only). PM mutants KILLED: outside-service rule off (`tray_state_table`); future stamp always
fresh (`stamp_in_window_is_symmetric_around_now`, `stop_with_stamp_61s_in_the_future_notifies`); running-server version ignored
(`poll_running_server_version_differs_while_cli_matches_is_red`, `running_server_version_is_checked_on_every_poll_...`); other
service words show Stop (`other_service_words_show_neither`).

## PM finding to confirm and size (do not fix)
`core/tests/runner.rs` is flaky: 1 of 5 and 1 of 12 clean runs failed with `Io("ExecutableFileBusy")` (ETXTBSY) in
`garbage_stdout_is_bad_output_without_content` / `error_object_and_exit_1_is_a_server_error`: a test writes a script and executes
it while another test thread's fork still holds the write fd. Confirm the mechanism in the test helper (`Scratch::script`) and
propose the smallest robust fix (e.g. a process-wide lock shared by script writing and spawning, or writing scripts via a temp
file + rename before any spawn). Also say whether the PRODUCTION runner can ever hit ETXTBSY (it should not retry blindly).

## Check
1. Every first-review finding: closed / not closed (table), with file:line.
2. P17 met/not met table again (state order per design.md rows 0-9, stamp window and deletion on every poll, version vs CLI and
   running server, `-dev` and `+meta` skip, "Stop server…").
3. New code from the fix rounds: process-group kill (does it kill grandchildren, is the reader thread joined, any zombie?),
   absolute-path env overrides, lost-notice baseline (a failed list poll keeps the old baseline; can a removed-then-re-added device
   double-notify?), menu plan vs design.md §4.1 "Which buttons are on" (tray and window must agree).
4. Gate: `MIN_TESTS=94` floor — sound, or should it be "> 0 per test binary"? Missing Cargo.toml -> FAIL.
5. Strings: code vs design.md keys (EN + ID), incl. the 3 Rust rows the PM added.

## Output
`docs/desktop/runs/DS3/re-review-report.md`: verdict; findings BLOCKER/MAJOR/MINOR/NIT with file:line + fix; first-review closure
table; P17 table; the flaky-test diagnosis + recommended fix. Read-only: never edit code, no git write commands, no tray launch,
never touch the server service. You may run `cargo test` repeatedly (CARGO_TARGET_DIR=desktop/target).
