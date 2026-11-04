# TD2 report — v2 redesign mockups (Q74)

- Delivered `docs/design/v2/mockups.html` (24 phone frames 393×873, every screen light + dark) and `docs/design/v2/design-notes.md`.
- Screens: Home A (conservative) / **B (recommended: search field, live grouped block, one-line history by day, full-width New chat)** / C (live cards + ledger, bottom app bar); chat busy, busy-with-text (steps open, delegate card, queued), new/idle; model sheet slider, segmented alternative, search state; Settings + Connection & PCs; approval sheet.
- Tweaks: view (board / clickable path), theme, corners (v1 / **Soft** / Round), PC link (online / offline / update notices). Path: home → chat → model sheet / approval → back; ⋮ → Settings → Connection.
- Content is real: titles and counts from the pm profile's state.db (day groups shifted one day), tool output from this project's chats, Hermes' model catalog (Anthropic 13, OpenRouter 60, Copilot 44). State demos are labelled on the board.
- Effort: recommend the **slider**. `ReasoningLevels.choices` yields 0, 7 or 8 levels, never 2–6. Eight don't fit one SegmentedButton row, and the slider has one TalkBack control (stateDescription, one level per swipe). It sends one config call per gesture.
- Notes map every token to M3 `Shapes` plus 3 custom shapes, `ColorScheme` roles with computed contrast, Typography slots and Compose components. They also list the spec changes needed (Q39 radii, Q68 "N steps" line with reasoning folded in, Q70 for B/C, composer, model sheet, new strings) and a TR1–TR6 ticket split.
- Palette A, fonts, Q41 order and Q67 sizes are unchanged; no blur, no gradients, no new shadows.
- Slop score: 0/10. Review fixed bugs, not slop: lost red on approval rows, fingerprint overflow, model-sheet scroll, button width, chip truncation.
- Verified in Chromium (browser harness): file loads with 0 page console errors and 24 frames render. All 4 font families load (Google Fonts CDN, so offline viewing falls back). I clicked through the path and checked the slider drag and arrow keys, model search, pick and effort-hide, the Always-allow second step, the steps toggle, and the link and corner tweaks. Screenshots are in `~/.hermes/profiles/pm/cache/scratch/td2/`.
- Not done (by rule): no adb/on-device view, no app code, no git changes.
- Open for the user: home A/B/C, corner scale, slider vs segmented, folding reasoning with tool calls (and single calls), hiding message counts on B.
