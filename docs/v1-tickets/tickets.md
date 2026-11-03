# Aristotle v0 + v1 — tickets

Spec: `docs/spec.md`. Status values: TODO, READY, BUILDING, REVIEW, AWAITING-HUMAN, DONE, BLOCKED.
**Board status: APPROVED 2026-11-03 (user: "go").**

Tickets marked ⟳ are re-scoped after T01, because the spike decides which Hermes methods/REST calls they use.

## Board

| # | Title | Blocked by | Area | Status |
|---|---|---|---|---|
| T01 | v0 spike: attach to a live Desktop session from Go | — | spikes/, docs | AWAITING-HUMAN (Desktop demo) |
| T02 | Android toolchain + app shell (name, EN/ID strings, placeholder icon, installs on Poco) | — | android | DONE |
| T03 | Server skeleton + gate script | — | server, docs | DONE |
| T02b | compileSdk/targetSdk 36 + current Compose/androidx (Q36); `android.builder.sdkDownload=false`; wrapper `distributionSha256Sum`; DayNight launch theme (no dark flash) — T02 review MAJOR×2, MINOR×2 | T02 | android | DONE |
| T03b | State dir forced to 0700 (chmod or refuse if group/other readable); android gate fails when `gradlew` exists but is not executable; config keys exact-case, duplicates refused — T03 review MAJOR + MINOR×2 | T03 | server, docs | DONE |
| T01b | `docs/protocol.md`: `session.info` has two shapes (partial update vs full inventory) — clients merge present keys only; correct system_prompt frequency; document ticket params; fix 403 cite — T01 review MAJOR + MINOR/NITs | T01 | docs | DONE |
| T04 | Hermes connector package (ticket, dial, capabilities, reconnect, fail closed) + fake Hermes | T01b, T03b | server | DONE |
| T05 | Listeners + device auth + manual pairing + devices list/revoke (server) | T03b | server | DONE |
| T04b | Connector review fixes: `Do` per profile (MAJOR), no redirects, strip caller auth headers, ack timeout + buffer cap, close-code helper, Hermes-order socket discovery + owner check | T04 | server/internal/hermes | DONE |
| T05b | Listener/pairing review fixes: Tailscale by TUN interface (MAJOR, cleartext risk), LAN physical-only + subnet peers, iface names in `pair`, per-IP lockout, pair read deadline, protocol.md §11 | T05 | server, docs | DONE |
| T05c | Hardening from wave-4 reviews: D9, D10, D12, D13, D14, D15 | T04b, T05b | server, docs §11 | DONE |
| T06 | `/v1/ws` relay: device-authenticated, one ticket per connection, size/rate caps, audit log, strips system prompt (Q34) | T05c | server | DONE |
| T06b | Relay review fixes: slow phone not cut by keepalive (MAJOR), revoke on stalled phone, method-name log allowlist, §12 docs, refuse `desktop_local` (Q43) | T06 | server, docs §12 | DONE |
| T06c | Relay liveness: dead phone closed even while Hermes streams small frames (T06b review MAJOR, proven) | T06b | server/internal/relay | DONE |
| TD1 | Design pass: mockups (pair, sessions, chat live turn, approval, offline), icon concepts, draft design notes (Q37) | — | docs/design | DONE |
| T06d | Relay hardening D20 | T06c | server/internal/relay, docs §12 | DONE |
| T07b | App + connector fixes from T07 review and device test: D18 (server), D19, D21, D22 | T07 | android, server/internal/hermes | DONE |
| T07 | First end-to-end: manual pairing in the app, pinned connection, read-only session list; network security config for Tailscale cleartext; `dataExtractionRules` so the token never leaves the device (T02 review) | T02b, T06b, TD1 approved | android | DONE |
| T08 | Pairing by QR (server prints QR; app scans) | T07b | server, android | TODO |
| T09 | App connection core: one process-wide socket, reconnect, Wi-Fi vs Tailscale endpoint, "PC offline since" | T07b | android | TODO |
| T10 | Foreground service (`specialUse`) + notifications + Xiaomi battery/autostart guide | T09 | android | TODO |
| T11 | Chat view: history + live streaming + tool cards (event reducer unit-tested on frames from T01) | T09 | android | TODO ⟳ |
| T12 | Approvals and clarify prompts: in chat + notification actions, withdrawn when answered elsewhere | T10, T11 | android | TODO ⟳ |
| T13 | Composer: send; Steer / Queue / Stop while busy | T11 | android | TODO ⟳ |
| T14 | Sessions: live on top + stored history below (Q48), search, pin, rename, new session; profile switch | T09 | android (+server if REST) | TODO ⟳ |
| T15 | Model + reasoning switch; slash commands | T13 | android | TODO ⟳ |
| T16 | Send pictures and files | T13 | android (+server) | TODO ⟳ |
| T17 | Subagents: view live output, stop | T11 | android | TODO ⟳ |
| T18 | Hermes cron jobs: view and control | T14 | android (+server if REST) | TODO ⟳ |
| T19 | Remote terminal: server-side PTY behind `terminal.enabled` (default off), spec Q35 | T09 | android (+server) | TODO ⟳ |
| TQ0 | Quote research: 20-30 verified Aristotle quotes, Greek + PD English + Bekker citation, misattribution list (Q45) | — | docs | DONE |
| TQ1 | Quote loading screen (Q44-Q46): icon + quote fade-in + verdigris progress line, at start and reconnecting | T09, TQ0 approved | android | TODO |
| T20 | Icon: concept B (hand on chin) from `docs/design/icon/`, simplified to read at 48 dp; adaptive + monochrome; user approves on device (Q42) | T02 | android, docs/assets | TODO |
| T21 | Server install: `aristotle-server install` (systemd user unit), uninstall, logs | T06 | server | TODO |
| T22 | v1 QA on the Poco + release signing | all above | all | TODO |

