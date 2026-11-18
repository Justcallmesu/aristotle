# DS5 build brief — Pair and Devices views

First read and follow `docs/desktop/runs/views-shared-brief.md` (binding). Your folders: `desktop/ui/src/views/pair/`,
`desktop/ui/src/views/devices/`. Design: §4.6 Pair, §4.7 Devices, §3.7 QR block, §4.1/4.2.

## Pair
Opening the view shows "Show pairing code" (D19-6: opening never issues a code); the `--pair` start intent (tray "Pair a phone…")
issues one at once. A code shows: QR drawn as SVG rects from `qr_modules` (spec **P19**: the server encodes the QR; `qr_modules` is
an array of `0`/`1` row strings without quiet zone — add a 4-module margin; it is being added to `pair --json` by ticket DS1d in
parallel (it also adds `qr_modules?: string[]` to `desktop/ui/src/ipc/types.ts`): build against the §22 text in the DS1d brief
`docs/desktop/runs/DS1d/build-brief.md` and a fake IPC reply; do not edit `ipc/` yourself), the code in large mono, a countdown to `expires_at` (5 min, ticking each second, `aria-live` polite at minute
boundaries only), the addresses with their interface, the certificate fingerprint; expired state with "Make a new code"; no-listener
state with `qr_error`; needs-server state. P11: the code and payload live only in this view's component state, are dropped on unmount
and on expiry (test: after unmount nothing holds them; no console logging anywhere).

## Devices
List with name, paired, last seen (relative + absolute on hover), empty state, Revoke through `ConfirmDialog` with the D15 text
("the phone must pair again"); after success the row disappears on the next snapshot (no optimistic delete that can lie); failure
shows `CommandFailed`.

Report path: `docs/desktop/runs/DS5/build-report.md`.
