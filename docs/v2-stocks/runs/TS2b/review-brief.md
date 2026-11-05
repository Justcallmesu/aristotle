# Re-review brief — v2 TS2b (fix round for TS2)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/TS2/review-report.md` (the findings),
`runs/TS2b/build-brief.md` + `build-report.md`. Diff vs HEAD: `git diff HEAD -- android` + untracked (TS2 + TS2b together are uncommitted; focus
on the TS2b changes: `data/LinkState.kt`, `data/LinkManager.kt`, `ui/stocks/**`, `ui/sessions/{ConnectionPage,SessionsScreen,HomeModeSwitch}.kt`
and their tests). Read-only: only your report; no gradle; no git state changes; no adb; never connect to a server.

Check: MAJOR-1 really closed (stocks use the server endpoint in Online/HermesDown/HermesUpdated, nothing in cert-changed/unpaired/stopped/
connecting); **the LinkManager change does not alter any existing chat/relay behaviour** (every consumer of HermesDown/HermesUpdated, reconnect,
backoff, notifications, background service, discovery) — that is the main risk; m1-m4 closed; no new MAJOR. Only BLOCKER/MAJOR trigger a round.
Report ≤ 20 lines at `docs/v2-stocks/runs/TS2b/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
