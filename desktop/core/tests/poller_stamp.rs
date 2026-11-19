//! The expected-stop stamp and the poller that ties snapshot, diff and stamp together.

mod common;
use aristotle_core::diff::Notice;
use aristotle_core::poller::Poller;
use aristotle_core::runner::{CliError, RawOutput};
use aristotle_core::stamp::ExpectedStop;
use common::*;
use std::fs;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

fn tempdir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("aristotle-stamp-{tag}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&d);
    fs::create_dir_all(&d).unwrap();
    d
}

fn at(secs: u64) -> SystemTime {
    UNIX_EPOCH + Duration::from_secs(secs)
}

#[test]
fn stamp_write_read_delete_roundtrip() {
    let dir = tempdir("rt");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    assert_eq!(stamp.read(), None);
    stamp.write(at(1_800_000_000)).unwrap();
    assert_eq!(stamp.read(), Some(at(1_800_000_000)));
    assert!(stamp.path().ends_with("aristotle-desktop/expected-stop"));
    stamp.delete().unwrap();
    assert_eq!(stamp.read(), None);
    stamp.delete().unwrap(); // already gone: fine
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn stamp_garbage_reads_as_none() {
    let dir = tempdir("garbage");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    stamp.write(at(5)).unwrap();
    fs::write(stamp.path(), "soon").unwrap();
    assert_eq!(stamp.read(), None);
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn stamp_without_runtime_dir_is_none() {
    let saved = std::env::var("XDG_RUNTIME_DIR").ok();
    std::env::remove_var("XDG_RUNTIME_DIR");
    assert!(ExpectedStop::from_env().is_none());
    std::env::set_var("XDG_RUNTIME_DIR", "");
    assert!(ExpectedStop::from_env().is_none());
    if let Some(v) = saved {
        std::env::set_var("XDG_RUNTIME_DIR", v);
    } else {
        std::env::remove_var("XDG_RUNTIME_DIR");
    }
}

fn up_replies() -> Vec<Result<aristotle_core::runner::RawOutput, aristotle_core::runner::CliError>>
{
    vec![
        ok(&fixture("status.json"), 0),
        ok(&fixture("devices_list.json"), 0),
        ok(&fixture("terminal_status.json"), 0),
    ]
}

fn down_reply() -> Vec<Result<aristotle_core::runner::RawOutput, aristotle_core::runner::CliError>>
{
    vec![ok(&fixture("status_not_running.json"), 1)]
}

#[test]
fn poller_stop_with_fresh_stamp_is_silent_and_consumes_the_stamp() {
    let dir = tempdir("fresh");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    let mut p = Poller::new(None, Some(stamp.clone()));
    let now = at(1_800_000_000);
    // dev build: version call happens on the first poll
    let first = [
        vec![
            ok(&fixture("status.json"), 0),
            ok(&fixture("version.json"), 0),
        ],
        up_replies()[1..].to_vec(),
    ]
    .concat();
    assert!(p.tick(&FakeCli::new(first), now).notices.is_empty());
    stamp.write(now - Duration::from_secs(3)).unwrap();
    let t = p.tick(&FakeCli::new([down_reply(), vec![]].concat()), now);
    assert!(t.notices.is_empty());
    assert_eq!(stamp.read(), None, "stamp must be single-use");
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn poller_stop_without_stamp_notifies_and_failed_poll_keeps_the_baseline() {
    let mut p = Poller::new(None, None);
    let now = at(1_800_000_000);
    let first = [
        vec![
            ok(&fixture("status.json"), 0),
            ok(&fixture("version.json"), 0),
        ],
        up_replies()[1..].to_vec(),
    ]
    .concat();
    p.tick(&FakeCli::new(first), now);
    // a timeout in between must not lose the "was running" baseline
    let t = p.tick(
        &FakeCli::new(vec![Err(aristotle_core::runner::CliError::Timeout)]),
        now,
    );
    assert!(t.notices.is_empty());
    let t = p.tick(&FakeCli::new(down_reply()), now);
    assert_eq!(t.notices, vec![Notice::ServerStopped]);
}

fn first_tick(p: &mut Poller, now: SystemTime) {
    let first = [
        vec![
            ok(&fixture("status.json"), 0),
            ok(&fixture("version.json"), 0),
        ],
        up_replies()[1..].to_vec(),
    ]
    .concat();
    assert!(p.tick(&FakeCli::new(first), now).notices.is_empty());
}

#[test]
fn poller_deletes_a_stale_stamp_even_when_no_stop_was_seen() {
    let dir = tempdir("stale");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    let mut p = Poller::new(None, Some(stamp.clone()));
    let now = at(1_800_000_000);
    first_tick(&mut p, now);
    stamp.write(now - Duration::from_secs(120)).unwrap();
    let t = p.tick(&FakeCli::new(up_replies()), now);
    assert!(t.notices.is_empty());
    assert_eq!(
        stamp.read(),
        None,
        "a stamp 120 s old is deleted on any tick"
    );
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn poller_deletes_a_future_stamp_and_the_next_crash_notifies() {
    let dir = tempdir("future");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    let mut p = Poller::new(None, Some(stamp.clone()));
    let now = at(1_800_000_000);
    first_tick(&mut p, now);
    stamp.write(now + Duration::from_secs(61)).unwrap();
    p.tick(&FakeCli::new(up_replies()), now);
    assert_eq!(stamp.read(), None, "a stamp 61 s ahead is deleted");
    let t = p.tick(&FakeCli::new(down_reply()), now);
    assert_eq!(t.notices, vec![Notice::ServerStopped]);
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn poller_keeps_an_in_window_stamp_until_the_stop_is_seen() {
    let dir = tempdir("keep");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    let mut p = Poller::new(None, Some(stamp.clone()));
    let now = at(1_800_000_000);
    first_tick(&mut p, now);
    stamp.write(now - Duration::from_secs(30)).unwrap();
    p.tick(&FakeCli::new(up_replies()), now);
    assert!(stamp.read().is_some());
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn restart_without_an_observed_stop_leaves_no_stamp_after_60_s() {
    let dir = tempdir("fastrestart");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    let mut p = Poller::new(None, Some(stamp.clone()));
    let t0 = at(1_800_000_000);
    first_tick(&mut p, t0);
    // The app stamps, restarts the server faster than one poll: the tray never sees "stopped".
    stamp.write(t0).unwrap();
    p.tick(&FakeCli::new(up_replies()), t0 + Duration::from_secs(5));
    p.tick(&FakeCli::new(up_replies()), t0 + Duration::from_secs(61));
    assert_eq!(stamp.read(), None, "no stamp 61 s after it was written");
    // A crash a little later is not silenced by the leftover.
    let t = p.tick(&FakeCli::new(down_reply()), t0 + Duration::from_secs(66));
    assert_eq!(t.notices, vec![Notice::ServerStopped]);
    let _ = fs::remove_dir_all(dir);
}

fn devices_json(ids: &[(&str, &str)]) -> String {
    let items: Vec<String> = ids
        .iter()
        .map(|(id, name)| {
            format!(
                r#"{{"id":"{id}","name":"{name}","created_at":"2026-11-01T10:00:00Z","last_seen_at":"2026-11-18T09:30:00Z"}}"#
            )
        })
        .collect();
    format!(r#"{{"devices":[{}]}}"#, items.join(","))
}

#[test]
fn a_failed_devices_poll_keeps_the_baseline_so_a_new_phone_still_notifies_once() {
    let mut p = Poller::new(None, None);
    let now = at(1_800_000_000);
    let status = || ok(&fixture("status.json"), 0);
    let terminals = || ok(&fixture("terminal_status.json"), 0);
    let one = devices_json(&[("d1", "pixel")]);
    let two = devices_json(&[("d1", "pixel"), ("d2", "tablet")]);
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            ok(&fixture("version.json"), 0),
            ok(&one, 0),
            terminals(),
        ]),
        now,
    );
    assert!(t.notices.is_empty());
    // devices list fails; the phone is paired meanwhile
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            Err(aristotle_core::runner::CliError::Timeout),
            terminals(),
        ]),
        now,
    );
    assert!(t.notices.is_empty());
    let t = p.tick(&FakeCli::new(vec![status(), ok(&two, 0), terminals()]), now);
    assert_eq!(
        t.notices,
        vec![Notice::DevicePaired {
            name: "tablet".into()
        }]
    );
    let t = p.tick(&FakeCli::new(vec![status(), ok(&two, 0), terminals()]), now);
    assert!(t.notices.is_empty(), "announced once");
}

#[test]
fn a_failed_terminal_poll_keeps_the_baseline_so_a_new_terminal_still_notifies_once() {
    let mut p = Poller::new(None, None);
    let now = at(1_800_000_000);
    let status = || ok(&fixture("status.json"), 0);
    let devices = || ok(&fixture("devices_list.json"), 0);
    let none = ok(&fixture("terminal_status_empty.json"), 0);
    p.tick(
        &FakeCli::new(vec![
            status(),
            ok(&fixture("version.json"), 0),
            devices(),
            none,
        ]),
        now,
    );
    p.tick(
        &FakeCli::new(vec![
            status(),
            devices(),
            Err(aristotle_core::runner::CliError::Timeout),
        ]),
        now,
    );
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            devices(),
            ok(&fixture("terminal_status.json"), 0),
        ]),
        now,
    );
    // The baseline had the switch off and no grants, so the fixture also turns it on and grants pixel (D7).
    let opened = |n: &&Notice| matches!(n, Notice::TerminalOpened { .. });
    assert_eq!(t.notices.iter().filter(opened).count(), 1);
    assert_eq!(t.notices.len(), 3);
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            devices(),
            ok(&fixture("terminal_status.json"), 0),
        ]),
        now,
    );
    assert!(t.notices.is_empty(), "announced once");
}

