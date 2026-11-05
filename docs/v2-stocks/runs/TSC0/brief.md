# TSC0 — design: slice 2 screener + slice 3 brief/alerts (read-only research + design draft)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Decisions: `docs/v2-stocks/spec.md` (Q84-Q103, P1-P7) and `docs/spec.md` Q14-Q22,
Q31 (catch-up at boot). Code: `server/internal/stocks/**` (provider, cache, indicators.go, calendar, analyzer job + Hermes client), `docs/protocol.md`
§20/§20.1, the phone's background service + notifications (`android/.../service/**`, notification channels; grep `NotificationChannel`).
No code; no network beyond reading local files; no commits.

Write `docs/v2-stocks/runs/TSC0/design.md` (≤ 120 lines) covering:
1. **Screener (Q85/Q86):** universe from `universe.json` (LQ45 ∪ IDX80 ∪ watchlist); data budget (daily bars 1y once a day after close + intraday
   15m during sessions? how many Yahoo calls per scan, cadence during sessions, backoff); storage (P1 said SQLite when slice 2 needs history — decide:
   SQLite (which pure-Go driver, new module) vs JSON/gob cache files per code; recommend with trade-off; the user must approve a new module only if it
   is a new third-party dependency — mark it as a user question if so); the five setups with exact formulas + default thresholds + tunables
   (breakout above 20-day high with volume ≥ 1.5x, volume spike x2, MA20/50 cross, RSI rebound from < 30, gap up/down ≥ 2 %); results model
   (candidates with which setups fired, score, data time); REST contract `/v1/stocks/screener` (+ settings) in §20 style; phone screens (a Screener
   tab/section on Stocks: list of candidates, setup chips, settings sheet).
2. **Brief + alerts (Q18, Q21, Q87):** the 08:30 WIB brief (inputs: overnight news, watchlist/holdings levels, yesterday's screener; one AI run in
   aristotle-stocks; output shape; kept per day); intraday alerts: entry/target/stop hit for kept verdicts and holdings, breaking news on watchlist,
   rule fires on watchlist -> automatic AI run capped at 5/day (Q87) with a counter; dedupe/quiet rules; scheduler in the server (job list, editable
   per Q20 — what is editable in v2: on/off + times), catch-up at boot (Q31); how alerts reach the phone (server -> phone push over the existing relay
   socket? a new event type? or phone polls `/v1/stocks/alerts` from the background service) — read the background service and recommend the one
   that works when the app is closed; notification channel + tap target.
3. Ticket split (server/android, ≤ 1 worker run each), order, risks, and an explicit list of USER questions (anything not decided by Q-rows).
