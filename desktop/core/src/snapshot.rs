//! One poll of the server (`Snapshot`), the tray state derived from it, and the version check (P5).

use crate::runner::{query, CliError, ServerCli};
use crate::types::{
    Device, DevicesList, OpenTerminal, ServerState, Status, TerminalStatus, VersionInfo,
    HERMES_REACHABLE,
};

/// The desktop build's own version, stamped by the release script (DS7). `None` for dev builds.
pub fn desktop_version() -> Option<&'static str> {
    option_env!("ARISTOTLE_VERSION")
}

/// Outcome of the P5 comparison.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VersionCheck {
    /// Not asked yet, or the question failed (retried on the next poll).
    Unchecked,
    /// A dev build on either side: nothing to compare.
    Skipped,
    Match,
    Mismatch {
        server: String,
        desktop: String,
    },
}

/// Unset, empty, `dev`, or a `-dev` version. The server's unstamped build prints `0.0.0-dev`, and
/// `scripts/build-server.sh` without a tag makes `0.0.0-dev+<sha>`: build metadata after `+` is ignored.
fn is_dev(version: &str) -> bool {
    let v = version.trim();
    let core = v.split('+').next().unwrap_or(v);
    core.is_empty() || core == "dev" || core.ends_with("-dev")
}

/// P5: compare the server's `version` string with this build's. Dev builds skip.
pub fn compare_versions(server: &str, desktop: Option<&str>) -> VersionCheck {
    let desktop = desktop.unwrap_or("");
    if is_dev(server) || is_dev(desktop) {
        return VersionCheck::Skipped;
    }
    if server.trim() == desktop.trim() {
        VersionCheck::Match
    } else {
        VersionCheck::Mismatch {
            server: server.trim().to_owned(),
            desktop: desktop.trim().to_owned(),
        }
    }
}

/// What the `status` call produced.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StatusPoll {
    Answered(Box<Status>),
    /// The CLI binary is missing: not installed (P13 covers the unit; this covers the binary).
    CliMissing,
    /// Timeout, garbage, `{"error"}`: the poll told us nothing. Not a transition (D5).
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Snapshot {
    pub status: StatusPoll,
    /// `None` unless the server answered and the call worked.
    pub devices: Option<Vec<Device>>,
    pub terminals: Option<Vec<OpenTerminal>>,
    pub version: VersionCheck,
}

impl Snapshot {
    pub fn answered(&self) -> Option<&Status> {
        match &self.status {
            StatusPoll::Answered(s) => Some(s),
            _ => None,
        }
    }

    pub fn server_running(&self) -> bool {
        self.answered().is_some_and(Status::is_running)
    }

