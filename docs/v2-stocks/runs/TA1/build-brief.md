# TA1 — server: emiten analyzer (indicators + Hermes job + verdict checks)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `PROJECT.md`, `docs/v2-stocks/spec.md` (**Q96-Q103, P5-P7**, Q17,
Q87), `docs/v2-stocks/runs/TA0/report.md` (**your design**: methods + citations, prompt, JSON schema, contract, indicators, checks), `docs/protocol.md`
§1-§7, §12, §20. Code: `server/internal/stocks/**` (TS1: Yahoo bars, calendar tick/ARA/ARB tables, state, http), `server/internal/hermes/**`
(control-socket ticket + `/api/ws` client used by the relay), `server/internal/app/serve.go`. Hermes source (read-only) to confirm every frame:
`/home/justcallmesu/.hermes/hermes-agent`.

## Do
1. `internal/stocks/indicators.go` (pure): MA20/50, RSI14 (Wilder), volume vs 20-day average, support/resistance (TA0 method), 52-week position,
   trend label, today's intraday stats/VWAP when 5m bars exist; ARA/ARB + tick for TODAY computed from the server's own last close (not Yahoo
   previousClose after the session, TA0 risk). Table tests with hand-computed values.
2. Hermes analyzer client (`internal/hermes` addition): profile-scoped ticket for `aristotle-stocks` (P5); create → check `info.tool_count == 0`
   (else delete + fail) → `prompt.submit {finite:true}` → wait `message.complete` for that admission id (fallback `prompt.receipt
   include_result`) → timeout 120 s → `session.interrupt`; answer no server requests (interrupt + fail); never send `unattended`. Fake-Hermes tests
   (reuse `hermestest`) for success, tool_count>0, timeout, interrupted, server request, socket drop.
3. Job runner in `internal/stocks`: `POST /v1/stocks/analyze/{code}` body `{lang:"id"|"en"}` → 202 `{job_id}`; one job per code (409 if running),
   max 2 at once (429 busy); `GET /v1/stocks/analysis/{code}` → `{state: idle|running|done|failed, started_at, result?, error?}` with the KEPT last
   result (Q99) in `stocks-analyses.json` (statefile, ≤ 50 codes, newest wins). Held vs not held decides the verdict set (Q98). Prompt per TA0 in
   the requested language (Q101), numbers + up to 8 recent matched headlines with source + time; strict JSON verdict; schema + P6 checks; ONE
   repair turn; result stores `data_as_of`, model, duration, verdict, reasons[{text, source}], entry_low/high, target, stop, confidence, flags.
   Session cleanup per P7 (delete on success; failed kept 7 days, swept at start and daily).
4. Errors: Hermes down → 503 `hermes_unavailable`; profile missing → 503 `analyzer_not_set_up`; no bars → 422 `no_data`.
5. Docs: `docs/protocol.md` §20 add "Analyzer" subsection (contract as built + the Hermes frames used). CHANGELOG [Unreleased] one Server line.
Logs: job id, code, state, duration only — never the prompt, holdings or verdict text.

## Hard rules
Only `server/**`, `docs/protocol.md` §20, `CHANGELOG.md`, `docs/v2-stocks/runs/TA1/build-report.md`. No new Go modules. Tests use fakes only: never
connect to the real Hermes/gateway, never create real sessions, never touch port 8760 or the real state dir, no listeners on real interfaces.
Another worker edits `android/**` in parallel — not yours. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit.
Verify: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TA1` exits 0 (+ `go test -race ./internal/stocks/... ./internal/hermes/...`).
Report ≤ 15 lines. Stop and ask (in the report) if a Hermes frame differs from TA0's citations.
