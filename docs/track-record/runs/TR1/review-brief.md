# TR1 — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the uncommitted server diff on top of `06a6723`
(`git status --short -- server docs/protocol.md`; new files `server/internal/stocks/{verdicts,score,track}.go` + tests). Inputs:
`docs/track-record/spec.md` (Q116-Q124, P10-P15), `docs/track-record/runs/TR0/report.md`, `runs/TR1/{build-brief,build-report}.md`,
`docs/protocol.md` §20.5.

## Already checked by the PM
`runs/pm-gate-tr1` GREEN (711 Go), `-race` ok on stocks + httpapi. Mutations (`runs/TR1/pm-check.log`): look-ahead removed -> 3 table rows
fail; target-before-stop in one bar -> 5 rows; stop in the fill bar ignored -> 2 rows + 2 job tests. All restored.

## Attack list
1. **Scoring truth.** Walk the scorer by hand on tricky sessions: gap-up open above the target with the entry never touched; gap-down
   through the stop before any fill; entry zone touched only in the closing auction (daily close vs last 5-minute bar, TR0 8d); a call made
   during the lunch break; a call at 15:55; Friday short session; a code suspended that day; a stock split inside the 3-day window
   (info_3d). Does each outcome match Q117-Q120a and P12? Is `closed` vs `right` vs `would_have` used exactly as the spec says?
2. **History integrity.** `keep` is the only writer; seed-once really once (restart, newer version, unusable file); scores attached exactly
   once (P13: never recomputed), including when a catch-up run and a manual job trigger overlap; append under the analyzer lock vs the
   track job reading.
3. **Yahoo budget.** One intraday request per code per run, pacing, the 58-day skip, a block stops the run, a network error leaves the
   record for next time (P15) — can any path loop or hammer Yahoo (e.g. a code that always errors gets retried every run forever)?
4. **Endpoint.** Auth, counts equal the records, `?code` filters calls only, cap 50, no prompt/reason text leaks, shape matches §20.5.
5. **Job.** Registered with `TradingDays: true` and catch-up as P11 says; editable `on` only; listed in `/jobs`; weekend/holiday tests real.
6. Code shape per `PROJECT.md` (small named functions, no duplication with bars.go / analyzer.go state-file code), logs codes/counts only,
   tests that guard nothing.

## Rules
Read-only: write ONLY `docs/track-record/runs/TR1/review-report.md`. Probes and mutations on a scratch COPY of `server/`. Never the real
Yahoo, Hermes, port 8760 or state dir. No git checkout/restore/stash/reset/commit. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified (file:line), Findings (BLOCKER/MAJOR/MINOR/NIT + fix), `VERDICT: ...`. Under ~40 lines.
