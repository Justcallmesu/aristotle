# TBA1c3 — server: apply spec P9 to `priceLike` (last tuning round)

Repo `/home/justcallmesu/project/personal/Aristotle`. TBA1c + TBA1c2 are uncommitted and are your base. Read `PROJECT.md`, spec
`docs/v2-stocks/spec.md` row **P9** (the rule you implement), `docs/v2-stocks/runs/TBA1c/review-report.md` (MINOR-1, MINOR-2, NITs), and
the real code: `server/internal/stocks/brief.go` (`briefPrompt` ~:634, `yearBeforeWords` ~:846, `priceLike`), `tba1_test.go`.

## Do
1. Remove `akhir`, `awal`, `sejak`, `since` from `yearBeforeWords` (P9). Keep: `tahun`, `thn`, `year`, `outlook`? -> remove `outlook` too
   only if a price can follow it in normal ID/EN prose ("outlook 2050" as a target): REMOVE it (P9: only unambiguous markers). Final list:
   `tahun`, `thn`, `year`, month names (EN + ID), `Q1-Q4`, `H1`, `H2`, `FY`, `semester I/II`, `kuartal I-IV`; plus `2026F/E`, full dates,
   and "right before `tahun|thn|year`" as now.
2. Refuse any non-ASCII digit (`unicode.IsDigit(r) && r > '9'`) anywhere in brief text (report it like a price). Also treat a digit group
   split by a space, NBSP (U+00A0), narrow NBSP (U+202F), thin space (U+2009) or `'` as one number (`Rp 2 050`, `10 000`, `2'050`):
   refused when the joined value is > 99.
3. Prompt RULES line: say exactly the accepted forms of P9, and "write a year as `tahun 2026` (e.g. `akhir tahun 2026`, `sejak tahun 2020`)".
4. `docs/protocol.md` §20.4 (~:1520-1527): make the no-prices paragraph match the code word for word, and fix the stranded "(EN/ID)".
5. Brief log line on a repair: add the repair reason as error codes only (e.g. `price_like`, `schema`), never the text or the number,
   so the repair rate per cause can be counted from the log (P9 (b)).
6. Tests (table): `target awal 2050`, `harga akhir 2050`, `naik sejak 2050`, `outlook 2050`, `Rp 2 050`, `Rp 10 000`, `2'050`,
   fullwidth `２０５０`, `2050` with NBSP inside -> refused; `akhir tahun 2026`, `sejak tahun 2020`, `tahun 2026`, `November 2026`,
   `Q3 2026`, `FY2025`, `2026F`, `6/11/2026`, `2026-11-05`, `MA 200`, `150%`, `naik 12 persen` -> pass. Keep every existing refused case
   refused. Also a test that the repair log carries the reason code and no number.

## Hard rules
Files: `server/**`, `docs/protocol.md`, `docs/v2-stocks/runs/TBA1c/build-report.md` (append a "TBA1c3" section, ≤ 6 lines, incl. which
new tests were RED first on a scratch copy of the pre-fix files). Not yours: `android/**` (a reviewer is reading it; never run Gradle),
`CHANGELOG.md`, other docs. No new Go modules. No commits; never `git checkout/restore/stash/reset`. Fakes only; never the real Hermes.
Avoid `bash -c`/heredocs/chained `$(...)`: script files + plain `bash <file>`. Gate after your LAST edit:
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA1c` exits 0, and
`cd server && go test -race -count=2 ./internal/stocks/...` ok.
