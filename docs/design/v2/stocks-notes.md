# Aristotle v2 — Stocks design notes (TD4, slice 1)

Board: `docs/design/v2/stocks.html` (self-contained, no network). Tweaks: home switch, watchlist rows, P/L colour, chart,
market state. Unchanged: palette A, Soft shapes, type, tonal elevation, motion (design.md, design-notes.md). All values on the
board are samples on real IDX rules (ticks 1/2/5/10/25, lot = 100, Thu sessions, 10 min delay, fees 0,15 / 0,25 %).
Numbers use id-ID grouping in both languages: `9.875`, `+1,28 %` (`NumberFormat.getInstance(Locale("id","ID"))`, U+2212 minus).

## 1. Picks for the user (board recommendation first)
1. Home switch: **segmented under the wordmark** (head 104 dp, +26) · tabs (+49) · title words (+0, drops the wordmark).
   On the wordmark's line it needs 404 of 393 dp, so that placement is out.
2. Watchlist rows: **two-line 64 dp** (code + name) · one-line 52 dp (code only).
3. P/L colour: ink only (design.md as written) · **green / red text** · IDX-style (coloured price, filled change, amber unchanged).
4. Chart: **Ink** (hollow up / filled down) · Colour (green / red candles + volume) · Bare (Ink, no grid).

## 2. Colour (needs a spec decision if 3 ≠ ink)
design.md §1/§7: red = errors only, no green. Green/red or IDX-style adds `StockColors` (CompositionLocal, not M3 slots):
| Token | Light | Dark | Contrast on surface / Low (L; D) |
|---|---|---|---|
| gain / loss / flat | `#1F6A2E` / `#B0281E` / `#7A5600` | `#8CD49A` / `#FFA99C` / `#E9C46A` | L 5.7–5.8 / 5.4; D 10.0–11.1 / 9.4–10.3 |
| gain/loss/flat container + on | `#CFE8C4`+`#0F3514`, `#F7D9D2`+`#5A110A`, `#F0E0B0`+`#3A2A00` | `#1F4A26`+`#C2EFC6`, `#7A2016`+`#FFDAD4`, `#4D3D0F`+`#F6E2A8` | L 10.4–10.6; D 7.9–8.2 |
Every change carries `arrow_drop_up/down` (or `remove` for 0) and a sign, so colour is never the only cue. Risks: loss red =
error red; gain has verdigris' lightness (different hue). Offline ("as of") drops all direction colour to `onSurfaceVariant`.

