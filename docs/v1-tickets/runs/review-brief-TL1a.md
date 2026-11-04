# Review brief — TL1a (server mDNS announce) — SECURITY + correctness

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` hard rules (never bind on all interfaces;
server never writes Hermes files), `docs/spec.md` Q49, Q65, `docs/protocol.md` §15, `docs/v1-tickets/runs/TL1a/build-brief.md` +
`build-report.md`. Diff: `git diff HEAD -- server docs/protocol.md` + untracked `server/internal/discovery/`. Library source:
`~/go/pkg/mod/github.com/hashicorp/mdns@v1.0.7/`. PM mutation (Kind != LAN check removed) must fail tests. Edit nothing but your report;
you may run `go test`/`go vet` in `server/`; never start the server on a real listener, never touch port 8760, never run the responder on a
real interface.

Check with file:line: what the library's `net.ListenMulticastUDP` actually binds on Linux (group addr vs 0.0.0.0:5353; does it receive
and answer queries arriving on OTHER interfaces, e.g. tailscale0 or docker0?) — does that break the "never bind on all interfaces" rule or
leak the service over Tailscale; ipv6 socket opened too?; what the responder answers (only our service? does it answer arbitrary
queries or reflect — amplification?); TXT leaks (hostname, fingerprint hint length 16 hex = 64 bits — fine as a hint?); goroutine/socket
leaks on stop/restart and shutdown; errors from the library swallowed (`_ :=` on listen); coexistence with avahi on 5353; dependency
weight/licences in go.sum; tests meaningful. Only BLOCKER/MAJOR trigger a fix round. Report ≤25 lines at
`docs/v1-tickets/runs/TL1a/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