#[test]
fn a_failed_terminal_read_keeps_the_granted_baseline_so_a_new_grant_notifies_once() {
    let mut p = Poller::new(None, None);
    let now = at(1_800_000_000);
    let status = || ok(&fixture("status.json"), 0);
    let devices = || ok(&fixture("devices_list.json"), 0);
    let granted = || ok(&fixture("terminal_status.json"), 0);
    let two = ok(
        r#"{"enabled":true,"granted":[{"id":"dev_a1","name":"pixel"},{"id":"dev_b2","name":"tablet"}],"open":[],"changed":false}"#,
        0,
    );
    let first = p.tick(
        &FakeCli::new(vec![
            status(),
            ok(&fixture("version.json"), 0),
            devices(),
            granted(),
        ]),
        now,
    );
    assert!(first.notices.is_empty(), "first poll is the baseline");
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            devices(),
            Err(aristotle_core::runner::CliError::Timeout),
        ]),
        now,
    );
    assert!(t.notices.is_empty());
    let t = p.tick(&FakeCli::new(vec![status(), devices(), two]), now);
    assert_eq!(t.notices.len(), 1);
    assert!(matches!(t.notices[0], Notice::TerminalGranted { .. }));
}

fn terminal_json(ids: &[&str]) -> String {
    terminal_json_with(true, ids)
}

