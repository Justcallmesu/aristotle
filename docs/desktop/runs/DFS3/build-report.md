# DFS3 build report

All items done. Gate green (`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DFS3/self-gate`).

## Items
- **MINOR-1**: `core/tests/runner.rs` `the_poll_keeps_the_runner_limit_for_status_so_a_4_s_status_still_answers` (fake `status` sleeps 4 s, `Snapshotter::poll(&cli(.., 10))` must be answered, devices present). Mutant R11 (`status` via `query_read`): FAILED as wanted; restored.
- **MINOR-2**: `ui/src/state/world.ts` `isChanging` excludes `unknown` (row 4). New `world.test.ts` row, seen failing first. The `StatusView.test.tsx` `it.each` expected "changing" for `unknown`; it now expects "Server stopped" (Start stays hidden for `unknown`, as before).
- **NIT-1**: `app/src/logs.rs` captures the pid before spawn; in `pre_exec` after `prctl`, `getppid() != parent` returns an error. Not testable (needs a parent death inside the fork window); existing PDEATHSIG tests pass.
- **NIT-2**: spawner started with `thread::Builder::new().name("log-spawner")`; failure is an `io` error for that call, the next call retries. `OnceLock` replaced by `static Mutex<Option<Sender>>`. Not testable (cannot make the OS refuse a thread); the two-starts and kill tests pass.
- **NIT-3**: runner `try_wait` Err comment reworded.
- **NIT-4**: `stamp.rs` `delete_if` comment states the remaining race; no code change.
- **NIT-5**: `tray/src/tray.rs` tests with a blocking `ServerCli` fake: Start enabled, then disabled while a start runs, a second `start_server` makes no second call, enabled again after success and after error. Mutants "Start always enabled" and "`mem::forget(token)`" both FAILED (both tests); restored.
- **NIT-6**:
  - `a_read_never_outlasts_the_runners_own_shorter_timeout`: 1 s `RealCli`, `run_within` 3 s, between 0.9 s and 3 s. Mutant R5 (`limit` instead of `limit.min(self.timeout)`) FAILED; restored.
  - The three `a_hung_*` tests also assert the read took >= 2.9 s (the read was already asserted empty).
  - `orphan_helper` is `#[ignore]`, started with `--ignored --exact`, so it no longer counts. The parent test still passes.
- **Floors** (`gates.sh`): `MIN_TESTS` 184 -> 187 (+2 tray, +2 core, -1 orphan helper), `MIN_UI_TESTS` 300 -> 301.

## Files changed
`desktop/app/src/logs.rs`, `desktop/core/src/{runner,stamp}.rs`, `desktop/core/tests/runner.rs`, `desktop/tray/src/tray.rs`, `desktop/ui/src/state/world.ts`, `desktop/ui/src/state/world.test.ts`, `desktop/ui/src/views/status/StatusView.test.tsx`, `docs/desktop/gates.sh` (floors only), this report, `runs/DFS3/self-gate/`.
Mutants were run on edited files, restored from copies in `$TMPDIR` (checked with `diff`), no git. spec P23 and design.md §4.1 already said `unknown` is row 4, so I did not touch the docs.

## Gate summary (verbatim)
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
rust-tests passed=187 failed=0
PASS desktop-test-count (187)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```

## Notes
- The first gate run failed `ui-test` because of the `StatusView.test.tsx` expectation above. I fixed it and re-ran the gate.
- No app or tray launched against the real service. Only the existing aristotle-server (pid 620, the user's service) was running, and I did not start it.
