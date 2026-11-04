# Review brief — TL2 (debug log, crash screen — PRIVACY), TM2 (message actions), TR7 (PC card), + TP1a/b/c (committed 00e729a, unreviewed)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q50, Q51, Q73, Q78-Q80, briefs + reports in
`docs/v1-tickets/runs/{TL2,TM2,TR7,TP1a,TP1b,TP1c}/`, `docs/protocol.md` §16, `docs/design/v2/design-notes.md` §5.7. Diff: `git diff HEAD --
android CHANGELOG.md docs` + untracked; and `git show 00e729a`. PM gate `runs/pm-gate-59` (one clean run over all three). Edit nothing but
your report; no gradle; no git state changes; no adb.

**TL2 privacy (highest stakes):** can any token, pairing code, sudo/secret value, full fingerprint, message/prompt/reasoning/tool-output
text, session title, or file path with the username reach the log or a crash file? Check every AppLog call site and the crash handler
(exception messages often embed content — e.g. JSON parse errors quoting input, OkHttp URLs). Redactor rules vs real data shapes
(device token = 43 base64url chars; pairing code alphabet). Ring bounds, rotation, thread; crash handler chaining (app still dies);
the crash dialog only after the app lock; FileProvider exposes only the `share/` cache subdir; files cleared on unpair; backup exclusion.
Judge the worker's 6 decisions in the TL2 report.
**TM2:** Steer-with never sends; quote at cursor; long-press doesn't break links/selection/steps; copy only from message text.
**TR7:** null-safety, states, contrast/roles (red only Hermes down), hand-drawn icons acceptable?
**TP1a:** `/v1/pc-status` read-only, behind requireDevice, no path disclosure beyond the home dir in `disk.path` (the username!) — is
that acceptable or should it be "~"?; Hermes Identify every 10 s cost; sampler lifecycle; no wildcard binds.
Only BLOCKER/MAJOR trigger a fix round. Report ≤30 lines at `docs/v1-tickets/runs/review-Q80.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
