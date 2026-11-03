# TD1 brief — design pass: Aristotle app mockups + design direction

route: deep (design work)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` (§1 v1 requirements, Q6, Q9,
Q10, Q24, Q27, Q28, Q34, Q35), and `docs/protocol.md` §2, §5, §6, §11 (so the screens show REAL states the app will have).
Icon reference: `docs/assets/icon-reference.png` (19th-c. engraving of Aristotle seated, hand on chin, plinth reads ΑΡΙΣΤ).
Load and follow the skills `claude-design`, `antislop-ui` and `make-interfaces-feel-better` before designing.

## Goal
A clickable HTML mockup board the user reviews (in the Hermes desktop chat preview and on his phone's browser) to choose the
look of the app before any UI ticket is built, plus a draft design-notes file the Compose tickets will follow.

## Constraints (locked)
- Android app in Jetpack Compose + Material 3 (Q9). Everything shown must be buildable with M3 components + custom
  theming — no web-only effects (no backdrop blur, no CSS tricks Compose can't do).
- Target device: Poco X5 Pro, 1080×2400 px ≈ **393 × 873 dp** portrait. Draw each screen in a phone frame at that size (CSS px = dp).
- Light AND dark for every screen (the user's phone is in dark mode).
- Two colour identities to compare (decision Q38 is open; the user chooses from your board):
  **(A) Aristotle palette** derived from the engraving (parchment / ink / sepia, one deliberate accent; must also work in dark),
  **(B) Material You** look (a plausible dynamic-colour scheme, e.g. from a blue-grey wallpaper).
- English copy, with an Indonesian toggle for at least the pairing and approval screens (Q28). Draft copy is fine; mark it draft.
- Brand type: you may choose one display face for the wordmark/headers (must be a Google Font available to Compose's downloadable
  fonts) + one UI sans. Justify the choice in the notes. No Inter by default.

## Screens (each a real state from the protocol, realistic content, no lorem ipsum, no fake metrics)
1. **Pair**: manual entry (address + 8-char code), the certificate fingerprint to compare in 4-char groups, a "Scan QR" action; plus the
   error state "invalid or expired pairing code".
2. **Sessions**: drawer/list of sessions with titles, last activity, the active profile chip and profile switcher, a running
   indicator on a session with a live turn, "PC offline since 22:10" banner state.
3. **Chat, live turn**: user message, assistant streaming text, a collapsed and an expanded tool-call card (e.g. terminal
   `go test ./...` with output), reasoning indicator, the busy composer with **Steer / Queue / Stop** (Q10).
4. **Approval**: the agent wants to run `rm -r /tmp/build-cache` — approve once / for session / always / deny; and the
   "answered on another device" withdrawn state. Also a clarify question with 2–3 choices.
5. **Offline / connection**: Hermes not running (close 4503), Hermes updated / protocol unsupported (4505), reconnecting.
Optional if time allows: the Xiaomi battery-settings guide (Q27).

## Deliverables
- `docs/design/mockups.html` — one self-contained file (embedded CSS/JS, web fonts may load from Google Fonts), all screens in
  phone frames on one board, with a small Tweaks bar: palette A/B, light/dark, EN/ID. Make the primary path clickable
  (Pair -> Sessions -> Chat -> Approval) where cheap.
- `docs/design/icon-concepts.html` (or SVGs in `docs/design/icon/`): 2–3 adaptive-icon concepts redrawn from the reference
  (simplified line art, readable at 48 dp, a monochrome variant) — shown at 48/96/192 px on light and dark launcher backgrounds.
  This feeds T20; it is a concept, not final assets.
- `docs/design/design-notes.md` — draft tokens per palette (colour roles mapped to M3 `ColorScheme` names, type scale mapped to
  M3 `Typography` slots, shape, spacing, elevation, motion), component rules (tool card, approval sheet, composer, banners),
  and the slop self-audit score with which tells fired.

## Verify
- Open `docs/design/mockups.html` in the browser tool at a desktop width and at 393 px width; no console errors; take a screenshot of
  each palette in dark mode and look at them yourself; fix what the slop audit flags.
- Files exist; report paths.

## Hard rules
- Files you may create: `docs/design/**` and `docs/v1-tickets/runs/TD1/build-report.md`. Do not touch `android/`, `server/`,
  other docs. No commits.

## Report
`docs/v1-tickets/runs/TD1/build-report.md`, ≤30 lines: files; type + palette choices and why; slop score; what to ask the user.
