//! One `start` at a time (design.md 4.4: a running command turns its buttons off). The tray's Start
//! entry is disabled, and a second activation refused, while the token of the first is alive.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

#[derive(Debug, Default)]
pub struct Starting(Arc<AtomicBool>);

/// Held by the thread that runs `start`; dropping it frees the slot.
#[derive(Debug)]
pub struct StartToken(Arc<AtomicBool>);

impl Starting {
    /// `None` while another start is running.
    pub fn try_begin(&self) -> Option<StartToken> {
        let was_busy = self.0.swap(true, Ordering::SeqCst);
        (!was_busy).then(|| StartToken(Arc::clone(&self.0)))
    }

    pub fn active(&self) -> bool {
        self.0.load(Ordering::SeqCst)
    }
}

impl Drop for StartToken {
    fn drop(&mut self) {
        self.0.store(false, Ordering::SeqCst);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_second_start_is_refused_until_the_first_ends() {
        let starting = Starting::default();
        assert!(!starting.active());
        let first = starting.try_begin().expect("first start");
        assert!(starting.active());
        assert!(
            starting.try_begin().is_none(),
            "second start while the first runs"
        );
        drop(first);
        assert!(!starting.active());
        assert!(starting.try_begin().is_some(), "free again");
    }
}
