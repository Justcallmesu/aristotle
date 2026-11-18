# DS2 build report

Full evidence: `docs/spikes/desktop-tray.md`, `docs/spikes/desktop-tray/`. Code: `spikes/tauri-tray/`. All started processes are killed (`pgrep -fa 'tauri-tray|WebKit'` empty); nothing git-staged; no system packages installed; no autostart entry created.

## Stop condition hit (PM decision needed)
- **The tray icon was not seen in Plasma's visible panel row** (3 crops; registered with StatusNotifierWatcher, Status Active, colour swaps happening). It may be in the "^" overflow popup; I could not open it without injecting input. **Please check by eye** (run the binary with WAYLAND_DISPLAY/DISPLAY/XDG_RUNTIME_DIR/DBUS_SESSION_BUS_ADDRESS exported: `spikes/tauri-tray/src-tauri/target/release/tauri-tray`, Quit from the menu).
- **Left click cannot reach the app**: libappindicator's SNI has no `Activate`/`ContextMenu` method (`No such method "Activate"`), tray-icon emits no click events on Linux. The menu works (Open/Notify/Quit verified through dbusmenu `Event`). D4 is satisfiable only through the menu item "Open".

## The 10 findings (short)
1. tauri 2.12.6, tauri-build 2.7.5, tauri-plugin-notification 2.8.1, tray-icon 0.25.1, muda 0.20.0, libappindicator(-sys) 0.9.0, tao 0.37.2, wry 0.57.2, @tauri-apps/cli 2.12.5.
2. The binary dlopens `libayatana-appindicator3.so.1`, falls back to `libappindicator3.so.1` (present). Works; no package needed (LD_DEBUG evidence).
3. Wayland + KDE. Registered and cycling; visible in panel: NOT confirmed (above).
4. Left/middle click never reach Rust (D-Bus-level proof; no real mouse click).
5. close()/destroy() remove the window and the app survives (needs `prevent_exit` on `ExitRequested{code:None}`), re-Open recreates. **WebKit WebProcess leaks** (+1 per cycle, ~245 MB each, never exits). Fix verified: `with_webview(|wv| wv.inner().terminate_web_process())` before close.
6. RSS: 69 MB tray-only; ~520 MB with window; after close 517 MB (leak) vs 293 MB with the fix (app process stays ~218 MB, NetworkProcess ~55 MB). **Contradicts spec D9** ("memory freed"): floor after first window is ~290 MB.
7. Notification verified by dbus-monitor (Notify, "Spike"/"hello from tray").
8. Release `--no-bundle`: cold 11 m 29 s, warm 1-2 min, binary 17.8 MB; ldd needs webkit2gtk-4.1 (+JavaScriptCore), gtk3, libsoup3 and their deps; appindicator is dlopen only.
9. clippy cold 3 m 14 s, fmt 0.05 s, cargo test 4 m 18 s (0 tests), tsc 1.1 s.
10. Leaked tray PNGs in `/run/user/1000/tray-icon/` on SIGTERM; PNG written per `set_icon`; GTK init panics without a display env; spectacle works, grim/strace absent.

## Recommendation for DS3
- Pin `tauri = "=2.12.6"`, `tauri-build = "=2.7.5"`, `tauri-plugin-notification = "=2.8.1"`, `@tauri-apps/cli` `2.12.5`; keep the lockfile committed; add `webkit2gtk = { version = "=2.0.2", features = ["v2_40"] }` under `cfg(target_os="linux")` for the leak fix.
- Destroy-on-close: `.build()` the window on demand; on `CloseRequested` call `terminate_web_process()` then let it close; in `app.run` call `api.prevent_exit()` when `code.is_none()`; `app.exit(0)` only from Quit. Starts tray-only with `app.windows: []`.
- Tray: menu is the only reliable entry. Put "Open" first; also handle `TrayIconEvent::Click` (harmless) but do not depend on it. Set icons only on state change (each set writes a PNG). Ask the user whether the missing left click is acceptable (D4 wording) and confirm the panel visibility first.
- Installer deps (D12): required `webkit2gtk-4.1`, `gtk3` (Arch names); recommended `libayatana-appindicator` or `libappindicator` (either; needed at runtime for the tray); Debian equivalents `libwebkit2gtk-4.1-0`, `libgtk-3-0`, `libayatana-appindicator3-1`. The installer's "webkit2gtk-4.1 present" check should also check an appindicator lib.
- Gate time: budget ~20 min cold, 1-2 min warm release; run `cargo test`/clippy in debug profile once, cache `target/`.

## Contradictions with the spec
- D9: memory is not freed by destroying the window unless the WebProcess is killed; even then ~290 MB stay resident.
- D4/D1 "clicking the tray icon opens the UI": not possible through Tauri on Linux; menu only.
- Spec "Risks": the Rust toolchain is working now (rustc 1.100.0), so D11 is already done on this PC.

## Files
Created: `spikes/tauri-tray/**` (incl. `.gitignore`; `node_modules`, `target` 4.3 GB, `dist` ignored), `docs/spikes/desktop-tray.md`, `docs/spikes/desktop-tray/{ldd-release.txt, ld-debug-appindicator.txt, dbus-monitor-notify.txt, window-open.png, tray-panel-crops.png}`, this report. Nothing else touched. Tip: `~/.cargo/registry` (269 MB) now exists from crate fetching.
