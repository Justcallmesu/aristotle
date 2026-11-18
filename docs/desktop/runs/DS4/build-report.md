# DS4 build report — Status, Service, Logs views

Result: all three views built. Own tests 82/82, `npm run lint` and `npm run typecheck` clean (whole project, at the time of the run). No git commands, no installs, no app launch.

## Files (all inside my folders)
- `desktop/ui/src/views/status/`: `StatusView.tsx`, `StatusView.test.tsx`, `facts.ts` (service/enabled words, relative time, listen address), `Rows.tsx` + `rows.css` (grouped rows, state row, `FailedBanner`), `useServiceAction.ts`, `status.css`, `testing.tsx` (test helpers), `shots/` (dev-only screenshot page: `index.html`, `main.tsx`, `fixtures.ts`; never imported by the app, not bundled).
- `desktop/ui/src/views/service/`: `ServiceView.tsx`, `serviceButtons.ts`, `service.css`, `ServiceView.test.tsx`.
- `desktop/ui/src/views/logs/`: `LogsView.tsx`, `logBuffer.ts` (2,000-line cap reducer), `logLine.tsx` (journal prefix + `level=` token, text nodes only), `logs.css`, `logs.test.tsx`.
- `docs/desktop/runs/DS4/shots/`: 62 PNGs (31 states × light/dark, 960×640).
- Service and Logs import the grouped-row pieces from `../status/` (inside my folders).

## i18n keys added
None. Every key the design lists was already in `en.json` / `id.json` (checked: `st_loading`, `st_noanswer`, `working_start`, `act_failed_*`, `logs_ended`, `logs_retry`, `no_listen_up`, …).

## States covered
Tests: `S` = `status/StatusView.test.tsx`, `V` = `service/ServiceView.test.tsx`, `L` = `logs/logs.test.tsx`. Shots are `shots/<name>-light.png` and `-dark.png`.

| design.md state | test | screenshot |
|---|---|---|
| Status row 0 loading | S "row 0: loading…" | status-loading |
| Row 6 Hermes reachable | S "row 6 reachable", "lists service, starts at login…" | status-running |
| Row 7 Hermes not running | S "row 7 not running" | status-hermes-down |
| Row 8 Hermes check failed (+ reason in mono) | S "row 8 error", "shows the server's Hermes reason…" | status-hermes-error |
| Row 9 `hermes` absent | S "row 9 absent" | status-hermes-unknown |
| Row 3 no answer | S "row 3 no answer", "Start is absent…" | status-no-answer |
| Row 4 stopped (Start primary) | S "row 4 stopped", "Start server: is primary…" | status-stopped |
| Row 5 outside the service | S "row 5 outside the service" | status-outside |
| Row 2 not installed (`./install.sh` + Copy, no button, P13 "Not installed") | S "row 2: not installed…" | status-not-installed (Shell shows its setup state here) |
| Row 1 no CLI | S "row 1: no CLI…" | status-no-cli |
| Version mismatch (err tag, no Start) | S "flags a version mismatch…" | status-mismatch |
| Start working / confirmed only by next poll | S "is primary, runs start, shows the working bar and stays off until the next poll" | status-start-working |
| Start failed (`{error}` and `{ok:false}`) | S "failure shows…", "a resolved { ok: false } counts as failed" | status-start-failed |
| Listeners (IPv6 bracket, iface/subnet/default route, `no_listen`, `no_listen_up`) | S "Status: listeners" ×3 | status-running, status-stopped |
| Phones/terminal summaries, navigate to Devices/Terminal, `—` when unread | S "Status: phones and terminal summaries" ×6 | status-running |
| Unknown systemctl word raw, `svc_unknown`/`en_unknown`, port `—` | S "shows — for an absent port…", "uses the unknown words…" | status-outside |
| Indonesian | S "renders … in Indonesian" | — |
| Service running (Stop/Restart on, `dis_running`) | V "running: …" | service-running |
| Service stopped (inactive and failed; Start on) | V "service inactive/failed" | service-stopped |
| Other word / unknown → all off | V "activating/deactivating/unknown", "running but service unknown", `serviceButtons` rule test | service-other-word |
| No-answer + active → Stop/Restart on | V "server not answering…" | service-no-answer |
| Not installed (`dis_notinst`) | V "not installed: all off…" | service-not-installed |
| Mismatch (`dis_mismatch`) | V "version mismatch…" | service-mismatch |
| No CLI, loading | V "no CLI…", "loading…" | status-no-cli, status-loading |
| Stop confirm: D15 text, focus on Cancel, Cancel / Esc / scrim send nothing, focus returns to opener | V "Stop opens the dialog…", "Esc on the stop dialog…", "clicking the scrim…" | service-stop-dialog |
| Stop confirm → working → next poll | V "confirming Stop runs stop once…" | service-working |
| Restart confirm (same checks) | V "Restart: dialog text…", "Esc on the restart dialog…" | service-restart-dialog |
| Start with no dialog | V "Start runs at once with no dialog…" | status-start-working |
| Command failed (`refused:` text, `{ok:false}`) | V "a failed command shows the banner…", "a resolved { ok: false }…" | service-failed |
| `--confirm stop` intent (opens once, not reopened, stopped → no dialog, status intent → none, confirm runs stop) | V "Service: --confirm stop start intent" ×5 | service-stop-intent |
| Indonesian | V "renders in Indonesian" | — |
| Logs live, follow on, wrap on | L "follow is on by default…", "wrap is on by default…" | logs-live |
| Wrap off (horizontal scroll) | L "wrap is on by default and the switch turns it off and on" | logs-nowrap |
| Paused, "N new lines", Jump to latest | L "scrolling up pauses, counts new lines, and Jump to latest resumes", "flipping the Follow switch off…" | logs-paused |
| 2,000-line cap + note | L "caps the view at 2,000 lines…", buffer tests ×2 | logs-live |
| `logs_start` on open, `logs_stop` on unmount **and** on leaving the view | L "calls logs_start…", "calls logs_stop on unmount", "calls logs_stop when the user leaves the view" | — |
| Text only, never HTML (`<script>`, `<img onerror>`) | L "renders lines as text, never as HTML" | logs-live (last line) |
| ERROR/WARN rows, bold `level=` | L "marks ERROR and WARN rows…", `parseLine` ×2 | logs-live |
| Empty box | L "shows only the empty box…" | logs-empty |
| Server stopped (Follow disabled, `logs_stopped`, lines kept) | L "Logs: server stopped" ×2 | logs-stopped |
| Stream ended / Reconnect; failed to start | L "Logs: stream ended or failed" ×2 | logs-ended, logs-start-failed |
| Indonesian | L "renders the bar and notes in Indonesian" | — |

