//! The tray's poll loop body: snapshot, diff against the last good snapshot, stamp handling.

use crate::diff::{diff, server_stopped, stamp_in_window, Notice};
use crate::runner::ServerCli;
use crate::snapshot::{Snapshot, Snapshotter};
use crate::stamp::ExpectedStop;
use std::time::SystemTime;

pub struct Poller {
    snapshotter: Snapshotter,
    stamp: Option<ExpectedStop>,
    /// The last poll where `status` answered. A failed poll never replaces it (D5).
    last_good: Option<Snapshot>,
}

pub struct Tick {
    pub snapshot: Snapshot,
    pub notices: Vec<Notice>,
}

impl Poller {
    pub fn new(desktop_version: Option<&str>, stamp: Option<ExpectedStop>) -> Self {
        Self {
            snapshotter: Snapshotter::new(desktop_version),
            stamp,
            last_good: None,
        }
    }

    pub fn tick(&mut self, cli: &dyn ServerCli, now: SystemTime) -> Tick {
        let snapshot = self.snapshotter.poll(cli);
        let expected = self.stamp.as_ref().and_then(ExpectedStop::read);
        let notices = diff(self.last_good.as_ref(), &snapshot, expected, now);
        // Single-use: the stop it announced has happened. Outside the window it is a leftover (P17).
        let stale = expected.is_some_and(|at| !stamp_in_window(at, now));
        if stale || self.saw_stop(&snapshot) {
            if let Some(stamp) = &self.stamp {
                let _ = stamp.delete();
            }
        }
        if snapshot.answered().is_some() {
            self.last_good = Some(self.baseline_for(&snapshot));
        }
        Tick { snapshot, notices }
    }

    /// The snapshot to diff against next time. A failed `devices list` / `terminal status` read keeps the
    /// previous list, so a change during the failed poll still notifies once.
    fn baseline_for(&self, snapshot: &Snapshot) -> Snapshot {
        let mut base = snapshot.clone();
        if let (true, Some(prev)) = (snapshot.server_running(), &self.last_good) {
            base.devices = base.devices.or_else(|| prev.devices.clone());
            base.terminals = base.terminals.or_else(|| prev.terminals.clone());
            if base.terminal_enabled.is_none() {
                base.terminal_enabled = prev.terminal_enabled;
                base.terminal_granted = prev.terminal_granted.clone();
            }
        }
        base
    }

    fn saw_stop(&self, next: &Snapshot) -> bool {
        self.last_good
            .as_ref()
            .is_some_and(|prev| server_stopped(prev, next))
    }
}
