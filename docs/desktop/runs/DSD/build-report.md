# DSD build report — desktop app design board

Worker: product designer (subagent). Date 2026-11-18. Status: **board built and checked; awaiting the user's choices.** Nothing committed (never ran git).

## Delivered (all under `docs/design/desktop/` plus this report)
- `mockups.html`: self-contained board (one file, plain CSS/JS, no remote resources; fonts are local files in `fonts/`). ~94 KB.
- `design-notes.md`: tokens (light/dark, mapped from palette A), type scale, spacing, components per view, deviations, 12 open choices, slop audit.
- `fonts/`: Atkinson Hyperlegible Next + Mono 400/500/600 TTFs copied from `android/app/src/main/res/font/` (OFL).
- `shots/`: 76 PNGs, light and dark (list below).

## What is on the board
1. **Live window**, 960 x 640, clickable: sidebar/tabs/band nav, buttons, dialogs (Esc, scrim, Tab trap, focus on Cancel), pairing countdown that ticks and expires, Stop/Restart in-flight bar, revoke removing a row, terminal enable/grant. Chips under it jump to every state; Tweaks bar: theme, EN/ID, shell 1/2/3, density, 960/720.
2. **Shell options** (navigation only): 1 tabs (conservative), 2 grouped sidebar + status footer (strong fit, recommended), 3 status band + tabs (divergent); shown at 960 and at 720 px.
3. **Every view, every state** (content only, light/dark, EN/ID): Status x6 (running+reachable, Hermes `not-running` with reason, Hermes `error` no answer, stopped, not installed P3, version mismatch P5), Service x6 (running, stop confirm, restart confirm, restarting, stopped, not installed), Logs x3 (follow on, follow off with N new, stopped), Pair x5 (before code, QR+code+countdown+fingerprint+addresses, expired, no listener with `qr_error`, server stopped), Devices x4 (list, empty, revoke confirm, server stopped), Terminal x6 (off, on with grant + open terminal, on + warning, `problem`, D7 enable dialog, D7 grant dialog).
4. **Tray**: icon green/amber/red at 16, 22, 24, 32 px (+16 px at 4x) on Breeze light and dark panels, KDE-style right-click menu (two variants: server up, server down), hover tooltips.
5. **Notifications**: the four D5 texts plus the D7 one, EN and ID, Plasma popup style; none contains a code, QR payload or token.

Data: every field shown is in the §22 shapes or the Go fixtures; QR is the fixture's `qr_payload`; invented values are limited to log attribute samples, the host name and the 2,000-line cap, all marked as samples/proposal.

## Open choices (details and recommendation in `design-notes.md` §9)
1. Shell: 1 / **2 (rec.)** / 3.  2. Density: **comfortable (rec.)** / compact.  3. Add an amber `warn` token: **yes**.  4. State light uses tertiary for running, error for stopped: **yes**.  5. Tray at 16 px: **tint + badge now, heavier 16 px variant in the icon ticket, approve on the real panel**.  6. Pair: **"Show pairing code" step; tray Pair issues at once**.  7. Log cap 2,000 lines, wrap on.  8. Confirm for "Turn off" terminal only when terminals are open: **yes**.  9. Dialog focus on Cancel.  10. Bundle Literata woff2 (needs an approved download) or Atkinson 600 titles.  11. "Not installed" relies on `enabled: not-found` (ASSUMED).  12. Stop in band/tray stays behind the D15 confirm.

## Verification — what was and was not done
**Done (real tool output):**
- Opened the board in a real browser over CDP (file URL, viewport 1500 x 1000, DPR 1). An error/console probe (`window.onerror`, `unhandledrejection`, `console.error`, `console.warn`) was injected before load: **0 entries across 58 page loads** (all views, states, themes, shells, widths, EN/ID).
- Clicked through the live prototype by script: view switch, Stop dialog (focus on Cancel, Esc closes), Stop confirm to stopped state, Start, D7 dialog (buttons: Cancel + "Turn on remote terminal"; no "ask again"/"remember" text), enable, revoke (rows 2 to 1), Pair countdown 05:00 to 04:59.
- QR: the board's own encoder output was decoded with `zbarimg` and matched the fixture `qr_payload` byte for byte (decoded from a PBM render of the same matrix; the on-screen SVG is drawn from that matrix).
- EN/ID parity: 170 keys each, none missing either way (script); no notification string contains a code.
- Contrast of the new pairs computed with the WCAG formula (design-notes §2). Palette A pairs are design.md's own numbers, not re-derived.
- Screenshots reviewed by eye for overflow/clipping; fixed: wrapped IPs, squeezed terminal rows, 720 px Pair address wrapping, shell-card alignment.

**Not done / caveats:**
- **Not a Tauri webview.** Checked in the CDP browser (Chromium-based) only; no WebKitGTK run, so `zoom` (used for the scaled previews), container queries and `paint-order` are unverified there. They are board-only (zoom) or standard; check once in the DS4 webview.
- **Literata not rendered**: not installed here; titles show the system serif fallback. Atkinson is the real font.
- **Tray at 16 px is weak** (blob glyph, 3-4 px badge): colour carries it there. Judged from 1x screenshots, not on a real KDE panel. The glyph is the app's monochrome SVG, tinted; no KDE SNI rendering was tested.
- `not-found` for a missing unit is **assumed** from systemd behaviour, not run (open choice 11). The Hermes `not-running` reason string "hermes is not running" is from `hermes.go`; real runs may differ.
- Light/dark and EN/ID were checked on a subset by eye (all panes in light EN; dark: status/pair/terminal/dialogs/notes/tray, ID: status + D7 dialog). The other combinations render from the same code and passed the error probe, but I did not inspect each image.
- Indonesian copy has not been read by a native speaker other than the author (me).
- No keyboard-only walk-through beyond the dialog focus/Esc test; no screen reader test.

## Slop self-audit
**0 / 10** by the claude-design ten-tell rubric (no gradient, no tech hue, no tile grid, no accent rail, no blur, no stat monument, no icon topper, no centre stack, chosen type, right surface). Own weak spots: Literata fallback, tray 16 px, heavy beige so hierarchy depends on type and grouping. Table in `design-notes.md` §10.

## Shots (`docs/design/desktop/shots/`, light and dark each)
`live-{status,service,logs,pair,devices,terminal}-*`, `live-status-{herm,stopped,notinst,mismatch}-*`, `live-dialog-{stop,restart,revoke,d7-enable,d7-grant}-*`, `live-{pair-expired,pair-nolisten,devices-empty,terminal-problem,logs-paused}-*`, `shell{1,3}-status-*`, `narrow720-shell{1,2,3}-terminal-*`, `live-status-id-*`, `live-dialog-d7-enable-id-*`, `live-devices-compact-*`, `shells-*`, `tray-*`, `notifications-en-id-*`, `gallery-{status,service,logs,pair,devices,terminal}-*`, `board-full-*`.

## For the PM
- Board is a contract only after the user answers §9; build tickets should wait for choices 1, 3-5, 10, 11.
- Server side: open choice 11 may want an explicit `installed` field in `status --json`.
- I did not touch `spikes/**`, `server/**`, skills, memory or config.
