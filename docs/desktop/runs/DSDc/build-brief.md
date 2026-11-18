# DSDc build brief — consolidate the chosen design into `docs/desktop/design.md`

Repo `/home/justcallmesu/project/personal/Aristotle`. Inputs: `docs/design/desktop/design-notes.md`, `docs/design/desktop/mockups.html`,
spec `docs/desktop/spec.md` row **D19** (the user's picks: all recommended options of design-notes §9; 11 replaced by P13).
Pattern for a binding design doc: `docs/design.md` (Android). Load `writing-for-agents`.

## Deliverable
`docs/desktop/design.md`, binding for UI tickets DS3w-DS6: only the chosen options (shell 2 grouped sidebar + status footer,
comfortable density, amber `warn` token, state light tertiary/error with shape + word, Pair "Show pairing code" step, logs 2,000
lines wrap on, terminal "Turn off" confirm only with open terminals, dialogs focus Cancel, Literata titles + Atkinson body). No
unchosen variants, no Tweaks. Sections: tokens as CSS custom properties (light + dark, exact values), type scale, spacing, radii,
elevation, motion + reduced motion; components (window shell, sidebar, status footer, state light, banners, row lists, buttons,
dialogs incl. D7 and D15 texts, QR block, log viewer); per view: layout, every state with the §22 field that drives it, empty and
error states; EN + ID string table (all keys, copied from the board); accessibility rules (focus order, contrast numbers);
fonts: files, licence (OFL), where they live (`desktop/ui/src/assets/fonts/`, copied by DS3w) — Literata download source URL and
the exact file to fetch (do not download it yourself; DS3w does it).
Keep `docs/design/desktop/` as the record of options; link to it.

## Files you may create/edit
`docs/desktop/design.md` only (+ report `docs/desktop/runs/DSDc/build-report.md`). `desktop/**` is another worker's: not yours.
Never git write commands; no installs; no skill/memory edits.

## Report
What went in, anything on the board that conflicts with the spec or the §22 contract (list, do not resolve silently).
