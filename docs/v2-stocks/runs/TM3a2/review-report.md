# TM3a2 re-review (uncommitted fix round on 8fff8da). Fresh reviewer, static, 2026-11-05
Read: review-TM3, the build brief and report, `git diff HEAD -- android`, the new StocksBackStack.kt and ChartHeightTest.kt, and the pm-gate-tm3a2 log (GREEN). I ran no gradle and no adb. I used javap on runtime-saveable 1.12.1 and parsed the hhea/OS-2 tables of atkinson_next_regular.ttf.

**MAJOR-1 closed.** `StocksBackStack` is a real stack. A step from a stocks page pushes that page. A step from any other page starts empty, so a stale `stockStack` is ignored. `back()` pops one page, and an empty stack lands on Home.
- Paths checked by hand: Home→News→A→All news→B→…; Holdings→stock→Holdings→Home; a stock replacing a stock. All unwind one screen per Back and reach Home in at most MAX_DEPTH+1 steps (cap 16).
- System back and every arrow go through one `goBack`. Holdings, News, StockRoute and Running are wired to it, and a source-guard test checks that.
- No regression for the Chats pages:
  - Every page outside PAGES still uses `backFrom(runningFrom)`, the same as before (`newsFrom` only ever affected StocksNews).
  - Running opened from a chat's "See all" still goes back to the chat on both back paths (TY7).
  - The other pages keep `page.back`.
- Process death: the saver is safe. In 1.12.1, `listSaver` saves an empty list as null, and `mutableStateSaver` then saves nothing, so a restore runs the init and gets `emptyList()`, never a null List. `decode` drops unknown pages and a StockDetail without a code.
- Notification entry: it goes to Chat, then Home. The stale stack is unused.
- Home mode switch: it happens on Settings/Home, so the stack is not involved.

**MAJOR-2 closed.** `chartHeight()` measures with `AristotleTextStyles.chartAxis` and `LocalDensity.current`. `PriceChart` draws with the same `axisStyle` and the same density (`rememberTextMeasurer` captures LocalDensity), in the same composition. `StockDetailScreen`'s placeholder uses the same `chartHeight()`.
- Box = max(250dp, labelTop + measured line + 1px). Modifier.height rounds by at most 0.5px, so a label of exactly the measured height always passes `fitsBelow`.
- Single-line height does not depend on the glyphs (Latin and digits only, no fallback font). The `remember` keys (measurer, style, density) re-measure when the app text size or the font scale changes. The bundled fonts load in Blocking mode.

**Fake-measurer inputs are faithful:**
- The real `AristotleStyles(scale)` style.
- A real `Density`, whose non-linear converter is asserted (14sp at 1.3x = 18.8dp, so the gate proves it is active).
- Atkinson metrics: hhea = typo = 984/−316 per 1000 em (1.3 em), checked.
- Small drift: Android rounds ascent and descent to whole px separately (up to +1px against the fake's single ceil). Compose's Trim and lineHeight handling is not modelled. This does not matter on the screen, because both the box and the drop rule use the same real measurement.

**MINOR-A (test gap, no round).** ChartHeightTest never runs `chartHeight()` or a TextMeasurer, so a regression in the real measuring path would pass. Examples: measuring with another style or without the app scale, passing `Density(density.density)` without the font scale, or a stale `remember` key. Cheap guard: a source-guard test like PairedPageTest's, or a Robolectric/instrumented check later.

**MINOR-B (wiring tests, no round).** `show`/`stocksStack` in AristotleApp are covered only by the source guard. Any future deep entry onto a stocks page, such as a stock-alert notification, must go through `show(StocksBackStack(Sessions).openStock(code))`. A plain `page = StockDetail` would reuse a stale `stockStack`. Back from there still ends on Home, but through old pages.

**Slot MINOR closed.** Only on-schedule bars advance `lastOnSchedule`; off-schedule bars take `max(slotOf, previous)`.
- Friday (09:00–11:30 = 30 slots, session 2 from 14:00): 08:55→0, 09:00→0, 11:30→29, 14:00→30; 55 slots in total.
- Mon–Thu: 08:50/08:55/09:00/09:05/13:30 → [0,0,0,1,36], 67 slots. Two 09:00 bars → [0,1].
- Positions are monotone, and 12:00 still shares the 11:55 slot.

VERDICT: PASS. MAJOR-1, MAJOR-2 and the slot MINOR are closed. Two optional test-gap MINORs (A, B); no BLOCKER/MAJOR, so no further round.
