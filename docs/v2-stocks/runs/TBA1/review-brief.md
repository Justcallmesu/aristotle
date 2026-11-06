# Review brief — TBA1 (slice 3 server: alerts, 08:30 brief, auto AI runs) + TSC2 minors

Fresh reviewer, deep (automated AI runs spend the user's Claude limits; alerts drive trading attention). Repo `/home/justcallmesu/project/personal/Aristotle`.
Read `docs/v2-stocks/spec.md` (Q18, Q31, Q87, Q103-Q110, P5-P8), `runs/TSC0/design.md` §2, `runs/TSC2/review-report.md`, `runs/TBA1/build-brief.md` +
`build-report.md`, `docs/protocol.md` §20-§20.4. Hermes source (read-only) for `session.create {model}`. Diff: `git diff HEAD -- server docs/protocol.md
CHANGELOG.md` + untracked `server/**`. The phone side (TBA2) is built in `android/**` in parallel — ignore it.
PM: gate `runs/pm-gate-tba1` GREEN (653 Go, -race ok). Mutations: auto-run cap +100 -> TestAutoRunCapRefusalAndFreshVerdict fails. **SURVIVED:**
brief job registered with `TradingDays: false` (`stocks.go:159`) -> no test fails: confirm there is no test that the scheduled brief never runs on
a weekend/holiday (Q108) and list it as a required fix with the test shape. Read-only: only your report; `go test`/`-race` allowed; no network; no real
Hermes; no listeners; no git state changes; no adb.

Check with file:line: auto runs — cap 5 persisted + 00:00 WIB reset under restart and clock jumps, once per code per day, ≥ 2 different bullish setups
from scan/eod origin only, watchlist+held only, Sonnet pinned at create (Hermes source), tool-less guards from TA1 kept, no run when Hermes is
down (no retry storm); alerts — dedupe keys, 3/code + 30/day caps, quiet hours 08:00-16:30 except the brief, ring 300/7 d, long-poll `wait ≤ 50`
cancellation and goroutine cleanup per client, auth; brief — trading days only, catch-up before 09:00, retry every 5 min only for retryable errors,
"no prices" rule (numbers > 99 refused) not breaking legit text like years/percent, language rule Q105, storage 7; holdings stop/target validation and
the new `stocks-levels.json` versioning; the five TSC2 fixes really closed; logs carry no prompt/brief/verdict/holdings; §20.4 matches. Only
BLOCKER/MAJOR trigger a round. Report ≤ 30 lines at `docs/v2-stocks/runs/TBA1/review-report.md`, last line `VERDICT: ...`.
