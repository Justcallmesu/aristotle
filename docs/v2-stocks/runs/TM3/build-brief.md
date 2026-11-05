route: deep.

# TM3 — server minors (TS1b + TA2b server parts)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/tickets.md` rows TS1b and TA2b, `runs/TS1/review-report.md`,
`runs/TA2/review-report.md` (findings with file:line), then the code in `server/internal/stocks/**`, `server/internal/hermes/analysis.go`.
Do: every TS1b item (news Close() marks closed so no poller restart after shutdown; backoff on Yahoo 5xx; name-fallback bypass of the word-code
rule e.g. CASH; image_url http(s) only; chart cache eviction (LRU, bounded); zero-cost guard) and the TA2b server items (news_only cap: count a
reason as news-backed when ALL its sources are news; a buy/add is news_only when no reason cites an indicator/level source — document; misleading
create log; one sweep-delete timeout must not stop the pass). Tests for each; `-race` clean.
Files: `server/**`, `docs/protocol.md` §20 (only if behaviour visible to clients changes), report. Fakes only; no network; no real Hermes; no port
8760; no real state dir. Another worker edits `android/**` in parallel. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit:
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TM3` exits 0. Report ≤ 12 lines at `runs/TM3/build-report.md`.

