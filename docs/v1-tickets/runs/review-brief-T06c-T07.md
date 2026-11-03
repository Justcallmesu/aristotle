# Review brief — wave: T06c (relay liveness, retry 2/2) and T07 (first app slice)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/spec.md` §2
and the decisions your ticket's brief names, your ticket's `docs/v1-tickets/runs/<T>/build-brief.md` + `build-report.md`.
PM gates GREEN (`runs/pm-gate-8`: 211 Go tests; android lint + 59 unit tests + assemble). Edit nothing but your report; no git
state changes. Severity BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT with file:line, why, fix. Report ≤45 lines at
`docs/v1-tickets/runs/<T>/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.

- **T06c** (`git diff HEAD -- server/internal/relay docs/protocol.md` + untracked relay files; this diff also contains T06b,
  never committed — review both, using `runs/T06b/review-report.md` as the previous findings). The worker uses a **10 ms write
  duration threshold** to tell a blocked write from an immediate one (no API exposes it). Judge: can it falsely keep a dead phone
  open (GC pause, CPU load, `-race`), or falsely close a slow one? Is the "last bytes already in kernel buffer" limit acceptable?
  Is a Linux-only alternative (TIOCOUTQ / SIOCOUTQ unsent bytes on the TCP socket under TLS) clearly better — recommend. Check
  the test-only hook cannot be enabled in production. Never connect to the real Hermes. You may run
  `cd server && go test -race -count=3 ./internal/relay/`.
- **T07** (`git diff HEAD -- android` + untracked android files): security first — pin check (`data/PinnedTls.kt`,
  `CertificateProbe.kt`: the probe throws after capture — can any code path send data over an unpinned connection?), hostname
  check replacement, cleartext interceptor (bypass via redirects, IPv6 forms, `ws://` upgrade, DNS names), Keystore AES-GCM
  (IV reuse, key params, what happens after an OS-level key loss), backup exclusion rules, token/code never logged. Then
  `docs/design.md` conformance (tokens only from `ui/theme`, no hard-coded colours/sizes, verdigris only for live work, no
  dynamic colour), EN/ID strings complete, protocol use vs `docs/protocol.md` §4/§11/§12 (no OkHttp pingInterval; close-code
  handling), architecture per brief item 7. Do not run gradle (PM did, ~5 min); reading code is enough.
