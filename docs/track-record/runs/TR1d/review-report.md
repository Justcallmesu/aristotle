# TR1d review (fresh reviewer, deep; uncommitted server diff + protocol.md on c74df38)

Method: read P16/P16a/P16b, the TR1c review, the TR1d brief/report, §20.5 and the whole diff. Scratch `~/.hermes/profiles/pm/cache/scratch/tr1drev/`
(`new/` = working tree, `old/` = `git archive c74df38`): gofmt/vet clean, stocks ok (relay fails there only for fixtures outside `server/`), probes
`zprobe_test.go` in both, 11 mutants (`mutate.py`, `check.py`). Repo untouched except this file.

## Verified
- **TR1c 1-3 + NIT closed.** The 79-run daily-restart probe (403 on 5m and 1d, restart before each run) is `unscorable` on run 10 (10-27) and is never asked again.
  Failing every other trading day: the hard limit gives up on trading day 31. Isolated failures weeks apart: no give-up (test). KILLED: `tooOld` = any
  status ≥ 400, previous trading day = calendar yesterday, body-read error back to a code failure, plus the PM's two mutants.
- **Scope widening (attack 2) is harmless.** `isNetwork` is read only by `track.go` (`isStop`, `stopCode`). `Market.note` backs off on `Status ≥ 500`
  only, so status 0 is unchanged. HTTP maps every `UpstreamError` to `502 upstream_error`. The bars, analyzer and screener jobs never read `Reason`. The only
  other visible change: a news feed (RSS, also through `fetch`) whose body breaks now shows `sources[].error` `"network"`, not `"read body"`. P16b says
  "every Yahoo caller", so keep it unscoped.
- **Attack 4:** literally right under P16b: RECB's 09-14 record is ≥ 30 trading days old and RECB fails, so it is `unscorable`. `partial` follows from `gaveUp > 0`.

## Findings
- MAJOR (attack 3): **a rollback loses the whole track record.** Version 1 decodes strictly (`versioned.go:43`, `DisallowUnknownFields`). Probe on
  c74df38 with a TR1d file that has `failures`: `unknown field "failures"`, history unusable, 0 records served, new verdicts in memory only (lost at
  restart). After the first give-up this lasts forever, because the entry is never removed (next finding). §20.5's "additive … version stays 1" is false
  for an older binary. The project's precedent is a separate `stocks-levels.json`, kept apart so that "an older binary reads" `stocks.json`. Fix: move
  `failures` to its own file (`stocks-track-failures.json`, `{"version":1,"failures":{…}}`; unusable → no failures, logged), and keep
  `stocks-verdicts.json` byte-for-byte as in v1.
- MINOR: **`failures` entries are not removed when the records are given up.** The probe leaves `"FAIL":{"first":"2026-10-14","last":"2026-10-27"}` for good,
  because only a success clears an entry and a code with no pending record is never planned again. The growth is bounded by distinct codes (≈ 50 B each),
  but the brief's claim is wrong. Fix: at the end of a run, drop the entries of codes with no unscored record (or with `last` older than 30 trading days).
- MINOR: **the hard limit overrides P16a's offline-gap rule.** Probe: 7 weeks away, then ONE 403 on the first run gives up a 32-trading-day-old record whose
  5m bars still exist (< 50 days). Also, in `TestTrackJobRecent422Waits` the 09-14 record is given up although its daily bar is in the store: a recent
  session's 422 fails the whole code. Both match P16b's literal text; the TR1c gap only needs isolated failures bounded. Fix (PM call): hard limit only
  when the code has also failed on an earlier date since its last success (keep `since` alongside `first`/`last`), or record the trade-off in P16b.
- MINOR (tests that guard nothing): these mutants SURVIVED: `Last` never advances (`noteFailure` `case day > f.Last`; a 3+ day streak then restarts
  every other day), `hardGiveUpTradingDays` 29 or 31 (tests run at 28 and ≥ 36), and dropping `clearFailure` on success. Add a 3-day streak crossing day 10 on
  day 3, runs at exactly 29/30 trading days, and a check that a success removes the entry from the file.
- NIT: `giveUp` now also records the failure (`noteFailure`), a side effect its name hides; call `noteFailure` from `runTrackScore`. The new
  newer-file branch (`verdicts.go:106`) is untested (mutant survives). §20.5 "Every other failure (… a bad body …)" should say "an unparsable body" now
  that a broken body read stops the run.

VERDICT: CHANGES REQUESTED. 1 MAJOR (rollback makes the history unreadable to c74df38); the MINORs can go into a follow-up.
