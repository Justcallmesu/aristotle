# Review brief — TH1 (connection history, saved addresses with stored-format migration, TL1e minors) — DATA + SECURITY

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q65, Q73, `docs/v1-tickets/decisions.md`
D24/D26-D28, `docs/v1-tickets/runs/TH1/build-brief.md` + `build-report.md`, `runs/TL1e/review-report.md` (MINOR-B/C). Diff:
`git diff HEAD -- android CHANGELOG.md docs` + untracked. PM gate `runs/pm-gate-51` GREEN (853); PM mutations: last address deletable ->
1 fail; MINOR-B local-address check off -> 1 fail. The build is installed on the user's phone (paired, real stored credentials in the OLD
format — the migration runs on real data at first launch). Edit nothing but your report; no gradle; no git state changes; no adb.

Red-team, with file:line:
- **Migration (highest stakes):** old -> format 2 on the real stored entry: token preserved byte for byte, encryption unchanged, any
  parse failure keeps the old entry (never wipes the pairing), downgrade (older APK reading format 2) — does it lose the pairing?, partial
  write / kill during migration, the remembered deletions field bounded.
- Delete: last address refused in store AND UI; deleting in-use reconnect; remembered deletions can't block the typed address or the
  only reachable address forever; cleared on unpair/re-pair.
- History log: no secrets (tokens, full fingerprints, session ids, message/server text), bounded (7 d / 200, queue 64), app-private and
  excluded from backup AND device-transfer rules, cleared on unpair, no main-thread I/O, no unbounded growth on a crash loop.
- MINOR-B/C closed; RTN_UNREACHABLE.
- UI: strings EN/ID; design.md rows; the history page under the app lock.
Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at `docs/v1-tickets/runs/TH1/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
