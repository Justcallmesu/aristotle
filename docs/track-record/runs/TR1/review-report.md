# TR1 review — verdict history, scorer, track.score, /v1/stocks/track-record (fresh reviewer, deep; diff on 06a6723)

Method: read spec Q116-Q124/P10-P15, TR0, build brief/report, §20.5 and all new/changed code. Probes and 8 extra mutations on a
scratch copy (`~/.hermes/profiles/pm/cache/scratch/tr1review/`); stocks + httpapi tests ok there, gofmt/vet clean. Repo untouched.

## Verified
- P12 no look-ahead: only bars with start > `analyzed_at` (`score.go:66,179-191`); lunch-break call 12:30 ignores an 11:00 stop touch (probe);
  Friday break and a 16:30 call scored on the next session are table rows. Stop in the fill bar = loss (`score.go:112`), target in the fill bar
  ignored on 5m (`:117`), both levels in one bar = loss (`:132-142`). Gap-up above target with the zone never touched = `not_triggered` (probe).
  15:55 call filled only by the 16:05 closing-auction bar = `closed` at the daily close (probe; close prefers the daily bar, `score.go:74-76`).
- Outcome vocabulary exact: `closed` only buy/add, `right` hold + avoid/sell (close ≤ price, `:96`), `would_have` wait (`:101`); base per Q120a (`:146-151`).
- History: `keep` is the only writer (sole caller `analyzer.go:534`), appends at `analyzer.go:720`; lock order an.mu→h.mu, the job never takes
  an.mu; seed only on ErrNotExist (`verdicts.go:72`); `apply` attaches a score only to a nil score (`verdicts.go:211`). No manual job trigger
  exists and the scheduler never runs a job twice at once (`scheduler.go:382`), so attach-once holds.
- Budget: ≤1 5m + ≤1 1d request per code per run (`track.go:117-153`), paced (`:92-100`), block/5xx/ctx stop the run (`:103-106,178`),
  network/bad body leaves the record (`:108-112,182`). Job: 17:00, TradingDays, CatchUpAlways, editable `on` (`stocks.go:161-162`);
  weekend/Christmas test uses the real calendar. Endpoint: device auth (httpapi test), n+pending = records, `code` filters calls only,
  cap 50, no reason/summary/news text, shape = §20.5.

## Findings
- **MAJOR 1 — a transient 4xx freezes a daily (look-ahead) score.** Any non-404 4xx on the 5m window (403, 401, 400…) is treated as "Yahoo had
  none" (`track.go:123-131`): with a fresh store the record is scored `source:"daily"`, frozen (P13), job status `ok`. Probe: a 13:58 call whose
  true 5m outcome is `not_triggered` became a frozen `loss` on one 403. Also one 422 (an old session in the code's window, 56-70-day cut-off
  unverified) pushes yesterday's sessions of that code to daily too. Contradicts P15 ("never frozen by a transient error"). Fix: fall back
  only on 422 (and 200-empty); other statuses → `f.failed`; on 422 retry the window without the sessions older than ~50 days. Update §20.5.
- **MAJOR 2 — daily fallback counts a target in the fill bar.** `score.go:114` returns `win` when the day reached the zone and the target
  without the stop; P12: "a target in the same bar as the entry fill does not count". Probe: the same OHLC as one 5m bar → `closed`, as a daily
  bar → `win` (+3.03). Inflates daily-source wins; P15 accepts only the look-ahead. Fix: drop the `daily` case (→ `closed`) or record a PM decision.
- **MAJOR 3 — one record without its levels kills the job for good.** `parseVerdicts` checks only id and code (`verdicts.go:138-142`; a
  newer-version file is not checked at all), the scorer dereferences `*r.EntryLow` etc. (`score.go:92-94`). Probe: a v1 buy_today with null
  levels → panic → run `failed internal_error`, nothing scored for any code, every run. Same class: a bad `analyzed_at` makes `barsAfter` keep
  every bar (fails open, `score.go:180-183`; probe: win→loss). Fix: validate records on load (`scorableVerdict`, RFC3339 `analyzed_at`,
  session date) and skip unscorable ones in `planTrack`.
- MINOR — split inside the info window: Yahoo daily closes are split-adjusted, the base is not; probe info_3d ≈ -80..-96%. Skip info_3d (and
  log) when the refetched session bar's close differs from the stored `score.close` by > ~1%.
- MINOR — wasted/endless requests: a `no_session` record still asks for info_3d every run for 30 days (probe: 19 requests over 21 runs for one
  unknown code; skip info for `no_session`); a code that always fails transiently is asked every run forever (probe: still asked 125 days
  later; give up after N days, e.g. score `no_session` or stop planning, logged).
- MINOR — boot catch-up launches `bars.daily` and `track.score` in the same tick (`scheduler.go:398-420`): the job sees a stale store and
  adds one 1d request per code in parallel with the bars job. Add `Wanted` that waits for a fresh store (TR0 §7) or a later tick.
- MINOR — gap-down below the zone (and below the stop) then back into the zone scores `closed` (probe -0.40); never back → `not_triggered`.
  A resting buy at entry_high would have filled at the gap open. Spec is silent: PM to decide (Q118 "not reached").
- MINOR — tests that guard nothing found by mutation (all SURVIVED): `apply` overwriting a score (P13 only guarded via `planTrack`), a stale
  bar store used for scoring (`track.go:139-140`), the bar starting exactly at `analyzed_at`, keep dedupe, the `scorableVerdict` skip, the
  30-day info cap. Add one row/test each.
- MINOR — duplication: `parseVerdicts`/`newerVerdictsError` repeat `loadAnalyses`/`newerAnalysesError` (`analyzer.go:228-276`); extract one
  versioned-file decoder. NIT — an unusable analyses file at first start creates an empty history and loses the seed for good (probe);
  `calls` sort compares RFC3339 strings (fine while all offsets are +07:00).

VERDICT: CHANGES REQUESTED — no BLOCKER; fix MAJOR 1-3 (or record PM decisions for 1-2) before commit.
