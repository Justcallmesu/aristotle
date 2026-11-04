# Aristotle — spec

Personal Android app to control the user's Hermes Agent sessions from the phone, plus personal
IDX stock features (v2). Personal use only, sideloaded.

Status: **draft for approval** (grilling finished 2026-11-03). Tickets for v0 + v1 in
`docs/v1-tickets/tickets.md`. v2 (stocks) gets its own spec pass before tickets.

## 1. Requirements

### v0 — spike (throwaway)
Prove the one risky link before any product code: a same-user Go process gets a ticket from
Hermes' control socket, opens `/api/ws`, attaches to a session the Desktop app has open, streams a
turn, and answers an approval. Also answer the spike questions in §5.

### v1 — chat and control
1. Pair the phone with the PC: QR code, or type address + code by hand (Q7b).
2. Reach the PC on home/office Wi-Fi (HTTPS, pinned certificate) and over Tailscale (Q3).
3. See and use the same live sessions as Hermes Desktop, without disturbing them (Q4).
4. Features (Q24): session list (search, pin, rename), live streaming with tool cards,
   approvals and clarify prompts, Steer / Queue / Stop when busy (Q10), model + reasoning switch,
   slash commands, send pictures/files, subagents (view, stop), Hermes cron jobs, remote terminal.
5. Switch between the PC's Hermes profiles (Q25).
6. Keep the connection and notifications alive after the app is closed (Q5), including on Xiaomi
   HyperOS (Q27).
7. UI in English and Indonesian, following the phone language (Q28).
8. App name "Aristotle", redrawn icon (Q6, Q29).
9. The PC can list and revoke paired devices (Q7c mitigation).

### v2 — IDX stocks (outline only; re-spec before tickets)
Editable job list in the server scheduler (Q20); Yahoo intraday (~10 min delay) + RSS news (Q16);
rules screener -> candidates -> Hermes (`aristotle-stocks` profile, Q22) ranks and explains (Q15);
watchlist + hand-entered holdings (Q14b); signals with entry/target/stop/confidence/sources/"data as
of" (Q14c, Q14d); 08:30 WIB brief + intraday alerts (Q18, Q21); "open in broker app"; analysis only
(Q17); catch up on missed jobs at boot (Q31).

### Out of scope
Share-to-Aristotle, voice input, multiple PCs (later), Play Store, broker integration, app lock,
public-internet exposure.

## 2. Architecture

```
Poco X5 Pro — Aristotle app (Kotlin + Compose M3, EN/ID)
  │  device token (pairing), HTTPS + pinned cert on every listener (Wi-Fi and Tailscale, Q49)
  ▼
aristotle-server (Go, systemd user service, laptop "little-boy")
  ├─ relay: phone WS <-> Hermes /api/ws (loopback, control-socket ticket per connection)
  ├─ REST: pairing, devices, what the app needs that Hermes' socket does not cover (from spike)
  └─ stocks engine (v2): scheduler, sources, SQLite, rules, alerts — separable module (Q31)
```

Rules that hold everywhere:
- Hermes stays on loopback. Only aristotle-server listens on Wi-Fi / Tailscale addresses, never `0.0.0.0`.
- Hermes credentials never leave the PC. The phone holds only its own revocable device token (stored hashed on the server).
- Frames from Hermes pass through unchanged, except the system prompt and `credential_hint` fields, which are stripped (Q34).
- aristotle-server itself never writes Hermes config or state files. Methods the phone sends (incl. `config.set`, session delete) are the user's own actions and are relayed (Q7d).
- On an unknown Hermes `runtime_protocol` the server refuses to relay and tells the phone (fail closed).

## 3. Decisions

