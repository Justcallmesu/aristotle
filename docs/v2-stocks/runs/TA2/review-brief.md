# Review brief — v2 TA2 (phone analyzer UI) + TA1b commit c1f8904 (server hardening, unreviewed)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md` (Q17, Q91, Q96-Q103, P5-P7), `docs/protocol.md`
§20.1, `runs/TA1/review-report.md` (MINOR-1 = prompt injection), `runs/TA1b/build-brief.md` + `build-report.md`, `runs/TA2/build-brief.md` +
`build-report.md`. Diffs: (a) `git show c1f8904` (TA1b, server); (b) `git diff HEAD -- android CHANGELOG.md docs/protocol.md` + untracked android files.
PM: gate `runs/pm-gate-ta2` GREEN (1228); mutations: TA1b news-only confidence cap removed -> 1 fail; TA2 VerdictCard news link without the web-scheme
check -> VerdictCardTest fails. Read-only: only your report; `go test` allowed; no gradle; no git state changes; no adb; never connect to Hermes/server.

Check with file:line: (TA1b) untrusted-data prompt wording really separates DATA from instructions; news_only rule (which verdicts, cap at med) and
its interaction with the repair turn; session id recorded before any post-create failure; sweep resilience; new codes match §20.1. (TA2) every
field/null vs server JSON incl. `news_only`, `hermes_refused`, `shutting_down`; summary/reasons plain text (no Linkify/markdown/AnnotatedString links);
only http(s) news links open; polling 3 s only while running + visible, stops on leave/done/failed, no leak; 409 shows the running job; stale-day rule
uses WIB session date; % maths (division by zero when price 0?); disclaimer always visible on a verdict; Q91 colour never the only cue; lang choice
(app language vs phone language — Q101 says the phone's language: judge whether following the app setting is acceptable); strings EN/ID.
Only BLOCKER/MAJOR trigger a round. Report ≤ 30 lines at `docs/v2-stocks/runs/TA2/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
