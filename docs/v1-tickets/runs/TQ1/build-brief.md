# TQ1 build brief — quote loading screen

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q44, Q45, Q46, Q47; `docs/design.md`
(palette, type, motion); `docs/quotes.json` + `docs/quotes.md` (24 approved quotes — use them verbatim, never edit or add); then the app's
launch path (`MainActivity.kt`, `AristotleApp.kt`, launch themes), `data/{LinkManager,LinkState}.kt`, `ui/components/OfflineBanner.kt`.

## Goal
While the app is really loading — app start until the first link state is known, and while reconnecting — the user sees one Aristotle quote,
calm and readable, instead of a blank screen or spinner.

## Requirements
1. **When (Q44)**: at app start from the system splash until the link is Online or definitively Offline/not paired; and full-screen while
   the link is Reconnecting AND no content has been shown yet in this process (once a session list/chat is on screen, reconnects keep
   using the existing banner — never cover content the user is reading). Minimum display ~1.2 s once shown (no flash), max: none, but it
   gives way immediately to the pairing screen when not paired.
2. **Content (Q45)**: English text (Literata), the original Greek below in small type, then the citation (work, book/chapter, Bekker
   number, translator + year) — fields exactly as in `quotes.json`. Not localised (same in ID). A different quote each time (random, not the
   same twice in a row; remember the last id).
3. **Motion (Q46)**: static app icon above (current launcher foreground drawable; T20 replaces it later), then the lines fade in one after
   another (calm: ~400 ms each, ease-out, staggered), a thin verdigris (`tertiary`) indeterminate progress line at the bottom. Respect the
   system "remove animations" setting (show all at once, static line).
4. Ship `quotes.json` as an app asset copied at build time from `docs/quotes.json` (one source of truth; a Gradle copy task or a
   sourceSet pointing at docs/ — no duplicate file in the repo). Parse once, off the main thread; a broken asset must never crash the app
   (fall back to a blank loading screen with the progress line).
5. Light and dark per design.md; content descriptions for accessibility (TalkBack reads the English quote and citation, not the Greek).

## Tests
Quote picker (never the same twice in a row, all 24 reachable), asset parsing (the real `docs/quotes.json` parses into 24 entries with
every field present), when-to-show state machine (start, reconnecting before/after content shown, not paired, min 1.2 s), reduced-motion.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/TQ1/build-report.md`. Do not edit `docs/quotes.*`. No commits; never git checkout/restore/
stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TQ1` exits 0.

## Report
`docs/v1-tickets/runs/TQ1/build-report.md`, ≤20 lines.
