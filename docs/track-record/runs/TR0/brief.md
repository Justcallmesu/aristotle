# TR0 — research (read-only): what the track-record scorer can rely on

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/track-record/spec.md` (Q116-Q120, readings
Q120a/b, assumptions A1/A2), `docs/protocol.md` §20-§20.4, and the code: `server/internal/stocks/{analyzer,verdict,bars,yahoo,provider,
idxcal,stocks}.go`.

## Answer, numbered, each VERIFIED (file:line or probe output) or ASSUMED (what would confirm it)
1. **Past-session 5-minute bars (A1, Q119):** can the existing Yahoo client fetch 5-minute bars for a given PAST IDX session (e.g. 1, 5,
   20, 40 trading days back)? Request shape through `provider.go`/`yahoo.go` (range/interval or period1/period2), how far back it works,
   whether the bars cover 09:00-16:00 WIB incl. the session break, and how a missing day looks. **Probe live, at most 8 requests total,
   ≥ 2 s apart, through the server's own client code path (same User-Agent/headers; a tiny Go test or `go run` in your scratch dir that
   imports the package, not curl).** Never touch port 8760 or the real state dir.
2. **Ordering rule feasibility:** with those bars, can the scorer tell entry-then-target vs entry-then-stop vs target-before-entry
   within one 5-minute bar? Propose the exact rule for a bar that touches two levels (conservative = stop first, per Q119).
3. **`session_date` (Q120b):** what `AnalysisResult.session_date` holds for a call made before 09:00, during the session, during the
   break, after 16:00, on a weekend/holiday. Cite the code that sets it.
4. **Bars coverage (A2):** can Analyze run on a code that is NOT in the bar store's quoted set? If so, how should the scorer get its
   daily bars (existing chart fetch path + cache) and what does it cost per day.
5. **Kept results today:** shape and count of `stocks-analyses.json` entries that could seed the history; which fields are missing for
   scoring (e.g. results kept before TBA1 have no `trigger`).
6. **Size:** bytes per compact history record and per year at ~10 calls/day; recommend a cap or none.
7. **Scheduler hook:** where a scoring step fits after `bars.daily` (16:20) — same job or its own job registered with `TradingDays: true`;
   what catch-up rule (Q31) fits.
8. Anything in the spec that the code contradicts.

## Rules
Read-only: write ONLY `docs/track-record/runs/TR0/report.md` (≤ 60 lines, numbered as above). No repo edits, no commits, never
`git checkout/restore/stash/reset`. No Hermes calls. Avoid `bash -c`, heredocs and chained `$(...)`: script files in your scratch dir
run with plain `bash <file>`. Another worker is building a design board under `docs/design/` in parallel: not yours.
