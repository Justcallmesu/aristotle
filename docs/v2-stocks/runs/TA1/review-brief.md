# Review brief — v2 TA1 (analyzer server: indicators, Hermes job, verdict checks)

Fresh reviewer, deep (security-relevant: the server now starts its own Hermes sessions). Repo `/home/justcallmesu/project/personal/Aristotle`.
Read `docs/v2-stocks/spec.md` (Q96-Q103, **P5-P7**, Q17), `runs/TA0/report.md`, `runs/TA1/build-brief.md` + `build-report.md`, `docs/protocol.md`
§20.1. Hermes source (read-only) `/home/justcallmesu/.hermes/hermes-agent` to confirm every frame. Diff: `git diff HEAD -- server docs/protocol.md
CHANGELOG.md` + untracked `server/**`. PM gate `runs/pm-gate-ta1` GREEN (527 Go); PM mutations caught: tool_count guard reduced to nil-check
(1 fail), Buy order check without the target side (1 fail). Read-only: only your report; `go test`/`-race` allowed; never connect to the real
Hermes/gateway or create sessions; no listeners; no port 8760; no real state dir; no git state changes; no adb.

Check with file:line: (1) the ticket is scoped to `aristotle-stocks` only and can never reach another profile's sessions (what if the profile is
missing, renamed, or the gateway is host-scoped?); (2) no path sends `unattended`, approves anything, or answers a server request; tool_count guard
before submit; (3) prompt injection: news titles and Yahoo names go into the prompt — can a headline make the model output something the checks
accept but the user should not trust? are reasons/summary length-capped and stored as plain text?; (4) verdict checks complete (schema, tick grid,
ARA/ARB, order per verdict, held vs not held set, confidence enum, sources only from given ids), one repair turn max, no snapping; (5) indicators
maths (Wilder RSI/ATR seeding, partial today bar excluded, MA with < 50 bars, swing S/R) against hand values; (6) job runner: per-code + global
limits under -race, shutdown interrupts, state file atomic + corrupt-file behaviour, 7-day sweep deletes only our sessions; (7) logs carry no prompt/
verdict/holdings; (8) protocol §20.1 matches the code, extra result fields documented. Only BLOCKER/MAJOR trigger a fix round.
Report ≤ 30 lines at `docs/v2-stocks/runs/TA1/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
