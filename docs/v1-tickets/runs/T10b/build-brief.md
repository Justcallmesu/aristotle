# T10b build brief — SessionWatcher fix round (T10 review)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T10/review-report.md` (all findings),
`docs/protocol.md` §2 (replay_gap line + the T10 background note), then `android/.../data/{SessionWatcher,AlertRouter,LinkManager}.kt`
and their tests (uncommitted T10 working tree — build on it).

## Fix (each with a test)
1. **MAJOR**: on `session.replay_gap` for a watched session, remove it from the resumed set and resume it again on the same socket
   (snapshot rebuild; open prompts re-read from the snapshot). Correct the §2 note ("stays resumed until a replay_gap or the socket ends").
2. Repeated alerts on reconnect: remember posted prompt ids (in memory, across reconnects within the process); re-posting an already-shown,
   still-open prompt is silent (no heads-up, `setOnlyAlertOnce`) and never restores a notification the user dismissed (track dismiss via
   `deleteIntent`). After a resume, cancel notifications for prompts that are no longer open in the snapshot.
3. Approval text: "Wants to run a command" only for command approvals; a neutral "Wants your approval" otherwise (EN/ID).
4. Add to the §2 note: automatic resume by the phone can cancel a pending close of a session that a TUI/CLI left mid-turn (narrow case;
   Desktop unaffected).

## Hard rules
Files: `android/**`, `docs/protocol.md` §2 note, `docs/v1-tickets/runs/T10b/build-report.md`. No commits; never git checkout/restore/
stash/reset. Phone not attached.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T10b` exits 0.

## Report
`docs/v1-tickets/runs/T10b/build-report.md`, ≤20 lines.
