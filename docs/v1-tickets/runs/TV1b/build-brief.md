# TV1b — app versioning + server compatibility warning (Q63), android side

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/spec.md` Q63, `docs/protocol.md` §0 and §11.2
(`/healthz` `protocol`, `/v1/me` `server{version,protocol,min_phone_protocol}`), `docs/v1-tickets/runs/TV1a/build-report.md`, then
`android/app/build.gradle.kts`, `data/ServerClient.kt` (and its `/v1/me` parsing), `ui/settings/*` (About section), `ui/sessions/
SessionsScreen.kt` (where a banner can go — look at how the PC-offline banner is done and copy that pattern).

## Do
1. App version: `versionName = "1.0.0"`, `versionCode = 10000` (scheme major*10000+minor*100+patch, written as a comment); expose a
   `PHONE_PROTOCOL = 1` constant (the protocol this app speaks) next to BuildConfig use.
2. Parse `server{version,protocol,min_phone_protocol}` from `/v1/me` (missing block = an old server: version unknown, protocol 1).
3. Compatibility rule (pure, tested): server.protocol < PHONE_PROTOCOL → "PC server is older than this app — update aristotle-server";
   PHONE_PROTOCOL < server.min_phone_protocol → "This app is older than the PC server — update Aristotle". Otherwise compatible.
   Incompatible: a persistent banner on the sessions screen (pattern: the PC offline banner), not a block — the app keeps working.
4. About: "Aristotle 1.0.0 (10000)", "PC server <version> · protocol <N>" (or "unknown" before first contact / old server).
5. EN + ID strings, append-only.

## Hard rules
Files: `android/app/build.gradle.kts` (version lines only), `data/ServerClient.kt` + the `/v1/me` model, a new `data/Compatibility.kt`,
`ui/settings/**` (About only), `ui/sessions/SessionsScreen.kt` (banner only), strings (append), tests,
`docs/v1-tickets/runs/TV1b/build-report.md`. Do NOT touch `ui/chat/**`, `data/chat/**` (another worker) or `server/**`. No commits;
never git checkout/restore/stash/reset. No adb.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TV1b` exits 0. Report ≤15 lines.
