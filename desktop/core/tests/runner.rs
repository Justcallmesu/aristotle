//! `RealCli` against fake binaries (shell scripts in a temp dir) and `parse_output`.

mod common;
use aristotle_core::runner::*;
use aristotle_core::types::{ActionResult, Status};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant};

/// Serializes every test that forks. A fork in one test can inherit another test's
/// still-open write fd on its fake script, and exec then fails with ETXTBSY.
static FORK_LOCK: Mutex<()> = Mutex::new(());

fn fork_lock() -> MutexGuard<'static, ()> {
    FORK_LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

struct Scratch(PathBuf, #[allow(dead_code)] MutexGuard<'static, ()>);
impl Scratch {
    fn new(tag: &str) -> Self {
        let guard = fork_lock();
        let dir = std::env::temp_dir().join(format!("aristotle-core-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        Scratch(dir, guard)
    }
    fn script(&self, body: &str) -> PathBuf {
        let path = self.0.join("fake-server");
        fs::write(&path, format!("#!/bin/sh\n{body}\n")).unwrap();
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).unwrap();
        path
    }
}
impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn cli(bin: &Path, secs: u64) -> RealCli {
    RealCli::new(bin.to_path_buf(), Duration::from_secs(secs))
}

#[test]
fn success_object_is_parsed_with_exit_code() {
    let s = Scratch::new("ok");
    let bin = s.script(r#"echo '{"ok":true,"service":"active"}'"#);
    let reply = query::<ActionResult>(&cli(&bin, 5), &["start"]).unwrap();
    assert_eq!(reply.exit_code, 0);
    assert!(reply.value.ok);
}

#[test]
fn success_object_with_exit_1_is_still_success() {
    let s = Scratch::new("exit1");
    let bin = s.script(&format!(
        "cat <<'EOF'\n{}EOF\nexit 1",
        common::fixture("status_not_running.json")
    ));
    let reply = query::<Status>(&cli(&bin, 5), &["status"]).unwrap();
    assert_eq!(reply.exit_code, 1);
    assert!(!reply.value.is_running());
}

#[test]
fn error_object_and_exit_1_is_a_server_error() {
    let s = Scratch::new("err");
    let bin = s.script(r#"echo '{"error":"refused: inside a terminal"}'; exit 1"#);
    let err = query::<ActionResult>(&cli(&bin, 5), &["stop"]).unwrap_err();
    assert_eq!(
        err,
        CliError::Server {
            message: "refused: inside a terminal".into(),
            exit_code: 1
        }
    );
}

#[test]
fn garbage_stdout_is_bad_output_without_content() {
    let s = Scratch::new("garbage");
    let bin = s.script("echo 'not json at all SECRET-TEXT'; exit 3");
    let err = query::<ActionResult>(&cli(&bin, 5), &["start"]).unwrap_err();
    assert_eq!(err, CliError::BadOutput { exit_code: 3 });
    assert!(!format!("{err} {err:?}").contains("SECRET-TEXT"));
}

#[test]
fn wrong_shape_is_bad_output() {
    let s = Scratch::new("shape");
    let bin = s.script(r#"echo '{"unrelated":1}'"#);
    let err = query::<ActionResult>(&cli(&bin, 5), &["start"]).unwrap_err();
    assert_eq!(err, CliError::BadOutput { exit_code: 0 });
}

#[test]
fn timeout_kills_the_process() {
    let s = Scratch::new("timeout");
    let pid_file = s.0.join("pid");
    let bin = s.script(&format!("echo $$ > {}\nexec sleep 30", pid_file.display()));
    let started = Instant::now();
    let err = cli(&bin, 1).run(&["status", "--json"]).unwrap_err();
    assert_eq!(err, CliError::Timeout);
    assert!(started.elapsed() < Duration::from_secs(5));
    let pid = fs::read_to_string(&pid_file).unwrap().trim().to_owned();
    assert!(
        !Path::new(&format!("/proc/{pid}")).exists(),
        "process {pid} still alive after timeout"
    );
}

#[test]
fn missing_binary_is_not_installed() {
    let _guard = fork_lock();
    let missing = std::env::temp_dir().join("aristotle-no-such-binary-xyz");
    let err = cli(&missing, 5).run(&["status", "--json"]).unwrap_err();
    assert_eq!(err, CliError::NotInstalled);
}

#[test]
fn semicolon_argument_is_passed_literally() {
    let s = Scratch::new("argv");
    let out = s.0.join("argv.txt");
    let bin = s.script(&format!(
        "for a in \"$@\"; do printf '%s\\n' \"$a\"; done > {}\necho '{{\"ok\":true,\"service\":\"x\"}}'",
        out.display()
    ));
    query::<ActionResult>(&cli(&bin, 5), &["devices", "revoke", "a;touch pwned"]).unwrap();
    let argv = fs::read_to_string(&out).unwrap();
    assert_eq!(argv, "devices\nrevoke\na;touch pwned\n--json\n");
    assert!(!s.0.join("pwned").exists());
}

#[test]
fn query_appends_json_as_the_last_argument() {
    let cli = common::FakeCli::new(vec![common::ok(r#"{"ok":true,"service":"active"}"#, 0)]);
    query::<ActionResult>(&cli, &["start"]).unwrap();
    assert_eq!(
        cli.calls(),
        vec![vec!["start".to_string(), "--json".into()]]
    );
}

#[test]
fn default_bin_prefers_the_env_override() {
    // One test touches the environment (tests run in parallel threads).
    std::env::set_var(BIN_ENV, "/tmp/override-bin");
    assert_eq!(default_bin(), Some(PathBuf::from("/tmp/override-bin")));
    std::env::remove_var(BIN_ENV);
    std::env::set_var("HOME", "/home/x");
    assert_eq!(
        default_bin(),
        Some(PathBuf::from("/home/x/.local/bin/aristotle-server"))
    );
    // A relative override would be looked up on PATH (P4): ignored, the default applies.
    for relative in [
        "aristotle-server",
        "./aristotle-server",
        "bin/aristotle-server",
    ] {
        std::env::set_var(BIN_ENV, relative);
        assert_eq!(
            default_bin(),
            Some(PathBuf::from("/home/x/.local/bin/aristotle-server")),
            "{relative}"
        );
    }
    std::env::remove_var(BIN_ENV);
    std::env::remove_var("HOME");
    assert_eq!(default_bin(), None);
}

#[test]
fn timeout_kills_grandchildren_too() {
    let s = Scratch::new("group");
    let child_pid = s.0.join("child-pid");
    let bin = s.script(&format!(
        "sleep 30 &\necho $! > {}\nexec sleep 30",
        child_pid.display()
    ));
    let started = Instant::now();
    let err = cli(&bin, 1).run(&["status", "--json"]).unwrap_err();
    assert_eq!(err, CliError::Timeout);
    assert!(started.elapsed() < Duration::from_secs(5));
    let pid = fs::read_to_string(&child_pid).unwrap().trim().to_owned();
    let gone = (0..50).any(|_| {
        let gone = !Path::new(&format!("/proc/{pid}")).exists();
        if !gone {
            std::thread::sleep(Duration::from_millis(100));
        }
        gone
    });
    assert!(gone, "grandchild {pid} still alive after the timeout");
}

#[test]
fn error_kind_is_a_fixed_code_without_the_message() {
    let secret = CliError::Server {
        message: "systemctl said SECRET-TEXT".into(),
        exit_code: 1,
    };
    assert_eq!(secret.kind(), "server-error");
    assert!(!secret.kind().contains("SECRET"));
    assert_eq!(CliError::Timeout.kind(), "timeout");
    assert_eq!(CliError::NotInstalled.kind(), "not-installed");
    assert_eq!(CliError::BadOutput { exit_code: 2 }.kind(), "bad-output");
    assert_eq!(CliError::Io("NotFound".into()).kind(), "io");
}

#[test]
fn a_grandchild_holding_stdout_is_killed_and_the_output_still_returns() {
    let s = Scratch::new("held");
    let child_pid = s.0.join("held-pid");
    let bin = s.script(&format!(
        "sleep 30 &\necho $! > {}\necho '{{\"ok\":true,\"service\":\"active\"}}'",
        child_pid.display()
    ));
    let started = Instant::now();
    let reply = query::<ActionResult>(&cli(&bin, 10), &["start"]).unwrap();
    assert!(reply.value.ok);
    assert!(
        started.elapsed() < Duration::from_millis(1500),
        "waited {:?} for the reader",
        started.elapsed()
    );
    let pid = fs::read_to_string(&child_pid).unwrap().trim().to_owned();
    let gone = (0..50).any(|_| {
        let gone = !Path::new(&format!("/proc/{pid}")).exists();
        if !gone {
            std::thread::sleep(Duration::from_millis(100));
        }
        gone
    });
    assert!(gone, "grandchild {pid} still alive after the child exited");
}

#[test]
fn a_binary_without_execute_permission_is_not_installed() {
    let s = Scratch::new("noexec");
    let bin = s.script("echo '{}'");
    fs::set_permissions(&bin, fs::Permissions::from_mode(0o644)).unwrap();
    let err = cli(&bin, 5).run(&["status", "--json"]).unwrap_err();
    assert_eq!(err, CliError::NotInstalled);
}

/// A fake server that answers `status` at once and hangs on `hung` (the first CLI word of a read).
fn hanging_read_script(s: &Scratch, hung: &str) -> PathBuf {
    s.script(&format!(
        "if [ \"$1\" = \"{hung}\" ]; then exec sleep 30; fi\n\
         case \"$1\" in\n\
         status) cat <<'EOF'\n{}EOF\n;;\n\
         version) cat <<'EOF'\n{}EOF\n;;\n\
         devices) cat <<'EOF'\n{}EOF\n;;\n\
         terminal) cat <<'EOF'\n{}EOF\n;;\n\
         esac",
        common::fixture("status.json"),
        common::fixture("version.json"),
        common::fixture("devices_list.json"),
        common::fixture("terminal_status.json"),
    ))
}

/// P23 (c): `devices list`, `terminal status` and `version` give up after 3 s, `status` keeps the
/// runner's own limit (10 s here, so a hang in a read costs 3 s, not 10).
fn poll_with_hung(hung: &str) -> (aristotle_core::snapshot::Snapshot, Duration) {
    let s = Scratch::new(&format!("hung-{hung}"));
    let bin = hanging_read_script(&s, hung);
    let mut snapshotter = aristotle_core::snapshot::Snapshotter::new(Some("1.2.3"));
    let started = Instant::now();
    let snapshot = snapshotter.poll(&cli(&bin, 10));
    (snapshot, started.elapsed())
}

#[test]
fn a_hung_devices_read_times_out_after_3_s_and_is_a_failed_read() {
    let (snapshot, took) = poll_with_hung("devices");
    assert!(took < Duration::from_secs(6), "took {took:?}");
    assert!(snapshot.answered().is_some());
    assert_eq!(snapshot.devices, None);
    assert!(snapshot.terminals.is_some(), "the other reads still ran");
}

#[test]
fn a_hung_terminal_status_read_times_out_after_3_s_and_is_a_failed_read() {
    let (snapshot, took) = poll_with_hung("terminal");
    assert!(took < Duration::from_secs(6), "took {took:?}");
    assert_eq!(snapshot.terminals, None);
    assert_eq!(snapshot.terminal_enabled, None);
    assert_eq!(snapshot.terminal_granted, None);
    assert!(snapshot.devices.is_some());
}

#[test]
fn a_hung_version_read_times_out_after_3_s_and_leaves_the_check_unchecked() {
    let (snapshot, took) = poll_with_hung("version");
    assert!(took < Duration::from_secs(6), "took {took:?}");
    assert_eq!(
        snapshot.version,
        aristotle_core::snapshot::VersionCheck::Unchecked
    );
    assert!(snapshot.devices.is_some());
}

#[test]
fn a_hung_status_keeps_the_runner_limit() {
    let s = Scratch::new("hung-status");
    let bin = hanging_read_script(&s, "status");
    let started = Instant::now();
    let err = query::<Status>(&cli(&bin, 5), &["status"]).unwrap_err();
    assert_eq!(err, CliError::Timeout);
    assert!(started.elapsed() >= Duration::from_secs(5));
}
