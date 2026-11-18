# DS2 spike: Tauri v2 tray on KDE (throwaway)

Code: `spikes/tauri-tray/` (thrown away). Run 2026-11-18 on this PC. Raw evidence: `docs/spikes/desktop-tray/`.
Status legend: VERIFIED = observed with the evidence named; NOT VERIFIED = could not be observed.

## Environment (VERIFIED)
- rustc/cargo 1.100.0 (Arch 1:1.100.0-1; the rust/llvm breakage from the spec is fixed on this PC), node v26.13.1, npm 12.2.2.
- `pacman -Q`: webkit2gtk-4.1 2.54.2-1, gtk3 1:3.24.52-2, libappindicator 12.10.1-2, librsvg 2:2.63.4-1. libayatana-appindicator: **not installed**.
- Session: `XDG_SESSION_TYPE=wayland`, `XDG_CURRENT_DESKTOP=KDE` (read from plasmashell's environ; the worker shell has neither set,
  and a GUI start from it fails with "Failed to initialize GTK" until WAYLAND_DISPLAY=wayland-1, DISPLAY=:1, XDG_RUNTIME_DIR,
  DBUS_SESSION_BUS_ADDRESS are exported). `loginctl show-session` printed `Type=wayland`. plasmashell pid 2547 running.
- Missing tools: `strace`, `grim`. Used `LD_DEBUG=libs` and `spectacle` instead.

## 1. Versions (Cargo.lock / node_modules, VERIFIED)
| Item | Version |
|---|---|
| tauri | 2.12.6 (features `tray-icon`, `image-png`) |
| tauri-build | 2.7.5 |
| tauri-plugin-notification | 2.8.1 (pulls notify-rust 4.19.0, zbus 5.19.0 / 6.3.0) |
| tray-icon | 0.25.1 |
| muda | 0.20.0 |
| libappindicator / libappindicator-sys | 0.9.0 / 0.9.0 |
| tao / wry | 0.37.2 / 0.57.2 |
| webkit2gtk crate (transitive; pinned `=2.0.2` for the leak workaround) | 2.0.2 |
| @tauri-apps/cli | 2.12.5 |
| vite / react / typescript | 7.3.8 / 19.3.1 / 5.9.3 |
Cargo.toml asks `tauri = "2"`, `tauri-build = "2"`, plugin `"2"`; the lockfile resolved the above. `npm install` warned that esbuild's
postinstall was blocked (npm-12 allowScripts); `vite build` still worked.

## 2. Appindicator library at runtime (VERIFIED)
- `ldd` on the release binary lists **no** appindicator (144 lines, `docs/spikes/desktop-tray/ldd-release.txt`; webkit2gtk-4.1 and gtk3 are linked).
- The binary contains the strings `libayatana-appindicator3.so.1`, `libappindicator3.so.1` (+ unversioned): `libappindicator-sys 0.9.0` **dlopens at runtime**.
- `LD_DEBUG=libs` (`ld-debug-appindicator.txt`): first tries `libayatana-appindicator3.so.1` (all paths, not found), then
  `find library=libappindicator3.so.1` -> `/usr/lib/libappindicator3.so.1`, `calling init`. So the **fallback to libappindicator3 works with
  what is installed**; no system package needed. The build also needed nothing from either lib (no -dev/pkg-config).

## 3. Tray icon in Plasma (PARTLY VERIFIED, one part NOT)
- VERIFIED: the app registers with `org.kde.StatusNotifierWatcher` (`RegisteredStatusNotifierItems` lists
  `:1.118/org/ayatana/NotificationItem/tray_icon_tray_app_spike`), `Status=Active`, `IconName=/run/user/1000/tray-icon/tray-icon-spike-N.png`
  with N increasing on every swap (7 after ~16 s: colour cycling every 2 s is happening; Rust log `SPIKE icon 0/1/2` every 2 s).
