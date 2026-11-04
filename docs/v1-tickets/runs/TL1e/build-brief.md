# TL1e — discovery must work with Tailscale on (MAJOR-2) + MINOR-A

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TL1c/review-report.md` (MAJOR-2, MINOR-A,
NITs), `runs/TL1c/build-report.md`, `runs/TL1/review-report.md` (MAJOR-1, why binding exists), `docs/spec.md` Q65, `docs/protocol.md` §15
phone paragraph. TL1 + TL1c + TM1 are uncommitted in the tree; continue them.

## Device evidence (PM, Poco X5 Pro, Android 14, TL1c build)
Phone on office Wi-Fi 192.168.1.10/24, laptop server at 192.168.1.61:8760 announcing. With the Tailscale app ON, Settings › Find PC on
this Wi-Fi shows "Can't scan this Wi-Fi". MAJOR-2 is confirmed: `Network.bindSocket` to the Wi-Fi is refused for an app under a
non-bypassable VPN (Tailscale), so the ScanGate says Unavailable and auto-find never runs either.

## Do
1. **Fallback when the bind is refused under a VPN** (pure decision function + tests): read the VPN network's LinkProperties routes
   (`ConnectivityManager.getLinkProperties(vpnNetwork).routes`, `RouteInfo.matches(InetAddress)`, `RouteInfo.type` (API 29+: only
   RTN_UNICAST counts; RTN_THROW/UNREACHABLE mean excluded), plus `excludedRoutes` handling on API 33+ if exposed). If NO VPN route
   matches the Wi-Fi subnet (check the Wi-Fi network address, its broadcast and one host in the /24 — Tailscale without an exit node
   routes only 100.64.0.0/10 and its own ranges), use UNBOUND sockets for discovery checks and the /24 probe: they go out over Wi-Fi
   because the VPN does not route them (exactly how the relay already reaches the LAN). If a VPN route covers the subnet (exit node
   without "Allow LAN access", full-tunnel VPN), keep "Can't scan this Wi-Fi" and skip auto-find. Keep the on-link filter for NSD
   addresses and the pinned check unchanged. Also before each unbound probe confirm the target is on the Wi-Fi subnet.
2. The decision is made per scan (routes change when the user toggles an exit node). Log (debug) which mode: bound / unbound-VPN-no-route /
   unavailable.
3. **MINOR-A.1:** if the trial relay fails before the 4409 close of the old relay arrives, start a new round immediately (same as the
   other order) — test both orders.
4. **MINOR-A.2:** a stored LAN address that reaches `gateway.ready` and then drops repeatedly: after 3 drops within 5 min on the same
   Wi-Fi, demote it below Tailscale for that Wi-Fi for 15 min (backoff no longer resets on each ready). Test.
5. NITs from the report if each is small (NSD tied to the Wi-Fi network if `discoverServices(..., network, ...)` exists on the API level;
   pre-34 resolve after slot release; the 20 s pending-trial network-change gap).
6. protocol §15 phone paragraph: the bound/unbound rule.

## Hard rules
Files: `android/**` (not `ui/theme/**`), `docs/protocol.md` §15 phone paragraph, `docs/v1-tickets/runs/TL1e/build-report.md`. strings
append-only. No commits; never git checkout/restore/stash/reset. No adb (the PM runs device checks). Never connect to the real server.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1e` exits 0. Report ≤20 lines.
