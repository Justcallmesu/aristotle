# Review brief — T12b focused re-review (tap-through guard and the T12 minors)

Fresh reviewer. Repo `/home/justcallmesu/project/personal/Aristotle`. T12+T12b are committed in `0d9eddc`. Read
`docs/v1-tickets/runs/T12/review-report.md` (the findings), `runs/T12b/build-brief.md` + `build-report.md`, `docs/spec.md` Q50/Q51.
Diff to review: `git show 0d9eddc -- android` focusing on the T12b files listed in the build report (ChatViewModel, PromptSheetState,
PromptSheet, ChatScreen, MainActivity, AndroidManifest, OpenSession, SecretMenu, ResumedSessions, SessionWatcher, ChatFeed + tests).
PM gate GREEN `runs/pm-gate-20` (281 tests); mutation (drop the ViewModel arm check) fails 7 tests. A T13 worker is editing android/ in
the working tree right now: review the COMMIT, not the working tree. Edit nothing but your report; no git state changes; no gradle.

Check: every answer path goes through the ViewModel arm check (no path bypasses it: notification, chip, sheet swap, Always-allow confirm,
clarify rows, free text submit, sudo/secret send + cancel); the clock is monotonic (not wall clock that can jump); MINOR-1 re-answerable
logic cannot send twice; the non-exported alias really blocks other apps (intent filter on the alias? exported=false; MainActivity check
not spoofable via component name); profile+session matching cannot drop a legitimate prompt in the common single-profile case.
Only BLOCKER/MAJOR trigger a fix round. Report ≤20 lines at `docs/v1-tickets/runs/T12b/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
