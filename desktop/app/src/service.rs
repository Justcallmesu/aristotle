//! `start` / `stop` / `restart` and the P15 expected-stop stamp.

use crate::backend::Backend;
use crate::dto::IpcResult;
use aristotle_core::runner::{query, CliError, Reply as CliReply};
use aristotle_core::types::ActionResult;
use serde::Deserialize;
use std::time::SystemTime;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ServiceAction {
    Start,
    Stop,
    Restart,
}

impl ServiceAction {
    fn verb(self) -> &'static str {
        match self {
            ServiceAction::Start => "start",
            ServiceAction::Stop => "stop",
            ServiceAction::Restart => "restart",
        }
    }

    /// The tray treats a stop within 60 s of the stamp as expected (P15).
    fn stops_the_server(self) -> bool {
        matches!(self, ServiceAction::Stop | ServiceAction::Restart)
    }
}

/// A timeout is not a failure: the CLI client was killed, but the systemd stop job may still finish,
/// so the stamp stays (it expires after 60 s, P17). Everything else that went wrong is definite.
fn definitely_failed(outcome: &Result<CliReply<ActionResult>, CliError>) -> bool {
    match outcome {
        Ok(reply) => !reply.value.ok,
        Err(CliError::Timeout) => false,
        Err(_) => true,
    }
}

/// Writes the stamp before `stop` / `restart` and removes it again when the command definitely fails (DF6): a
/// stamp left behind would hide a real crash in the next 60 s.
pub fn run_service_action(
    backend: &Backend,
    action: ServiceAction,
    now: SystemTime,
) -> IpcResult<ActionResult> {
    backend.guard_action()?;
    let stamp = backend.stamp.as_ref().filter(|_| action.stops_the_server());
    if let Some(stamp) = stamp {
        // Without a stamp the tray would announce an unexpected stop; the action still goes ahead.
        let _ = stamp.write(now);
    }
    let outcome = query::<ActionResult>(backend.cli(), &[action.verb()]);
    if let (true, Some(stamp)) = (definitely_failed(&outcome), stamp) {
        let _ = stamp.delete();
    }
    Ok(outcome?.value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testing::*;
    use aristotle_core::stamp::ExpectedStop;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Arc;

    struct Rig {
        cli: Arc<FakeCli>,
        backend: Backend,
        stamp: ExpectedStop,
        stamp_seen_during_call: Arc<AtomicBool>,
        _dir: std::path::PathBuf,
    }

    fn rig(tag: &str, action_reply: Reply) -> Rig {
        let dir = std::env::temp_dir().join(format!("aristotle-app-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let stamp = ExpectedStop::in_runtime_dir(&dir);
        let mut replies = running_poll();
        replies.push(action_reply);
        let cli = FakeCli::new(replies);
        let seen = Arc::new(AtomicBool::new(false));
        let (seen2, stamp2) = (seen.clone(), stamp.clone());
        cli.on_call(move |args| {
            if args[0] == "stop" || args[0] == "restart" || args[0] == "start" {
                seen2.store(stamp2.path().exists(), Ordering::SeqCst);
            }
        });
        let backend = Backend::new(cli.clone(), None, Some(stamp.clone()), Some("1.2.3"));
        Rig {
            cli,
            backend,
            stamp,
            stamp_seen_during_call: seen,
            _dir: dir,
        }
    }

    #[test]
    fn stop_writes_the_stamp_before_the_command_runs() {
        let r = rig("stop-ok", json("stop.json"));
        run_service_action(&r.backend, ServiceAction::Stop, SystemTime::now()).unwrap();
        assert!(r.stamp_seen_during_call.load(Ordering::SeqCst));
        assert!(r.stamp.path().exists(), "kept for the tray to consume");
        assert_eq!(r.cli.calls().last().unwrap(), "stop --json");
    }

    #[test]
    fn restart_writes_the_stamp_too() {
        let r = rig("restart-ok", json("restart.json"));
        run_service_action(&r.backend, ServiceAction::Restart, SystemTime::now()).unwrap();
        assert!(r.stamp_seen_during_call.load(Ordering::SeqCst));
    }

    #[test]
    fn a_failed_stop_deletes_the_stamp() {
        let err_reply = reply(r#"{"error":"refused: inside a terminal"}"#, 1);
        let r = rig("stop-fail", err_reply);
        let err =
            run_service_action(&r.backend, ServiceAction::Stop, SystemTime::now()).unwrap_err();
        assert!(
            r.stamp_seen_during_call.load(Ordering::SeqCst),
            "written first"
        );
        assert!(!r.stamp.path().exists(), "deleted after the failure");
        assert_eq!(err.kind, "server-error");
        assert_eq!(err.message.as_deref(), Some("refused: inside a terminal"));
    }

    #[test]
    fn a_stop_that_times_out_keeps_the_stamp() {
        // The systemd stop job may still finish after the CLI client was killed (MINOR-3).
        let r = rig("restart-timeout", Err(CliError::Timeout));
        let err =
            run_service_action(&r.backend, ServiceAction::Restart, SystemTime::now()).unwrap_err();
        assert_eq!(err.kind, "timeout");
        assert!(r.stamp.path().exists(), "kept: the stop may still land");
    }

    #[test]
    fn a_stop_that_cannot_run_the_binary_deletes_the_stamp() {
        let r = rig("stop-missing", Err(CliError::NotInstalled));
        let err =
            run_service_action(&r.backend, ServiceAction::Stop, SystemTime::now()).unwrap_err();
        assert_eq!(err.kind, "not-installed");
        assert!(!r.stamp.path().exists());
    }

    #[test]
    fn a_stop_with_unreadable_output_deletes_the_stamp() {
        let r = rig("stop-garbage", reply("not json", 1));
        let err =
            run_service_action(&r.backend, ServiceAction::Stop, SystemTime::now()).unwrap_err();
        assert_eq!(err.kind, "bad-output");
        assert!(!r.stamp.path().exists());
    }

    #[test]
    fn a_stop_that_answers_not_ok_deletes_the_stamp() {
        let r = rig(
            "stop-not-ok",
            reply(r#"{"ok":false,"service":"active"}"#, 0),
        );
        let done = run_service_action(&r.backend, ServiceAction::Stop, SystemTime::now()).unwrap();
        assert!(!done.ok);
        assert!(!r.stamp.path().exists());
    }

    #[test]
    fn start_never_writes_a_stamp() {
        let r = rig("start", json("start.json"));
        let done = run_service_action(&r.backend, ServiceAction::Start, SystemTime::now()).unwrap();
        assert!(done.ok);
        assert!(!r.stamp_seen_during_call.load(Ordering::SeqCst));
        assert!(!r.stamp.path().exists());
    }

    #[test]
    fn a_mismatched_server_refuses_before_any_stamp_or_command() {
        let r = rig("mismatch", json("stop.json"));
        let backend = Backend::new(r.cli.clone(), None, Some(r.stamp.clone()), Some("9.9.9"));
        let err = run_service_action(&backend, ServiceAction::Stop, SystemTime::now()).unwrap_err();
        assert_eq!(err.kind, "version-mismatch");
        assert!(!r.stamp.path().exists());
        assert!(r.cli.calls().iter().all(|c| !c.starts_with("stop")));
    }
}
