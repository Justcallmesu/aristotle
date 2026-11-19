//! Builders shared by the integration tests.
#![allow(dead_code)]

use aristotle_core::runner::{CliError, RawOutput, ServerCli};
use aristotle_core::snapshot::{Snapshot, StatusPoll, VersionCheck};
use aristotle_core::types::*;
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::Mutex;

pub fn fixture_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../server/internal/app/testdata/json")
}

pub fn fixture(name: &str) -> String {
    std::fs::read_to_string(fixture_dir().join(name)).expect("fixture readable")
}

pub fn status(service: &str, installed: bool, server: ServerState, hermes: Option<&str>) -> Status {
    let result = (server == ServerState::Running).then(|| StatusResult {
        version: "1.2.3".into(),
        addresses: vec![],
        port: Some(8760),
        listeners: None,
        hermes: hermes.map(|state| Hermes {
            state: state.into(),
            reason: None,
        }),
    });
    Status {
        service: service.into(),
        enabled: "enabled".into(),
        installed,
        server,
        result,
    }
}

pub fn running(hermes: Option<&str>) -> Status {
    status("active", true, ServerState::Running, hermes)
}

pub fn snap(status: Status) -> Snapshot {
    Snapshot {
        status: StatusPoll::Answered(Box::new(status)),
        devices: None,
        terminals: None,
        terminal_enabled: None,
        terminal_granted: None,
        version: VersionCheck::Skipped,
    }
}

pub fn failed() -> Snapshot {
    Snapshot {
        status: StatusPoll::Failed,
        devices: None,
        terminals: None,
        terminal_enabled: None,
        terminal_granted: None,
        version: VersionCheck::Skipped,
    }
}

pub fn device(id: &str, name: &str) -> Device {
    Device {
        id: id.into(),
        name: name.into(),
        created_at: "2026-11-01T10:00:00Z".into(),
        last_seen_at: "2026-11-18T09:30:00Z".into(),
    }
}

pub fn terminal(id: &str, device_id: &str) -> OpenTerminal {
    OpenTerminal {
        id: id.into(),
        device_id: device_id.into(),
        listener: "lan".into(),
        since: "2026-11-18T09:00:00Z".into(),
        pid: 4242,
    }
}

/// A fake CLI: answers each call from a queue of canned results, records the argv.
pub struct FakeCli {
    pub replies: Mutex<VecDeque<Result<RawOutput, CliError>>>,
    pub calls: Mutex<Vec<Vec<String>>>,
}

impl FakeCli {
    pub fn new(replies: Vec<Result<RawOutput, CliError>>) -> Self {
        Self {
            replies: Mutex::new(replies.into()),
            calls: Mutex::new(vec![]),
        }
    }
    pub fn calls(&self) -> Vec<Vec<String>> {
        self.calls.lock().unwrap().clone()
    }
}

pub fn ok(stdout: &str, exit_code: i32) -> Result<RawOutput, CliError> {
    Ok(RawOutput {
        exit_code,
        stdout: stdout.into(),
    })
}

impl ServerCli for FakeCli {
    fn run(&self, args: &[&str]) -> Result<RawOutput, CliError> {
        self.calls
            .lock()
            .unwrap()
            .push(args.iter().map(|a| a.to_string()).collect());
        self.replies
            .lock()
            .unwrap()
            .pop_front()
            .expect("unexpected extra CLI call")
    }
}
