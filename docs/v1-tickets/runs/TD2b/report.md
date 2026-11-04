# TD2b report — profile switcher (Q75)

- User changed scope mid-run: no 3 options; draw his pick: avatar top right on Home C → today's profile sheet.
- Board `docs/design/v2/mockups.html`: Home C (light + dark) gets a head row with a 40 dp Soft avatar (initial, `secondaryContainer`, 16 dp corners, no pill) plus the name under it; chip removed from the bottom bar; ledger rows one line, no message count. Group 1 says C was chosen; the clickable path starts on C.
- New section 1b: the sheet over Home C (today's content, Soft shapes, profiles = `~/.hermes/profiles/` names + `default`), a failure state, avatar specimens for every profile, chat top-bar states (running / waiting / idle / offline: 16 dp mark + name). New Tweak "Profile label": under (rec.) / beside / icon only.
- Why the label goes under the avatar: `default` and `developer` both get D; `bkm-reports-bot` ellipsizes at 72 dp; TalkBack reads the full name.
- No session count per profile: `profiles.list` returns only `last_session`, `worker_session` and `canonical_session` per profile, so the sheet shows none.
- `design-notes.md`: §5.6 Compose mapping (`ProfileAvatarButton`, `ProfileMark`, `ProfileRow` clip, chat inline mark), §4 colours, §5.1 head and ledger, §6, §9 answers, TR2 scope.
- Verified in Chromium: no console errors; avatar → sheet → pick updates the avatar; scrim closes; offline dims the avatar. Not seen on the phone (no adb).
- Open: the user should confirm the label position on the device. The roster order after the first row is a guess (alphabetical). Pre-edit copies are in the pm scratch dir.
