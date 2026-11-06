# v2 stocks — tickets

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| TS0 | Research: data sources for slice 1 (ticker search, LQ45/IDX80, Yahoo limits, RSS, holidays, tick sizes) | - | docs | DONE |
| TD4 | Design board: Home Chats\|Stocks switch, watchlist, stock detail + chart, holdings, news | - | design | DONE |
| TS1 | Server: stocks module (statefile watchlist/holdings/fees, Yahoo provider, RSS news, `/v1/stocks/*`) | TS0 | server | DONE |
| TS1b | Server minors from review: news Close() marks closed (no poller restart after shutdown); backoff on Yahoo 5xx; name-fallback bypass of the word-code rule (CASH); image_url http(s) only; chart cache eviction; zero-cost guard | TS1 | server | TODO |
| TS2 | Phone: Home switch (title words), Stocks home, watchlist, holdings, news, Settings › Stocks, stock palette | TD4, TS1 | android | DONE |
| TS2b | TS2 fix round: stocks independent of Hermes (MAJOR-1), connection notice on Stocks, reload on reconnect, preview contrast, switch height | TS2 | android | DONE |
| TS3 | Phone: stock detail + chart (Canvas candles/volume/line, ranges, crosshair), Open in Ajaib | TS2 | android | DONE |
| TA0 | Research: running a stock analysis in Hermes from the server (methods, no-tools mode, profile setup, prompt + JSON verdict, contract, indicators) | - | docs | DONE |
| TA1 | Server: indicators + analysis job (`/v1/stocks/analyze`), verdict validation, kept last result | TA0 | server | DONE |
| TA1b | Analyzer hardening (TA1 review): untrusted-data instruction + flag news-only reasons (MINOR-1); record session id before any post-create failure (MINOR-2); sweep continues past errors + first-real-run check of cross-socket delete (MINOR-3); nits (refusal codes, hold target check, shutdown race, failed_at parse, title caps, duplicate keys) | TA1 | server | DONE (review folded into TA2's) |
| TA2 | Phone: Analyze button + verdict card on the stock page | TA1, TS3 | android | DONE |
| TS3b | TS3 minors: stale long-range bars need Retry/refetch; 60 s refresh must not cut a crosshair hold; exclude chart from system back-gesture edge; 1.3x font overflow (device check); 1D slot for bars at 12:00/11:30/pre-09:00; Back from All news -> stock; single-bar line; Add holding before load; Ajaib uninstall feedback | TS3 | android | TODO |
| TA2b | Analyzer minors: news_only cap dodged by one indicator citation (backstop only); misleading create log; sweep timeout stops the pass; 'From <day>' should name the session; stale start error after a later done read | TA2 | server+android | TODO |
| TM3s | Server minors: TS1b + TA2b server part | TS1, TA2 | server | DONE |
| TM3a | Android minors: TS3b + TA2b android part | TS3, TA2 | android | DONE (fix round TM3a2) |
| TSC0 | Design: screener (slice 2) + 08:30 brief/alerts (slice 3) | TA2 | docs | DONE |
| TM3a2 | Fix round for TM3a: Back-from-news loop (MAJOR-1), time labels vanish at large text (MAJOR-2), pre-09:00 slot | TM3a | android | DONE |
| TSC1 | Server: scheduler (wall-clock 30 s, catch-up Q31), bar store `stocks-bars.json` (P8), universe override, jobs API | TSC0 | server | DONE (fixed in TSC1b/c) |
| TSC1b | Fix round for TSC1: jobs PUT data race (MAJOR), persist interval slot, catch-up once, freshness rule, bars time 16:15+, 180-code cap | TSC1 | server | DONE |
| TSC1c | TSC1b's race test passes on the old code (PM check): make it RED on d2e1255, GREEN now | TSC1b | server | DONE |
| TSC2 | Server: five setups + scan + `/v1/stocks/screener` | TSC1, Q106/Q111 | server | DONE (8fdba61) |
| TSC3 | Phone: screener section/page/settings (+ TM3a2 review MINOR-A: source check that chartHeight uses the Canvas style/density; MINOR-B: any deep link into a stock page goes through show(...)) | TSC2 | android | DONE (with TSC3b, b96e305) |
| TBA1 | Server: alerts + long-poll + auto runs + 08:30 brief (+ TSC2 review minors 1-5) | TSC2, Q104/Q107-Q110 | server | DONE (with TBA1b) |
| TBA1b | TBA1 fix round: weekend/holiday brief test (MAJOR-1), priceLike holes, rerank->scan upgrade, no auto runs without a persisted cap, brief retries only before submit, nits 1-4 | TBA1 | server | DONE (re-review PASS-WITH-FINDINGS) |
| TBA1c | Brief no-prices rule: year words the prompt invites ("sepanjang 2026", "H1 2026", "kuartal III", dates) refused -> extra Opus repair turns; year ranges / glued letters pass; prompt states the real rule; §20.4 nits; runtime save failure turns auto runs off; 2 duplicated blocks; upgraded hit keeps rerank score (TBA1b review MINOR-1/2 + NITs) | TBA1b | server | TODO (next) |
| TSC3b | TSC3 fix round: bearish flags hidden on own rows (MAJOR-1, Q106) + refresh after 16:00, cooldown, mode switch, error follow | TSC3 | android | DONE |
| TBA2 | Phone: alerts watcher + notifications, brief card/page, holding stop/target, auto-run status (absorbed the planned TBA3a/TBA3b; the brief server part went into TBA1) | TBA1 | android | REVIEWED: CHANGES-REQUIRED (MAJOR-1 brief day chips) -> TBA2b; held uncommitted |
| TBA2b | TBA2 fix round: brief chips by date (MAJOR-1), Q115 card states (MINOR-2), unpair race, pure Q109 target + tests, one heads-up per code, job time on page, nits | TBA2 | android | BUILDING (2026-11-06) |
| TM3 | Run B delta: news_only counts ind/lvl citations only (hold:* no longer lifts the cap) | TM3s | server | DONE (8fdba61) |
| TM4 | Run B delta: back-gesture exclusion = 48 dp edge strips (was whole chart); readout height measured from styles | TM3a2 | android | DONE (8fdba61) |
| TS4 | Screener research refining TSC0 (live Yahoo probe: daily-bar open for gaps, no gap prefilter, pace volume by market time, spark summary only, separate stocks-screener.json, Rp 5 bn liquidity floor) | TSC0 | docs | DONE |
| TD5 | Design board: screener section, page, candidate row, settings, states | TS4 | design | DONE (Q112 PM reading) |

## Log
- 2026-11-05 v2 grill: Q84-Q90 (all A). Yahoo + RSS re-probed 200. TS0 + TD4 dispatched in parallel.
- 2026-11-05 TS0 done (Yahoo needs browser UA; spark for quotes; kontan feed URL corrected in docs/spec.md §6). TD4 board done; source saved to docs/design/v2/stocks-src/; opened on the phone.
- 2026-11-05 Board picks Q91-Q94 asked; user away (clarify timed out 60 min). TS1 (server, independent of the picks) dispatched; Android tickets wait for the picks.
- 2026-11-05 TS1 built; pm-gate-ts1 GREEN (480 Go); mutations sell-fee sign 7 fail, Friday S1 end 3 fail. Deep review dispatched.
- 2026-11-05 TS1 review PASS-WITH-FINDINGS (no BLOCKER/MAJOR; -race clean). Committed. Minors -> TS1b (folded into the next server touch).
- 2026-11-05 User picks Q91 A, Q92 title words, Q93 A, Q94 Ajaib, Q95 yes. Real server installed as a user service (0.0.0-dev+6bf72c4; listens 192.168.1.61, 100.74.61.96, fd7a::…:8760). TS2 dispatched.
- 2026-11-05 TS2 built; pm-gate-ts2 GREEN (1162). Mutation 'poll while market closed': first attempt broke compile (not counted), redo compiled -> 2 fail. Installed on the phone. Deep review running. Open: tick-check hint on holdings avg (asked user); stocks gated on the Hermes link (reviewer sizing).
- 2026-11-05 TS2 review CHANGES-REQUIRED (MAJOR-1: stocks gated on the Hermes link). TS2b dispatched with m1-m4. Off-tick average stays allowed (§20).
- 2026-11-05 TS2b re-review: only finding was the PM's own in-flight mutation (LinkState.kt:57, restored; reviewer started too early) -> void; all else closed, chat/relay unchanged. pm-gate-ts2b GREEN (1169) after the last edit; mutation HermesDown->null 4 fail. Installed. TS2+TS2b committed.
- 2026-11-05 User asks for an emiten analyzer -> Q96-Q100 (all A: day trade, PC-computed numbers + news, Buy/Wait/Avoid & Add/Hold/Sell, Analyze button with kept result, build next). Profile aristotle-stocks does not exist (user creates it). TA0 research dispatched in parallel with TS3.
- 2026-11-05 TA0 done. Q101 phone language; Q102 PM created profile aristotle-stocks (no tools; smoke 'OK/none'); Q103 Opus. P5-P7 recorded. TA1 dispatched (server) in parallel with TS3 (android).
- 2026-11-05 TS3 built; pm-gate-ts3 GREEN (1204); mutation price-axis inverted -> 2 fail. Phone not connected (install pending). D50 RepairTest flake 4th time. Deep review dispatched after the mutation finished.
- 2026-11-05 TS3 review PASS-WITH-FINDINGS (no BLOCKER/MAJOR). Committed (android + docs); its CHANGELOG line rides with TA1's commit (shared file). Minors -> TS3b.
- 2026-11-05 TA1 built; pm-gate-ta1 GREEN (527 Go, -race clean per builder); mutations tool_count guard 1 fail, Buy order 1 fail. Deep review dispatched.
- 2026-11-05 TA2 dispatched against TA1's uncommitted contract (read-only) while TA1 is reviewed; steer if the review changes the contract.
- 2026-11-05 TA1 review PASS-WITH-FINDINGS (8 security checks hold). Committed with TS3's CHANGELOG line. MINOR-1 (prompt injection) fixed now as TA1b (advice quality = core promise); TA2 steered: summary/reasons plain text only.
- 2026-11-05 TA1b: pm-gate-ta1b GREEN (542 Go); mutation news-only cap removed -> 1 fail. Committed c1f8904 without its own review; TA2's review re-checks `git show c1f8904`. TA2 steered with the new codes + news_only flag.
- 2026-11-05 TA2 built (1228). PM: protocol §20.1 poll 2 s -> 3 s (code is right), CHANGELOG Phone line applied from the report. Gate + mutation (VerdictCard link guard removed) running; review waits for the mutation. D53 flake recorded.
- 2026-11-05 TA2 + TA1b review PASS-WITH-FINDINGS. CHANGELOG line moved to the top (newest first). Q101 marked PM reading (app language). Committed.
- 2026-11-05 User away ("do all"). Wave: TM3s (server minors), TM3a (android minors), TSC0 (design slices 2-3) in parallel.
- 2026-11-05 Minors wave: pm-gate-tm3 GREEN both sides (550 Go); mutations: CASH word rule -> TestNameFallbackKeepsWordCodeRule fails; chart hold -> ChartHoldAndDetailRulesTest fails (first attempt matched no class, redone). Committed; batch review on the commits in parallel with TSC1. TSC0 design done; Q104-Q111 open for the user; P8 JSON bar store.
- 2026-11-05 review-TM3: TM3s PASS; TM3a FAIL (2 MAJOR, on the pushed commit 8fff8da) -> TM3a2 dispatched. TSC1 built (577 Go); PM gate + mutation next, then review.
- 2026-11-05 TSC1: pm-gate-tsc1 GREEN (577 Go); mutation catch-up cutoff ignored -> TestSchedulerSuspendJumpCatchesUpLatestSlotOnce fails. Committed; deep review on the commit.
- 2026-11-05 TSC1 review CHANGES-REQUIRED (MAJOR data race on jobs PUT during a run, on pushed d2e1255) -> TSC1b with the cheap minors.
- 2026-11-05 TSC1b built (584 Go, -race clean). PM red-check: its new race test PASSES on d2e1255 (guards nothing) -> TSC1c. TSC1b held uncommitted until TSC1c.
- 2026-11-05 TSC1c: PM red-check confirmed (d2e1255 -> WARNING: DATA RACE + FAIL; tree -race GREEN), pm-gate-tsc1c GREEN 584. TSC1b+TSC1c committed (fix round; TSC2's review re-checks the diff).
- 2026-11-05 TM3a2 built; pm-gate-tm3a2 GREEN; mutation back() never pops -> 3 PairedPageTest failures. Deep re-review dispatched.
- 2026-11-05 TM3a2 re-review PASS. Committed. Minors A/B folded into TSC3. Next: flake triage D50/D53 (android tests) while the user is away.
- 2026-11-05 11:35 User away, 'do all' (decisions.md). TM3 (server), TM4 (android), TS4 (screener research) dispatched in parallel.
- 2026-11-05 13:50 Overlap found (decisions.md). TM3/TM4 deltas to gate+review; TSC2 (server) and TD5 (board) dispatched; TBA1 stays WAITING on Q104, Q107-Q110.
- 2026-11-05 TM4 delta: pm-gate-tm4 GREEN (android). Mutation (gesture strip height cap removed): first filtered run matched no class (StockChartTest.kt holds several classes) -> not counted; redo on ChartHoldAndDetailRulesTest -> theGestureStripsAreTheChartEdgesWithinTheSystemLimit fails. TM3 delta checked after TSC2 (same package); one review for both deltas.
- 2026-11-05 TSC2 built (637 Go, -race clean); TD5 board done. PM: §20.2 jobs line; mutations liquidity floor (2 fail), news_only hold:* (1 fail; first try NOT APPLIED, sed delimiter). One deep review for TSC2+TM3+TM4 deltas; TSC3 dispatched on Q112 PM reading.
- 2026-11-05 TSC2 review PASS-WITH-FINDINGS (stress -race clean). Committed 8fdba61 with TM3+TM4 deltas. TSC2 minors 1-5 folded into TBA1 (two change what alerts see). TBA1 dispatched; TSC3 PM gate + mutation running, review after.
- 2026-11-05 TSC3: pm-gate-tsc3 GREEN (1299); mutation polls outside scan windows -> ScreenerRefreshTest 1/9 fails (XML checked). Deep review dispatched.
- 2026-11-05 TSC3 review CHANGES-REQUIRED (MAJOR-1: bearish flag hidden when a code is both candidate and flagged; breaks Q106). TSC3b dispatched with MINOR 1-5; TSC3 held uncommitted until TSC3b passes.
- 2026-11-05 TSC3b built (1304, 7 tests RED first). pm-gate-tsc3b GREEN; mutation bearish-last -> ScreenerLogicTest 1/8 fails (XML). Re-review of TSC3+TSC3b dispatched.
- 2026-11-05 TSC3b re-review APPROVED. TSC3+TSC3b committed (android). CHANGELOG Phone line rides with TBA1's commit (shared file, TBA1 editing it). MINOR-6 (TalkBack re-reads the cooldown every second) -> TBA2 brief.
- 2026-11-05 TBA1 built (653 Go, -race ok). PM mutations: auto-run cap -> caught; brief TradingDays flag -> SURVIVED (first try hit the view-only check at brief.go:804; real guard stocks.go:159 has no test) -> required fix via review. Deep review + TBA2 (phone) dispatched.
- 2026-11-05 15:50 PAUSED by the user. Installed on the phone: b96e305 (slices 1-2 + analyzer); real server updated to b96e305.
### RESUME POINT (2026-11-05 15:50)
1. In flight at pause: TBA1 deep review (deleg_4374755d task 0) and TBA2 build (task 1). Their results arrive during the pause: log TBA2 as BUILT (unreviewed); record the TBA1 verdict; no fix round, gate or commit until "go".
2. On "go": TBA1 — add the missing test (brief job must not run on weekends/holidays: `stocks.go:159` TradingDays mutant survived) + any review BLOCKER/MAJOR as TBA1b; gate, mutate, commit (CHANGELOG also takes TSC3's Phone line from runs/TSC3/build-report.md "Doc text").
3. Then TBA2: PM gate + mutation (filter by real class name, check the XML), deep review, commit; TBA2's CHANGELOG line from its report.
4. Device checks waiting for the user: pairing with the real server; first live Analyze (time + Claude cost); chart back-gesture edge strips; 1.3x/Large text on the stock page and screener settings; Edit list sheet with the keyboard; screener after the 16:20 bars job; alerts with the screen off on HyperOS (TBA2); T22 v1 release check; D45 licence notices.
5. Open user confirmations: none (Q101-Q112 decided).
- 2026-11-05 (paused) TBA1 review CHANGES-REQUIRED: MAJOR-1 = missing test only (scheduled brief must not run on weekends/holidays; shape: Fri 17:00 -> next_run Mon 08:30; ticks Sat/Sun/2026-12-25 08:30 -> no session, 404 no_brief, no alert; bounded waitFor). MINORs for TBA1b: no-prices rule (1990-2099 and "4,9 ribu" pass; "150%"/"MA200" refused), re-rank-first hits never alert, auto-run cap only in memory after a bad alerts file, brief retries on hermes_lost re-spend Opus up to ~6x (cap retries). Nit: Hermes cite canonical_sessions.py:28 not :29.
- 2026-11-05 (paused) TBA2 BUILT (unreviewed): builder reports gate PASS (1338), BUT a second gate process it never read ended `FAIL android (1)` -> on "go" run the PM gate first and read the failure. Builder decisions to confirm: target > avg not required (server does not require it); alerts > 30 min old listed without a notification; brief card wording for an older brief.
- 2026-11-06 Resumed on "A". Board statuses fixed (TSC2/TSC3/TM3/TM4 DONE; TBA2 row = phone ticket that absorbed TBA3a/b). Q113/Q114 kept, Q115 PM reading - confirm. TBA1b dispatched (std, deleg_4106bf5c); TBA2 PM gate running in parallel (android only). Snapshot runs/TBA1b/pre.txt.
- 2026-11-06 TBA2: pm-gate-tba2 GREEN (android, 1338); the builder-reported stray FAIL did not reproduce. Mutations: first read notifies history (first try did not compile, not counted; redo) -> AlertsTest x2 + AlertWatcherTest x1 fail; stale >30 min notified -> AlertsTest 1 fail. Alerts.kt sha matches pre.txt. Deep review dispatched (deleg_7db70ba8), parallel with TBA1b.
- 2026-11-06 TBA1b built (std). Files within whitelist. pm-gate-tba1b GREEN (660 Go), -race ok. Mutations: brief TradingDays false -> TestBriefNeverOnWeekendOrHoliday; retry after submit -> TestBriefRetriesOnlyBeforeSubmit; auto run without saved cap -> TestNoAutoRunsWhenAlertsStateUnusable (all killed, restored). Deep re-review of TBA1+TBA1b dispatched (deleg_37ab170d).
- 2026-11-06 TBA2 review CHANGES-REQUIRED (MAJOR-1 brief day chips pick by index, newest kept brief unreachable before 08:30). Reviewer confirms Q115 reading except MINOR-2 (a) jobs unknown, (b) pending after 09:00. TBA2b dispatched (std, deleg_c7df94e5) with MAJOR-1 + MINOR-2..6 + nits (MINOR-3/5 touch notifications = user impact). Snapshot runs/TBA2b/pre.txt.
- 2026-11-06 TBA1+TBA1b re-review PASS-WITH-FINDINGS (7 reviewer mutants killed; retry cannot re-spend Opus; model mismatch counts to the cap, no tokens). Committed with CHANGELOG (TBA1 Server lines + TSC3 Phone line). MINOR-1 (priceLike refuses year text the prompt invites -> extra Opus repair turns daily) = money impact -> TBA1c next, after TBA2b (server side free).
