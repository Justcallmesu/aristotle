# TH1 — connection history + saved addresses (Q73), plus TL1e review minors

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q24-Q28 area (endpoints), Q65, **Q73**,
`docs/v1-tickets/decisions.md` D24/D26-D28 (merge cap 8, typed address kept), `docs/design.md`, `docs/v1-tickets/runs/TL1e/review-report.md`
(MINOR-B, MINOR-C, NITs), then `data/{PcAddresses,CredentialStore,CredentialCodec,LinkManager,EndpointChoice,LanDiscovery,
AndroidLanDiscovery,LanFlaps,NetworkWatch}.kt`, `ui/settings/{SettingsConnection,SettingsViewModel,SettingsPage,SettingsList,
SettingsScreen}.kt`, `ui/AristotleApp.kt` (PairedPage).

## Q73 (user 2026-11-04: "both")
1. **Connection history** — new `data/ConnectionLog.kt`: an append-only log on the phone, newest first in the UI, bounded to 7 days AND
   200 entries (prune on write), stored in app-private storage (no backup — follow how CredentialStore excludes backups), survives
   restarts, cleared on Unpair. Events (one line each, time + text): connected (address, Wi-Fi/Tailscale), switched (from -> to), offline
   (reason class: PC unreachable / Hermes not running / revoked / superseded …, never raw server text), found on Wi-Fi (auto or Find PC,
   address), Find PC not found / can't scan, address saved / deleted / demoted (flap). Never log tokens, fingerprints in full (8 hex max),
   session ids, message content. Write from LinkManager / discovery through one small interface (fake in tests); writes must not block the
   link (background, bounded queue).
2. **Saved addresses** — each stored address carries `source` (typed | pairing | server list | found on Wi-Fi | earlier) and
   `lastSuccessAt`. Migrate the stored format in CredentialCodec with a version bump: existing entries become `earlier`, no data lost;
   a test reads the old format. Merge rules unchanged (cap 8, typed kept, D24). Delete: per address with confirm; the LAST address can't
   be deleted (button disabled with a reason); deleting the in-use address makes the link reconnect through another (no 60 s stand-down).
   A deleted address learned from the server list may come back on the next `/v1/addresses` refresh — say so in the confirm text, unless
   it is cheap to remember deletions (then remember them per PC, bounded, cleared on unpair) — choose and report.
3. **UI** — Settings › Connection & PCs: the "Addresses" area becomes "Saved addresses" rows (address, network kind, source, "worked
   <relative time>", in-use / last-good markers, delete), and a "Connection history" row opening its own sub-page (PairedPage entry; back to
   Connection) with the log list (time, icon per kind, text), empty state, and "Clear history".
4. **TS3 leftover** — remove the repeated section header on every Settings sub-page (the top bar already has the title).

## TL1e review minors (same files)
5. MINOR-B: in unbound mode, after connect and before any TLS byte, require the socket's local address == the Wi-Fi's address; else close
   and skip that target. Test with a fake.
6. MINOR-C: re-read the VPN routes before each target (cheap) rather than once per search.
7. NIT: treat RTN_UNREACHABLE VPN routes as covering (show "Can't scan", not "Not found").

## Hard rules
Files: `android/**` (not `ui/theme/**`), `docs/protocol.md` §15 phone paragraph (MINOR-B line), `CHANGELOG.md` `[Unreleased]` (one
Phone line for the history + addresses), `docs/v1-tickets/runs/TH1/build-report.md`. strings append-only (EN + ID). No commits; never git
checkout/restore/stash/reset. No adb. Never connect to the real server.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TH1` exits 0. Report ≤20 lines.
