//! Shared core of the Aristotle desktop apps (spec P16): no GUI dependency.
//!
//! - [`types`]: the CLI JSON shapes (protocol section 22)
//! - [`runner`]: runs `aristotle-server <cmd> --json`
//! - [`snapshot`]: one poll, tray state, version check
//! - [`diff`]: notification diff
//! - [`stamp`]: expected-stop stamp
//! - [`strings`]: English and Indonesian texts
//! - [`poller`]: the loop body the tray runs every 5 s

pub mod diff;
pub mod poller;
pub mod runner;
pub mod snapshot;
pub mod stamp;
pub mod strings;
pub mod types;

/// Poll interval (spec P1).
pub const POLL_INTERVAL: std::time::Duration = std::time::Duration::from_secs(5);
