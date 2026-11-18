//! `pair [--name N] --json` (protocol section 22).
//!
//! The pairing code and QR payload (P11) go straight back to the caller: nothing here stores, logs or
//! formats them; `Pair`'s `Debug` already hides them.

use crate::backend::{plain_arg, Backend};
use crate::dto::IpcResult;
use aristotle_core::runner::query;
use aristotle_core::types::Pair;

pub fn issue_code(backend: &Backend, name: Option<&str>) -> IpcResult<Pair> {
    let name = name.map(str::trim).filter(|n| !n.is_empty());
    let name = name.map(plain_arg).transpose()?;
    backend.guard_action()?;
    let mut args = vec!["pair"];
    if let Some(name) = name {
        args.extend(["--name", name]);
    }
    Ok(query::<Pair>(backend.cli(), &args)?.value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::*;

    fn pair_rig() -> (std::sync::Arc<FakeCli>, Backend) {
        let mut replies = running_poll();
        replies.push(json("pair.json"));
        let cli = FakeCli::new(replies);
        let backend = backend(&cli, "1.2.3");
        (cli, backend)
    }

    #[test]
    fn the_ipc_reply_carries_the_qr_modules() {
        let (_cli, backend) = pair_rig();
        let pair = issue_code(&backend, None).unwrap();
        let reply = serde_json::to_value(&pair).unwrap();
        let rows = reply["qr_modules"].as_array().expect("qr_modules array");
        assert!(rows.len() >= 21 && rows.iter().all(|r| r.as_str().unwrap().len() == rows.len()));
    }

    #[test]
    fn pair_without_a_name_runs_plain_pair() {
        let (cli, backend) = pair_rig();
        let pair = issue_code(&backend, None).unwrap();
        assert!(pair.qr_payload.starts_with("aristotle://pair"));
        assert_eq!(cli.calls().last().unwrap(), "pair --json");
    }

    #[test]
    fn pair_passes_the_name_as_one_token() {
        let (cli, backend) = pair_rig();
        issue_code(&backend, Some("  pixel 8; rm -rf  ")).unwrap();
        let last = cli.calls().pop().unwrap();
        assert_eq!(last, "pair --name pixel 8; rm -rf --json");
    }

    #[test]
    fn a_name_that_looks_like_a_flag_never_reaches_the_cli() {
        let (cli, backend) = pair_rig();
        let err = issue_code(&backend, Some("--json")).unwrap_err();
        assert_eq!(err.kind, "invalid-argument");
        assert!(cli.calls().is_empty());
    }

    #[test]
    fn the_result_does_not_print_the_code() {
        let (_cli, backend) = pair_rig();
        let pair = issue_code(&backend, None).unwrap();
        let shown = format!("{pair:?}");
        assert!(!shown.contains("K7Q2M9XD"));
    }
}
