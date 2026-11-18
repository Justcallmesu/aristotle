//! Launches the window app (`aristotle-desktop`) from the tray. One tracked child (single instance on
//! the tray side); a reaper thread owns each `Child` so none stays a zombie.

use std::path::{Path, PathBuf};
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};
use std::thread;

/// Development override for the window app path.
pub const DESKTOP_BIN_ENV: &str = "ARISTOTLE_DESKTOP_BIN";
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
}

impl Launcher {
    pub fn from_env() -> Arc<Self> {
        let exe = std::env::current_exe().ok();
        let override_path = aristotle_core::runner::absolute_env_path(DESKTOP_BIN_ENV);
        Arc::new(Self {
            bin: window_bin(override_path.as_deref(), exe.as_deref()),
            running: Mutex::new(None),
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
        let _ = child.wait();
        if tracked {
            *self.running.lock().unwrap_or_else(|e| e.into_inner()) = None;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn launcher(bin: Option<&str>) -> Arc<Launcher> {
        Arc::new(Launcher {
            bin: bin.map(PathBuf::from),
            running: Mutex::new(None),
        })
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
        assert_eq!(launcher(None).open(&[], None), Launch::Missing);
    }

    #[test]
    fn missing_binary_is_reported_not_spawned() {
        let l = launcher(Some("/nonexistent/aristotle-desktop"));
        assert_eq!(l.open(&[], None), Launch::Missing);
    }

    #[test]
    fn second_open_is_deduplicated_while_the_child_runs() {
        let l = launcher(Some("/bin/sleep"));
        // `sleep` with no args exits at once on its own: keep one alive by pre-filling the slot.
        *l.running.lock().unwrap() = Some(4242);
        assert_eq!(l.open(&[], None), Launch::AlreadyRunning(4242));
    }
}
