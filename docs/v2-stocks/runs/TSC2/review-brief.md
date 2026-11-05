# Review brief — TSC2 (screener server) + TM3 delta (news_only rule) + TM4 delta (gesture strips, readout height)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md` (Q85, Q86, Q106, Q111, P1, P8),
`runs/TS4/report.md` (binding corrections), `runs/TSC0/`, `runs/TSC2/build-brief.md` + `report.md`, `runs/TM3/build-report.md`, `runs/TM4/build-report.md`,
`docs/protocol.md` §20.1-§20.3. Diff: `git diff HEAD -- server android docs/protocol.md CHANGELOG.md` + untracked `server/**`. TSC3 (android
screener UI) is being built in parallel in `android/**` — IGNORE files you do not see named in TM4's report.
PM: gate `runs/pm-gate-tsc2` GREEN (637 Go, -race clean); android gate `runs/pm-gate-tm4` GREEN. Mutations caught: liquidity floor off ->
TestRankOrderLiquidityAndFlags + TestScreenerScanJob; news_only lifted by hold:* -> TestNewsOnlyBuyIsFlaggedAndCapped; gesture strip height cap
removed -> theGestureStripsAreTheChartEdgesWithinTheSystemLimit. PM added §20.2 "Jobs now" line for `screener.scan`.
Read-only: only your report; `go test`/`-race` allowed; no gradle; no network; no git state changes; no adb.

Check with file:line: each setup formula vs TS4 (gap from the daily open; pacing by market time; RSI/MA use only completed bars; stale/no-signal
rules; at-ARA), the score, liquidity floor; scan schedule slots (Mon-Thu 34 / Fri 28, holidays, delay shift) and Yahoo budget, 429 handling;
settings file validation + versioning (an older binary must not break `stocks.json`); hits log semantics for slice 3; endpoints + error codes vs
§20.3; concurrency (scan vs settings PUT vs eod screen) under -race; manual scan rate limit; the contract change `high`+`lookback` documented;
the TM3 news_only rule; TM4 strips/readout. Only BLOCKER/MAJOR trigger a round.
Report ≤ 30 lines at `docs/v2-stocks/runs/TSC2/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
