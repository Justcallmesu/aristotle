# Releasing and installing

## Releasing (on the dev PC)
1. Set `aristotle.version=<x.y.z>` in `android/gradle.properties` and move the `[Unreleased]` entries in `CHANGELOG.md` under
   `## [<x.y.z>] - <YYYY-MM-DD>`. Commit; the tree must be clean.
2. `scripts/release.sh <x.y.z>` refuses unless the version is `x.y.z` (minor and patch at most 99), the tree is clean, the two
   versions match, the CHANGELOG heading exists and tag `v<x.y.z>` does not. It runs the full gate (`docs/v1-tickets/gates.sh`,
   logs in `dist/gate-<x.y.z>/`) and the desktop gate (`docs/desktop/gates.sh`, logs in `dist/gate-<x.y.z>/desktop/`), then builds
   `dist/aristotle-<x.y.z>/`: `aristotle-<x.y.z>.apk` (assembleRelease), `aristotle-server` (linux-amd64, version and commit
   embedded), the desktop app built by `scripts/build-desktop.sh` (`aristotle-tray`, `aristotle-desktop`, their two `.desktop`
   templates, `aristotle-server.png`, the app icon), `install.sh`, `THIRD_PARTY_NOTICES.txt` (server),
   `DESKTOP_THIRD_PARTY_NOTICES.txt` (Rust crates, npm runtime packages, the bundled fonts' OFL), `RELEASE-NOTES.md` (that
   version's CHANGELOG section) and `SHA256SUMS` (every other file). `scripts/licences.sh` writes and checks both notices files.
3. It refuses (and leaves no bundle) when the APK signer is not the pinned release certificate (`docs/release/spec.md`,
   `5af32f63…70bb`), the server's `version` line is not `aristotle-server <x.y.z> (<commit>) …`, or `aristotle-tray --version` /
   `aristotle-desktop --version` is not `<name> <x.y.z>`.
4. `scripts/release.sh --tag <x.y.z>` also creates the local annotated tag `v<x.y.z>` after everything passed. It never pushes,
   publishes or installs; push the tag yourself.

Signing: Gradle reads `~/.config/aristotle/signing.properties` (or the file named by `ARISTOTLE_SIGNING`). `dist/` is git-ignored.
Tests: `bash scripts/test/run.sh` (also a line of the gate).

## Installing on a PC (Linux, systemd user session)
Needs: Hermes Agent installed for your user (the installer starts its gateway; `--no-gateway` skips that), a terminal on the PC.
The desktop app (tray icon and window) also needs `libwebkit2gtk-4.1` (Arch: `webkit2gtk-4.1`, Debian/Ubuntu: `libwebkit2gtk-4.1-0`) and
a graphical session with a system tray; without the library the installer prints `desktop app skipped: webkit2gtk-4.1 not installed`
and installs the server only.

**From a checkout of this repo:** `./install.sh` runs the newest `dist/aristotle-<x.y.z>/` (version order). `dist/` is not in git
and only the dev PC can build it (`scripts/release.sh` needs the signing key), so on any other PC copy the release folder there and
run `./install.sh --bundle <dir>` (or run `<dir>/install.sh` directly; same result). Every other option goes to the bundle installer.
If the newest folder is incomplete (its `install.sh` missing or not executable) it stops; it never installs an older one instead.

**The bundle installer** (`aristotle-<x.y.z>/install.sh [--pair | --no-pair] [--no-gateway] [--no-firewall]`):
- refuses inside the phone's remote terminal, and (unless `--no-gateway`) when the `hermes` CLI is missing, before touching anything;
- checks every file against `SHA256SUMS` and installs nothing if one does not match;
- copies the binary to `~/.local/bin/aristotle-server` (temp file + rename) and keeps the one it replaces as
  `aristotle-server.prev`;
- copies the APK and a generated `aristotle.json` (`version`, `version_code`, `sha256`, `size`) to
  `~/.local/share/aristotle/app/` (`$XDG_DATA_HOME` honoured; temp file + rename, json last), so the server can hand them to the
  phone (`docs/protocol.md` §21);
- runs `aristotle-server install --force --start`: writes or updates the systemd user unit, restarts a running service or
  starts a stopped one;
- waits up to 30 s (wall time) for `aristotle-server status` to exit 0; if it does not, or if `install --force --start`
  itself fails, it prints the last lines of `journalctl --user -u aristotle-server` and the rollback command, and exits 1;
- checks that the server can reach Hermes (`aristotle-server status` shows `Hermes:`). If not, it runs
  `hermes -p default gateway install` (the gateway of `~/.hermes`, the home the server uses, even when you switched profiles
  with `hermes profile use`), then waits up to 120 s (`ARISTOTLE_GATEWAY_TIMEOUT`) for Hermes to report ready. Still not
  reachable: it prints the reason and the gateway log, says what was and was not done, and exits 1 (fix Hermes, run it again);
- turns on systemd linger when it is off, so both services keep running after you log out;
- opens the firewall for the LAN subnet(s) the server serves, and only those: `<port>/tcp` and `5353/udp` (discovery) from
  e.g. `192.168.1.0/24`, with ufw or firewalld; sudo asks for your password. Tailscale needs no rule. When sudo cannot ask, the
  firewall is something else, or `--no-firewall` is given, it prints the exact commands instead. A firewall problem never fails
  the install. **On another network, run the installer again** (rules for old networks stay in place);
- **desktop app** (only when the bundle has it and `libwebkit2gtk-4.1` is present, found with `ldconfig -p`; a bundle from before
  2.3.0 skips this silently): copies `aristotle-tray` and `aristotle-desktop` to `~/.local/bin` (temp file + rename, each replaced
  one kept as `<name>.prev`), the icon to `~/.local/share/icons/hicolor/256x256/apps/aristotle-server.png`, the app-menu entry to
  `~/.local/share/applications/aristotle-desktop.desktop` and the autostart entry to `~/.config/autostart/aristotle-tray.desktop`
  (`$XDG_DATA_HOME` and `$XDG_CONFIG_HOME` honoured when absolute, like the server does; `Exec=` gets the absolute path of the
  program, quoted as the Desktop Entry spec says, so a home with a space works). An open `aristotle-desktop` window and a running
  tray, found by their executable path in `~/.local/bin` (symlinks resolved; a copy started from anywhere else is left alone), are
  stopped (SIGTERM, SIGKILL after about 5 s), and the new tray is started detached (working directory `/`) when `WAYLAND_DISPLAY` or
  `DISPLAY` is set (its output goes to `$XDG_STATE_HOME/aristotle/tray.log`, default `~/.local/state/aristotle/tray.log`); it says "Started the tray" only when that process is still running after about 1 s, else it names the other tray that holds the lock (pid and path); without a graphical
  session the tray starts at the next login. A tray whose panel (StatusNotifierWatcher) stays gone for 30 s exits, so it never holds the lock for a dead session. Every file is checked against `SHA256SUMS` first like the rest. A problem in this
  step prints a warning and the install carries on: it never fails the server install;
- prints the running version, the rollback command and where the APK is.

Run it in a terminal on the PC, never from the phone's remote terminal: the script refuses there, because an update restarts
the service and closes every open phone terminal.

Removing it: `aristotle-server uninstall` removes the systemd unit, the app-menu and autostart entries and the icon, and stops a
running `aristotle-tray` and `aristotle-desktop` from `~/.local/bin` (the entries first, then the tray, then the service). The programs in `~/.local/bin` (`aristotle-server`, `aristotle-tray`,
`aristotle-desktop`, the `.prev` copies) and the paired-device state stay (`--purge` deletes the state); delete the programs by hand.

Rollback: `mv -f ~/.local/bin/aristotle-server.prev ~/.local/bin/aristotle-server && ~/.local/bin/aristotle-server install --force --start`.
The desktop programs have their own `.prev` copies: close the window, then
`pkill -xf "$HOME/.local/bin/aristotle-tray"; mv -f ~/.local/bin/aristotle-tray.prev ~/.local/bin/aristotle-tray; mv -f ~/.local/bin/aristotle-desktop.prev ~/.local/bin/aristotle-desktop`
and start `~/.local/bin/aristotle-tray` again (the tray is single-instance: a second one exits at once while one runs).

Phones update themselves from 2.1.0 on: after you install a release on the PC, the paired phone offers the new version
(`GET /v1/app/update`, `docs/protocol.md` §21). A phone older than 2.1.0 has no updater, so it needs the APK once by hand.

The phone: copy `aristotle-<x.y.z>.apk` to it and open it. The first time you switch from the debug build, uninstall the debug
app first (another signing key, so Android refuses the update), then pair again (`aristotle-server pair`).
