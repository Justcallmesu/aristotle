# DS2b build report

Built `spikes/ksni-tray/` (ksni 0.3.7 + notify-rust 4.19.0, blocking API) and the `SPIKE_WINDOW_ONLY=1` mode in `spikes/tauri-tray/src-tauri/src/main.rs`; full findings appended to `docs/spikes/desktop-tray.md` ("ksni tray (DS2b)"). All my processes are killed (`pgrep -fa 'ksni-tray|tauri-tray'` empty). No system install, no git write command, no plasmashell/Hermes/server contact.

## Findings in short
- **The ksni icon shows in Plasma's visible tray row** and cycles red/green/amber (panel crops, `docs/spikes/desktop-tray/ksni-panel-crops.png`). No stop condition hit.
- The item exports `Activate`; `busctl ... Activate ii 0 0` runs `activate` and launches the child (D-Bus proof). **The real left click is not tested (needs the user).**
- Tray resident RSS **~7 MB**, 6,960 KB after 10 min of cycling; unchanged (+0.2-1 MB) while a child runs and after it exits. `ldd`: libc/libm/libgcc_s only.
- Single instance by child PID: second Activate -> "already running". Reaper thread `wait()`s: no zombie, WebKit children gone. Window close (test hook, same path as the X button) -> child exit code 0, `pgrep` empty.
- Menu Open/Notify/Quit all work (via dbusmenu `Event`). Notification via notify-rust is shown (dbus-monitor); actions are supported by KDE, the click callback is untested.
- No watcher: `spawn()` returns an Err (verified on a private bus). Watcher restart: ksni re-registers by itself (source, not run).
- Builds: release cold 214 s, warm 44 s, binary 8.6 MB; clippy/fmt clean; 0 tests. Rebuilt the Tauri spike release (2 m 17 s).

## Real-click check for the PM (user must click)
1. `cd /home/justcallmesu/project/personal/Aristotle/spikes/ksni-tray && WAYLAND_DISPLAY=wayland-1 DISPLAY=:1 XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus ./target/release/ksni-tray 2>/tmp/ksni.log &`
   (rebuild with `cargo build --release` if the binary is missing; child binary path defaults to `../tauri-tray/src-tauri/target/release/tauri-tray`, override with `ARISTOTLE_WINDOW_BIN`).
2. Look in the tray row for the round icon that changes red/green/amber every 2 s.
3. **Left-click the icon once.** Expect the "spike" window to open and in `/tmp/ksni.log`: `KSNI <t> ACTIVATE (left click) at <x>,<y>`, `open: got xdg activation token` (maybe), `open: spawned child pid <N>`. If the log has no ACTIVATE line but a menu pops up, Plasma did not call `Activate`.
4. Left-click again while the window is open: expect `open: child <N> already running, not starting another`, no second window.
5. Close the window with the X: expect `child <N> exited: Ok(ExitStatus(unix_wait_status(0))) (reaped)`; `pgrep -fa tauri-tray` empty.
6. Right-click: menu Open / Notify / Quit. Notify shows a toast with an "Open" action; if you click it, the log prints `notify: action/close = open` (untested by me).
7. Quit from the menu (or `pkill -f release/ksni-tray`).

## DS3 recommendation
- Cargo workspace: `core` (lib: poller, state, server client, events), `tray` (bin `aristotle-tray`: ksni + notify-rust + poller), `desktop` (Tauri bin `aristotle-desktop`). `tray` must not depend on tauri/webkit (keeps `ldd` free of GTK, ~7 MB).
- Single instance: keep the child PID in the tray (as in the spike) for tray-side dedupe, **plus** a single-instance guard inside `aristotle-desktop` itself (`tauri-plugin-single-instance`, D-Bus name based; a second launch focuses the open window), because the user can start the window app from a launcher too. A lock file adds nothing beyond that.
- Window binary location: next to the tray, `std::env::current_exe()?.parent()/aristotle-desktop` (installed to `~/.local/bin`), with an env override for development; spawn with absolute path, own reaper thread.
- Pass `XDG_ACTIVATION_TOKEN` from `ksni::current_xdg_activation_token()` to the child for Wayland focus (works only if a real click carries a token: verify in the check above).
- `watcher_offline` returns `true`; use `.assume_sni_available(true)` so autostart before the shell is up does not fail. Update the icon only on state change. Keep the window-app's `terminate_web_process` workaround out of it: the process exiting on close frees everything.
