//! Single instance (P26 (1)): the tray holds an exclusive `flock` on `$XDG_RUNTIME_DIR/aristotle-desktop/tray.lock`
//! for its whole life; a second tray exits 0 at once, silently, before it registers anything on D-Bus.
//!
//! Two processes: a helper (this test binary, started with `--ignored --exact`, like the log-child orphan test in
//! `app/src/logs.rs`) holds the lock, and the real `aristotle-tray` binary is started against the same runtime dir.

use std::fs::{self, File, OpenOptions};
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

extern "C" {
    fn flock(fd: i32, operation: i32) -> i32;
}
const LOCK_EX: i32 = 2;
const LOCK_NB: i32 = 4;

fn lock_file(runtime: &Path) -> PathBuf {
    runtime.join("aristotle-desktop").join("tray.lock")
}

/// Takes the lock like a running tray would; `None` when somebody else holds it.
fn take_lock(runtime: &Path) -> Option<File> {
    let path = lock_file(runtime);
    fs::create_dir_all(path.parent().unwrap()).unwrap();
    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(false)
        .open(path)
        .unwrap();
    // SAFETY: a valid open descriptor and plain integers.
    (unsafe { flock(file.as_raw_fd(), LOCK_EX | LOCK_NB) } == 0).then_some(file)
}

static SPAWN_GATE: Mutex<()> = Mutex::new(());

/// One lock around every script write and every spawn: a fork in one test thread copies a file descriptor another
/// thread still has open for writing, and the exec of that fresh script then fails with "Text file busy".
fn spawn_gate() -> MutexGuard<'static, ()> {
    SPAWN_GATE.lock().unwrap_or_else(|e| e.into_inner())
}