- **NOT VERIFIED: the icon was NOT seen in the visible panel row.** Three spectacle crops over 2.4 s of the panel (`tray-panel-crops.png`, cropped to the
  tray; full-screen shots were deleted, they show private windows) show the usual icons (bell, display, Discover, clipboard, volume, brightness, mic,
  network, "^" overflow arrow) and no green/amber/red square. The only other SNI registered (`:1.72`, Discover notifier) is the one that is visible.
  Either the icon sits in the "^" overflow popup (I could not open it: Wayland, no input injection without moving the user's cursor) or Plasma does not render an
  absolute-path `IconName`. **The PM must check by eye** (look in the "^" popup while the spike runs: `bash` a run with the env vars above).
  Per the brief's stop condition this is the open question for the user.
- Colour cycling visible: NOT VERIFIED (same reason).

## 4. Click handling (VERIFIED by D-Bus; a real mouse click NOT performed)
- The libappindicator-based item exports only `SecondaryActivate`, `XAyatanaSecondaryActivate`, `Scroll` and a `Menu` object. There is
  **no `Activate` and no `ContextMenu` method**: `busctl call ... Activate ii 10 10` -> `Call failed: No such method "Activate"`.
  A left click in Plasma is a call to `Activate` (or showing the dbusmenu); so the app cannot receive it.
- Rust side: `on_tray_icon_event` never fired in any run. Calling `SecondaryActivate` (middle click) over D-Bus returned OK and logged no
  `SPIKE tray event`. This matches tray-icon's docs: tray *click events are not emitted on Linux*; only the menu works.
- The menu (dbusmenu `GetLayout`: ids 2 Open, 3 Notify, 4 Quit) works: `com.canonical.dbusmenu.Event(id, "clicked")` ran the handlers (`SPIKE menu: open|notify|quit`).
  **Consequence: on this stack left click can only show the menu; "Open" must be a menu item. D4/D9 "click opens the UI" is not deliverable via Tauri's tray events.**
  Whether Plasma shows the menu on left click (fallback after Activate fails) is the part I could not click-test.

## 5. Destroy on close and recreate (VERIFIED, with a leak found)
- `WebviewWindow::close()` and `destroy()` both leave `app.webview_windows().len() == 0` and the app alive (because `RunEvent::ExitRequested` with `code: None`
  gets `api.prevent_exit()`; without that the last-window-close exits the app). The next Open creates a new window ("created window #2").
  The window renders "spike" (`window-open.png`).
- **The WebKit processes are NOT freed.** `pgrep -fa 'WebKit(Web|Network|GPU)Process'`: no window 0 processes; open 2 (NetworkProcess + WebProcess); after close **still 2, unchanged
  after 100 s**; every re-open adds one more WebProcess (3 after cycle 2, 4 after cycle 3), also with `destroy()` and also with a separate `data_directory`
  (WebContext) per window (then +2 per cycle, a NetworkProcess too). D9's "memory freed on close" does not hold with plain close/destroy.
- **Workaround that works:** before closing call `window.with_webview(|wv| wv.inner().terminate_web_process())` (needs the `webkit2gtk` crate with
  feature `v2_40`, Linux only). Then the WebProcess dies on every close: 3 cycles gave 2 -> 1 -> 2 -> 1 -> 2 -> 1 processes, no growth.
  One WebKitNetworkProcess (~55 MB) stays for the life of the app.

## 6. Memory (VERIFIED, `ps -o rss`, KB; sum = app + WebKit children; shared pages are counted twice, so sums overstate)
| State | Plain close (leak) | With terminate_web_process |
|---|---|---|
| tray only, no window | 69,244 (app) | 69,644 |
| window open (cycle 1) | 520,956 (app 217,932 + Net 55,352 + Web 247,672) | 555,308 |
| after close | 517,516 | **293,388** (app ~218 MB + Net ~55 MB left) |
| open again | 792,632 (2 Web) | 557,080 |
| after 3 cycles | 1,032,468 (4 procs) | 298,000 |
The app process itself grows from ~69 MB to ~217 MB at the first window (WebKit/GTK libs mapped) and does **not** shrink after close. So the floor after
the first window is ~290 MB RSS, not 69 MB. A true 69 MB resident floor would need the first window never to be created in the tray process (two-process design) – spec D9 chose one process.

## 7. Notification (VERIFIED)
`dbus-monitor` (`dbus-monitor-notify.txt`): `org.freedesktop.Notifications.Notify` from `:1.137`, app_name `tauri-tray`, summary "Spike", body "hello from tray",
expire -1; Rust log `SPIKE notify: Ok(())`. No screenshot of the toast.

## 8. Release build (VERIFIED)
`npx tauri build --no-bundle` (v2 builds only the binary; no bundle config needed, `bundle.active=false`):
- Cold (empty cargo registry, `cargo build` of ~400 crates, vite build): **11 min 29 s** wall (user 27 min).
- Warm, after editing main.rs: 1 m 55 s, 1 m 23 s, 58 s, and a no-change re-run 1 m 14 s (the app crate is rebuilt each time; the 3 edits above also
  recompiled the tauri crates once because I ran plain `cargo build --release` in between, which flips the `custom-protocol` feature - use `npx tauri build` only).
- Binary: **17.8 MB** (17,811,928 B, release, default profile, no strip/LTO tuning). `target/` is 4.3 GB (release + debug).
- Runtime `ldd` list: `ldd-release.txt` (144 lines incl. transitive). Direct ones that matter for the installer: `libwebkit2gtk-4.1.so.0` (+ `libjavascriptcoregtk-4.1.so.0`, same package webkit2gtk-4.1),
  `libgtk-3.so.0` (gtk3), `libsoup-3.0.so.0` (libsoup3), gdk-3, gdk_pixbuf, cairo, glib/gobject/gio, libdbus-1, pango; all arrive as dependencies of webkit2gtk-4.1 + gtk3. The appindicator lib is NOT in ldd:
  it is dlopened (`libayatana-appindicator3.so.1` or `libappindicator3.so.1`), so it is an install-time `Recommends`, not a link dependency. No `not found` entries.

## 9. Other gates on the skeleton (VERIFIED)
| Command | Time |
|---|---|
| `cargo clippy -- -D warnings` | cold 3 m 14 s (clean, no warnings); re-run after `touch` 0.8 s |
| `cargo fmt --check` | 0.05 s (clean after `cargo fmt`) |
| `cargo test` | 4 m 18 s (debug profile compiles everything again; 0 tests) |
| `npx tsc --noEmit` | 1.1 s |
Gate cost for DS3+: first CI run ~20 min (cold clippy + test + release); incremental is dominated by the 1-2 min release link.

## 10. Surprising / notes
- libappindicator path: the SNI `IconName` is an absolute file path in `/run/user/<uid>/tray-icon/` and **the temp PNGs are leaked** when the process is killed with SIGTERM (I deleted mine). A normal Quit (menu) removed them.
- tray-icon writes a new PNG per `set_icon`; a 2-s cycle churns files (7+ per 16 s) - DS3 should swap icons only on state change.
- Window title bar is GTK client-side decoration; the screenshot shows a grey strip below the webview (GTK window chrome, not styled).
- `Gtk-Message: Failed to load module "appmenu-gtk-module"` on every start (harmless).
- Without a display env (autostart before session env import) the app panics "Failed to initialize GTK": D6 autostart must run inside the graphical session (XDG autostart does).
- A window with `tauri.conf.json` `app.windows: []` starts tray-only as wanted; the capability file must list the window label (`main`) for notification/core permissions.
- Test hook: `SPIKE_CLOSE_AFTER=<s>` / `SPIKE_VARIANT=destroy|ctx|kill` in `main.rs` closes the window from a thread (same CloseRequested -> destroy path as the title bar button; I could not click the real X). Not a real click of the close button.
- Dark/light tray contrast: NOT checked (solid colour squares only).

## D6 autostart (described, not created)
`~/.config/autostart/aristotle-server.desktop` with `Type=Application`, `Name=Aristotle Server`, `Exec=<abs path to app> --hidden` (app starts tray-only anyway), `X-GNOME-Autostart-enabled=true`. Written by the root installer / removed by uninstall (P8).

# ksni tray (DS2b)

Code: `spikes/ksni-tray/` (plain Cargo binary, no webview) + env-var mode `SPIKE_WINDOW_ONLY=1` in `spikes/tauri-tray/src-tauri/src/main.rs`
(no tray, window at start, process exits when the window closes; `RunEvent::ExitRequested` is not prevented in that mode). Run 2026-11-18.
Evidence: `docs/spikes/desktop-tray/` (`ksni-tray-run1.log`, `ksni-tray-run2.log`, `ksni-panel-crops.png`, `ldd-ksni-tray.txt`, `dbus-monitor-ksni-notify.txt`, `ksni-no-watcher.log`).
GUI env verified: `wayland-1`, `/run/user/1000/bus` exist; GUI env exported by hand as in DS2.

## 1. Versions and runtime libs (VERIFIED, Cargo.lock)
ksni **0.3.7** (feature `blocking`; default `tokio` feature still on, so a tokio runtime runs inside), zbus 5.19.0 (ksni) and 6.3.0 (notify-rust), notify-rust **4.19.0**, tokio 1.54.1. 118 locked packages.
`ldd target/release/ksni-tray`: only `libgcc_s`, `libm`, `libc`, ld-linux. **No GTK, no webkit, no dbus-1 lib** (zbus is pure Rust).

## 2. Icon in Plasma (VERIFIED)
`org.kde.StatusNotifierWatcher RegisteredStatusNotifierItems` lists `org.kde.StatusNotifierItem-<pid>-1/StatusNotifierItem`, `Status=Active`.
`ksni-panel-crops.png` (3 spectacle shots, each cropped to the tray area in memory, full shots deleted): **the round icon is in the visible tray row** (not the overflow popup) and is **red, green, amber in the three crops** (cycling every 2 s confirmed; log `KSNI icon 0/1/2`). Pixmap icons (22x22 ARGB, in memory, no files) render fine; this resolves the DS2 doubt about the visible row (that one was libappindicator's file-path `IconName`).
The item exports `Activate`, `ContextMenu`, `Scroll`, `SecondaryActivate` (`busctl introspect`), unlike the Tauri/libappindicator item.

## 3. Left click reaches `activate` (D-Bus VERIFIED; real mouse click NOT performed by me)
`busctl --user call org.kde.StatusNotifierItem-<pid>-1 /StatusNotifierItem org.kde.StatusNotifierItem Activate ii 0 0` -> log `KSNI ... ACTIVATE (left click) at 0,0` then `open: spawned child pid N`. This proves the ksni method wiring only; whether Plasma calls `Activate` on a real left click is the PM check in `docs/desktop/runs/DS2b/build-report.md`. Via D-Bus there is no XDG activation token (log shows no `got xdg activation token`); a real click is expected to carry one (`ksni::current_xdg_activation_token()`, passed to the child as `XDG_ACTIVATION_TOKEN` for Wayland focus) - NOT VERIFIED.

## 4. Memory of the tray process (VERIFIED, `ps -o rss`, KB)
| State | RSS |
|---|---|
| idle, cycling, 5 s after start | 6,952 |
| idle after 619 s (10 min) of cycling | **6,960** (2 threads) |
| while the child window runs (run 1) | 6,996 (child 214,912 + WebKitNetwork 53,344 + WebKitWeb 245,944, all separate processes) |
| after the child exited (run 1) | 7,124 |
| run 2: child running / after exit / after a Notify and a second child | 6,916 / 7,240 / 8,260 |
The resident floor is **~7 MB** (vs ~290 MB for the one-process Tauri design), and a child run does not grow it (+0.2-1 MB, notify-rust/zbus threads included).

## 5. Child launch, close, gone (VERIFIED, close via test hook)
- Single instance: second `Activate` while the child runs -> `open: child 198096 already running, not starting another`; one `tauri-tray` process (pgrep). Method: the tray holds the child PID in `Arc<Mutex<Option<u32>>>`, cleared by the reaper thread.
- Reap: a thread owns the `Child` and calls `wait()`: `child 198096 exited: ... (unix_wait_status(15)) (reaped)` after SIGTERM; no `defunct` entry in `ps`; WebKit children gone with it.
- Window close: child started with `SPIKE_CLOSE_AFTER=6` (the DS2 hook, same CloseRequested path as the X button, NOT a real click): window created, 6 s later `child 199247 exited: Ok(ExitStatus(unix_wait_status(0))) (reaped)`, `pgrep` shows no `tauri-tray`/WebKit left, tray RSS unchanged (6,916 -> 7,240). Window-only exit on close works with exit code 0.
- Menu: dbusmenu ids 1 Open, 2 Notify, 3 Quit (`GetLayout`); `Event(id,"clicked")` ran `menu: Open` (spawned a child), `menu: Notify`, `menu: Quit` (tray exited, "Remote peer disconnected").
- The "Notify" menu item and left click use the same `open_window`; the child inherits the tray's environment (hence the GUI env must be in the tray's env: XDG autostart is fine).

## 6. Build and gates (VERIFIED)
| Item | Result |
|---|---|
| debug cold (registry warm from DS2 for most crates; ksni crates downloaded) | 1 m 44 s |
| release, cold target (registry warm) | 3 m 34 s (214 s) |
| release, warm (touch main.rs) | 44.5 s |
| debug, warm | 1.4 s |
| `cargo clippy -- -D warnings` | clean (rc 0); cold time not captured (a script bug lost it); warm 0.2 s |
| `cargo fmt --check` | clean, 0.1 s |
| `cargo test` | 0 tests, 0.5 s (already compiled) |
| release binary | 8,581,912 B (8.6 MB, no strip/LTO) |
| rebuilt `tauri-tray` release with the new env-var mode (`npx tauri build --no-bundle`) | 2 m 17 s warm, 17,819,576 B |
Note: no cargo offline cold build was run, so "cold" excludes the crates.io download time.

## 7. Notifications from the tray (VERIFIED shown; click callback NOT tested)
`notify-rust` 4.19.0 (zbus backend, own connection). `dbus-monitor-ksni-notify.txt`: `org.freedesktop.Notifications.Notify` app_name `ksni-tray`, summary `DS2b spike`, body `hello from the tray process`, actions `["open","Open"]`, expire -1; log `notify: shown`. KDE's `GetCapabilities` returns `actions`, `body-markup`, `persistence`, `inline-reply`, `x-kde-urls`, ... so a clickable action is possible. The code waits for it with `NotificationHandle::wait_for_action` in a thread; I did not click the toast, so the callback delivery is NOT VERIFIED. No screenshot of the toast.

## 8. Watcher restart / no watcher
- No watcher (VERIFIED on a private bus, `dbus-run-session`, `ksni-no-watcher.log`): `spawn()` returns `Err(Watcher(ServiceUnknown "The name org.kde.StatusNotifierWatcher was not provided by any .service files"))`; the tray does not exist then. With `.assume_sni_available(true)` ksni treats this as a soft error and calls `Tray::watcher_offline` instead (from the docs/source, NOT run).
- Watcher restart (plasmashell restart NOT done; from ksni 0.3.7 source `service.rs`): it listens for `NameOwnerChanged` on `org.kde.StatusNotifierWatcher`; owner lost -> `watcher_offline(OfflineReason::No)` (return `false` shuts the tray down, `true` keeps waiting); owner back -> `watcher_online()` and the item is re-registered automatically. Also `Error::WontShow` when no StatusNotifierHost exists. DS3 should return `true` (and log), so the tray survives a plasmashell restart.

## 9. Notes
- ksni's `blocking` API worked without our own tokio code (`handle.update(|t| ..)` from a plain thread).
- Updating the icon every 2 s is only for the spike; ksni emits `NewIcon` on each update. DS3: update only on state change.
- `ksni::current_xdg_activation_token()` exists for Wayland focus of the launched window.
- `target/` of the spike is gitignored (`spikes/ksni-tray/.gitignore`).
