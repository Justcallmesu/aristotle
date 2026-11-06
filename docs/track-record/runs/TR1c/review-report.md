# TR1 + TR1b + TR1c final review (fresh reviewer, deep; whole uncommitted server diff on 06a6723)

Method: read spec Q116-Q125/P10-P16a, the TR1 and TR1b reviews, the TR1c brief and report, §20.2/§20.5 and all changed code. Scratch copy
`~/.hermes/profiles/pm/cache/scratch/tr1crev/`: gofmt/vet clean, stocks + httpapi ok, 3 probes (`zprobe_test.go`), 27 mutants (`mutate.py`). Repo untouched.

## Verified
- **TR1b MAJOR 1 closed.** A network error (`isNetwork` `provider.go:59`, set only at `yahoo.go:66`) stops the run (`isStop` `track.go:128-131`)
  and the loop breaks before `giveUp` (`:239-241`). A code is given up only when it already failed on an earlier WIB date (`:277`, `:302-314`),
  and its streak ends when a run scores it (`:251`). The 3-weeks-offline probe gives up nothing, and the next day's data scores every record. KILLED: network
  not a stop, any status-0 counted as network, give-up on the same day, streak never set, streak never cleared. The TR1b MINORs are closed: the 422 rule, the
  §20.5 `n`/decided text, and the guards (price 0, split both ways, the `appendLocked` gate, the info-only store rule) are each killed by a test.
- **422 walk** (`track.go:160-176`). Old-only (sessions 51-55 days old): one request, then `422`. The retry span is empty, so both sessions score `source:daily`
  (probe). Recent: `failed`, the record waits (`TestTrackJobRecent422Waits`). Mixed: retried without the >50-day sessions. If that works, the old
  sessions score from daily bars and the recent ones from 5m bars (`TestTrackJob422RetriesWithoutOldSessions`). A 422 on the retry leaves the whole code
  waiting, `codes_failed` (probe; the builder's decision). A network error on the retry stops the run.
- **§20.5 decided** (`protocol.md:1619-1623`) is word for word P16a. It matches Q123's denominator for buy/add. With the per-group raw counts, the phone can
  sum each verdict across model × trigger × confidence (Q124) and apply Q122/Q123 without server help.
- **No regression.** KILLED: look-ahead `>`→`>=`, target before stop, a target in the fill bar counted, a gap fill not counted, `apply` overwriting a score,
  `After` ignored, stale store used (score and info-only), the info cap, info for `no_session`, the 50-call cap, unknown-with-daily scoring, give-up age.
  Job registration (`stocks.go:162`), the token check (httpapi test) and the history single-writer path are unchanged since TR1b.

## Findings
- MINOR (attack 2): **with a restart every day, the give-up has no limit.** `failStreaks` is in memory (`track.go:296`). If the laptop shuts down every
  night, the systemd user service starts fresh each day, so the earlier-date failure is never seen. Probe: one code with 403 on both 5m and 1d, not in the
  store, restarted each trading day. Result: 79 runs, 79 requests, never `unscorable`, still `pending` and still asked. Without restarts it gives up after 11 runs. The cost is
  1 paced request per failing code per run, with no wrong score and no frozen data, so this can be deferred. It is still a P16 gap ("bounded Yahoo use").
  Fix: also give up any record still failing 30 trading days after its session, whatever the streak (or save `since` in the history file).
- MINOR: **two isolated failures on either side of an offline gap give records up.** Probe: one 403 on 10-14, laptop suspended, one 403 again on 11-05.
  The 10-13 record becomes `unscorable` although its 5m bars still exist. P16a's intent is "Yahoo keeps failing". Fix: also keep the date of the last
  failure, and restart the streak when that date is before the previous trading day.
- MINOR (a test that guards nothing): the mutant `tooOld` → any status ≥ 400 SURVIVED. With it, a 403 on a window of 50-58-day sessions falls back to a
  frozen daily score. Add a row: a 403 on a 52-day session waits.
- MINOR (doc): `protocol.md:1610-1611` contradicts itself. "(or, for info_3d only, whenever it holds the code)" is stale; the code
  (`track.go:202-205`) and the next clause say "only when current". Delete the stale clause.
- NIT: a body-read error after a 200 (connection reset mid-body, `yahoo.go:78`) counts as a code failure, not a network stop. Open TR1b NITs:
  `info3dPct` is used only by tests, and `register` does not check that `After` names a real job.

VERDICT: APPROVED WITH MINORS. No BLOCKER and no MAJOR; the MINORs can go into a follow-up (the first is the one to schedule).
