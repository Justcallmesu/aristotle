# DS5 build report — Pair and Devices views

Result: both views built; 42 own tests, lint and typecheck green. Nothing outside my folders was edited.

## Files (all under `desktop/ui/src/views/`)
- `pair/PairView.tsx` (view + `PairFlow`, `CodePanel`, `Addresses`), `pair/QrTile.tsx`, `pair/qr.ts` (matrix check), `pair/useCountdown.ts`, `pair/pair.css`, `pair/PairView.test.tsx` (20 tests)
- `devices/DevicesView.tsx`, `devices/time.ts` (+ `time.test.ts`, 5 tests), `devices/devices.css`, `devices/DevicesView.test.tsx` (17 tests)
- `pair/preview/{index.html,main.tsx}`: dev-only screenshot harness (real Shell + view over a fake transport, `?state=&theme=`). Not bundled by `vite build` (only `/index.html`), no new dependency.
- `docs/desktop/runs/DS5/shots/*.png`: 15 states x light/dark.

## States -> test -> screenshot
Screenshots are `shots/<name>-light.png` and `-dark.png`.

| design.md state | Test (describe > it) | Shot |
|---|---|---|
| Pair loading | before a code > shows the loading word before the first poll | – (text only) |
| Pair 1 before a code (opening issues nothing) | opening the view shows the button and issues nothing; sends the optional name, trimmed | pair-before |
| Pair 1 name starts with `-` | a name starting with - shows the error and keeps the button off | pair-name-error |
| Pair 2 making a code | making a code > busy label, button off until the reply | pair-busy |
| Pair 3 code (QR, countdown, bar, fingerprint, addresses, copy) | code > shows QR rects…; copies the raw 8-character code; ticks each second…; Make a new code issues another code; draws no QR tile when qr_modules is absent | pair-code |
| Pair 4 expired (all dropped from DOM) | expired > drops code, QR and fingerprint…; already-past expires_at | pair-expired |
| Pair 5 no listener (`qr_error`) | no listener: dead tile, explanation, qr_error and Try again | pair-no-listener |
| Pair 6 failed (`CommandFailed`, form stays) | a failed command shows CommandFailed and keeps the form | pair-failed |
| Pair needs-server (Start offered) | needs-server state when the server is not running | pair-needs-server |
| Pair mismatch (button off + `dis_mismatch`) | mismatch: the button is off and the reason is written | pair-mismatch |
| Tray `--pair` intent | the --pair start intent issues a code at once; under a mismatch issues nothing | – |
| P11 unmount / no logging | unmounting drops the code…; never logs to the console | – |
| Devices list (name, id, paired, last seen + ISO title, grant tag, CLI order, `—`) | list > shows name, id…; keeps the CLI order; writes Indonesian dates; shows — for unreadable…; just now | dev-list (780 px: Paired/Terminal hidden by the container query), dev-list-wide-light (1100 px: all columns) |
| Devices empty | states > empty: cause, then the one action, which opens Pair | dev-empty |
| Devices needs-server / not installed | needs-server…; not installed: needs-server without a Start button | dev-needs-server |
| Devices mismatch | mismatch: Revoke is off and the reason sits under the table | dev-mismatch |
| Devices read failed (`devices == null`) | devices unread (null) shows — | – |
| Devices loading | states > loading before the first poll | – |
| Revoke dialog (D15) | revoke > opens the D15 dialog on Cancel focus; Cancel sends nothing; Escape sends nothing, focus returns | dev-revoke |
| Revoke confirm (no optimistic delete) | confirm runs devices_revoke once…; the row disappears when the next snapshot no longer lists it | – |
| Revoke failed | a failed revoke shows CommandFailed and keeps the row | dev-failed |
| Time text | `time.test.ts` (now / min / today / date EN+ID / dmy) | – |

## Keys added
None. Every key the two views use already exists in `en.json` and `id.json`.

## RED evidence
Scratch copies in `~/.hermes/profiles/pm/cache/scratch/ds5/`; each mutation was restored afterwards (no git).
- Expiry never fires (`if (seconds === 0) onExpire()` -> `if (false)`): 2 failures (expired drops code; past `expires_at`).
- Minute threshold 60 -> 30 in `relativeTime`: 3 failures (time test, list, Indonesian list).
- Dialog `onCancel` wired to confirm: 2 failures (Cancel sends nothing; Escape sends nothing).
The tests were written together with the code, so "RED first" is shown by these mutation runs, not by a failing run before the code existed.

## Gate output (own scope)
- `cd desktop/ui && timeout 600 npx vitest run src/views/pair src/views/devices` -> 3 files, 42 tests passed.
- `npm run lint` -> clean. `npm run typecheck` -> clean.

## Design notes and decisions
- `qr_modules`: read through a local optional-field cast in `pair/qr.ts`; compiles with and without DS1d's `types.ts` change (it landed during the run). Rows must be equal-length `0`/`1` strings, else no tile is drawn and the code is still shown for typing. Design.md has no string for this case, so the report asks for one if wanted.
- The pairing reply is never kept by `useCommand` (the command stores it in the view state and returns `null`), so the code is not held after expiry or unmount.
- Countdown: visible text ticks every second. A separate `sr-only` `aria-live="polite"` region shows minutes left (`pair_expires` + `dur_min`), which changes only at minute boundaries.
- A stray `--pair` intent while the server is not running is acknowledged and dropped, so a code is not issued later when the server starts.
- Paired column `dmy` is written as `Aug 9, 2026` (EN) / `9 Agu 2026` (ID); design.md does not define the exact format.
- Grant tags come from one `terminal status` read on mount and when the device id list changes (no polling); on failure the last good set stays, `—` where none.
- Revoke buttons carry `aria-label="Revoke… <name>"` so each row is distinguishable.
- `.reason-hint` is defined in both view CSS files (the shared stylesheet has no field/table/QR/hint classes; I did not touch it).

## Shared-code issues (not fixed, for the PM)
- `styles/tokens.css` follows `prefers-color-scheme` only; there is no `data-theme` attribute, unlike design.md §1. Screenshots use CDP media emulation.
- Shared CSS has no classes for fields, tables, grouped rows or the QR tile; each view brings its own CSS. Worth hoisting if DS4/DS6 duplicate it.
- Screenshot caveat: the light set and `pair-before-dark` are 960x640; the other dark shots were taken at 780x437 (the browser tool dropped the viewport override), so they show the narrow-container layout (208 px QR tile, Paired/Terminal columns hidden). In the harness the sidebar highlights "Status" (route race in the preview page only).
