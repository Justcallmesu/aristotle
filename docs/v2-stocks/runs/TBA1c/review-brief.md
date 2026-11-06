# TBA1c (+ TBA1c2) — review brief (fresh reviewer, deep)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`. Review the uncommitted server diff on top of `3dac6ed`:
`git diff HEAD -- server docs/protocol.md`. Inputs: `docs/v2-stocks/runs/TBA1c/build-brief.md`, `build-brief-2.md` (TBA1c2 removed the
price-introducing words `pada|in|by|hingga|until`; the PM fixed §20.4 line ~1525 by hand), `build-report.md`, and the finding source
`runs/TBA1b/review-report.md` (MINOR-1/2, NIT-1..4). `android/**` is another ticket: out of scope.

## Already checked by the PM
`runs/pm-gate-tba1c` GREEN (664 Go), `-race` ok. Mutations (`runs/TBA1c/pm-check.log`): `pada` re-added as a year word -> TestPriceLikeTable
+ TestPriceLikeYearRules fail; failed counter save keeps runs on -> TestAutoRunsStopWhenCounterSaveFails fails.

## Attack list
1. `priceLike`: try to sneak a price 100..99999 past it in ID and EN brief prose (year words before a price, month names that are also
   words — `may`, `march`, `awal`/`akhir` before a price like "akhir 2050-an", `sejak 2050` as a price, "outlook 2050", `Q3 2050`,
   unicode digits, NBSP, `2.050,00`, `Rp 2 050`, glued prefixes). And try ordinary year text that is still refused (repair = a second Opus
   turn). Rate both risks; the prompt rule must match the code word-for-word.
2. NIT-2 runtime save failure: auto runs stop, log once, manual Analyze and the brief unaffected.
3. NIT-3 helpers: behaviour and log lines unchanged.
4. NIT-4: upgraded hit takes the firing run's score/mode; no double alert, no double Q107 count.
5. §20.4 text vs code.

## Rules
Read-only: write ONLY `docs/v2-stocks/runs/TBA1c/review-report.md`. Probes on a scratch COPY of `server/`. No git
checkout/restore/stash/reset/commit; never Gradle; never the real Hermes. Avoid `bash -c`/heredocs: script files + plain `bash`.
Report: Verified, Findings (BLOCKER/MAJOR/MINOR/NIT with file:line + fix), `VERDICT: ...`. Under ~30 lines.
