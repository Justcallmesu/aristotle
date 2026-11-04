# TL1b — mDNS responder that really stays on the LAN interface (TL1a MAJOR-1)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` (never bind all interfaces), `docs/spec.md` Q65,
`docs/v1-tickets/runs/TL1a/{build-report,review-report}.md` (MAJOR-1 + m1-m4 with the netns proof), the uncommitted TL1a code in
`server/internal/discovery/`, `server/internal/{app/serve.go,listen/manager.go,config}`, `docs/protocol.md` §15, and the reviewer's probe
in `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/tl1a-probe/`.

## Do
Replace the `hashicorp/mdns` server with our own small responder (drop the dependency from go.mod/go.sum if nothing else uses it; keep
`github.com/miekg/dns` only if you use it for message packing — otherwise `golang.org/x/net/dns/dnsmessage`, already in x/net):
1. **IPv4 only.** One socket per announced interface, bound to the group address `224.0.0.251:5353` (not 0.0.0.0) with SO_REUSEADDR/
   SO_REUSEPORT (avahi coexists), `IP_MULTICAST_ALL=0`, joined on that interface only, `IP_MULTICAST_IF` = that interface, TTL 255.
2. `IP_PKTINFO` (x/net/ipv4 ControlMessage): drop every packet whose arrival ifindex is not ours or whose source is not on that interface's
   subnet. Unicast queries to our addresses are never answered (the socket isn't bound to them; make sure).
3. Answer only questions for our PTR `_aristotle._tcp.local.`, our instance SRV/TXT, and the A record of the instance target — use a
   target host name we own (`aristotle-<short hostname>.local.`), never `<host>.local.` (avahi owns it). Replies by multicast (QU bit →
   unicast to the asker only if the source is on-link). Rate-limit: at most one multicast answer per record per second per interface.
4. RFC 6762 basics: announce twice on start (1 s apart), goodbye (TTL 0) on stop/shutdown, TTL 120 for host/SRV, 4500 for PTR/TXT.
5. Errors surface: socket setup failure returns an error, logged once with backoff (m2, m4 — no 10 s spam: double up to 5 min).
6. `docs/protocol.md` §15: correct the description (group-addr bind, per-interface filter, IPv4 only, the record set).

## Tests
Packet filter as a pure function (ifindex, source subnet, question match) with cases for a tailscale0 arrival, a docker0 arrival, an off-
link source, a unicast query; record building (PTR/SRV/TXT/A, TTLs, goodbye); backoff; start/stop on listener change still works. Plus an
**integration test in a user+net namespace** like the reviewer's probe, skipped (t.Skip with the reason) when `unshare -rn` is not
available: two veth-like dummy interfaces, assert `ss`-equivalent: no socket bound to 0.0.0.0:5353, a query on the "other" interface gets
no answer, a query on the LAN one does. Never run anything on the real interfaces; never touch port 8760.

## Hard rules
Files: `server/**`, `docs/protocol.md` §15, `docs/v1-tickets/runs/TL1b/build-report.md`. Do not touch `android/**` (other workers).
No commits; never git checkout/restore/stash/reset.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1b` exits 0; report whether the namespace test ran or skipped.

## Report
≤20 lines.
