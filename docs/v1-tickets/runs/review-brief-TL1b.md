# Re-review brief — TL1a + TL1b (server mDNS responder) — SECURITY

Fresh reviewer, deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` hard rules, `docs/spec.md` Q65,
`docs/v1-tickets/runs/TL1a/review-report.md` (MAJOR-1 + m1-m4), `runs/TL1b/build-brief.md` + `build-report.md`, `docs/protocol.md` §15.
Diff: `git diff HEAD -- server docs/protocol.md` + untracked `server/internal/discovery/`. PM gate `runs/pm-gate-43` (368 Go, incl. the
netns test); PM mutation (interface check off) fails 1. The previous reviewer's probe: `/home/justcallmesu/.hermes/profiles/pm/cache/
scratch/tl1a-probe/`. You may run `go test`/`go vet` and your own probe inside `unshare -rn` namespaces ONLY; never a real interface,
never port 8760, never start the server.

Verify MAJOR-1 is truly closed: re-run your own namespace probe against the NEW responder (not just its test): what `ss` shows, unicast to
LAN/Tailscale addrs, multicast arriving on a tailscale-like link with another group member joined there, off-link source on LAN, IPv6.
Then: raw socket code (`socket_linux.go`) correctness and fd leaks, SO_BINDTODEVICE vs capabilities (does it need CAP_NET_RAW? fails
silently?), the restart loop, legacy unicast reply TTL, rate limit, goodbye on shutdown, m1-m4 closed?, dnsmessage parsing of hostile
input (panics, oversized, compression loops), and that nothing else in the server binds a wildcard. Only BLOCKER/MAJOR trigger a fix
round. Report ≤25 lines at `docs/v1-tickets/runs/TL1b/review-report.md`, last line `VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
