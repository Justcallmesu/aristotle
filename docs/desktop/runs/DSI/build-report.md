# DSI build report — tray icon set (D19-5)

## Outcome
Real tray icons for Green / Amber / Red, four sizes each (16, 22, 24, 32 px), 16 px as a separate heavier drawing. The tray serves
all four sizes per state through `icon_pixmap`. `cargo test -p aristotle-tray`: 19 passed. `cargo build -p aristotle-tray --release`: clean, no warnings.

## Design
- Glyph: the paths of `docs/design/icon/aristotle-b2-monochrome.svg` (fold is a real hole). Badge top right where the glyph has no ink:
  green circle, amber triangle, red square (the state-light shapes of `docs/desktop/design.md`).
- One tint per state, not a light/dark pair: the tray cannot know the panel theme. Each is >= 3:1 against both Breeze panels
  (`#EFF0F1` / `#31363B`): green `#1E9C77` (3.03 / 3.53), amber `#B87700` (3.24 / 3.30), red `#E5483B` (3.46 / 3.09).
  This replaces the placeholder colours in `icons.rs` (the `#5FD1AE` etc. dark-panel set), which fail on the light panel.
- 16 px variant (`tray-<state>-16.svg`): head, neck and hand only (torso dropped), strokes thickened (outline 5 units, neck 8), badge
  about 1.5x larger, own viewBox. Ink is about 34 % of the pixmap.
- **Honest reading at 16 px:** triangle vs the other two is clear; circle vs square differ by about 5 to 6 pixels' worth of alpha. The shape cue
  is better than the 1x board drawing, still modest. This is the thing to judge on the real panel.

## Files
Created: `desktop/tray/assets/tray-{green,amber,red}.svg` (22/24/32 source), `tray-{green,amber,red}-16.svg` (heavy), `assets/png/tray-<state>-<16|22|24|32>.png` (12),
`desktop/tray/tools/make_icons.py`, `docs/design/desktop/tray-icons.html`, `docs/design/desktop/tray-icons/` (copy of the SVGs and PNGs for the sheet, 18 files),
this report.
Edited: `desktop/tray/src/icons.rs` (rewritten), `desktop/tray/src/tray.rs` (one line: `icons::pixmaps(state)`), `desktop/tray/Cargo.toml` (`png.workspace = true`).
**Outside the allowed paths, needed for the dependency:** `desktop/Cargo.toml` (one line under `[workspace.dependencies]`: `png = { version = "0.18", default-features = false }`)
and `desktop/Cargo.lock` (png, crc32fast, miniz_oxide, adler2, bitflags, fdeflate, flate2 entries). The other worker should keep these when editing the workspace.

## How the pixmaps are produced
`python3 desktop/tray/tools/make_icons.py` (run from the repo root, dev-time only) writes the SVGs and renders the PNGs with `rsvg-convert` (installed).
The PNGs are committed. `icons.rs` embeds them with `include_bytes!`, decodes at startup with the pure-Rust `png` crate and converts RGBA to
ARGB32 network order. No build script, no system tool at build time. After editing the script, also copy `assets/*.svg` and `assets/png/*.png` to
`docs/design/desktop/tray-icons/` (the sheet reads from there).

## Tests (`icons.rs`)
- every state x size decodes, dimensions as declared, data length = w*h*4, not blank, corner transparent;
- opaque pixels carry the state colour;
- the three states differ in alpha mask at every size (summed alpha difference >= 3 px, pairwise), not only colour;
- the 16 px variant is solid (ink fraction > 0.30).

## ldd of the tray (release)
```
linux-vdso.so.1, libgcc_s.so.1, libm.so.6, libc.so.6, /lib64/ld-linux-x86-64.so.2
```
Libc-only (libgcc_s and libm are part of the libc runtime set); no GUI toolkit, no webview.

## Panel check done by me
- No screenshot: the session is Wayland (Plasma); the only capture tools here (`spectacle`, `import`) grab the whole screen, which the brief forbids. No panel crop path.
- Instead I ran the release tray once on the user's session bus against a fake `ARISTOTLE_SERVER_BIN` script (fixture JSON from
  `server/internal/app/testdata/json`; the real server and service were not touched), and read `IconPixmap` over D-Bus with `busctl` while switching
  green -> amber -> red. Each state returned four pixmaps (16/22/24/32) with the right colour at the centre pixel (30,156,119 / 184,119,0 / 229,72,59). Tray
  killed afterwards; no `aristotle-tray` process left.

## Real-panel steps for the user
1. Preview sheet first: open `docs/design/desktop/tray-icons.html` in a browser (all states x sizes on both panels).
2. Build: `cd desktop && cargo build -p aristotle-tray --release` (binary at `desktop/target/release/aristotle-tray`, or `$CARGO_TARGET_DIR`).
3. Make a fake server so the real one is untouched. Fixtures live in `server/internal/app/testdata/json`:
   ```
   F=/tmp/fake; mkdir -p $F; J=$PWD/server/internal/app/testdata/json
   cp $J/status.json $F/green.json
   sed 's/"state":"reachable"/"state":"not-running"/' $J/status.json > $F/amber.json
   cp $J/status_not_running.json $F/red.json; cp $F/green.json $F/cur.json
   printf '#!/bin/sh\ncase "$*" in *version*) cat %s/version.json;; *status*) cat %s/cur.json; exit 1;; *) echo "{\\"error\\":\\"x\\"}"; exit 2;; esac\n' $J $F > $F/fake.sh
   chmod +x $F/fake.sh
   ```
4. Run it: `ARISTOTLE_SERVER_BIN=/tmp/fake/fake.sh desktop/target/release/aristotle-tray` (green icon appears in the panel).
5. Switch state, the tray polls every 5 s: `cp /tmp/fake/amber.json /tmp/fake/cur.json`, wait 5 to 10 s (amber); then `red.json` (red).
6. Look at the icon at the panel's real size (and with the panel at another icon size if you use one); try Breeze light and dark. Check the 16 px case
   (small panel / Icons-only task manager size 16) for the badge shapes. Quit from the tray menu or Ctrl+C.
7. Approve, or say what to change (badge size, colours, 16 px stroke weight); `make_icons.py` regenerates everything.
