# DS7 build report — release bundle, installer desktop step, uninstall, licences (P24)

All gates GREEN (below). No git write command, no real `install.sh` / `aristotle-server install|uninstall|start|stop`, nothing under
`~/.local/bin`, `~/.config/autostart`, `~/.local/share/applications`; the user's preview tray (pid from `scratch/preview/`) was never
touched (my tests match the installed path only, and Go tests use a fake `/proc`). Every process I started has exited
(`pgrep` shows only the preview tray).

## Items

| # | Change | Where |
|---|---|---|
| 1 | `scripts/build-desktop.sh [--version X] <outdir>`: `npm ci` + `npm run build`, `cargo build --release -p aristotle-tray`, `tauri build --no-bundle` (the same commands as the DS3w gate; `CARGO_TARGET_DIR` defaults to `desktop/target`, so the gate's cache is reused), `ARISTOTLE_VERSION` exported. Copies `aristotle-tray`, `aristotle-desktop`, the two `.desktop` templates, `aristotle-server.png`. Icon = `desktop/app/icons/icon.png` (already 256x256 RGBA, the committed D14 icon), no generation needed. | `scripts/build-desktop.sh` (new) |
| 2 | `aristotle-desktop --version` prints `aristotle-desktop <v>` (`0.0.0-dev` on an unstamped build) and returns before Tauri/GTK init. Pure fns `version_requested` / `version_line`. | `desktop/app/src/args.rs:57-67` (+2 tests), `desktop/app/src/main.rs:13-19` |
| 3 | `release.sh`: desktop gate after the full gate (`dist/gate-<v>/desktop/`), `build_desktop`, `verify_desktop_versions` (both lines must equal `<name> <v>`), `DESKTOP_THIRD_PARTY_NOTICES.txt`, `BUNDLE_FILES` list feeds `SHA256SUMS` (11 lines), modes 755/644, usage comment lists all 12 bundle files. | `scripts/release.sh:5-11,73-77,88-90,117-131,147-148,~160-175` |
| 4 | `licences.sh` also writes/checks `desktop/THIRD_PARTY_NOTICES.txt` via `scripts/licences_desktop.py`: 319 Rust crates linked into the two binaries (`cargo metadata --locked --offline --filter-platform x86_64-unknown-linux-gnu`, normal deps only, workspace crates excluded; name, version, licence expression, every LICENSE/LICENCE/COPYING/UNLICENSE file verbatim), 4 npm runtime packages (lock entries not dev-only: `@tauri-apps/api`, `react`, `react-dom`, `scheduler`; LICENSE from `node_modules`), the fonts' OFL texts (`OFL.txt`, `OFL-Literata.txt`). `--check` compares it (fails when stale). File is 2.6 MB. | `scripts/licences.sh`, `scripts/licences_desktop.py` (new), `desktop/THIRD_PARTY_NOTICES.txt` (new, generated) |
| 5 | `install.sh` desktop step after `open_firewall`, before the final messages: `has_webkit` (`ldconfig -p`, searched in PATH + `ARISTOTLE_SBIN_DIRS`, stubbable by PATH), `install_desktop_binary` (temp + `mv`, `.prev` when different), icon, menu entry, autostart entry (`@BIN_DIR@` -> absolute dir; templates now say `Icon=aristotle-server`), `replace_tray` (stop, wait up to 5 s, then SIGKILL, wait; start detached with `setsid` when `WAYLAND_DISPLAY`/`DISPLAY` is set, log `$XDG_STATE_HOME/aristotle/tray.log`), bundle without desktop files = silent skip, any failure = `warning:` and exit 0. `verify_desktop_listed` refuses before changes when a present desktop file has no `SHA256SUMS` line; the generic `sha256sum --check` covers mismatches. Header steps renumbered (8 = desktop, 9 = final). Tray matched by `/proc/<pid>/exe` == `$BIN_DIR/aristotle-tray` (also `(deleted)`), never by `comm`. | `scripts/install.sh:2-24,89-115,~505-650,656` |
| 6 | Tests: 9 new cases in `install_test.sh` (+ `ldconfig` and `aristotle-tray` stubs; `run_install` now unsets `XDG_STATE_HOME`, `DISPLAY`, `WAYLAND_DISPLAY`): webkit absent -> skip line; present -> files, Exec/Icon, tray started; update replaces a running fake tray (a `sleep` copy named `aristotle-tray`) and leaves another tray at a different path alive; no display -> not started; tampered desktop file -> exit 1, nothing created, nothing run; unlisted desktop file -> refused; older bundle -> silent; autostart dir unwritable -> warning, exit 0, server installed. `release_test.sh`: stub desktop gate and stub `build-desktop.sh`; new cases desktop gate fails, wrong desktop `--version`, twelve files / eleven sums / modes / `--version` lines / notices copy. | `scripts/test/install_test.sh:~575-700`, `scripts/test/release_test.sh`, `scripts/test/stubs/` |
| 7 | `uninstall` also removes the menu entry, autostart entry and icon (XDG with fallbacks, relative XDG refused), SIGTERMs processes whose `/proc/<pid>/exe` basename is exactly `aristotle-tray` or `aristotle-desktop` (also `(deleted)`), and, because the server binary is kept, keeps the desktop programs and says so (`Kept the programs in <dir> (aristotle-tray, aristotle-desktop), like aristotle-server; ...`). Seams: `desktop` var (`/proc` root + signal func); `useFakes` now points it at an empty temp dir and sets `XDG_DATA_HOME`, so no test can touch real processes. Usage text updated. | `server/internal/service/desktop.go` (new), `server/internal/app/servicecmds.go:35-37,122,138-153`, `server/internal/app/app.go` (usage), tests `server/internal/service/desktop_test.go` (new), `servicecmds_test.go` |
| 8 | `CHANGELOG.md` `[Unreleased]` / Added: 6 `Server:` lines (desktop app, installer step, uninstall, start/stop/restart, `--json` incl. `pair --json` QR grid, desktop licence file). `docs/release/README.md`: bundle contents, desktop gate, webkit2gtk-4.1 requirement, the installer's desktop step, how to remove. `docs/protocol.md` and the top-level docs do not list what uninstall removes: unchanged. | `CHANGELOG.md`, `docs/release/README.md` |

## RED evidence (seen failing before the code)
- `--version` (Rust): `cargo test -p aristotle-desktop --lib args` -> `error[E0425]: cannot find function version_line`, exit 101; after: 10 passed.
- Go service: `go vet ./internal/service/` -> `undefined: DesktopPaths` (tests written first).
- Go wiring: with `removeDesktop` call disabled, `TestUninstallRemovesTheDesktopApp` FAILED (three files remain, `stopped [], want [11 12]`, no "Kept" line), exit 1; restored -> passes.
- Installer: new cases against an `install.sh` with the desktop step stripped (`INSTALL_SH=` hook): 6 FAIL lines (skip line missing, binaries/icon not installed, modes empty) then the script aborted at the missing entry file, exit 1. After: `install_test: all passed` (302 checks).
- Release: with the `verify_desktop_versions` call deleted, `release_test.sh` -> `FAIL: wrong desktop version line: exit 0, want 1` (+2 follow-ups), exit 1; restored (`cmp` identical) -> green.

## Gates (real exit codes)
- `GATE_ONLY=server gate.sh docs/desktop/runs/DS7/self-gate-server`: exit 0; gofmt, vet, test PASS, `go-tests passed=899 failed=0`.
- `bash docs/desktop/gates.sh docs/desktop/runs/DS7/self-gate-desktop`: exit 0; all 17 steps PASS, `ui-tests passed=301`, `rust-tests passed=196 failed=0` (the count includes DF11's concurrent tray tests; floors in `gates.sh` untouched: DF11 owns them, 196 >= 187).
- `bash scripts/test/run.sh` (the gate's `scripts` step, which `GATE_ONLY` skips): exit 0; `install_test`, `release_test`, `root_install_test` PASS (436 `ok` lines in total).
- `scripts/licences.sh --check`: exit 0 (server, desktop, Android up to date); with one byte appended to the desktop file it exits 1 (`is stale`); file restored.
- `scripts/build-desktop.sh --version 9.9.9 $TMPDIR/ds7-out`: exit 0 in 4m39s; files `aristotle-tray` (9.2 MB), `aristotle-desktop` (17 MB), two `.desktop`, `aristotle-server.png` (256 px). `aristotle-desktop --version` -> `aristotle-desktop 9.9.9`, `aristotle-tray --version` -> `aristotle-tray 9.9.9`, both exit 0, also with `DISPLAY`, `WAYLAND_DISPLAY` and the D-Bus address unset.
- No dry release (needs the clean tree and signing key; the PM runs it).

## Licence gaps (no licence file in the package source; text not invented, listed under "Gaps" at the end of the notices file and on stderr)
| Crate | Declared in Cargo.toml |
|---|---|
| `alloc-stdlib 0.3.0` | BSD-3-Clause |
| `dlopen2 0.8.2` | MIT |
| `dlopen2_derive 0.4.3` | MIT |
| `libappindicator-sys 0.9.0` | Apache-2.0 OR MIT |
| `selectors 0.38.0` | MPL-2.0 |

All other 314 crates and 4 npm packages carry a licence expression and a licence file. MPL-2.0 (`selectors`) has file-level terms and asks that the source be available; the PM may want the licence text added from upstream by hand.

## For the PM to decide / know
1. The gaps above: ship as is, or add the upstream texts (needs a download I did not do).
2. Uninstall matches processes by executable basename anywhere (not only `~/.local/bin`), so it would also stop a tray started from another directory (e.g. the preview). The installer is stricter on purpose: it replaces only the tray at the installed path. Say if uninstall should be restricted the same way.
3. A dev (unstamped) `aristotle-desktop --version` says `0.0.0-dev`; release builds are checked to equal `<v>`.
4. A `$HOME` containing a space (or `$`) is not quoted in the generated `Exec=` lines; not handled, not tested.
5. `tray.log` grows without rotation (installer appends); it lives in the state dir, which `uninstall --purge` removes.
6. `release_test.sh` stubs the desktop gate and `build-desktop.sh` (the real build is ~5 min), so the real script is exercised only by my manual run above, and by the PM's dry release.
7. `docs/desktop/gates.sh` had uncommitted DF11 edits when I started; I did not touch it.

## Files
Modified: `CHANGELOG.md`, `desktop/app/src/args.rs`, `desktop/app/src/main.rs`, `desktop/packaging/*.desktop` (Icon line), `docs/release/README.md`, `scripts/install.sh`, `scripts/licences.sh`, `scripts/release.sh`, `scripts/test/install_test.sh`, `scripts/test/release_test.sh`, `server/internal/app/app.go`, `server/internal/app/servicecmds.go`, `server/internal/app/servicecmds_test.go`.
New: `scripts/build-desktop.sh`, `scripts/licences_desktop.py`, `scripts/test/stubs/{ldconfig,aristotle-tray,build-desktop.sh}`, `server/internal/service/desktop.go`, `server/internal/service/desktop_test.go`, `desktop/THIRD_PARTY_NOTICES.txt`, `docs/desktop/runs/DS7/self-gate-{server,desktop}/`.
