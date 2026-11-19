//! The tray state and its reason, derived from a snapshot (design.md section 4.1).

use crate::snapshot::{Snapshot, StatusPoll, VersionCheck};
use crate::types::{ServerState, Status, HERMES_REACHABLE};

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