Frontier at approval: **T01, T02, T03** (disjoint files; may run in parallel). T20 after T02.

## Deferred review findings

| # | Finding (source) | Goes to | Status |
|---|---|---|---|
| D1 | Unmatched routes log the raw path (`httpapi.go:47`); a secret in a path would leak (T03b review) | T05 | DONE (T05) |
| D2 | `Lstat`->`Chmod` race on the state dir; open with O_NOFOLLOW and fchmod (T03b review) | T05 | DONE (T05) |
| D3 | No test for the other-owner refusal; inject file info (T03b review) | T05 | DONE (T05) |
| D4 | Request log written after the handler returns; long-lived WS logs nothing until close (T03b review) | T06 | DONE (T06) |
| D5 | Config file containing only `null` accepted as defaults (T03b review) | T05 | DONE (T05) |
| D6 | `docs/protocol.md` §1.2: a MISSING ticket subprotocol falls through to the web gate (only ill-formed -> 4403) (T01b review) | T04 | DONE (T04) |
| D7 | Second Ctrl-C ignored during shutdown; net/http errors bypass slog (T03 review) | T21 | OPEN |
| D8 | Revisit night-theme window colours when edge-to-edge is adopted (T02b review) | T11 | OPEN |
| D9 | `Do` header strip misses non-canonical keys (`authorization`); use `delete(h, name)` (T04b review) | T05c | DONE (T05c) |
| D10 | `Do` strips a denylist; switch to an allowlist so `X-Forwarded-*` never reaches Hermes (T04b review) | T05c | DONE (T05c) |
| D11 | `"default"` profile = launch profile in Aristotle, root `~/.hermes` in Hermes; names case-sensitive (T04b review) | T14 | OPEN |
| D12 | No test for 1013/4403 arriving on `Conn.Read` after dial (T04b review) | T05c | DONE (T05c) |
| D13 | Tailscale listener accepts tailnet-range sources from any interface; bind with SO_BINDTODEVICE (T05b review, unproven) | T05c | DONE (T05c) |
| D14 | One rejection-log limiter per listener; hard-coded "5 tries" text; control lock taken after cert creation; §11 omissions (T05b review NITs) | T05c | DONE (T05c) |
| D15 | `decodeEnvelope` `sizeOK` parameter is dead (T04b review NIT) | T05c | DONE (T05c) |
| D16 | No positive test that the interface pin is applied (getsockopt SO_BINDTODEVICE); empty iface name silently unpins (`manager.go:220`) (T05c review F1+F2) | T06 | DONE (T06) |
| D18 | Connector looks for `gateway.sock` only in `$HERMES_HOME`; when that is a profile home (e.g. `profiles/pm`, set in any Hermes-launched shell) it reports 4503 although the root gateway runs. Fall back to the root home / follow Hermes' own resolution (found in T07 device test) | T07b | DONE |
| D19 | Default device name is the model code (`22101320G`); use the marketing name (`Settings.Global.DEVICE_NAME` / `Build.MODEL` fallback) (T07 device test) | T07b | DONE |
| D20 | Relay: detect blocked writes exactly (wrap the TLS listener, write via SyscallConn) instead of the 10 ms threshold; `TCP_NOTSENT_LOWAT` ~128 KiB on phone sockets; §12: clients must answer every ping and hand frames off fast (T06c review F1, F3, F5, F6) | T06d | DONE |
| D21 | App: Tailscale cleartext only over the VPN `Network` (refuse when Tailscale is off) + `Proxy.NO_PROXY` on every client (T07 review MAJOR, unproven) | T07b | DONE |
| D22 | App: transient Keystore decrypt error must not wipe pairing; Keystore off the main thread; `parseIpv6` never resolves DNS; move relay logic from SessionsViewModel to `data/` with tests; 3 missing security tests; 4 NITs (T07 review MINORs) | T07b | DONE |
| D23 | App: a non-Tailscale full-tunnel VPN (0.0.0.0/0) passes the tailnet route check; cleartext would go through it. Fix depends on Q49 (TLS on Tailscale) (T07b review MAJOR, unproven) | Q49 | OPEN |
| D24 | Android 10-11: transient keystore daemon failure looks like a missing key and wipes pairing (T07b review) | T09 | OPEN |
| D25 | With HERMES_HOME=profile, the profile name "default" resolves to that profile, not the root; document or fix before profile switching (T07b review) | T14 | OPEN |
| D26 | Shared OkHttp pool does not compare socket factories (safe today via forEndpoint); discover.go symlinks; duplicate served entry; "Tailscale is off" wording when VPN has no route (T07b NITs) | T09 | OPEN |
| D27 | trackedConn inherits ReadFrom/writev from embedded *net.TCPConn, bypassing drain tracking; error text; protocol.md:497 wording; long line (T06d review) | T09 | OPEN |
| D17 | No test that every listener shares the rejection-log limiter (`serve.go:112`); `Content-Length` allowlist entry is a no-op, no POST-body test through `Do`; import grouping, long doc line, §11 "pin is Linux-only" (T05c review F3-F5) | T06 | DONE (T06) |

