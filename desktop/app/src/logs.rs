//! `aristotle-server logs -f` as a child process, streamed to the frontend in batches of lines.
//!
//! The child leads its own process group, so killing the group also stops the journalctl it spawns, and
//! it dies with the app when the app is killed (`PR_SET_PDEATHSIG`, see `spawn_child`).
//! Line content is never logged (P11); the frontend keeps the capped buffer.

use crate::dto::{IpcError, IpcResult};
use std::io::{BufRead, BufReader};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex, OnceLock};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

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
    #[cfg(test)]
    fn kill(pid: i32, sig: i32) -> i32;
    fn prctl(option: i32, arg2: u64, arg3: u64, arg4: u64, arg5: u64) -> i32;
}

const PR_SET_PDEATHSIG: i32 = 1;

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
        let mut child = spawn_child(bin).map_err(spawn_error)?;
        let stdout = child.stdout.take().ok_or_else(IpcError::internal)?;
        let stopped = Arc::new(AtomicBool::new(false));
        let reader = thread::spawn({
            let stopped = stopped.clone();
            move || read_lines(stdout, &stopped, &emit)
        });
        let started = Running {
            child,
            reader,
            stopped,
        };
        // Swap in one step: a concurrent `start` that got here first is stopped, not leaked.
        let previous = lock(&self.running).replace(started);
        if let Some(previous) = previous {
            stop_running(previous);
        }
        Ok(())
    }

    /// Kills the child's group and reaps it, waiting at most [`STOP_WAIT`]. Quiet when nothing runs.
    pub fn stop(&self) {
        let running = lock(&self.running).take();
        if let Some(running) = running {
            stop_running(running);
        }
    }
}

/// A spawn request for the long-lived spawner thread: the binary and where to send the child.
type SpawnJob = (PathBuf, mpsc::Sender<std::io::Result<Child>>);

/// Spawns `logs -f` from one thread that lives as long as the process. The child gets `SIGKILL` when its
/// parent *thread* exits (`PR_SET_PDEATHSIG` is per thread), so spawning from a short-lived blocking-pool
/// thread would kill the stream early; spawning from this one means it dies with the app, also on a crash
/// (a journalctl it started ends on its next write, when the pipe is closed).
fn spawn_child(bin: &Path) -> std::io::Result<Child> {
    static SPAWNER: OnceLock<Mutex<mpsc::Sender<SpawnJob>>> = OnceLock::new();
    let jobs = SPAWNER.get_or_init(|| {
        let (tx, rx) = mpsc::channel::<SpawnJob>();
        thread::spawn(move || {
            for (bin, reply) in rx {
                let _ = reply.send(spawn_logs(&bin));
            }
        });
        Mutex::new(tx)
    });
    let (reply, child) = mpsc::channel();
    lock(jobs)
        .send((bin.to_path_buf(), reply))
        .map_err(|_| std::io::Error::other("log spawner is gone"))?;
    child
        .recv()
        .unwrap_or_else(|_| Err(std::io::Error::other("log spawner is gone")))
}

