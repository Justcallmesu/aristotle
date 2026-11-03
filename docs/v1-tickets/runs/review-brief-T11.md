# Review brief — T11 (chat view) + re-check of T10b

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/design.md`,
`docs/design/mockups.html` screen 3, `docs/protocol.md` §2, §2.1, §4, §12, `docs/v1-tickets/runs/T11/build-brief.md` + `build-report.md`,
`docs/v1-tickets/runs/T10b/build-brief.md` + `build-report.md`. Diff: `git diff HEAD -- android` + untracked android files; T10b is
`git show 1c9bec4 -- android/app/src/main/java/io/github/justcallmesu/aristotle/data/SessionWatcher.kt` and AlertRouter/Notifier.
PM gate GREEN (`runs/pm-gate-16`, android 210 tests). Edit nothing but your report; no git state changes; do not run gradle.
Hermes source (read-only): `/home/justcallmesu/.hermes/hermes-agent/`.

Decide with evidence:
1. **Second `session.resume` on one socket** (worker's claim): PM read `gateway/session_authority.py:355-375` — same actor reuses its
   subscription key. Confirm `event_stream.fanout.attach(transport)` is idempotent for an already-attached transport (no duplicate events)
   and that `catch_up` is skipped. If it can duplicate, MAJOR.
2. Reducer correctness on the captured frames `docs/spikes/frames/*.jsonl`: streaming deltas, tool start/complete pairing by id, cancelled
   turns, session.info shape A/B merge (no field blanked), seq de-dup across reconnect (`session.events.since` vs snapshot rebuild — can items
   duplicate or go missing at the seam?), unknown events ignored.
3. HistoryMapper vs Hermes source (`messages` shape in resume; is the running turn's user prompt included? cite).
4. Secrets: system_prompt/credential_hint never stored or rendered; Markdown renderer with untrusted text (links: does tapping open arbitrary
   URIs/intents? images: does it fetch remote images — a privacy leak of the phone IP? If so, MAJOR: must be off).
5. Performance: LazyColumn keys, deltas touch only the last item, no parsing on the main thread.
6. Refused resume after reconnect leaves the chat silently stale (worker's NOT-VERIFIED) — severity?
7. design.md conformance (verdigris only for live work; tertiary/error never on surfaceContainerHighest in light), EN/ID, D8 edge-to-edge.
8. T10b: replay_gap re-resume, silent re-post, dismiss tracking — correct and race-free vs ResumedSessions?
Only BLOCKER/MAJOR trigger a fix round. Report ≤40 lines at `docs/v1-tickets/runs/T11/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