## Tickets

### T01 — v0 spike: attach to a live Desktop session from Go
Throwaway Go CLI in `spikes/v0-attach/` that answers spec §5.
- [ ] `identify` and `session-ticket` over `~/.hermes/gateway.sock`; dials `/api/ws` with the ticket subprotocol; sends `client.capabilities {server_requests: true}`.
- [ ] Lists sessions; resumes a **test session the user opened in Desktop for this purpose**; a prompt typed on the Desktop streams into the CLI; a prompt sent from the CLI appears live on the Desktop.
- [ ] An approval raised in that session is answered from the CLI and withdrawn on the Desktop, and the reverse.
- [ ] `docs/spikes/v0-attach.md`: answers to §5 questions 1–6 with evidence (redacted frames, source cites); `docs/protocol.md`: the frames and methods v1 needs.
- Verify: the user watches the demo live (human check); PM re-reads cited source lines.
- Stop and ask if: the ticket path needs anything beyond same-user socket access, or the spike would have to touch Hermes config.

### T02 — Android toolchain + app shell
- [ ] `android/` Gradle KTS project, Compose M3, applicationId `io.github.justcallmesu.aristotle` (spec Q33), app label "Aristotle", minSdk 29, target latest stable.
- [ ] `values/strings.xml` + `values-in/strings.xml`; the phone language picks one.
- [ ] Placeholder adaptive icon; one screen.
- [ ] Builds with the gate command; installs and opens on the Poco.
- Verify: `./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug`; adb install (human check on phone).
- Stop and ask if: the installed JDK 26 is unsupported and a JDK must be installed.

