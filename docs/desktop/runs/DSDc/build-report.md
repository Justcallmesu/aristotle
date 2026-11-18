# DSDc build report

Deliverable: `docs/desktop/design.md` (binding; only the D19 picks, no variants, no Tweaks). Nothing run, rendered or downloaded; the
only checks were scripted: board `I18N` en/id parity (170/170) and `{param}` parity for every row, contrast numbers recomputed
(match design-notes §2 for the new pairs).

## What went in
§0 the 11 D19 picks · §1 tokens (light + dark CSS, exact values, role rules, contrast trap) · §2 type scale, spacing, radii, elevation, motion + reduced motion ·
§3 components (shell 2, state light, buttons/fields/switch/tag, banners, grouped rows/tables, dialogs incl. D7 and D15 + the new turn-off dialog, QR block, log viewer) ·
§4 views: one state model (§22 field per state), action/error states, then Status, Service, Logs, Pair, Devices, Terminal (layout, every state, empty and error) ·
§5 string table EN + ID (all 170 board keys, 190 rows) · §6 tray and notifications · §7 accessibility (focus order, landmarks, 22 contrast pairs) ·
§8 fonts: files, OFL, `desktop/ui/src/assets/fonts/`, Literata URL + exact file (`Literata[opsz,wght].ttf`, raw URL in the doc, not downloaded).

## Conflicts on the board (listed; each resolution is in the doc and marked, override welcome)
1. **Version mismatch uses `protocol`; spec P5 (corrected 2026-11-18) compares `version`.** Board: `proto` field, `mm_body` "speaks protocol {srv}…", `protocol`, `app_expects` keys. Doc follows P5: `mm_body` reworded (⚠), `app_expects` reworded (⚠), `protocol` dropped (`status --json` has no such field).
2. **"Not installed" drawn from `enabled:"not-found"`; P13 / §22 say `installed`.** `status_not_installed.json` has `"enabled":"enabled","installed":false`. Doc reads `installed`; "Starts at login" shows `en_not-found` whenever `installed` is false.
3. **`n_term_b` hard-codes "(LAN)"** but `open[].listener` can be tailscale. Doc: `({via})` = listener word (⚠).
4. **Tray `m_stop` is "Stop server"; P14 says the item is "Stop server…"** (opens the window with the D15 dialog). Doc: "Stop server…" (⚠).
5. **D19-8 (confirm "Turn off" when terminals are open) has no board strings or drawing.** Added `d_toff_t`, `d_toff_b` (★) and the dialog row in §3.6.
6. **Board has no state for §22 values it must handle:** `server:"no-answer"`, binary missing (DS3 "red"), `service`/`enabled` `"unknown"`, absent `result.hermes` (sparse fixture; the board code would throw), running with no `listeners`, command failure `{"error"}`, log stream ended, `pair --name` starting with `-`, loading before the first poll. 20 ★ keys cover them (incl. nav_label, copied). **Decisions I took that DS3's `tray_state` must match:** `no-answer` → red; `hermes` absent → green (nothing known to be wrong, no sub line); `service:"unknown"` with a running server → red. Check against DS3.
7. **D7 enable dialog on the board always says "No phone holds a grant yet"** (`d7_nogrant`), false when `granted` is non-empty. Doc shows it only when `granted` is empty.
8. **Off switch thumb**: board colours it `--outline` on `--c-highest` = 2.89 light (< 3). Doc uses `--on-surface-v` (5.61). Deviation from the board.
9. **Small button radius 8 on the board**, not in the radius scale (4/12/16/20/28). Doc: 12.
10. **Board `no_listen` says "while the server is stopped"**; wrong when the server runs with no listener. Added `no_listen_up` (★).
11. **Dark theme danger button is salmon** (design-notes deviation 4 was not among the 12 choices). Kept as drawn, stated in §3.3.

## Not decided by D19, taken from design-notes as is (flag if unwanted)
Minimum window 720 × 520 and sidebar 168 px under 800 px (board simulated 720); pair countdown total 300 s (verified `pairing.TTL = 5 min`, `server/internal/pairing/pairing.go:27`); Wrap resets to on each time Logs opens (no persistence; P3 has no settings); Copy copies the raw 8-character code; log box `aria-live="off"`.

## Gaps for other tickets
- Literata size not known (woff2 100–150 KB in design-notes is an estimate); DS3w converts to woff2 only if a converter exists already.
- `desktop/**` was not read or touched (the other worker's); no conflict seen there.
- Real KDE tray click, the 16 px icon and rendering of Literata remain user/DSI checks.
