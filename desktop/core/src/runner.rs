//! Runs `aristotle-server <args> --json` and parses stdout per protocol section 22.
//!
//! Never a shell, fixed argv, hard timeout. Stdout and stderr content are never logged (P11): errors
//! carry the server's own `{"error"}` text only, which the server guarantees holds no secret.

use serde::de::DeserializeOwned;
use std::fmt;
use std::io::Read;
use std::os::unix::process::CommandExt;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(10);
/// P23 (c): the limit for the quick reads (`devices list`, `terminal status`, `version`). `status` keeps
/// [`DEFAULT_TIMEOUT`]: it holds the server's own 2 s Hermes probe.
pub const READ_TIMEOUT: Duration = Duration::from_secs(3);
/// Development override for the binary path.
pub const BIN_ENV: &str = "ARISTOTLE_SERVER_BIN";
const MAX_STDOUT: u64 = 4 * 1024 * 1024;
const POLL_STEP: Duration = Duration::from_millis(10);
const READER_GRACE: Duration = Duration::from_secs(2);

/// What a finished process printed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawOutput {
    pub exit_code: i32,
    pub stdout: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CliError {
    /// The binary does not exist or cannot be executed (or `$HOME` is unset): the server is not installed.
    NotInstalled,
    /// The process ran past the timeout and was killed.
    Timeout,
    /// The process could not be started or read (message holds only the OS error kind).
    Io(String),
    /// Stdout is neither the expected success object nor `{"error"}`. Content is not kept.
    BadOutput { exit_code: i32 },
    /// The server printed `{"error":"..."}`.
    Server { message: String, exit_code: i32 },
}

impl CliError {
    /// A fixed reason code for logs (P11): never the message, never any CLI output.
    pub fn kind(&self) -> &'static str {
        match self {
            CliError::NotInstalled => "not-installed",
            CliError::Timeout => "timeout",
            CliError::Io(_) => "io",
            CliError::BadOutput { .. } => "bad-output",
            CliError::Server { .. } => "server-error",
        }
    }
}

impl fmt::Display for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            CliError::NotInstalled => write!(f, "aristotle-server is not installed"),
            CliError::Timeout => write!(f, "aristotle-server timed out"),
            CliError::Io(kind) => write!(f, "could not run aristotle-server: {kind}"),
            CliError::BadOutput { exit_code } => {
                write!(
                    f,
                    "unreadable output from aristotle-server (exit {exit_code})"
                )
            }
            CliError::Server { message, exit_code } => {
                write!(f, "aristotle-server failed (exit {exit_code}): {message}")
            }
        }
    }
}

impl std::error::Error for CliError {}

/// The seam: tests use a fake, the apps use [`RealCli`].
pub trait ServerCli: Send + Sync {
    /// Runs the server binary with exactly `args` (the caller adds `--json`).
    fn run(&self, args: &[&str]) -> Result<RawOutput, CliError>;

    /// Like [`run`](Self::run), giving up after `limit` when that is shorter than the runner's own
    /// timeout. A runner without a clock (a fake) ignores `limit`.
    fn run_within(&self, args: &[&str], _limit: Duration) -> Result<RawOutput, CliError> {
        self.run(args)
    }
}

/// A parsed success object plus the process exit code (`status` exits 1 on success output too).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Reply<T> {
    pub value: T,
    pub exit_code: i32,
}

/// Runs `<args> --json` (the flag goes last, protocol section 22) and parses stdout as `T`.
pub fn query<T: DeserializeOwned>(
    cli: &dyn ServerCli,
    args: &[&str],
) -> Result<Reply<T>, CliError> {
    let mut argv: Vec<&str> = args.to_vec();
    argv.push("--json");
    parse_output(&cli.run(&argv)?)
}

/// [`query`] for a quick read: gives up after [`READ_TIMEOUT`]. A timeout is a failed read.
pub fn query_read<T: DeserializeOwned>(
    cli: &dyn ServerCli,
    args: &[&str],
) -> Result<Reply<T>, CliError> {
    let mut argv: Vec<&str> = args.to_vec();
    argv.push("--json");
    parse_output(&cli.run_within(&argv, READ_TIMEOUT)?)
}

/// Success object vs `{"error"}`, per section 22.
pub fn parse_output<T: DeserializeOwned>(raw: &RawOutput) -> Result<Reply<T>, CliError> {
    let bad = || CliError::BadOutput {
        exit_code: raw.exit_code,
    };
    let value: serde_json::Value = serde_json::from_str(raw.stdout.trim()).map_err(|_| bad())?;
    if let Some(message) = error_text(&value) {
        return Err(CliError::Server {
            message,
            exit_code: raw.exit_code,
        });
    }
    let value = serde_json::from_value(value).map_err(|_| bad())?;
    Ok(Reply {
        value,
        exit_code: raw.exit_code,
    })
}

/// `{"error":"text"}`: an object whose only job is the `error` string.
fn error_text(value: &serde_json::Value) -> Option<String> {
    let object = value.as_object()?;
    object.get("error")?.as_str().map(str::to_owned)
}

