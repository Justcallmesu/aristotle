# Review brief — TS1 (Settings: re-pair, unpair, appearance, language, notifications)

Fresh reviewer. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` Q25, Q49, Q60, `docs/design.md`
(PM updated §1 for Q60 + an 11 sp floor), `docs/v1-tickets/runs/TS1/build-brief.md` + `build-report.md`. Diff: `git diff HEAD -- android`
+ untracked android files (note: `ChatDrafts.clear()` already landed in commit 6809747). PM gate GREEN `runs/pm-gate-39` (639); PM mutation
(unpair keeps credentials) fails 1. Edit nothing but your report; no git state changes; no gradle; do not touch the phone.

Check with file:line: re-pair is atomic (new pairing saved + active before the old is removed; every failure path keeps the old pairing,
including process death mid-save and Keystore errors); unpair clears token, pin, addresses, drafts, per-PC profile/pins and leaves no
background link/service/terminal running with the old token; the revoke command shown has no secret; language switching (LocaleManager on
33+, stored + applied on <33) cannot crash or loop; theme/text size apply app-wide and the 11 sp floor (design.md §1) — is it enforced? if
not, MINOR; notification channel ids unchanged; EN/ID; design.md. Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at
`docs/v1-tickets/runs/TS1/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
