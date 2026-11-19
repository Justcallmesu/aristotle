# DF11 build report

Outcome: both items done; self-gate all PASS (`runs/DF11/self-gate`; rust-tests 196, ui-tests 301).

- Early-exit notice: `Launcher` gets an `on_early_failure` callback; the existing reaper thread times each child and calls it
  when the child exits non-zero or by signal in under 5 s (`EARLY_EXIT_WINDOW`). Exit 0 or a run over 5 s is silent. `open()`
  still returns at once (no zombies, no blocking). `notifier::show_window_failed` sends the toast; no child output in it.
- Strings: `Key::WindowFailedTitle/Body` (EN and ID, `Key::ALL` = 33) + exact-text test in `core/tests/strings.rs`;
  two Rust rows added to `design.md` §5.
- `aristotle-tray --version` prints `aristotle-tray <v>` (core `desktop_version()`, else `0.0.0-dev`) and exits 0 as the first
  step of `main`, before the poller/D-Bus. Tested via a pure `version_line()`, not by running the tray binary.
- Tests (7 new): launcher with fake shell scripts: exits 1, killed by signal -> 1 notice; exit 0 -> none; `sleep 6; exit 1`
  -> none and `open()` non-blocking; 2 `version_line` tests; 1 strings test. RED seen: with a no-op hook the two
  failure tests failed (29 run, 2 failed). The version and strings tests were written with their code, so no separate RED.
- Gate floor `MIN_TESTS` 187 -> 194 (`docs/desktop/gates.sh`).

Files: `desktop/tray/src/{launcher,main,notifier,tray}.rs`, `desktop/core/src/strings.rs`, `desktop/core/tests/strings.rs`,
`docs/desktop/design.md`, `docs/desktop/gates.sh`.
Not covered: no live check of the real toast (real tray never launched). `Launcher::from_env` now takes the hook.
