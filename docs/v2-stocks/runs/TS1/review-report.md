# TS1 review — stocks server (`/v1/stocks/*`)

Fresh deep reviewer, read-only, 2026-11-05. Checked: `git diff HEAD` (server, CHANGELOG, protocol §20), untracked `internal/stocks/**`, `httpapi/stocks_test.go`. Ran `gofmt -l`, `go vet`, `go test -race -count=1` (stocks, httpapi, app) and full `go test ./...`: all green. Probes ran on a scratch copy, now deleted. No network, listener or state dir touched; git status unchanged.

## Verified (no issue)
- **P/L** `pl.go:26-41,70-100`: exact `math/big`, each amount rounded half up once, fee in ppm (`:20`). TS0 example 4 807 200 / 4 917 675 / 110 475 / 2.3 holds. 10^7 lots × ~10^7 IDR × 1.05 ≈ 1.05e16 fits int64 (`pl_test.go:19`); 50 holdings ≈ 5e17. Total pl_pct guards cost 0 (`:296`).
- **Auth** `httpapi.go:101-103`: mounted only when `Deps.Stocks` is not nil, inside `requireDevice` (`auth.go:16`). Tests cover nil, no token, bad token (`stocks_test.go:10-36`). Other paths/methods give 404 (`stocks.go:146`).
- **Validation**: code `^[A-Z]{4}$` (`http.go:48`, `state.go:34`); body ≤ 4 KiB, one object, unknown fields refused (`http.go:57-71`); reorder same set, no repeats (`state.go:191-207`); lots 1..1e7, avg 0.01..1e7, fees 0..5 (`:140-157`). JSON cannot carry NaN/Inf (1e400 fails to decode); NaN checked anyway. Limits re-checked under the lock after the Yahoo call (`:172,219`).
- **State**: `statefile.WriteAtomic`, 0600 (`state.go:108-114`); load needs version 1, refuses unknown fields (`:85-106`). A bad file is never overwritten: state routes answer 500, the rest works (`stocks.go:103-107,154,164`).
- **Concurrency**: `Service.mu` held across the save (`stocks.go:161-177`); `Market.mu` guards cache + backoff; `News.mu` guards ring + status; concurrent refreshes joined (`news.go:279-300`). Race run clean.
- **Yahoo**: browser UA (`yahoo.go:18,61`); 15 s client timeout (`:24`), 20 s per news round (`news.go:43`); 8 MiB body cap (`yahoo.go:68`). 429 → shared backoff 1→2→4→8→15 min, no calls out (`provider.go:135-158`), stale cache served (`:200-203,241-245`); nothing cached → 503 + `retry_after_s` + `Retry-After` (`http.go:37-45`). Logs carry no URLs, tokens or holdings values (`TestLogsCarryNoHoldingValues`).
- **News**: Kontan URL fixed (`news.go:31`); poller starts on a request, stops on the first tick after 30 min idle (`:215-266`). `encoding/xml` (no entity expansion), 8 MiB cap, ring of 300 deduplicated by link (`:363-378`). Matcher case-sensitive, with stop-list; `K-CASH` ≠ `CASH`; word-codes only in the strong form (`match.go:21-32,110-127`).
- **Calendar**: fixed `WIB` zone, host TZ irrelevant (`idxcal.go:197`; UTC input probed OK). Fri 09:00-11:30 / 14:00-16:00 matches spec §6 (`:236-241`). 24/25/31 Dec 2026, Lebaran 2027, 241 trading days, ARA/ARB bands correct by probe.
- **Contract**: extra fields (`session`, `closes_at`, `week52_*`, `spark`, `tick`, `ara`, `arb`, `today`, `name`, `stale`) documented in protocol §20. No new listener, `go.mod` unchanged, shutdown calls `Close` (`serve.go:201`).

## Findings (no BLOCKER, no MAJOR)
1. **MINOR** `news.go:219-225,268-276`: `Close()` has no closed flag, so `Items()` after `Close()` (an in-flight remote request; `serve.go:201` runs before `wg.Wait`) starts a new poller that lives up to 30 min. Probe: polling true after Close+Items. Fix: `closed` flag checked in `Items`.
2. **MINOR** `yahoo.go:74`, `provider.go:135-151`: Yahoo 5xx gets no backoff (TS0 §3 said 429/5xx; build brief only 429). In an outage every request past the TTL calls Yahoo.
3. **NIT** `pl.go:277`: `Evaluate` calls `Pct(pl, cost)` with no cost > 0 guard (`roundDiv` panics on 0). Safe only because `state.go:144` keeps avg ≥ 0.01 (cost ≥ 1). Add the guard as in `Total`.
4. **NIT** `news.go:124-130`: `image_url` scheme not checked (`link` is, `:116`). Keep only http(s).
5. **NIT** `http.go:318-329`: dedupe O(n²), 50-code cap applied after the loop. Bounded by `MaxHeaderBytes` 64 KiB (`listen/manager.go:168`), under 1 s. Check the count first.
6. **NIT** `stocks.go:195-196` + `match.go:143-147`: a name that falls back to the bare code matches case-insensitively and skips the word-code rule (probe: CASH weak-matched "cash"). "Timah" (TINS) weak-matches tin news; documented choice.
7. **NIT** `provider.go:225-237`: chart cache never evicts (search capped at 500, `:253`). Each entry costs one Yahoo call.
8. **NIT** `idxcal.go:234-241`: `open` until 16:00, pre-close runs to 16:01:59 (spec §6). Documented.

VERDICT: PASS-WITH-FINDINGS
