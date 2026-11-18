# Desktop app — tickets (approved 2026-11-18)

Spec: `docs/desktop/spec.md` (D0-D16, P1-P11). Gates/roles: `PROJECT.md`. Runs: `docs/desktop/runs/<id>/`.
Routes: build `std` unless noted; every review `deep`, fresh context, never the author.

| Id | Title | Depends | Side | Route | Status |
|---|---|---|---|---|---|
| DS0 | **User:** `sudo pacman -Syu` (fixes rust 1.99.1 vs llvm-libs 22, D11); PM then checks `rustc --version` | - | user | - | DONE (2026-11-18: kernel 7.3.4, rustc 1.100.0) |
| DS1 | Server: `--json` on the read commands and the action commands; new `start` / `stop` / `restart` (P9) | - | server | std | DONE (gate GREEN 883, PM mutants 3/3 killed, deep review APPROVED-WITH-FINDINGS; minors -> DS1b) |
| DS1b | DS1 review minors before DS3 parses the contract: MINOR-1 one unit spelling (`UnitName`), MINOR-2 optional status fields marked in §22 + old-server fixture, MINOR-3 one terminal marker constant; NITs (URL loop dup, stub helper x3, `pair --name --json`, usage lines, `service:"unknown"` in §22, dead `_ = srv`) | DS1 | server | std | DONE (gate GREEN 890, PM mutants 2/2 killed; no own review: the DS3 review re-checks this diff) |
| DS2 | Spike: Tauri v2 tray + window on KDE (throwaway, `spikes/tauri-tray/`) | DS0 | spike | std | BUILT, stop condition hit: no tray left click, window memory not freed -> D17/D18 to the user; tray visibility check by the user |
| DSD | Design pass: mockups of every view (real states, light/dark, EN/ID), tray icon set, tokens; user picks; then consolidated into `docs/desktop/design.md` | - | design | std | BUILT (board + notes, slop 0/10); waiting on the user's picks (design-notes §9, 1-12; 11 settled by P13) |
| DS2b | Spike: `ksni` tray, left click launches the window app (D17/D18); memory, single instance, real-click check | DS2 | spike | std | BUILT (icon visible, Activate via D-Bus launches the child, tray ~7 MB, single instance, reaped); **real left click: user check pending** |
| DS1c | Server: `status --json` `installed` field (P13) + fixture + §22 | DS1b | server | std | DONE (aa9c5a7; gate GREEN 893, PM mutant killed; no own review: the DS3 review re-checks it) |
| DS3 | Desktop core + tray: `desktop/` Rust workspace (P16), `core` (CLI runner, §22 types, poller, tray state, notification diff incl. P15, strings P10, version check P5), `tray` bin (ksni, left click + menu per P14, notifications), desktop gate script | DS1c, DS2b | desktop | std | TODO |
| DSDc | Consolidate the picked design (D19) into binding `docs/desktop/design.md` (tokens as CSS variables, type, spacing, components per view, only chosen options) | DSD | design | std | TODO |
| DS3w | Window app skeleton: `app` crate (Tauri 2.12.6, single instance + argument hand-off P14, expected-stop stamp P15, P4 capabilities/CSP, process exits on window close), `desktop/ui` React+TS+Vite + Vitest + ESLint, design tokens + fonts from `design.md`, shell 2 navigation with empty views, IPC to `core`; gate gains npm steps | DS3, DSDc | desktop | std | TODO |
| DSI | Tray icon set: green/amber/red from the app icon, heavier 16 px variant (D19-5); user approves on the real panel | DS3 | design | std | TODO |
| DS4 | UI: Status, Service, Logs | DS3w | desktop | std | TODO |
| DS5 | UI: Pair (QR + countdown), Devices (revoke) | DS3w | desktop | std | TODO |
| DS6 | UI: Terminal (on/off, grants, open terminals; D7 confirms) | DS3w | desktop | std | TODO |
| DS7 | Bundle + installer + release + second PC | DS4-DS6 | scripts | std | TODO |

