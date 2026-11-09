# UP2c — updater: stale install statuses (UP2b re-review)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `733573f`; UP1 + UP2 + UP2b are uncommitted in the tree (your base). Read
`PROJECT.md`, your input `docs/release/runs/UP2b/review-report.md` fully, `runs/UP2b/build-report.md`, and the reviewer's probe in
`/home/justcallmesu/.hermes/profiles/pm/cache/scratch/up2b/` (read-only: copy its failing test idea into the repo's tests).

## Do
1. **MAJOR:** the current install's session id is kept (memory + the store); `UpdateInstallReceiver` drops any status whose
   `EXTRA_SESSION_ID` differs (log one line); `giveUp` abandons the session it gives up on (its own late status is then dropped by rule 1).
   Tests first: a stale "aborted" status arriving while a new install is Installing must not fail it; the reviewer's probe scenario
   (give-up, Try again, pending-user-action) ends in Confirming with one confirm launch.
2. **MINOR:** the on-connect check time is recorded only after the PC answered (200 or 204); a failed or cut-off check does not block the
   next on-connect check. Test.
3. NITs: fix the "from the second self-update on" comment (the Android source lets the first self-update qualify); do not store a late
   pending-user-action status after a give-up.

## Hard rules
Files: `android/**`, `docs/release/runs/UP2c/build-report.md`. No new libraries. No adb. Never read `~/.config/aristotle`. No commits; never `git
checkout/restore/stash/reset`. RED first (show the stale-status test failing before the fix). Avoid `bash -c`/heredocs/chained `$(...)`.
Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/release/runs/UP2c` exits 0.

## Report
`docs/release/runs/UP2c/build-report.md`, ≤ 10 lines.
