# DS2 build brief — Tauri v2 tray spike on KDE (throwaway)

Repo: `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md` first). Spec: `docs/desktop/spec.md` (D2 Tauri,
D4 tray colours + menu, D5 notifications, D6 autostart hidden, D9 one process / window destroyed on close, D13 React+TS+Vite).

## Why
The whole desktop app rests on unverified facts: does a Tauri v2 tray icon work on this KDE Plasma (Wayland or X11?), does a
LEFT click on the tray reach the app (StatusNotifierItem may deliver only the menu), can the window's webview be destroyed
on close and recreated, what does it cost in memory and build time, and which shared libraries does the binary need at
runtime. Answer them before any product code is written. This code is thrown away; the findings doc is the deliverable.

## Build (in `spikes/tauri-tray/` only)
- A minimal Tauri v2 app, React + TypeScript + Vite frontend (one page saying "spike").
- Tray icon that cycles green → amber → red every 2 s (three small generated PNGs are fine).
- Tray menu: "Open", "Notify", "Quit". Left click on the icon opens the window (if the platform delivers it).
- Closing the window DESTROYS it (not hide); the next Open/click creates a new one. Starts with no window (tray only).
- "Notify" shows one desktop notification (Tauri notification plugin).
- A `.gitignore` inside `spikes/tauri-tray/` for `node_modules/`, `target/`, `dist/`.

## First, check and record (stop and report if any is missing — do not install system packages)
`rustc --version`, `cargo --version`, `node --version`, `npm --version`, `pacman -Q webkit2gtk-4.1 gtk3 libappindicator
libayatana-appindicator librsvg 2>&1`, `echo $XDG_SESSION_TYPE $XDG_CURRENT_DESKTOP` (from the user's session: also check
`loginctl show-session $(loginctl | awk '/seat0/{print $1; exit}') -p Type`), `pgrep -a plasmashell`.
Project-local dependencies are allowed: `npm install` inside the spike dir (incl. `@tauri-apps/cli` as a devDependency, run
via `npx tauri`), and cargo fetching crates. NOT allowed: `npm -g`, `cargo install`, `rustup`, `pacman`, `yay`, any sudo.
If the Tauri Linux build needs a system library that is absent (e.g. it insists on libayatana-appindicator), stop and report
the exact package name; the PM asks the user.

## Find out and record in `docs/spikes/desktop-tray.md` (each item VERIFIED with evidence, or a stated failure)
1. Versions: tauri, tauri-build, @tauri-apps/cli, plugins, tray-icon/libappindicator crate as resolved in Cargo.lock.
2. Which appindicator library the binary loads at runtime (`ldd` + `strace -f -e trace=openat` or `LD_DEBUG=libs` grep), and
   whether that works with what is installed.
3. Session type (Wayland/X11) and whether the tray icon appears in Plasma's system tray; colour cycling visible.
4. Left click on the icon: does it reach the app (log a line in the Rust handler) or only open the menu? Same for middle click.
5. Window: destroyed on close (the webview process count drops: `pgrep -fa WebKit` before/after) and recreated on Open.
6. Memory: RSS of the app + WebKit processes (sum, `ps -o rss`) with no window, with the window open, and after close.
7. Notification shown (screenshot or `dbus-monitor` line of org.freedesktop.Notifications.Notify).
8. `npx tauri build --no-bundle` (or the v2 equivalent that produces only the binary): wall time (cold and warm), binary size,
   `ldd` list of the release binary (DS7 needs the runtime deps).
9. What `cargo clippy -- -D warnings`, `cargo fmt --check`, `cargo test` and `npx tsc --noEmit` cost in time on this skeleton.
10. Anything surprising (Wayland quirks, GTK warnings, icon sizing, dark/light tray icon contrast).
Screenshots: the GUI runs on the user's live desktop. You may launch the spike binary there (it only shows a tray icon and a
window) and must quit it afterwards; take screenshots with `spectacle -b -n -o <file>` (or `grim`) if available, saved under
`docs/spikes/desktop-tray/`. If screenshots fail, say so; the PM checks by eye.

## Files you may create/edit
`spikes/tauri-tray/**`, `docs/spikes/desktop-tray.md`, `docs/spikes/desktop-tray/**`. Nothing else.
`server/**`, `docs/protocol.md` and `docs/desktop/runs/DS1/**` belong to another worker running now: not yours.

## Hard rules
- Never `git add/commit/push/checkout/restore/stash/reset`.
- Never touch `aristotle-server`, its service, `~/.local/state/aristotle`, `~/.config/aristotle`, Hermes or its Desktop app,
  `~/.config/autostart` (do NOT create an autostart entry; describe how D6 would do it instead).
- Kill every process you started before you finish (`pgrep -fa tauri-tray` must be empty).
- Do not edit skills, memory or agent config; put tips in the report.

## Stop and ask (report and stop) if
- The tray icon does not show on KDE, or left click cannot be received AND the menu cannot carry "Open" (D4 needs one of them).
- A system package is needed.

## Report
`docs/desktop/runs/DS2/build-report.md`: short summary of the 10 findings with the recommendation for DS3 (crate versions to pin,
how to do destroy-on-close, tray click handling, runtime deps list for the installer), plus anything that contradicts the spec.
