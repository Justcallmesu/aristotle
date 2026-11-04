# TL1c — TL1 review fix round (MAJOR-1 + minors)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TL1/review-report.md` (all findings with
file:line), `runs/TL1/build-brief.md` + `build-report.md`, `runs/TM1/report.md`, `docs/spec.md` Q65. The TL1 + TM1 code is uncommitted
in the tree; you continue it.

## Do
1. **MAJOR-1:** the manual /24 probe and every discovery `/healthz` check run on the Wi-Fi `Network` itself: bind sockets with
   `network.socketFactory` / `network.bindSocket` (the pinned OkHttp client for the check gets a per-call client with
   `.socketFactory(wifi.socketFactory)` and `.dns` resolving via the network — reuse the same TrustManager/pin, no token). Find the Wi-Fi
   `Network` via ConnectivityManager (TRANSPORT_WIFI, not a VPN). If there is no such network, or a VPN with no LAN access is the default
   and binding is impossible, do not scan: show "Can't scan this Wi-Fi" (new string). Pure gate function + tests (no Wi-Fi, VPN default,
   cellular default with a no-internet Wi-Fi → bound scan still allowed, bind failure → no scan).
2. MINOR-1: discovered (NSD) addresses must be on-link for the Wi-Fi's LinkProperties (`SubnetScan.onLink`), else dropped before any
   connection. Test.
3. MINOR-2: a found address is added to the stored list only after a relay connection through it succeeds (until then it is a one-shot
   candidate the link may try first); it never pulls a working Tailscale link over unless that candidate connects. Test.
4. MINOR-3: on API < 34, a resolve that does not complete releases the app-wide slot after ≤ 3 s (and the discovery window accounts for
   it). Test with a fake.
5. MINOR-4: the app-lock 2-minute cap also applies while the app stays open (a timer or check on the next lifecycle event), and a stuck
   `AuthBroker.busy` (FreshAuth.kt:74) is released by the same cap so Unlock always works again. Test.
6. NITs: probe only port 8760 plus at most one other port taken from the typed address; protocol §15 SDK 37 note mentions NSD resolve;
   `SessionsScreen` `pcId` has no default (callers pass it).

## Hard rules
Files: `android/**` (not `ui/theme/**`), `docs/protocol.md` §15 note, `docs/v1-tickets/runs/TL1c/build-report.md`. strings append-only.
No commits; never git checkout/restore/stash/reset. No adb. Never connect to the real server.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1c` exits 0. Report ≤20 lines.
