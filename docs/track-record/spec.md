# AI track record — spec

Status: **decided (Q116-Q120, 2026-11-06); readings Q120a/b to confirm; not built**. Feature after v2 slice 3 (`docs/v2-stocks/`).
Own ids continue the global Q numbering.

## Goal
Score every AI stock verdict against what the price did afterwards, and show hit rates per verdict type, model, trigger and
confidence, so the user can judge whether the AI spend (Q103 Opus, Q110 Sonnet) gives useful calls.

## Verified current behaviour (2026-11-06, HEAD f516b5d)
- Only the LAST verdict per code is kept: `keep` overwrites `an.st.Analyses[code]` and drops the oldest code above 50
  (`server/internal/stocks/analyzer.go:712-727`, `maxAnalyses = 50` at `server/internal/stocks/analyzer.go:33`). Spec Q99 asked
  for exactly this ("the last result per stock is kept"). **There is no verdict history today**; scoring needs a new append-only store.
- A kept result already carries what scoring needs: code, held, analyzed_at, session_date, market_state, model, trigger
  (manual|auto), verdict, confidence, entry_low/high, target, stop, price (`server/internal/stocks/analyzer.go:119-147`).
- Verdicts (Q98): not held `buy_today|wait|avoid`, held `add|hold|sell` (`server/internal/stocks/verdict.go:37-40`). Level rules
  (`server/internal/stocks/verdict.go:489-517`): buy_today/add need entry, target, stop (stop < entry ≤ target); hold needs a stop
  below the price, optional target, no entry; sell/avoid have no levels; wait may carry levels.
- Confidence is `low|med|high`, not a percentage (`server/internal/stocks/verdict.go:34`, checked at `:308-309`).
- Horizon is a day trade: "good to buy TODAY?" (spec Q96, `docs/v2-stocks/spec.md:27`).
- Daily bars `[t,o,h,l,c,v]`, up to a year, for universe ∪ watchlist ∪ holdings, rewritten after the 16:20 job on trading days
  (`server/internal/stocks/bars.go:17-22`, `:40-45`). Daily bars cannot tell whether the target or the stop was touched first on
  a day that touched both.
- The brief's focus items carry a stance `watch_buy|watch|avoid` (`server/internal/stocks/brief.go:71`) and cite level ids, no own
  price levels (RULES line, `server/internal/stocks/brief.go:640`).

## Research findings (TR0, `docs/track-record/runs/TR0/report.md`, live Yahoo probe 2026-11-06, 7 requests)
- A1 VERIFIED: past-session 5-minute bars come back for 1, 5, 20 and 40 trading days back (56 calendar days); 70 days back gives
  HTTP 422, so the limit is ~60 days. One request can cover several sessions. Sessions run 09:00-16:10 in 5-minute bars, no bars in the
  lunch break; a weekend day answers 200 with 0 bars. The shipped client sends only `range`+`interval`: the scorer needs a new
  provider method taking `period1`/`period2`.
- Q120b VERIFIED in code: a call before 16:00 on a trading day gets that day as `session_date`; after 16:00, on a weekend or holiday,
  the next trading day.
