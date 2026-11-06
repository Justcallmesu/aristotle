# TR2 — phone: AI track record (page, verdict-card line, stock-page past calls)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `c74df38` (clean). Read `PROJECT.md`, then fully:
`docs/track-record/spec.md` (Q117-Q125, P11, P14, P15, P16a: "decided"), `docs/protocol.md` **§20.5 (your contract; the server is
committed)**, the design board `docs/design/v3/track-record.html` + `docs/design/v3/track-record-notes.md` (Compose mapping) — build the
**recommended variants only (Q121: cards per verdict type + tonal result chips)**, `docs/design.md`. Phone code to extend and copy patterns
from: `ui/stocks/` (`StocksPages.kt` for a page in the stocks back stack, `BriefScreen.kt` + `BriefViewModel.kt` for a page with its own
ViewModel and states, `VerdictCard.kt`, `StockDetailScreen.kt`, `StocksHome.kt` ⋮ menu), `data/stocks/` (`StocksApi.kt`, `StocksModels.kt`),
`ui/StocksBackStack.kt`. Read the real code before editing.

## Do
1. **Transport:** `GET /v1/stocks/track-record[?code=]` per §20.5 in `StocksApi` + models (unknown fields ignored, missing fields safe).
2. **Rates (pure, unit-tested, in `data/stocks/`):** per verdict type, summed over the active filters: "decided" exactly as §20.5/P16a; Buy
   today / Add hit rate = win ÷ (win + loss + closed) (Q123), not triggered shown on its own; Hold / Avoid / Sell rate = right (+ win for
   hold) ÷ decided; Wait shows average "would have made", no rate; a rate only from 10 decided calls, else "Not enough calls yet · n of 10"
   (Q122); `unscorable` / `no_session` never in a rate. Averages only over records that have the value (`result_n`, `info_3d_n`).
3. **Track record page:** from Stocks ⋮ "AI track record" and from the card line; cards per verdict type, filter chips model / trigger /
   confidence, the 3-day column visibly secondary (Q117), "scored after 17:00" notice when `pending > 0` (P11; the board says 16:20 —
   use 17:00), the "How scoring works" sheet in plain words (Q117-Q125, stop first, limit-buy fill, daily-only marker P15), states per the
   board (no history, only today's, small sample, filters match nothing, PC offline per P14: no cache).
4. **Verdict-card line (Q124):** that verdict type across all models and triggers, e.g. "AI record on Buy today: 8 of 24 hit target"; below 10
   decided "not enough calls yet"; tap -> the page. Avoid / Sell / Hold wording is right/wrong, not hit target (Q121 note).
5. **Stock page "Past calls":** `?code=`, newest first, collapsed to 3 + "Show more", result chip per outcome, a small "daily" marker for
   `source:"daily"` (P15); rows not tappable.
6. Strings EN + ID (append-only), no hardcoded UI text; 1.3x text and 393 dp width without clipping (measure through the real text path, per
   the earlier chart tests' pattern).

## Tests (pure; RED first where the API exists)
Rates: every verdict type, the Q122 threshold at 9/10, Q123 denominator (not triggered excluded), unscorable/no_session excluded, filters
summing groups, averages weighted by `result_n`/`info_3d_n`. Card line text per verdict + below-threshold. Transport parse incl. an unknown
outcome string (shown as "other", never a crash). Past calls collapse/expand logic.

## Hard rules
Files: `android/**`, `docs/track-record/runs/TR2/build-report.md` (with a "Doc text" CHANGELOG Phone line). Not yours: `server/**` (a server
worker, TR1d, edits it in parallel: never touch it, never run Go), all other docs. No new libraries. No adb, no server connection. No commits;
never `git checkout/restore/stash/reset`. Gradle `--tests` filters match CLASS names. Avoid `bash -c`/heredocs/chained `$(...)`: script
files + plain `bash <file>`. Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/track-record/runs/TR2` exits 0.

## Stop and report if
§20.5 lacks a field the page needs; the board needs a number the server does not send.

## Report
`docs/track-record/runs/TR2/build-report.md`, ≤ 15 lines: per item file:line, tests + RED-first, gate line + test count, files, not done.
