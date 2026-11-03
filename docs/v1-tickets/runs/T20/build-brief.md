# T20 build brief — the Aristotle app icon (concept B, simplified)

route: deep (visual design). Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q6, Q42, Q46; `docs/design.md`
§1 (palette) and §6 (icon); `docs/design/icon-concepts.html` + `docs/design/icon/{thinker,seated,coin}*.svg` (concept B = the hand on
chin direction — identify which SVG is B from icon-concepts.html); the user's reference image is the engraving of the seated Aristotle,
hand on chin (described in spec Q6/Q42). Current icon resources: `android/app/src/main/res/mipmap-anydpi/`, `res/drawable/` (launcher
foreground/background), the manifest, and `ui/quote/QuoteLoadingScreen.kt` (uses the launcher foreground).

## Goal
A launcher icon that reads clearly at 48 dp on the Poco home screen (light and dark launchers, themed icons), in the Aristotle line-art
style: ink `#2B2119` on parchment `#F3EAD8`, a head-and-shoulders silhouette with the hand on the chin.

## Requirements
1. Simplify concept B per design.md §6: silhouette first, heavier stroke (≥ 3 dp at 48 dp equivalent), few interior lines, larger fist; no
   text, no thin hatching. Keep everything inside the 66 dp safe zone of the 108 dp adaptive canvas.
2. Deliver: `docs/design/icon/aristotle-b2.svg` (master, 108×108 viewBox) and `aristotle-b2-monochrome.svg`; Android vector drawables
   `ic_launcher_foreground.xml` (ink lines), `ic_launcher_background.xml` (parchment), `ic_launcher_monochrome.xml` (Android 13 themed
   icon), adaptive icon XML for `ic_launcher` and `ic_launcher_round` in `mipmap-anydpi`; legacy PNG mipmaps are NOT needed (minSdk 29
   supports adaptive icons) — confirm.
3. Preview sheet for the user: `docs/design/icon/preview.html` showing the icon at 48 dp, 64 dp, 108 dp, inside circle / squircle /
   rounded-square masks, on a light wallpaper, a dark wallpaper, and as a themed (monochrome tinted) icon; plus PNG renders of the same
   (render the SVG with any tool available locally, e.g. `rsvg-convert` or Inkscape if installed — check; if none, use a headless Chrome
   via the browser tool is NOT available to you, so say so and ship only the HTML).
4. The quote loading screen and any notification small icon: the status-bar icon (`ic_stat_*`, T10 used a stock bubble) becomes a white
   monochrome silhouette of the same mark (status-bar icons must be single-colour with alpha).
5. Do not change any other UI.

## Hard rules
Files: `android/app/src/main/res/{drawable,mipmap-anydpi}/**`, the manifest's icon attributes only, `ui/quote/QuoteLoadingScreen.kt`
(only if the icon reference changes), `background/Notifier.kt` (small icon reference only), `docs/design/icon/**`,
`docs/v1-tickets/runs/T20/build-report.md`. A T18b worker edits cron files and appends to strings.xml at the same time: do not touch
strings or cron. No commits; never git checkout/restore/stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T20` exits 0 (if it fails only in T18b's files, say so).

## Report
`docs/v1-tickets/runs/T20/build-report.md`, ≤20 lines, with the paths of the preview sheet and PNGs.
