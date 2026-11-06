# AI track record — tickets

Spec: `docs/track-record/spec.md` (Q116-Q120, readings Q120a/b). Kit, gates and roles: `PROJECT.md`
(`bash docs/v1-tickets/gates.sh <run-dir>`, `GATE_ONLY=server|android`). Runs go in `docs/track-record/runs/<id>/`.

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| TR0 | Research (read-only): Yahoo 5-minute bars for a PAST session (how far back, request shape through the existing provider, cost per call), `session_date` semantics for calls made before / during / after the session (Q120b), codes with no bars in the store (A2), history file size per year | - | docs | DONE |
| TR1 | Server: append-only verdict history `stocks-verdicts.json` (seeded from today's kept results), scorer after the 16:20 bars job (session score Q117-Q120 + 3-day info), 5-minute ordering fetch (Q119), `GET /v1/stocks/track-record` (summary by verdict/model/trigger/confidence + per-code list), protocol §20.5 | TR0 | server | DONE (with TR1b, TR1c) |
| TD6 | Design board: Track record page (summary table, filters by model/trigger), one line on the verdict card ("AI record on Buy today: 11 of 24"), past calls on the stock page; empty and small-sample states; EN + ID | TR0 | design | DONE (Q121: cards + tonal chips) |
| TR2 | Phone: track-record page, verdict-card line, stock-page history, per the TD6 pick | TR1, TD6 | android | DONE (review APPROVE; minors -> TR2b) |
| TR1b | TR1 fix round 1: transient 4xx froze daily scores, daily fill-day target, bad record crashed the job, Q125 fill, P16 give-up/splits, boot order, shared versioned decoder | TR1 | server | DONE (with TR1) |
| TR1c | TR1 fix round 2: network error stops the run, give up only after failures on two days, recent 422 waits, "decided" defined, 5 guard tests | TR1b | server | DONE (final review APPROVED WITH MINORS) |
| TR1d | Server minors (TR1c review): give-up unbounded with daily restarts (30-trading-day hard limit or persist the failure date); two isolated failures weeks apart give up (track last failure date); test: 403 on a 52-day window waits; NIT body-read error after 200 | TR1c | server | REVIEWED: CHANGES REQUESTED (MAJOR: `failures` in the verdicts file breaks a rollback to c74df38) -> TR1e; held uncommitted |
| TR1e | TR1d fix round: `failures` in its own file `stocks-track-failures.json` (verdicts file byte-compatible with v1), entries removed after give-up, hard limit only for codes that also failed on an earlier trading day (P16a kept), tests for the surviving mutants, NITs | TR1d | server | TODO |
| TR2b | Phone minors (TR2 review): page subline cut at 1.3x; false 'PC out of reach' while the link connects; model filter by family; card-line rate words via the pure rule + test; filters reset when opened from a card line | TR2 | android | TODO |

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
- 2026-11-06 Committed c74df38 (TR1+b+c). TR2 (phone, std) and TR1d (server minors, std) dispatched in parallel (disjoint sides).
- 2026-11-06 TR2 built (std, 1406 per builder) and TR1d built (std, 763). pm-gate-tr1d GREEN, -race ok; mutations no hard limit, failures not saved -> killed. P16b recorded. TR1d deep review dispatched; TR2 PM mutations + gate running.
- 2026-11-06 TR2: pm-gate-tr2 GREEN (1406); mutations not-triggered in buy rate, threshold 1 -> killed (4 cases each). Deep review dispatched in parallel with the TR1d review.
- 2026-11-06 TR2 review APPROVE (5 minors -> TR2b). TR1d review CHANGES REQUESTED (MAJOR rollback compatibility of stocks-verdicts.json) -> TR1e. TR2 committed alone (android + CHANGELOG Phone line); TR1d held.
