# DSD build brief — desktop app design pass (mockups board)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Spec: `docs/desktop/spec.md` (all D and P rows; the
views are D3 items 1-6, tray D4/D17, confirms D7/D15, languages D16, theme P7, secrets P11). Brand: the Android app's binding
design `docs/design.md` (palette A, type, shapes, role rules) and its icon `docs/design/icon/`, `docs/assets/icon-reference.png`.
Real data shapes: `docs/protocol.md` §22 "CLI JSON output" (being reviewed; read it from the working tree) and the structs it
names. Load the skills `claude-design`, `antislop-ui`, `frontend-ui-engineering` and follow them.

## Deliverable
`docs/design/desktop/mockups.html` (self-contained, no remote resources) + `docs/design/desktop/design-notes.md`.
- Window frame at a realistic desktop size (start 960x640; show one narrower 720 px state). The surface is **Operate/Monitor**: a
  small control panel, not a web dashboard; no hero, no feature tiles, no fake metrics.
- Screens with REAL states from the JSON shapes: Status (running + Hermes reachable; running + Hermes not running with reason;
  server stopped; service not installed P3; version mismatch banner P5), Service (start/stop/restart with the D15 confirm), Logs
  (live journal lines, follow on/off, capped buffer note), Pair (QR + code + countdown + addresses + fingerprint; expired state;
  no listener state), Devices (list with last seen, empty state, revoke confirm), Terminal (disabled; enabled with grants and open
  terminals; the D7 full-access dialog with red button and no "don't ask again"; problem state from `terminal status`).
- Navigation between the 6 views (sidebar or tabs: give 3 composition options for the shell only, conservative / strong-fit /
  divergent; the views themselves one strong design each).
- Tray: the icon in green/amber/red at 16, 22, 24 and 32 px on a light AND a dark panel (derived from the app icon, readable at
  16 px), and the right-click menu (D4) drawn as a KDE-style menu.
- Notifications (D5): the four texts in EN and ID, as a KDE notification would show them; never containing a pairing code (P11).
- Light and dark for every screen; EN/ID toggle (all strings in both; Indonesian written naturally, not word-for-word); a small
  Tweaks bar for the open choices (shell option, density).
- Only effects a Tauri webview + plain CSS can build; reuse palette A tokens from `docs/design.md` mapped to CSS variables; pick a
  font the app can bundle offline (say which and why).

## design-notes.md
Tokens (CSS variables, light/dark) mapped from palette A; type scale; spacing; component list per view; the open choices for the
user as a numbered list with your recommendation; the slop self-audit score.

## Files you may create/edit
`docs/design/desktop/**` only. `spikes/**`, `server/**`, `docs/spikes/**` belong to other workers: not yours.
Never `git add/commit/push/checkout/restore/stash/reset`. No installs. Do not edit skills, memory or agent config.

## Verify
Open the board in a headless browser if one is available (or check it parses), no console errors; screenshot the main states
(light + dark) into `docs/design/desktop/shots/`. Say exactly what was and was not verified.

## Report
`docs/desktop/runs/DSD/build-report.md`: what is on the board, the open choices list, verification, slop score.
