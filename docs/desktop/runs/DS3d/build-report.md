# DS3d build report: flaky runner tests

Only `desktop/core/tests/runner.rs` changed. No production code, no git writes.

## Diff summary
- Added `use std::sync::{Mutex, MutexGuard}`.
- Added `static FORK_LOCK: Mutex<()>` and `fork_lock()`. It recovers a poisoned lock with `unwrap_or_else(|e| e.into_inner())`.
- `Scratch` is now `Scratch(PathBuf, MutexGuard<'static, ()>)`. `Scratch::new` takes the lock first, so it is held for the whole test and released after `Drop` removes the dir. The guard field has `#[allow(dead_code)]` because clippy `-D warnings` flagged it as never read (first attempt failed the gate on that).
- `missing_binary_is_not_installed` takes `fork_lock()` too.
- No ETXTBSY retry anywhere.

## 100-run result
`cargo test -q -p aristotle-core --test runner` in a loop, `CARGO_TARGET_DIR=desktop/target`:
- **0 failed of 100 runs**, wall time 222 s. This was run on the final code. An earlier 100-run loop on the pre-clippy-fix code was also 0 failed (221 s).

## Gate summary (`docs/desktop/runs/DS3d/self-gate`)
```
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=94 failed=0
PASS desktop-test-count (94)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
```
