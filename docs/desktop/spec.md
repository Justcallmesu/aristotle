# Desktop app (tray + window for aristotle-server) — spec

Status: **decided (D0-D16, P1-P11) 2026-11-15; tickets DRAFT in `docs/desktop/tickets.md`, awaiting user approval. Nothing dispatched.**
Request (user 2026-11-15): "a dedicated desktop server app for Aristotle so I don't need to control it from the
terminal; a desktop UI and a tray app."

## Verified current state (HEAD 8fa94dc)
- Everything the user does in a terminal today is a CLI subcommand (`server/internal/app/app.go:16-38`):
  `pair`, `devices list|revoke|grant-terminal|ungrant-terminal`, `terminal enable|disable|status`,
  `install|uninstall`, `status [--porcelain]`, `logs [-f]`, `version`.
- Runtime commands go to the running server over the control socket `<state>/control.sock`, mode 0600, one JSON
  request/response per connection, same-uid peers only (`server/internal/control/control.go:1-4`). Command names:
  `pair.start`, `devices.list`, `devices.revoke`, `status` (`server/internal/app/controlcmds.go:18-21`),
  `terminal.enable|disable|status` (`server/internal/app/terminalcmds.go:17-19`). An `Authorize` hook refuses
  processes inside a remote terminal everything outside a short allowlist (`server/internal/control/control.go:59`,
  wired at `server/internal/app/serve.go:143`).
- Service lifecycle is systemd `--user` (`server/internal/service/service.go:134`); logs via journalctl
  (`server/internal/service/service.go:36`). The service runs on this PC (`aristotle-server.service` active,
  listeners 127.0.0.1, wlan0, tailscale0 on :8760).
- Config file `$XDG_CONFIG_HOME/aristotle/config.json` (`server/internal/config/config.go:225-231`); absent on this
  PC (defaults in use).
- Linux only (`docs/release/spec.md:7-8`, Q128). Second PC `cachyos-asus-1` installed via the root installer.
- This PC: KDE Plasma (`plasmashell` running, StatusNotifierItem tray native); webkit2gtk-4.1, gtk3, gtk4, Go,
  node/npm, cargo installed. The second PC's desktop: ASSUMED KDE (CachyOS default), not checked.

- Found in round 2 discovery (2026-11-15): **the Rust toolchain on this PC is broken**: `rust 1:1.99.1-1` needs
  `libLLVM.so.23.1`, installed `llvm-libs 22.1.8-2` (a partial upgrade); `rustc --version` fails to load.
  `libappindicator3.so.1` is present (`libappindicator 12.10.1-1`); `libayatana-appindicator3` is not.
- The control socket is request/response only (no push), but the data the notifications need is readable:
  `status` (version, listeners, Hermes state; `server/internal/app/controlcmds.go:125-149`), `terminal.status` with
  `open` terminals (`server/internal/app/terminalcmds.go:54-60`), `devices.list`.