- A2 VERIFIED: Analyze works on any code (falls back to Yahoo's 1y chart), so the scorer fetches per code, ~1 request per scored code.
- Size: ~450 bytes per scored record, ~1.1 MB a year at 10 calls a day: no cap needed.
- Not a problem: the bundled holiday list holds 2026 dates only from 12-24 on (`server/internal/stocks/idxcal.go:18-22`, "only the dates
  still ahead when this was written"); the history starts after that, so no past holiday is scored as a session.
- Unverified: the exact 5-minute cut-off (56-70 days) and whether the day's last 5-minute bars exist at 16:30.

## PM technical
| Id | Decision | Why |
|---|---|---|
| P10 | History: append-only `stocks-verdicts.json` (compact record, no reasons/news/indicators), appended in the analyzer's `keep`; Q99's last-result file unchanged. Seeded once from the results kept today. | One writer; Q99 untouched. |
| P11 | Scoring is its own scheduler job `track.score` at **17:00** WIB, trading days, catch-up always (one run scores every unscored session still inside Yahoo's ~60-day 5-minute window; older unscored calls are scored from daily bars with the stop-first rule and marked "daily only"). | Leaves margin after 16:20 bars and the unverified 16:30 availability; Q31 catch-up. |
| P12 | No look-ahead: a call scores only bars that START after its `analyzed_at` (a 14:00 call ignores the morning). A bar touching two levels counts stop first; a target in the same bar as the entry fill does not count (TR0 rule). | Otherwise a morning touch counts as a fill the user could not have had. |
| P13 | Scores are stored with the record and carry a `rule` version; they are never silently recomputed. | Numbers the user saw do not change under him. |
| P14 | Offline: the phone shows the PC-offline state, no cached copy (no new phone cache in this feature). | YAGNI; the stock page already shows offline the same way. |
| P15 | Daily-bar fallback (no 5-minute bars: session older than ~58 days, Yahoo had none) cannot drop the hours before a mid-session call, so it may count a morning touch; such scores carry `source:"daily"` and the phone marks them. A network error does NOT fall back (the record waits for the next run), so a daily score is never frozen by a transient error. | TR1 builder; accepted by PM: rare (only after weeks offline), visible, and conservative stop-first still applies. |
| P15a | (TR1 review) Daily fallback ONLY on HTTP 422 or a 200 with no bars; every other failure leaves the record for the next run. On the daily bar a target in the fill day never counts (same as P12): fill + target, no stop -> `closed`. | PM technical; P15's "never frozen by a transient error" made exact. |
| P16 | A record still unscored 10 trading days after its session (Yahoo keeps failing for that code) is scored `unscorable` (excluded from every rate, counted on its own, logged); `no_session` and `unscorable` records never ask for `info_3d`. A stock split inside the info window (refetched session close ≠ stored close by > 1 %) leaves `info_3d` empty. | PM technical: bounded Yahoo use; no fake -80 % info. |
| P16a | (TR1b review) A network error stops the run like a block (nothing scored, nothing given up). Give up (`unscorable`) only when the code also failed on an EARLIER trading day's run, not on one run after an offline gap. A 422 counts as "too old" only for sessions older than ~50 days; a 422 on a window of recent sessions is a failure (record waits), never a daily fallback. "Decided" for Q122 = every outcome except `not_triggered`, `no_session`, `unscorable` and `would_have`; §20.5 says so. | PM technical; keeps P15's "never frozen by a transient error". |

## Assumptions (resolved by TR0, see Research findings)
- A1 (5-minute bars for past sessions): VERIFIED, ~60 days. A2 (codes outside the bar store): VERIFIED, fetched per code.

## Decisions
| Id | Decision | Source |
|---|---|---|
| Q116 | Scored: analyzer verdicts only, manual (Opus) and automatic (Sonnet). Brief stances and screener setups are not scored in this feature (later). | User 2026-11-06 "A". |
| Q117 | Score window = the session the call was made for (day trade, Q96); a 3-trading-day result is shown beside it for information only (not in the win rate). | User 2026-11-06 "A". |
| Q118 | Buy today / Add whose entry zone is not reached in its session = **not triggered**: own column, neither win nor loss. | User 2026-11-06 "A". |
| Q119 | A session that touches both target and stop (or entry and a level) is ordered with that session's 5-minute bars from Yahoo, fetched once after close; when they are not available the call counts as **stop first**. | User 2026-11-06 "A". |
| Q120 | Avoid / Sell: right when the close at the end of the window is not above the price at the call. Hold: by its own stop/target. Wait: shows what a buy would have made, no right/wrong. | User 2026-11-06 "A". |
| Q120a | Hold with only a stop = right when the stop is not hit by the window's close (and win when a target exists and is reached first). Wait's "would have made" = buy at its entry high if it gave an entry, else at the price at the call, to the window's close. Avoid/Sell use the session close for the score and the 3-day close for the info column. | PM reading, **confirmed by the user 2026-11-06**. |
| Q120b | A call made after the close (market_state closed) belongs to the NEXT session (its `session_date`). | PM reading, **confirmed by code** (TR0 §3). |
| Q121 | Track record page = TD6 board's recommended variant: one card per verdict type + tonal result chips (`docs/design/v3/track-record.html`). Board text fixes for the build: "scored after 17:00" (P11, the board says 16:20); Avoid/Sell counts read right / wrong, not win / loss. | User 2026-11-06 "A". |
| Q122 | A hit rate shows only from 10 decided calls per verdict type; below that "Not enough calls yet · n of 10". | User 2026-11-06 "A". |
| Q123 | Buy today / Add hit rate = win ÷ (win + loss + closed flat); not triggered is shown on its own, outside the rate. | User 2026-11-06 "A". |
| Q124 | The verdict-card line counts that verdict type across all models and triggers. | User 2026-11-06 "A". |
| Q125 | Buy today / Add fill = a limit buy at the entry high: any price at or below `entry_high` after the call counts as filled (a gap below the zone fills; the stop is then checked in that bar, often a loss). Result % still measured from `entry_high`. | User 2026-11-06 "A" (TR1 review). |

Logs of the track-record job carry codes and counts only.
