# TBA1 + TBA1b — re-review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` first. Review the WHOLE uncommitted server side of slice 3:
TBA1 (alerts, long-poll, auto runs, 08:30 brief, TSC2 minors) plus its fix round TBA1b. Scope: `git status --short -- server docs/protocol.md`
(modified + untracked; `git diff HEAD -- server docs/protocol.md` plus the untracked files `alerts.go`, `brief.go`, `levels.go`, `tba1_test.go`).
`android/**` is another ticket (TBA2, under its own review): out of scope.

Inputs: `docs/v2-stocks/runs/TBA1/{build-brief,build-report,review-report}.md` (the first review: what it verified stands unless the
fix round touched it), `runs/TBA1b/{build-brief,build-report}.md`, spec `docs/v2-stocks/spec.md` Q18, Q31, Q87, Q103-Q110, P5-P8,
contract `docs/protocol.md` §20-§20.4. Hermes source for the model pin: `~/.hermes/hermes-agent/` (read only).

## Already checked by the PM (hunt elsewhere)
`runs/pm-gate-tba1b/gate-summary.txt` GREEN (660 Go); `-race` ok. Mutations (`runs/TBA1b/pm-check.log`): brief job `TradingDays: false`
-> TestBriefNeverOnWeekendOrHoliday fails; retry after submit -> TestBriefRetriesOnlyBeforeSubmit fails; auto runs with an unsaved cap
-> TestNoAutoRunsWhenAlertsStateUnusable fails.

## Attack list
1. Each first-review finding (MAJOR-1, MINOR-2..5, nits 1-4) is really closed; nits 5, 6 and the clock-jump nit were left on purpose.
2. `priceLike` rewrite: probe it hard (ID/EN text: "support 2.050", "di 2050", "tahun 2026", "Q3 2026", "FY2025", "4,9 ribu", "Rp4.900",
   "5rb", "150%", "naik 12 persen", "MA200", "RSI 14", "1.5x", dates "6/11"). It must refuse prices and must not refuse ordinary text
   so often that most briefs fail. Report the false-refusal risk.
3. "submitted" fact: is every path after `prompt.submit` (incl. a failure inside the submit call, a timeout, ctx cancel) final, and
   every path before it retryable? Can a retry still re-spend an Opus turn? Is the 09:00 cutoff re-checked after every wake?
4. Rerank -> scan upgrade: exactly one alert and one Q107 count per (date, code, setup, dir); no double count when scan and eod both fire;
   behaviour across a restart (hits log reload).
5. Model mismatch abort: the snapshot really is read before submit; the session is deleted; the run is (or is not) counted against the
   5/day cap — say which and whether that matches Q87's intent.
6. No-persist rule: no automatic run when the alerts file cannot be saved; manual Analyze and the brief unaffected.
7. Concurrency: `-race -count=5` on `./internal/stocks/...` yourself; locks held across Hermes calls; long-poll wake vs Close.
8. Logs: no prompt, verdict, brief text, holding values or tokens in any new log line.
9. §20.4 text vs code after the doc fixes (check order, retry rule, no-prices rule).
10. Code shape per `PROJECT.md` (small named functions, no duplication); tests that guard nothing.

## Rules
Read-only: write ONLY `docs/v2-stocks/runs/TBA1b/review-report.md`. Probes and mutations on a COPY of `server/` in your scratch dir,
never in the repo. Never connect to the real Hermes or port 8760. No git checkout/restore/stash/reset/commit. Avoid `bash -c` and
heredocs; script files + plain `bash`.

## Report
Verified (file:line), Findings (BLOCKER / MAJOR / MINOR / NIT with file:line, evidence, fix), `VERDICT: APPROVED | PASS-WITH-FINDINGS |
CHANGES-REQUIRED`. Under ~40 lines.
