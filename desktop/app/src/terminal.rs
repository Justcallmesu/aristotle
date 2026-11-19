//! `terminal status|enable|disable --json` and `devices grant-terminal|ungrant-terminal <id> --json`.

use crate::backend::{plain_arg, Backend};
use crate::dto::IpcResult;
use aristotle_core::runner::{query, query_read};
use aristotle_core::types::TerminalStatus;

/// A read: not guarded, it also serves "Check again" while the versions differ.
pub fn status(backend: &Backend) -> IpcResult<TerminalStatus> {
    Ok(query_read::<TerminalStatus>(backend.cli(), &["terminal", "status"])?.value)
}

pub fn enable(backend: &Backend) -> IpcResult<TerminalStatus> {
    change(backend, &["terminal", "enable"])
}

pub fn disable(backend: &Backend) -> IpcResult<TerminalStatus> {
    change(backend, &["terminal", "disable"])
}

pub fn grant(backend: &Backend, id: &str) -> IpcResult<TerminalStatus> {
    change(backend, &["devices", "grant-terminal", plain_arg(id)?])
}

pub fn ungrant(backend: &Backend, id: &str) -> IpcResult<TerminalStatus> {
    change(backend, &["devices", "ungrant-terminal", plain_arg(id)?])
}

fn change(backend: &Backend, args: &[&str]) -> IpcResult<TerminalStatus> {
    backend.guard_action()?;
    Ok(query::<TerminalStatus>(backend.cli(), args)?.value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::*;

    fn run(f: impl Fn(&Backend) -> IpcResult<TerminalStatus>, fixture_name: &str) -> String {
        let mut replies = running_poll();
        replies.push(json(fixture_name));
        let cli = FakeCli::new(replies);
        f(&backend(&cli, "1.2.3")).unwrap();
        cli.calls().pop().unwrap()
    }

    #[test]
    fn each_command_maps_to_its_cli_call() {
        assert_eq!(
            run(enable, "terminal_enable.json"),
            "terminal enable --json"
        );
        assert_eq!(
            run(disable, "terminal_disable.json"),
            "terminal disable --json"
        );
        assert_eq!(
            run(|b| grant(b, "dev-1"), "devices_grant_terminal.json"),
            "devices grant-terminal dev-1 --json"
        );
        assert_eq!(
            run(|b| ungrant(b, "dev-1"), "devices_ungrant_terminal.json"),
            "devices ungrant-terminal dev-1 --json"
        );
    }

    #[test]
    fn the_status_read_has_the_3_s_limit_and_a_timeout_is_an_error() {
        let cli = FakeCli::new(vec![Err(aristotle_core::runner::CliError::Timeout)]);
        let err = status(&backend(&cli, "1.2.3")).unwrap_err();
        assert_eq!(err.kind, "timeout");
        assert_eq!(cli.limits(), vec![std::time::Duration::from_secs(3)]);
    }

    #[test]
    fn writes_are_refused_while_mismatched_but_the_read_still_works() {
        let cli = FakeCli::new(running_poll());
        let b = backend(&cli, "9.9.9");
        assert_eq!(enable(&b).unwrap_err().kind, "version-mismatch");
        assert_eq!(grant(&b, "dev-1").unwrap_err().kind, "version-mismatch");
        let cli = FakeCli::new(vec![json("terminal_status.json")]);
        assert!(status(&backend(&cli, "9.9.9")).is_ok());
    }
}
