# Review brief — T15 (model, reasoning, slash commands) + TQ1 (quote screen, committed 2078804)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q44-Q47,
Q7d; `docs/design.md`; `docs/protocol.md` §4/§5 (T15 added rows); `docs/v1-tickets/runs/T15/build-brief.md` + `build-report.md`;
`runs/TQ1/build-brief.md` + `build-report.md`. Diff: `git diff HEAD -- android docs/protocol.md` + untracked files for T15;
`git show 2078804 -- android` for TQ1. PM gate GREEN `runs/pm-gate-27` (427 tests); PM mutation (revision may move backwards) fails 1
test. Hermes source + Desktop client read-only under `/home/justcallmesu/.hermes/hermes-agent/`. Edit nothing but your report; no git
state changes; no gradle.

T15 — decide with evidence (file:line):
1. `model.options`, `session.mutate model/reasoning` (fences, deferred-while-running, confirm token resend), `commands.catalog`,
   `slash.exec`, skill-command expansion with the phone's own submission id: exact vs source. Can `slash.exec` run while a turn is running
   do anything the Desktop forbids? Can a skill command double-submit?
2. The confirm-token "Switch anyway" path: can a stale token switch a different model than the user confirmed?
3. Hand-kept desktop-only command list: what happens for a command missing from it (Hermes refuses cleanly? or runs something harmful
   on the PC from the phone)? Severity, and is there a source-of-truth flag in `commands.catalog` the phone should use instead?
4. Not auto-retrying a conflict (Desktop retries once): fine?
TQ1 — check the worker's seven notes, especially "the quote screen doesn't show during reconnect attempts before any content" which would
contradict spec Q44 and the TQ1 report; and design.md (Literata use, radius token, theme colour, motion curve). State which are real.
Only BLOCKER/MAJOR trigger a fix round. Report ≤35 lines at `docs/v1-tickets/runs/T15/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
