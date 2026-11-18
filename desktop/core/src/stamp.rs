//! Expected-stop stamp (P15): `$XDG_RUNTIME_DIR/aristotle-desktop/expected-stop`, unix seconds.
//!
//! The window app writes it just before it runs `stop` or `restart`; the tray reads it to tell an
//! app-issued stop from a crash, and deletes it once it has seen the stop.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const DIR_NAME: &str = "aristotle-desktop";
const FILE_NAME: &str = "expected-stop";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExpectedStop {
    path: PathBuf,
}

impl ExpectedStop {
    /// Stamp under an explicit runtime dir (tests).
    pub fn in_runtime_dir(runtime_dir: impl Into<PathBuf>) -> Self {
        Self {
            path: runtime_dir.into().join(DIR_NAME).join(FILE_NAME),
        }
    }

    /// From `$XDG_RUNTIME_DIR`. `None` when it is unset or empty: stops then notify.
    pub fn from_env() -> Option<Self> {
        let dir = std::env::var("XDG_RUNTIME_DIR")
            .ok()
            .filter(|v| !v.is_empty())?;
        Some(Self::in_runtime_dir(dir))
    }

    pub fn path(&self) -> &std::path::Path {
        &self.path
    }

    /// The stamped time, or `None` when missing or unreadable.
    pub fn read(&self) -> Option<SystemTime> {
        let text = fs::read_to_string(&self.path).ok()?;
        let secs: u64 = text.trim().parse().ok()?;
        Some(UNIX_EPOCH + Duration::from_secs(secs))
    }

    /// Writes `at` (whole seconds) via a temp file and rename, so a reader never sees half a number.
    pub fn write(&self, at: SystemTime) -> io::Result<()> {
        let secs = at
            .duration_since(UNIX_EPOCH)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?
            .as_secs();
        let dir = self.path.parent().unwrap_or(std::path::Path::new("."));
        fs::create_dir_all(dir)?;
        let tmp = self.path.with_extension("tmp");
        fs::write(&tmp, format!("{secs}\n"))?;
        fs::rename(&tmp, &self.path)
    }

    /// Deletes the stamp; a missing file is fine.
    pub fn delete(&self) -> io::Result<()> {
        match fs::remove_file(&self.path) {
            Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
            _ => Ok(()),
        }
    }
}
