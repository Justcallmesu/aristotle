# DS6 build report — Terminal view

## Files (all under `desktop/ui/src/views/terminal/`)
- `TerminalView.tsx` — view: loading / needs-server / body, switch, state row, banners, columns, dialogs
- `useTerminal.ts` — `terminal_status` data (+ live `open` and `devices` from the snapshot); newest request wins
- `TerminalLists.tsx` — Granted phones group (+ add row), Open terminals group
- `TerminalDialogs.tsx` — D7 enable, D7 grant, turn-off dialogs on the shared `ConfirmDialog`/`WarnBox`
- `format.ts`, `terminal.css`
- `TerminalView.test.tsx` — 28 tests
- `shots.html` + `shots.tsx` — dev-only state gallery with fake IPC (not in the app build), used for the screenshots
- Shots: `docs/desktop/runs/DS6/shots/<state>-{light,dark}.png` (26 files)

## Behaviour notes
- `terminal status` is read when the server is up, when the paired-phone ids or the polled switch change, and after each action; no timer. The snapshot `terminals` list is the live `open` list. A failed read keeps the last good data; none → `—` and the switch off.
- All actions share one `useCommand`; a failure shows the shared `CommandFailed` banner; Turn on / Grant / Turn off / Remove grant are disabled while one runs.
- Dialogs: confirm closes the dialog and runs once; Cancel/Esc/scrim send nothing.
- The D7 notification after enable/grant is not sent from the view: no IPC command exists for it (brief says it is the tray's job).

## States → test → screenshot
| design.md state | test | shot |
|---|---|---|
| loading | `loading: says so until the first poll` | loading |
| needs-server | `needs-server: stopped server shows the cause and Start server` | needs-server |
| not installed | `not-installed: needs-server without a Start button` | not-installed (the shared Shell shows its setup state; the view is not reached) |
| off | `off: switch off, description, Turn on… and no open group` | off |
| on (grants, open, hint) | `on: switch on, hint, grants and open terminals with the device name` | on |
| on, empty lists | `on with nothing open and nothing granted` | on-empty |
| unknown device / no name | `an open terminal of an unknown device…`, `a grant without a name…` | – |
| add row / all granted / no phones | three `add row…` tests | on, on-empty |
| warning | `warning: a status banner…` | warning |
| problem | `problem: only the alert, switch disabled, no lists, Check again re-reads` | problem |
| mismatch | `mismatch: … are off`, `mismatch with the switch off` | mismatch |
| failed read | `a failed read keeps the view usable…` | – |
| command failed | `command failed: alert with the server's text…` | failed |
| D7 enable dialog | `has the warning, a red confirm, no remember option, focus on Cancel`; `leaves out the no-grant line…`; `Cancel and Esc send nothing`; `the switch off→on opens the same dialog; confirm runs terminal_enable` | dialog-enable |
| D7 grant dialog | `names the phone, warns, red confirm…; Cancel sends nothing`; `confirm grants the selected phone's id` | dialog-grant |
| turn off | `with open terminals it confirms; Cancel sends nothing`; `…confirm runs terminal_disable once`; `with none open it runs at once, no dialog` | dialog-off |
| Remove grant | `Remove grant runs directly with the id` | on |
| Indonesian | `uses the ID catalog` | – |

## Keys added
None (all `t_*`, `d7_*`, `d_toff_*` keys already exist in `en.json` and `id.json`).

## RED evidence
Scratch backups, restored after each run:
- "Turn off confirms only with open terminals" changed to never confirm → 2 failed (`with open terminals it confirms; Cancel sends nothing`, `…confirm runs terminal_disable once`).
- Dialog `onCancel` made to run `terminal_enable` → 3 failed (`Cancel and Esc send nothing`, grant-dialog Cancel, turn-off Cancel).
- Restored: 28/28 pass.

## Gate output
- `timeout 600 npx vitest run src/views/terminal`: 1 file, 28 tests passed.
- `npm run lint` / `npm run typecheck`: no findings in my folder. Both fail only on `src/views/logs/logBuffer.ts(18,65)` `'cap' is declared but never read` — not mine (DS4 worker, in progress).

## Shared-code issues
- `.banner-slot` has its own 24 px margin, so `CommandFailed` and the banner inside `.content` get double spacing. I override it in `terminal.css` (`.tm-view .banner-slot`); the PM may fix it once in `shell.css`.
- No shared CSS for grouped rows/selects, so `terminal.css` defines `tm-*` classes that follow design.md §3.5. Devices/Pair may want the same classes moved to shared styles.
- `fakeTransport` in `test/fakes.tsx` throws for unknown commands and returns fixed replies, so my tests that need a changing reply override `transport.invoke` in the test.
