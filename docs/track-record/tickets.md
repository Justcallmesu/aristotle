# AI track record — tickets

Spec: `docs/track-record/spec.md` (Q116-Q120, readings Q120a/b). Kit, gates and roles: `PROJECT.md`
(`bash docs/v1-tickets/gates.sh <run-dir>`, `GATE_ONLY=server|android`). Runs go in `docs/track-record/runs/<id>/`.

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| TR0 | Research (read-only): Yahoo 5-minute bars for a PAST session (how far back, request shape through the existing provider, cost per call), `session_date` semantics for calls made before / during / after the session (Q120b), codes with no bars in the store (A2), history file size per year | - | docs | DONE |
| TR1 | Server: append-only verdict history `stocks-verdicts.json` (seeded from today's kept results), scorer after the 16:20 bars job (session score Q117-Q120 + 3-day info), 5-minute ordering fetch (Q119), `GET /v1/stocks/track-record` (summary by verdict/model/trigger/confidence + per-code list), protocol §20.5 | TR0 | server | DONE (with TR1b, TR1c) |
| TD6 | Design board: Track record page (summary table, filters by model/trigger), one line on the verdict card ("AI record on Buy today: 11 of 24"), past calls on the stock page; empty and small-sample states; EN + ID | TR0 | design | DONE (Q121: cards + tonal chips) |
| TR2 | Phone: track-record page, verdict-card line, stock-page history, per the TD6 pick | TR1, TD6 | android | TODO |
| TR1b | TR1 fix round 1: transient 4xx froze daily scores, daily fill-day target, bad record crashed the job, Q125 fill, P16 give-up/splits, boot order, shared versioned decoder | TR1 | server | DONE (with TR1) |
| TR1c | TR1 fix round 2: network error stops the run, give up only after failures on two days, recent 422 waits, "decided" defined, 5 guard tests | TR1b | server | DONE (final review APPROVED WITH MINORS) |
| TR1d | Server minors (TR1c review): give-up unbounded with daily restarts (30-trading-day hard limit or persist the failure date); two isolated failures weeks apart give up (track last failure date); test: 403 on a 52-day window waits; NIT body-read error after 200 | TR1c | server | TODO (deferred, small) |

## Log
- 2026-11-06 Feature opened after v2 slice 3. Discovery: no verdict history exists (analyzer.go:712-727, Q99 keeps the last per code).
  User answered Q116-Q120 all A. Q120a/b recorded as PM readings to confirm. Nothing dispatched; waiting for the user's go.
- 2026-11-06 TR0 (research, std) and TD6 (design board, std) dispatched in parallel. TR0 may probe Yahoo (<= 8 requests).
- 2026-11-06 TR0 done (5m past bars ~60 days VERIFIED; Q120b confirmed by code; holiday list fine). TD6 board done (cards + tonal chips recommended). P10-P14 recorded. TR1 dispatched (deep): API returns counts only, rates/min-count on the phone pending Q121-Q124.
- 2026-11-06 Q121-Q124 decided (A), Q120a confirmed. TR2 waits for TR1.
- 2026-11-06 TR1 built (deep, 711 Go). pm-gate-tr1 GREEN, -race ok; mutations look-ahead, target-before-stop, stop-in-fill-bar -> all killed. P15 (daily fallback look-ahead, no fallback on network error) recorded. Deep review dispatched.
- 2026-11-06 TR1 review CHANGES REQUESTED (MAJOR: transient 4xx froze daily scores; daily fill-day target counted; bad record panics the job). Q125 decided (A). P15a, P16 recorded. TR1b fix round 1 of 2 dispatched (deep).
- 2026-11-06 TR1b built (deep, 737 Go, RED-first + 16 builder mutations). pm-gate-tr1b GREEN, -race ok; mutations any-4xx fallback, inside-zone fill, never give up -> all killed. Deep re-review of TR1+TR1b dispatched.
- 2026-11-06 TR1+TR1b re-review CHANGES REQUESTED (MAJOR: one transient network error after an offline gap gives up every old record). P16a recorded (network error stops the run; give up only after failures on two trading days; recent-window 422 = failure; "decided" defined). TR1c = fix round 2 of 2 (deep).
- 2026-11-06 TR1c built (deep, 757 Go). pm-gate-tr1c GREEN, -race ok; mutations network-not-stop, first-run give-up (first try NOT APPLIED, redone on :277), recent-422 fallback -> all killed. Final review dispatched (fix-round cap reached).
- 2026-11-06 TR1c final review APPROVED WITH MINORS (26/27 reviewer mutants killed). PM fixed protocol §20.5 info-only clause; CHANGELOG Server line added. TR1+TR1b+TR1c committed. Minors -> TR1d.