DS1 and DS2 can run in parallel (disjoint dirs). DS4, DS5, DS6 can run in parallel after DS3 (disjoint view folders;
each adds only its own strings keys, merged by the PM).

## DS1 — server `--json` + start/stop/restart
**Goal:** the CLI is the app's whole API (D10, P9). Go stays the only owner of the shapes.
- `--json` on: `status`, `version`, `devices list`, `terminal status`, `pair` (adds `qr_payload` = `pairing.Payload(...)`, P11),
  and on the actions `devices revoke|grant-terminal|ungrant-terminal <id>`, `terminal enable|disable`, `start|stop|restart`.
  Output: one JSON object on stdout; errors as `{"error":"..."}` on stdout with the same non-zero exit code as today.
  The object is the control result already returned (`server/internal/app/controlcmds.go:125-149`,
  `server/internal/app/terminalcmds.go:54-60`, `server/internal/devices/devices.go:46-51`), not a new shape.
- `status --json` must answer when the server is down (service state from systemd + `"server":"not-running"`), mirroring
  `status --porcelain` (P22, `docs/release/spec.md`). Exit code identical to plain `status`.
- `start|stop|restart`: `systemctl --user <verb> aristotle-server` through the existing `service.Manager`
  (`server/internal/service/service.go:134`); refused inside a remote terminal like the other risky commands.
- Plain text output of every command unchanged byte for byte.
**Pattern:** `status --porcelain` (IN1) for the flag + "server down" path; `runTerminal` (`server/internal/app/terminalcmds.go:157`).
**Acceptance:** Go tests per command: JSON decodes into the documented fields; plain output unchanged (golden); error path JSON +
exit code; `pair --json` never writes the code to stderr/log; `start|stop|restart` call the runner with exact args (stub runner);
usage text lists the new flags/commands. Contract fixtures saved under `server/internal/app/testdata/json/` for DS3 to reuse.
**Verify:** `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/desktop/runs/DS1/gate`.
**PM mutants:** drop `qr_payload`; `status --json` with server down returns exit 0; `stop` maps to `start`.
**Stop and ask if:** any existing plain output must change, or a control result carries a secret beyond the pairing code.

## DS2 — Tauri tray spike (throwaway)
**Goal:** prove D2/D4/D9 on this KDE before building on it. Never imported by product code.
- Tauri v2 app: tray icon that switches between 3 coloured icons every 2 s, menu (Open, Quit), click opens a window,
  closing the window destroys the webview and a later click recreates it; one desktop notification.
- Record in `docs/spikes/desktop-tray.md`: Tauri/crate versions; which appindicator lib it loads (libappindicator3 vs ayatana)
  and whether the AUR/pacman package list must change; tray left-click behaviour on KDE (does SNI deliver the click, or
  only the menu?); RSS idle with window closed vs open; `npm run tauri build` time and output binary size + `ldd` list
  (runtime deps for DS7); GNOME note if cheap.
**Acceptance:** the spike doc with each item VERIFIED or a stated failure; screenshots of tray + menu.
**Verify:** PM runs the built binary on this PC and checks tray, click, close/reopen, notification by eye.
**Stop and ask if:** the tray icon or left-click does not work on KDE (re-decide D2/D4), or a system package must be installed.

## DS3 — desktop core + gate
**Goal:** the non-visual app: everything the views and the tray need, testable without a desktop session.
- `desktop/` (P2): Tauri v2 + React + TS + Vite (D13); app id/name "Aristotle Server" (D14); P4 capabilities (only own commands,
  strict CSP, no shell/fs plugins).
- CLI runner: `$HOME/.local/bin/aristotle-server` (where `scripts/install.sh:23-24` installs it), fixed argv, no shell, timeout;
  parses DS1 JSON into Rust types tested against DS1's fixtures; missing binary / service not installed -> red state (P3).
