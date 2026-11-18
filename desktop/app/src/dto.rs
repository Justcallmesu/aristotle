//! What crosses the IPC boundary: the snapshot with its assessment, and the typed error.
//!
//! Core's poll types are not `Serialize`; they are mapped here with exhaustive matches, so a new
//! core variant fails to compile until the frontend contract is updated.

use aristotle_core::runner::CliError;
use aristotle_core::snapshot::{Assessment, Reason, Snapshot, StatusPoll, TrayState, VersionCheck};
use aristotle_core::types::{Device, OpenTerminal, Status};
use serde::Serialize;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum StatusDto {
    Answered { status: Box<Status> },
    CliMissing,
    Failed,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum VersionDto {
    Unchecked,
    Skipped,
    Match,
    Mismatch { server: String, desktop: String },
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct AssessmentDto {
    /// `green`, `amber` or `red`.
    pub state: &'static str,
    /// The design.md section 4.1 row, in kebab-case.
    pub reason: &'static str,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct SnapshotDto {
    pub status: StatusDto,
    pub devices: Option<Vec<Device>>,
    pub terminals: Option<Vec<OpenTerminal>>,
    /// The terminal switch from the same poll (the sidebar count); `None` when that read failed.
    pub terminal_enabled: Option<bool>,
    /// Grows with every poll; the frontend drops a snapshot older than the one it shows.
    pub seq: u64,
    pub version: VersionDto,
    pub assessment: AssessmentDto,
    /// This build's version; `None` for dev builds (P5).
    pub app_version: Option<String>,
}

impl SnapshotDto {
    pub fn new(
        snapshot: &Snapshot,
        assessment: Assessment,
        app_version: Option<&str>,
        seq: u64,
    ) -> Self {
        Self {
            status: match &snapshot.status {
                StatusPoll::Answered(status) => StatusDto::Answered {
                    status: status.clone(),
                },
                StatusPoll::CliMissing => StatusDto::CliMissing,
                StatusPoll::Failed => StatusDto::Failed,
            },
            devices: snapshot.devices.clone(),
            terminals: snapshot.terminals.clone(),
            terminal_enabled: snapshot.terminal_enabled,
            seq,
            version: match &snapshot.version {
                VersionCheck::Unchecked => VersionDto::Unchecked,
                VersionCheck::Skipped => VersionDto::Skipped,
                VersionCheck::Match => VersionDto::Match,
                VersionCheck::Mismatch { server, desktop } => VersionDto::Mismatch {
                    server: server.clone(),
                    desktop: desktop.clone(),
                },
            },
            assessment: assessment_dto(assessment),
            app_version: app_version.map(str::to_owned),
        }
    }
}

fn assessment_dto(a: Assessment) -> AssessmentDto {
    let state = match a.state {
        TrayState::Green => "green",
        TrayState::Amber => "amber",
        TrayState::Red => "red",
    };
    let reason = match a.reason {
        Reason::AllOk => "all-ok",
        Reason::HermesDown => "hermes-down",
        Reason::HermesUnknown => "hermes-unknown",
        Reason::ServerStopped => "server-stopped",
        Reason::RunningOutsideService => "running-outside-service",
        Reason::NotInstalled => "not-installed",
        Reason::NoAnswer => "no-answer",
        Reason::CliMissing => "cli-missing",
        Reason::VersionMismatch => "version-mismatch",
    };
    AssessmentDto { state, reason }
}

/// Every command returns `Result<T, IpcError>`. `kind` is a core `CliError::kind()` code or one of the
/// app's own: `version-mismatch`, `not-installed` (guard), `invalid-argument`, `internal`.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct IpcError {
    pub kind: &'static str,
    /// Only the server's own `{"error"}` text. It is shown to the user and never logged.
    pub message: Option<String>,
    pub exit_code: Option<i32>,
}

impl IpcError {
    pub fn new(kind: &'static str) -> Self {
        Self {
            kind,
            message: None,
            exit_code: None,
        }
    }

    pub fn version_mismatch() -> Self {
        Self::new("version-mismatch")
    }

    pub fn not_installed() -> Self {
        Self::new("not-installed")
    }

    pub fn invalid_argument() -> Self {
        Self::new("invalid-argument")
    }

    pub fn internal() -> Self {
        Self::new("internal")
    }
}

impl From<CliError> for IpcError {
    fn from(error: CliError) -> Self {
        let kind = error.kind();
        match error {
            CliError::Server { message, exit_code } => Self {
                kind,
                message: Some(message),
                exit_code: Some(exit_code),
            },
            CliError::BadOutput { exit_code } => Self {
                kind,
                message: None,
                exit_code: Some(exit_code),
            },
            _ => Self::new(kind),
        }
    }
}

pub type IpcResult<T> = Result<T, IpcError>;
