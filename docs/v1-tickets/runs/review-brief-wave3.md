# Review brief — wave 3: T04 (Hermes connector), T05 (listeners, pairing, device auth)

Fresh reviewer; you did not write this. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`,
`docs/spec.md` §2 + the decisions the brief names, your ticket's `docs/v1-tickets/runs/<T>/build-brief.md` and
`build-report.md`. Diff: `git diff HEAD -- server docs/protocol.md` plus untracked files in your ticket's paths.
PM gates GREEN (`docs/v1-tickets/runs/pm-gate-3/gate-summary.txt`, 110 Go tests). PM mutations caught:
T04 protocol guard -> `TestUnsupportedProtocolFailsClosed`; T05 token compare -> 4 auth tests.
Edit nothing but your report. No git state changes. You may run `cd server && go test -race -count=1 ./...`.

Severity BLOCKER / MAJOR (proven) / MAJOR (unproven) / MINOR / NIT; file:line, why, fix. Report ≤50 lines at
`docs/v1-tickets/runs/<T>/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.

- **T04** (`server/internal/hermes/**`, `go.mod/sum`, protocol.md §1.2): does the code match `docs/protocol.md` §1/§3/§8
  and Hermes source (`/home/justcallmesu/.hermes/hermes-agent/`, read-only)? Ticket never logged; no Origin header;
  capabilities ack matching (assumes integer id 1 — robust?); buffered pre-ack frames; close codes surfaced usefully for
  T06; `Do` single-use ticket per request and request body/redirect handling; context cancellation and goroutine leaks;
  the fake Hermes faithful enough (where does it diverge from source?). Do not connect to the real Hermes.
- **T05** (all other server changes): security boundary review. Wildcard never bound; peer gate per listener
  (IPv4-mapped IPv6 peers? spoofing via X-Forwarded-For ignored?); TLS key/cert handling and the fingerprint definition
  (SPKI SHA-256) matching what an Android pinning client can compute; pairing code entropy, alphabet, single-use, lockout,
  expiry, races between concurrent pair attempts; token generation, hash storage, constant-time compare, revoke;
  control socket peer-cred check; atomic 0600 state writes; logs never contain code/token/Authorization; D1/D2/D3/D5 really
  fixed. Also assess: a docker bridge (`172.28.0.1`) gets an HTTPS listener and is printed first — recommend a fix.
