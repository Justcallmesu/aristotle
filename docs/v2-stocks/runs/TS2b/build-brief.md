# TS2b — fix round for TS2 (review CHANGES-REQUIRED)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/runs/TS2/review-report.md` (MAJOR-1, m1-m4 with file:line),
`runs/TS2/build-brief.md`, `build-report.md`, `docs/v2-stocks/spec.md` Q91/Q92, then the code it cites (`data/LinkManager.kt` link states,
`ui/stocks/**`, `data/stocks/**`, `ui/sessions/SessionsScreen.kt` Home head).

## Do
1. **MAJOR-1:** stocks work whenever the aristotle-server answers, independent of Hermes. Keep the server address in the HermesDown /
   HermesUpdated link states; one helper returns the server endpoint for Online, HermesDown, HermesUpdated (null otherwise); the Stocks ViewModel
   uses it. Market line wording per state: Hermes down/updated must NOT say the PC link is missing (stocks still load; no Hermes mention
   needed on Stocks). Tests: stocks load in HermesDown and HermesUpdated; not in Offline/Unpaired/cert-changed.
2. **m1:** when the link needs the user (cert changed, unpaired, Hermes updated), Stocks shows the same compact connection notice Chats shows
   (reuse it) with its action, so a saved Stocks mode never hides it.
3. **m2:** Holdings and News reload when the link comes (back) online and on screen show, not only once.
4. **m3:** stock gain/loss text in the light-theme holding preview block reaches ≥ 5.4:1 (adjust the token for that surface or use the
   surface the table was measured on); add the measured ratio to stocks-notes' contrast table only if you change a value.
5. **m4:** keep the 48 dp touch target but without growing Home's head (e.g. touch target via `minimumInteractiveComponentSize` overlap /
   padding inside the existing row height — measure and state the head height before/after); move `HomeModeSwitch` to its own file in `ui/sessions/`.
6. Keep the off-tick rule as §20 (no check).

## Hard rules
`android/**`, `docs/design/v2/stocks-notes.md` (contrast row only if changed), the report. strings append-only EN+ID. No commits; never git
checkout/restore/stash/reset. No adb; never connect to a server.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v2-stocks/runs/TS2b` exits 0. Report ≤ 12 lines at `runs/TS2b/build-report.md`.
