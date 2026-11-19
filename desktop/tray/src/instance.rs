//! Single instance (P26 (1)): an exclusive `flock` on `$XDG_RUNTIME_DIR/aristotle-desktop/tray.lock`, held for the
//! whole life of the tray. The kernel releases it when the process ends, however it ends, so a crash never leaves a
//! stale lock. A second tray finds the lock taken and exits 0 without registering anything. That makes every
//! duplicate-tray path harmless: a symlinked home, an installer-started tray that survives logout while autostart
//! starts another at login, a manual start.

use aristotle_core::runner::non_empty_env;
use std::fs::{self, File, OpenOptions};
use std::io;
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};

extern "C" {
    fn flock(fd: i32, operation: i32) -> i32;
}
const LOCK_EX: i32 = 2;
const LOCK_NB: i32 = 4;
const LOCK_UN: i32 = 8;

/// The held lock. Dropping it calls `flock(LOCK_UN)` on the open file description (P27 (2)): closing the file alone
/// would keep the lock while a forked child that has not exec'd yet (the file is O_CLOEXEC) holds a duplicate.
#[derive(Debug)]
pub struct Lock(File);

impl Drop for Lock {
    fn drop(&mut self) {
        // SAFETY: a valid open descriptor and plain integers.
        unsafe { flock(self.0.as_raw_fd(), LOCK_UN) };
    }
}

/// What `acquire` found.
#[derive(Debug)]
pub enum Instance {
    /// This process holds the lock; keep the value alive until the process exits.
    First(Lock),
    /// Another tray holds it.
    Second,
}

/// The lock file under `runtime_dir`.
pub fn lock_path(runtime_dir: &Path) -> PathBuf {
    runtime_dir.join("aristotle-desktop").join("tray.lock")
}

/// The lock file under `$XDG_RUNTIME_DIR`; `None` when it is unset or empty.
pub fn lock_path_from_env() -> Option<PathBuf> {
    non_empty_env("XDG_RUNTIME_DIR").map(|dir| lock_path(Path::new(&dir)))
}

/// Takes the exclusive lock at `path` without waiting. `Err` only for a real I/O problem (no directory, no permission).
pub fn acquire(path: &Path) -> io::Result<Instance> {
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(false)
        .open(path)?;
    // SAFETY: a valid open descriptor and plain integers.
    if unsafe { flock(file.as_raw_fd(), LOCK_EX | LOCK_NB) } == 0 {
        return Ok(Instance::First(Lock(file)));
    }
    let err = io::Error::last_os_error();
    if err.kind() == io::ErrorKind::WouldBlock {
        Ok(Instance::Second)
    } else {
        Err(err)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;
    use std::time::Duration;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "aristotle-instance-unit-{name}-{}",
            std::process::id()
        ));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn the_lock_lives_under_the_runtime_dir() {
        assert_eq!(
            lock_path(Path::new("/run/user/1000")),
            PathBuf::from("/run/user/1000/aristotle-desktop/tray.lock")
        );
    }

    #[test]
    fn the_first_takes_it_the_second_is_refused_and_a_released_lock_is_free_again() {
        let dir = temp_dir("cycle");
        let path = lock_path(&dir);
        let first = acquire(&path).unwrap();
        assert!(matches!(first, Instance::First(_)));
        assert!(matches!(acquire(&path).unwrap(), Instance::Second));
        drop(first);
        assert!(matches!(acquire(&path).unwrap(), Instance::First(_)));
        let _ = fs::remove_dir_all(dir);
    }

    /// P27 (2): a child between fork and exec holds a duplicate of the descriptor; the lock must still be freed.
    #[test]
    fn dropping_the_lock_frees_it_even_while_a_forked_child_holds_a_copy() {
        use std::os::unix::process::CommandExt;
        let dir = temp_dir("forked");
        let path = lock_path(&dir);
        let first = acquire(&path).unwrap();
        let child = thread::spawn(|| {
            let _gate = crate::testing::spawn_gate();
            let mut cmd = std::process::Command::new("true");
            // SAFETY: the hook only sleeps; it keeps the child between fork and exec.
            unsafe {
                cmd.pre_exec(|| {
                    thread::sleep(Duration::from_millis(600));
                    Ok(())
                });
            }
            cmd.status().unwrap()
        });
        thread::sleep(Duration::from_millis(200));
        drop(first);
        let again = acquire(&path).unwrap();
        assert!(
            matches!(again, Instance::First(_)),
            "the lock stayed held by the forked child"
        );
        drop(again);
        child.join().unwrap();
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_unusable_directory_is_an_error_not_a_second_instance() {
        let dir = temp_dir("blocked");
        fs::create_dir_all(&dir).unwrap();
        let blocker = dir.join("aristotle-desktop");
        fs::write(&blocker, "a file where the directory should be").unwrap();
        assert!(acquire(&lock_path(&dir)).is_err());
        let _ = fs::remove_dir_all(dir);
    }
}
