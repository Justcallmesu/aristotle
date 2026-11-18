//! Command-line arguments to a [`StartIntent`] (P14, D19-6).

use serde::Serialize;

/// Which view the window opens on, and what it does at once.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "kebab-case")]
pub enum StartIntent {
    /// No arguments: the Status view.
    Status,
    /// `--pair`: the Pair view, and a code is issued at once.
    Pair,
    /// `--confirm stop`: the Service view with the stop dialog open.
    ConfirmStop,
}

/// `argv` includes the program name. The first recognised argument wins; unknown ones are ignored.
pub fn parse_args<S: AsRef<str>>(argv: &[S]) -> StartIntent {
    let mut args = argv.iter().skip(1).map(AsRef::as_ref);
    while let Some(arg) = args.next() {
        match arg {
            "--pair" => return StartIntent::Pair,
            "--confirm" if args.next() == Some("stop") => return StartIntent::ConfirmStop,
            "--confirm=stop" => return StartIntent::ConfirmStop,
            _ => {}
        }
    }
    StartIntent::Status
}

/// The intent for a window that cannot listen yet, until its frontend has read it.
///
/// A second launch while the first window is still loading would otherwise emit an event nobody
/// receives: [`PendingIntent::hand_off`] queues it until the frontend has called `start_intent`.
pub struct PendingIntent(std::sync::Mutex<Slot>);

struct Slot {
    queued: Option<StartIntent>,
    /// The frontend has read its intent, so it listens for the event from now on.
    window_ready: bool,
}

impl PendingIntent {
    pub fn new(first: StartIntent) -> Self {
        Self(std::sync::Mutex::new(Slot {
            queued: Some(first),
            window_ready: false,
        }))
    }

    fn slot(&self) -> std::sync::MutexGuard<'_, Slot> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// The `start_intent` command: the queued intent (the newest hand-off wins), else Status. After
    /// this the window listens for the `start-intent` event.
    pub fn take(&self) -> StartIntent {
        let mut slot = self.slot();
        slot.window_ready = true;
        slot.queued.take().unwrap_or(StartIntent::Status)
    }

    /// A second launch's intent. `Some(intent)` = the window listens, emit it now; `None` = queued
    /// for the window's first `start_intent` read.
    #[must_use]
    pub fn hand_off(&self, intent: StartIntent) -> Option<StartIntent> {
        let mut slot = self.slot();
        if slot.window_ready {
            return Some(intent);
        }
        slot.queued = Some(intent);
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_hand_off_before_the_window_reads_its_intent_is_queued_not_emitted() {
        let pending = PendingIntent::new(StartIntent::Status);
        assert_eq!(pending.hand_off(StartIntent::Pair), None);
        assert_eq!(pending.take(), StartIntent::Pair, "the newest intent wins");
    }

    #[test]
    fn a_hand_off_after_the_window_read_its_intent_is_emitted_and_not_kept() {
        let pending = PendingIntent::new(StartIntent::ConfirmStop);
        assert_eq!(pending.take(), StartIntent::ConfirmStop);
        assert_eq!(pending.hand_off(StartIntent::Pair), Some(StartIntent::Pair));
        assert_eq!(
            pending.take(),
            StartIntent::Status,
            "nothing replays on a reload"
        );
    }

    #[test]
    fn no_arguments_open_the_status_view() {
        assert_eq!(parse_args(&["aristotle-desktop"]), StartIntent::Status);
        assert_eq!(parse_args::<&str>(&[]), StartIntent::Status);
    }

    #[test]
    fn pair_opens_the_pair_view() {
        assert_eq!(
            parse_args(&["aristotle-desktop", "--pair"]),
            StartIntent::Pair
        );
    }

    #[test]
    fn confirm_stop_opens_the_service_dialog() {
        assert_eq!(
            parse_args(&["aristotle-desktop", "--confirm", "stop"]),
            StartIntent::ConfirmStop
        );
        assert_eq!(
            parse_args(&["aristotle-desktop", "--confirm=stop"]),
            StartIntent::ConfirmStop
        );
    }

    #[test]
    fn confirm_with_another_word_or_nothing_is_ignored() {
        assert_eq!(
            parse_args(&["aristotle-desktop", "--confirm", "restart"]),
            StartIntent::Status
        );
        assert_eq!(
            parse_args(&["aristotle-desktop", "--confirm"]),
            StartIntent::Status
        );
    }

    #[test]
    fn unknown_arguments_are_ignored_and_the_known_one_still_counts() {
        assert_eq!(
            parse_args(&["aristotle-desktop", "--hidden", "--bogus", "--pair", "x"]),
            StartIntent::Pair
        );
        assert_eq!(
            parse_args(&["aristotle-desktop", "stop", "--confirm"]),
            StartIntent::Status
        );
    }

    #[test]
    fn serialises_as_a_kebab_tag() {
        let json = serde_json::to_string(&StartIntent::ConfirmStop).unwrap();
        assert_eq!(json, r#"{"kind":"confirm-stop"}"#);
    }
}