## 3. Screens → Compose
**Home head** (`SessionsScreen.HomeHead`): `Column(weight 1f) { wordmark; PcLine?; HomeModeSwitch }` + `ProfileAvatarButton`
unchanged. `HomeModeSwitch` = `SingleChoiceSegmentedButtonRow` (2 × `SegmentedButton`, min 96 dp, 40 dp, outer `shapes.small`,
selected `secondaryContainer`, M3 check icon). The mode is saved (DataStore) and restored. Chats keeps PC card + limits;
Stocks replaces both with the market line. `BottomAppBar` per mode: Stocks = News, Holdings, ⋮ (Broker fees, Settings),
FAB `add` → Add to watchlist.
**Market line** (`MarketStatusLine`): `titleSmall` state ("Session 1 · closes 12:00", "Session break · session 2 opens 13:30",
"Market closed · opens Fri 09:00") + `bodySmall` muted "Prices 10 min delayed · as of 10:42"; offline: cloud-off,
"Offline since 10:52", `TextButton` Retry now, "Prices as of 10:42. little-boy stopped answering." Text only, no dot.
**Watchlist** (`WatchRow` in grouped rows, `groupedRowShape`): code Mono 600 16, name `bodySmall` ellipsis, `Sparkline`
64 × 28 (Canvas: today's 5 m closes so far, dotted prev-close baseline), last `titleMedium` tnum, `ChangeText`
(12 sp 600 + 18 dp arrow). Tap → detail; long press → remove / reorder (not drawn). Compact: 52 dp, no name, % only.
**Holdings card** (`Surface(large, surfaceContainerLow)`, one button): "4 stocks · value", value 20 sp 600 tnum, P/L after
fees + Today. **News strip**: `LazyRow` of 272 dp cards like the live cards (meta 12, headline Literata 16/22 3 lines,
`TickerChip`s: `AssistChip(small)` Mono 600 13 + change %).
**Code search sheet** (`ModalBottomSheet`): FieldShape search, grouped result rows (code + name, + or "On watchlist").
**Detail** (`StockDetailScreen`): `TopAppBar` code Mono 600 18 + name; price 32/40 600 tnum, `ChangeText` 15 sp, as-of
line; `Readout` (fixed 44 dp: range summary, or the touched bar's time, C, change, O/H/L, volume lots); `PriceChart`
(Canvas, P3: 180 dp price + 40 dp volume, right axis 46 dp, dashed last-price line + `primary` tag, 1D on the full S1+S2
axis with the break and prev close marked); under it range `FilterChip`s 1D 5D 1M 6M 1Y (32 dp, small) + candles/line
icon toggle. Crosshair on long-press drag (`pointerInput` + `detectDragGesturesAfterLongPress`), haptic per bar, gone on
lift. Then "Today" card (`kgrid`: Open, Prev close, High, Low, Volume lots, 52-week range), holding card (lots · avg, Edit,
Value, Today, P/L after fees) or a row "You don't hold X · Add holding", related news rows, bottom `FilledTonalButton`
"Open in broker app" (`open_in_new`). Range → Yahoo interval: 5m / 30m / 1d / 1d / 1wk.
**Holdings** (`HoldingsScreen`): `TopAppBar` + subline "4 stocks · prices as of 10:42"; value 24/32, P/L after fees, Today,
one fee line; grouped rows by value (code, "20 lots · avg 9.150", value, P/L after fees with %); `ExtendedFloatingActionButton`
Add holding. **Holding sheet**: Stock (picked via the code search), Lots (`OutlinedTextField`, `shapes.small`, number
keyboard, support "1 lot = 100 shares · N shares"), Average price (prefix Rp; error if off-tick: "From 2.000 to 5.000
prices move in steps of 10. Try 4.370 or 4.380."), edit preview (`surfaceContainer` block: cost with buy fee, sold now less
sell fee, P/L), Cancel / Save (disabled while invalid), Remove (`error` text button, edit only).
P/L after fees = last × shares × (1 − sell) − avg × shares × (1 + buy); % of that cost. Today = (last − prev) × shares.
**News** (`NewsScreen`): `TopAppBar` + sources line; `FilterChip`s All / Watchlist / Holdings; day sections; item = meta,
headline `bodyLarge` (untranslated), ticker chips; item tap = Custom Tab, chip = detail.
**Empty / stale**: empty watchlist = `titleMedium` + one line + tonal "Add a stock"; no holdings = one grouped row with +;
offline = muted values, outline sparklines, arrows kept, avatar 38 %; closed = closing prices, next open in the line.

## 4. Data each field needs (TS0 / TS1)
Yahoo chart meta + bars: last, prev close, day O/H/L, volume, 52-week high/low, bars per range. RSS: source, title, time,
link. Not drawn because no source carries them: P/E, market cap, foreign flow, bid/offer. Open: code + name list for
search, ticker matching rules (code and names like "BCA"), holiday calendar (closed-day line), broker app to open (Settings).

## 5. Slop audit (ten tells)
Score **0/10**. Monitor (Stocks home) / Inspect (detail) / Operate (holdings) surfaces, no hero; no gradient, blur, glow,
accent rail, icon topper or decorative dot; the 32 sp price is the detail screen's subject, not a monument stat; numbers are
labelled samples. Fixed during review: caret box in the edit sheet, sparkline crammed into a third of its width, 1D
prev-close label under the candles, readout spans ("of 21 Nov"), empty state hiding ticker chips.
