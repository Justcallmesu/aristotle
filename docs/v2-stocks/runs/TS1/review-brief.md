# Review brief — v2 TS1 (stocks server)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/v2-stocks/spec.md`,
`docs/v2-stocks/runs/TS0/report.md` (§7 contract), `runs/TS1/build-brief.md` + `build-report.md`, `docs/protocol.md` §16 and §20.
Diff: `git diff HEAD -- server CHANGELOG.md docs/protocol.md` + untracked `server/internal/stocks/**`, `server/internal/httpapi/stocks_test.go`.
PM gate `docs/v2-stocks/runs/pm-gate-ts1` GREEN (480 Go). PM mutations: sell fee sign flipped -> 7 fail; Friday S1 end 11:30->12:00 -> 3 fail.
Read-only: edit only your report; `go test` allowed; no network calls to Yahoo/RSS; never bind a listener, touch port 8760 or the real state
dir; no git state changes; no adb.

Check with file:line: P/L math (rounding, overflow on 10^7 lots x price, fee ppm conversion, pl_pct with cost 0) against TS0 §7; auth — every
`/v1/stocks/*` route behind `requireDevice`, no route when Deps.Stocks nil; input validation (code regex, limits, body size, PUT reorder set
rule, lots/price/fee bounds, NaN/Inf); state file atomic + version + corrupt-file behaviour; concurrency (cache, state, news ring, poller —
`go test -race ./internal/stocks/...`); Yahoo client: browser UA, timeouts, response-size caps, backoff, stale cache, 503 retry_after, no secret
or holdings value in logs; news poller: only while a device asked in the last 30 min, stops cleanly on shutdown (no goroutine leak), XML size
caps, matcher false positives; calendar: sessions, Friday, holidays, WIB without relying on the host TZ; the extra fields beyond the contract
documented in §20; no listener on all interfaces; no new modules. Only BLOCKER/MAJOR trigger a fix round.
Report ≤ 30 lines at `docs/v2-stocks/runs/TS1/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
