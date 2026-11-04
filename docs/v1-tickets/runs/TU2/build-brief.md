# TU2 build brief — fonts, icons, theme tokens (UI refinement foundation)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TU1/audit.md` (all findings, especially G1
and everything assigned to TU2), `docs/design.md`, `docs/spec.md` Q40, **Q66, Q67**, then `android/app/src/main/java/io/github/justcallmesu/
aristotle/ui/theme/{Type,Spacing,Theme,Color,Shape,Motion}.kt`, `res/drawable/ic_*`, and grep `heightIn(min = 48.dp)` across `ui/**`.

## Do
1. **Fonts (Q66):** bundle Atkinson Hyperlegible Next (400/500/600) and Atkinson Hyperlegible Mono (400/500) as `res/font/*.ttf` from the
   official source (Google Fonts download / the Braille Institute GitHub release), with `OFL.txt` saved to `docs/licences/` (for D45).
   Literata stays downloadable. Keep the device fallbacks. Verify the files are the real families (font name table) and the APK grows
   only by what they weigh (report the size).
2. **Typography floor:** text size Small/Default/Large is applied by scaling the `Typography` styles (not a density/fontScale override),
   so it reaches dialogs, sheets and menus too, and clamp every style at ≥ 11 sp (design.md §1). Remove the contradicting comment in
   `AppSettings.kt:27` (TS1 review). Move the scaling code into the theme; AppSettings only stores the choice.
3. **Controls (Q67):** remove `heightIn(min = 48.dp)` from visible buttons/chips and rely on Material's minimum interactive component size
   (touch area stays ≥ 48dp — keep a test or lint check that proves it). Add spacing tokens for the chat rhythm (4/12/20 dp) to `Spacing.kt`
   for TU4 to use; do not edit chat files.
4. **Icons:** one icon style everywhere (outlined, 24dp, as design.md says — check which it names); replace the mixed filled ones in
   `res/drawable/ic_*` keeping the same resource names so no screen file changes.
5. Everything else the audit assigns to TU2.

## Hard rules
Files: `ui/theme/**`, `data/AppSettings.kt` (comment + removal of scaling only), `res/font/**`, `res/drawable/ic_*` (not launcher/stat
icons), the `heightIn(48)` removals in any `ui/**` file (that one-line change only), `docs/licences/**`, `docs/design.md` §2 (fonts line:
bundled per Q66) and §spacing, `docs/v1-tickets/runs/TU2/build-report.md`. No commits; never git checkout/restore/stash/reset. Do not
install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TU2` exits 0.

## Report
`docs/v1-tickets/runs/TU2/build-report.md`, ≤25 lines: font source + licence + APK size delta, icons changed, every `heightIn` removed
(file:line), what TU3/TU4/TU5 should use from the new tokens.
