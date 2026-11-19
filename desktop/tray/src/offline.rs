//! How long the panel's StatusNotifierWatcher has been gone (P27 (1)). A tray that outlived its login (started by the
//! installer, linger on) would otherwise sit on the single-instance lock with a dead display. The ksni callbacks only
//! feed this; the poll loop asks it, with the clock passed in, so the timing is plain unit-tested logic.

use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Offline for this long and the tray exits. A plasmashell restart takes seconds and stays under it.
pub const OFFLINE_LIMIT: Duration = Duration::from_secs(30);

#[derive(Debug, Default)]
pub struct OfflineWatch {
    since: Option<Instant>,
}

impl OfflineWatch {
    /// The watcher went away at `now`. A repeated call keeps the first time.
    pub fn went_offline(&mut self, now: Instant) {
        self.since.get_or_insert(now);
    }

    /// The watcher is back: the timer is cancelled.
    pub fn came_online(&mut self) {
        self.since = None;
    }

    /// True once the watcher has been offline for `limit` or more at `now`.
    pub fn expired(&self, now: Instant, limit: Duration) -> bool {
        self.since
            .is_some_and(|since| now.saturating_duration_since(since) >= limit)
    }
}

/// The watch shared between the ksni callbacks and the poll loop.
pub type SharedOfflineWatch = Arc<Mutex<OfflineWatch>>;

/// Locks the watch; a poisoned lock still holds a usable value.
pub fn lock(watch: &SharedOfflineWatch) -> std::sync::MutexGuard<'_, OfflineWatch> {
    watch.lock().unwrap_or_else(|e| e.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIMIT: Duration = Duration::from_secs(30);

    #[test]
    fn a_watch_that_never_went_offline_never_expires() {
        let watch = OfflineWatch::default();
        assert!(!watch.expired(Instant::now() + Duration::from_secs(3600), LIMIT));
    }

    #[test]
    fn it_expires_at_the_limit_and_not_before() {
        let t0 = Instant::now();
        let mut watch = OfflineWatch::default();
        watch.went_offline(t0);
        assert!(!watch.expired(t0 + Duration::from_secs(29), LIMIT));
        assert!(watch.expired(t0 + Duration::from_secs(30), LIMIT));
        assert!(watch.expired(t0 + Duration::from_secs(300), LIMIT));
    }

    #[test]
    fn coming_back_online_within_the_limit_cancels_it() {
        let t0 = Instant::now();
        let mut watch = OfflineWatch::default();
        watch.went_offline(t0);
        watch.came_online();
        assert!(!watch.expired(t0 + Duration::from_secs(300), LIMIT));
    }

    #[test]
    fn a_second_offline_after_coming_back_starts_a_new_period() {
        let t0 = Instant::now();
        let mut watch = OfflineWatch::default();
        watch.went_offline(t0);
        watch.came_online();
        watch.went_offline(t0 + Duration::from_secs(25));
        assert!(!watch.expired(t0 + Duration::from_secs(40), LIMIT));
        assert!(watch.expired(t0 + Duration::from_secs(55), LIMIT));
    }

    #[test]
    fn a_repeated_offline_report_keeps_the_first_time() {
        let t0 = Instant::now();
        let mut watch = OfflineWatch::default();
        watch.went_offline(t0);
        watch.went_offline(t0 + Duration::from_secs(20));
        assert!(watch.expired(t0 + Duration::from_secs(30), LIMIT));
    }

    #[test]
    fn a_clock_before_the_start_does_not_expire() {
        let t0 = Instant::now() + Duration::from_secs(100);
        let mut watch = OfflineWatch::default();
        watch.went_offline(t0);
        assert!(!watch.expired(t0 - Duration::from_secs(50), LIMIT));
    }
}