| Id | Decision | Notes |
|---|---|---|
| Q1 | Build a new personal app from scratch, not a fork of hermes-mobile-remote. | Co-worker's app (`~/hermes-mobile-remote`, MIT) is a protocol reference only. |
| Q3 | Network: home/office Wi-Fi normally; Tailscale when away. No public internet exposure. | |
| Q4 | The phone attaches to the same live sessions as Hermes Desktop. | Rules out the OpenAI-compatible API server (:8642). |
| Q5 | A foreground service keeps the connection and notifications alive after the app is closed. | FGS type `specialUse` (sideload); `dataSync` is time-limited on Android 15 (ASSUMED). |
| Q6 | Name "Aristotle". Icon: vector redraw of the reference engraving, with a monochrome layer. | `docs/assets/icon-reference.png`. Public-domain status ASSUMED. |
| Q7 | aristotle-server (Go) is the only door: phone -> server (device token) -> loopback `/api/ws` via control-socket ticket. | Spike first (v0). |
| Q7b | Pairing: QR on the PC (address + one-time code + cert fingerprint) and a manual path (type address + code). No Tailscale-identity auto-trust. | Manual path shows the fingerprint to compare. |
| Q7c | ~~No app lock, including for approvals.~~ Superseded by Q61 (optional app lock). | Accepted risk: an unlocked phone can drive the PC agent. Mitigation: revoke from the PC. |
| Q7d | The phone may call everything the Desktop can (no method allowlist; size/rate sanity only). | |
| Q8 | Stock features are native app screens backed by the server. | v2. |
| Q9 | App: Kotlin + Jetpack Compose (Material 3). | PM technical call ("stack with the best UI"). |
| Q10 | When busy, the phone offers explicit Steer / Queue / Stop; it never changes `busy_input_mode`. | |
| Q11 | Sideloaded APK, personal use. | |
| Q12 | Own code; the co-worker's app is read only for protocol behaviour. | |
| Q13 | Market: IDX. | |
| Q14 | Day trading: screener ("good today") + favourites with buy-now / hold / when-to-sell. | |
| Q14b | Holdings entered by hand (emiten, lots, average price). | |
| Q14c | Signal = rating + entry zone, target, stop-loss, confidence, reasons with sources, data time. | |
| Q14d | ~10 min delayed free data accepted: 15m/1h setups; alerts show data time + "open in broker app". | No logged-in broker scraping. |
| Q15 | Rules narrow to candidates; Hermes ranks and explains with sources. | |
| Q16 | Free sources (Yahoo, RSS). Fragility and ToS risk accepted. Price provider behind an interface. | |
| Q17 | Analysis only; no broker integration. | |
| Q18 | 08:30 WIB brief + intraday alerts (entry/target/stop hit, breaking news on watchlist). | |
| Q20 | Scraping = editable job list in the server scheduler; Hermes only for analysis. | Interpreted from "Q18. C"; not objected to. |
| Q21 | AI runs for the brief, when a rule fires on a watchlist emiten, and on demand. | Interpreted from "Q12. A"; not objected to. |
| Q22 | Stock AI runs in a dedicated Hermes profile `aristotle-stocks`. | Spike checks how a profile is reached (§5). |
| Q23 | Phasing v0 spike -> v1 chat/control -> v2 stocks. | |
| Q24 | v1 features as §1 v1.4. Out: share-to, voice. | |
| Q25 | v1: one PC, profile switching. Connection code keyed per PC so multi-PC is additive. | |
| Q26 | Server runs as a systemd user service. | |
| Q27 | Poco X5 Pro 5G, Android 14. minSdk 29, target latest. In-app Xiaomi battery/autostart guide. | Xiaomi killing ASSUMED; verify on device. |
| Q28 | English + Indonesian from v1, following the phone language. | User chose over "English first". |
| Q29 | A worker draws the SVG icon; user reviews in rounds. | |
| Q30 | Monorepo `Aristotle/`: `server/`, `android/`, `docs/`. | |
| Q31 | PC off: accept; at boot the server catches up missed jobs (brief runs if before close, else marked missed); phone shows "PC offline since". Stocks engine stays separable for a later always-on host. | v2 behaviour; v1 shows offline state. |
| Q32 | Private GitHub repo `Justcallmesu/aristotle`. | The user creates/pushes it. |
| Q33 | Android applicationId and Kotlin root package: `io.github.justcallmesu.aristotle`. | Personal project, so no company domain. Permanent once installed. |
| Q34 | The relay strips the system prompt (and `credential_hint`) from `session.info` / resume results before sending to the phone (replaced by its length). The one exception to "frames pass unchanged". | Saves ~21 KB per full `session.info` (once per turn, plus per resume and per explicit call; `docs/protocol.md` §2.1, §10); memories/skills never stored on the phone. Found in T01. |
| Q35 | Remote terminal (T19) is a real shell on the laptop opened by aristotle-server and streamed to the phone, behind the device token. | Hermes Desktop's terminal is a Desktop-local PTY, not a Hermes feature (T01). PM adds a server setting `terminal.enabled`, default off, as a safety default; user may object. |
| Q36 | Android: keep build-tools 36.0.0 (auto-installed by AGP 9.4.1) and install platform `android-36`; compileSdk/targetSdk 36 and current Compose/androidx versions. | User approved the SDK install 2026-11-03. |
| Q37 | A design pass (TD1: clickable mockups of the key screens + icon concepts + draft design notes) happens before T07; the approved result becomes `docs/design.md`, which every UI ticket follows. | User chose (a) 2026-11-03. |
| Q38 | Colour identity: Aristotle palette only (parchment/ink, verdigris accent for live work), light + dark. No Material You / dynamic colour option. | User chose A, 2026-11-03. The T02 shell's dynamic colour is removed in T07. |
| Q39 | Buttons and controls: slightly rounded corners, not M3 pills. | User 2026-11-03. |
| Q40 | Type: Literata (wordmark, titles) + Atkinson Hyperlegible Next / Mono (UI, codes, terminal), Google downloadable fonts. | User 2026-11-03. Slashed zero accepted. |
| Q41 | Approval sheet as drawn: Deny + Allow once primary side by side; For this session + Always allow smaller below. | User 2026-11-03. |
| Q42 | Icon direction for T20: concept B, hand on chin line art. | User chose B over PM's A, 2026-11-03. TD1 rated B weak at 48 dp: T20 must simplify until it reads at 48 dp on the Poco home screen; user approves on device. |
| Q43 | The relay refuses a phone `client.capabilities` frame carrying `desktop_local` (close 1008 `desktop_local_refused`); the phone can never be treated as the local Desktop. Narrows Q7d. | User chose (a) refuse, 2026-11-03. Found in T06 review. |
| Q44 | Quote loading screen shows only while really loading: app start while connecting, and the reconnecting state; minimum display ~1.2 s so it can be read. | User 2026-11-03. |
| Q45 | Quote language: English (public-domain translation) with the original Greek in small type and a source citation (work + Bekker number). Not localised. | User 2026-11-03. Only quotes traceable to Aristotle's works; misattributions (e.g. Durant's "We are what we repeatedly do") excluded. |
| Q46 | Animation: calm line-by-line fade-in with a thin verdigris progress line, plus the app icon (static) above the quote. | User "A, and an icon too" 2026-11-03; PM reads it as the static icon, not a stroke-drawing animation. Icon = T20 result; placeholder until then. |
| Q47 | Loading-screen quotes: all 24 in `docs/quotes.json` approved as they are (period translations kept verbatim). | User 2026-11-03. |
| Q48 | Session list shows live sessions on top and stored history below (`session.list` scope live + stored), matching the Desktop sidebar. | User 2026-11-03. Built in T14. |
| Q49 | Every phone-facing listener (LAN and Tailscale) serves HTTPS with the same pinned self-signed certificate; no cleartext anywhere. The phone pins one fingerprint per PC for all its addresses. Replaces "plain HTTP inside Tailscale". | User chose (a) 2026-11-03 after the third cleartext finding (T05, T07, T07b). Resolves D23. |
| Q50 | No prompt (approval, clarify, sudo, secret) can be answered from the lock screen or a notification action; the "Hermes needs you" notification only opens the app. | User 2026-11-03 ("no approve from lock screen"). T12. |
| Q51 | The phone answers sudo password and secret requests (flexibility). Safeguards: masked field with show/hide, no autofill/suggestions, value held only in the sheet's state, sent once over the pinned TLS relay then cleared, never logged, persisted or shown in a notification; FLAG_SECURE while the sheet is open; decline button. Replaces the PM's proposed "answer on the PC only". | User 2026-11-03 ("i want sudo password and secret from phone, i want flexibility"). T12. |
| Q44b | Q44 narrowed: the quote screen covers only the first connection attempt (min ~1.2 s); later reconnects before content show the offline page with the cause and Retry. | User "do all" 2026-11-04 = recommended option A. |
| Q52 | Remote terminal (T19) is built in v1 with the red-team safeguards (`runs/T19/redteam-report.md`): terminal = full account access, documented; pair/grant/enable refused from terminal processes; lost-phone runbook. | User "do all" 2026-11-04 = A. |
| Q53 | A device needs an explicit terminal grant typed on the PC (`aristotle-server devices grant-terminal <id>`), on top of `terminal.enabled`. | User "do all" = A. |
| Q54 | The shell runs as a child of the aristotle-server service (NoNewPrivileges: no sudo/su/pkexec; private /tmp); the terminal says so. | User "do all" = A. |
| Q55 | No reattach: socket close or network switch ends the shell; idle limit 30 min counted from input; maximum session length 8 h (PM value). | User "do all" = A. |
| Q56 | Attachments: max 765 KiB per file (one relay frame); photos are downscaled to fit; bigger files refused with a clear message. | User "do all" = A. |
| Q57 | Cron edit / Run now / run history stay on the Desktop in v1 (REST-only in Hermes; no REST proxy in aristotle-server). | User "do all" = A. |
| Q58 | D40: strip the Google code scanner's datatransport/CCT telemetry components from the merged manifest; the scanner must still work on the Poco (device check), else revisit. | User "do all" = A. |
| Q59 | Chat loading shows a quote (same calm style as Q44-Q46) while a chat loads its history on first open or after a reconnect, only if loading takes > ~300 ms, then stays ≥ ~1.2 s. | User 2026-11-04. TQ2. |
| Q60 | Settings screen: Connection & PCs (paired PC, its addresses, re-pair, unpair), Notifications, Appearance (light/dark/system, text size), Language (EN/ID/system), Security, About. | User 2026-11-04. TS1. |
| Q61 | Optional app lock (replaces Q7c "no app lock"), off by default: fingerprint / device PIN (BiometricPrompt, device credential allowed) when opening Aristotle after N minutes away; when on, also required before opening the terminal and before Always allow / sudo / secret answers. | User 2026-11-04. TS2. |
| Q62 | Security setting "Show Terminal on this phone" (phone-local; hides the entry). The terminal itself is still enabled only on the PC (Q52). | User 2026-11-04. TS2. |
| Q63 | Semantic versions (1.0.0) for app and server, shown in About; the app warns when the server's protocol version is too old or too new. | User 2026-11-04. TV1. |
| Q64 | Change log: `CHANGELOG.md` in the repo (Keep a Changelog style) + an in-app "What's new" shown once after an update (and from About). | User 2026-11-04. TV2. |
| Q65 | LAN auto-discovery: aristotle-server announces itself on the Wi-Fi (mDNS/DNS-SD); the phone discovers it on a network change and connects only if the pinned certificate fingerprint matches. Fallback: a manual "Find PC on this Wi-Fi" button that probes port 8760 on the local /24 (never automatic, to avoid looking like a scan on office networks). Tailscale stays the last fallback. | User "A+B is good" 2026-11-04. TL1. |
| Q66 | (PM, technical — audit D1) Bundle Atkinson Hyperlegible Next + Mono (OFL) in the app; Literata stays downloadable. The Play-services font provider does not deliver the 2025 Atkinson families (device shows the system font). Replaces design.md "do not bundle fonts in v1". OFL notice goes in D45 licences. | PM 2026-11-04, from TU1 audit G1. |
| Q67 | (PM, technical — audit D2) Visible controls keep Material sizes (40dp buttons); the 48dp touch area comes from Material's minimum interactive size, not `heightIn(48)`. Chat rhythm: 4dp between consecutive tool/reasoning items, 12dp between a message and them, 20dp before a user bubble. | PM 2026-11-04. |
| Q68 | Chat: 3+ consecutive tool calls fold into one card "N tool calls · last: <name>" (tap to expand); long paths shortened to the part that differs. | User 2026-11-04 (audit D3). |
| Q69 | Busy composer: Stop always visible; Queue / Steer only when the field has text. | User 2026-11-04 (audit D4). |
| Q70 | Session-list top bar: wordmark · profile chip · search · ⋮ menu (Scheduled, Terminal, Settings), as the mockup. | User 2026-11-04 (audit D5). |