### T03 — Server skeleton + gate script
- [ ] Go module `server/`, `cmd/aristotle-server` with `serve` and `version`; config in `~/.config/aristotle/`; state in `~/.local/state/aristotle/`.
- [ ] Loopback-only `GET /healthz` for now; structured logs without secrets.
- [ ] `docs/v1-tickets/gates.sh <run-dir>`: gofmt, go vet, go test (server) and the gradle gate (android, when present); writes a summary.
- Verify: `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T03`.

### T04 — Hermes connector package
- [ ] One package owns everything about Hermes: socket discovery, `identify`, ticket, dial, capabilities, reconnect with a fresh ticket, fail closed on unknown `runtime_protocol`.
- [ ] A fake Hermes (unix socket + WS) in tests covers: happy path, expired ticket, protocol mismatch, socket missing, Hermes restart.
- Verify: gates; one mutation check on the protocol-version guard.

### T05 — Listeners + device auth + manual pairing
- [ ] Binds the laptop's private Wi-Fi address (HTTPS, self-signed cert persisted, fingerprint printed) and Tailscale addresses (HTTP); refuses wildcard binds; re-binds on network change.
- [ ] `aristotle-server pair` prints address, a one-time code (short TTL, single use) and the cert fingerprint; `devices list|revoke`.
- [ ] Device tokens stored as hashes; unknown/revoked token -> 401.
- Verify: gates; tests for code expiry/reuse, revoke, wildcard refusal.

### T06 — `/v1/ws` relay
- [ ] Device-authenticated upgrade; one connector connection (fresh ticket) per phone connection; frames relayed unchanged both ways; frame size and rate caps; audit line per connection (device, duration, method counts; no prompt text).
- Verify: gates with the fake Hermes; revoke closes a live connection.

### T07 — First end-to-end slice (app)
- [ ] Manual pairing screen: address + code; shows the cert fingerprint to compare before trusting.
- [ ] Token in Android Keystore; TLS pinned to the fingerprint.
- [ ] After pairing, the app shows the PC's session list (read-only).
- Verify: gates; human check on the Poco over Wi-Fi and over Tailscale.

### T08 — Pairing by QR
- [ ] `aristotle-server pair` also prints a terminal QR holding address(es), code and fingerprint.
- [ ] The app scans it (camera permission only while scanning); the manual path stays.
- Verify: gates; human check.

### T09 — App connection core
- [ ] One socket for the whole process; reconnect with backoff; prefers Wi-Fi address when on the trusted network, else Tailscale; capability handshake on every connect.
- [ ] "PC offline since HH:MM" state; connection keyed per PC (Q25).
- Verify: gates (unit tests for the endpoint choice and reconnect policy); human check toggling Wi-Fi/Tailscale.

### T10 — Foreground service + notifications
- [ ] `specialUse` foreground service keeps the socket after the app is closed; notification channels for "turn finished" and (T12) approvals.
- [ ] First-run guide to Xiaomi battery "No restrictions" + Autostart.
- Verify: gates; human check: app closed for 8 h on the Poco, still receives a "turn finished" notification.

### T11 — Chat view ⟳
- [ ] Stored history + live streaming + tool-call cards, from a pure reducer unit-tested on recorded frames from T01.
- [ ] A turn typed on the Desktop streams on the phone at the same time.
- Verify: gates; human check side by side with the Desktop.

