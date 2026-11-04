# Review brief — TY1 (Running screen), TY3 (project grouping/sorting), TY6 (Claude limits)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TX1/report.md` (the Hermes facts),
`docs/spec.md` Q81, Q82, briefs + reports in `docs/v1-tickets/runs/{TY1,TY3,TY6}/`, `docs/protocol.md` §17-§19. Hermes source
(read-only): `/home/justcallmesu/.hermes/hermes-agent`. Diff: `git diff HEAD -- android docs` + untracked. PM clean gate `runs/pm-gate-61`
GREEN (1079); PM mutations: auto projects before explicit -> 2 fail; plan % unclamped -> 1 fail. Edit nothing but your report; no gradle; no
git state changes; no adb.

Check with file:line against the Hermes source: every RPC name, params and response field the phone uses (session.active_list,
subagent.roster, projects.tree, projects.project_sessions, model.options usage windows) — any wrong name/field = MAJOR; roster id matching
(id / session_key / resolved_id); polling only while visible+online (no leak, no Home polling, badge from one read); fan-out cost and
failure isolation per profile; Desktop sort rules and project order really copied (cite the Desktop code); persistence per PC+profile and
cleared on unpair; the 300 cap; cost hidden when all 0; Claude limits parsing (percent vs fraction per Hermes inventory code), stale 15 min,
retry once; the `LocalRunningNav` approach and the AristotleApp re-indent (no behaviour change); strings EN/ID. Only BLOCKER/MAJOR trigger
a fix round. Report ≤30 lines at `docs/v1-tickets/runs/review-TY.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
