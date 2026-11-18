//! Pure description of the tray menu (P14, P17), so the shape is testable without a bus.

use aristotle_core::snapshot::{Snapshot, VersionCheck};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Entry {
    Open,
    Pair { enabled: bool },
    Start,
    Stop,
    Quit,
}

/// The `service` word, as far as the menu cares.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Service {
    Active,
    /// `inactive` or `failed`: the unit can be started.
    Down,
    /// `activating`, `unknown`, ...
    Other,
    /// No status answered (CLI missing or a failed poll): the service state is not known.
    NoStatus,
}

/// What the menu depends on. Compared between polls to see whether a redraw is needed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MenuInput {
    pub server_running: bool,
    pub installed: bool,
    pub mismatch: bool,
    pub service: Service,
}

impl MenuInput {
    pub fn from_snapshot(snapshot: &Snapshot) -> Self {
        let service = match snapshot.answered() {
            None => Service::NoStatus,
            Some(s) => match s.service.as_str() {
                "active" => Service::Active,
                "inactive" | "failed" => Service::Down,
                _ => Service::Other,
            },
        };
        Self {
            server_running: snapshot.server_running(),
            installed: snapshot.installed(),
            mismatch: matches!(snapshot.version, VersionCheck::Mismatch { .. }),
            service,
        }
    }

    pub fn none() -> Self {
        Self {
            server_running: false,
            installed: false,
            mismatch: false,
            service: Service::NoStatus,
        }
    }
}

/// Start or Stop… follow the service state (design.md 4.1): active -> Stop…; inactive/failed -> Start.
/// Any other word (`activating`, `unknown`, …), a mismatch, a missing unit or an unknown service show neither.
/// Exception: `server == "no-answer"` with `service == "active"` -> Stop… (recovery, handled by MenuInput).
fn lifecycle(input: &MenuInput) -> Option<Entry> {
    if input.mismatch || !input.installed {
        return None;
    }
    match input.service {
        Service::Active => Some(Entry::Stop),
        Service::Down => Some(Entry::Start),
        Service::Other | Service::NoStatus => None,
    }
}

/// Open, Pair (needs a running server and no mismatch), Start or Stop…, Quit.
pub fn plan(input: &MenuInput) -> Vec<Entry> {
    let mut entries = vec![
        Entry::Open,
        Entry::Pair {
            enabled: input.server_running && !input.mismatch,
        },
    ];
    entries.extend(lifecycle(input));
    entries.push(Entry::Quit);
    entries
}

#[cfg(test)]
mod tests {
    use super::*;

    fn input(service: Service) -> MenuInput {
        MenuInput {
            server_running: true,
            installed: true,
            mismatch: false,
            service,
        }
    }

    fn lifecycle_of(input: &MenuInput) -> Vec<Entry> {
        plan(input)
            .into_iter()
            .filter(|e| matches!(e, Entry::Start | Entry::Stop))
            .collect()
    }

    #[test]
    fn active_service_shows_stop_and_enables_pair() {
        assert_eq!(
            plan(&input(Service::Active)),
            [
                Entry::Open,
                Entry::Pair { enabled: true },
                Entry::Stop,
                Entry::Quit
            ]
        );
    }

    #[test]
    fn stopped_service_shows_start_and_disables_pair() {
        let stopped = MenuInput {
            server_running: false,
            ..input(Service::Down)
        };
        assert_eq!(
            plan(&stopped),
            [
                Entry::Open,
                Entry::Pair { enabled: false },
                Entry::Start,
                Entry::Quit
            ]
        );
    }

    #[test]
    fn no_answer_with_active_service_keeps_stop_for_recovery() {
        let no_answer = MenuInput {
            server_running: false,
            ..input(Service::Active)
        };
        assert_eq!(lifecycle_of(&no_answer), [Entry::Stop]);
    }

    #[test]
    fn hand_started_server_with_inactive_service_shows_start_not_stop() {
        // server answers (hand-started), the unit is inactive: the menu follows the service.
        assert_eq!(lifecycle_of(&input(Service::Down)), [Entry::Start]);
    }

    #[test]
    fn other_service_words_show_neither() {
        assert_eq!(lifecycle_of(&input(Service::Other)), []);
    }

    #[test]
    fn version_mismatch_shows_neither_and_keeps_open() {
        for service in [Service::Active, Service::Down, Service::Other] {
            let mismatch = MenuInput {
                mismatch: true,
                ..input(service)
            };
            assert!(lifecycle_of(&mismatch).is_empty(), "{service:?}");
            assert_eq!(plan(&mismatch)[0], Entry::Open);
            assert!(plan(&mismatch).contains(&Entry::Pair { enabled: false }));
        }
    }

    #[test]
    fn not_installed_shows_neither() {
        let missing = MenuInput {
            installed: false,
            server_running: false,
            ..input(Service::Down)
        };
        assert!(lifecycle_of(&missing).is_empty());
        assert_eq!(plan(&missing)[0], Entry::Open);
    }

    #[test]
    fn unknown_service_state_shows_neither() {
        assert!(lifecycle_of(&MenuInput::none()).is_empty());
    }
}
