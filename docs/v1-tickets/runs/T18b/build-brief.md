# T18b build brief — cron minors (T18 review)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T18/review-report.md`, then the cited files
under `android/.../data/cron/` and `ui/cron/` (committed in HEAD).

## Fix (each with a test that fails before the fix; keep a scratch copy of the old file, never use git to revert)
1. MINOR-1: the `cron.changed` refetch pauses while the Scheduled page is in the background (same ON_STOP/ON_START pattern as T17b's
   ChatViewModel), and ON_START refetches immediately.
2. MINOR-2: if a `cron.manage` result lacks `scoped: true` for a non-launch profile, show a banner "This PC's Hermes can't manage jobs for
   this profile from the phone" and disable the controls (never act on the wrong profile).
3. MINOR-3: delegated `[bot:x]` routines keep their switch disabled with Bot Mode's reason (cite the Desktop's rule).
4. NITs: timezone note says "Hermes' timezone" (show it if the result carries it); month-day input 1-31 only; Create's 600 ms arm restarts
   when a refusal message moves the button.

## Hard rules
Files: ONLY `android/app/src/main/java/io/github/justcallmesu/aristotle/{data/cron,ui/cron}/**`, their tests, the two `strings.xml`
files (append new keys at the end only), and `docs/v1-tickets/runs/T18b/build-report.md`. A T20 worker edits `res/drawable`, `res/mipmap*`
and the quote screen at the same time: do not touch them. No commits; never git checkout/restore/stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T18b` exits 0 (if it fails only in T20's files, say so).

## Report
`docs/v1-tickets/runs/T18b/build-report.md`, ≤15 lines, listing which tests failed against the old code.
