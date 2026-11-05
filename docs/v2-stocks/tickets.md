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
| TA1b | Analyzer hardening (TA1 review): untrusted-data instruction + flag news-only reasons (MINOR-1); record session id before any post-create failure (MINOR-2); sweep continues past errors + first-real-run check of cross-socket delete (MINOR-3); nits (refusal codes, hold target check, shutdown race, failed_at parse, title caps, duplicate keys) | TA1 | server | BUILDING |
| TA2 | Phone: Analyze button + verdict card on the stock page | TA1, TS3 | android | BUILDING |
| TS3b | TS3 minors: stale long-range bars need Retry/refetch; 60 s refresh must not cut a crosshair hold; exclude chart from system back-gesture edge; 1.3x font overflow (device check); 1D slot for bars at 12:00/11:30/pre-09:00; Back from All news -> stock; single-bar line; Add holding before load; Ajaib uninstall feedback | TS3 | android | TODO |

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
