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

## Assumed (to confirm in the research/design ticket)
- A1: Yahoo serves 5-minute bars for a past session for at least ~30 days (TS3's 1D chart uses intraday bars for today only).
- A2: Analyze can run on a code outside the bar store's quoted set (any stock page); such a code then needs its own fetch.

## Decisions
| Id | Decision | Source |
|---|---|---|
| Q116 | Scored: analyzer verdicts only, manual (Opus) and automatic (Sonnet). Brief stances and screener setups are not scored in this feature (later). | User 2026-11-06 "A". |
| Q117 | Score window = the session the call was made for (day trade, Q96); a 3-trading-day result is shown beside it for information only (not in the win rate). | User 2026-11-06 "A". |
| Q118 | Buy today / Add whose entry zone is not reached in its session = **not triggered**: own column, neither win nor loss. | User 2026-11-06 "A". |
| Q119 | A session that touches both target and stop (or entry and a level) is ordered with that session's 5-minute bars from Yahoo, fetched once after close; when they are not available the call counts as **stop first**. | User 2026-11-06 "A". |
| Q120 | Avoid / Sell: right when the close at the end of the window is not above the price at the call. Hold: by its own stop/target. Wait: shows what a buy would have made, no right/wrong. | User 2026-11-06 "A". |
| Q120a | **PM reading — confirm:** Hold with only a stop = right when the stop is not hit by the window's close (and win when a target exists and is reached first). Wait's "would have made" = buy at its entry high if it gave an entry, else at the price at the call, to the window's close. Avoid/Sell use the session close for the score and the 3-day close for the info column. | PM 2026-11-06. |
| Q120b | **PM reading — confirm:** a call made after the close (market_state closed) belongs to the NEXT session (its `session_date`), which is what the analyzer already targets. | PM 2026-11-06; verify `session_date` semantics in TR0. |

## PM technical (proposed, P-rows once the Q rows are decided)
- History store: new append-only `stocks-verdicts.json` (compact record: the fields above minus reasons/news/indicators), written
  in `keep`; Q99's "last result" file stays unchanged. Seeded once from the up-to-50 results kept today.
- Scoring runs after the 16:20 bars job on trading days, from stored bars; results stored with the verdict, never recomputed
  silently (a rule change bumps a `rule` version and rescoring is explicit).
- Logs carry codes and counts only.
