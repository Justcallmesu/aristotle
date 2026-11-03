# TD1b report — docs/design.md

- Created `docs/design.md` (palette A only, light+dark, M3 role map with hex; no dynamic colour stated; Q39–Q42 applied).
- Sources: spec Q37–Q42, `design-notes.md`; icon-concepts.html checked for concept B. Mockups not re-skimmed in detail; notes already carry the palette A values.
- Recomputed contrast with a script. Notes' values confirmed. Added pairs the notes lacked (containers, onTertiary, onError).
- Finding: light `tertiary` and `error` on `surfaceContainerHighest` are 4.3 / 4.4 (<4.5). The notes put banners and code blocks on Highest. design.md adds a rule: use onSurface text there, tertiary/error only on surface/Low.
- Additions beyond notes: onSecondary (not in notes; chosen `#FFFFFF` light / `#2F241A` dark, uncontrasted by user — confirm), pairing-code type 22/28, reduced-motion static-bar rule, 200 ms exit duration, easing curves.
- Length: under 250 lines. No other files touched; no commits.
