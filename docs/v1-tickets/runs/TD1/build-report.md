# TD1 build report — design pass

**Files** (all new, nothing else touched, no commits)
- `docs/design/mockups.html`: 13 phone screens at 393 × 873 dp: pair, pair 401, sessions, profile sheet, PC offline, live chat, approval, withdrawn, clarify, 4503, 4505, reconnecting, Xiaomi battery guide. Tweaks: identity A/B, light/dark, EN/ID (all screens), type, board/click-through (Pair → Sessions → Chat → Approval → Chat).
- `docs/design/icon-concepts.html` + `docs/design/icon/{seated,thinker,coin}{,-monochrome}.svg`: 3 concepts traced over the reference, at 48/96/192 px on light and dark launchers, circle and squircle masks, themed monochrome.
- `docs/design/design-notes.md`: M3 `ColorScheme` tokens for both identities (light and dark), `Typography` slots, shapes, spacing, elevation, motion, component rules, slop audit, open questions.

**Choices**
- A, Aristotle: parchment and iron-gall ink from the plate. One accent, verdigris (bronze patina), used only for live work (running spinner, streaming cursor), so red stays free for Deny, Stop and errors. All text pairs are ≥ 4.5:1 (computed).
- B, Material You: dynamic colour on the phone. The board shows a plausible blue-grey tonal-spot sample, which is not the user's real wallpaper colours. Both identities use the same role rules, so switching between them is a theme swap.
- Type: Literata (screen-made book serif with Greek glyphs) for titles; Atkinson Hyperlegible Next/Mono for the UI and code, because it keeps 0/O and 1/l/I apart, which matters for pairing codes and fingerprints. All are Google Fonts available to Compose's downloadable fonts. Roboto Flex is there as a tweak for comparison.

**Verified**: opened in the browser at 1440 px and at 393 px (mobile emulation). No console errors and no horizontal overflow. Click-through path and card toggles work. I screenshotted every screen in A dark, A light, B dark, B light and Indonesian, and looked at each set myself.

**Slop score**: 3/10 on the first pass (icon above the 4503/4505 headlines, a 1 dp rule on the reasoning text, a decorative dot on the profile chip). All three are removed. Now 0/10 for A and 1/10 for B: B keeps a blue primary because the brief asked for a blue-grey Material You sample.

**Caveats**: Icon concept B (hand on chin) is weak at 48 dp. The cut lines in the SVGs still have to be turned into real holes in T20 (the monochrome files already do this with a mask). The server version "0.1.0" and the session names are sample data. All copy, EN and ID, is draft.

**Ask the user**
1. Identity A or B (Q38)? Or A by default with B as a setting?
2. A's buttons: 8 dp corners or M3 pills?
3. Literata + Atkinson, or plain Roboto Flex? Atkinson's zero is slashed, and that shows in times too.
4. Approval layout: Deny and Allow once side by side at the bottom, with For this session and Always as text buttons below. OK?
5. Which icon goes into T20: A seated, B hand on chin, or C coin head?
