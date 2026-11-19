//! `assess` / `tray_state` table, version check and the snapshot poll.

mod common;
use aristotle_core::runner::CliError;
use aristotle_core::snapshot::*;
use aristotle_core::types::ServerState::*;
use common::*;

fn with_version(mut s: Snapshot, v: VersionCheck) -> Snapshot {
    s.version = v;
    s
}

#[test]
fn tray_state_table() {
    let cases: Vec<(&str, Snapshot, TrayState, Reason)> = vec![
        (
            "all ok",
            snap(running(Some("reachable"))),
            TrayState::Green,
            Reason::AllOk,
        ),
        (
            "hermes down",
            snap(running(Some("unreachable"))),
            TrayState::Amber,
            Reason::HermesDown,
        ),
        (
            "hermes absent",
            snap(running(None)),
            TrayState::Amber,
            Reason::HermesUnknown,
        ),
        (
            "hermes error state is amber like not-running",
            snap(running(Some("error"))),
            TrayState::Amber,
            Reason::HermesDown,
        ),
        (
            "server stopped",
            snap(status("inactive", true, NotRunning, None)),
            TrayState::Red,
            Reason::ServerStopped,
        ),
        (
            "not installed",
            snap(status("inactive", false, NotRunning, None)),
            TrayState::Red,
            Reason::NotInstalled,
        ),
        (
            "no answer",
            snap(status("active", true, NoAnswer, None)),
            TrayState::Red,
            Reason::NoAnswer,
        ),
        (
            "unknown server word",
            snap(status("active", true, Other, None)),
            TrayState::Red,
            Reason::ServerStopped,
        ),
        (
            "service not active but server answers",
            snap(status("inactive", true, Running, Some("reachable"))),
            TrayState::Red,
            Reason::RunningOutsideService,
        ),
        (
            "outside the service + hermes down (design 4.1 row 5 beats 7)",
            snap(status("inactive", true, Running, Some("not-running"))),
            TrayState::Red,
            Reason::RunningOutsideService,
        ),
        (
            "service unknown + hermes absent (row 5 beats 9)",
            snap(status("unknown", true, Running, None)),
            TrayState::Red,
            Reason::RunningOutsideService,
        ),
        (
            "service failed + hermes unreachable",
            snap(status("failed", true, Running, Some("unreachable"))),
            TrayState::Red,
            Reason::RunningOutsideService,
        ),
        (
            "not installed beats everything below it",
            snap(status("unknown", false, Running, Some("reachable"))),
            TrayState::Red,
            Reason::NotInstalled,
        ),
        (
            "no-answer beats not-running wording even when service is inactive",
            snap(status("inactive", true, NoAnswer, None)),
            TrayState::Red,
            Reason::NoAnswer,
        ),
        (
            "version mismatch beats not installed",
            with_version(
                snap(status("inactive", false, NotRunning, None)),
                VersionCheck::Mismatch {
                    server: "1.0".into(),
                    desktop: "2.0".into(),
                },
            ),
            TrayState::Red,
            Reason::VersionMismatch,
        ),
        (
            "cli missing",
            Snapshot {
                status: StatusPoll::CliMissing,
                ..failed()
            },
            TrayState::Red,
            Reason::CliMissing,
        ),
        ("cli failed", failed(), TrayState::Red, Reason::NoAnswer),
        (
            "version mismatch beats green",
            with_version(
                snap(running(Some("reachable"))),
                VersionCheck::Mismatch {
                    server: "1.0".into(),
                    desktop: "2.0".into(),
                },
            ),
            TrayState::Red,
            Reason::VersionMismatch,
        ),
        (
            "version match stays green",
            with_version(snap(running(Some("reachable"))), VersionCheck::Match),
            TrayState::Green,
            Reason::AllOk,
        ),
    ];
    for (name, snapshot, state, reason) in cases {
        let a = assess(&snapshot);
        assert_eq!((a.state, a.reason), (state, reason), "case: {name}");
        assert_eq!(tray_state(&snapshot), state, "case: {name}");
    }
}

#[test]
fn compare_versions_rules() {
    use VersionCheck::*;
    assert_eq!(compare_versions("1.2.3", Some("1.2.3")), Match);
    assert_eq!(
        compare_versions("1.2.3", Some("1.2.4")),
        Mismatch {
            server: "1.2.3".into(),
            desktop: "1.2.4".into()
        }
    );
    assert_eq!(compare_versions("dev", Some("1.2.3")), Skipped);
    assert_eq!(compare_versions("1.2.3", Some("dev")), Skipped);
    assert_eq!(compare_versions("1.2.3", Some("")), Skipped);
    assert_eq!(compare_versions("1.2.3", None), Skipped);
    assert_eq!(compare_versions("", Some("1.2.3")), Skipped);
}

