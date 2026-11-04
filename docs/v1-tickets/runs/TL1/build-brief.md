# TL1 — LAN auto-discovery, phone side (Q65)

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q49, Q65 (and D24/D26-D28 endpoint decisions in
`docs/v1-tickets/decisions.md`), `docs/protocol.md` §11 (`/healthz`, `/v1/addresses`), **§15** (the server's `_aristotle._tcp`
announcement: TXT `v`, `fp` = first 16 hex of the cert fingerprint (lowercase), `proto`; IPv4 only; LAN interfaces only), then
`data/{PcAddresses,LinkManager,NetworkWatch,ServerClient,CredentialStore,PcRecords}.kt`, the TLS pinning code (grep `fingerprint`/
`CertificatePinner`/`TrustManager`), `ui/settings/SettingsConnection.kt`, `AndroidManifest.xml`.

## Do
1. **NSD discovery** (`android.net.nsd.NsdManager`, `_aristotle._tcp`): run a short discovery (≤ 10 s) when the phone joins a Wi-Fi
   network (NetworkWatch) and the link is not already up on a working LAN address, and when "Find PC on this Wi-Fi" is tapped. For each
   resolved service: skip unless TXT `v=1` and `fp` is a prefix of the paired PC's pinned fingerprint (cheap filter, NOT trust); then
   verify over TLS with the existing pin (a `/healthz` call through the pinned client); only then add `https://<ip>:<port>` to the PC's
   addresses through the existing merge (cap 8, typed address kept, D24) and let the link use it (LAN first on Wi-Fi). Resolve on API 34+
   with `registerServiceInfoCallback`, older with `resolveService` (one at a time — the old API fails with FAILURE_ALREADY_ACTIVE
   otherwise). Multicast lock: acquire `WifiManager.MulticastLock` only during discovery, release always (finally); add
   `CHANGE_WIFI_MULTICAST_STATE` permission. No location permission needed — verify for API 29-36 and document.
2. **Manual "Find PC on this Wi-Fi"** in Settings › Connection: first NSD; if nothing within 5 s, probe the phone's current Wi-Fi /24
   (from LinkProperties; skip if prefix is shorter than /24 or the network is not Wi-Fi) on the paired PC's LAN port(s) known from its
   addresses (default 8760): TCP connect with 300 ms timeout, ≤ 32 in parallel, then the pinned `/healthz` check on hits; stop at the
   first verified PC. Never automatic (Q65). Show progress and the result ("Found at 192.168.x.y" / "Not found on this Wi-Fi — try
   Tailscale").
3. Tailscale stays the last fallback; nothing changes for the existing order except that a discovered address is a LAN candidate.
4. A wrong/unpinned PC answering is silently ignored (never shown, never stored). Log (debug) discovered/verified/rejected counts only.
5. CHANGELOG.md: move nothing; add under `[Unreleased] ### Added` one `Phone:` line for auto-find and one for the button.
6. EN + ID strings, append-only.

## Tests (pure where possible)
TXT filter (v, fp prefix case-insensitive, missing keys), /24 candidate list (own address excluded, prefix rules), probe scheduler
(parallel cap, stop at first verified, cancellation), the "when to discover" rule (Wi-Fi join + not already on a working LAN address),
multicast lock released on every path (fake), discovered address goes through the existing merge.

## Not yours
`data/Compatibility.kt`, `data/AppLock.kt`, `data/FreshAuth.kt`, `ui/sessions/CompatibilityBanner.kt`, `ui/sessions/WhatsNewCard.kt`,
`ui/settings/SettingsScreen.kt` and `WhatsNewScreen.kt` (TM1 works on them now), `server/**`. In `ServerClient.kt` add only what the
pinned `/healthz` probe needs; do not touch its `/v1/me` parsing.

## Hard rules
No commits; never git checkout/restore/stash/reset. No adb. Never connect to the real Hermes or the real server.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TL1` exits 0. Report `runs/TL1/build-report.md` ≤20 lines.
