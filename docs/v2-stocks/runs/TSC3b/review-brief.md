# Re-review brief — TSC3 + TSC3b (phone screener, whole uncommitted ticket)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `runs/TSC3/review-report.md` (MAJOR-1 + MINOR 1-5 + NIT), `runs/TSC3b/build-brief.md` +
`build-report.md`, spec Q106, `docs/protocol.md` §20.3, committed server code (`git show HEAD:server/internal/stocks/...`; the server working tree has
TBA1's uncommitted edits — ignore them). Diff: `git diff HEAD -- android` + untracked android files (TSC3 + TSC3b together).
PM: gate `runs/pm-gate-tsc3b` GREEN (1304); mutation bearish labels last -> ScreenerLogicTest 1 of 8 fails.
Read-only: only your report; no gradle; no git state changes; no adb; never connect to a server.
Check: MAJOR-1 closed (bearish never hidden on own rows, incl. "+N" and max=1 rows, stale/offline greying); MINOR 1-5 closed per the report (refresh after
16:00 on trading days, holiday stays quiet; cooldown only from own accepted scan or too_soon.retry_after; mode switch shows only that mode; follow stops
after 10 errors); the builder's open edge case (server started after 16:10) acceptable; no new MAJOR anywhere in the TSC3 diff.
Only BLOCKER/MAJOR trigger a round. Report ≤ 15 lines at `docs/v2-stocks/runs/TSC3b/review-report.md`, last line `VERDICT: ...`.
