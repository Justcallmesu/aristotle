# TBA2 — phone: slice 3 (brief screen, alert notifications, holding stop/target, auto-run status)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read fully: `docs/design.md`, `docs/design/v2/stocks-notes.md` (no board: design in
the same language), `docs/v2-stocks/spec.md` (Q18, Q87, Q104-Q110, Q17), `runs/TSC0/design.md` §2, `docs/protocol.md` **§20.4 (your contract)** + §20 holdings,
`runs/TBA1/build-report.md`; server code READ ONLY (uncommitted, under review): `server/internal/stocks/{alerts,brief,levels,http}.go`. Phone code: the
foreground service / "keep connected" path and existing notification channels (v1 approvals/finish notifications), `ui/stocks/**`, `data/stocks/**`.
`runs/TSC3b/review-report.md` MINOR-6.
## Do
1. **Alerts delivery:** long-poll `GET /v1/stocks/alerts?after&wait` (≤ 50 s) from the existing connected service only while "keep connected" is on
   (Q109), else while the app is open; persist `after`; backoff on errors; stops on unpair. One notification channel "Stock alerts" (EN/ID), grouped
   per code; tap opens the stock page (or the brief). No holdings values on the lock screen (use VISIBILITY_PRIVATE + a public version without
   numbers). Missed alerts list on Stocks home ("N alerts" -> page with the ring).
2. **Brief screen:** Stocks home card "Today's brief" (ready / pending / missed / not a trading day) -> brief page per §20.4 (sections, cited levels
   by id rendered from the data, news chips like TA2, plain text only, disclaimer Q17, "Run again"), last 7 briefs.
3. **Holding stop/target (Q104):** optional fields in the holding sheet, validated as the server (tick grid, stop < avg < target), shown on the
   holding block.
4. **Auto runs:** stock page / verdict card shows "auto" runs; Settings › Stocks shows today's auto-run count "2 of 5" and the alerts job on/off;
   the Q109 line "With Keep connected off, alerts arrive only while the app is open".
5. MINOR-6: the stale notice's countdown outside the TalkBack live region.
Pure tests: alert parsing + dedupe display, long-poll lifecycle (fake clock, cancel, backoff, unpair), notification content (no numbers on public
version), brief parsing/rendering states, stop/target validation, Q109 gating.
## Hard rules
Files: `android/**`, report (with a "Doc text" CHANGELOG Phone line). strings append-only EN+ID. No new libraries. `server/**` is under review: read only.
No adb; never connect to a server. No commits; never git checkout/restore/stash/reset. Gate after your LAST edit: `GATE_ONLY=android bash
docs/v1-tickets/gates.sh docs/v2-stocks/runs/TBA2` exits 0. Report ≤ 15 lines.
