# TD2b — profile switcher redesign (Q75)

route: deep (design). Repo `/home/justcallmesu/project/personal/Aristotle`. Follow the `claude-design` skill. Read `docs/design/v2/
mockups.html` + `design-notes.md` (yours from TD2), `docs/spec.md` Q74, **Q75**, Q29-Q30 (profiles), and how the profile is used:
`ui/sessions/SessionsScreen.kt`, `SessionSheets.kt` (profile sheet), the chat top bar subtitle in `ui/chat/ChatScreen.kt`.

## The user (2026-11-04)
Picked Home **C** (no message counts), Soft corners, effort slider, "N steps" line. "the profile chip is kinda ugly i dont like it can you
redesign it too".

## Do
In `docs/design/v2/mockups.html` (keep everything else; add a section "Profile switcher" near Home C, and update Home C itself to the
recommended one), light + dark, real profile names from `~/.hermes/profiles/` (read the directory names only):
- **3 options** for showing and switching the Hermes profile on Home C, e.g. (1) a quiet monogram/avatar button in the bottom bar opening
  a sheet, (2) the profile as the screen title "pm ▾" (Claude/ChatGPT-like workspace switcher) opening a sheet, (3) a segmented/tab row
  when ≤ 3 profiles with overflow — your call, differing in composition.
- The switch sheet itself (list of profiles, current checked, live session count per profile if the data exists, otherwise none).
- How the profile shows in the chat top bar.
Recommend one. Update `design-notes.md` (Compose mapping) and remove the old chip from Home C. Also make Home C history rows without
the message count (Q75).

## Hard rules
Only `docs/design/v2/**` and `docs/v1-tickets/runs/TD2b/report.md` (≤10 lines). No app code; no git state changes; no adb.
Another worker (TR1) edits `android/**` now. Verify the HTML opens without console errors.
