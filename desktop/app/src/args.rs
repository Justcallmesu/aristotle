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

#[cfg(test)]
mod tests {
    use super::*;

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
