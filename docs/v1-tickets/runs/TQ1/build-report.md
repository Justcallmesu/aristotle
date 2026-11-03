# TQ1 build report

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TQ1` → exit 0 (lintDebug, testDebugUnitTest, assembleDebug PASS).

## Built
- `data/Quotes.kt`: `Quote`, `QuoteParser` (broken asset → empty list, no crash), `QuotePicker` (random, never `lastId`), `QuoteSource` (parse once, last id kept in `AppSettings.lastQuoteId`).
- `data/QuoteGate.kt`: pure when-to-show state machine (`GateInput` → `GateOutput`), 1.2 s minimum, unpaired gives way at once, no cover once content was shown; `QuoteMotion` (400 ms fade, 350 ms stagger, reduced = animator scale 0).
- `ui/quote/QuoteLoadingScreen.kt`: static icon tile (launcher foreground on `ic_launcher_background`), English (Literata) → Greek small → source + translator, fading in line by line; verdigris `tertiary` indeterminate line at the bottom (static 40 % bar when reduced). TalkBack reads English + citation only (`clearAndSetSemantics`). Quote null → icon + line only.
- Wiring: `AristotleApp` (`QuoteGateHost`) and `AppContainer` (`quotes`, `quoteGate`, background preload). New `loading_progress` string (en, id). New text styles in `Type.kt`.
- `app/build.gradle.kts`: `copyQuotes` Copy task (`docs/quotes.json` → `build/generated/quote-assets`, wired to `preBuild`) added as an assets srcDir. APK contains `assets/quotes.json`; no duplicate in the repo.
- Tests: `QuotesTest` (real 24 parse, picker, reachability, broken asset), `QuoteGateTest`, `QuoteMotionTest`.

## Decisions / notes
- "Reconnecting" = `LinkState.Idle`/`Connecting` (the only no-answer-yet states). `Offline` is definitive: the list and banner take over; Offline never shows the quote.
- Content counts as shown once the screen hands over to a paired page; later reconnects use the banner. A re-pair after unpair does not re-show the quote.
- No system-splash change: the launch theme already paints the window colour, and no splash library was added.
- Not run on a device: fade timing, Literata Greek glyphs and TalkBack are unverified visually. The Compose screen has no UI test.
- `docs/quotes.*` untouched; nothing installed or committed.
