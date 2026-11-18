//! Notification diff cases (D5, P15).

mod common;
use aristotle_core::diff::*;
use aristotle_core::snapshot::Snapshot;
use common::*;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn now() -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(1_800_000_000)
}

fn ago(secs: u64) -> Option<SystemTime> {
    Some(now() - Duration::from_secs(secs))
}

fn with_devices(mut s: Snapshot, devices: Vec<aristotle_core::types::Device>) -> Snapshot {
    s.devices = Some(devices);
    s
}

fn with_terminals(mut s: Snapshot, t: Vec<aristotle_core::types::OpenTerminal>) -> Snapshot {
    s.terminals = Some(t);
    s
}

fn up(hermes: &str) -> Snapshot {
    snap(running(Some(hermes)))
}

fn down() -> Snapshot {
    snap(status(
        "inactive",
        true,
        aristotle_core::types::ServerState::NotRunning,
        None,
    ))
}

#[test]
fn first_poll_never_notifies() {
    let next = with_terminals(
        with_devices(up("reachable"), vec![device("d1", "pixel")]),
        vec![terminal("t1", "d1")],
    );
    assert!(diff(None, &next, None, now()).is_empty());
    assert!(diff(None, &down(), None, now()).is_empty());
}

#[test]
fn new_device_is_announced_by_name() {
    let prev = with_devices(up("reachable"), vec![device("d1", "pixel")]);
    let next = with_devices(
        up("reachable"),
        vec![device("d1", "pixel"), device("d2", "tablet")],
    );
    assert_eq!(
        diff(Some(&prev), &next, None, now()),
        vec![Notice::DevicePaired {
            name: "tablet".into()
        }]
    );
    assert!(diff(Some(&next), &next, None, now()).is_empty());
}

#[test]
fn device_list_missing_on_one_side_is_silent() {
    let prev = up("reachable");
    let next = with_devices(up("reachable"), vec![device("d1", "pixel")]);
    assert!(diff(Some(&prev), &next, None, now()).is_empty());
}

#[test]
fn new_terminal_names_the_device() {
    let devices = vec![device("d1", "pixel")];
    let prev = with_terminals(with_devices(up("reachable"), devices.clone()), vec![]);
    let next = with_terminals(
        with_devices(up("reachable"), devices),
        vec![terminal("t1", "d1")],
    );
    assert_eq!(
        diff(Some(&prev), &next, None, now()),
        vec![Notice::TerminalOpened {
            device_name: Some("pixel".into()),
            listener: "lan".into()
        }]
    );
}

#[test]
fn new_terminal_with_unknown_device_has_no_name() {
    let prev = with_terminals(up("reachable"), vec![]);
    let next = with_terminals(up("reachable"), vec![terminal("t1", "ghost")]);
    assert_eq!(
        diff(Some(&prev), &next, None, now()),
        vec![Notice::TerminalOpened {
            device_name: None,
            listener: "lan".into()
        }]
    );
}

#[test]
fn existing_terminal_is_not_new() {
    let t = with_terminals(up("reachable"), vec![terminal("t1", "d1")]);
    assert!(diff(Some(&t), &t, None, now()).is_empty());
}

#[test]
fn hermes_flap_notifies_once_per_transition() {
    let good = up("reachable");
    let bad = up("unreachable");
    // reachable -> not: one notice
    assert_eq!(
        diff(Some(&good), &bad, None, now()),
        vec![Notice::HermesUnreachable]
    );
    // stays down: nothing
    assert!(diff(Some(&bad), &bad, None, now()).is_empty());
    // comes back: nothing (only the loss notifies)
    assert!(diff(Some(&bad), &good, None, now()).is_empty());
    // down again: a second notice
    assert_eq!(
        diff(Some(&good), &bad, None, now()),
        vec![Notice::HermesUnreachable]
    );
}

#[test]
fn hermes_going_absent_counts_as_unreachable() {
    let prev = up("reachable");
    let next = snap(running(None));
    assert_eq!(
        diff(Some(&prev), &next, None, now()),
        vec![Notice::HermesUnreachable]
    );
}

#[test]
fn hermes_notice_is_not_added_when_the_server_stopped() {
    let n = diff(Some(&up("reachable")), &down(), None, now());
    assert_eq!(n, vec![Notice::ServerStopped]);
}

#[test]
fn failed_poll_is_not_a_transition() {
    let good = up("reachable");
    assert!(diff(Some(&good), &failed(), None, now()).is_empty());
    assert!(diff(Some(&failed()), &down(), None, now()).is_empty());
    let missing = Snapshot {
        status: aristotle_core::snapshot::StatusPoll::CliMissing,
        ..failed()
    };
    assert!(diff(Some(&good), &missing, None, now()).is_empty());
}

#[test]
fn stop_without_stamp_notifies() {
    assert_eq!(
        diff(Some(&up("reachable")), &down(), None, now()),
        vec![Notice::ServerStopped]
    );
}

#[test]
fn stop_with_fresh_stamp_is_silent() {
    assert!(diff(Some(&up("reachable")), &down(), ago(5), now()).is_empty());
    assert!(diff(Some(&up("reachable")), &down(), ago(60), now()).is_empty());
}

#[test]
fn stop_with_stale_stamp_notifies() {
    assert_eq!(
        diff(Some(&up("reachable")), &down(), ago(61), now()),
        vec![Notice::ServerStopped]
    );
}

#[test]
fn stop_with_future_stamp_counts_as_fresh() {
    let future = Some(now() + Duration::from_secs(30));
    assert!(diff(Some(&up("reachable")), &down(), future, now()).is_empty());
}

#[test]
fn already_stopped_does_not_notify_again() {
    assert!(diff(Some(&down()), &down(), None, now()).is_empty());
}

#[test]
fn notices_carry_only_names_ids_and_listeners() {
    // The pairing code is not in any polled shape; the Notice type has no field for it.
    let prev = with_devices(up("reachable"), vec![]);
    let next = with_devices(up("reachable"), vec![device("d2", "tablet")]);
    let shown = format!("{:?}", diff(Some(&prev), &next, None, now()));
    assert!(shown.contains("tablet") && !shown.to_lowercase().contains("code"));
}

#[test]
fn stop_with_stamp_61s_in_the_future_notifies() {
    let prev = up("reachable");
    let at = Some(now() + Duration::from_secs(61));
    assert_eq!(
        diff(Some(&prev), &down(), at, now()),
        vec![Notice::ServerStopped]
    );
}

#[test]
fn stop_with_stamp_exactly_at_the_window_edges_is_expected() {
    let prev = up("reachable");
    for stamp in [
        now() + Duration::from_secs(60),
        now() - Duration::from_secs(60),
    ] {
        assert!(diff(Some(&prev), &down(), Some(stamp), now()).is_empty());
    }
}

#[test]
fn stop_with_stamp_61s_old_notifies() {
    let prev = up("reachable");
    assert_eq!(
        diff(Some(&prev), &down(), ago(61), now()),
        vec![Notice::ServerStopped]
    );
}

#[test]
fn stamp_in_window_is_symmetric_around_now() {
    let s = |d: i64| {
        if d >= 0 {
            now() + Duration::from_secs(d as u64)
        } else {
            now() - Duration::from_secs((-d) as u64)
        }
    };
    assert!(stamp_in_window(s(0), now()));
    assert!(stamp_in_window(s(-60), now()));
    assert!(stamp_in_window(s(60), now()));
    assert!(!stamp_in_window(s(-61), now()));
    assert!(!stamp_in_window(s(61), now()));
}
