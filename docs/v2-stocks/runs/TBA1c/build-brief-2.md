# TBA1c2 — server: drop the ambiguous year words from `priceLike` (PM brief error in TBA1c)

Repo `/home/justcallmesu/project/personal/Aristotle`. TBA1c is uncommitted and is your base (`server/internal/stocks/brief.go`,
`tba1_test.go`, `docs/protocol.md`). Read `docs/v2-stocks/runs/TBA1c/build-brief.md` and `build-report.md` first.

## Why
The TBA1c brief listed `pada`, `in`, `by`, `hingga`, `until` as year words. These are also normal price wording ("tertahan pada 2050",
"naik hingga 2050", "support in 2050", "by 2050"), and IDX prices often fall in 1990-2099, so they open a price hole. The PM's error,
not yours.

## Do
1. Remove `pada`, `in`, `by`, `hingga`, `until` (and any other word that commonly precedes a price: check your list for `di`, `at`, `ke`,
   `dari`, `to`, `from`) from the year-word list. Keep only words that cannot introduce a price: `tahun`, `year`, `since`, `sejak`,
   `sepanjang`, `akhir`, `awal`, `outlook`, month names, `H1/H2`, `semester I/II`, `kuartal I-IV`, `Q1-Q4`, `FY`, plus `2026F/E` and full dates.
2. Update the prompt's rule lines and §20.4 so the list they name matches the code exactly.
3. Tests: `pada 2050`, `hingga 2050`, `in 2050`, `by 2050`, `until 2050`, `tertahan pada 2050`, `naik hingga 2.050` refused; `sejak 2020`,
   `sepanjang 2026`, `akhir 2026`, `tahun 2026` still pass. Restore `TestPriceLikeTable`'s original `pada 2050` -> refused expectation.

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/v2-stocks/runs/TBA1c/build-report.md` (append a "TBA1c2" section, ≤ 5 lines). Not yours:
`android/**` (another worker runs Gradle there: never run Gradle), `CHANGELOG.md`. No commits; never `git checkout/restore/stash/reset`.
Fakes only. Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash <file>`. Gate after your LAST edit:
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA1c` exits 0.
