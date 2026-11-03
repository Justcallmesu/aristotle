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
| T05c | Hardening from wave-4 reviews: D9, D10, D12, D13, D14, D15 | T04b, T05b | server, docs §11 | TODO |
| T06 | `/v1/ws` relay: device-authenticated, one ticket per connection, size/rate caps, audit log, strips system prompt (Q34) | T05c | server | TODO |
| T07 | First end-to-end: manual pairing in the app, pinned connection, read-only session list; network security config for Tailscale cleartext; `dataExtractionRules` so the token never leaves the device (T02 review) | T02b, T06 | android | TODO |
| T08 | Pairing by QR (server prints QR; app scans) | T07 | server, android | TODO |
| T09 | App connection core: one process-wide socket, reconnect, Wi-Fi vs Tailscale endpoint, "PC offline since" | T07 | android | TODO |
| T10 | Foreground service (`specialUse`) + notifications + Xiaomi battery/autostart guide | T09 | android | TODO |
| T11 | Chat view: history + live streaming + tool cards (event reducer unit-tested on frames from T01) | T09 | android | TODO ⟳ |
| T12 | Approvals and clarify prompts: in chat + notification actions, withdrawn when answered elsewhere | T10, T11 | android | TODO ⟳ |
| T13 | Composer: send; Steer / Queue / Stop while busy | T11 | android | TODO ⟳ |
| T14 | Sessions: search, pin, rename, new session; profile switch | T09 | android (+server if REST) | TODO ⟳ |
| T15 | Model + reasoning switch; slash commands | T13 | android | TODO ⟳ |
| T16 | Send pictures and files | T13 | android (+server) | TODO ⟳ |
| T17 | Subagents: view live output, stop | T11 | android | TODO ⟳ |
| T18 | Hermes cron jobs: view and control | T14 | android (+server if REST) | TODO ⟳ |
| T19 | Remote terminal: server-side PTY behind `terminal.enabled` (default off), spec Q35 | T09 | android (+server) | TODO ⟳ |
| T20 | Icon: SVG redraw, adaptive + monochrome layers, user review rounds | T02 | android, docs/assets | TODO |
| T21 | Server install: `aristotle-server install` (systemd user unit), uninstall, logs | T06 | server | TODO |
| T22 | v1 QA on the Poco + release signing | all above | all | TODO |

Frontier at approval: **T01, T02, T03** (disjoint files; may run in parallel). T20 after T02.

## Deferred review findings

| # | Finding (source) | Goes to | Status |
|---|---|---|---|
| D1 | Unmatched routes log the raw path (`httpapi.go:47`); a secret in a path would leak (T03b review) | T05 | DONE (T05) |
| D2 | `Lstat`->`Chmod` race on the state dir; open with O_NOFOLLOW and fchmod (T03b review) | T05 | DONE (T05) |
| D3 | No test for the other-owner refusal; inject file info (T03b review) | T05 | DONE (T05) |
| D4 | Request log written after the handler returns; long-lived WS logs nothing until close (T03b review) | T06 | OPEN |
| D5 | Config file containing only `null` accepted as defaults (T03b review) | T05 | DONE (T05) |
| D6 | `docs/protocol.md` §1.2: a MISSING ticket subprotocol falls through to the web gate (only ill-formed -> 4403) (T01b review) | T04 | DONE (T04) |
| D7 | Second Ctrl-C ignored during shutdown; net/http errors bypass slog (T03 review) | T21 | OPEN |
| D8 | Revisit night-theme window colours when edge-to-edge is adopted (T02b review) | T11 | OPEN |
| D9 | `Do` header strip misses non-canonical keys (`authorization`); use `delete(h, name)` (T04b review) | T05c | OPEN |
| D10 | `Do` strips a denylist; switch to an allowlist so `X-Forwarded-*` never reaches Hermes (T04b review) | T05c | OPEN |
| D11 | `"default"` profile = launch profile in Aristotle, root `~/.hermes` in Hermes; names case-sensitive (T04b review) | T14 | OPEN |
| D12 | No test for 1013/4403 arriving on `Conn.Read` after dial (T04b review) | T05c | OPEN |
| D13 | Tailscale listener accepts tailnet-range sources from any interface; bind with SO_BINDTODEVICE (T05b review, unproven) | T05c | OPEN |
| D14 | One rejection-log limiter per listener; hard-coded "5 tries" text; control lock taken after cert creation; §11 omissions (T05b review NITs) | T05c | OPEN |
| D15 | `decodeEnvelope` `sizeOK` parameter is dead (T04b review NIT) | T05c | OPEN |

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
