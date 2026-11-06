# TR1d + TR1e re-review (fresh reviewer, deep; uncommitted server diff + protocol.md on 256b80e)

Method: read P16-P16b, the TR1d review, the TR1d/TR1e reports, §20.5 and the whole diff, including the new `trackfails{,_test}.go`. Scratch: `~/.hermes/profiles/pm/cache/scratch/tr1erev/`
(`new/` = working-tree copy, `old/` = `git archive c74df38`, which builds). Probes are in `zprobe_test.go` in both copies. Ran 20 mutants with `mutate.py`. gofmt and vet are clean. The repo is untouched except for this file.

## Verified
- **Rollback (the TR1d MAJOR) is closed.** State A was written by the new code: FAIL and WAIT's October record given up, GOOD scored, and WAIT's entry left in `stocks-track-failures.json`. The c74df38 code reads all 4 records through `GET /v1/stocks/track-record` with the right outcomes, logs nothing unusable and leaves the file unrewritten. In the reverse case, a TR1d-era `failures` field is moved once (`since` takes `first`). After the next verdicts write (state B), c74df38 reads it (2 records).
- **The failures file:** writes are atomic (`statefile.WriteAtomic`, 0600). An unusable file is logged and never overwritten (the in-memory copy still counts). A newer-version file is read but never written. Entries are removed on a score and on a full give-up, and a partial give-up keeps the entry. Growth is bounded by codes with pending records. `since` matches P16b: a streak restart keeps it, and only a score or a full give-up resets it.
- **A failed write is only logged.** This is acceptable: `dirty` stays set and the next run's flush writes it (probe: a directory in place of the file, then the next run writes `{}`). The worst case after a restart is a delayed give-up.
- **The hard limit needs an earlier failure.** The 7-weeks-away probe gives up nothing (`TestHardLimitNeedsAnEarlierFailure`). A code failing every 2nd, 3rd or 5th trading day is given up at exactly 30 trading days in all three cases (restart before each run). One failure at age 5, then 7 weeks away, then one failure: given up, which matches the amended P16b text.
- **`TestTrackJobRecent422Waits`:** the new expectation is right (the first run gives up nothing, and 11-09 gives up the 40-day record). Mutant M17 (hard limit ignoring failure history) kills it.
- **Mutants:** 14 of 20 KILLED. These include `ever` read from `first`, `since` reset on a streak restart, no flush, no legacy save, an unusable or newer file written, clear on any give-up, no clear on success, `dirty` never set, and `omitempty` dropped.

## Findings
- MINOR (tests that guard nothing): the entry validation that §20.5 promises ("entries with a bad code or date are dropped") has no test. Removing the code check, the date-order check or the date-parse check all SURVIVE. Also surviving:
  - noting a failure for an info-only code: `failCode` without its `len(recs)==0` guard. The entry would never be cleared.
  - taking `failures` from a newer-version verdicts file.
  - merging the legacy field when a failures file already exists.

  One table test of `validFailures` plus one `failCode` info-only case would cover these.
- NIT (code shape):
  - `failureLog.snapshot()` is dead (no caller, not even a test).
  - `legacy = nil` in `loadVerdictHistory` is redundant: `parseVerdicts` already returns nil on every error (an equivalent mutant).
  - `failCode` takes `fails`, but `giveUp` reaches through `s.analyzer.history.fails`. Pass it in, or have `failCode` clear the entry.
  - `previousTradingDay` lives in `track.go`, but only `trackfails.go` uses it (it would fit `idxcal.go`).
  - `verdictHistory` carries `fails` only as a carrier. Compared with `levels.go`, the separate file is the right pattern; the ownership could sit on `Service`.
- NIT (docs): the §20.5 "Code:" line still lists `{verdicts,score,track}.go`. Add `trackfails.go`.
- FYI: a TR1d leftover stays in `stocks-verdicts.json` until the next verdicts write, so a rollback before that write still fails (state C: c74df38 serves 0 records). This is by design per the brief, and TR1d never shipped.
- FYI: after a rollback and roll-forward, stale entries can linger, and an old `since` can satisfy the hard limit. Both effects are bounded.
- FYI (carried over from the TR1d review, not this diff): a recent-window 422 fails the whole code, so RECB's September record is given up although its daily bar is in the store.

VERDICT: APPROVE. No BLOCKER or MAJOR; the MINOR and NITs can go into a follow-up.
