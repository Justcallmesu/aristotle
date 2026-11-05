# TS2 review — phone: Stocks slice 1 (fresh reviewer, read-only; no gradle/adb/server)

**Checked OK:**
- Fields: `StocksModels.kt:11-154` matches `provider.go:41-57`, `http.go:151-177,435-449`, `idxcal.go:69-74`, `news.go:59-62`, `universe.go:78-80` and `state.go:52-53` (names, types, nullability). `spark:null` reads as an empty list via `coerceInputValues` (`RpcFrames.kt:18-21`), with a real fixture in `quotes.json`.
- Auth and logs: Bearer device token over the reused pinned `clientFor` (`ServerClient.kt:123-124,226-240`), no new TLS path, no logging in the stocks code.
- Polling: every 60 s only while visible + Online + market open, plus one read at `next_open` (`QuoteRefresh.kt:25-33`, `StocksViewModel.kt:122-131`). It stops on pause or dispose (`StocksHome.kt:111-114`). The PC card polls in Chats only (`chatsShown`).
- Formatting: `Rp 4.917.675`, `+1,86 %`, U+2212 minus and NBSP, the same in ID and EN.
- P/L preview: `HoldingInput.kt:18-31` matches `pl.go` exactly (fee in ppm, average in centi-IDR, one half-away rounding).
- Validation matches §20: lots 1..10 M whole; average 0.01..10 M with ≤ 2 decimals; fees 0..5.
- Q91: palette only through `changeColor` (`StockParts.kt:45-49`); arrow and sign always drawn, muted states drop only the colour.
- Q92: `Role.Tab` + `selectableGroup`, saved in `AppSettings.homeMode`.
- Ajaib: `<queries>` in the manifest; Open is hidden without a launch intent (`StocksSettings.kt:121-141`).
- News links: http/https only (`webTarget`, full regex match), sent as `ACTION_VIEW` + BROWSABLE (`StocksText.kt:66-75`).
- Strings: 121 new keys, EN and ID match, append-only. `design.md:247` has the Q91 line; the CHANGELOG has one Phone line.

**MAJOR-1 — Stocks need the Hermes relay to be Online (the builder's note). This is a design flaw.**
- **Where:** `StocksViewModel.kt:90,124,392-395` accept only `LinkState.Online`.
- **Why:** §20/P2 need only the server plus the token. In HermesDown (4503) or HermesUpdated (4505) the server has just answered at a known address (`LinkState.kt:102-106`), but that address is dropped (`:40,43,73-74`). Stocks then stay empty or muted, and the copy is false: "Prices need the link to your PC" (`strings.xml:890,915`). Q31 only accepts "PC off = no data". HermesUpdated waits for the user, so stocks stay dead until the user acts.
- **Fix (small, about half a day; no server change):** carry the endpoint in `RoundEnd`/`LinkState.HermesDown` and `HermesUpdated` (`LinkManager.kt:228-236`, about 12 test sites). Add a `LinkState.serverEndpoint` helper and use it in `api()`, `active` and `online`. Write a market line for these states and add 2 VM tests. About 40-60 lines.

**MINOR**
- m1: Stocks mode skips Home's connection pages (`SessionsScreen.kt:250-256`): no CertificateChanged/HermesDown/HermesUpdated reason, only "Offline since…", and the saved mode can keep the user there. Show the reason in `MarketStatusLine` as part of MAJOR-1.
- m2: Holdings and News load once (`StocksPages.kt:93,210`, `LaunchedEffect(viewModel)`). If opened while not Online, they stay on "Loading…" until a pull to refresh. Key the effect on `state.online`.
- m3: In the preview block on `surfaceContainer` (`StocksSheets.kt:364`), light gain/loss text measures 5.12/5.11:1 (my computation). That is under Q91's ≥ 5.4:1 but still passes AA. Use `surfaceContainerLow`.
- m4: Title words use `heightIn(min = 48.dp)` (`StockDetailScreen.kt:52`) against a 36 sp wordmark line, about 12 dp taller, against Q92 "no extra height". Use the Q67 pattern. `HomeModeSwitch` also belongs in another file.
- nit: The preview uses default fees until the fees load (`StocksSheets.kt:79`; the % is shown). `noteRead` does not move a loop that is already sleeping (`QuoteRefresh.kt:58-61`), so one extra read can follow a pull to refresh.
- Builder's off-tick ASK: following §20 is right. A non-blocking hint is a later PM call.

Not verified by me: on-device layout (no adb); the gate (the PM runs it; `gate-summary.txt` says PASS android).
VERDICT: CHANGES-REQUIRED
