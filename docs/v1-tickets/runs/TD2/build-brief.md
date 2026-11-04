# TD2 — redesign mockups (Q74)

route: deep (design). Repo `/home/justcallmesu/project/personal/Aristotle`. Load and follow the `claude-design` skill, especially
"Mockups for a native app" and the Slop Diagnostic.

## The user's words (2026-11-04)
"i want to redesign the app, the application design is so rough, i want it look attractive and beautiful, the home is bad design, for the
chat model select i want to collapse the provider and can search model. for the effort use a slider wdyt? the home design is ugly."
Follow-ups: "its not the color but the component ui shape" (keep the palette); home: "show me options as mockups"; inspiration: "ChatGPT and
claude look ok".

## Read first
`docs/design.md` (binding palette A, type: Literata titles, Atkinson Hyperlegible Next/Mono — bundled, TU2), `docs/design/mockups.html`
+ `design-notes.md` (the v1 board), `docs/spec.md` Q38-Q46, Q59-Q74, `docs/v1-tickets/runs/TU1/audit.md`, the current device screenshots
in `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/ui/` (10-list, 19-live-chat, 21-model-sheet, 23-slash, 41-chat, f3, 52-live, 60-now),
the real screens in `android/app/src/main/java/io/github/justcallmesu/aristotle/ui/**` (what each screen must contain: sessions list live +
history + pins + search + profile chip + ⋮ menu (Q70) + offline/compat/what's-new banners; chat top bar, transcript rows incl. folded tool
calls (Q68), subagent/notification cards, prompt sheets (Q41), composer busy states (Q69); model sheet = models grouped by provider from the
catalog + reasoning levels from `ReasoningLevels`), and `docs/protocol.md` for real states.

## Deliver `docs/design/v2/mockups.html` (+ `docs/design/v2/design-notes.md`)
Self-contained HTML, phone frames 393x873 dp, light AND dark for each screen, real content (this project's real session titles / tool
names, never lorem), clickable primary path, a small Tweaks bar. Screens:
1. **Home — 3 options** (conservative / strong-fit / divergent), e.g. a ChatGPT/Claude-like drawer-free list with a search field,
   live sessions as a compact pinned block with a running indicator, history grouped by Today / Yesterday / Earlier, a large
   "New chat" affordance, PC status as a quiet line or chip (not a banner wall). Your call on the three — they must differ in
   composition, not colour.
2. **Chat** — the redesigned transcript: assistant text without a bubble (Claude/ChatGPT style) vs user bubble, tool calls as a quiet
   collapsible "N steps" line, cards, the composer as a rounded floating field with attach / model chip / send-stop, busy state (Q69).
3. **Model sheet** — search field at top; providers as collapsible sections (current provider expanded, others collapsed with a model
   count); current model checked; **reasoning effort as a stepped slider** with snapping ticks and the level name shown (only the levels
   the model supports; hidden when it has none). Also show the alternative (segmented control) side by side and say which you recommend
   and why (accessibility, 2-6 levels).
4. **Settings list** (Q72) and one sub-page, in the new component language.
5. **Prompt sheet** (approval) in the new language — keep Q41's information order.
Component language: define shapes (radii), elevation, row heights, chips, fields, buttons, sheets — Material 3 Compose-buildable only
(no blur, no CSS-only tricks). Map every token to Material 3 roles/typography/shapes in design-notes.md so build tickets can copy them,
plus a slop score.

## Hard rules
Only write `docs/design/v2/**` and `docs/v1-tickets/runs/TD2/report.md` (≤15 lines). No app code. No git state changes. No adb (the PM
shows it on the phone). Verify the HTML opens without console errors (browser tool if available) and say what you verified.
