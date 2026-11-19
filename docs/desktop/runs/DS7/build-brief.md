# DS7 brief — release bundle, installer, uninstall, licences for the desktop app (P24)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Spec `docs/desktop/spec.md` **P24** (binding), D12, D14,
D18, P8, P5, P11; design `docs/desktop/design.md` §6, §8 (fonts). Existing code to extend (read fully first):
`scripts/release.sh`, `scripts/install.sh` (+ its tests under `scripts/test/`), `scripts/licences.sh`, `scripts/build-server.sh`,
`server/internal/app/servicecmds.go` (`runUninstall`) + `server/internal/service/`, `desktop/packaging/*.desktop`,
`docs/desktop/gates.sh`, `desktop/app/` (main.rs args), `CHANGELOG.md`, the release docs under `docs/release/`.

## Build
1. `scripts/build-desktop.sh [--version X.Y.Z] <outdir>`: builds the UI (`npm ci` + build) and both binaries in release mode with
   `ARISTOTLE_VERSION` set (no Tauri bundler: plain binaries; check how DS3w's gate builds the app and reuse it), copies
   `aristotle-tray`, `aristotle-desktop`, the two `.desktop` templates and the app icon PNG (256 px; from `docs/design/icon/` or the
   Android icon, D14; generate reproducibly if needed) into `<outdir>`.
2. `aristotle-desktop --version` prints `aristotle-desktop <v>` and exits before any GTK/Tauri init (`aristotle-tray --version` is
   being added by DF11 in parallel: do not edit `desktop/tray/**`).
3. `release.sh`: runs the desktop gate after the server/android gate (logs under `dist/gate-<v>/desktop/`), calls
   build-desktop.sh into the stage, verifies both `--version` lines equal `<v>`, adds `DESKTOP_THIRD_PARTY_NOTICES.txt`, includes
   every new file in `SHA256SUMS`, sets modes (755 executables, 644 others). The usage comment lists the new bundle files.
4. `scripts/licences.sh`: also writes/checks `desktop/THIRD_PARTY_NOTICES.txt` from `cargo metadata` (runtime deps of the two
   binaries: name, version, licence expression, the crate's licence file text when present in the registry source) + npm runtime
   deps of `desktop/ui` (`dependencies` only, with their LICENSE text) + the fonts' OFL files from `desktop/ui/src/assets/fonts/`.
   Unknown/missing licence: list it in the report, do not guess. `--check` covers it.

## Install (`scripts/install.sh`)
5. Desktop step per P24 (2), after `open_firewall`, before the final messages: webkit check (`ldconfig -p` for
   `libwebkit2gtk-4.1.so.0`; overridable for tests like the other `ARISTOTLE_*` hooks), atomic binary install with `.prev`, icon,
   menu entry, autostart entry (`@BIN_DIR@` -> absolute dir, `Icon=aristotle-server`), replace a running tray (stop the old one by
   exact name and wait for it to exit), start the tray detached (`setsid`, output to `$XDG_STATE_HOME/aristotle/tray.log` or
   similar) when `WAYLAND_DISPLAY` or `DISPLAY` is set. A bundle without desktop files (older bundle) = skip silently. Any failure in
   this step warns and continues; never touches the server step. Verify the desktop files against `SHA256SUMS` in `verify_bundle`.
   Update the header comment's numbered steps.
6. Tests in `scripts/test/` (same harness as the existing install tests, all paths under a temp HOME, fake binaries; never the real
   `~/.local/bin`, never real systemctl): webkit absent -> skip line; present -> files and entries written with the right Exec/Icon;
   update replaces a running (fake) tray; no display -> not started; checksum mismatch on a desktop file -> refuse before changes.

## Uninstall (Go)
7. `aristotle-server uninstall` (P8): also removes `~/.local/share/applications/aristotle-desktop.desktop`,
   `~/.config/autostart/aristotle-tray.desktop`, the icon; stops a running `aristotle-tray` (exact name) and `aristotle-desktop`.
   Binaries: do what it already does for `aristotle-server` (read it; if it keeps the server binary, keep the desktop ones and say
   so in the output). Paths via XDG env with the usual fallbacks; tests with a temp HOME. `docs/protocol.md`/README text if they list
   what uninstall removes.

## Docs
8. `CHANGELOG.md` `## [Unreleased]`: user-facing lines for the desktop app (tray + window, what it can do), the server's new
   `--json`, `start`/`stop`/`restart`, `pair --json` QR grid, and the installer's desktop step. The PM turns it into `## [2.3.0]`.
   Install docs (`docs/release/` and wherever install is described): the desktop step, webkit2gtk-4.1 requirement, how to remove.

## Verify
- `GATE_ONLY=server timeout 1800 bash docs/v1-tickets/gates.sh docs/desktop/runs/DS7/self-gate-server` (+ the scripts gate if
  `gates.sh` has one: run it) and `timeout 1800 bash docs/desktop/gates.sh docs/desktop/runs/DS7/self-gate-desktop`.
- `scripts/build-desktop.sh --version 9.9.9 $TMPDIR/ds7-out` and both `--version` lines.
- A dry release is NOT run (needs a clean tree and the signing key; the PM runs it).

## Hard rules
Pitfall (seen by the PM): process names are truncated to 15 chars in `comm`, so `pkill -x aristotle-desktop` matches nothing
(`aristotle-tray` fits). Match on the executable path (`/proc/<pid>/exe`) or `pgrep -f` with an anchored full path, and test it.
Never git write commands. No installs/new deps (npm/cargo deps already locked are fine). Never run `install.sh` or
`aristotle-server install/uninstall/start/stop` for real, never touch `~/.local/bin`, `~/.config/autostart`,
`~/.local/share/applications`, the running service or tray. No skill/memory edits. Kill every process you start.
Files: `scripts/**`, `server/internal/app/**`, `server/internal/service/**`, `desktop/app/**`, `desktop/packaging/**`,
`docs/desktop/gates.sh`, `desktop/THIRD_PARTY_NOTICES.txt`, `CHANGELOG.md`, `docs/release/**`, `docs/protocol.md`. Not
`desktop/tray/**`, `desktop/core/src/strings.rs` (DF11).

## Report
`docs/desktop/runs/DS7/build-report.md`: per item: change (file:line), tests, RED evidence, gate summaries, licence gaps, anything
the PM must decide.