## 4. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Control-socket protocol is internal and may change on a Hermes update | Relay stops working | Fail closed on unknown `runtime_protocol`; phone shows "Hermes updated, update Aristotle server"; one connector module with a fake-Hermes test suite. |
| Xiaomi HyperOS kills the foreground service | No notifications, stale state | Battery/autostart guide screen; reconnect on app open; on-device check in v1 QA. |
| Unlocked phone in the wrong hands (Q7c, Q7d) | Full control of the PC agent | Device revoke from the PC; audit log of device connections. |
| Manual pairing on a hostile Wi-Fi | Impostor server | Show cert fingerprint to compare; QR is the default path. |
| Yahoo blocks or changes the chart API (v2) | No prices | Provider interface; backoff; job shows last error. |
| JDK 26 is newer than the Android Gradle Plugin supports (ASSUMED) | App does not build | T02 checks the toolchain first; install a supported JDK (user approves installs). |

## 5. Spike questions (v0 answers these)

1. Exact frames: `identify` result (`runtime_protocol`, `api_origin`, profile ids), `session-ticket` request/response, WS subprotocols.
2. How to list sessions, resume one open in the Desktop, and receive its live events; which methods the app needs.
3. Approval and clarify round trip after `client.capabilities {server_requests: true}`; withdrawal when answered on the Desktop.
4. Profiles: there is no `gateway.sock` under `~/.hermes/profiles/pm/` (VERIFIED). Does the default gateway serve all profiles (ticket `scope: host`)? How is a profile selected?
5. What session data is only available over REST (`/api/*`) vs the socket (sessions list, pin, rename, media upload, cron jobs), and which credential those REST calls accept from a same-user process.
6. Remote terminal: which methods/channel the Desktop uses.

