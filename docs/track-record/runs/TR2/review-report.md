# TR2 review: phone AI track record (fresh reviewer, deep)

Scope: uncommitted `android/**` on `c74df38` against committed §20.5 (`git show HEAD:docs/protocol.md`), spec Q117-Q125/P11/P14/P15/P16a, board and notes. TR1d only changes server-side give-up rules, not the answer shape. I read the code and did not run Gradle; the PM gate `pm-gate-tr2` is GREEN with 1406 tests and its mutations were killed. Subline widths measured with the app's Atkinson Next font in PIL.

## Verified
- Rates, `TrackRecord.kt`. Decided is set per type at :126-132: Buy/Add = win+loss+closed, Hold = win+loss+right, Avoid/Sell = right+wrong, Wait = 0. Hits are set at :134-140 and need 10 at :145. `no_session`+`unscorable` go only into `notScored` (:216), never into a rate. Filters sum the matching groups (:176, :203-222). Averages are `sum/n` over `result_n`/`info_3d_n` (:152, :155), and their test checks weighting against the mean of means (`TrackRecordTest`:191). The rule tests cover each verdict type, 9 vs 10, and Q123.
- Wiring. `TrackUiState.view()` (`TrackRecordViewModel.kt`:66-82) does all aggregation through `TrackRecords`/`TrackDisplay`. The composables only pick words, except `rateWords` (MINOR-4).
- Card line (Q124). `StockDetailState.recordLine` (`StockDetailViewModel.kt`:79) uses the whole unfiltered summary. For an unknown verdict or before the first read it returns null and no line is drawn (`AnalysisSection.kt`:115, :246). On an older server's 404, `loadTrack` (:208-213) swallows the failure, the card still renders, and Past calls draw nothing (`TrackParts.kt`:166). Tested in `StockDetailTrackTest`:100.
- `?code=` lifecycle. One read each time `active` turns true (:133) plus one when a run finishes (:198). No requests from composition, and the work is in the route ViewModel's scope. Collapse to 3 and Show N more / Show less is state kept in the ViewModel (:205, `PastCalls.view`). The "daily" marker is in the meta line (`TrackParts.kt`:210). Rows are not clickable.
- Navigation. `openTrackRecord` = `go(StocksTrackRecord)` (`StocksBackStack.kt`:56, :91). It is reachable from ⋮ (`AristotleApp.kt`:393) and from the card line (:465). The page has no forward link, so it cannot loop. `PairedPageTest` 8 -> 9 is justified: exactly one new `-> TrackRecordScreen(..., onBack = goBack)` line (:452). The new test (:218-227) covers both entries, Back and encode/decode.
- Builder deviations, all accepted:
  - (a) "through <date>": §20.5 has no first date and calls are capped at 50, so "since" cannot be computed.
  - (b) "N verdicts are scored after 17:00": `pending` is every unscored record, not only today's.
  - (c) Wait average from 10: the board's `agg` gates Wait the same way (`enough` with denominator n). Counting `would_have` is stricter and keeps `no_session`/`unscorable` out.
  - (e) No saved copy: P14 wins over the board.
- Strings. 95 `stocks_record_/past_/chip_` keys, EN = ID, every one used. The sheet's wording matches §20.5 (fill bar, gap below the stop, daily fallback).

## Findings
- MINOR-1, subline at 1.3x. `StocksTopBar` keeps the subline to `maxLines = 1` with ellipsis (`StocksPages.kt`:86). The board wraps it, and the build report says "no maxLines". Measured at 1.3x: "117 verdicts scored · through Thu 5 Nov" is 268.4 dp of 269 dp available. "9 verdicts scored · through Wed 30 Sep" is 271.5 dp, so it is cut, and the app's own text scale makes it worse. Fix: let this page's subline wrap (an optional `sublineMaxLines`). **Device check** at 1.3x × app text size, EN and ID.
- MINOR-2, false offline message while connecting. `view()` returns `Offline` for `LinkState.Connecting` (:67), so after a cold start or network switch the page says "Your PC is out of reach" until the link is up. Fix: show `Loading` for `Connecting`, as `BriefScreen` does at :191.
- MINOR-3, model filter repeats a family. The options are raw model ids (`TrackRecords.options`:192) but the labels are family names (`modelName`). Two Opus ids, e.g. after a default-model change, show two "Opus" entries that each filter half. Fix: give each entry the raw id when two share a family, or filter by family.
- MINOR-4, a rule in a composable. `rateWords` (`TrackRecordScreen.kt`:327-331) repeats `TrackRecords.line`'s enough/Wait/average fallback and is untested. Its edge case renders "Not enough calls yet · 12 of 10". Fix: one pure `TrackText.rate(record)` used by both.
- MINOR-5, filters outlive the visit. The ViewModel lives in the stocks store, so filters persist, and tapping a card line ("8 of 24") can open a page still filtered to other numbers. Fix: clear the filters when the page is entered from the card line, or decide that keeping them is intended.
- NIT:
  - The "· 50 %" rate suffix (:330) is not on the board and is undocumented.
  - The " · " join (:316) and `"$word $pct"` (`TrackParts.kt`:149) build text outside string resources.
  - `TrackDisplay.showsInfo` is used only by tests.
  - These tests guard nothing: the 52 % chip test (`TrackTextTest`:111-117 asserts its own formula), `assertNotNull(vm.uiState.value)` and a parser assert in a ViewModel test (`TrackRecordViewModelTest`:131, :167).
  - A refresh failure while a body is shown is silent.

No BLOCKER, no MAJOR.

VERDICT: APPROVE. Phone TR2 can be committed. MINOR-1 and MINOR-2 are worth a quick follow-up, and the 1.3x subline and chip wrap need a device check.
