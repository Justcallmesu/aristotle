//! `aristotle-server logs -f` as a child process, streamed to the frontend in batches of lines.
//!
//! The child leads its own process group, so killing the group also stops the journalctl it spawns.
//! Line content is never logged (P11); the frontend keeps the capped buffer.

use crate::dto::{IpcError, IpcResult};
use std::io::{BufRead, BufReader};
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};

/// Lines per event at most; a smaller batch goes out as soon as the pipe has no more buffered data.
const MAX_BATCH: usize = 200;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LogEvent {
    Lines(Vec<String>),
    /// The stream ended without a `stop` (the server or journalctl exited).
    Ended,
}

struct Running {
    child: Child,
    reader: JoinHandle<()>,
    stopped: Arc<AtomicBool>,
}

#[derive(Default)]
pub struct LogStream {
    running: Mutex<Option<Running>>,
}

extern "C" {
    fn killpg(pgrp: i32, sig: i32) -> i32;
}

const SIGKILL: i32 = 9;

impl LogStream {
    pub fn new() -> Self {
        Self::default()
    }

    /// Starts the stream; a stream that is already running is stopped first.
    pub fn start(
        &self,
        bin: Option<&Path>,
        emit: impl Fn(LogEvent) + Send + 'static,
    ) -> IpcResult<()> {
        self.stop();
        let bin = bin.ok_or_else(IpcError::not_installed)?;
        let mut child = Command::new(bin)
            .args(["logs", "-f"])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .process_group(0)
            .spawn()
            .map_err(spawn_error)?;
        let stdout = child.stdout.take().ok_or_else(IpcError::internal)?;
        let stopped = Arc::new(AtomicBool::new(false));
        let reader = thread::spawn({
            let stopped = stopped.clone();
            move || read_lines(stdout, &stopped, &emit)
        });
        *lock(&self.running) = Some(Running {
            child,
            reader,
            stopped,
        });
        Ok(())
    }

