//! `devices revoke <id> --json`.

use crate::backend::{plain_arg, Backend};
use crate::dto::IpcResult;
use aristotle_core::runner::query;
use aristotle_core::types::Device;

pub fn revoke(backend: &Backend, id: &str) -> IpcResult<Device> {
    let id = plain_arg(id)?;
    backend.guard_action()?;
    Ok(query::<Device>(backend.cli(), &["devices", "revoke", id])?.value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::*;

    #[test]
    fn revoke_runs_the_cli_with_the_id() {
        let mut replies = running_poll();
        replies.push(json("devices_revoke.json"));
        let cli = FakeCli::new(replies);
        revoke(&backend(&cli, "1.2.3"), "dev-1").unwrap();
        assert_eq!(cli.calls().last().unwrap(), "devices revoke dev-1 --json");
    }

    #[test]
    fn revoke_is_refused_while_mismatched() {
        let cli = FakeCli::new(running_poll());
        let err = revoke(&backend(&cli, "9.9.9"), "dev-1").unwrap_err();
        assert_eq!(err.kind, "version-mismatch");
    }

    #[test]
    fn an_id_that_looks_like_a_flag_is_refused() {
        let cli = FakeCli::new(vec![]);
        let err = revoke(&backend(&cli, "1.2.3"), "--all").unwrap_err();
        assert_eq!(err.kind, "invalid-argument");
    }
}
