# Review brief — T14 (sessions screen, profiles, D29, D30)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q4, Q7d,
Q25, Q48; `docs/design.md`; `docs/protocol.md` §1, §4, §12 (T14 edited them); `docs/v1-tickets/runs/T14/build-brief.md` + `build-report.md`.
Diff: `git diff HEAD -- android server docs/protocol.md` + untracked files. PM gate GREEN both sides `runs/pm-gate-24` (274 Go, 364
Android); PM mutation (pinned row also in Live) fails 1 test. Hermes source read-only: `/home/justcallmesu/.hermes/hermes-agent/`.
Edit nothing but your report; no git state changes; no gradle.

Decide with evidence (file:line):
1. Every method wired from source only (`profiles.list`, `session.list` stored scope + limit growth to 200, `session.mutate` rename,
   `session.detach`, `session.create source:"gui"` with a `profile` key) matches the source exactly. Wrong shape = MAJOR.
2. Rename sends revision 0 then retries once with the revision from the conflict error: does this silently overwrite a concurrent rename
   from the Desktop? Is there a safer way (read the revision first)? Severity?
3. Profile routing: the phone adds a `profile` key per call — confirm the relay/Hermes path really scopes by it (no call accidentally goes
   to the launch profile; `ErrUnknownProfile` handled); only reachable profiles listed; switching unsubscribes the old profile's watched
   sessions except open chats; alerts/notifications for the right profile; T12b profile+session prompt matching still correct.
   Session-id collisions across profiles in notification tags/drafts: severity?
4. Live/stored merge (session moving stored->live shows once), "load more" with growing limit (duplicates? cost of re-fetching 200 rows
   on a slow link?), search local only.
5. D29 address order + drain; D30 shared ErrorLog bridge levels (no request content logged).
6. design.md, EN/ID.
Only BLOCKER/MAJOR trigger a fix round. Report ≤35 lines at `docs/v1-tickets/runs/T14/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
