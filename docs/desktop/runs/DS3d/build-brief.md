# DS3d fix brief — flaky runner tests (fix round 2 of 2, test-only)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/desktop/runs/DS3/re-review-report.md` (section on the flaky test:
mechanism ETXTBSY + the verified fix) and `desktop/core/tests/runner.rs`.

## Fix
In `desktop/core/tests/runner.rs` only: one process-wide `static` lock (std `Mutex`, poison-tolerant: recover with
`into_inner` on a poisoned lock so one failing test does not fail the rest) taken in `Scratch::new` and held for the whole test
(store the guard in `Scratch`), plus the same lock in `missing_binary_is_not_installed` (it forks too). No production code change;
no blind ETXTBSY retry anywhere.

## Verify
1. Run `cargo test -q -p aristotle-core --test runner` 100 times in a loop (`CARGO_TARGET_DIR=desktop/target`): report the count
   of failed runs (must be 0) and total wall time.
2. `bash docs/desktop/gates.sh docs/desktop/runs/DS3d/self-gate` all PASS; paste the summary.

## Files you may edit
`desktop/core/tests/runner.rs` only.

## Hard rules
Never git write commands; no installs; no tray launch; no aristotle-server commands; no skill/memory edits.

## Report
`docs/desktop/runs/DS3d/build-report.md`: the diff summary, the 100-run result, gate summary.
