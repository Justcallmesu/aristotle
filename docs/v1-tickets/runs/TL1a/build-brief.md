# TL1a build brief — LAN discovery, server side (Q65)

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q49, **Q65**, `docs/protocol.md` §0, §11, §12,
`server/internal/{listen,app,tlscert,version}` and their tests.

## Do
1. Announce `_aristotle._tcp` via mDNS/DNS-SD **only on LAN listeners' interfaces** (never Tailscale, never loopback, never all
   interfaces), port = the LAN listener port. TXT records: `v=1` (discovery format), `fp=<first 16 hex of the cert fingerprint>` (a hint
   only; the phone verifies the full pin over TLS), `proto=<ProtocolVersion>`. No device ids, tokens, hostnames beyond the service
   instance name `Aristotle on <short hostname>`.
2. Library: prefer `github.com/hashicorp/mdns` or `github.com/grandcat/zeroconf` (MIT/BSD, maintained) — pick one, justify, and make it
   bind per-interface. If neither can restrict to an interface cleanly, implement a minimal responder with `golang.org/x/net/ipv4`
   multicast on the chosen interfaces only.
3. Follow listener changes: when a LAN listener is added/removed (network change), start/stop the announcement on that interface.
4. Config: `discovery.enabled` (default true) to turn it off. Log start/stop per interface.
5. Document in protocol §15 (new): service type, TXT keys, that discovery is a hint and trust = the pinned fingerprint.

## Tests
Announcement only on LAN interfaces (fake interface list), TXT content, start/stop on listener change, config off. A loopback/multicast
integration test only if it can run without root and without touching real interfaces; otherwise unit-level with a fake responder.

## Hard rules
Files: `server/**`, `docs/protocol.md` §15, `docs/v1-tickets/runs/TL1a/build-report.md`. An android worker (TS2) runs in parallel: do not
touch `android/**`. Never touch port 8760 or the demo server. No commits; never git checkout/restore/stash/reset.

## Verify
`GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1a` exits 0.

## Report
`docs/v1-tickets/runs/TL1a/build-report.md`, ≤20 lines.
