//! Notification diff (D5), pure: previous snapshot, next snapshot, expected-stop stamp, now.

use crate::snapshot::{Snapshot, StatusPoll};
use crate::types::Device;
use std::collections::HashSet;
use std::time::{Duration, SystemTime};

/// P15: a stop within this long of the stamp is expected.
pub const EXPECTED_STOP_WINDOW: Duration = Duration::from_secs(60);

/// A notification to show. Carries device names and listener words only, never a pairing code (P11).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Notice {
    DevicePaired {
        name: String,
    },
    /// `device_name` is `None` when the devices list has no such id.
    TerminalOpened {
        device_name: Option<String>,
        listener: String,
    },
    /// P20: the remote terminal switch went off to on.
    TerminalTurnedOn,
    /// P22: a device was newly granted terminal access. `device_name` is `None` for an unknown id.
    TerminalGranted {
        device_name: Option<String>,
    },
    HermesUnreachable,
    ServerStopped,
}

impl Notice {
    /// design.md section 6: the switch-on and the grant notices have no **Open** button.
    pub fn has_open_action(&self) -> bool {
        !matches!(
            self,
            Notice::TerminalTurnedOn | Notice::TerminalGranted { .. }
        )
    }
}

/// Notices for the change from `prev` to `next`. Silent on the first poll and on failed polls.
pub fn diff(
    prev: Option<&Snapshot>,
    next: &Snapshot,
    expected_stop: Option<SystemTime>,
    now: SystemTime,
) -> Vec<Notice> {
    let Some(prev) = prev else {
        return Vec::new();
    };
    // A failed or missing-CLI poll on either side tells us nothing: not a transition.
    if prev.answered().is_none() || next.answered().is_none() {
        return Vec::new();
    }
    let mut notices = Vec::new();
    notices.extend(new_devices(prev, next));
    // A device name comes from this poll's list, else the last good one (a failed `devices list` read
    // is not "no such device").
    let devices = next
        .devices
        .as_deref()
        .or(prev.devices.as_deref())
        .unwrap_or(&[]);
    notices.extend(new_terminals(prev, next, devices));
    notices.extend(terminal_access_widened(prev, next, devices));
    if hermes_went_down(prev, next) {
        notices.push(Notice::HermesUnreachable);
    }
    if server_stopped(prev, next) && !stop_expected(expected_stop, now) {
        notices.push(Notice::ServerStopped);
    }
    notices
}

/// True when the server was running in `prev` and is not in `next` (both answered).
pub fn server_stopped(prev: &Snapshot, next: &Snapshot) -> bool {
    matches!(prev.status, StatusPoll::Answered(_))
        && matches!(next.status, StatusPoll::Answered(_))
        && prev.server_running()
        && !next.server_running()
}

fn stop_expected(stamp: Option<SystemTime>, now: SystemTime) -> bool {
    stamp.is_some_and(|stamp| stamp_in_window(stamp, now))
}

/// P17: a stamp counts only while `now - 60 s <= stamp <= now + 60 s`. Older is a leftover; further
/// in the future is a clock step. Either way it is ignored (and the poller deletes it).
pub fn stamp_in_window(stamp: SystemTime, now: SystemTime) -> bool {
    let not_too_new = stamp <= now + EXPECTED_STOP_WINDOW;
    let not_too_old = now <= stamp + EXPECTED_STOP_WINDOW;
    not_too_new && not_too_old
}

fn new_devices(prev: &Snapshot, next: &Snapshot) -> Vec<Notice> {
    let (Some(before), Some(after)) = (&prev.devices, &next.devices) else {
        return Vec::new();
    };
    let known: HashSet<&str> = before.iter().map(|d| d.id.as_str()).collect();
    after
        .iter()
        .filter(|d| !known.contains(d.id.as_str()))
        .map(|d| Notice::DevicePaired {
            name: d.name.clone(),
        })
        .collect()
}

fn new_terminals(prev: &Snapshot, next: &Snapshot, devices: &[Device]) -> Vec<Notice> {
    let (Some(before), Some(after)) = (&prev.terminals, &next.terminals) else {
        return Vec::new();
    };
    let known: HashSet<&str> = before.iter().map(|t| t.id.as_str()).collect();
    after
        .iter()
        .filter(|t| !known.contains(t.id.as_str()))
        .map(|t| Notice::TerminalOpened {
            device_name: device_name(devices, &t.device_id),
            listener: t.listener.clone(),
        })
        .collect()
}

/// D7/P20/P22: the switch went off to on, and, separately, each newly granted id (switch on or off).
/// `None` on either side is "no information" (the `terminal status` read failed): silent.
fn terminal_access_widened(prev: &Snapshot, next: &Snapshot, devices: &[Device]) -> Vec<Notice> {
    let mut notices = Vec::new();
    if let (Some(false), Some(true)) = (prev.terminal_enabled, next.terminal_enabled) {
        notices.push(Notice::TerminalTurnedOn);
    }
    if let (Some(before), Some(after)) = (&prev.terminal_granted, &next.terminal_granted) {
        let known: HashSet<&str> = before.iter().map(String::as_str).collect();
        notices.extend(
            after
                .iter()
                .filter(|id| !known.contains(id.as_str()))
                .map(|id| Notice::TerminalGranted {
                    device_name: device_name(devices, id),
                }),
        );
    }
    notices
}

fn device_name(devices: &[Device], id: &str) -> Option<String> {
    devices.iter().find(|d| d.id == id).map(|d| d.name.clone())
}

/// Reachable -> reported but not reachable while the server stays up (a stop is `ServerStopped`'s job).
/// A server that omits `hermes` is silent (P23): absent is unknown, not unreachable.
fn hermes_went_down(prev: &Snapshot, next: &Snapshot) -> bool {
    let (Some(before), Some(after)) = (prev.answered(), next.answered()) else {
        return false;
    };
    before.is_running()
        && after.is_running()
        && before.hermes_reachable()
        && after.hermes_reported()
        && !after.hermes_reachable()
}
