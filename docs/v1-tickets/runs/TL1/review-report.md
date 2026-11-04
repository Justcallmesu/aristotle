# TL1 + TM1 security review (fresh, deep; read-only, no gradle/adb)

Scope: the brief's attack list against `git diff HEAD` + untracked LanDiscovery/AndroidLanDiscovery/tests. Paths under `android/app/src/main/java/.../aristotle/`.

## Holds (verified)
- Trust: `/healthz` uses `HttpLinkDialer.clientFor` (data/ServerClient.kt:93-94, 102-103), the relay's own cached client per (fingerprint, network): `PinnedTrustManager`, `PinnedHostnameVerifier` (ignores host, re-checks key), TLS only, `NO_PROXY`, redirects off (data/HttpClients.kt:44-56). Needs `200 {"status":"ok"}`, sends no token (ServerClient.kt:131-134). Spoofed TXT/fp, captive portals, redirects and "pass once then swap" all fail: every later connect re-pins. 3 tests catch a `/healthz` skip (PM mutation).
- NSD input: IPv4 only (AndroidLanDiscovery.kt:226), then `ServerAddress.parse` + `== Lan` = RFC1918 only (no loopback, link-local, CGNAT/tailnet, public, IPv6; LanDiscovery.kt:295-296). fp must be 16-64 hex (:95). Unverified candidates never stored; one store per run (`firstOrNull`). Merge keeps typed, last-good and per-kind last-good (CredentialCodec.kt:109-115, PcAddresses.kt:43-46).
- Multicast lock: `held{}` releases on every path (LanDiscovery.kt:41-48); ref-counted with an `isHeld` guard (AndroidLanDiscovery.kt:99-105).
- NSD callbacks after cancel are no-ops (`trySend`/`close` on a closed flow, `stopServiceDiscovery` in runCatching, API 34+ callbacks unregistered in `finally`).
- API < 34 resolve: no deadlock; acquire is cancellable, permit freed on reply or after 15 s (:198-222).
- /24 probe: manual tap only (LanAutoFind calls `discover` only); needs Wi-Fi (not VPN), prefix ≥ /24, RFC1918 own address; 32 parallel, 300 ms timeouts.
- Logs: counts only, debug (LanDiscovery.kt:260, 286). No fingerprints or addresses.
- Permissions (protocol §15): correct for API 29-36; the SDK 37 / `ACCESS_LOCAL_NETWORK` note matches developer.android.com.
- TM1: `ServerVersions` keyed by PC id (Compatibility.kt:37-51); `/v1/me` records under the PC asked (ServerClient.kt:146); About and banner read the active PC; re-pair clears only the old id (PcRecords.kt:44). Both races closed; a late reply after unpair re-adds a harmless stale entry. `abandoned` counter is sound because `AuthBroker.busy` (FreshAuth.kt:74-86) allows one check at a time.

## Findings
- **MAJOR-1: the /24 probe is not confined to the Wi-Fi it scans.** `TcpPortProbe` sockets are unbound (AndroidLanDiscovery.kt:113-124) and `findOnWifi` checks neither VPN nor default network (LanDiscovery.kt:266-272). The up to 254×ports SYNs then follow the default network's routes: (i) a full-tunnel/work VPN, or a Tailscale exit node with "Allow LAN access" off, scans the remote side's same /24 — the "looks like a scan on an office network" Q65 forbids; (ii) a no-internet Wi-Fi with cellular as default (AndroidWifiWatch accepts it, :84-85) scans over cellular. Build report lists it as "Not done". Fix (small): (a) scan only when the Wi-Fi handle is the active network and `NetworkWatch.vpnUp()` is false, or (b) bind the probe socket and the hit's `/healthz` to the Wi-Fi `Network` (`bindSocket`), bind failure = no scan. Add a test for the gate.
- MINOR-1: announced hosts need not be on-link (LanDiscovery.kt:292-298). A hostile TXT can list many RFC1918 A records; the phone sends a TLS ClientHello (no token) to each, possibly over a VPN. Filter with `SubnetScan.onLink(host, wifi)`.
- MINOR-2: a TCP relay can poison the stored list. A LAN host forwarding :8760 to the real PC passes the pin, so its IP is stored first (CredentialStore.addFound). It can then tear down a working Tailscale link (`movesToFound` switches at once, LinkManager.kt:337), rotate IPs across joins/taps to evict unkept addresses (cap 8), and later serve its own cert so an unreachable PC shows "certificate changed → Pair again" instead of "unreachable" (LinkState.kt:133-137). No confidentiality loss (end-to-end pin), and an ARP spoofer already has this power. Consider storing a found address only after a relay connects through it (it becomes last-good via `remember`).
- MINOR-3 (API 29-33): one never-resolving announcement holds the process-wide permit 15 s (AndroidLanDiscovery.kt:221, 234), longer than the 10 s / 5 s windows, so the auto-find run is blocked (manual falls back to the /24). Cap the hold at ~RESOLVE_MILLIS + 1 s, or skip the remaining resolves.
- MINOR-4 (TM1): the cap is judged only on a return (AppLock.kt:60). A return within 2 min during a hung check waits for an `authEnded` that never comes (test `aCheckStillWithinTheCapKeepsHoldingTheReturnBack` encodes this), so that time away never counts while the app stays in front; judge it when the cap expires (timer or next start). Also, a stuck `AuthBroker.busy` (FreshAuth.kt:74) makes every later check, Unlock included, return Failed until the coroutine dies (pre-existing; matters more now).
- NIT: ports come from every stored LAN address (Q65 says 8760), up to 8×254 probes; §15's SDK 37 line should add that NSD resolve also needs the permission or `FLAG_SHOW_PICKER`; `SessionsScreen(pcId = "")` default silently hides the banner.

## Not verified
Device behaviour (NSD, lock, VPN routing, LNP): no adb.

VERDICT: CHANGES-REQUIRED