#[test]
fn dev_versions_skip_the_check_on_either_side() {
    use VersionCheck::*;
    // The server's unstamped build (server/internal/version) and build-server.sh without a tag.
    assert_eq!(compare_versions("0.0.0-dev", Some("1.2.3")), Skipped);
    assert_eq!(
        compare_versions("0.0.0-dev+abc1234", Some("1.2.3")),
        Skipped
    );
    assert_eq!(compare_versions("1.2.3", Some("0.0.0-dev")), Skipped);
    assert_eq!(compare_versions("1.2.3", Some("1.2.4-dev")), Skipped);
    // A release candidate is not a dev build.
    assert!(matches!(
        compare_versions("1.2.3-rc1", Some("1.2.3")),
        Mismatch { .. }
    ));
}

fn status_with_version(version: &str) -> String {
    let text = fixture("status.json");
    assert!(text.contains(r#""version":"1.2.3""#));
    text.replace(r#""version":"1.2.3""#, &format!(r#""version":"{version}""#))
}

fn json(name: &str) -> Result<aristotle_core::runner::RawOutput, CliError> {
    ok(&fixture(name), 0)
}

#[test]
fn poll_running_fetches_version_devices_terminals() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    let mut s = Snapshotter::new(Some("1.2.3"));
    let snap = s.poll(&cli);
    assert_eq!(snap.version, VersionCheck::Match);
    assert_eq!(snap.devices.as_ref().unwrap().len(), 2);
    assert_eq!(snap.terminals.as_ref().unwrap().len(), 1);
    assert_eq!(snap.terminal_enabled, Some(true), "carried for the sidebar");
    assert_eq!(snap.terminal_granted, Some(vec!["dev_a1".to_string()]));
    assert_eq!(tray_state(&snap), TrayState::Green);
    let argv: Vec<String> = cli.calls().iter().map(|c| c.join(" ")).collect();
    assert_eq!(
        argv,
        [
            "status --json",
            "version --json",
            "devices list --json",
            "terminal status --json"
        ]
    );
}

#[test]
fn poll_stopped_skips_devices_and_terminals() {
    let cli = FakeCli::new(vec![
        ok(&fixture("status_not_running.json"), 1),
        json("version.json"),
    ]);
    let snap = Snapshotter::new(Some("1.2.3")).poll(&cli);
    assert!(snap.devices.is_none() && snap.terminals.is_none());
    assert_eq!(snap.terminal_enabled, None);
    assert_eq!(snap.terminal_granted, None);
    assert_eq!(tray_state(&snap), TrayState::Red);
}

#[test]
fn poll_version_asked_once_and_again_after_restart() {
    let mut s = Snapshotter::new(Some("1.2.3"));
    let up = || {
        vec![
            json("status.json"),
            json("devices_list.json"),
            json("terminal_status.json"),
        ]
    };
    // first poll: version asked
    let cli = FakeCli::new(
        [
            vec![json("status.json"), json("version.json")],
            up()[1..].to_vec(),
        ]
        .concat(),
    );
    s.poll(&cli);
    // second poll: not asked again
    let cli = FakeCli::new(up());
    s.poll(&cli);
    assert!(cli.calls().iter().all(|c| c[0] != "version"));
    // server stops, then starts: asked again
    let cli = FakeCli::new(vec![ok(&fixture("status_not_running.json"), 1)]);
    s.poll(&cli);
    let cli = FakeCli::new(
        [
            vec![json("status.json"), json("version.json")],
            up()[1..].to_vec(),
        ]
        .concat(),
    );
    s.poll(&cli);
    assert!(cli.calls().iter().any(|c| c[0] == "version"));
}

#[test]
fn poll_version_mismatch_is_red() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    let snap = Snapshotter::new(Some("9.9.9")).poll(&cli);
    assert_eq!(assess(&snap).reason, Reason::VersionMismatch);
}

#[test]
fn poll_dev_build_skips_the_check() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    let snap = Snapshotter::new(None).poll(&cli);
    assert_eq!(snap.version, VersionCheck::Skipped);
    assert_eq!(tray_state(&snap), TrayState::Green);
}

