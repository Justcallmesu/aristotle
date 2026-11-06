# TBA1c build report
- **MINOR-1** brief.go `priceLike`/`yearContext` (~:776-905): a year 1990-2099 passes after tahun/thn/year/in/since/by/until/sepanjang/akhir/awal/hingga/sejak/pada/outlook/H1/H2/FY/Q1-Q4/month (needs a space), after `semester|kuartal|quarter I-IV`, before tahun/thn/year, with `F`/`E` suffix; full dates `d/m/yyyy` and `yyyy-mm-dd` are blanked first (`blankDates`); `MA|EMA|SMA|RSI` + space + 2-3 digits passes. Prompt (brief.go ~:636) states the rule in 2 lines.
- **MINOR-2** (a) hyphen form only as a full date, `2010-2050` refused (the old `-<digit>` rule is gone). (b) `gluedOK`: glued letters pass only for `MA|EMA|SMA|RSI|LQ|IDX|Q|H` + 1-3 digits and `FY` + a year; `level2050`, `RSI2050`, `FY12345` are refused.
- **NIT-1** protocol.md §20.4: POST order (body first, `lang` kept on refusal), auto runs off while the alerts file is unusable or a counter save fails, `model_error` counts to the cap, no-prices rule.
- **NIT-2** alerts.go: `saveLocked` returns its error; a failed counter save in `maybeAutoRun` sets `persist=false`, `noRuns=true`, logs once (`logRunsOff`).
- **NIT-3** `analyzer.deleteSession` (analyzer.go; used by analyze, modelMismatch, brief `make`) and `briefer.failJob` (brief.go; both `runJob` failure blocks). Same log messages.
- **NIT-4** screener.go `installLocked`/`upgradeHitsLocked`: the upgraded hit takes the firing run's `score` and `mode` (`hitFire`).
- **Tests** (tba1_test.go, all RED first on a scratch copy of the pre-fix brief/alerts/analyzer/screener): `TestPriceLikeYearRules` (all review-report probes, new cases, the 10 required prices), `TestBriefPromptStatesYearRule`, `TestAutoRunsStopWhenCounterSaveFails` (dir in place of the file after a good load), `TestUpgradedHitTakesFiringScoreAndMode`.
- **Changed test:** `TestPriceLikeTable` expected `pada 2050` refused; the brief lists `pada` as a year word, so it now passes. Trade-off: "pada 2050" as a price now slips through (so do `in 2050`, `by 2050`); none of the required price forms conflicts.
- **Gate:** `GATE_ONLY=server gates.sh` exit 0 (gofmt/vet/test PASS, go-tests passed=664 failed=0); `go test -race -count=3 ./internal/stocks/... ./internal/hermes/...` ok.
- **Files:** server/internal/stocks/{brief,alerts,analyzer,screener}.go, tba1_test.go; docs/protocol.md; this report. Nothing not done; no android/CHANGELOG touched.
- **Doc text:** none (no user-visible change besides fewer repair turns).

## TBA1c2 — remove ambiguous year words from priceLike

Removed `pada`, `in`, `by`, `hingga`, `until` from `yearBeforeWords` (brief.go ~:846); prompt rule updated (brief.go ~:634). Tests: `TestPriceLikeTable`, `TestPriceLikeYearRules` (now refuse 10 ambiguous cases: `pada 2050`, `hingga 2050`, `in 2050`, etc.; still pass `sejak 2020`, `sepanjang 2026`, `akhir 2026`, `tahun 2026`). Gate: PASS (gofmt/vet/test, 664 tests).

## TBA1c3 — spec P9 in priceLike (last tuning round)

- brief.go: `yearBeforeWords` keeps only `tahun|thn|year`, month names, `Q1-4/H1/H2/FY` (semester/kuartal I-IV, `2026F/E`, dates, before-`tahun` as before); `akhir|awal|sejak|since|sepanjang|outlook` removed. `nonASCIIDigits` refuses any non-ASCII digit; `spacedGroups` joins digit groups split by space (3-digit groups, 1-3 digit start), NBSP, U+202F, U+2009 or `'` and refuses a joined value > 99 (`Rp 2 050`, `10 000`, `2'050`; `Q3 2026` untouched).
- Prompt RULES line states the accepted forms and "write a year as `tahun 2026`" (`akhir tahun 2026`, `sejak tahun 2020`). protocol.md §20.4 no-prices paragraph rewritten to match (word list, non-ASCII, split numbers, repair log), stranded "(EN/ID)" fixed.
- Repair log: `stocks: brief repair` with `reasons=price_like|schema` (`repairReasons`; codes only, no text or number).
- Tests (tba1_test.go): new `TestPriceLikeP9`, `TestBriefPromptP9`, `TestRepairReasonsAreCodesOnly`, repair-log check in `TestBriefAt0830`; changed `TestPriceLikeTable`/`TestPriceLikeYearRules` (`akhir|awal|sejak|since|sepanjang|outlook YYYY` now refused, `akhir tahun 2026`/`sejak tahun 2020` pass). RED first on a scratch copy of the pre-fix brief.go: P9, prompt, repair-log, Table and YearRules tests failed; `TestRepairReasonsAreCodesOnly` only against a stub (the func is new).
- Gate: `GATE_ONLY=server gates.sh` PASS (gofmt/vet/test, 667 passed, 0 failed); `go test -race -count=2 ./internal/stocks/...` ok. Known gap: `Q3<NBSP>2026` is still refused (year word detection needs an ASCII space). No android/CHANGELOG touched.
