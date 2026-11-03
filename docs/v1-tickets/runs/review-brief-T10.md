# Review brief — T10 (foreground service + notifications + Xiaomi guide)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` Q5, Q18,
Q27, `docs/design.md`, `docs/protocol.md` §2 (incl. the new background-subscription note), §6, §12, `docs/v1-tickets/runs/T10/build-brief.md`
+ `build-report.md`. Diff: `git diff HEAD -- android docs/protocol.md` + untracked android files. PM gate GREEN (`runs/pm-gate-14`,
android 157 tests). Edit nothing but your report; no git state changes; do not run gradle.

Focus: Android 14 FGS rules (specialUse declaration + subtype property, start-from-background restrictions, `startForeground` within the
time limit, Stop action, onTaskRemoved), POST_NOTIFICATIONS flow, PendingIntent flags (IMMUTABLE), notification content never leaking
secrets (approval command hidden — confirm; clarify question shown — any risk?), lock-screen visibility, channel importance; SessionWatcher
correctness vs protocol (resume rules, duplicate resumes, sessions.changed handling, settled prompts dismissing the alert, cancelled turns);
interaction with LinkManager states (Stopped) and the T06 server limits (resuming many sessions on one socket — any frame-rate risk vs 600
frames/min?); the Xiaomi intents (do they resolve / safe fallback); design.md conformance; EN/ID. Only BLOCKER/MAJOR trigger a fix round.
Report ≤35 lines at `docs/v1-tickets/runs/T10/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
