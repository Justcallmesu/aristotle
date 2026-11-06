# TR2b review: phone minors from the TR2 review (fresh reviewer, deep)

Scope: uncommitted `android/**` on `256b80e` (12 modified files and the new `TrackSublineFitTest`). The server diff is out of scope. I read the code against the TR2 review, the build brief and report, spec Q121-Q124 and P14, board notes and §20.5. I did not run Gradle: `pm-gate-tr2b` is GREEN (1414 tests) and both PM mutations were killed. I checked the Compose `ui-unit` 1.12.1 bytecode to see how the test converts sp to dp.

## Verified
- MINOR-1 closed. `TrackSubline` (`TrackRecordScreen.kt`:159) has no maxLines, and the top bar gets `subline = null` (:97). `TrackSublineFitTest` follows the screen's text path:
  - the real `atkinson_next_regular.ttf`;
  - bodySmall 12 sp × the app's text size (the largest is 1.15, the same factor `TextStyle.scaled` uses);
  - `Density(1, 1.3).toDp`, which runs `FontScaleConverterFactory`, so the system scale is the real non-linear one;
  - width 393 − 2×16 (`listGutter` = 16).
  It simplifies two things: AWT ignores `tnum` and kerning, and a ×1.05 margin covers both. The 1.15× "two lines" check guards nothing on its own, because a line that wraps is never cut. The real guard is the source test (no maxLines or TextOverflow, not in the top bar).
- MINOR-3 closed. `TrackFilters.matches` and `options` go through `TrackRecords.family`. Haiku, `gpt-x` and an empty id go under Other, with tests. Chips come from the groups the server sent, so no family chip appears with zero records. The Q124 card line (`StockDetailViewModel.kt`:79) still reads the unfiltered summary.
- MINOR-4 closed. `TrackText.rate` is built on `TrackRecords.line(record)`, which the card line uses too (`line(groups, v)` calls it). `rateWords` and the undocumented "· 50 %" are gone, and the tests assert concrete words.
- MINOR-5 closed. `trackRecordViewModel` resolves to the same store owner (`stocks-<pcId>`) on the stock page and on the page, so it is the same instance. Creating it early starts no read, because `visible` is false. The card-line lambda calls `opened(CardLine)` (`AristotleApp.kt`:467), and the ⋮ lambda (:394) does not. The source test does guard something: removing the call, or adding it to ⋮, fails one of its two regexes. It does not tie the call to that particular lambda; that is acceptable.
- Skipped NITs: I agree with all three. The " · " join and "word pct" are separators that read the same in both languages; the silent refresh failure is a behaviour change; the 52 % chip test needs a layout seam.
- No regression of the TR2 verified list: rates, decided, Q122/Q123, wiring, `?code=`, navigation, strings (3 new keys, EN = ID).

## Findings
- MINOR-1, the body still flickers on a reconnect (attack 2). `view()` returns `Loading` for `Connecting` even when a body is held (`TrackRecordViewModel.kt`:71), and the test pins this (`TrackRecordViewModelTest`:80). On a network switch (`LinkManager.kt`:238, `RoundEnd.Switch` → `Connecting`) the page goes body → "Loading…" → body and loses its scroll position. The comment "the other stock pages do the same" (:70) is wrong:
  - Brief (`BriefScreen.kt`:190-192), Holdings (`StocksPages.kt`:130) and StockDetail (:176) keep the data they hold while unreachable, and with no data they show "Shows once your PC is reachable".
  - Only Sessions has a connecting body (`SessionsScreen.kt`:327), and it keeps its items.
  The false "out of reach" is fixed and this is not a regression (TR2 flashed Offline). Fix: for `Connecting`, show the body if one is held, otherwise Loading. P14 only covers Offline. Update the test.
- NIT:
  - The `TrackSubline` KDoc says "under the filters", but the item sits above them (`TrackRecordScreen.kt`:106 vs :132).
  - Removing `showsInfo` left an orphan KDoc (`TrackDisplay.kt`:47).
  - `TrackFilters` has two stacked KDocs, and only the second one attaches (`TrackRecord.kt`:94-95).
  - `VerdictRecord.ratePct` is now used only by tests, the same kind of NIT as `showsInfo` was.
  - `TrackEntry.Menu` is never passed in production.
  - The `TrackRecordTest` test "the page's record line is the card line over one record" compares `line(sum)` with `line(sum)`, so it guards nothing.

No BLOCKER, no MAJOR.

VERDICT: APPROVE. Phone TR2b can be committed. The Connecting-with-body flicker (MINOR-1) is a small follow-up. The 1.3× subline still needs a device check (no adb in this run).
