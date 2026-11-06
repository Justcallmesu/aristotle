# Aristotle v3 — AI track record design notes (TD6)

Board: `docs/design/v3/track-record.html` (self-contained, no network; source `track-record-src/`: `src.html`, `strings.json` EN + ID, `build.py`).
Frame, tokens, fonts, Tweaks pattern = `v2/screener.html`. Nothing new in palette, shapes, type or motion. Samples are generated (12 Oct – 5 Nov 2026, today Thu 5 Nov), not results.
Numbers id-ID (`+0,4 %`, one decimal), U+2212 minus. Tweaks: view, theme, language, text 1.0 / 1.3×, summary, result chips, scenario (clickable path).

## 1. Picks for the user (recommended first)
1. Summary: **cards** (one `groupedRowShape` row per verdict type) · table (6 columns; wraps at 1.3×, the verdict name goes to two lines).
2. Result chips: **tonal fill** (gain / loss container, neutral `surfaceContainerHigh`) · outline + `check` / `close` icon. Same 24 dp `extraSmall` `labelMedium` part as the screener `SetupLabel`.

## 2. Screens → Compose
**Entry 1** Stocks `BottomAppBar` ⋮ `DropdownMenu`: Broker fees, **AI track record**, Settings. **Entry 2** the record line (below).
**Record line** (`RecordLine`, inside `VerdictCard`, under the verdict-word row, above the summary): `Row(clickable, heightIn(min = 48.dp))`, `bodySmall` `onSurfaceVariant`,
numbers `SemiBold` `onSurface`, `chevron_right` 20 dp. Text: "AI record on Buy today: 11 of 21 hit target" / Avoid "16 of 26 were right" / Hold "18 of 22 held above the stop" /
Wait "a buy would have made +0,2 % on average". Fewer than 10 decided: "AI record on Add: not enough calls yet". No history: "No AI record yet · this verdict is scored after 16:20".
Offline: same text + "· saved copy", numbers muted. Always opens the page. No maxLines: wraps at 1.3×.
**Past calls** (`PastCalls` under the card in `AnalysisSection`): `SectionHeader` "Past calls · 8", `groupedRowShape` rows (min 64 dp): verdict `titleSmall`, `bodySmall` "Wed 4 Nov · Sonnet · auto",
`ResultChip` at the end (`weight`-free, max 52 % width, wraps). Newest first; today's verdict is first with "Scored after 16:20" (outline, `schedule`). Collapsed to 3 + a 48 dp
`TextButton` row "Show 5 more" / "Show less" (state kept in the ViewModel). Rows are not clickable: the old reasons are not kept. Empty: `titleMedium` + one line; offline: muted + "Saved copy from 10:52".
**Page** (`PairedPage.StocksTrackRecord`): `TopAppBar` (back, title Literata 18, subline `bodySmall` "117 verdicts scored · since 12 Oct", ⓘ `IconButton` opens the sheet).
Filters: `LazyRow` of three `FilterChip`s (trailing `expand_more`) each with a `DropdownMenu` (All + options, check on the active one); active = `secondaryContainer`, "Model: Sonnet"; a "Clear" chip first when any is set;
subline becomes "15 of 117 scored verdicts". Notice (`surfaceHighest`, `schedule`): "3 of today's verdicts are scored after 16:20". `LazyColumn`: `SectionHeader` "By verdict · N", the list, footer (`bodySmall` + `TextButton` "How scoring works").
**VerdictRecordCard** (`groupedRowShape`, padding 16 × 12): line 1 `titleMedium` verdict + `bodySmall` "33 verdicts" at the end; line 2 rate in words (`bodyMedium`, numbers SemiBold) + at the end "Avg result" (`bodySmall`) and a `ChangeText` (md, arrow + sign;
**Avoid / Sell use ink, not green / red**: a fall is the right answer there); a 8 dp `Row` bar of `weight`ed boxes (win `gain`, loss `loss`, flat `outline`, not triggered `outlineVariant`; no bar for Wait); counts `FlowRow` "8 win · 13 loss · 9 not triggered · 3 closed flat"; last,
`bodySmall` `onSurfaceVariant` "3 days later +0,6 % · for information" (visibly secondary, Q117). Wait: "A buy would have made +0,2 % on average", counts and bar dropped.
Under 10 decided: "Not enough calls yet · 8 of 10 needed to show a rate", bar and counts kept, no average, no 3-day line.
**Table variant**: header row + rows on a 6-column grid (name · Win · Loss · Not trig. · Flat · Avg 84 dp with "3 d +0,6 %" under it), legend `bodySmall`. Use only if picked; at font scale > 1.15 fall back to cards.
**Scoring sheet** (`ModalBottomSheet`, `headlineSmall` title): intro, then items (`titleSmall` + `bodyMedium`): window, Buy today and Add, target and stop on one day (stop-first, Q119), Avoid and Sell, Hold, Wait, 3 days later, Avg result, small samples, closing note; scrolls inside, tonal Close button.
**ResultChip text**: Buy/Add "Target hit +3,7 %" · "Stop hit −2,4 %" · "Closed flat +0,4 %" · "Not triggered"; Avoid/Sell "Right −2,5 %" · "Wrong +2,3 %" (the price move); Hold "Held" · "Stop hit"; Wait "If bought −2,4 %"; today "Scored after 16:20". The word carries the result, colour only confirms it.

## 3. States (all drawn, light + dark)
| State | Page | Stock page |
|---|---|---|
| no history | "Nothing to score yet" + cause + How scoring works button, no filters | line "No AI record yet …"; Past calls "No past verdicts for ADRO yet" |
| only today's | "The record starts after the close · 3 verdicts … 16:20" | Past calls: one row "Scored after 16:20" |
| small sample | rows under 10 show counts, no rate / average (Add, Sell; Sonnet + High) | line "not enough calls yet" |
| filters match nothing | "No verdicts match these filters" + Clear filters | – |
| PC offline | offline line + Retry now, last copy muted, filters dim, "Saved copy from 10:52, all verdicts" | muted rows, "Saved copy from …" |

## 4. Fields the screens need (per verdict; the contract is TR1's)
code, verdict, model, trigger, confidence, session date, outcome (win / loss / flat / not triggered / pending), result %, 3-day %, `scored_at`; per type: counts, mean result %, mean 3-day %; totals and first date.
Filtering by model / trigger / confidence is server-side (the phone has one saved copy only for offline).

## 5. Open points (PM proposals in the board, listed in the TD6 report)
Minimum 10 decided verdicts · rate denominator = decided (win + loss + flat; not triggered excluded) · result % base = top of the entry zone (as Q120a's Wait) · Hold / Avoid / Sell result = price move from the call price ·
the card line counts all models and triggers of that verdict type · offline keeps one saved copy · Q120a / Q120b still to confirm. Opus is manual and Sonnet automatic, so some filter pairs are empty (drawn).

## 6. Slop audit (ten tells)
Score **0/10**. Compare / Monitor / Learn surfaces, no hero, no stat tiles; no gradient, blur, glow, accent rail, icon topper or decorative dot; the bar marks a real split with the counts beside it; colour is on win / loss only and every chip carries words;
all numbers are labelled samples. Checked in Chromium: no overflow or clipped text at 393 dp in EN and ID, 1.0× and 1.3×, cards and table, both chip styles; the only scrolling strip is the filter row by design.
