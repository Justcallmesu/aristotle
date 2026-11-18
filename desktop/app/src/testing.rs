//! Test helpers: a scripted CLI and the section 22 fixtures.

use crate::backend::Backend;
use aristotle_core::runner::{CliError, RawOutput, ServerCli};
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

type CallHook = Box<dyn FnMut(&[&str]) + Send>;

pub type Reply = Result<RawOutput, CliError>;

pub fn fixture(name: &str) -> String {
    let dir =
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../server/internal/app/testdata/json");
    std::fs::read_to_string(dir.join(name)).expect("fixture readable")
}

pub fn reply(stdout: &str, exit_code: i32) -> Reply {
    Ok(RawOutput {
        exit_code,
        stdout: stdout.into(),
    })
}

pub fn json(name: &str) -> Reply {
    reply(&fixture(name), 0)
}

/// Answers each call from a queue and records the argv.
pub struct FakeCli {
    replies: Mutex<VecDeque<Reply>>,
    calls: Mutex<Vec<Vec<String>>>,
    /// Runs on every call before the reply (to look at a file the app wrote).
    on_call: Mutex<Option<CallHook>>,
}

impl FakeCli {
    pub fn new(replies: Vec<Reply>) -> Arc<Self> {
        Arc::new(Self {
            replies: Mutex::new(replies.into()),
            calls: Mutex::new(vec![]),
            on_call: Mutex::new(None),
        })
    }

    pub fn calls(&self) -> Vec<String> {
        self.calls
            .lock()
            .unwrap()
            .iter()
            .map(|c| c.join(" "))
            .collect()
    }

    pub fn on_call(&self, f: impl FnMut(&[&str]) + Send + 'static) {
        *self.on_call.lock().unwrap() = Some(Box::new(f));
    }
}

impl ServerCli for FakeCli {
    fn run(&self, args: &[&str]) -> Result<RawOutput, CliError> {
        self.calls
            .lock()
            .unwrap()
            .push(args.iter().map(|a| a.to_string()).collect());
        if let Some(f) = self.on_call.lock().unwrap().as_mut() {
            f(args);
        }
        self.replies
            .lock()
            .unwrap()
            .pop_front()
            .expect("unexpected extra CLI call")
    }
}

pub fn backend(cli: &Arc<FakeCli>, app_version: &str) -> Backend {
    Backend::new(cli.clone(), None, None, Some(app_version))
}

/// Replies for one poll of a running server (`status`, first `version`, `devices list`, `terminal status`).
pub fn running_poll() -> Vec<Reply> {
    vec![
        json("status.json"),
        json("version.json"),
        json("devices_list.json"),
        json("terminal_status.json"),
    ]
}

static FORK_LOCK: Mutex<()> = Mutex::new(());

/// Serialises tests that write a script and exec it: a fork in another test can hold the script's
/// write fd and exec then fails with ETXTBSY.
pub fn fork_lock() -> std::sync::MutexGuard<'static, ()> {
    FORK_LOCK.lock().unwrap_or_else(|e| e.into_inner())
}
