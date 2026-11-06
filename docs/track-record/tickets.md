# AI track record — tickets

Spec: `docs/track-record/spec.md` (Q116-Q120, readings Q120a/b). Kit, gates and roles: `PROJECT.md`
(`bash docs/v1-tickets/gates.sh <run-dir>`, `GATE_ONLY=server|android`). Runs go in `docs/track-record/runs/<id>/`.

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| TR0 | Research (read-only): Yahoo 5-minute bars for a PAST session (how far back, request shape through the existing provider, cost per call), `session_date` semantics for calls made before / during / after the session (Q120b), codes with no bars in the store (A2), history file size per year | - | docs | TODO |
| TR1 | Server: append-only verdict history `stocks-verdicts.json` (seeded from today's kept results), scorer after the 16:20 bars job (session score Q117-Q120 + 3-day info), 5-minute ordering fetch (Q119), `GET /v1/stocks/track-record` (summary by verdict/model/trigger/confidence + per-code list), protocol §20.5 | TR0 | server | TODO |
| TD6 | Design board: Track record page (summary table, filters by model/trigger), one line on the verdict card ("AI record on Buy today: 11 of 24"), past calls on the stock page; empty and small-sample states; EN + ID | TR0 | design | TODO |
| TR2 | Phone: track-record page, verdict-card line, stock-page history, per the TD6 pick | TR1, TD6 | android | TODO |

## Log
- 2026-11-06 Feature opened after v2 slice 3. Discovery: no verdict history exists (analyzer.go:712-727, Q99 keeps the last per code).
  User answered Q116-Q120 all A. Q120a/b recorded as PM readings to confirm. Nothing dispatched; waiting for the user's go.
- 2026-11-06 TR0 (research, std) and TD6 (design board, std) dispatched in parallel. TR0 may probe Yahoo (<= 8 requests).
- 2026-11-06 TR0 done (5m past bars ~60 days VERIFIED; Q120b confirmed by code; holiday list fine). TD6 board done (cards + tonal chips recommended). P10-P14 recorded. TR1 dispatched (deep): API returns counts only, rates/min-count on the phone pending Q121-Q124.
