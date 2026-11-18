# DS3 build brief — desktop core + tray (Rust workspace, no window yet)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Read in full: `docs/desktop/spec.md` (D4, D5, D6, D8,
D15-D19, P1-P16), `docs/protocol.md` §22 (the CLI JSON contract you parse), `docs/spikes/desktop-tray.md` (both spikes; the
"ksni tray (DS2b)" section is your starting point), `docs/desktop/runs/DS2b/build-report.md` (DS3 recommendation), and the spike
code `spikes/ksni-tray/` (copy patterns, not the throwaway code shape). Load the skills `antislop-code`.

## Goal
`desktop/` Cargo workspace (P16) with two crates now (`app` comes in DS3w; leave room in the workspace):

### `core` (lib, no GUI deps)
1. **Types** for every §22 shape (`status`, `version`, `devices list`, `terminal status`, `pair`, action results, `{"error"}`) with
   serde; absent-able fields are `Option` / `#[serde(default)]` exactly as §22 marks them; unknown fields ignored (forward compat).
   Tests decode EVERY fixture in `server/internal/app/testdata/json/` (read by path relative to the crate; fail if a fixture is not
   covered, so a new fixture forces a type update).
2. **CLI runner** (`trait ServerCli` seam + real impl): runs `$HOME/.local/bin/aristotle-server <args> --json` by absolute path
   (env override `ARISTOTLE_SERVER_BIN` for development only), fixed argv, never a shell, 10 s timeout (kill on timeout), parses
   stdout per §22 (success object vs `{"error"}`, exit code kept). Missing binary -> a typed `NotInstalled`-like error.
   Never logs stdout/stderr content (P11).
3. **Snapshot + tray state**: one poll = `status` (+ `devices list` and `terminal status` only when the server answers). Pure
   `tray_state(&Snapshot) -> Green | Amber | Red`: Green = installed && service active && server running && hermes reachable;
   Amber = server running && hermes not reachable (or hermes absent); Red = everything else (not installed P13, stopped, no
   answer, CLI missing, version mismatch). Tooltip text per state (P10 strings).
4. **Version check (P5)**: `version --json` once per poller start and after the server (re)starts; `protocol` must equal the
   constant the core is built for (read the server's current protocol number from `server/internal/version/version.go`); mismatch
   -> Red with a distinct reason.
5. **Notification diff** (pure, D5): `diff(prev: Option<&Snapshot>, next: &Snapshot, expected_stop: Option<SystemTime>, now) ->
   Vec<Notice>`: new device id paired; new open terminal id (name the device from the devices list, fall back to "a device");
   Hermes reachable -> not reachable (once per transition, not every poll); server running -> not running unless an expected-stop
   stamp is <= 60 s old (P15). First poll (`prev == None`) never notifies. A failed poll (CLI timeout) is not a transition.
6. **Expected-stop stamp (P15)**: read/delete helpers for `$XDG_RUNTIME_DIR/aristotle-desktop/expected-stop` (unix seconds);
   the writer lives here too (DS3w calls it). Missing `XDG_RUNTIME_DIR` -> no stamp (stops then notify).
7. **Strings (P10)**: `en` + `id` tables for tray menu, tooltips, notification titles/bodies; locale = `LC_ALL`, then
   `LC_MESSAGES`, then `LANG`, starting with `id` -> Indonesian, else English. Use the exact texts from
   `docs/design/desktop/mockups.html` / `design-notes.md` (notifications and tray menu, EN + ID). Every key in both tables (test).
   No string contains a pairing code (there is none in polled data; assert notices only take device names/ids).

### `tray` (bin `aristotle-tray`; depends on core, ksni 0.3.7, notify-rust 4.19.0; NEVER tauri/gtk/webkit)
- ksni tray, `assume_sni_available(true)`, `watcher_offline` returns true, icon updated only when the state changes. Placeholder
  icons: generated pixmaps in the three state colours from `design-notes.md` tokens (the real set is ticket DSI).
- Poller thread every 5 s (P1) with the real runner; notices sent via notify-rust (app name "Aristotle Server"); a notice's
  default action opens the window app.
- Left click (`activate`) and menu "Open" -> launch the window app; menu per P14: Open, Pair a phone… (`--pair`), Start server
  (runs `start --json` directly; shown when the server is stopped), Stop server… (`--confirm stop`; shown when running), Quit
  (tray only; never stops the server). Window app path: `current_exe()` parent + `aristotle-desktop` (env override
  `ARISTOTLE_DESKTOP_BIN`); if missing, a notification says so (the window app arrives in DS3w). Child PID held + reaper thread
  (single instance on the tray side, as in DS2b); pass `XDG_ACTIVATION_TOKEN` from `ksni::current_xdg_activation_token()`.
- `desktop/packaging/aristotle-tray.desktop` (XDG autostart: `X-GNOME-Autostart-enabled=true`, `Hidden=false`, Exec by absolute
  path placeholder the installer fills) and `desktop/packaging/aristotle-desktop.desktop` (app menu). Installed by DS7, not now.

### Gate
`docs/desktop/gates.sh <run-dir>` (pattern: `docs/v1-tickets/gates.sh`: summary file, PASS/FAIL lines, never edits sources):
`cargo fmt --check`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`, plus `ldd` of the release
`aristotle-tray` must not list gtk/webkit (fail if it does). Fixed `CARGO_TARGET_DIR=desktop/target`. Add `desktop/.gitignore`
(`target/`).

## Tests (all without a display or a real server)
Fixture decode coverage; runner with a fake binary script in a temp dir (success, `{"error"}` + exit 1, garbage stdout, timeout
kill, missing binary, an argument containing `;` is passed literally); `tray_state` table; `diff` cases: first poll silent, new
device, new terminal, Hermes flap = one notice per transition, failed poll not a transition, stop with fresh stamp silent, stale
stamp (61 s) notifies, no stamp notifies; locale selection; string table parity. See them RED first against a stub (keep a scratch
copy, never git).

## Real-server smoke (read-only, after the tests)
You may run the real `~/.local/bin/aristotle-server status --json`, `version --json`, `devices list --json`, `terminal status
--json` (read-only) and measure one `status --json` call's wall time 5 times (spec risk "polling cost"; record min/median/max).
Never run `pair`, `start`, `stop`, `restart`, `devices revoke|grant-terminal|ungrant-terminal`, `terminal enable|disable`. You may
launch the built tray on the user's desktop for at most a few minutes (GUI env: `WAYLAND_DISPLAY=wayland-1 DISPLAY=:1
XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus`), check its icon colour matches the real
state (panel crop only, never a full-screen shot), then quit it. Do not click its Start/Stop.

## Files you may create/edit
`desktop/**` (except `desktop/ui/**` and `desktop/app/**`, which are DS3w), `docs/desktop/gates.sh`. Another worker writes
`docs/desktop/design.md` now: not yours.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No system installs (no pacman/yay/sudo/cargo install/rustup); crate
fetching into the project is allowed. Never touch the server's service, `~/.local/state/aristotle`, `~/.config/autostart`,
Hermes. Kill every process you start. No skill/memory edits; tips go in the report.

## Stop and ask if
A §22 field the core needs is missing or ambiguous; the tray needs a GUI library; the real `status --json` takes > 1 s median.

## Report
`docs/desktop/runs/DS3/build-report.md`: crate layout, files, every test name, RED evidence, gate output (exit codes), smoke
results (timings, icon colour vs real state), deviations, open questions.
