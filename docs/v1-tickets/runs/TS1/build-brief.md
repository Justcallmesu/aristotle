# TS1 build brief — Settings screen

route: deep. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q25, Q33, Q40, Q49, **Q60**, Q61-Q64
(for placeholders only); `docs/design.md` (settings rows, sheets, type, both themes); then `ui/settings/*` (T10's settings page),
`data/{AppSettings,CredentialStore,PcCredentials,PcAddresses,ServerClient,LinkManager}.kt`, `ui/theme/*`, `ui/AristotleApp.kt`,
`background/Notifier.kt`, `res/values*/strings.xml`.

## Goal
One Settings screen, reachable from the sessions top bar gear, grouped as on Android system settings, that replaces T10's small page.

## Sections (Q60)
1. **Connection & PCs**: the paired PC (name/address, fingerprint shown grouped as on pairing), its known addresses (from PcAddresses,
   kind LAN/Tailscale, last working marked), current link state; actions: **Re-pair** (goes to the pairing screen keeping the old
   credentials until the new pairing succeeds), **Unpair** (confirm; forgets token, pin, addresses, drafts and per-PC settings; tells the
   user to also revoke on the PC with the exact command). Keep "Keep connected in background" here.
2. **Notifications**: per channel switches mirrored from the system (deep link to the channel's system settings — Android owns them),
   "Turn finished" / "Hermes needs you" description, the battery guide entry (existing).
3. **Appearance**: theme Light / Dark / System (default System); text size Small / Default / Large (scales the app's typography only,
   not the system font scale) — apply app-wide immediately, persisted in AppSettings.
4. **Language**: System / English / Bahasa Indonesia via AppCompat per-app locales (`AppCompatDelegate.setApplicationLocales`) or the
   platform `LocaleManager` on 33+ — choose the one that works without an AppCompat theme (cite); add `locales_config.xml`.
5. **Security**: a section header with a placeholder row "App lock — coming soon" (TS2 builds it). Nothing else.
6. **About**: app name, version (from BuildConfig), "What's new" placeholder (TV2), licences row placeholder (D45), source/author line.

## Rules
design.md everywhere (no new colours, verdigris only for live work); EN/ID strings; every destructive action confirms; no secrets shown
(token never; fingerprint is public). Settings live in AppSettings (DataStore/Preferences as already used) — no new storage library.

## Tests
Theme/text-size/language persistence and mapping; unpair clears everything listed (credential store, addresses, drafts, settings keys)
and re-pair keeps old credentials until success; address list rendering order; notification channel mapping.

## Hard rules
Files: `android/**`, `docs/v1-tickets/runs/TS1/build-report.md`. server/ has another worker (T19c): do not touch. No commits; never git
checkout/restore/stash/reset. Do not install on the phone.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TS1` exits 0.

## Report
`docs/v1-tickets/runs/TS1/build-report.md`, ≤30 lines.
