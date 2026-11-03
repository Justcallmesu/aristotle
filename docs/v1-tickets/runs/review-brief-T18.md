# Review brief — T18 (cron), committed

Fresh reviewer. Repo `/home/justcallmesu/project/personal/Aristotle`. Review the commit "T18: Scheduled page" (`git log -1 --grep T18`),
not the working tree (a T17b worker edits android/). Read `PROJECT.md`, `docs/design.md`, `docs/protocol.md` §7 and new §13,
`docs/v1-tickets/runs/T18/build-brief.md` + `build-report.md`. Hermes + Desktop read-only under `/home/justcallmesu/.hermes/hermes-agent/`.
PM gate GREEN `runs/pm-gate-32` (548); PM mutation (bare '30m' one-shot) fails 1. Edit nothing but your report; no git state changes;
no gradle.
Check with file:line: `cron.manage` actions and shapes vs source (incl. profile routing — does cron.manage honour the `profile` key or act
on the launch profile only? if it silently acts on the wrong profile = MAJOR); schedule strings the phone builds vs Hermes' parser (one-shot
`in 30m`, intervals, cron expr, "stop after N"); delete confirm; 600 ms arm; refresh (cron.changed only for launch profile + 20 s poll —
paused in background?); refusal display; that the worker's claim "edit/run-now/history are REST-only" is right.
Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at `docs/v1-tickets/runs/T18/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
