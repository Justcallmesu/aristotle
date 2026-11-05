# v2 stocks — tickets

| Id | Title | Depends | Side | Status |
|---|---|---|---|---|
| TS0 | Research: data sources for slice 1 (ticker search, LQ45/IDX80, Yahoo limits, RSS, holidays, tick sizes) | - | docs | DONE |
| TD4 | Design board: Home Chats\|Stocks switch, watchlist, stock detail + chart, holdings, news | - | design | DONE |
| TS1 | Server: stocks module (statefile watchlist/holdings/fees, Yahoo provider, RSS news, `/v1/stocks/*`) | TS0 | server | DONE |
| TS1b | Server minors from review: news Close() marks closed (no poller restart after shutdown); backoff on Yahoo 5xx; name-fallback bypass of the word-code rule (CASH); image_url http(s) only; chart cache eviction; zero-cost guard | TS1 | server | TODO |
| TS2 | Phone: Home switch (title words), Stocks home, watchlist, holdings, news, Settings › Stocks, stock palette | TD4, TS1 | android | BUILDING |
| TS3 | Phone: stock detail + chart (Canvas candles/volume/line, ranges, crosshair), Open in Ajaib | TS2 | android | TODO |

## Log
- 2026-11-05 v2 grill: Q84-Q90 (all A). Yahoo + RSS re-probed 200. TS0 + TD4 dispatched in parallel.
- 2026-11-05 TS0 done (Yahoo needs browser UA; spark for quotes; kontan feed URL corrected in docs/spec.md §6). TD4 board done; source saved to docs/design/v2/stocks-src/; opened on the phone.
- 2026-11-05 Board picks Q91-Q94 asked; user away (clarify timed out 60 min). TS1 (server, independent of the picks) dispatched; Android tickets wait for the picks.
- 2026-11-05 TS1 built; pm-gate-ts1 GREEN (480 Go); mutations sell-fee sign 7 fail, Friday S1 end 3 fail. Deep review dispatched.
- 2026-11-05 TS1 review PASS-WITH-FINDINGS (no BLOCKER/MAJOR; -race clean). Committed. Minors -> TS1b (folded into the next server touch).
- 2026-11-05 User picks Q91 A, Q92 title words, Q93 A, Q94 Ajaib, Q95 yes. Real server installed as a user service (0.0.0-dev+6bf72c4; listens 192.168.1.61, 100.74.61.96, fd7a::…:8760). TS2 dispatched.
