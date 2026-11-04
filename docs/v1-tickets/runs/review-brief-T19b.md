# Review brief — T19b (remote terminal, phone) — SECURITY

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q7c, Q51,
Q52–Q55; `docs/design.md`; `docs/protocol.md` §11.2, §12, §14; `docs/terminal.md`; `docs/v1-tickets/runs/T19/redteam-report.md` (phone
MAJORs), `runs/T19b/build-brief.md` + `build-report.md`. PM accepted the deviation (Termux emulator + renderer with an own view, no
TerminalView, no libtermux.so). Diff: `git diff HEAD -- android` + untracked android files (a T19c worker edits server/ only — ignore it).
PM gate GREEN `runs/pm-gate-36` (612 tests, APK has no libtermux); PM mutation (answer-back filter allows everything) fails 3 tests.
Edit nothing but your report; no git state changes; no gradle.

Check with evidence (file:line, and Termux 0.118.3 sources/bytecode for library claims):
1. No phone-side shell can start (no TerminalSession/JNI path reachable; the native lib absent changes nothing at runtime — no crash on a
   code path that loads it?).
2. Answer-back filter: can a malicious PC output make the emulator send keystrokes/commands back (title report, DECRQSS, OSC 52, paste
   injection without bracketed paste, OSC 8 links)? Clipboard only on user action; copies marked sensitive.
3. Keyboard: no-learning / visible-password flags really applied to the custom view's `onCreateInputConnection`; autofill + content capture
   excluded; FLAG_SECURE set on the window while the screen is shown and removed (or not leaking) after.
4. Transport: pinned WSS with the link's pin + token (no weaker TLS path), never via the relay socket; no auto-reconnect (Q55); closes on
   background / leave / network change; 4401/4403 handling hides the entry; bounded output queue (512 KiB) cannot grow; paste chunking.
5. Dependency: JitPack restricted to the Termux group (content filter in settings.gradle.kts), pinned version, licence Apache-2.0 for these
   two modules — confirm; supply-chain risk note.
6. Logging: no terminal bytes anywhere.
Only BLOCKER/MAJOR trigger a fix round. Report ≤35 lines at `docs/v1-tickets/runs/T19b/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
