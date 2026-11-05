# Review brief — TSC3 (phone screener UI)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md` (Q85, Q86, Q106, Q111, Q112), `docs/protocol.md`
§20.2-§20.3, `server/internal/stocks/{screener,screener_settings,http}.go` (committed 8fdba61: the real JSON), `docs/design/v2/screener-notes.md` + board,
`runs/TSC3/build-brief.md` + `build-report.md`. Diff: `git diff HEAD -- android` + untracked android files. TBA1 is being built in `server/**` in
parallel — ignore server changes in the working tree; use the committed server code (`git show HEAD:<path>`).
PM: gate `runs/pm-gate-tsc3` GREEN (1299); mutation: screener polls outside scan windows -> ScreenerRefreshTest 1 of 9 fails.
Read-only: only your report; no gradle; no git state changes; no adb; never connect to a server.

Check with file:line: every field/null vs the committed server JSON (incl. `high`+`lookback`, flags, stale, running, too_soon retry); settings
validation mirrors the server ranges exactly (and null never sent); jobs-endpoint interval save; universe edit (both lists, confirm on "use bundled");
refresh rules (60 s only in a scan window + reachable + visible; 3 s follow after Scan now, stops); no leak per page; `show(...)` routing (MINOR-B)
and back stack; the `ServerClient.kt` empty-body POST change does not alter other POSTs' behaviour (auth headers, content type, analyze body);
Q91/Q106 colours with arrow/sign or label; strings EN/ID; the builder's departures (two code fields, 5-30 min, in-memory "list kept"). Only
BLOCKER/MAJOR trigger a round. Report ≤ 25 lines at `docs/v2-stocks/runs/TSC3/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