- Version check (P5). Poller every 5 s (P1); measures one `status` call and records it in the report (spec risk).
- Pure functions + unit tests: `tray_state(status) -> Green|Amber|Red`; `diff(prev, next) -> [Notification]` for the four D5
  events, "stopped unexpectedly" only without an app-issued stop; first poll never notifies; strings `en`/`id` by locale (P10);
  notification text never contains a pairing code (P11, test).
- Tray (D4) + window lifecycle (D9) + XDG autostart file template (D6; installed by DS7). Tray Stop -> confirm (D15).
- Desktop gate script `docs/desktop/gates.sh` (P6) and a `desktop` option for the project gate.
**Acceptance:** gate GREEN; tests for each pure function incl. first-poll, flapping Hermes (one notification per transition),
app-issued stop silent; runner never uses a shell (test with an argument containing `;`).
**Verify:** `bash docs/desktop/gates.sh docs/desktop/runs/DS3/gate`.
**PM mutants:** notify on first poll; treat app-issued stop as unexpected; Amber mapped to Green.

## DS4 / DS5 / DS6 — views
Shared rules: React function components, state from the Rust core via events/commands (no polling in the UI), strings in
`en`/`id` files only, light/dark from the system (P7), keyboard reachable, `antislop-ui` + `frontend-ui-engineering` loaded.
- **DS4 Status, Service, Logs:** status card (service, version, listeners, Hermes); Start / Stop / Restart with D15 confirms;
  Logs streams `aristotle-server logs -f` (P9), child killed when the view or window closes (test), capped line buffer.
- **DS5 Pair, Devices:** Pair runs `pair --json`, renders `qr_payload` with a QR library, code + addresses + fingerprint,
  countdown, "expired - new code" state; code dropped from memory when the view closes (P11). Devices list with last seen,
  Revoke with D15 confirm.
- **DS6 Terminal:** enabled state, grants per device, open terminals (device, since, listener); Enable and Grant behind the D7
  dialog (full warning text from the CLI, red button, no "don't ask again"); Disable / Ungrant without the big dialog.
**Acceptance (each):** component tests (Vitest + Testing Library) for every confirm path (Cancel sends nothing), empty/error
states, `id` strings present for every `en` key; screenshots light + dark in the run dir.
**Verify:** `bash docs/desktop/gates.sh docs/desktop/runs/DS<n>/gate`; PM opens the screenshots.
**PM mutants:** confirm Cancel still sends; logs child not killed on close (DS4); D7 dialog skippable (DS6).

## DS7 — bundle, installer, release, second PC
- `scripts/release.sh` builds the desktop binary (`tauri build`, no bundler formats), adds it + icons + `.desktop` files to the
  bundle, SHA256SUMS covers them.
- Bundle `scripts/install.sh`: if `webkit2gtk-4.1` and a graphical session lib are present, install the binary to
  `~/.local/bin`, an app-menu entry (`~/.local/share/applications`) and an autostart entry (`~/.config/autostart`), restart the
  running app; otherwise skip with one line (D12). `uninstall` removes them (P8).
- Tests with stubs in `scripts/test/` (both branches, uninstall cleanup). README install section updated.
- Release (version per the user) and install on `cachyos-asus-1` over ssh; record its desktop environment and webkit presence
  (spec risk). Real-PC check by the user: tray colours, pair a phone from the window, a notification.
**Verify:** `bash scripts/test/run.sh` + full project gate + desktop gate.

## Deferred / later
| Id | Item | Source |
|---|---|---|
| DL1 | Settings editor (port, LAN interfaces) | D3 |
| DL2 | Stocks view/settings on the desktop | D3 |
| DL3 | Server push channel instead of polling | P1 |

## Log
- 2026-11-18 Spec + tickets committed (f9213fe). Baseline `runs/baseline` GREEN (server, 805 Go tests). Wave 1 dispatched in
  parallel: DS1 (std, `runs/DS1/build-brief.md`), DS2 (std, `runs/DS2/build-brief.md`); `pre.txt` snapshots saved.
  Project-local npm/cargo dependencies allowed for DS2; no system installs.
