# Review brief — v2 TS3 (phone: stock detail + Canvas chart)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md` (Q91, Q93, Q94, P3), `runs/TS3/build-brief.md`
+ `build-report.md`, `docs/protocol.md` §20 + `server/internal/stocks/http.go`/`yahoo.go` (real JSON, range→interval), `docs/design/v2/stocks-notes.md`
+ board. Diff: `git diff HEAD -- android CHANGELOG.md` + untracked android files (ignore `server/**`, `docs/v2-stocks/runs/TA*` — another ticket).
PM gate `docs/v2-stocks/runs/pm-gate-ts3` GREEN (1204); PM mutation: price→y axis inverted -> 2 fail. Read-only: only your report; no gradle; no git
state changes; no adb; never connect to a server.

Check with file:line: JSON fields/nullability vs the server; chart maths (flat series hi==lo division by zero, single bar, empty, NaN), tick-grid labels,
gap collapsing per session table incl. Friday, WIB without host TZ; crosshair (snap, readout, no per-frame allocation, gesture conflicts with scroll
and with the system back gesture); paths cached outside draw (keys correct so a new range/series redraws); polling rule reuse (no leak per page,
ViewModel scoped to the route); 503 retry_after handling (no tight loop); Ajaib launch (hidden when missing, no crash if uninstalled between);
holding block uses the same P/L helper as TS2; Q91 colour only on values, candles ink (Q93); TalkBack summary; strings EN/ID; the builder's notes
(bar sizes follow the server; 1D always fetched for the open; Back from All news → Home; 1.3x label overflow). Only BLOCKER/MAJOR trigger a round.
Report ≤ 25 lines at `docs/v2-stocks/runs/TS3/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
