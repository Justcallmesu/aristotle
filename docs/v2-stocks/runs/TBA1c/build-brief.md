# TBA1c — server: brief no-prices rule tuning + TBA1b review minors

Repo `/home/justcallmesu/project/personal/Aristotle`. TBA1+TBA1b are committed (`3dac6ed`). Read `PROJECT.md`, then your input
`docs/v2-stocks/runs/TBA1b/review-report.md` (MINOR-1, MINOR-2, NIT-1..4), spec `docs/v2-stocks/spec.md` Q18, Q87, Q105, Q107, and
`docs/protocol.md` §20.4. Read the real code before editing.

## Why
`priceLike` (brief.go ~:780-844) refuses ordinary year text that the brief prompt (brief.go ~:636, "years excepted") invites. Each refusal
costs a second Opus turn (repair) every morning. The rule must still refuse every price form.

## Do
1. **MINOR-1.** Accept a year (1990-2099) when it follows a common year word or period marker, EN + ID: `tahun`, `year`, `in`, `since`,
   `by`, `until`, `sepanjang`, `akhir`, `awal`, `hingga`, `sejak`, `pada`, `outlook`, `H1`/`H2`, `semester I/II`, `kuartal I-IV`, `Q1-Q4`,
   `FY`, a month name; also `2026F`/`2026E` (forecast suffix) and full dates `6/11/2026`, `2026-11-05`. Also `MA 200`/`EMA 50`
   (indicator name + space + period). State the exact rule in the prompt in one or two lines so the model follows it.
2. **MINOR-2.** (a) A hyphen form passes only as a full `YYYY-MM-DD`; `2010-2050` is price-like. (b) Glued letters pass only for the
   prefixes `MA|EMA|SMA|RSI|FY|LQ|IDX|Q|H`; `level2050` is price-like.
3. Table test covering every probe in the review report (refused and passed lists), the new cases above, and these prices that must stay
   refused: `support 2.050`, `di 2050`, `Rp4.900`, `Rp.2050`, `IDR 2050`, `4,9 ribu`, `5rb`, `2050-an`, `range 2010-2050`, `level2050`.
4. **NIT-1 (doc).** §20.4: the real POST check order (body first; `lang` is kept even when the POST is refused), automatic runs are off
   while `stocks-alerts.json` is unusable or a save of the counter fails, a model mismatch fails the job `model_error` and counts to the cap,
   and the no-prices rule from items 1-2.
5. **NIT-2.** A failed save of the auto-run counter at run time stops automatic runs for the rest of the process (same as `persist=false`),
   logged once. Test with a write that fails after a good load.
6. **NIT-3.** Extract the duplicated brief-failure block in `runJob` and the "delete the session, else warn" pattern (analyzer.go and
   brief.go) into small named helpers. No behaviour change; the existing tests must stay green.
7. **NIT-4.** On an upgrade, the hit takes the firing scan's/eod's `score` and `mode`. Test.

## Non-goals
No change to the alerts/brief/analysis API shapes, Q107's meaning, or the retry rule. No android changes.

## Hard rules
- Files you may edit: `server/**`, `docs/protocol.md`, `docs/v2-stocks/runs/TBA1c/build-report.md`. Not yours: `android/**` (another
  worker is editing it now: never run Gradle, ignore it), `CHANGELOG.md`, other docs.
- No new Go modules. Fakes only; never connect to the real Hermes or port 8760; no real state dir.
- No commits; never `git checkout/restore/stash/reset` (keep copies in your scratch dir to undo).
- Tests seen RED first against a scratch copy of the pre-fix files; record which.
- Avoid `bash -c`, heredocs and chained `$(...)`: script files + plain `bash <file>`.
- Gate after your LAST edit: `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA1c` exits 0 and
  `cd server && go test -race -count=3 ./internal/stocks/... ./internal/hermes/...` is ok.
- Logs never contain prompt, verdict, brief text or holding values.

## Stop and report if
A year rule cannot refuse every listed price form while accepting the listed year forms (name the conflicting pair).

## Report
`docs/v2-stocks/runs/TBA1c/build-report.md`, ≤ 15 lines: per item what changed (file:line), tests added and which were RED first, gate
line + go-tests count, files touched, anything not done. Include a "Doc text" CHANGELOG line only if the user sees a change.
