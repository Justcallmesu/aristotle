//! Shared state of the window app: the CLI seam, the snapshotter and the latest snapshot.
//!
//! The window app polls only while it is open (D18); it never reads or deletes the expected-stop
//! stamp (that is the tray's job), so it uses `Snapshotter` and not the tray's `Poller`.

use crate::dto::{IpcError, IpcResult, SnapshotDto};
use aristotle_core::runner::{MissingCli, RealCli, ServerCli};
use aristotle_core::snapshot::{
    assess, desktop_version, Reason, Snapshot, Snapshotter, VersionCheck,
};
use aristotle_core::stamp::ExpectedStop;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};

pub struct Backend {
    pub(crate) cli: Arc<dyn ServerCli>,
    /// Absolute path of the server binary for the log child; `None` when it cannot be known.
    pub(crate) bin: Option<PathBuf>,
    pub(crate) stamp: Option<ExpectedStop>,
    app_version: Option<String>,
    snapshotter: Mutex<Snapshotter>,
    latest: Mutex<Option<Snapshot>>,
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

impl Backend {
    pub fn new(
        cli: Arc<dyn ServerCli>,
        bin: Option<PathBuf>,
        stamp: Option<ExpectedStop>,
        app_version: Option<&str>,
    ) -> Self {
        Self {
            cli,
            bin,
            stamp,
            app_version: app_version.map(str::to_owned),
            snapshotter: Mutex::new(Snapshotter::new(app_version)),
            latest: Mutex::new(None),
        }
    }

    /// The real server binary (`$ARISTOTLE_SERVER_BIN` or `~/.local/bin/aristotle-server`).
    pub fn from_env() -> Self {
        let bin = aristotle_core::runner::default_bin();
        let cli: Arc<dyn ServerCli> = match RealCli::from_env() {
            Some(real) => Arc::new(real),
            None => Arc::new(MissingCli),
        };
        Self::new(cli, bin, ExpectedStop::from_env(), desktop_version())
    }

    pub fn cli(&self) -> &dyn ServerCli {
        self.cli.as_ref()
    }

    /// Polls now and replaces the latest snapshot.
    pub fn refresh(&self) -> SnapshotDto {
        let snapshot = lock(&self.snapshotter).poll(self.cli.as_ref());
        let dto = self.dto(&snapshot);
        *lock(&self.latest) = Some(snapshot);
        dto
    }

    /// The latest snapshot, polling first when there is none yet.
    pub fn current(&self) -> SnapshotDto {
        let latest = lock(&self.latest).clone();
        match latest {
            Some(snapshot) => self.dto(&snapshot),
            None => self.refresh(),
        }
    }

    fn dto(&self, snapshot: &Snapshot) -> SnapshotDto {
        SnapshotDto::new(snapshot, assess(snapshot), self.app_version.as_deref())
    }

    /// Refuses an action while the server and the app disagree (P5/P17) or nothing is installed (P13).
    /// Reads are never guarded.
    pub fn guard_action(&self) -> IpcResult<()> {
        // Bound first: a lock taken in the `match` head would still be held when `refresh` locks it.
        let cached = lock(&self.latest).clone();
        let snapshot = match cached {
            Some(snapshot) => snapshot,
            None => {
                self.refresh();
                lock(&self.latest).clone().ok_or_else(IpcError::internal)?
            }
        };
        if matches!(snapshot.version, VersionCheck::Mismatch { .. }) {
            return Err(IpcError::version_mismatch());
        }
        match assess(&snapshot).reason {
            Reason::NotInstalled | Reason::CliMissing => Err(IpcError::not_installed()),
            _ => Ok(()),
        }
    }
}

/// An id or name from the frontend goes into argv as one token; one that starts with `-` would be read
/// as a flag by the CLI (protocol section 22 refuses it for `pair --name`).
pub fn plain_arg(value: &str) -> IpcResult<&str> {
    if value.is_empty() || value.starts_with('-') {
        return Err(IpcError::invalid_argument());
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::*;

    #[test]
    fn guard_refuses_a_version_mismatch() {
        let cli = FakeCli::new(running_poll());
        let err = backend(&cli, "9.9.9").guard_action().unwrap_err();
        assert_eq!(err.kind, "version-mismatch");
    }

    #[test]
    fn guard_refuses_when_the_service_is_not_installed() {
        let cli = FakeCli::new(vec![
            json("status_not_installed.json"),
            json("version.json"),
        ]);
        let err = backend(&cli, "1.2.3").guard_action().unwrap_err();
        assert_eq!(err.kind, "not-installed");
    }

    #[test]
    fn guard_refuses_when_the_cli_is_missing() {
        let cli = FakeCli::new(vec![Err(aristotle_core::runner::CliError::NotInstalled)]);
        let err = backend(&cli, "1.2.3").guard_action().unwrap_err();
        assert_eq!(err.kind, "not-installed");
    }

    #[test]
    fn guard_allows_an_installed_matching_server() {
        let cli = FakeCli::new(running_poll());
        assert!(backend(&cli, "1.2.3").guard_action().is_ok());
    }

    #[test]
    fn plain_arg_rejects_flags_and_empty() {
        assert!(plain_arg("-x").is_err());
        assert!(plain_arg("").is_err());
        assert_eq!(plain_arg("abc").unwrap(), "abc");
    }
}
