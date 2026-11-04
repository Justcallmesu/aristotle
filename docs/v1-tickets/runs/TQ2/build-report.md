# TQ2 build report

Gate: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TQ2` → PASS (exit 0; lint, unit tests, assemble).

## Built
- `data/Quotes.kt`: `QuotePicker.pickAvoiding(quotes, avoid)`; `QuoteSource.nextInline()` never returns the launch quote of this process nor the previous inline one, and leaves `lastQuoteId` alone. `next()` records the launch id.
- `data/ChatQuoteTiming.kt` (pure): 300 ms delay before the quote, ≥1200 ms on screen once shown (Q59), `delayBeforeShow`, `holdRemaining`.
- `ui/quote/QuoteLoadingScreen.kt`: `QuoteLines` made internal; new `InlineQuote` (same lines/fade as launch, no icon, thin progress line, fills the transcript area).
- `ui/chat/ChatScreen.kt`: only the loading state. Optional `quotes: QuoteSource?` param (threaded through `ChatContentView`) and a `LoadingQuote` overlay in the transcript Box. Loaded in under 300 ms: nothing, the quiet line stays. Otherwise quote covers the area, holds to 1.2 s, fades out (400 ms, 0 on reduced motion) over the transcript. Not shown on load failure. `AristotleApp` passes `container.quotes`.
- Tests: `ChatQuoteTest` (no-repeat over 200 seeds × 30 picks, launch id untouched, fallbacks, delay/hold maths).

## Notes
- No strings added. Reuses the TQ1 quote asset, picker and fade; no change to `ui/settings`, `PcRecords`, `Compatibility`, `server`.
- Hold means the transcript is covered (not interactive) up to 1.2 s once a quote showed.
- "After a reconnect" shows a quote only if the feed goes back to not-loaded; I did not verify that happens. Not run on a device: fade, overlay timing and the cover are unverified visually; the Compose parts have no UI test.
