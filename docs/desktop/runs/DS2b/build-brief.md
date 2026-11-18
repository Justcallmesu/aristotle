# DS2b build brief — `ksni` tray + launch-on-click spike (throwaway)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Spec: `docs/desktop/spec.md`, especially D4, D5, D17,
D18 and the "DS2 spike findings" section. Previous spike: `docs/spikes/desktop-tray.md` (Tauri tray: no left click, ~290 MB
after the window closes). Read both first.

## Why
D18 splits the app into a small resident tray process (`ksni` StatusNotifierItem, notifications, poller, no webview) and a
Tauri window app it launches on left click, which exits when its window closes. Prove the tray half on this KDE Plasma (Wayland)
before DS3 builds on it.

## Build (in `spikes/ksni-tray/` only; a plain Cargo binary, no Tauri, no webview)
- `ksni` tray (latest release; record the version) with an icon that cycles green → amber → red every 2 s (pixmap icons generated
  in code are fine; no PNGs written to disk on each change).
- `activate` (left click): launches the DS2 Tauri spike as the child window app. Add an env-var mode to
  `spikes/tauri-tray/src-tauri/src/main.rs` (e.g. `SPIKE_WINDOW_ONLY=1`): no tray, open the window at start, exit the process when
  the window closes. Rebuild it (release) and launch that binary by absolute path.
- Single instance: a second left click while the child runs does not start a second one (record how you detect it: child PID
  held by the tray, or a lock file / D-Bus name; recommend one for DS3).
- Menu (right click): "Open", "Notify", "Quit". "Notify" sends one desktop notification from the tray process (`notify-rust` or
  zbus directly; record which).
- When the child exits, the tray notices (reaped, no zombie).

## Find out and record in `docs/spikes/desktop-tray.md` (append a section "ksni tray (DS2b)"; VERIFIED with evidence or a failure)
1. Versions resolved (ksni, zbus, notify-rust, ...). Runtime libs: `ldd` of the release binary (expect no GTK/webkit).
2. The icon shows in Plasma's tray (visible row or overflow) — screenshot of the panel area only (`spectacle -b -n -r` region or a
   crop; never save full-screen shots, they show private windows; delete any that slip through).
3. **Left click reaches `activate`**: the user has to click for real. Prepare everything, then write the exact steps for the PM
   into the report ("run X, left-click the icon, expect Y in log Z") and include a log line the click will produce. If you can
   trigger `Activate` over D-Bus (`busctl --user call <name> /StatusNotifierItem org.kde.StatusNotifierItem Activate ii 0 0`), do
   that and record it as D-Bus proof; the real click stays for the PM/user.
4. Memory: RSS of the tray process alone (idle, after 10 min of cycling, while the child runs, after the child exits).
5. Child launch → window shown → close → child process gone (`pgrep`), memory of the tray unchanged.
6. Build time cold/warm (debug and release), binary size; `cargo clippy -- -D warnings`, `cargo fmt --check`, `cargo test` times.
7. Notifications from the tray: shown (dbus-monitor line), and whether actions/clicks on the notification are possible (for later).
8. Behaviour when the StatusNotifierWatcher restarts (`plasmashell` restart is NOT allowed; just note what ksni documents) and when
   no watcher exists (ksni's offline callback).
9. Recommendation for DS3: crate layout (workspace: `core` lib crate + `tray` bin + `desktop` Tauri bin), single-instance method,
   how the tray finds the window binary (absolute path next to itself in `~/.local/bin`).

## Allowed / not allowed
Project-local cargo/npm dependencies only. NOT allowed: `pacman`, `yay`, `sudo`, `cargo install`, `npm -g`, `rustup`,
restarting plasmashell or any session service, creating autostart entries, touching `aristotle-server`, its service or state,
Hermes, Hermes Desktop. Never `git add/commit/push/checkout/restore/stash/reset`. Kill every process you start before you finish
(`pgrep -fa 'ksni-tray|tauri-tray'` empty).

## Files you may create/edit
`spikes/ksni-tray/**` (with its own `.gitignore` for `target/`), `spikes/tauri-tray/src-tauri/src/main.rs` (only the env-var
"open window at start, exit on close" mode), `docs/spikes/desktop-tray.md` (append only), `docs/spikes/desktop-tray/**`.
`docs/design/desktop/**` belongs to another worker running now: not yours. Server files are under review: do not touch `server/`.

## Stop and ask (report and stop) if
- The icon does not appear in Plasma at all with ksni, or a system package is needed.

## Report
`docs/desktop/runs/DS2b/build-report.md`: the findings in short, the exact real-click check steps for the PM, the DS3 recommendation.