/// Runs the real binary by absolute path.
#[derive(Debug, Clone)]
pub struct RealCli {
    bin: PathBuf,
    timeout: Duration,
}

impl RealCli {
    pub fn new(bin: PathBuf, timeout: Duration) -> Self {
        Self { bin, timeout }
    }

    /// `$ARISTOTLE_SERVER_BIN`, else `$HOME/.local/bin/aristotle-server`. `None` without either.
    pub fn from_env() -> Option<Self> {
        Some(Self::new(default_bin()?, DEFAULT_TIMEOUT))
    }
}

pub fn default_bin() -> Option<PathBuf> {
    if let Some(path) = absolute_env_path(BIN_ENV) {
        return Some(path);
    }
    let home = non_empty_env("HOME")?;
    Some(PathBuf::from(home).join(".local/bin/aristotle-server"))
}

/// An env override that is an absolute path. A relative one would be looked up on `PATH` (P4): ignored.
pub fn absolute_env_path(name: &str) -> Option<PathBuf> {
    let path = PathBuf::from(non_empty_env(name)?);
    if path.is_absolute() {
        return Some(path);
    }
    eprintln!("aristotle: ignoring {name}: not an absolute path");
    None
}

/// The value of an environment variable, `None` when it is unset or empty.
pub fn non_empty_env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.is_empty())
}

impl ServerCli for RealCli {
    fn run(&self, args: &[&str]) -> Result<RawOutput, CliError> {
        self.run_for(args, self.timeout)
    }

    fn run_within(&self, args: &[&str], limit: Duration) -> Result<RawOutput, CliError> {
        self.run_for(args, limit.min(self.timeout))
    }
}

impl RealCli {
    fn run_for(&self, args: &[&str], timeout: Duration) -> Result<RawOutput, CliError> {
        let mut child = Command::new(&self.bin)
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            // Own process group, so a timeout can kill grandchildren too (systemctl under start/stop).
            .process_group(0)
            .spawn()
            .map_err(spawn_error)?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| CliError::Io("no stdout".into()))?;
        let (tx, rx) = mpsc::channel();
        let reader = thread::spawn(move || {
            let mut text = Vec::new();
            let result = stdout.take(MAX_STDOUT).read_to_end(&mut text);
            let _ = tx.send(result.map(|_| text));
        });

        let deadline = Instant::now() + timeout;
        let status = loop {
            match child.try_wait() {
                Ok(Some(status)) => break status,
                Ok(None) if Instant::now() >= deadline => {
                    kill_group(&child);
                    let _ = child.wait();
                    // The pipe closes with the group, so the reader ends; join it. A process that left
                    // the group could keep the pipe: then the reader is detached, not waited on.
                    if rx.recv_timeout(READER_GRACE).is_ok() {
                        let _ = reader.join();
                    }
                    return Err(CliError::Timeout);
                }
                Ok(None) => thread::sleep(POLL_STEP),
                Err(e) => {
                    // Best effort: if the child is still ours, kill its group and reap it.
                    kill_group(&child);
                    let _ = child.wait();
                    return Err(CliError::Io(format!("{:?}", e.kind())));
                }
            }
        };
        // The child is gone. A grandchild that kept the pipe open would block the reader: kill the
        // group (a no-op when it is empty) so the reader ends, then join it.
        kill_group(&child);
        let bytes = rx
            .recv_timeout(READER_GRACE)
            .map_err(|_| CliError::Timeout)?
            .map_err(|e| CliError::Io(format!("{:?}", e.kind())))?;
        let _ = reader.join();
        Ok(RawOutput {
            // A signal death has no code: report it as a failure.
            exit_code: status.code().unwrap_or(-1),
            stdout: String::from_utf8_lossy(&bytes).into_owned(),
        })
    }
}

extern "C" {
    fn killpg(pgrp: i32, sig: i32) -> i32;
}

const SIGKILL: i32 = 9;

/// SIGKILL to the child's whole process group (the child is its leader, see `process_group(0)`).
fn kill_group(child: &std::process::Child) {
    if let Ok(pgid) = i32::try_from(child.id()) {
        // SAFETY: killpg takes two plain integers and touches no memory of ours.
        unsafe {
            killpg(pgid, SIGKILL);
        }
    }
}

fn spawn_error(e: std::io::Error) -> CliError {
    match e.kind() {
        // A binary we may not execute is as good as missing (DF5).
        std::io::ErrorKind::NotFound | std::io::ErrorKind::PermissionDenied => {
            CliError::NotInstalled
        }
        kind => CliError::Io(format!("{kind:?}")),
    }
}

/// A runner for a missing `$HOME` and no override: every call reports `NotInstalled`.
#[derive(Debug, Clone, Copy)]
pub struct MissingCli;

impl ServerCli for MissingCli {
    fn run(&self, _args: &[&str]) -> Result<RawOutput, CliError> {
        Err(CliError::NotInstalled)
    }
}
