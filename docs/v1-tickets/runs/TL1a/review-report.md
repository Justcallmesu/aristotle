# TL1a review — server mDNS announce (security + correctness)

**Method.** I read the diff, the library code and Go's net code. I ran the exact `MDNSStart` call sequence (`NewMDNSService` + `NewServer{Iface}`) in an unprivileged `unshare -rn` netns with two veth pairs: `lanA` 192.168.50.10 and `tsA` 100.64.0.5 / fd7a:115c:a1e0::5. Nothing ran on a real NIC, nothing touched 8760, and the probe lives in scratch. I also ran `go vet ./...` and `go test -race` on discovery, app, listen and config: all clean. The PM mutation (`e.Kind != listen.LAN` removed, applied in a scratch copy) fails `TestOnlyLANInterfacesAreAnnounced` and `TestDiscoveryAnnouncesLANOnlyAndStopsWithServer`.

**MAJOR-1: the mDNS sockets bind the wildcard address and answer on every interface. This breaks the PROJECT.md:43 rule "never binds `0.0.0.0`/`::`", and protocol §15 and the build report describe it wrongly.**
- `mdns/server.go:67-68` calls `net.ListenMulticastUDP` for both udp4 and udp6. Go's `net/sock_posix.go:186-203` rewrites a multicast laddr to `IPv4zero`/`IPv6unspecified`, sets only `SO_REUSEADDR` (`sockopt_linux.go:31-34`), and joins the group on the one interface (`udpsock_posix.go:267-295`). `Iface` controls the group join and the outgoing interface. It does not control what the socket receives. `ss` in the netns shows `0.0.0.0:5353` and `[::]:5353`.
- Netns results with the responder started on `lanA` only: a unicast PTR query to the Tailscale address 100.64.0.5:5353 gets an answer (157 B, with `fp=`). An IPv6 unicast query to fd7a:115c:a1e0::5 gets an answer that contains the LAN A record 192.168.50.10. A multicast query arriving on `tsA` gets no answer on its own, but once any other socket joins 224.0.0.251 on `tsA` (as avahi does on multicast-capable interfaces such as docker0), it gets an answer. The cause is Linux's default `IP_MULTICAST_ALL=1`, which Go never clears. With two LAN interfaces, each responder also hears the other's network and advertises its own interface's IP there.
- Impact: the tailnet, docker networks and other interfaces learn the instance name, hostname, LAN IP, port and fp hint. Discovery was meant to be Wi-Fi only (Q65).
- Fix: do not use `mdns.NewServer`. Write a small responder on `golang.org/x/net/ipv4`, which is already in the link graph, and reuse `mdns.MDNSService.Records` for the records. It should (1) bind `224.0.0.251:5353` (the group address, not the wildcard), with `SO_REUSEADDR` so avahi can coexist, and `IP_MULTICAST_ALL=0`; (2) enable `IP_PKTINFO` and drop any packet whose ifindex is not the announced interface, or whose source is outside that interface's prefix (RFC 6762 §11); (3) send QM answers by multicast on that interface; (4) skip IPv6, or apply the same rules to `ff02::fb`. Unit-test the packet filter with fake control messages. Then correct protocol §15's statements "one multicast socket per interface" and "Never on Tailscale".

**MINOR**
- **m1 Reflection.** The responder replies to any `from` with no on-link check (`server.go:281-297`); an ephemeral-port unicast query gets a reply. Amplification is small (39 B → 157 B, about 4×) and only for our own names, since `zone.go:140-155` answers nothing else. MAJOR-1's on-link filter fixes this too.
- **m2 Swallowed listen errors.** `NewServer` ignores the listen errors (`server.go:67-68`). If IPv4 fails but IPv6 succeeds, it still reports success, so the IPv4 announcement is silently missing and `Sync` never retries it.
- **m3 Not RFC 6762 compliant.** Answers to multicast (QM) queries go unicast to the querier's `ip:5353`, there are no unsolicited announcements or goodbyes, and no probing. It also claims the A record for `<short>.local.`, which avahi already owns. Android NsdManager interop is unverified, as the build report says.
- **m4 Log spam.** A start that keeps failing logs a Warn on every 10 s scan.

**OK**
- Shutdown and restart: `Shutdown` closes both sockets and `recv` exits. After `Close` the netns shows no 5353 sockets. `Manager.Close` → `OnChange(nil)` runs after the Run loop, so nothing races it.
- TXT carries only `v`, `fp` and `proto`. A 64-bit fp hint is fine because the fingerprint is public to any TLS client and the pin stays authoritative.
- Dependencies: hashicorp/mdns (MIT), miekg/dns (BSD-3) and x/net + x/sys (BSD-3) are linked. x/mod, x/tools and x/sync appear only in go.sum.
- The tests are meaningful but never touch the real socket, which is where MAJOR-1 lives.

VERDICT: CHANGES-REQUIRED
