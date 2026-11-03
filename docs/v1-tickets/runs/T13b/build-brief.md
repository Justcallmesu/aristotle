# T13b build brief — composer/prompt minors (T13 + T12b reviews)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T13/review-report.md`,
`docs/v1-tickets/runs/T12b/review-report.md`, board row D38, then the files they cite (committed in HEAD).

## Fix (each with a test that fails before the fix)
1. T13 MINOR-2: after a 4409 close, keep the existing immediate triggers (network change, foreground, Retry) AND add a slow backstop retry
   (60 s, then normal backoff cap) so a backgrounded phone on a stable network does not stay offline and miss notifications.
2. T13 MINOR-1: the D35 "running turn's prompt" lookup skips steer rows when finding the last user row (no duplicate bubble).
3. T13 MINOR-3: a failed steer offers Retry only on Hermes' refusal code 4001; any other error -> not retryable (✕ restores the text).
4. T13 NITs: dismissing a "no answer" submit keeps its `submission_id` for a re-send of the same text; drafts of sessions no longer in the
   list are pruned on the next session.list; EN string "laptop" -> "PC"; Stop with unknown generation shows a short "Can't stop yet" note.
5. D38 (T12b): in the Always-allow confirm step Deny/Allow once are hidden (only Confirm / Back); the arm timer restarts when the activity
   resumes (prompt sheet and composer); ignore an OPEN_SESSION intent with FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY; right after a reconnect a
   sudo/secret prompt is not answerable until the new snapshot confirms it is still open.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T13b/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on
the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T13b` exits 0.

## Report
`docs/v1-tickets/runs/T13b/build-report.md`, ≤20 lines.