## 6. Verified facts this spec depends on

- Attaching to a live session adds a subscriber; disconnecting one client does not end the session. Hermes docs, Programmatic Integration.
- A session is owned by one gateway process; other gateways cannot write it. Same page.
- Closing every viewer leaves the turn and its pending approval alive. Hermes docs, Gateway local sessions.
- Methods `session.steer`, `session.interrupt`, `prompt.background` exist. Hermes docs, method catalog.

### Hermes `/api/ws` authentication (source read 2026-11-03, Hermes v0.22.5 @717447e)

Paths under `/home/justcallmesu/.hermes/hermes-agent/`.

- Local clients get a single-use ticket from the owner-only control socket `$HERMES_HOME/gateway.sock`
  (verb `session-ticket`, `gateway/control_socket.py:339`), TTL 30 s (`gateway/runtime_bootstrap.py:26`),
  redeemed as a WS subprotocol on `/api/ws` (`gateway/run_api.py:428-450`, purpose `interactive`). VERIFIED.
- The socket exists for the default home: `/home/justcallmesu/.hermes/gateway.sock` (mode 0600). VERIFIED.
- A WS client must send `client.capabilities {server_requests: true}` to receive approval/clarify prompts
  (`tui_gateway/server_requests.py:31`, `:136`). VERIFIED.
