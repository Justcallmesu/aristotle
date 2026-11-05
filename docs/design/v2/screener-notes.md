# Aristotle v2 — Screener design notes (TD5, slice 2)

Board: `docs/design/v2/screener.html` (self-contained, no network; source + `build.py` in `screener-src/`). Same frame, tokens, fonts and Tweaks
as `stocks.html`; nothing new in palette, shapes, type or motion. Data = labelled samples (Thu 5 Nov 2026) on real IDX rules (ticks, ARA 25 %,
10 min delay), fields from protocol §20.3 / TS4 §3-§5. Numbers id-ID: `2.480`, `+2,90 %`, `Vol ×2,3`. Tapping a row opens the existing stock page.

## 1. Picks for the user (recommended first; all are Tweaks)
1. Candidate rows: **two-line 64 dp** (code, name, last, change / labels, score) · one-line 52 dp (code, best label + "+N", last, change).
2. Setup labels: **tonal fill** (`surfaceContainerHigh`; bearish `lossContainer`) · outline (1 dp `outline`; bearish `loss` text + border).
3. Home section: **under the watchlist** (above Holdings) · first.
4. Universe: **Edit list button** (brief; opens the list sheet) · read-only "edit on the PC" (TS4's reading, no sheet to build).

## 2. Screens → Compose
**Stocks home** (`StocksHome`): section header = `.sec` row (`labelMedium` "Screener", muted "· scanned 10:45", `TextButton` "See all (6)"), then
`groupedRowShape` list of the top 3 `CandidateRow`s. Watchlist rows (`WatchRow`) add a label line (24 dp + 4 dp gap) only when a setup fired
today: best label, then "+N" (`labelMedium` muted); the row grows from 64 dp, no fixed height.
**CandidateRow** (min 64 dp, `groupedRowShape`, padding 16 × 7): line 1 = code (Mono 600 16, 46 dp), name (`bodySmall`, ellipsis, weight 1),
last (`titleMedium` tnum, min 48 dp), `ChangeText`. Line 2 = up to 3 `SetupLabel` + "+N", end: `bookmark_added` (watchlist) / `account_balance_wallet`
(held) 16 dp, "score 5,2" (`bodySmall`, `onSurfaceVariant`). Bearish flags rows: same, no score. Clickable = the whole row (`openStock`).
**SetupLabel**: not a chip, a `Box`/`Surface` 24 dp, `shapes.extraSmall` (4 dp, so it never reads as a 12 dp `FilterChip`), `labelMedium` 600 12, padding 8,
`surfaceContainerHigh` + `onSurface`; bearish = `StockColors.lossContainer` + `onLossContainer`. Text: `Breakout 20d`, `Vol ×2,3`, `MA20↑50 · Wed`
(arrow = 16 dp `arrow_drop_up/down` icon, bearish `arrow_drop_down`), `RSI 31`, `Gap +2,4 %` / `Gap −2,9 %`, `ARA` (neutral, first). Direction is in
the arrow or sign, never colour alone.
**Screener page** (`PairedPage.StocksScreener`): `TopAppBar` (back, title Literata 18, subline `bodySmall` "80 stocks · scanned 10:45 · next 11:00"),
actions gear (settings) and ⋮ (`DropdownMenu`: "Scan now", disabled with "in 1:20" while `429 too_soon`). Under it: `SingleChoiceSegmentedButtonRow`
Today | Last close (full width, 40 dp), `LazyRow` of 6 `FilterChip`s (All · Breakout · Volume · MA · RSI · Gap), `LazyColumn`: "Candidates · N" list,
"Your stocks · bearish · N" list (Q106: watchlist + holdings only) with one `bodySmall` line, footer (as-of, skipped counts, `TextButton` "Change in settings").
Pull-to-refresh = GET only. Last close auto-selected when the market is closed.
**Settings** (`ScreenerSettingsPage`, from the gear and Settings › Stocks): `LazyColumn` of grouped cards. Per setup `Switch` (M3) + `titleSmall` + one line
(`bodySmall`); when on, a 2-column grid of `NumberStepper` = `OutlinedTextField` (number keyboard, `shapes.small`, label above) with `−`/`+` `IconButton`s
(44 dp, disabled at the bound), range as supporting text; invalid → `isError`, "10 to 60 days", Save disabled. Gap also has `FilterChip` Gap up / Gap down.
Then "Liquidity" (Min value traded, Rp bn, 0 = off, "N stocks are under it today"), "Scan" (every N min, writes `screener.scan` job), "Universe"
(count, source, `valid_to`, `FilledTonalButton` "Edit list"). Bottom bar: `OutlinedButton` Defaults + `Button` Save (enabled when changed and valid,
`PUT /settings` + job PUT, 400 never sent). Edit list = `ModalBottomSheet`: period, multi-line mono field of codes, "Use bundled list" (DELETE), Cancel, Save.

## 3. States (all drawn; values muted via the existing `.stale` rule, arrows and signs kept)
| State | Home section | Page |
|---|---|---|
| closed | "last close Thu 5 Nov", See all, 3 EOD rows | Last close selected, subline names next scan, EOD setups only (no gap) |
| before first scan (`no_bars`) | "Building price history · first list after 16:20", no See all | cause + time + Screener settings button |
| Yahoo blocked | notice line, muted rows | `notice` "Yahoo refused the 10:45 scan. Showing the 10:30 list." + Scan now |
| no candidates | "No setups fired · 80 stocks scanned" | counts, setups on, floor, Screener settings button |
| PC offline | "List saved on this phone" | offline line + Retry now; Settings read-only (values dim, Save off) |
Specified, not drawn: session break "Session break · last scan 12:05"; partial "3 stocks could not be read" (`bodySmall` under the subline); expired list
"LQ45/IDX80 list ended 29 Jan 2027 · update it on the PC" (`errorContainer` banner on the settings Universe card, Edit list stays).

## 4. Field → source (§20.3)
Code, name, price, change_pct, in_watchlist, held, at_ara, score, setups[id, dir, first_at, values]; `scan.last_run`/`next_run`/`error`/`codes_failed`;
`skipped.illiquid`/`no_bars`; `universe.count/valid_to/expired`; `data_as_of` (footer). The board's `val20` is only the sort tiebreak. Setup text is built on
the phone from `values`. Not in the contract: scan-interval range (board uses 5-60 min, step 5; needs the job's real limits).

## 5. Open points
Scan now menu has one item (kept as TS4 says); "Edit list" on the phone goes beyond TS4's read-only proposal (pick 4). Gap-down on a watchlist row shows a label,
no sell wording: the board says "A flag is not advice to sell".

## 6. Slop audit (ten tells)
Score **0/10**. Monitor + Configure surfaces, no hero or stat tiles; no gradient, blur, glow, accent rail or icon topper (the only gradient is a text-fade on the
list sheet); the only coloured marks are loss labels on bearish rows; every number is a labelled sample. Fixed in review: filter chips and the holdings card
lost their CSS in the trim, labels scaled for 12 dp chips.
