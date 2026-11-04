# TL1b build report — own mDNS responder, LAN interface only (fixes TL1a MAJOR-1, m1–m4)

**Gate:** `GATE_ONLY=server bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1b` exits 0 (gofmt, vet; 368 tests passed, 0 failed). **The namespace test RAN and passed** (`TestNamespaceResponderStaysOnLAN`, about 12 s). `-race` is clean on discovery, app, listen and config. `GOOS=darwin go vet ./internal/discovery` is clean (a stub returns an error there).

**Built** (`server/internal/discovery`; hashicorp/mdns and miekg/dns are removed from go.mod/go.sum; x/net and x/sys are now direct dependencies)
- `socket_linux.go`: one IPv4 socket per interface. It is created with raw `unix.Socket`/`Bind` to **224.0.0.251:5353**, because `net.ListenPacket` silently rewrites a multicast laddr to 0.0.0.0, which my first netns run caught. Options: SO_REUSEADDR + SO_REUSEPORT, SO_BINDTODEVICE, IP_MULTICAST_ALL=0, group joined on that interface only, IP_MULTICAST_IF, TTL 255, IP_PKTINFO. Every setup failure returns a wrapped error.
- `filter.go` `zone.decide` (pure): a packet is dropped unless it arrived on our ifindex, has dst == group, comes from an on-link IPv4 source and is a standard query asking about our records. Otherwise the reply goes by multicast (QM), unicast for all-QU questions, or as a legacy unicast to an ephemeral source port (ID and questions echoed, TTL ≤ 10, no cache-flush bit).
- `zone.go`: records PTR 4500, SRV 120, TXT 4500 and A 120, with cache-flush on the unique ones. The target is `aristotle-<short>.local.` (sanitised, never `<host>.local.`). PTR answers carry SRV, TXT and A as additionals. Goodbye sends the same records with TTL 0.
- `responder.go`: announces twice, 1 s apart. On stop it sends a goodbye, after any pending announcement. Multicast answers are limited to one per record set per second. A read failure is kept in `Err()`, and `Sync` then restarts the responder with backoff.
- `backoff.go` and `Announcer`: a failed start is retried after 10 s, then the delay doubles up to 5 min. It warns once per distinct error, then logs at Debug (m2, m4). The Spec now carries address prefixes for the subnet filter.
- `docs/protocol.md` §15 is rewritten to cover the group-address bind, the per-interface filter, IPv4 only, the record/TTL table, replies, lifecycle and errors.

**Tests**
- Pure filter cases: tailscale0 arrival (also with a LAN-looking source), docker0 arrival, off-link and tailnet sources, unicast and legacy-unicast to our address, a response packet, garbage, a foreign name. Also QM, QU, mixed and legacy replies.
- Records/TTLs/goodbye/TargetHost, the backoff sequence, warn-once, dead-responder restart, and the existing start/stop-on-listener-change tests.
- Namespace test: re-execs itself under `unshare -rn` with a peer netns and two veth pairs. It checks the 2 announcements, then reads `/proc/net/udp{,6}`: exactly one 5353 socket, on 224.0.0.251, nothing on 0.0.0.0 or IPv6. With rp_filter routes in place, these get no answer: unicast and legacy unicast to the LAN address, unicast to the Tailscale address, a multicast query on tsB, an off-link source on lanB, and tsB queries from 100.64.0.9 and from a LAN-looking 192.168.50.30 while an avahi-like wildcard socket is joined on tsA. These do get an answer: a LAN multicast query (A plus fp) and a legacy query. A goodbye is seen on stop, and no socket is left afterwards. It skips with the reason when unshare, nsenter, ip or veth are unavailable.

**Mutation checks** (done in a scratch copy):
- Bind to 0.0.0.0 plus no dst check: both the netns and the unit tests fail.
- No on-link check: both fail.
- Kernel guards and the ifindex check both removed: the netns test fails.
- Either the kernel guards or the ifindex check removed alone: the netns test still passes, because the other layer covers it. The unit filter tests do catch a missing ifindex check.

**Caveats:** no probing or conflict resolution and no known-answer suppression (both noted in §15). Android NsdManager and avahi-browse interop has not been verified on a real LAN. Nothing ran on a real NIC, port 8760 was untouched, `android/**` was untouched, and nothing was committed.