fn spawn_logs(bin: &Path) -> std::io::Result<Child> {
    let mut command = Command::new(bin);
    command
        .args(["logs", "-f"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .process_group(0);
    // SAFETY: the hook runs between fork and exec and only calls `prctl` with plain integers.
    unsafe {
        command.pre_exec(|| {
            if prctl(PR_SET_PDEATHSIG, SIGKILL as u64, 0, 0, 0) == 0 {
                Ok(())
            } else {
                Err(std::io::Error::last_os_error())
            }
        });
    }
    command.spawn()
}

/// How long `stop` may wait for the child to exit and the reader to finish.
const STOP_WAIT: Duration = Duration::from_secs(2);
const STOP_POLL: Duration = Duration::from_millis(10);

/// Kills the group, waits up to `STOP_WAIT`, then `kill`s the child itself. Whatever is still not
/// finished (an unkillable child, or a process outside the group holding the pipe) goes to a
/// detached reaper thread, so the caller (the window's close path) never blocks without bound.
fn stop_running(mut running: Running) {
    running.stopped.store(true, Ordering::SeqCst);
    if let Ok(pgid) = i32::try_from(running.child.id()) {
        // SAFETY: killpg takes two plain integers and touches no memory of ours. The child is not
        // reaped yet, so its pid cannot belong to anything else.
        unsafe {
            killpg(pgid, SIGKILL);
        }
    }
    let deadline = Instant::now() + STOP_WAIT;
    let exited = wait_until(deadline, || matches!(running.child.try_wait(), Ok(Some(_))));
    if !exited {
        let _ = running.child.kill();
    }
    let reader = &running.reader;
    if exited && wait_until(deadline, || reader.is_finished()) {
        let _ = running.reader.join();
        return;
    }
    let Running {
        mut child, reader, ..
    } = running;
    thread::spawn(move || {
        let _ = child.wait();
        let _ = reader.join();
    });
}

/// Polls `done` every `STOP_POLL` until it is true or the deadline passes.
fn wait_until(deadline: Instant, mut done: impl FnMut() -> bool) -> bool {
    loop {
        if done() {
            return true;
        }
        if Instant::now() >= deadline {
            return false;
        }
        thread::sleep(STOP_POLL);
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

    /// Runs only inside the process the next test starts (see `a_killed_app_does_not_leave_the_log_child`).
    #[test]
    fn orphan_helper() {
        let Ok(dir) = std::env::var("ARISTOTLE_ORPHAN_HELPER") else {
            return;
        };
        let bin = PathBuf::from(dir).join("fake-server");
        let stream = LogStream::new();
        stream.start(Some(&bin), |_| {}).unwrap();
        thread::sleep(Duration::from_secs(60)); // killed by the parent test long before this ends
    }

    #[test]
    fn a_killed_app_does_not_leave_the_log_child() {
        let _g = fork_lock();
        let (dir, _bin) = script(
            "orphan",
            "echo $$ > \"$(dirname \"$0\")/pid\"\nexec sleep 300",
        );
        let mut app = Command::new(std::env::current_exe().unwrap())
            .args([
                "logs::tests::orphan_helper",
                "--exact",
                "--nocapture",
                "--test-threads=1",
            ])
            .env("ARISTOTLE_ORPHAN_HELPER", &dir)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        wait_for("the log child to start", || dir.join("pid").exists());
        let pid = fs::read_to_string(dir.join("pid"))
            .unwrap()
            .trim()
            .to_owned();
        assert!(alive(&pid));
        app.kill().unwrap(); // a crash: no destructor, no `stop`
        let _ = app.wait();
        let gone = |pid: &str| {
            fs::read_to_string(format!("/proc/{pid}/stat"))
                .map_or(true, |stat| stat.contains(") Z "))
        };
        let end = Instant::now() + Duration::from_secs(3);
        while !gone(&pid) && Instant::now() < end {
            thread::sleep(Duration::from_millis(20));
        }
        let survived = !gone(&pid);
        if survived {
            if let Ok(n) = pid.parse::<i32>() {
                // SAFETY: plain integers; cleans up the leaked child before the assert.
                unsafe { killpg(n, SIGKILL) };
                unsafe { kill(n, SIGKILL) };
            }
        }
        assert!(!survived, "log child {pid} outlived the app");
        let _ = fs::remove_dir_all(dir);
    }

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

    /// A grandchild that leaves the group (`setsid`) and keeps the stdout pipe open.
    const ESCAPEE: &str = "setsid sleep 30 &\necho $! > \"$(dirname \"$0\")/escapee-pid\"\nwhile true; do echo line; sleep 0.05; done";

    fn kill_pid(pid: &str) {
        let _ = std::process::Command::new("kill")
            .args(["-9", pid])
            .status();
    }

    #[test]
    fn stop_returns_in_bounded_time_when_a_process_outside_the_group_holds_the_pipe() {
        let _g = fork_lock();
        let (dir, bin) = script("escapee", ESCAPEE);
        let (events, emit) = collector();
        let stream = Arc::new(LogStream::new());
        stream.start(Some(&bin), emit).unwrap();
        wait_for("a first line", || !events.lock().unwrap().is_empty());
        wait_for("the escapee pid", || dir.join("escapee-pid").exists());
        let escapee = fs::read_to_string(dir.join("escapee-pid"))
            .unwrap()
            .trim()
            .to_owned();
        let (done_tx, done_rx) = std::sync::mpsc::channel();
        let stopper = stream.clone();
        thread::spawn(move || {
            stopper.stop();
            let _ = done_tx.send(());
        });
        let returned = done_rx.recv_timeout(Duration::from_secs(3));
        kill_pid(&escapee);
        assert!(returned.is_ok(), "stop() did not return within 3 s");
        let _ = fs::remove_dir_all(dir);
    }

    const RECORDING: &str =
        "echo $$ >> \"$(dirname \"$0\")/pids\"\nwhile true; do echo line; sleep 0.05; done";

    fn pids(dir: &Path) -> Vec<String> {
        fs::read_to_string(dir.join("pids"))
            .unwrap_or_default()
            .lines()
            .map(str::to_owned)
            .collect()
    }

    #[test]
    fn two_starts_at_once_leave_exactly_one_stream_running() {
        let _g = fork_lock();
        for round in 0..5 {
            let (dir, bin) = script(&format!("twostarts{round}"), RECORDING);
            let stream = Arc::new(LogStream::new());
            let gate = Arc::new(std::sync::Barrier::new(2));
            let starters: Vec<_> = (0..2)
                .map(|_| {
                    let (stream, gate, bin) = (stream.clone(), gate.clone(), bin.clone());
                    thread::spawn(move || {
                        gate.wait();
                        stream.start(Some(&bin), |_| {}).unwrap();
                    })
                })
                .collect();
            for starter in starters {
                starter.join().unwrap();
            }
            // A loser killed before its shell wrote a pid leaves one line; either way, once the
            // starts have settled exactly one child may be alive.
            wait_for("a child to have started", || !pids(&dir).is_empty());
            thread::sleep(Duration::from_millis(600));
            let alive_now = pids(&dir).iter().filter(|p| alive(p)).count();
            assert_eq!(alive_now, 1, "round {round}: pids {:?}", pids(&dir));
            stream.stop();
            wait_for("the last child to die", || {
                pids(&dir).iter().all(|p| !alive(p))
            });
            let _ = fs::remove_dir_all(dir);
        }
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