    /// The unit file exists. A missing CLI counts as not installed.
    pub fn installed(&self) -> bool {
        self.answered().is_some_and(|s| s.installed)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayState {
    Green,
    Amber,
    Red,
}

/// Why the tray has its state; picks the tooltip.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reason {
    AllOk,
    HermesDown,
    /// `hermes` absent from the status (older server): unknown is not healthy (P17).
    HermesUnknown,
    ServerStopped,
    /// Server answers but `service` is not `active`: started by hand, or `unknown` (P17).
    RunningOutsideService,
    NotInstalled,
    NoAnswer,
    CliMissing,
    VersionMismatch,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Assessment {
    pub state: TrayState,
    pub reason: Reason,
}

/// Pure mapping from a snapshot to the tray state and its reason.
pub fn assess(snapshot: &Snapshot) -> Assessment {
    let (state, reason) = match &snapshot.status {
        StatusPoll::CliMissing => (TrayState::Red, Reason::CliMissing),
        StatusPoll::Failed => (TrayState::Red, Reason::NoAnswer),
        StatusPoll::Answered(status) => assess_status(status, &snapshot.version),
    };
    Assessment { state, reason }
}

/// design.md section 4.1 rows 2-9, first match wins (row 1 and the failed poll are in `assess`).
fn assess_status(status: &Status, version: &VersionCheck) -> (TrayState, Reason) {
    if matches!(version, VersionCheck::Mismatch { .. }) {
        return (TrayState::Red, Reason::VersionMismatch);
    }
    if !status.installed {
        return (TrayState::Red, Reason::NotInstalled);
    }
    if status.server == ServerState::NoAnswer {
        return (TrayState::Red, Reason::NoAnswer);
    }
    if !status.is_running() {
        return (TrayState::Red, Reason::ServerStopped);
    }
    if !status.service_active() {
        return (TrayState::Red, Reason::RunningOutsideService);
    }
    match status.result.as_ref().and_then(|r| r.hermes.as_ref()) {
        Some(h) if h.state == HERMES_REACHABLE => (TrayState::Green, Reason::AllOk),
        Some(_) => (TrayState::Amber, Reason::HermesDown),
        None => (TrayState::Amber, Reason::HermesUnknown),
    }
}

pub fn tray_state(snapshot: &Snapshot) -> TrayState {
    assess(snapshot).state
}

/// Polls through a [`ServerCli`] and remembers when the version must be asked again.
pub struct Snapshotter {
    desktop_version: Option<String>,
    /// The `version --json` check; asked again after each (re)start.
    version: VersionCheck,
    was_running: bool,
    /// Answered polls since `version --json` was last asked.
    polls_since_version: u32,
    /// `status.result.version` of the last answered poll that had one.
    last_status_version: Option<String>,
}

/// `version --json` is asked again after this many answered polls (DF4).
const VERSION_RECHECK_POLLS: u32 = 12;

impl Snapshotter {
    pub fn new(desktop_version: Option<&str>) -> Self {
        Self {
            desktop_version: desktop_version.map(str::to_owned),
            version: VersionCheck::Unchecked,
            was_running: false,
            polls_since_version: 0,
            last_status_version: None,
        }
    }

    /// One poll: `status`, then `devices list` and `terminal status` only when the server answers.
    pub fn poll(&mut self, cli: &dyn ServerCli) -> Snapshot {
        let status = match query::<Status>(cli, &["status"]) {
            Ok(reply) => StatusPoll::Answered(Box::new(reply.value)),
            Err(CliError::NotInstalled) => StatusPoll::CliMissing,
            // `status` exits 1 with a success object when the service is down: that is `Ok` above.
            Err(_) => StatusPoll::Failed,
        };
        let running = matches!(&status, StatusPoll::Answered(s) if s.is_running());
        if running && !self.was_running {
            // The server (re)started: ask the version again (P5).
            self.version = VersionCheck::Unchecked;
        }
        self.was_running = running;
        if matches!(status, StatusPoll::Answered(_)) && self.version_due(&status) {
            self.refresh_version(cli);
        }
        let (devices, terminals) = if running {
            (fetch_devices(cli), fetch_terminals(cli))
        } else {
            (None, None)
        };
        let status_check = self.check_running_version(&status);
        Snapshot {
            status,
            devices,
            terminals,
            version: combine_checks(&self.version, &status_check),
        }
    }

    /// P5/P17: the running server's own `status.result.version`, checked on every poll.
    fn check_running_version(&self, status: &StatusPoll) -> VersionCheck {
        match status {
            StatusPoll::Answered(s) => match &s.result {
                Some(r) => compare_versions(&r.version, self.desktop_version.as_deref()),
                None => VersionCheck::Unchecked,
            },
            _ => VersionCheck::Unchecked,
        }
    }

    /// Not asked yet, 12 answered polls since the last ask, or the running server's version changed
    /// (an installed-but-not-restarted binary, DF4).
    fn version_due(&mut self, status: &StatusPoll) -> bool {
        self.polls_since_version += 1;
        let seen = match status {
            StatusPoll::Answered(s) => s.result.as_ref().map(|r| r.version.clone()),
            _ => None,
        };
        let changed = matches!((&self.last_status_version, &seen), (Some(a), Some(b)) if a != b);
        if seen.is_some() {
            self.last_status_version = seen;
        }
        self.version == VersionCheck::Unchecked
            || self.polls_since_version >= VERSION_RECHECK_POLLS
            || changed
    }

    fn refresh_version(&mut self, cli: &dyn ServerCli) {
        if let Ok(reply) = query::<VersionInfo>(cli, &["version"]) {
            self.polls_since_version = 0;
            self.version = compare_versions(&reply.value.version, self.desktop_version.as_deref());
        }
    }
}

/// A mismatch on either side wins (the first one found: the CLI's); else the CLI's answer stands.
fn combine_checks(cli: &VersionCheck, running: &VersionCheck) -> VersionCheck {
    match (cli, running) {
        (VersionCheck::Mismatch { .. }, _) => cli.clone(),
        (_, VersionCheck::Mismatch { .. }) => running.clone(),
        _ => cli.clone(),
    }
}

fn fetch_devices(cli: &dyn ServerCli) -> Option<Vec<Device>> {
    query::<DevicesList>(cli, &["devices", "list"])
        .ok()
        .map(|r| r.value.devices)
}

fn fetch_terminals(cli: &dyn ServerCli) -> Option<Vec<OpenTerminal>> {
    query::<TerminalStatus>(cli, &["terminal", "status"])
        .ok()
        .map(|r| r.value.open)
}