## RED evidence
Scratch copies kept in `$TMPDIR/ds4scratch`, files restored after each run (verified 50/50 green after).
- A real bug found by the new test: the "Paused · N new lines" counter stayed 0 (a state updater read a ref that the effect had already advanced). Test `scrolling up pauses, counts new lines…` failed with `Unable to find an element with the text: Paused · 3 new lines`; fixed by reading the delta before updating.
- Mutation 1, remove `logs_stop` on cleanup: 3 tests fail (`calls logs_stop on unmount`, `…when the user leaves the view`, the Reconnect test).
- Mutation 2, allow Start for every non-active word: 5 fail (`activating`, `deactivating`, `unknown`, `running but service unknown`, `serviceButtons agrees with the tray menu rule`).
- Mutation 3, drop the 2,000 cap: 3 fail (both buffer tests, the view cap test).
- Helper bug during build (test `gate` resolved before the call): fixed in `testing.tsx`, not a product bug.

## Own gate output
- `cd desktop/ui && timeout 600 npx vitest run src/views/status src/views/service src/views/logs` → `Test Files 3 passed (3), Tests 82 passed (82)` (Status 32, Service 29, Logs 21).
- `npm run lint` → exit 0, no output beyond the npm banner. `npm run typecheck` → exit 0. (An earlier run showed `TerminalView.test.tsx` type errors from another worker; gone on the final run.)

## Choices to confirm
- **Logs "needs-server when stopped"** (brief) vs design.md 4.5: I followed design.md: when `server != running` the view stays, Follow is disabled, no Live/Paused text, last lines kept, `logs_stopped` hint. The stream is still opened (the journal is readable while the server is down).
- **Service buttons, other systemctl words**: all off with no written reason (design has no key for it; the state row and `k_service` row show the word).
- **Start "waits for the next poll"** (design 4.2): `useServiceAction` keeps the working bar and all buttons off after the command returns until the snapshot `seq` advances. Failed commands release at once.
- **Reconnect clears the lines** before restarting (the journal replays its tail; keeping them would duplicate).
- Status Start button is also hidden for row 5 only when `service` is `active`; for `unknown` it shows (design: `service != active`), which the tray rule would not offer on Service. Matches the design text literally.
- Row 1 / row 2 views never render inside the real app (the Shell swaps in `SetupState`); the views still handle both so they are correct standalone.

## Shared-code / contract issues found (not edited, for the PM)
1. **`SnapshotDto` has no granted-phones count.** Status needs `granted` for `term_summary_on`. I call `terminalStatus()` once on open and again only when the switch or open-count changes (not polling); on failure it shows `—`. A `terminal_granted` count in the snapshot would drop that call.
2. **§22 `status.result.addresses[]` has no `default_route`** (only `pair` addresses do), so the Listening rows' "default route" suffix can never appear today. I read `default_route` defensively if a server sends it. Needs a server field or a design change.
3. **`CommandFailed` sits inside `.banner-slot`** (margin `24px 24px 0`), which double-indents when used inside the padded view content. I wrapped it in `FailedBanner` (status/Rows.tsx) with `.kv-failed .banner-slot{margin:0}`. DS5/DS6 will hit the same; better fixed once in shared CSS.
4. **No shared grouped-row / state-row styles** (design 3.5). I built them in `views/status/rows.css` (`kv-*` classes). DS6's Terminal state row and group rows could reuse them or the PM can move them to `styles/components.css`.
5. `src/ipc/types.ts` shows as modified in `git status`; not by me.
6. Dev harness note: screenshots came from a throw-away `vite` dev server on `127.0.0.1:5199` (stopped) rendering `views/status/shots/index.html` with a fake transport; no real server or app was touched.
