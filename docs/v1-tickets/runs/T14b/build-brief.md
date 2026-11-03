# T14b build brief — sessions screen minors (T14 review)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T14/review-report.md` (all MINORs + NITs),
then the cited files (committed in HEAD).

## Fix (each with a test that fails before the fix)
1. A failed stored-history call must not hide the live list: show live sessions and an inline "Couldn't load history · Retry" row.
2. Traffic: on `sessions.changed` refetch only the live list, debounced (coalesce bursts, at most one refetch per 2 s); the stored list
   refreshes on screen open, pull-to-refresh (add it if missing) and load-more only.
3. A remembered profile that the PC no longer serves falls back to the launch profile (and says so once in a snackbar), so the background
   watcher keeps watching and notifications keep arriving.
4. The review's NITs that sit in the files you touch.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/T14b/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not install on
the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T14b` exits 0.

## Report
`docs/v1-tickets/runs/T14b/build-report.md`, ≤20 lines. Say which tests you saw fail before the fix (keep a copy of the old file in your
scratch dir to run them against; never use git to revert).
