# Review brief — TS2 (app lock, fresh auth, hide terminal) + TS1b — SECURITY

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q50, Q51, Q61, Q62, `docs/v1-tickets/runs/
TS2/build-brief.md` + `build-report.md`, `runs/TS1/review-report.md` (TS1b items). Diff: `git diff HEAD -- android` + untracked android
files (ignore `ui/chat/ChatText.kt` = PM's one-line regex escape). PM gate `runs/pm-gate-42`; PM mutation (lock timeout never elapses)
must fail tests. Edit nothing but your report; no gradle; no git state changes; no adb.

Attack, with file:line: can the content be seen/used while locked (composition underneath, recents thumbnail, notifications' tap path,
deep links/open-chat intents, the terminal page restored, the share/attach result, process death, rotation, multi-window, a second
activity task); can the lock be skipped (timer clock: elapsedRealtime vs wall clock, onStop/onStart counting, the auth-in-progress
exemption abused, turning the lock off without auth); fresh-auth gates (terminal, Always allow, sudo, secret): any path that sends without
a successful auth (retry, outbox, double tap, a stale callback after cancel, the sheet re-opened); BiometricPrompt usage (authenticators
per API level, Android 10 weak-biometric fallback — acceptable?, CryptoObject not used — is that fine for a UI gate?); the worker's choices
in the report "Decisions for you" — judge each; TS1b cancel/commit order. Only BLOCKER/MAJOR trigger a fix round. Report ≤30 lines at
`docs/v1-tickets/runs/TS2/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