fn terminal_json_with(enabled: bool, ids: &[&str]) -> String {
    let granted: Vec<String> = ids
        .iter()
        .map(|id| format!(r#"{{"id":"{id}","name":"n"}}"#))
        .collect();
    format!(
        r#"{{"enabled":{enabled},"granted":[{}],"open":[],"changed":false}}"#,
        granted.join(",")
    )
}

fn terminal_tick(
    p: &mut Poller,
    reply: Result<RawOutput, CliError>,
    first: bool,
) -> aristotle_core::poller::Tick {
    let mut replies = vec![ok(&fixture("status.json"), 0)];
    if first {
        replies.push(ok(&fixture("version.json"), 0));
    }
    replies.push(ok(&fixture("devices_list.json"), 0));
    replies.push(reply);
    p.tick(&FakeCli::new(replies), at(1_800_000_000))
}

#[test]
fn a_failed_read_then_a_revoke_and_a_new_grant_notifies_the_new_grant_only() {
    let mut p = Poller::new(None, None);
    let base = terminal_tick(&mut p, ok(&terminal_json(&["dev_a1"]), 0), true);
    assert!(base.notices.is_empty());
    let failed = terminal_tick(&mut p, Err(CliError::Timeout), false);
    assert!(failed.notices.is_empty());
    assert_eq!(failed.snapshot.terminal_granted, None);
    let t = terminal_tick(&mut p, ok(&terminal_json(&["dev_b2"]), 0), false);
    assert_eq!(
        t.notices,
        vec![Notice::TerminalGranted {
            device_name: Some("tablet".into())
        }]
    );
    assert!(
        terminal_tick(&mut p, ok(&terminal_json(&["dev_b2"]), 0), false)
            .notices
            .is_empty()
    );
}

#[test]
fn existing_grants_after_a_tray_restart_are_the_baseline() {
    let mut p = Poller::new(None, None);
    let t = terminal_tick(&mut p, ok(&terminal_json(&["dev_a1", "dev_b2"]), 0), true);
    assert!(t.notices.is_empty());
    let t = terminal_tick(&mut p, ok(&terminal_json(&["dev_a1", "dev_b2"]), 0), false);
    assert!(t.notices.is_empty());
}

#[test]
fn a_grant_while_the_switch_is_off_notifies_once() {
    let off = |ids: &[&str]| terminal_json_with(false, ids);
    let mut p = Poller::new(None, None);
    assert!(terminal_tick(&mut p, ok(&off(&[]), 0), true)
        .notices
        .is_empty());
    let t = terminal_tick(&mut p, ok(&off(&["dev_a1"]), 0), false);
    assert_eq!(t.notices.len(), 1);
    assert!(matches!(t.notices[0], Notice::TerminalGranted { .. }));
    assert!(terminal_tick(&mut p, ok(&off(&["dev_a1"]), 0), false)
        .notices
        .is_empty());
}

#[test]
fn a_grant_in_a_tick_whose_devices_read_failed_still_has_the_known_name() {
    let mut p = Poller::new(None, None);
    let now = at(1_800_000_000);
    let status = || ok(&fixture("status.json"), 0);
    let devices = || ok(&fixture("devices_list.json"), 0);
    p.tick(
        &FakeCli::new(vec![
            status(),
            ok(&fixture("version.json"), 0),
            devices(),
            ok(&terminal_json(&[]), 0),
        ]),
        now,
    );
    // `devices list` fails in the very tick that carries the grant.
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            Err(CliError::Timeout),
            ok(&terminal_json(&["dev_a1"]), 0),
        ]),
        now,
    );
    assert_eq!(
        t.notices,
        vec![Notice::TerminalGranted {
            device_name: Some("pixel".into())
        }]
    );
}

