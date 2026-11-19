//! Launches the window app (`aristotle-desktop`) from the tray. One tracked child (single instance on
//! the tray side); a reaper thread owns each `Child` so none stays a zombie.

use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

/// Development override for the window app path.
pub const DESKTOP_BIN_ENV: &str = "ARISTOTLE_DESKTOP_BIN";
/// A child that dies badly within this long of launch means the window could not open (P11).
const EARLY_EXIT_WINDOW: Duration = Duration::from_secs(5);
const WINDOW_BIN_NAME: &str = "aristotle-desktop";

/// `override` if it is an absolute path, else `aristotle-desktop` next to the tray executable. `None`
/// when neither gives an absolute path (a relative one would be looked up on `PATH`).
pub fn window_bin(override_path: Option<&Path>, current_exe: Option<&Path>) -> Option<PathBuf> {
    if let Some(path) = override_path.filter(|p| p.is_absolute()) {
        return Some(path.to_path_buf());
    }
    let dir = current_exe?.parent()?;
    dir.is_absolute().then(|| dir.join(WINDOW_BIN_NAME))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Launch {
    Started(u32),
    /// A window app runs and no argument asked for anything: nothing to do.
    AlreadyRunning(u32),
    /// The window app binary does not exist.
    Missing,
    Failed,
}

pub struct Launcher {
    /// `None`: no usable path, so the window app counts as missing.
    bin: Option<PathBuf>,
    running: Mutex<Option<u32>>,
    /// Called once when a launched child dies badly within `EARLY_EXIT_WINDOW`.
    on_early_failure: Box<dyn Fn() + Send + Sync>,
}

impl Launcher {
    /// `on_early_failure` runs on the reaper thread, so it may block.
    pub fn from_env(on_early_failure: impl Fn() + Send + Sync + 'static) -> Arc<Self> {
        let exe = std::env::current_exe().ok();
        let override_path = aristotle_core::runner::absolute_env_path(DESKTOP_BIN_ENV);
        Arc::new(Self {
            bin: window_bin(override_path.as_deref(), exe.as_deref()),
            running: Mutex::new(None),
            on_early_failure: Box::new(on_early_failure),
        })
    }

    /// Opens the window app. With no `args` and one already running, does nothing. With `args`
    /// (`--pair`, `--confirm stop`) it always launches: the app's own single-instance guard hands the
    /// argument to the open window (P14) and exits.
    pub fn open(self: &Arc<Self>, args: &[&str], activation_token: Option<String>) -> Launch {
        let Some(bin) = self.bin.as_ref().filter(|b| b.exists()) else {
            return Launch::Missing;
        };
        let mut slot = self.running.lock().unwrap_or_else(|e| e.into_inner());
        if let (Some(pid), true) = (*slot, args.is_empty()) {
            return Launch::AlreadyRunning(pid);
        }
        let mut cmd = Command::new(bin);
        cmd.args(args);
        if let Some(token) = activation_token {
            cmd.env("XDG_ACTIVATION_TOKEN", token);
        }
        match cmd.spawn() {
            Ok(child) => {
                let pid = child.id();
                if args.is_empty() {
                    *slot = Some(pid);
                }
                let this = Arc::clone(self);
                let tracked = args.is_empty();
                thread::spawn(move || this.reap(child, tracked));
                Launch::Started(pid)
            }
            Err(_) => Launch::Failed,
        }
    }

    fn reap(&self, mut child: Child, tracked: bool) {
        let started = Instant::now();
        let failed = child
            .wait()
            .map(|status| !status.success())
            .unwrap_or(false);
        // The slot first: the notice below is a blocking D-Bus call, and an "Open" click during it must start a window.
        if tracked {
            *self.running.lock().unwrap_or_else(|e| e.into_inner()) = None;
        }
        if failed && started.elapsed() < EARLY_EXIT_WINDOW {
            (self.on_early_failure)();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::spawn_gate;

    /// `open` under the spawn gate, so no other test holds a script open for writing across this fork.
    fn open_gated(l: &Arc<Launcher>, args: &[&str]) -> Launch {
        let _gate = spawn_gate();
        l.open(args, None)
    }

    fn launcher(bin: Option<&str>) -> Arc<Launcher> {
        Arc::new(Launcher {
            bin: bin.map(PathBuf::from),
            running: Mutex::new(None),
            on_early_failure: Box::new(|| {}),
        })
    }

    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    /// A launcher over a throwaway shell script that counts early-failure notices.
    fn fake(script: &str) -> (Arc<Launcher>, Arc<AtomicUsize>, PathBuf) {
        use std::os::unix::fs::PermissionsExt;
        static N: AtomicUsize = AtomicUsize::new(0);
        let dir = std::env::temp_dir().join(format!(
            "aristotle-launcher-test-{}-{}",
            std::process::id(),
            N.fetch_add(1, Ordering::SeqCst)
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("aristotle-desktop");
        {
            let _gate = spawn_gate();
            std::fs::write(&bin, format!("#!/bin/sh\n{script}\n")).unwrap();
            std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let count = Arc::new(AtomicUsize::new(0));
        let seen = Arc::clone(&count);
        let l = Arc::new(Launcher {
            bin: Some(bin),
            running: Mutex::new(None),
            on_early_failure: Box::new(move || {
                seen.fetch_add(1, Ordering::SeqCst);
            }),
        });
        (l, count, dir)
    }

    fn wait_until_idle(l: &Launcher) {
        for _ in 0..100 {
            if l.running.lock().unwrap().is_none() {
                return;
            }
            thread::sleep(Duration::from_millis(100));
        }
        panic!("child never reaped");
    }

    #[test]
    fn a_child_that_fails_at_once_gives_one_notice() {
        let (l, count, dir) = fake("exit 1");
        assert!(matches!(open_gated(&l, &[]), Launch::Started(_)));
        wait_until_idle(&l);
        thread::sleep(Duration::from_millis(100));
        assert_eq!(count.load(Ordering::SeqCst), 1);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_child_killed_by_a_signal_gives_one_notice() {
        let (l, count, dir) = fake("kill -9 $$");
        open_gated(&l, &[]);
        wait_until_idle(&l);
        thread::sleep(Duration::from_millis(100));
        assert_eq!(count.load(Ordering::SeqCst), 1);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_child_that_exits_zero_is_silent() {
        let (l, count, dir) = fake("exit 0");
        open_gated(&l, &["--pair"]);
        thread::sleep(Duration::from_millis(500));
        assert_eq!(count.load(Ordering::SeqCst), 0);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_child_that_runs_past_the_window_is_silent_even_when_it_fails_later() {
        let (l, count, dir) = fake("sleep 6; exit 1");
        open_gated(&l, &[]);
        // open() returns at once: the tray never waits on the child.
        assert!(l.running.lock().unwrap().is_some());
        wait_until_idle(&l);
        assert_eq!(count.load(Ordering::SeqCst), 0);
        let _ = std::fs::remove_dir_all(dir);
    }

    /// L8: the slot is cleared before the notice, which is a blocking D-Bus call: an "Open" click during it must not
    /// be answered with `AlreadyRunning` for a child that is already dead.
    #[test]
    fn the_running_slot_is_clear_while_the_early_failure_notice_is_shown() {
        use std::os::unix::fs::PermissionsExt;
        let dir =
            std::env::temp_dir().join(format!("aristotle-launcher-slot-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let bin = dir.join("aristotle-desktop");
        {
            let _gate = spawn_gate();
            std::fs::write(&bin, "#!/bin/sh\nexit 1\n").unwrap();
            std::fs::set_permissions(&bin, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let cell: Arc<Mutex<Option<Arc<Launcher>>>> = Arc::new(Mutex::new(None));
        let slot_was_clear = Arc::new(AtomicUsize::new(0));
        let notices = Arc::new(AtomicUsize::new(0));
        let (hook_cell, hook_clear, hook_count) = (
            Arc::clone(&cell),
            Arc::clone(&slot_was_clear),
            Arc::clone(&notices),
        );
        let l = Arc::new(Launcher {
            bin: Some(bin),
            running: Mutex::new(None),
            on_early_failure: Box::new(move || {
                let launcher = hook_cell.lock().unwrap().clone().expect("launcher set");
                if launcher.running.lock().unwrap().is_none() {
                    hook_clear.fetch_add(1, Ordering::SeqCst);
                }
                hook_count.fetch_add(1, Ordering::SeqCst);
            }),
        });
        *cell.lock().unwrap() = Some(Arc::clone(&l));
        assert!(matches!(open_gated(&l, &[]), Launch::Started(_)));
        for _ in 0..100 {
            if notices.load(Ordering::SeqCst) == 1 {
                break;
            }
            thread::sleep(Duration::from_millis(50));
        }
        assert_eq!(notices.load(Ordering::SeqCst), 1);
        assert_eq!(
            slot_was_clear.load(Ordering::SeqCst),
            1,
            "the slot was still set during the notice"
        );
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn window_bin_sits_next_to_the_tray() {
        let p = window_bin(None, Some(Path::new("/home/u/.local/bin/aristotle-tray")));
        assert_eq!(
            p,
            Some(PathBuf::from("/home/u/.local/bin/aristotle-desktop"))
        );
    }

    #[test]
    fn window_bin_absolute_override_wins() {
        let exe = Some(Path::new("/a/b/aristotle-tray"));
        assert_eq!(
            window_bin(Some(Path::new("/x/y")), exe),
            Some(PathBuf::from("/x/y"))
        );
    }

    #[test]
    fn window_bin_relative_override_is_ignored() {
        let exe = Some(Path::new("/a/b/aristotle-tray"));
        assert_eq!(
            window_bin(Some(Path::new("aristotle-desktop")), exe),
            Some(PathBuf::from("/a/b/aristotle-desktop"))
        );
    }

    #[test]
    fn window_bin_without_a_usable_exe_path_is_none() {
        assert_eq!(window_bin(None, None), None);
        assert_eq!(window_bin(None, Some(Path::new("aristotle-tray"))), None);
        assert_eq!(
            window_bin(Some(Path::new("rel")), Some(Path::new(""))),
            None
        );
    }

    #[test]
    fn no_path_is_reported_as_missing() {
        assert_eq!(open_gated(&launcher(None), &[]), Launch::Missing);
    }

    #[test]
    fn missing_binary_is_reported_not_spawned() {
        let l = launcher(Some("/nonexistent/aristotle-desktop"));
        assert_eq!(open_gated(&l, &[]), Launch::Missing);
    }

    #[test]
    fn second_open_is_deduplicated_while_the_child_runs() {
        let l = launcher(Some("/bin/sleep"));
        // `sleep` with no args exits at once on its own: keep one alive by pre-filling the slot.
        *l.running.lock().unwrap() = Some(4242);
        assert_eq!(open_gated(&l, &[]), Launch::AlreadyRunning(4242));
    }
}
