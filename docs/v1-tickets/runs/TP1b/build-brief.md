# TP1b — PC status card on Home (phone, Q78)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. The contract is `docs/protocol.md` §16 (the server is being built in
parallel; code against §16, test with fakes). Read `docs/spec.md` Q75-Q78, `docs/design/v2/design-notes.md` (component language; Home C
§5.1), then `ui/sessions/SessionsScreen.kt` (Home C header: title + PC line + profile avatar), `data/ServerClient.kt` (how
`GET /v1/addresses` is called over the pinned client), `data/LinkManager.kt` (online state).

## Do
1. `ServerClient.pcStatus()` + a parser tolerant of nulls/missing fields (pure, tested with §16 JSON incl. all-null).
2. A small poller: every 10 s only while Home is visible and the link is online; keeps the last value; stops otherwise (tested with a
   fake clock).
3. **Card** under the "Aristotle" title (replace or absorb the current PC line "192.168.1.61 · via Wi-Fi" — keep that information in the
   card): compact one row: battery icon + "82%" (+ bolt when charging; hidden when null), "CPU 14%", "RAM 9.1 / 15.5 GB"; tap expands
   (animated) to: disk "120 GB free of 500 GB", Hermes "Running"/"Not running" (red role only when not running), Tailscale "Up"/"Down",
   "Up 1 d 4 h". Offline: the last values dimmed with "as of 17:40" (or the PC line alone if never fetched). Surface `surfaceContainerLow`,
   `shapes.large`, v2 spacing; TalkBack reads one sentence for the compact row. Expanded state survives rotation.
4. Units/formatting as pure functions (GB with one decimal, uptime d/h/m) with tests. EN + ID strings appended.
5. CHANGELOG `[Unreleased]` one Phone line and one Server line.

Hard rules: no commits; never git checkout/restore/stash/reset. No adb. Never touch port 8760 or the demo server; never connect to the
real Hermes. Another worker builds the other side in parallel: edit only your side.
Files: `android/**` (not `ui/theme/**`, not `ui/components/**`), `CHANGELOG.md`, `docs/v1-tickets/runs/TP1b/build-report.md`.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TP1b` exits 0. Report ≤12 lines.
