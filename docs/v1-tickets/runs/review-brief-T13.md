# Review brief — T13 (composer: send, Steer / Queue / Stop)

Fresh reviewer; you did not write this. This code writes into the user's live Hermes sessions. Repo
`/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q10, Q24, Q50; `docs/design.md` (composer);
`docs/protocol.md` §5 (T13 edited the `session.steer` row), §4, §12; `docs/v1-tickets/runs/T13/build-brief.md` + `build-report.md`.
Diff: `git diff HEAD -- android docs/protocol.md` + untracked android files. PM gate GREEN `runs/pm-gate-21` (307 tests); PM mutation
(drop the composer arm check) fails 3 tests. Hermes source read-only: `/home/justcallmesu/.hermes/hermes-agent/`. Edit nothing but your
report; no git state changes; no gradle.

Decide with evidence (file:line):
1. Frames: `prompt.submit {session_id,text,submission_id}`, `session.steer {session_id,execution_generation,text}`,
   `session.interrupt {session_id,execution_generation}`, `prompt.cancel` — exact vs source; a stale `execution_generation` cannot stop or
   steer the NEXT turn; Queue while busy really queues (never `busy_input_mode`, never `config.set`).
2. Never a double submit: Retry reuses `submission_id` and Hermes really dedups on it (cite, incl. after a reconnect / new socket and across
   viewers); no auto-resend; steer retry only when it surely never arrived.
3. Echo reconciliation: no duplicate bubble, no lost message (pending entry -> turn user row), Desktop-sent messages unaffected.
4. Pending-prompt behaviour: worker claims the Desktop turns Steer off and queues while a prompt waits (deviates from brief req 7 wording,
   which said "match Desktop, verify"). Verify against the Desktop/TUI source; is it safe (a queued message cannot answer the prompt)?
5. Tap-through: every send/steer/queue/stop/cancel path goes through the ViewModel arm check (monotonic clock); idle<->busy swap re-arms.
6. Drafts: local only, excluded from backup, never logged; D36 4409 handling cannot leave the link stuck offline forever.
7. design.md, EN/ID, keyboard insets in code.
Also note T12b re-review MINOR-A/B (`runs/T12b/review-report.md`) if T13 touched the same code. Only BLOCKER/MAJOR trigger a fix round.
Report ≤35 lines at `docs/v1-tickets/runs/T13/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
