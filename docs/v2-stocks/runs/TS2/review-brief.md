# Review brief — v2 TS2 (phone: Stocks slice 1)

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v2-stocks/spec.md` (Q84-Q95, P1-P4), `runs/TS2/build-brief.md`
+ `build-report.md`, `docs/protocol.md` §20 and `server/internal/stocks/http.go` (the real JSON), `docs/design.md`, `docs/design/v2/stocks-notes.md`
(+ `stocks.html` board), `docs/design/v2/design-notes.md`. Diff: `git diff HEAD -- android docs/design.md CHANGELOG.md` + untracked android files.
Read-only: edit only your report; no gradle (the PM gate runs in parallel), no git state changes, no adb, never connect to a server.

Check with file:line: every field/endpoint the phone reads vs the server's real JSON (name, type, nullability — e.g. `spark` null) — a wrong name
or unhandled null = MAJOR; auth (device token, pinned client reused, no new TLS path); polling rule (60 s only while visible + online + market
open; no leak when leaving; Chats-only PC-card polling); money/number formatting (Rupiah ".", percent ",", sign, ID+EN), P/L preview equals the
server formula; validation vs §20; Q91 palette only on stock values, arrow + sign always, contrast; Q92 title-word switch a11y + persistence; Ajaib
`<queries>` + hidden when missing; ACTION_VIEW for news links (scheme check: only http/https); no holdings values in logs; strings EN/ID;
design.md line. Also judge the builder's note: stocks load only while the Hermes relay link is Online — is that a design flaw (stocks must work
when Hermes is down but the server is up)? Say how big the fix is. Only BLOCKER/MAJOR trigger a fix round.
Report ≤ 30 lines at `docs/v2-stocks/runs/TS2/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
