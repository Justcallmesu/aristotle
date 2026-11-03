# TD1b brief — write docs/design.md from the approved choices

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` decisions Q37–Q42, `docs/design/design-notes.md`,
and skim `docs/design/mockups.html` (palette A only).

Write `docs/design.md`: the binding design spec every Compose UI ticket follows. Content:
- Palette A only (light + dark), mapped to M3 `ColorScheme` roles with hex values; accent (verdigris) usage rule: live work only;
  red reserved for Deny/Stop/errors; contrast pairs ≥4.5:1 listed. State plainly: no dynamic colour (Q38).
- Type: Literata + Atkinson Hyperlegible Next / Mono mapped to M3 `Typography` slots (size/line height/weight), how to load them
  (Compose downloadable Google Fonts, fallback).
- Shape: slightly rounded (give the corner radii per component size) — no pills (Q39).
- Spacing scale, elevation, motion durations/easing, reduced-motion rule.
- Component rules: tool card, approval sheet (Q41 layout), clarify, composer with Steer/Queue/Stop, banners (offline, reconnecting),
  connection pages (4503/4505), session row, profile chip, pairing code + fingerprint display.
- Icon direction: concept B (Q42) with the note that T20 must simplify it for 48 dp.
- A short "do not" list (the slop tells from the audit).
Keep it ≤250 lines, precise values, no prose padding. Do not edit other files except the report
`docs/v1-tickets/runs/TD1/design-md-report.md` (≤15 lines). No commits.