#[test]
fn poll_failed_version_call_is_retried_next_poll() {
    let mut s = Snapshotter::new(Some("1.2.3"));
    let cli = FakeCli::new(vec![
        json("status.json"),
        Err(CliError::Timeout),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    assert_eq!(s.poll(&cli).version, VersionCheck::Unchecked);
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    assert_eq!(s.poll(&cli).version, VersionCheck::Match);
}

#[test]
fn poll_failures_map_to_red_states() {
    let mut s = Snapshotter::new(None);
    let snap = s.poll(&FakeCli::new(vec![Err(CliError::NotInstalled)]));
    assert_eq!(snap.status, StatusPoll::CliMissing);
    let snap = s.poll(&FakeCli::new(vec![Err(CliError::Timeout)]));
    assert_eq!(snap.status, StatusPoll::Failed);
    let snap = s.poll(&FakeCli::new(vec![ok("garbage", 1)]));
    assert_eq!(snap.status, StatusPoll::Failed);
    assert_eq!(tray_state(&snap), TrayState::Red);
}

#[test]
fn poll_devices_failure_keeps_status() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        Err(CliError::Timeout),
        json("terminal_status.json"),
    ]);
    let snap = Snapshotter::new(Some("1.2.3")).poll(&cli);
    assert!(snap.devices.is_none() && snap.terminals.is_some());
    assert_eq!(tray_state(&snap), TrayState::Green);
}

#[test]
fn poll_terminal_failure_leaves_the_switch_unknown() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        Err(CliError::Timeout),
    ]);
    let snap = Snapshotter::new(Some("1.2.3")).poll(&cli);
    assert!(snap.terminals.is_none() && snap.terminal_enabled.is_none());
}

fn poll_with_status_version(status_version: &str, desktop: &str) -> Snapshot {
    let cli = FakeCli::new(vec![
        ok(&status_with_version(status_version), 0),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    Snapshotter::new(Some(desktop)).poll(&cli)
}

#[test]
fn poll_running_server_version_differs_while_cli_matches_is_red() {
    // version --json says 1.2.3 (= desktop); the running server says 1.2.2: installed, not restarted.
    let snap = poll_with_status_version("1.2.2", "1.2.3");
    assert_eq!(
        snap.version,
        VersionCheck::Mismatch {
            server: "1.2.2".into(),
            desktop: "1.2.3".into()
        }
    );
    assert_eq!(assess(&snap).reason, Reason::VersionMismatch);
}

#[test]
fn poll_running_server_dev_version_skips_its_side() {
    let snap = poll_with_status_version("0.0.0-dev", "1.2.3");
    assert!(!matches!(snap.version, VersionCheck::Mismatch { .. }));
    assert_eq!(tray_state(&snap), TrayState::Green);
}

#[test]
fn poll_cli_mismatch_wins_over_a_matching_running_version() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]);
    let snap = Snapshotter::new(Some("9.9.9")).poll(&cli);
    assert!(
        matches!(&snap.version, VersionCheck::Mismatch { server, .. } if server == "1.2.3"),
        "{:?}",
        snap.version
    );
}

#[test]
fn running_server_version_is_checked_on_every_poll_not_only_after_a_restart() {
    let mut s = Snapshotter::new(Some("1.2.3"));
    let up = |v: &str, with_version_call: bool| {
        let mut replies = vec![ok(&status_with_version(v), 0)];
        if with_version_call {
            replies.push(json("version.json"));
        }
        replies.push(json("devices_list.json"));
        replies.push(json("terminal_status.json"));
        FakeCli::new(replies)
    };
    assert_eq!(s.poll(&up("1.2.3", true)).version, VersionCheck::Match);
    // No restart seen, but the status carries a different version now: `version` is asked again (DF4).
    let cli = up("1.2.2", true);
    let snap = s.poll(&cli);
    assert!(cli.calls().iter().any(|c| c[0] == "version"));
    assert!(matches!(snap.version, VersionCheck::Mismatch { .. }));
}

#[test]
fn version_is_asked_again_every_12_polls() {
    let mut s = Snapshotter::new(Some("1.2.3"));
    let stopped = |with_version_call: bool| {
        let mut replies = vec![ok(&fixture("status_not_running.json"), 1)];
        if with_version_call {
            replies.push(json("version.json"));
        }
        FakeCli::new(replies)
    };
    let asked = |cli: &FakeCli| cli.calls().iter().any(|c| c[0] == "version");
    assert!(asked(&{
        let cli = stopped(true);
        s.poll(&cli);
        cli
    }));
    for n in 2..=12 {
        let cli = stopped(false);
        s.poll(&cli);
        assert!(!asked(&cli), "poll {n} must not ask");
    }
    let cli = stopped(true);
    s.poll(&cli);
    assert!(asked(&cli), "poll 13 asks again");
    let cli = stopped(false);
    s.poll(&cli);
    assert!(!asked(&cli), "the counter restarts after an ask");
}

#[test]
fn a_failed_terminal_read_makes_the_grants_unknown_not_empty() {
    let cli = FakeCli::new(vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        Err(CliError::Timeout),
    ]);
    let snap = Snapshotter::new(None).poll(&cli);
    assert_eq!(snap.terminal_enabled, None);
    assert_eq!(snap.terminal_granted, None);
}
