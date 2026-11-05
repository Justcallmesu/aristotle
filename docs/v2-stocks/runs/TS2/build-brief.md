# TS2 — phone: Stocks (slice 1) — Home switch, Stocks home, watchlist, holdings, news, settings

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `PROJECT.md`, `docs/design.md` (binding), `docs/design/v2/design-notes.md`,
`docs/design/v2/stocks-notes.md` + open `docs/design/v2/stocks.html` (the picked board), `docs/v2-stocks/spec.md` (**Q84-Q95**, P1-P4),
`docs/protocol.md` **§20 Stocks** (your contract — the server is built and committed; read `server/internal/stocks/http.go` for exact JSON).
Pattern files: the PC status client + card (`data/PcStatus*.kt`, `ui/sessions/PcStatusCard.kt`) for an authenticated REST call over the pinned
HTTPS client; `ui/sessions/SessionsScreen.kt` (Home C head); `ui/components/GroupedRows.kt`; settings sub-pages (`ui/settings/**`).

## Do
1. **Home switch (Q92 title words):** "Chats  Stocks" replace the wordmark in Home C's head; the selected word is ink, the other muted; the
   switch is remembered; the profile avatar is unchanged; accessible as a tab row (role, selected state). Chats view = today's Home unchanged.
2. **Stocks home:** market line (state, session, "data ~10 min delayed · as of 10:42"); watchlist with two-line rows (Q93) + sparkline from
   `quote.spark`; holdings card (value, P/L after fees, today); news strip; add-to-watchlist sheet with search (`/v1/stocks/search`);
   reorder + remove; pull-to-refresh; refresh quotes every 60 s ONLY while visible, market open and online; stale/offline/closed/empty states
   per board.
3. **Holdings:** list + add/edit sheet (code search, lots, average price; validation as §20), delete with confirm, P/L breakdown.
4. **News feed:** All / Watchlist / Holdings filters, source + time + ticker chips, opens the link in the browser (Custom Tab if already used,
   else ACTION_VIEW).
5. **Settings › Stocks** (new category row): buy/sell fee % (`/v1/stocks/settings`), "Broker app: Ajaib" (Q94; hidden action when not installed;
   `<queries>` entry for `ajaib.co.id`).
6. **Colours (Q91):** add the stock up/down palette from stocks-notes (contrast table) to the theme as stock-only tokens; every change shows an
   arrow and a sign; add one line to `docs/design.md` recording the Q91 exception.
7. Tapping a stock opens a placeholder detail route `stock/{code}` (title + last price) — TS3 builds the real detail + chart there.
Money/number formatting: one helper — Rupiah with "." thousands, no decimals; percent with "," decimal and sign (`+1,25 %`) in ID and EN locale
as the board shows; lots as integers.
Pure tests: formatting, P/L display mapping, refresh-lifecycle rule (fake clock + market state), watchlist reorder, validation, switch persistence,
JSON parsing against fixtures copied from the server tests (`server/internal/stocks/testdata`, http_test expectations).

## Hard rules
Files: `android/**`, `docs/design.md` (one Q91 line), `CHANGELOG.md` [Unreleased] one Phone line, `docs/v2-stocks/runs/TS2/build-report.md`.
No server edits (report contract gaps instead). strings EN + ID, append-only. No new libraries. No commits; never git checkout/restore/stash/reset.
No adb; never connect to the real server or Hermes.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TS2` exits 0. Report ≤ 15 lines.
Stop and ask (in the report) if the board and §20 disagree on a field the screen needs.