- Fixed-token alternative `HERMES_DASHBOARD_SESSION_TOKEN` applies to a separate `hermes serve` (`hermes_cli/web_server_session_token.py:17`). Not used.
- Hermes' own listener binds only loopback or a wildcard (`gateway/webapp_listener.py:76-124`). ASSUMED (subagent; not re-opened).
- Scraping the token from the served page (co-worker's approach) is undocumented and fragile. Not used.

### IDX data sources (research 2026-11-03)

- Yahoo chart API (`query1.finance.yahoo.com/v8/finance/chart/BBCA.JK?interval=5m&range=1d`): 200 unauthenticated; last bar ~603 s old in session 1 (PM probe). Yahoo lists .JK as 10 min delayed. ToS forbids automated collection. VERIFIED.
- idx.co.id JSON endpoints: 403 (Cloudflare) to plain clients. VERIFIED (subagent).
- RSS 200: cnbcindonesia.com/market/rss, kontan.co.id/rss, finance.detik.com/rss, idxchannel.com/rss. Bisnis 403, Kompas 404, Emiten News 500. VERIFIED (status only).
- No free intraday foreign flow / broker summary; free versions are end of day.
- IDX hours (Kep-00003/BEI/04-2025, effective 2025-12-15): pre-open 08:45; S1 09:00-12:00 (Fri -11:30); S2 13:30-15:49:59 (Fri 14:00-); pre-close 15:50-16:01:59; post 16:02-16:15 WIB. VERIFIED (subagent). Holiday calendar not yet fetched.

### Environment (probed 2026-11-03)

- Laptop `little-boy`, Arch Linux; Go 1.26.5; OpenJDK 26.0.1; Android SDK at `~/Android/Sdk` (platform-tools present, `adb` not on PATH); `gh` and `tailscale` installed; `rtcwake` present.
