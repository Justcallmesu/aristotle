# Review brief — T17 (subagents), committed

Fresh reviewer. Repo `/home/justcallmesu/project/personal/Aristotle`. T17 is the commit "T17: subagents in the chat" (`git log -1 --grep T17`);
review `git show <that commit>`. A T18 worker is editing android/ in the working tree: review the COMMIT, not the tree. Read `PROJECT.md`,
`docs/design.md`, `docs/protocol.md` §2.2 and §5 "Subagent controls" (T17 added them), `docs/v1-tickets/runs/T17/build-brief.md` +
`build-report.md`. Hermes source + Desktop read-only under `/home/justcallmesu/.hermes/hermes-agent/`. PM gate GREEN `runs/pm-gate-31`
(519 tests). Edit nothing but your report; no git state changes; no gradle.
Check with file:line: every `subagent.*` event shape and `subagent.list` / `subagent.tail` / `subagent.interrupt` vs source; the worker's
claims (child session gives no live updates on /api/ws; Desktop has no stop-all; subagent finish is not a turn end); polling cost
(`subagent.tail` every 2 s per open page + `subagent.list` every 5 s — stops when the page closes, the app backgrounds, the link drops?);
interrupting the right subagent (ids can't be confused across sessions/profiles); the 600 ms arm; history parity; design.md.
Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at `docs/v1-tickets/runs/T17/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
