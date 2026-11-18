# DSI build brief — tray icon set (D19-5)

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Read `docs/desktop/design.md` (tray section, tokens:
ok/warn/err colours), `docs/design/desktop/design-notes.md` §6 + choice 5, `docs/design/desktop/mockups.html` (tray icons as drawn),
the app icon sources `docs/design/icon/` and `docs/assets/icon-reference.png`, and `desktop/tray/src/icons.rs` (current placeholder
pixmaps). Load `antislop-ui`.

## Goal
Real tray icons for Green / Amber / Red: derived from the app icon, tint + state badge (shape differs per state, not only colour),
with a **heavier 16 px variant** (head and hand only, per design-notes) and 22/24/32 px variants. Readable on Breeze light and
dark panels.
- Source: SVGs under `desktop/tray/assets/` (one per state, plus the 16 px variant), and the rendered ARGB pixmaps the tray loads
  (embed at build time with `include_bytes!` of PNGs, or generate from the SVGs in a build step that needs no system tool beyond what
  is installed: check `rsvg-convert`/`magick` presence first; if none, render with a pure-Rust crate in a dev-only tool binary).
- `icons.rs` serves the right size per request (ksni `icon_pixmap` returns several sizes); keep the tray libc-only (`ldd`).
- A preview sheet `docs/design/desktop/tray-icons.html` (all states x sizes on light and dark backgrounds) for the user's approval.

## Tests
Pixmaps decode, sizes as declared, three states differ in alpha mask (shape), not only colour.

## Real panel check (for the user, prepared by you)
Write the exact steps in the report to run the tray and see the three states on the real panel; you may launch the tray once on
the user's desktop with a panel-area crop only (never full screen) and quit it.

## Files you may create/edit
`desktop/tray/**`, `docs/design/desktop/tray-icons.html`, `docs/design/desktop/tray-icons/**`. `desktop/app/**`, `desktop/ui/**`,
`desktop/core/**`, `docs/desktop/gates.sh` belong to another worker running now: not yours. Run only `cargo test -p aristotle-tray`
and `cargo build -p aristotle-tray --release` (the other worker runs the full gate).

## Hard rules
Never git write commands; no system installs; never touch the server service, autostart, Hermes; kill your processes; no skill/memory edits.

## Report
`docs/desktop/runs/DSI/build-report.md`: files, how pixmaps are produced, tests, ldd of the tray, panel crop path, real-panel steps.