fn scratch(name: &str) -> PathBuf {
    let dir =
        std::env::temp_dir().join(format!("aristotle-instance-{name}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    dir
}

/// Runs only inside the process `holder` starts: holds the lock until it is killed.
#[test]
#[ignore = "helper process for the tray single-instance tests"]
fn lock_holder() {
    let runtime = PathBuf::from(std::env::var("ARISTOTLE_LOCK_HELPER").expect("started by a test"));
    let _lock = take_lock(&runtime).expect("the helper could not take the lock");
    fs::write(runtime.join("holder-ready"), "1").unwrap();
    thread::sleep(Duration::from_secs(60)); // killed by the parent test long before this ends
}

fn holder(runtime: &Path) -> Child {
    let _gate = spawn_gate();
    let child = Command::new(std::env::current_exe().unwrap())
        .args([
            "lock_holder",
            "--ignored",
            "--exact",
            "--nocapture",
            "--test-threads=1",
        ])
        .env("ARISTOTLE_LOCK_HELPER", runtime)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    drop(_gate);
    wait_for(|| runtime.join("holder-ready").exists());
    child
}

fn wait_for(cond: impl Fn() -> bool) {
    let end = Instant::now() + Duration::from_secs(10);
    while !cond() {
        assert!(Instant::now() < end, "timed out");
        thread::sleep(Duration::from_millis(20));
    }
}

/// A stand-in for `aristotle-server` (never the real one in `~/.local/bin`): it leaves a marker, then sleeps, so the
/// tray's first poll blocks and a tray that got past the lock stays up before any D-Bus step.
fn stub_server(runtime: &Path) -> PathBuf {
    use std::os::unix::fs::PermissionsExt;
    let stub = runtime.join("fake-aristotle-server");
    let _gate = spawn_gate();
    if !stub.exists() {
        fs::write(
            &stub,
            "#!/bin/sh\ntouch \"$(dirname \"$0\")/polled\"\nsleep 5\n",
        )
        .unwrap();
        fs::set_permissions(&stub, fs::Permissions::from_mode(0o755)).unwrap();
    }
    stub
}

/// The tray binary with a runtime dir, the stub server and no usable session bus: a tray that gets past the lock
/// blocks in its first poll (stays up) or fails (exit 1), and either way it is not the "second instance" outcome.
fn tray(runtime: &Path) -> Child {
    let stub = stub_server(runtime);
    let _gate = spawn_gate();
    Command::new(env!("CARGO_BIN_EXE_aristotle-tray"))
        .env("XDG_RUNTIME_DIR", runtime)
        .env(
            "DBUS_SESSION_BUS_ADDRESS",
            "unix:path=/nonexistent/aristotle-test-bus",
        )
        .env("ARISTOTLE_SERVER_BIN", stub)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap()
}

#[test]
fn a_second_tray_exits_zero_silently_while_another_holds_the_lock() {
    let runtime = scratch("second");
    let mut held = holder(&runtime);
    let mut second = tray(&runtime);
    let end = Instant::now() + Duration::from_secs(10);
    let status = loop {
        if let Some(status) = second.try_wait().unwrap() {
            break Some(status);
        }
        if Instant::now() > end {
            let _ = second.kill();
            break None;
        }
        thread::sleep(Duration::from_millis(20));
    };
    let out = second.wait_with_output().unwrap();
    let _ = held.kill();
    let _ = held.wait();
    let _ = fs::remove_dir_all(&runtime);
    let status = status.expect("the second tray did not exit within 10 s");
    assert_eq!(
        status.code(),
        Some(0),
        "stderr: {}",
        String::from_utf8_lossy(&out.stderr)
    );
    assert!(
        out.stdout.is_empty() && out.stderr.is_empty(),
        "a second tray must be silent: {out:?}"
    );
}

#[test]
fn the_lock_dies_with_its_holder_and_the_next_tray_takes_it() {
    let runtime = scratch("release");
    let mut held = holder(&runtime);
    assert!(take_lock(&runtime).is_none(), "the helper holds the lock");
    held.kill().unwrap(); // a crash: no destructor, the kernel releases the flock
    let _ = held.wait();
    wait_for(|| take_lock(&runtime).is_some());
    let _ = fs::remove_dir_all(&runtime);
}

#[test]
fn a_tray_with_no_other_instance_goes_on_to_start() {
    let runtime = scratch("first");
    let mut first = tray(&runtime);
    thread::sleep(Duration::from_millis(1500));
    // Not a "second instance" (that would have exited 0 by now). With no session bus it fails with exit 1 or keeps polling.
    let exited = first.try_wait().unwrap();
    let _ = first.kill();
    let out = first.wait_with_output().unwrap();
    let _ = fs::remove_dir_all(&runtime);
    if let Some(status) = exited {
        assert_eq!(
            status.code(),
            Some(1),
            "stderr: {}",
            String::from_utf8_lossy(&out.stderr)
        );
        assert!(String::from_utf8_lossy(&out.stderr).contains("could not start the tray"));
    }
}

/// A running tray holds the lock for its whole life (not only while it starts up).
#[test]
fn a_running_tray_holds_the_lock_and_a_second_real_tray_exits_zero_silently() {
    let runtime = scratch("running");
    let mut first = tray(&runtime);
    // The stub marks its start: the tray is past the lock and blocked in its first poll. No lock probe before that,
    // since a probe would make the first tray see a taken lock.
    wait_for(|| runtime.join("polled").exists());
    let held = take_lock(&runtime).is_none();
    let mut second = tray(&runtime);
    let end = Instant::now() + Duration::from_secs(10);
    let second_status = loop {
        if let Some(status) = second.try_wait().unwrap() {
            break Some(status);
        }
        if Instant::now() > end {
            let _ = second.kill();
            break None;
        }
        thread::sleep(Duration::from_millis(20));
    };
    let first_still_up = first.try_wait().unwrap().is_none();
    let _ = first.kill();
    let _ = first.wait();
    let out = second.wait_with_output().unwrap();
    let _ = fs::remove_dir_all(&runtime);
    assert!(held, "a running tray must hold the lock");
    assert!(first_still_up, "the first tray exited");
    assert_eq!(
        second_status.expect("the second tray did not exit").code(),
        Some(0)
    );
    assert!(out.stdout.is_empty() && out.stderr.is_empty(), "{out:?}");
}
