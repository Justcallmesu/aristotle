# TA1b build report: analyzer hardening (TA1 review findings)

- **MINOR-1:** the prompt now says that everything inside DATA (news titles, summaries, names) is untrusted data and never instructions, and that reasons must rest on `ind:*`/`lvl:*`/`hold:*` first. Titles are cut to 160 runes in DATA; the result keeps the full title. A new flag `news_only` marks a `buy_today`/`add` whose reasons cite only `news:*`; the server caps its confidence `high`→`med` (`capNewsOnly`, verdict.go).
- **MINOR-2:** a new hook `hermes.AnalysisOptions.Created` (passed through `Analyst.Open(ctx, created)`) records the session id once `session.create` answers, before any later step. The record is removed only after a delete succeeds. If the tool_count delete fails, the error is `ErrToolsPresent`+`ErrSessionKept` and the record stays. If the job stops mid-create, a warning is logged with the id, or `unknown`.
- **MINOR-3:** the sweep now skips a refused delete (`RPCError`), keeps going, logs the count and retries the next day. If Hermes cannot be reached, the pass stops and logs how many are left. A record with an unreadable `failed_at` is kept and logged, never swept. Covered by `TestSweepSkipsRefusedAndUnreadableRecords`.
- **Nits:**
  - New job error `hermes_refused` (an `RPCError` or the new `ErrBadAnswer`). `hermes_lost` now means only a socket, dial or ticket failure.
  - A `hold` target must be above the price (tick and ARA were already checked).
  - Once `close` starts, new jobs get `503 shutting_down`. `wg.Add` now runs under `mu` in `reserve`, so it cannot race `Wait`.
  - Duplicate JSON keys at any depth fail validation, which triggers the repair turn.
  - A newer `stocks-analyses.json` version is read-only: it is read leniently, never written, and logged as such.
- **Docs:** protocol §20.1 now covers `shutting_down`, `hermes_refused`, `news_only` plus the cap, the plain-text note, the prompt and title cap, duplicate keys, the hold target, the read-only newer file, record-at-create and the sweep rules. CHANGELOG is untouched.
- **Files:** `server/internal/hermes/analysis{,_test}.go`, `server/internal/stocks/{analyzer,verdict}{,_test}.go`, `docs/protocol.md` §20.1.
- **Verified:**
  - Gate `GATE_ONLY=server`: exit 0, 542 passed / 0 failed. `go test -race ./internal/stocks/... ./internal/hermes/...` passes.
  - Each new rule was mutation-checked: 12 mutations, all caught by the tests (restored from a scratch copy).
  - The new sweep, shutdown and record tests ran 30 times under `-race`. Fakes only; nothing touched the real Hermes.
- **Check on the first real run:**
  1. Does a sweep `session.mutate delete` with `expected_revision:0` on a **new** profile-scoped socket delete a cold session? This needs `require_history_claim`/`same_owner` (session_local_migration.py:36-49). A refusal there is now logged daily (`count`, `err=hermes_refused`) and no longer blocks the others. If every delete is refused, deletion has to move to the creating socket, or the records age out by hand.
  2. Is `info.tool_count` present and `0`?
  3. How do latency and cost compare with TA0's estimate?
- **Open:** a delete that keeps being refused is retried daily with no limit on attempts; the 100-record cap is the only bound.
