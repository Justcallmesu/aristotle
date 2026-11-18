//! Shapes of the CLI JSON contract (docs/protocol.md section 22).
//!
//! Fields marked `?` there are `Option` or `#[serde(default)]` here. Unknown fields are ignored
//! (serde's default), so a field the server adds later does not break the desktop apps.

use serde::{Deserialize, Serialize};
use std::fmt;

/// `version --json`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct VersionInfo {
    pub version: String,
    pub commit: String,
    /// The phone API version. Not the CLI contract version: never compared by the desktop (P5).
    pub protocol: u32,
}

/// The `server` word of `status --json`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Serialize)]
pub enum ServerState {
    #[serde(rename = "running")]
    Running,
    #[serde(rename = "not-running")]
    NotRunning,
    #[serde(rename = "no-answer")]
    NoAnswer,
    /// A word a newer server may add; treated as "not running" everywhere.
    #[serde(other)]
    Other,
}

/// `status --json`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Status {
    /// systemctl `is-active` word; `"unknown"` when systemctl printed nothing.
    pub service: String,
    pub enabled: String,
    pub installed: bool,
    pub server: ServerState,
    /// Present only when `server` is `running`.
    #[serde(default)]
    pub result: Option<StatusResult>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct StatusResult {
    pub version: String,
    /// Always an array.
    #[serde(default)]
    pub addresses: Vec<Address>,
    #[serde(default)]
    pub port: Option<u16>,
    /// Absent (not `[]`) when nothing is bound.
    #[serde(default)]
    pub listeners: Option<Vec<Listener>>,
    #[serde(default)]
    pub hermes: Option<Hermes>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Address {
    pub url: String,
    pub kind: String,
    pub iface: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Listener {
    pub kind: String,
    pub iface: String,
    pub ip: String,
    #[serde(default)]
    pub subnet: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Hermes {
    pub state: String,
    #[serde(default)]
    pub reason: Option<String>,
}

/// The `hermes.state` word that means Hermes answers.
pub const HERMES_REACHABLE: &str = "reachable";

impl Status {
    pub fn is_running(&self) -> bool {
        self.server == ServerState::Running
    }

    /// `systemctl --user is-active` said `active`.
    pub fn service_active(&self) -> bool {
        self.service == "active"
    }

    /// True only when the server answered and reports Hermes as reachable.
    pub fn hermes_reachable(&self) -> bool {
        self.result
            .as_ref()
            .and_then(|r| r.hermes.as_ref())
            .is_some_and(|h| h.state == HERMES_REACHABLE)
    }
}

/// A paired phone (`devices.Public`).
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Device {
    pub id: String,
    pub name: String,
    pub created_at: String,
    pub last_seen_at: String,
}

/// `devices list --json`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct DevicesList {
    #[serde(default)]
    pub devices: Vec<Device>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct GrantedDevice {
    pub id: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct OpenTerminal {
    pub id: String,
    pub device_id: String,
    pub listener: String,
    pub since: String,
    pub pid: u32,
}

/// `terminal status|enable|disable --json` and `devices grant-terminal|ungrant-terminal --json`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct TerminalStatus {
    pub enabled: bool,
    #[serde(default)]
    pub granted: Vec<GrantedDevice>,
    #[serde(default)]
    pub open: Vec<OpenTerminal>,
    #[serde(default)]
    pub changed: bool,
    #[serde(default)]
    pub warning: Option<String>,
    #[serde(default)]
    pub problem: Option<String>,
    #[serde(default)]
    pub device: Option<Device>,
}

#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct PairAddress {
    pub url: String,
    pub kind: String,
    pub iface: String,
    pub default_route: bool,
}

/// `pair --json`. Carries the pairing code: `Debug` hides it (P11).
#[derive(Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct Pair {
    pub code: String,
    pub expires_at: String,
    pub fingerprint: String,
    #[serde(default)]
    pub addresses: Vec<PairAddress>,
    pub qr_payload: String,
    /// The QR matrix of `qr_payload`, one `0`/`1` string per row, no quiet zone (P19). Absent when
    /// the server could not encode it; left out of the IPC reply then, so the UI sees `undefined`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub qr_modules: Option<Vec<String>>,
    #[serde(default)]
    pub qr_error: Option<String>,
}

impl fmt::Debug for Pair {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Pair")
            .field("code", &"<hidden>")
            .field("expires_at", &self.expires_at)
            .field("qr_payload", &"<hidden>")
            .field("qr_modules", &"<hidden>")
            .finish_non_exhaustive()
    }
}

/// `start|stop|restart --json`.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct ActionResult {
    pub ok: bool,
    /// systemctl `is-active` word after the verb.
    pub service: String,
}

/// `{"error":"<text>"}` printed on failure.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize, Serialize)]
pub struct ErrorBody {
    pub error: String,
}
