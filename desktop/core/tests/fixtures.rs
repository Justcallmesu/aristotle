//! Decodes every section 22 fixture. A fixture without a decoder here fails the test, so a new
//! server fixture forces a type update.

mod common;
use aristotle_core::types::*;
use common::{fixture, fixture_dir};
use std::collections::BTreeSet;

type Check = fn(&str);

fn decode<T: serde::de::DeserializeOwned>(text: &str) -> T {
    serde_json::from_str(text).unwrap_or_else(|e| panic!("decode failed: {e}\n{text}"))
}

fn check_status(text: &str) {
    let s: Status = decode(text);
    assert!(!s.service.is_empty());
}
fn check_terminal(text: &str) {
    let _: TerminalStatus = decode(text);
}
fn check_action(text: &str) {
    let a: ActionResult = decode(text);
    assert!(a.ok);
}
fn check_device(text: &str) {
    let _: Device = decode(text);
}
fn check_devices(text: &str) {
    let _: DevicesList = decode(text);
}
fn check_pair(text: &str) {
    let _: Pair = decode(text);
}
fn check_version(text: &str) {
    let _: VersionInfo = decode(text);
}

const COVERAGE: &[(&str, Check)] = &[
    ("devices_grant_terminal.json", check_terminal),
    ("devices_list_empty.json", check_devices),
    ("devices_list.json", check_devices),
    ("devices_revoke.json", check_device),
    ("devices_ungrant_terminal.json", check_terminal),
    ("pair.json", check_pair),
    ("restart.json", check_action),
    ("start.json", check_action),
    ("status.json", check_status),
    ("status_not_installed.json", check_status),
    ("status_not_running.json", check_status),
    ("status_sparse.json", check_status),
    ("stop.json", check_action),
    ("terminal_disable.json", check_terminal),
    ("terminal_enable.json", check_terminal),
    ("terminal_status_empty.json", check_terminal),
    ("terminal_status.json", check_terminal),
    ("version.json", check_version),
];

#[test]
fn every_fixture_decodes() {
    for (name, check) in COVERAGE {
        check(&fixture(name));
    }
}

#[test]
fn no_fixture_is_left_uncovered() {
    let on_disk: BTreeSet<String> = std::fs::read_dir(fixture_dir())
        .expect("fixture dir")
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.ends_with(".json"))
        .collect();
    let covered: BTreeSet<String> = COVERAGE.iter().map(|(n, _)| n.to_string()).collect();
    let missing: Vec<_> = on_disk.difference(&covered).collect();
    let stale: Vec<_> = covered.difference(&on_disk).collect();
    assert!(
        missing.is_empty(),
        "fixtures without a decoder: {missing:?}"
    );
    assert!(stale.is_empty(), "decoders without a fixture: {stale:?}");
}

#[test]
fn status_full_fields() {
    let s: Status = decode(&fixture("status.json"));
    assert!(s.is_running() && s.installed && s.service_active());
    assert!(s.hermes_reachable());
    let r = s.result.unwrap();
    assert_eq!(r.port, Some(8760));
    assert_eq!(r.listeners.unwrap().len(), 2);
    assert_eq!(r.addresses.len(), 2);
}

#[test]
fn status_sparse_has_absent_fields() {
    let s: Status = decode(&fixture("status_sparse.json"));
    let r = s.result.unwrap();
    assert!(r.port.is_none() && r.listeners.is_none() && r.hermes.is_none());
    assert!(r.addresses.is_empty());
}

#[test]
fn status_not_installed_and_not_running() {
    let s: Status = decode(&fixture("status_not_installed.json"));
    assert!(!s.installed && !s.is_running() && s.result.is_none());
    let s: Status = decode(&fixture("status_not_running.json"));
    assert!(s.installed && !s.is_running() && !s.service_active());
}

#[test]
fn terminal_optional_fields() {
    let t: TerminalStatus = decode(&fixture("terminal_enable.json"));
    assert_eq!(
        t.warning.as_deref(),
        Some("terminal.json could not be fsynced")
    );
    let t: TerminalStatus = decode(&fixture("terminal_status_empty.json"));
    assert_eq!(t.problem.as_deref(), Some("bad json"));
    let t: TerminalStatus = decode(&fixture("devices_grant_terminal.json"));
    assert_eq!(t.device.unwrap().id, "dev_a1");
    let t: TerminalStatus = decode(&fixture("terminal_status.json"));
    assert_eq!(t.open[0].device_id, "dev_a1");
    assert!(t.warning.is_none() && t.device.is_none());
}

#[test]
fn unknown_fields_are_ignored() {
    let s: Status = decode(
        r#"{"service":"active","enabled":"enabled","installed":true,"server":"running","future":1,
            "result":{"version":"1","addresses":[],"newthing":{"a":1}}}"#,
    );
    assert!(s.is_running());
    let s: Status = decode(
        r#"{"service":"active","enabled":"enabled","installed":true,"server":"something-new"}"#,
    );
    assert_eq!(s.server, ServerState::Other);
    assert!(!s.is_running());
}

#[test]
fn error_body_decodes() {
    let e: ErrorBody = decode(r#"{"error":"boom"}"#);
    assert_eq!(e.error, "boom");
}

#[test]
fn pair_debug_hides_the_code_and_payload() {
    let p: Pair = decode(&fixture("pair.json"));
    let shown = format!("{p:?}");
    assert!(
        !shown.contains(&p.code) && !shown.contains("aristotle://"),
        "{shown}"
    );
}