### T12 — Approvals and clarify ⟳
- [ ] Prompts shown in chat and as a notification with actions; answering on either screen withdraws it on the other.
- Verify: gates; human check both directions.

### T13 — Composer: send, Steer / Queue / Stop ⟳
- [ ] Idle: send. Busy: Steer, Queue, Stop buttons (Q10); never changes `busy_input_mode`.
- Verify: gates; human check during a long turn.

### T14 — Sessions and profiles ⟳
- [ ] Search, pin, rename, new session; switch between the PC's Hermes profiles (Q25).

### T15 — Model, reasoning, slash commands ⟳
### T16 — Send pictures and files ⟳
### T17 — Subagents ⟳
### T18 — Hermes cron jobs ⟳
### T19 — Remote terminal ⟳
Acceptance criteria for T14–T19 are written after T01, from `docs/protocol.md`.

### T20 — Icon
- [ ] SVG redraw of `docs/assets/icon-reference.png` (seated figure, hand on chin), readable at 48 dp; adaptive foreground/background + monochrome layer; user approves after review rounds.
- Verify: human review of renders at 48/96/192 px and on the Poco home screen.

### T21 — Server install
- [ ] `aristotle-server install|uninstall` writes/removes a systemd **user** unit; starts at login; `aristotle-server logs`.
- Verify: gates; the user runs install himself.

### T22 — v1 QA + release signing
- [ ] Checklist on the Poco: Wi-Fi, Tailscale, Desktop + phone at once, approvals both ways, 8 h background, Indonesian UI, revoke.
- [ ] Release APK signed with the user's own keystore (he keeps the keystore; never in the repo).