#[test]
fn a_terminal_opened_in_a_tick_whose_devices_read_failed_still_has_the_known_name() {
    let mut p = Poller::new(None, None);
    let now = at(1_800_000_000);
    let status = || ok(&fixture("status.json"), 0);
    let devices = || ok(&fixture("devices_list.json"), 0);
    p.tick(
        &FakeCli::new(vec![
            status(),
            ok(&fixture("version.json"), 0),
            devices(),
            ok(&fixture("terminal_status_empty.json"), 0),
        ]),
        now,
    );
    let t = p.tick(
        &FakeCli::new(vec![
            status(),
            Err(CliError::Timeout),
            ok(&fixture("terminal_status.json"), 0),
        ]),
        now,
    );
    let named = |n: &&Notice| {
        matches!(
            n,
            Notice::TerminalOpened {
                device_name: Some(_),
                ..
            }
        )
    };
    assert_eq!(t.notices.iter().filter(named).count(), 1, "{:?}", t.notices);
}

#[test]
fn an_unparsable_stamp_is_deleted_by_the_next_tick() {
    let dir = tempdir("garbage-tick");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    stamp.write(at(5)).unwrap();
    fs::write(stamp.path(), "soon").unwrap();
    let mut p = Poller::new(None, Some(stamp.clone()));
    p.tick(
        &FakeCli::new(vec![
            ok(&fixture("status.json"), 0),
            ok(&fixture("version.json"), 0),
            ok(&fixture("devices_list.json"), 0),
            ok(&fixture("terminal_status_empty.json"), 0),
        ]),
        at(1_800_000_000),
    );
    assert!(!stamp.path().exists(), "garbage stamp is removed");
    let _ = fs::remove_dir_all(dir);
}

#[test]
fn delete_if_leaves_a_stamp_written_after_the_read() {
    let dir = tempdir("delete-if");
    let stamp = ExpectedStop::in_runtime_dir(&dir);
    stamp.write(at(1_800_000_000)).unwrap();
    let seen = stamp.read().unwrap();
    stamp.write(at(1_800_000_030)).unwrap(); // the window app stamps again
    stamp.delete_if(seen).unwrap();
    assert_eq!(
        stamp.read(),
        Some(at(1_800_000_030)),
        "the newer stamp stays"
    );
    stamp.delete_if(at(1_800_000_030)).unwrap();
    assert_eq!(stamp.read(), None, "the stamp that was read is deleted");
    let _ = fs::remove_dir_all(dir);
}