    /// Kills the child's group and reaps it. Quiet when nothing runs.
    pub fn stop(&self) {
        let Some(mut running) = lock(&self.running).take() else {
            return;
        };
        running.stopped.store(true, Ordering::SeqCst);
        if let Ok(pgid) = i32::try_from(running.child.id()) {
            // SAFETY: killpg takes two plain integers and touches no memory of ours. The child is not
            // reaped yet, so its pid cannot belong to anything else.
            unsafe {
                killpg(pgid, SIGKILL);
            }
        }
        let _ = running.child.wait();
        let _ = running.reader.join();
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(|e| e.into_inner())
}

fn spawn_error(e: std::io::Error) -> IpcError {
    match e.kind() {
        std::io::ErrorKind::NotFound | std::io::ErrorKind::PermissionDenied => {
            IpcError::not_installed()
        }
        _ => IpcError::new("io"),
    }
}

fn read_lines(stdout: impl std::io::Read, stopped: &AtomicBool, emit: &impl Fn(LogEvent)) {
    let mut reader = BufReader::new(stdout);
    let mut batch: Vec<String> = Vec::new();
    let mut raw = Vec::new();
    loop {
        raw.clear();
        match reader.read_until(b'\n', &mut raw) {
            Ok(0) | Err(_) => break,
            Ok(_) => batch.push(line_text(&raw)),
        }
        if batch.len() >= MAX_BATCH || reader.buffer().is_empty() {
            flush(&mut batch, stopped, emit);
        }
    }
    flush(&mut batch, stopped, emit);
    if !stopped.load(Ordering::SeqCst) {
        emit(LogEvent::Ended);
    }
}

fn line_text(raw: &[u8]) -> String {
    let text = String::from_utf8_lossy(raw);
    text.trim_end_matches(['\n', '\r']).to_owned()
}

fn flush(batch: &mut Vec<String>, stopped: &AtomicBool, emit: &impl Fn(LogEvent)) {
    if batch.is_empty() {
        return;
    }
    let lines = std::mem::take(batch);
    if !stopped.load(Ordering::SeqCst) {
        emit(LogEvent::Lines(lines));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::fork_lock;
    use std::fs;
    use std::os::unix::fs::PermissionsExt;
    use std::path::PathBuf;
    use std::time::{Duration, Instant};

    fn script(tag: &str, body: &str) -> (PathBuf, PathBuf) {
        let dir = std::env::temp_dir().join(format!("aristotle-logs-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("fake-server");
        fs::write(&bin, format!("#!/bin/sh\n{body}\n")).unwrap();
        fs::set_permissions(&bin, fs::Permissions::from_mode(0o755)).unwrap();
        (dir, bin)
    }

    fn collector() -> (
        Arc<Mutex<Vec<LogEvent>>>,
        impl Fn(LogEvent) + Send + 'static,
    ) {
        let events = Arc::new(Mutex::new(Vec::new()));
        let sink = events.clone();
        (events, move |e| sink.lock().unwrap().push(e))
    }

    fn wait_for(what: &str, mut ok: impl FnMut() -> bool) {
        let end = Instant::now() + Duration::from_secs(5);
        while !ok() {
            assert!(Instant::now() < end, "timed out waiting for {what}");
            thread::sleep(Duration::from_millis(20));
        }
    }

    fn alive(pid: &str) -> bool {
        Path::new(&format!("/proc/{pid}")).exists()
    }

    const ENDLESS: &str =
        "echo $$ > \"$(dirname \"$0\")/pid\"\nsleep 60 &\necho $! > \"$(dirname \"$0\")/child-pid\"\nwhile true; do echo \"line $$\"; sleep 0.05; done";

    #[test]
    fn stop_kills_the_child_and_its_group_and_reaps_it() {
        let _g = fork_lock();
        let (dir, bin) = script("stop", ENDLESS);
        let (events, emit) = collector();
        let stream = LogStream::new();
        stream.start(Some(&bin), emit).unwrap();
        wait_for("a first line", || !events.lock().unwrap().is_empty());
        let pid = fs::read_to_string(dir.join("pid"))
            .unwrap()
            .trim()
            .to_owned();
        let child_pid = fs::read_to_string(dir.join("child-pid"))
            .unwrap()
            .trim()
            .to_owned();
        assert!(alive(&pid));
        let stopping = Instant::now();
        stream.stop();
        assert!(
            stopping.elapsed() < Duration::from_secs(3),
            "stop waited {:?}: the group was not killed",
            stopping.elapsed()
        );
        assert!(!alive(&pid), "child {pid} still present after stop");
        wait_for("the grandchild to die", || !alive(&child_pid));
        let seen = events.lock().unwrap().len();
        thread::sleep(Duration::from_millis(200));
        assert_eq!(events.lock().unwrap().len(), seen, "no events after stop");
        assert!(!events.lock().unwrap().contains(&LogEvent::Ended));
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn starting_again_stops_the_previous_child() {
        let _g = fork_lock();
        let (dir, bin) = script("restart", ENDLESS);
        let (events, emit) = collector();
        let stream = LogStream::new();
        stream.start(Some(&bin), emit).unwrap();
        wait_for("a first line", || !events.lock().unwrap().is_empty());
        let first = fs::read_to_string(dir.join("pid"))
            .unwrap()
            .trim()
            .to_owned();
        let (_events2, emit2) = collector();
        stream.start(Some(&bin), emit2).unwrap();
        assert!(!alive(&first), "first child {first} survived the restart");
        stream.stop();
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_stream_that_ends_by_itself_reports_ended_after_its_lines() {
        let _g = fork_lock();
        let (dir, bin) = script("ends", "echo one\necho 'two  '\nprintf 'three'");
        let (events, emit) = collector();
        let stream = LogStream::new();
        stream.start(Some(&bin), emit).unwrap();
        wait_for("the end", || {
            events.lock().unwrap().contains(&LogEvent::Ended)
        });
        let all: Vec<String> = events
            .lock()
            .unwrap()
            .iter()
            .flat_map(|e| match e {
                LogEvent::Lines(l) => l.clone(),
                LogEvent::Ended => vec![],
            })
            .collect();
        assert_eq!(all, ["one", "two  ", "three"]);
        stream.stop();
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_missing_binary_is_not_installed_and_stop_is_quiet() {
        let stream = LogStream::new();
        let missing = std::env::temp_dir().join("aristotle-no-such-logs-bin");
        let err = stream.start(Some(&missing), |_| {}).unwrap_err();
        assert_eq!(err.kind, "not-installed");
        assert_eq!(
            stream.start(None, |_| {}).unwrap_err().kind,
            "not-installed"
        );
        stream.stop();
    }
}
