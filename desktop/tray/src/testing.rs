//! Test-only helper shared by the tray's unit tests.

use std::sync::{Mutex, MutexGuard};

static SPAWN_GATE: Mutex<()> = Mutex::new(());

/// One lock for every test that writes an executable script or forks a child. A fork in one thread copies a file
/// descriptor another thread still has open for writing, and the exec of that fresh script then fails with
/// "Text file busy" (ETXTBSY). Hold it while a script is written and around every spawn.
pub fn spawn_gate() -> MutexGuard<'static, ()> {
    SPAWN_GATE.lock().unwrap_or_else(|e| e.into_inner())
}
