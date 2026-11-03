# T07 build brief — first end-to-end slice: pair by hand, pinned connection, read-only session list

route: deep (first real app slice: TLS pinning, token storage, protocol client, design system foundation)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read in this order:
1. `PROJECT.md` (hard rules, code shape), `docs/spec.md` §1 v1, §2, decisions Q7b, Q25, Q27, Q28, Q33, Q38–Q41.
2. `docs/design.md` — BINDING. Every colour, type style, radius, spacing and component rule comes from it.
3. `docs/design/mockups.html` screens 1 (Pair) and 2 (Sessions) — the target look (palette A only; ignore palette B).
4. `docs/protocol.md` §11 (phone API: listeners, `/v1/pair`, `/v1/me`, fingerprint), §12 (relay socket, close codes, keepalive),
   §1.3 (`gateway.ready`), §3 (profiles), §4 (`session.list` shape).
5. The real app code in `android/` (T02/T02b) and `server/internal/httpapi/pair.go`, `server/internal/tlscert/` (server side of
   what you talk to).

## Goal
On the Poco, the user types the laptop address and the pairing code, compares the fingerprint, and then sees the PC's session
list, in the Aristotle design. This is the foundation later app tickets extend: design system, transport, credential store.

## Non-goals
QR scanning (T08), reconnect policy / endpoint switching / offline banner (T09), background service (T10), chat (T11), profile
switching UI (T14; show the active profile name only). No Material You.

## Requirements
1. **Design system** (`ui/theme/`): replace the T02 theme with palette A light/dark from `docs/design.md` (no dynamic colour),
   Literata + Atkinson Hyperlegible Next / Mono via Compose downloadable Google Fonts with the fallbacks design.md names, the
   type scale, shapes (slightly rounded, Q39), spacing tokens as Kotlin values. One place for tokens; screens never hard-code
   colours or sizes.
2. **Pair screen** per design.md + mockup screen 1: address field (host or `host:port`; default port 8760), 8-char code field
   (normalise like the server: case-insensitive, ignore spaces/hyphens, O→0, I/L→1), optional device name (default device
   model). Scheme rule: a Tailscale address (100.64.0.0/10 or fd7a:115c:a1e0::/48) -> `http://`; a private LAN address ->
   `https://` with pinning; anything else -> refuse with a clear message (spec §2: no public exposure).
   For HTTPS: first fetch the server certificate, show its SPKI SHA-256 fingerprint in 4-char groups (exact format in §11) and ask
   the user to compare it with what `aristotle-server pair` printed; only after "Matches" send `POST /v1/pair`. Error states:
   wrong/expired code (401, design.md wording), unreachable, fingerprint changed later (refuse, tell the user to re-pair).
   Strings EN + ID (`values-in`).
3. **Credential store** (`data/`): device id, token, base URL(s), pinned fingerprint, per PC (Q25: keyed per PC even though v1 has
   one). Token encrypted with an Android Keystore AES-GCM key; never logged. `android:allowBackup="false"` stays and add
   `dataExtractionRules` (Android 12+) + `fullBackupContent` excluding everything, so the token never leaves the device.
4. **TLS pinning**: OkHttp with a custom `X509TrustManager` that accepts exactly the pinned SPKI SHA-256 (self-signed cert, so
   normal chain validation cannot be used); hostname verification relaxed only for that pinned connection (the cert SANs are LAN
   IPs). Unit-test the pin check with a generated cert.
5. **Cleartext for Tailscale only**: Android blocks cleartext by default. Use a network security config that keeps cleartext
   disabled, and allow it only for the tailnet: if the config cannot express a CIDR, permit cleartext in the config and enforce
   "plain HTTP only to a tailnet-range IP" in code before every request — document the choice in your report.
6. **Session list**: after pairing, `GET /v1/me`, then open `/v1/ws` (Bearer header; OkHttp WebSocket; NO `pingInterval` — the
   server pings and large downloads must not be cut, see §12), wait for `gateway.ready`, call `session.list {limit: 50}` and show
   the sessions per design.md "session row" (title, relative last activity, running indicator in verdigris only for `running`).
   Show the active profile name (launch profile) as text. Handle the §12 close codes for this screen with design.md's
   connection-page wording: 4503 Hermes not running, 4505 Hermes updated, 4401 revoked (back to pairing, credentials wiped),
   others -> generic error with retry. No automatic reconnect loop (T09).
7. **Architecture**: one `MainActivity`; screens are Composables; state in ViewModels (`StateFlow`); transport in `data/`
   (`ServerClient` for REST, `RelaySocket` for WS, `CredentialStore`); JSON with kotlinx.serialization; pure functions
   (code normalisation, scheme choice, fingerprint formatting, session-row formatting) in plain Kotlin with JUnit tests.
   Coroutines; no DI framework. Small named functions.
8. Library choices (PM technical decision): OkHttp 5 (or latest stable 4.x if 5 is not stable), kotlinx-serialization-json,
   lifecycle-viewmodel-compose, androidx.compose.ui:ui-text-google-fonts. Add via the version catalog; no others without a reason
   in the report.

## Hard rules
- Files you may edit/create: `android/**`, `docs/v1-tickets/runs/T07/build-report.md`. Not `server/**` (T06c edits the relay in
  parallel), not other docs.
- No SDK package installs (`sdkDownload=false` stays). No commits. Never log tokens, codes or the Authorization header.
- You may install the debug APK on the attached Poco (`~/Android/Sdk/platform-tools/adb install -r …`) and take screenshots
  (`adb exec-out screencap -p`) to check the design. Do not change phone settings.
- Manual end-to-end (optional, if you get that far): run `server` from a temp XDG dir on port 8760
  (`cd server && XDG_STATE_HOME=… XDG_CONFIG_HOME=… go run ./cmd/aristotle-server serve`), `pair --name poco`, enter it on the phone
  via `adb shell input text` over the Tailscale address, screenshot the session list. Stop the server afterwards. The PM will
  repeat this with the user.

## Verify
`cd android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug` exit 0, lint 0 errors, no new suppressions.

## Stop and report if
design.md and the mockup disagree on something you need (say which, use design.md); or a requirement weakens a spec §2 rule.

## Report
`docs/v1-tickets/runs/T07/build-report.md`, ≤50 lines: files; libraries + versions; the cleartext decision; tests by name;
commands + exit codes; screenshots taken (paths under the scratch dir); NOT-VERIFIED list.
