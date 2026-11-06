# TR1 + TR1b re-review (fresh reviewer, deep; whole uncommitted server diff on 06a6723)

Method: read spec Q116-Q125/P10-P16, TR1 build/review, TR1b brief/report, §20.2/§20.5 and all changed code. Scratch copy
`~/.hermes/profiles/pm/cache/scratch/tr1brev/`: gofmt/vet clean, stocks + httpapi ok, 4 probes, 21 extra mutations (`mutate.py`). Repo untouched.

## Verified
- **TR1 MAJOR 1 closed:** `track.go:154-165`. Only a 422 or a 200 with no bars falls back to daily bars. 400/401/403 and a bad body set `failed` and
  the run reports `codes_failed` (`:245`). Mutation "404 = 200-empty" KILLED. **MAJOR 2:** `score.go:180-191`: the daily bar is the fill bar, so a
  target that day gives `closed` (`score_test.go:87`). **MAJOR 3:** `readCall` (`score.go:74-107`) is the only reader behind `scoreRecord`,
  `info3dOf` and `laterInfo3d`. `barsAfter` takes the parsed time (`:264`). The planner skips bad records (`track.go:77`), load logs them once for
  every file version (`verdicts.go:106-109`), and no nil dereference is reachable (`fillInfo3d` only gets records with a score).
- **TR1 MINORs closed:** split check both ways (`score.go:257`; a reverse-split probe is detected too); no info for no_session/unscorable
  (`track.go:89`); give-up (`:253`); boot order (`scheduler.go:402,434`, `stocks.go:162`); `versioned.go:26` shared by both files.
- **Q125 by hand:** gap below the zone, above the stop: the gap bar fills and the next target touch is `win` +3.03. Gap below the stop: `loss`
  at the stop, -3.03. Gap then rally: `win`. Gap-down day on the daily fallback: `closed`, -1.62. The base is always `entry_high`
  (`score.go:87,153`; table rows `score_test.go:90-100`).
- **Scheduler `After`:** `waitsLocked` only skips; nothing blocks or waits under `mu`, so no deadlock. It is checked before `Handled` is written,
  so the slot stays due. `bars.daily` is an at-job that ends every run (ok or failed), so `track.score` is never starved unless bars hangs.
  Only `track.score` sets `After`. Existing scheduler tests are unchanged apart from the job list. Mutations "After ignored" and "waits
  forever" KILLED.
- **`versioned.go`:** compared old and new by hand: same error strings, same newer-version path, same post-processing. A newer file still
  seeds and an unusable one creates no history (mutations KILLED).
- **Yahoo budget, bad weeks:** probe with 403 every day for 3 codes: 3 requests per run for 10 trading days, then `unscorable`, then 0 (33 in
  total). A 429, 5xx or backoff stops the run (`track.go:157`). A 422 costs at most 2 5m requests + 1 1d request per code per run.
- **Builder decisions accepted:** a 404 for a code that has daily bars waits (bounded by the give-up; an unknown code costs one extra 1d
  request once). The split log repeats until the 30-day cap (bounded).

## Findings
- **MAJOR 1: one transient error after an offline gap permanently marks every old record `unscorable`.** `giveUp` (`track.go:253-257`) checks
  only the record's age plus one failure in this run. Probe: back after 3 weeks away, the first run gets one network error per code. Every
  record ≥10 trading days old becomes `unscorable`, and good data the next day cannot fix it (P13). Its 5m bars were still at Yahoo. With the
  network down at boot or resume (track runs one tick after bars), this hits every code at once. That contradicts P16 ("Yahoo keeps failing")
  and P15 ("never frozen by a transient error"). `TestTrackJobGivesUpAfterTenTradingDays` itself uses a network error. Fix: treat a network
  error (status 0) like a block, so it stops the run and gives up nothing. Give up only after the code has also failed on an earlier trading
  day: keep an in-memory first-failure date per record (a restart only delays the give-up). Or record a PM decision.
- MINOR: a 422 for a window with no session older than ~50 days scores recent sessions from daily bars, and they stay frozen. Probe: the retry
  422s too, and yesterday's call is frozen as a `loss` (source daily). TR0 says 56-day windows answer, so this 422 is not "too old". Fix: fall
  back only when the 422 window holds a session older than `intradayRetryAge`, else `failed`. This needs a PM ruling, because P15a says "only
  on 422" and the `REJA` row pins the current behaviour.
- MINOR (§20.5 for the phone): "unscorable is counted in n and left out of every rate" is clear. But `n` also counts `not_triggered` and
  `no_session`, and Q122's "n of 10 decided calls" is easy to read as `n`. Say that `n` is never a denominator or the Q122 counter, and define
  "decided" per verdict type.
- MINOR (tests that guard nothing; these mutations SURVIVED): reverse-split check (`score.go:257` with only `< -1 %`); level order and
  `price > 0` in `readCall` (`:77,88`); `appendLocked`'s `scorable` gate (`verdicts.go:161`); info-only runs using a stale store
  (`track.go:174`); the strict v1 decode (`versioned.go:43`, already unguarded before TR1). Add one row or assert each.
- NIT: `info3dPct` (`score.go:216`) is used only by tests; delete it and test `info3dOf`. A stop equal to the fill-bar low is untested (`:185`).
  `pending` counts never-scorable records forever. `register` does not check that `After` names a job. With `bars.daily` moved after 17:00
  (allowed up to 23:00), bars can start while track runs.

VERDICT: CHANGES REQUESTED. No BLOCKER. Fix MAJOR 1 or record a PM decision; the MINORs can go in the same round.
