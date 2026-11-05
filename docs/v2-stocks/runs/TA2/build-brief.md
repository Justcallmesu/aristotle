# TA2 — phone: Analyze button + verdict card on the stock page

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/v2-stocks/spec.md` (Q96-Q103, P5-P7, Q17, Q91), `docs/protocol.md`
**§20.1 Analyzer** (your contract) and `docs/v2-stocks/runs/TA1/build-report.md`; the server code is uncommitted and under review — READ ONLY
`server/internal/stocks/{analyzer,verdict}.go` for the exact JSON; do not edit or run it. `docs/design.md`, `docs/design/v2/stocks-notes.md` +
`docs/design/v2/design-notes.md` (no board screen exists for this: design it in the same language — grouped surfaces, Soft corners, Q91 palette
only on values, arrow + sign). Code: `ui/stocks/StockDetailScreen.kt`, `StockDetailViewModel.kt`, `data/stocks/**` (TS3, committed 9c7ae80).

## Do
1. **Analyze section** on the stock page (after the Today card, before the holding block): idle → "Analyze" button + one line "AI verdict for
   today · uses your Claude limits · ~1 min"; running → progress with elapsed time and "This takes about a minute", can leave the page (the job
   runs on the PC; on return it shows the state); done → the **verdict card**; failed → error text per code (`hermes_unavailable`,
   `analyzer_not_set_up` with one line telling the user the profile is missing, `no_data`, busy/409/429, timeout, invalid verdict) + Retry.
2. **Verdict card (Q98):** big verdict word (Buy today / Wait / Avoid; Add / Hold / Sell), confidence chip (low/med/high), entry zone, target,
   stop (Rupiah, with % from the current price), summary, 3-5 reasons each with source chips (`news:N` → title + time, opens the link;
   indicator sources as plain labels), "data as of HH:mm", model + duration in small muted text, flags (e.g. repaired) as a quiet note, and a
   fixed disclaimer line "Analysis only, not financial advice" (Q17) in EN/ID. A kept result older than today's session shows "From <date> —
   analyze again". "Analyze again" button on a done card. Verdict colours: Buy/Add = stock-up tone, Avoid/Sell = stock-down tone, Wait/Hold = ink;
   colour never the only cue.
3. Send `lang` from the phone locale (Q101: "id" for Indonesian, else "en"). Poll `GET /v1/stocks/analysis/{code}` every 3 s only while running
   AND the page is visible; stop at done/failed; read once on page show. Uses `LinkState.serverEndpoint` (works while Hermes is down for the
   GET; POST will answer 503 `hermes_unavailable`).
4. Pure tests: verdict JSON parsing (fixtures from the server's tests/`verdict_test.go` expectations), state machine (idle/running/done/failed,
   stale-day rule), polling lifecycle (fake clock), price % maths, error-code mapping, lang choice.

## Hard rules
Files: `android/**`, `docs/v2-stocks/runs/TA2/build-report.md`. strings append-only EN+ID. No new libraries. Do NOT edit `server/**`,
`docs/protocol.md`, `CHANGELOG.md` (the PM adds the Phone line). No commits; never git checkout/restore/stash/reset. No adb; never connect to a
server. Gate after your LAST edit: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TA2` exits 0. Report ≤ 12 lines, with
a "Doc text" section holding the one CHANGELOG Phone line.
