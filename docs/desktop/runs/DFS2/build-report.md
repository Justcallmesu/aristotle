# DFS2 build report (P23 a-c)

No git write command, no install, no app/tray launch, `aristotle-server` never run. Only test children were started (fake shell scripts, all gone). The `aristotle-server serve` in `pgrep` (pid 620) is the user's own service, not mine.

| Item | Status | Where | Test (seen failing first) |
|---|---|---|---|
| P23 (a) row 4b | DONE | `ui/src/state/world.ts` (`"4b"` row, `isChanging`, `subParams {word}`); `SetupState`, `StatusView`, `ServiceView` pass `subParams`; `en.json`/`id.json` `st_changing`/`sub_changing` as design.md. Tray unchanged (red, same tooltip) | `world.test.ts` (activating/deactivating/failed/absent + raw word), `StatusView.test.tsx` row 4b, `ServiceView.test.tsx` state row, `components.test.tsx` footer red; 6 red before the fix. The i18n files test passes. The P21 Start-button test now expects the 4b headline for non-settled words |
| P23 (b) absent Hermes silent | DONE | `core/src/diff.rs` `hermes_went_down` requires `hermes_reported()` (new in `core/src/types.rs`) | `hermes_going_absent_is_silent` (red first); `hermes_going_not_running_notifies` (kept green) replace the old "absent counts as unreachable" test |
| P23 (c) 3 s read timeout | DONE | `core/src/runner.rs`: `READ_TIMEOUT`, `ServerCli::run_within` (default = `run`), `query_read`, `RealCli::run_within` = `min(limit, own)`; `core/src/snapshot.rs` (`devices list`, `terminal status`, `version`) and `app/src/terminal.rs` `status` use `query_read`. `status` and all writes unchanged (10 s) | `core/tests/runner.rs`: fake script hangs on `devices`/`terminal`/`version`, poll finishes < 6 s with that read `None`/`Unchecked`, others intact (red: 10 s); `a_hung_status_keeps_the_runner_limit`; `app/src/terminal.rs` limit test (red via mutant: `query` instead of `query_read`). `app/src/testing.rs` FakeCli records limits |

Floors in `docs/desktop/gates.sh`: `MIN_TESTS=184`, `MIN_UI_TESTS=300`. `cargo fmt` applied once after the first gate run failed `desktop-fmt`.

## Gate
`timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DFS2/self-gate`:

```
PASS ui-ci (0)
PASS ui-lint (0)
PASS ui-typecheck (0)
PASS ui-test (0)
ui-tests passed=300
PASS ui-test-count (300)
PASS ui-build (0)
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=184 failed=0
PASS desktop-test-count (184)
PASS desktop-test-binaries (0)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
PASS desktop-app-build (0)
PASS desktop-app-binary (0)
```

## Notes for the PM
- Row 4b applies only to `server == "not-running"` (per P23); `server == "other"` with a transitional service word still reads as row 4.
- `RealCli::run_within` caps at the runner's own timeout, so a runner configured below 3 s keeps its shorter limit.
- The app has no separate `devices list` or `version` read; both go through `Snapshotter`, which `Backend::refresh` uses, so the poller and the app share the 3 s limit.