## Event log
- 2026-11-03 Board drafted by PM after grilling. Awaiting user approval.
- 2026-11-03 Approved. T01 (deep), T02 (std), T03 (std) dispatched in parallel; disjoint files.
- 2026-11-03 T01/T02/T03 built. PM gates GREEN (runs/pm-gate-1: gofmt, vet, 22 Go tests, android lint+3 tests+assemble). T03 mutation (loopback guard -> `if false`) fails 3 subtests, restored sha256 match. T02 note: AGP 9.4.1 auto-downloaded build-tools 36.0.0 (rule breach by tool, reported to user). Reviews dispatched (deep).
- 2026-11-03 User decided Q34 (strip system prompt), Q36 (platform 36; PM installed `platforms;android-36` via sdkmanager), Q35 (server PTY terminal). Added T02b. T01 stays open for the live Desktop co-viewer demo with the user.
- 2026-11-03 Wave-1 reviews: T01, T02, T03 all PASS-WITH-FINDINGS, no BLOCKER. Findings became T01b/T02b/T03b and notes on T07. PM redacted `credential_hint` (8 chars of an API key) in 22 places in docs/spikes/frames and in the T01 review report. Spec §2 wording fixed (server never writes Hermes files; phone-sent config.set etc. relayed per Q7d).
- 2026-11-03 T02, T03 committed and pushed by PM (user grant). T01 committed; ticket stays open only for the Desktop co-viewer demo.
- 2026-11-03 User: go. T01b, T02b, T03b dispatched in parallel (std each; disjoint files).
- 2026-11-03 T01b/T02b/T03b built. PM gates GREEN on the full tree (runs/pm-gate-2: 30 Go tests, android lint+test+assemble on SDK 36). T03b mutation (skip chmod of existing dir) fails TestEnsureDirTightensExisting; restored sha256 match. T03b's own android FAIL was a parallel-build collision, not reproduced. Reviews: T01b std, T02b std (config/resources), T03b deep.
- 2026-11-03 Wave-2 reviews: T01b, T02b, T03b PASS-WITH-FINDINGS, no BLOCKER/MAJOR. All DONE; deferred findings D1-D8 on the board.
- 2026-11-03 Wave 2 committed+pushed (ea00641..4d8207a). T04 (std) and T05 (deep, security boundary) dispatched in parallel; T04 owns internal/hermes + go.mod, T05 owns cmd/app/config/httpapi/new packages. Dependency decision: github.com/coder/websocket for the WS client (std lib has none).
- 2026-11-03 T04/T05 built. PM gates GREEN (runs/pm-gate-3: 110 Go tests + android). Mutations: T04 protocol guard -> TestUnsupportedProtocolFailsClosed fails; T05 token compare forced true -> 4 auth tests fail; both restored sha-match. T05 live run on real Wi-Fi + Tailscale: pair 201, /v1/me 200, revoke 401. Reviews dispatched (deep x2). Open: docker bridge 172.28.0.1 gets an HTTPS listener.
- 2026-11-03 Wave-3 reviews: T04 PASS-WITH-FINDINGS (MAJOR: REST `Do` bound to launch profile; unused so far) -> committed e927430 and pushed, fixes in T04b. T05 CHANGES-REQUIRED (MAJOR proven: any 100.64/10 address got a plain-HTTP 'Tailscale' listener; PM confirmed `listen/addr.go:81-97` classifies by address only) -> not committed; fix round T05b (deep, retry 1/2). Docker bridge listener folded into T05b.
- 2026-11-03 T04b/T05b built. PM gates GREEN (runs/pm-gate-4: 154 Go tests + android; control+listen -race -count=3 green). Mutations: Tailscale-by-TUN -> TestSelectTailnetRangeNeedsTailscaleTun fails; Do launch-profile ticket -> TestDoMintsTicketForTargetProfile fails; both restored sha-match. T05b also fixed a shared tls.Config data race and a flaky TestOtherUIDRefused. Reviews dispatched (deep x2).
- 2026-11-03 Wave-4 reviews: T04b and T05b PASS-WITH-FINDINGS, no BLOCKER/MAJOR; all earlier findings FIXED. Committed T04b and T05+T05b; new minors D9-D15 -> T05c (before T06), D11 -> T14.
- 2026-11-03 Wave 4 committed+pushed (8d631d3, 338cb11, 99df769). T05c (std) dispatched alone because it touches listen/app/hermes files T06 also needs. T06 brief written (deep), dispatch after T05c lands.
- 2026-11-03 T05c built. PM gates GREEN (runs/pm-gate-5: 162 Go tests + android). SO_BINDTODEVICE works unprivileged on kernel 7.1.4. Mutations: bind no-op -> only TestSetDeviceUnknownInterfaceFails fails (weak; asked reviewer); header allowlist widened -> 3 tests fail. Review dispatched (deep).
- 2026-11-03 Device check on the Poco (22101320G 'redwood', Android 14, HyperOS OS2.0): debug APK installed after the user enabled 'Install via USB'; launches in 205 ms; dark mode (phone is in night mode) renders the dynamic-colour dark theme; per-app locale id-ID shows 'Belum dipasangkan' (app locale reset afterwards). NOT checked: light-mode launch flash, launcher icon.
- 2026-11-03 T05c review PASS-WITH-FINDINGS (no BLOCKER/MAJOR; live `ss` shows %wlan0 / %tailscale0 pins incl. IPv6). Committed. F1-F5 -> D16/D17, folded into T06. T06 dispatched (deep).
- 2026-11-03 User chose design pass before T07 (Q37). TD1 dispatched (deep) in parallel with T06; disjoint files (docs/design only). Q38 colours left open for the user to pick from the board.
- 2026-11-03 T06 built. Smoke test vs real Hermes: session.list -> 3 sessions (read-only). PM gates GREEN (runs/pm-gate-6: 201 Go tests + android). Mutations: redaction fast path forced -> 5 redact tests fail; revoke hook removed -> TestRelayRevokeClosesWithinOneSecond fails; restored sha-match. Review dispatched (deep), incl. whether to strip `desktop_local: true` from phone frames.
- 2026-11-03 TD1 delivered (13 screens, 3 icon concepts, design-notes.md; slop A 0/10, B 1/10). PM checked the board in the browser (renders, no errors) and opened it on the Poco via adb reverse :8099 (local http.server, loopback only). Awaiting the user's 5 design choices.
- 2026-11-03 T06 review PASS-WITH-FINDINGS (MAJOR unproven: slow phone cut by 25s/15s keepalive on large frames). Committed a8a44e7; TD1 draft committed 6fb3f88. T06b dispatched (deep). Q43 desktop_local refuse/strip/pass -> user.
- 2026-11-03 User chose Q43 = refuse desktop_local; steered into running T06b. Design choices Q38-Q42 still open.
- 2026-11-03 User chose Q38 A (Aristotle palette only), Q39 slightly rounded, Q40 Literata+Atkinson, Q41 as drawn, Q42 icon B. TD1b: consolidate into docs/design.md (light worker).
- 2026-11-03 docs/design.md written (210 lines). PM accepted the writer's unspecified values (onSecondary, pairing-code type 22/28, reduced-motion rule, 200 ms exit) as technical calls, and its contrast fix (tertiary/error never on surfaceContainerHighest in light). User asked for an Aristotle-quote loading screen: TQ1 added, Q44-Q46 asked.
- 2026-11-03 User: Q44 a, Q45 c, Q46 a + icon. TQ0 quote research dispatched (std).
- 2026-11-03 TQ0 delivered 24 quotes (NE 12, Metaphysics 5, Poetics 3, Rhetoric 2, Politics 2) + 12 misattributions. PM spot-checked 4 random quotes against the MIT Classics texts: all 4 match verbatim. Awaiting user approval of the list.
- 2026-11-03 User approved all 24 quotes (Q47). T06b built; PM accepted its stricter desktop_local check (all phone frames, not only those containing `capabilities`, closes \u-escape bypass). Note for T07/T09: the app must not set OkHttp pingInterval during large downloads (T06b report).
- 2026-11-03 T06b review CHANGES-REQUIRED (MAJOR proven: write-start counted as progress, so a dead phone stays open while Hermes streams). Not committed; T06c dispatched (deep, relay retry 2/2). T07 dispatched in parallel (deep): android only, relay wire contract unchanged by T06c.
- 2026-11-03 T06c + T07 built. PM gates GREEN (runs/pm-gate-8: 211 Go tests; android lint + 59 unit tests + assemble). T07 mutation: pin check disabled -> 2 of 59 tests fail; restored sha-match. T07 not run on device (phone not visible to adb at the time). T06c uses a 10 ms blocked-write threshold (no API); reviewer asked to judge vs Linux SIOCOUTQ. Reviews dispatched (deep x2).
- 2026-11-03 T07 device test with the user: Wi-Fi pairing with fingerprint compare WORKED (/v1/pair 201, /v1/me 200 over pinned HTTPS). Relay gave 4503 because the PM's shell exported HERMES_HOME=profiles/pm (no gateway.sock there) -> D18. Server restarted with HERMES_HOME=~/.hermes for the demo.
- 2026-11-03 T06c review PASS-WITH-FINDINGS (dead phone closed at ping+pong 3/3; recommends exact SyscallConn detection over SIOCOUTQ) and T07 review PASS-WITH-FINDINGS (MAJOR unproven: 100.64/10 trusted by range on the phone too). Committed T06b+T06c and T07. Follow-ups T06d (D20) and T07b (D18, D19, D21, D22) before T08/T09.
- 2026-11-03 User chose Q48 (a): live + stored sessions -> T14.
- 2026-11-03 T06d + T07b built. PM gates GREEN (runs/pm-gate-9: 221 Go tests; android 88 unit tests). T07b mutation (tailnet without VPN -> system default) fails 1 test, restored sha-match. PM added the D18 profile-home note to protocol.md §1.1. Reviews dispatched (deep x2).
- 2026-11-03 T06d PASS-WITH-FINDINGS, T07b PASS-WITH-FINDINGS (MAJOR unproven: full-tunnel non-Tailscale VPN passes the route check). Both committed. D23 waits on Q49 (TLS on the Tailscale listener too); D24-D27 -> T09/T14.
