# TM3s — server minors (TS1b + TA2b server part)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read the rows TS1b and TA2b in `docs/v2-stocks/tickets.md`, then
`docs/v2-stocks/runs/TS1/review-report.md` and `runs/TA2/review-report.md` (findings with file:line), `docs/protocol.md` §20/§20.1.
Do (server side only): news Close() marks the module closed so no poller restarts after shutdown; backoff on Yahoo 5xx like 429; the name
fallback must not bypass the word-code rule (CASH); news image_url only http(s); chart cache eviction (LRU, cap ~200 entries); zero-cost guard in
Evaluate; misleading log on an unusable create answer; a sweep delete timeout must not stop the pass (skip + continue, keep the "Hermes
unreachable" early stop). Leave the news_only rule as is (backstop; documented). Tests for each.
Hard rules: only `server/**`, `docs/protocol.md` §20 (only if a behaviour text changes), the report. Fakes only; never connect to real Hermes/Yahoo;
no port 8760; no real state dir. Another worker edits `android/**`. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit:
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TM3s` exits 0 + `go test -race ./internal/stocks/...`. Report ≤ 10 lines.
