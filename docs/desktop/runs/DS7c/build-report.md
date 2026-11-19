# DS7c build report (fix round 2 of 2)

All 6 brief items done. Both gates exit 0. The tray test binary ran 200 times with 0 failures.

## What changed
1. **P27 (2) lock guard**: `desktop/tray/src/instance.rs` has `pub struct Lock(File)`; `Drop` calls `flock(LOCK_UN)`.
   `Instance::First(Lock)`, and `main.rs` `single_instance()` returns `Option<Option<instance::Lock>>`.
   New unit test `dropping_the_lock_frees_it_even_while_a_forked_child_holds_a_copy` (a child held between fork and exec).
   **Seen failing first**: with the `flock` call commented out it failed ("the lock stayed held by the forked child"). It is green with the call back.
2. **ETXTBSY**: new test-only `desktop/tray/src/testing.rs` with `spawn_gate()` (one static mutex).
   - `launcher.rs` tests hold it while each fake script is written and chmodded, and around every `open()` (helper `open_gated`).
   - The new fork test in `instance.rs` holds it around its spawn.
   - `tests/instance.rs` has its own gate around the stub write and every tray/holder spawn.
   - Other modules checked: `core/tests/runner.rs` (`FORK_LOCK`) and `app/src/logs.rs` (`crate::testing::fork_lock`) already serialise write+spawn. No change needed.
3. **Mutant test**: `tests/instance.rs` `tray()` now sets `ARISTOTLE_SERVER_BIN` to a stub script in the scratch runtime dir (it touches `polled`, then sleeps 5 s). It never uses `~/.local/bin/aristotle-server`.
   New test `a_running_tray_holds_the_lock_and_a_second_real_tray_exits_zero_silently`: it waits for `polled`, so no probe races the tray. It then checks that the lock is held, that a second real tray exits 0 with no output, and that the first is still up.
   **Mutant** `let _ = match single_instance()` in `main.rs`: the test FAILED ("a running tray must hold the lock"). It is restored and green.
4. **P27 (1) watcher offline 30 s**: new `desktop/tray/src/offline.rs` with `OfflineWatch` (`went_offline(now)`, `came_online()`, `expired(now, limit)`) and `OFFLINE_LIMIT = 30 s`, shared as `Arc<Mutex<..>>`.
   The ksni `watcher_offline` / `watcher_online` callbacks (`tray.rs`) only feed it. `poll_forever` (`main.rs`) checks it every tick and returns, so `main` ends with exit 0 and drops the lock.
   6 unit tests with an injected clock. Mutants seen failing: `came_online` as a no-op (2 tests failed), and `>=` changed to `>` (3 tests failed).
   Not covered: no test of the poll-loop wiring itself, which needs a real StatusNotifierWatcher.
5. **P27 (3) installer** (`scripts/install.sh`): `start_tray` takes the pid. `report_tray_start` waits `${TRAY_START_CHECK_SECONDS:-1}` s and runs `process_alive`, which also rejects a zombie. If alive it prints "Started the tray (log: …)".
   Otherwise `other_tray` scans `/proc` for any `aristotle-tray` and the script prints `another aristotle-tray is running (pid N, <path>); the new one exits` (exit 0). With no other tray found it warns "the new tray exited right after it started" and returns 1, so the existing "desktop app not fully installed" warning follows and the server install is unaffected.
   `other_tray` names the first tray found, which is not necessarily the lock holder. If several run, the named pid can be another one.
   Tests in `install_test.sh`: stub `aristotle-tray` exits at once when `STUB_TRAY_EXITS` is set.
   - Another tray at a different path: asserts the line shape and that the named pid is live. The pid is not pinned, because the real preview tray on this PC (pid 242035) can be named first.
   - No other tray: asserts no "Started the tray" and exit 0.
   - **Seen failing**: with the liveness check forced true, 3 FAILs.
6. **Nits**:
   - README rollback uses `pkill -xf "$HOME/.local/bin/aristotle-tray"`. The README also says "entries first, then the tray, then the service" and describes the 1 s check and the 30 s offline exit.
   - `servicecmds.go`: uninstall now removes the menu, autostart and icon entries first (`removeDesktopEntries`), then stops the tray and window, then the service. `reportKeptPrograms` prints the "Kept the programs" line at the end.
     New test `TestUninstallRemovesTheAutostartEntryBeforeStoppingTheTrayAndTheService` (daemon-reload fails): **seen failing first** ("autostart entry was still there when the tray was stopped"). Tray-before-service is kept (the existing test is green).
   - The relative-`XDG_DATA_HOME` test now creates the menu entry and icon under `$HOME/.local/share` and asserts they are removed.
7. **Floors** in `docs/desktop/gates.sh`: `MIN_TESTS` 203 → **211** (+1 instance unit, +6 offline, +1 integration). `MIN_UI_TESTS` stays 301 (unchanged, 301 passed).

## Gates (verbatim)
`timeout 3000 bash docs/v1-tickets/gates.sh docs/desktop/runs/DS7c/self-gate-full` → exit 0
```
PASS server-gofmt (0)
PASS server-vet (0)
PASS server-test (0)
go-tests passed=905 failed=0
PASS android (0)
PASS licences (0)
PASS scripts (0)
```
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS7c/self-gate-desktop` → exit 0
```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=301
PASS ui-test-count (301)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=211 failed=0
PASS desktop-test-count (211)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```
Tray binaries in that run: unit `src/main.rs` 40 tests, `tests/instance.rs` 5 tests.

## Parallel loop
`cargo test -p aristotle-tray`: 4 parallel loops × 50 runs = **200 runs, 0 failures** (loop 1, 2, 3, 4: 50 runs each, 0 failures).

## Files changed this round
- `desktop/tray/src/`: `instance.rs`, `main.rs`, `launcher.rs`, `tray.rs`; new `offline.rs`, `testing.rs`
- `desktop/tray/tests/instance.rs`
- `scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/aristotle-tray`
- `server/internal/app/servicecmds.go`, `servicecmds_test.go`
- `docs/release/README.md`, `docs/desktop/gates.sh`
- `docs/desktop/runs/DS7c/` (gate output and logs; this report)

`cargo fmt` was run over the workspace; the gate's `cargo fmt --check` passes.

## Notes
- **Wrong script launched**: while starting the loops I launched a pre-existing `loop.sh`/`all.sh` in the scratch dir (an Android gradle loop, not mine). I stopped it within about 4 minutes by killing only the PIDs it had started (the loop shells, then their gradle/java children).
  - It ran `RunningViewModelTest` a few times and wrote nothing into the repo; `git status` shows no Android or AF1 changes.
  - I overwrote `all.sh` in `~/.hermes/profiles/pm/cache/scratch` (the pre-existing scratch file, not repo).
  - The real loops used new `ds7c-tray-*.sh` scripts.
- The preview tray (pid 242035) was not touched.