## Decisions
| Id | Decision | Notes |
|---|---|---|
| D1 | A: a separate desktop app controls the existing systemd service; the server stays headless. Clicking the tray icon opens the UI. | User 2026-11-15. Quitting the app never stops the server. One-process vs two-process reading → D9. |
| D2 | B: **Tauri** (Rust backend + TypeScript frontend). | User 2026-11-15; PM had recommended Wails v3. Consequence: the server's Go packages cannot be imported; the command shapes need one owner (D10). Adds a Rust toolchain to the gates; the toolchain is broken on this PC (D11). |
| D3 | v1 = 1 status dashboard, 2 pair with on-screen QR + countdown, 3 devices list/revoke, 4 remote terminal on/off + grants + open terminals, 5 service start/stop/restart, 6 live log viewer. Later: settings editor, stocks. | User 2026-11-15. |
| D4 | A: tray icon green (all ok) / amber (server up, Hermes unreachable) / red (server down); menu Open, Pair a phone…, Start/Stop server, Quit (Quit = UI only). | User 2026-11-15. |
| D5 | A: notify on new phone paired, phone opened a remote terminal, Hermes became unreachable, server stopped unexpectedly. | User 2026-11-15. Source of events: P1. |
| D6 | A: tray autostarts at login, hidden; closing the window hides to the tray. | User 2026-11-15. |
| D7 | A: terminal enable and grant allowed in the UI behind a confirm dialog with the CLI's full-access warning, red button, no "don't ask again", notification after the change. | User 2026-11-15. The server's remote-terminal `Authorize` guard stays. |
| D8 | A: Linux only, tested on KDE; GNOME best effort (AppIndicator extension). | User 2026-11-15. Matches Q128. |
| D9 | ~~A: one process.~~ **Superseded by D18 (2026-11-18)**: DS2 showed the webview keeps ~290 MB after close. | User 2026-11-15. |
| D10 | A: the server CLI is the app's API. `status`, `devices list`, `terminal status`, `pair` (and the action commands) gain `--json`; the Tauri app runs `aristotle-server <cmd> --json` and parses it. Go stays the only owner of the shapes. | User 2026-11-15. Server tickets come first; the JSON output is a stable contract with Go tests. |
| D11 | A: the user runs `sudo pacman -Syu` on the dev PC to fix the rust/llvm partial upgrade. Only the dev PC needs Rust. | User 2026-11-15. **Blocks the first desktop ticket until done** (server `--json` tickets can start). |
| D12 | A: the app binary ships in the release bundle; the root installer adds an app-menu entry and an XDG autostart entry only when webkit2gtk-4.1 is present; otherwise server only + a one-line note. | User 2026-11-15. |
| D13 | A: React + TypeScript + Vite frontend. | User 2026-11-15. |
| D14 | A: name "Aristotle Server", the Android app icon; tray variants tinted green/amber/red. | User 2026-11-15. |
| D15 | A: simple confirm dialog for revoke device ("the phone must pair again") and stop / restart server ("phones disconnect"); none for start, pair, refresh. | User 2026-11-15. Applies to the tray menu's Stop too. |
| D16 | A: English + Indonesian, following the system language (like the Android app's `values-in`). | User 2026-11-15. Covers the window, tray menu and notifications (P10). |
| D17 | A: own StatusNotifierItem tray with the `ksni` crate; left click opens the UI; right click shows the D4 menu. | User 2026-11-18 after DS2 (Tauri's Linux tray has no left click). Plasma calling `Activate` ASSUMED until DS2b. |
| D18 | A: two processes. `aristotle-tray` (Rust, ksni, notifications, the core poller, no webview) is autostarted and resident; left click / "Open" launches the Tauri window app `aristotle-desktop` (single instance: a second launch focuses the open window), which exits when its window closes. Both share one Rust core crate. | User 2026-11-18 ("D19. A", PM interpretation D18). Supersedes D9. Consequences: two binaries in the bundle (D12/DS7), the autostart entry starts the tray only (D6), the window app polls only while open. |
| D0 | Design diagram (closing round, below) confirmed: user answered D15/D16 without objecting to it. | 2026-11-15, logged in `docs/desktop/decisions.md`. |
| D19 | Design board picks (`docs/design/desktop/design-notes.md` §9): all recommended. 1 grouped sidebar + status footer; 2 comfortable density; 3 add amber `warn` token; 4 state light tertiary = running, error = stopped (always shape + word); 5 tray tint + badge, the icon ticket draws a heavier 16 px variant approved on the real panel; 6 Pair view shows "Show pairing code" (opening never issues a code), the tray's "Pair a phone…" issues at once; 7 logs keep 2,000 lines, wrap on; 8 "Turn off" terminal confirms only when terminals are open; 9 destructive dialogs focus Cancel; 10 bundle Literata (one OFL font download approved); 12 n/a (shell 2). 11 replaced by P13. | User 2026-11-18 "i think good enough" after the board + picks table: **PM reading = all recommended, to confirm** (stated in chat). |

## PM technical decisions (override welcome)
| Id | Decision | Why |
|---|---|---|
| P1 | No server push in v1: the app polls `status`, `devices.list`, `terminal.status` every 5 s and `systemctl --user is-active`; notifications come from diffs between polls. "Stopped unexpectedly" = not active without a stop the app itself issued. | All local, cheap; avoids a new streaming channel on the server. A 5 s delay on a notification is acceptable. |
| P2 | Code lives in a new top-level `desktop/` dir; `docs/v1-tickets/gates.sh` (or a desktop gate) gains its build/lint/test commands. | One responsibility per dir, same as `server/` and `android/`. |
| P3 | The app controls only the local server; it never edits `config.json` (settings editor is later, D3). If the service is not installed, the app shows red with "run ./install.sh" and offers no install button. | Install/uninstall stay with the installer (D3 scope). |
| P4 | Tauri security: the frontend gets only the app's own named commands (no shell/fs plugins exposed), strict CSP, no remote content. The Rust side runs `aristotle-server` by absolute path with fixed argument lists (no shell string). | A webview that can run arbitrary commands is a local privilege hole next to the remote-terminal switch. |
| P5 | The app reads `aristotle-server version --json` and compares the server `version` with its own build version (both stamped by the release script; dev builds skip the check); a mismatch shows a banner ("re-run the installer") and disables actions. Not `protocol`: that is the phone API version. | Bundle installs both together, but a hand-copied binary can drift. Corrected 2026-11-18 (was "protocol mismatch"). |
| P6 | Desktop gate: `cargo fmt --check`, `cargo clippy -- -D warnings`, `cargo test`, `npm run lint`, `npm run typecheck`, `npm test` (Vitest), `npm run tauri build`. Notification diffing and tray-state mapping are pure functions with unit tests. | Same bar as server/android: logic testable without a desktop session. |
| P7 | Theme follows the system light/dark setting. | No setting to build. |
| P8 | `uninstall` (server CLI / installer) also removes the app-menu and autostart entries it added. | No dead autostart entry pointing at a deleted binary. |
| P9 | The server CLI gains `start`, `stop`, `restart` (wrapping `systemctl --user` on the unit it already owns, `server/internal/service/service.go:134`); the app never calls systemctl directly. Logs = the existing `aristotle-server logs -f` as a child process, killed when the Logs view closes; raw journal lines, no JSON. | D10: one owner for the unit name and the service logic. |
| P10 | Strings: the frontend keeps `en` + `id` message files; the Rust side (tray menu, notifications) keeps its own small `en`/`id` table. Language = system locale (`LANG`/`LC_MESSAGES` starting with `id` → Indonesian), else English. | Tray and notifications exist without a window (D9), so they cannot read frontend strings. |
| P11 | Secrets: the pairing code and QR payload appear only in the Pair view; never in a notification, the tray, a log line or a panic message. The app never logs CLI output. `pair --json` returns the QR payload text (`pairing.Payload`) and the frontend renders it with a QR library, so the QR rules stay in Go. | Same rule as PROJECT.md "logs never contain tokens". |
| P12 | `stop` and `restart` refuse inside an Aristotle remote terminal (reusing the existing detection); `start` is allowed. | A restart from the phone's shell kills that shell mid-command. Accident guard, not a security boundary (the shell can still run systemctl). |
| P13 | `status --json` gains `installed` (bool: the unit file exists at `service.Manager.UnitPath()`); the app's "not installed" state reads it, never `enabled`. | VERIFIED 2026-11-18 on this PC: `systemctl --user is-enabled <absent unit>` prints `enabled`, exit 0, so `enabled` cannot detect a missing install (DSD choice 11 assumed `not-found`). |
| P14 | The tray has no dialog toolkit (D18: no GTK/webview). Tray menu actions that need a confirm or a view open the window app with an argument: "Stop server…" -> `aristotle-desktop --confirm stop` (Service view with the D15 dialog), "Pair a phone…" -> `aristotle-desktop --pair` (Pair view, code issued at once, D19-6), "Open" / left click -> `aristotle-desktop`. "Start server" runs `aristotle-server start --json` directly (no confirm, D15). A running window app receives the argument through the single-instance hand-off. | One dialog implementation (the window's), no second UI toolkit in the tray. |
| P15 | "Stopped unexpectedly" (D5): before the window app runs `stop`/`restart` it writes `$XDG_RUNTIME_DIR/aristotle-desktop/expected-stop` (unix time); the tray treats a stop within 60 s of that stamp as expected (no notification) and deletes the stamp. A stop from the CLI or a crash notifies. | The tray and window are separate processes (D18), so "app-issued" needs a shared, short-lived marker; runtime dir = per user, cleared at logout. |
| P16 | Rust workspace `desktop/` with crates `core` (lib: CLI runner, §22 types, poller, tray state, notification diff, strings), `tray` (bin `aristotle-tray`, depends on core + ksni + notify-rust, never on tauri) and later `app` (bin `aristotle-desktop`, Tauri). Versions pinned as in `docs/spikes/desktop-tray.md` (ksni 0.3.7, notify-rust 4.19.0; tauri 2.12.6 for `app`). | `ldd` of the tray stays libc-only (~7 MB RSS, DS2b). |
| P17 | Tray-state edge rules (DS3 vs DSDc alignment, 2026-11-18): an absent `hermes` field (older server) = **Amber** ("Hermes state unknown"), not Green; `server:"no-answer"` = Red; server running but `service` not `active` (`unknown`, or a server started by hand) = Red with its own reason ("running outside the service"), not "server stopped". Tray menu label "Stop server…" (with the ellipsis: it opens a dialog, P14). P5 compares the build version with BOTH the CLI's `version --json` and the running server's `status.result.version`. An expected-stop stamp counts only while `now - 60 s <= stamp <= now + 60 s`; outside that window (old, or far in the future after a clock step) it is ignored and deleted. | Unknown is not healthy; a hand-started server is not the service; an installed-but-not-restarted binary must also flag. design.md follows these rows. |
| P18 | On a version mismatch the window's status footer is red with the mismatch word, matching the tray (design.md:249 had a green footer + banner). The banner stays. | One state, one colour on every surface; the tray is already red (P17). Supersedes that design.md line. |
| P19 | The QR is encoded by the server, not the frontend: `pair --json` adds `qr_modules` (array of equal-length strings of `0`/`1`, one per row, quiet zone excluded) built by the server's existing QR encoder (`server/internal/termqr`, rsc.io/qr) from the same `qr_payload` with all addresses up to `pairing.MaxPayloadAddresses`; absent when `qr_payload` is empty. The window draws it as SVG rects. Amends P11 ("frontend renders it with a QR library"). | No new npm dependency; one QR encoder for terminal and window; the payload rules stay in Go. |
| P20 | The D7 notification is sent by the tray, not the window: the core snapshot carries `terminal_granted` (ids); the tray diff notifies `TermOn*` on `terminal_enabled` false→true and on each new granted id (first snapshot = baseline, no notification). Amends design.md 4.8 and the DS6 brief. | One notifier process; also catches changes made from the CLI (full access widened by anyone is worth a notice); the granted list also replaces three separate `terminal status` readers in the UI. |
| P21 | One "can Start" rule everywhere (tray menu, Status, Service, needs-server): Start only when installed, no mismatch, not busy, and `service` is `inactive` or `failed`; other systemctl words show `dis_other`. | The same state must never offer Start on one screen and hide it on another; starting during `activating`/`deactivating` races systemd. |
| P22 | Q-D7b = A (user 2026-11-18): a newly granted id gets its own notice `n_grant_t`/`n_grant_b` ("Terminal access granted" / "{name} can open a shell with full access to your account while the remote terminal is on."), whether the switch is on or off; the switch going off→on keeps `n_d7_*`. Both in one tick = two notices. `{name}` = device name, `ADevice` fallback (P11: names only). Amends P20. | Every widening of access is announced truthfully; a grant made while off is the access that goes live at the next switch-on. |

## Design (confirmed 2026-11-15)
```
 Aristotle Server (Tauri, one process, XDG autostart hidden)
   Tray: green/amber/red · menu Open · Pair a phone… · Start/Stop (confirm on Stop) · Quit (UI only)
   Window (React+TS, created on click, destroyed on close):
     Status | Pair (QR + countdown) | Devices (revoke, confirm) | Terminal (on/off, grants, open; D7 confirm)
     | Service (start/stop/restart, confirm stop/restart) | Logs (live)
   Rust core: poll every 5 s -> pure diff -> notifications (paired, terminal opened, Hermes down, server stopped)
        | runs by absolute path, fixed args, no shell
        v
   aristotle-server <cmd> --json  (NEW: Go owns the shapes)      aristotle-server start|stop|restart|logs -f
        | control.sock (exists, same uid)                              | systemctl --user / journalctl
        v                                                              v
   aristotle-server.service (unchanged, headless, starts at boot) -> Hermes, phone
```
Notification sources (VERIFIED fields): new phone = new `id` in `devices list` (`server/internal/devices/devices.go:46-51`);
terminal opened = new `id` in `terminal status` `open[]`, which carries `device_id` (`server/internal/terminal/hub.go:284-290`);
Hermes down = `status` `hermes.state` leaves `reachable` (`server/internal/app/controlcmds.go:142-149`); server stopped =
not active without an app-issued stop.

## Risks and open checks
- **Tray on KDE through Tauri** (libappindicator3 present, ayatana absent): ASSUMED to work; DS2 proves it before any UI work.
- **Rust toolchain** broken until the user's `pacman -Syu` (D11). DS1 (Go only) does not wait.
- **Second PC** (`cachyos-asus-1`): desktop environment and webkit2gtk-4.1 presence NOT checked; DS7 checks over ssh.
- **Polling cost**: `status` probes Hermes on every call (`server/internal/app/controlcmds.go:183`); ASSUMED cheap (local
  socket, short timeout). DS3 measures one call and records it; if heavy, poll Hermes less often.
- **Gate time**: a Tauri release build adds minutes to the gate; DS2 records it.

## DS2 spike findings (2026-11-18, `docs/spikes/desktop-tray.md`) — open questions D17-D18
- Tauri's Linux tray (tray-icon 0.25.1 over libappindicator) exposes no SNI `Activate`: a left click cannot reach the app;
  only the menu works (reported by DS2 via `busctl`; real mouse click not tested). Tray icon visibility in the panel NOT
  confirmed (maybe in the overflow); user check pending.
- Destroying the window leaks a WebKit WebProcess (~245 MB each) unless `terminate_web_process()` is called; even with the fix the
  process stays at ~290 MB after the first window (69 MB tray-only, ~520 MB window open). D9's "memory freed" does not hold.
- `ksni` (pure-Rust StatusNotifierItem) has an `activate` callback for left click (VERIFIED: docs.rs `ksni::Tray::activate`);
  that Plasma calls it on left click is ASSUMED until a spike.
| Id | Open question | Options (recommended first) |
|---|---|---|
| D17 | How the tray opens the UI | A: own SNI tray via `ksni` (left click opens the UI); B: accept Tauri's tray, menu only ("Open" first item) |
| D18 | Process model (revisits D9) | A: two processes: small resident tray (ksni + notifications + core poller, no webview) launches the Tauri window app on click, which exits on close; B: one Tauri process as decided, ~290 MB after first open |

Decided 2026-11-18: **D17 = A** (user). **D18 = A** (user wrote "D19. A"; no D19 exists, PM interpretation = D18, flagged in
chat). Rows added to the Decisions table; D9 superseded.
