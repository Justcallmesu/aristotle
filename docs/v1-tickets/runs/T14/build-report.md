# T14 build report — sessions: live + history, search, pin, rename, new session, profile switch; D29; D30

Gates: `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/T14` → exit 0 (gofmt/vet/test PASS, 274 Go tests; android lint+364 unit tests+assemble PASS).
Mutants killed (each fails its test): no D29 drain at ready, no watcher profile filter, no live/stored merge. Not installed; no device check; Hermes never contacted.

| UI action | Method (phone → relay → Hermes) | Source cite |
|---|---|---|
| List: Live section | `session.list {scope:"live", limit:200, profile?}` | `gateway/session_controls.py:561-607` |
| List: History + load more | `session.list {scope:"stored", limit:50→100→150→200}`; no offset exists, 200 is the cap | `session_controls.py:565-572`, `gateway/session_listing.py:27-86` |
| Open a row (live or stored) | `session.resume {session_id}` (ChatFeed, unchanged) | `session_controls.py:609`; Desktop `canonical-protocol.ts:403-406` |
| Search | none: client-side over loaded titles (WS `title` is an exact lookup; search is REST-only) | `session_controls.py:573-590`; Desktop `api/sessions.ts:439-448` |
| Rename | `session.mutate {…, expected_revision, operation:"rename", payload:{title}}`, rev 0 then one retry at `error.data.revision` | `canonical-protocol.ts:328-347,440-442`; `session_controls.py:845-849` |
| Pin | phone-local only, labelled "Pin on this phone" (list rows carry no `pinned`; Desktop pins are local + REST) | `contracts/canonical_sessions.py:171-181`; `store/session-pin-sync.ts:1-21` |
| New session | `session.create {request_id, source:"gui", profile?}` → chat opens, composer focused once | `canonical_sessions.py:21-46`; EXERCISED `c-create.jsonl` |
| Profile picker | `profiles.list {include_sessions:true}`; offers own row + rows with `last_session` (served siblings) | `gateway/session_group_controls.py:45,316-370,390-460` |
| Profile switch | every call gets `profile:<name>` (null = launch, no key); watcher `session.detach {session_id, subscription_id}` for the old profile | `session_controls.py:96-100,173-198,305-318,714-737` |

Done:
- Data: `SessionRepository` (live+stored merge by any shared id, paging, show/reload/create/rename/profiles), `SessionFrames`/`SessionLists` (pure), `ProfileLink` (adds `profile` to every call), `ProfileRoster`, `ProfileSelection` (remembered per PC), `SessionPins` (per PC+profile), `RpcError.data/reason`, `SessionSummary.resolvedId`.
- Watcher (T10): follows `ProfileSelection`; same socket + new profile → detaches the old profile's watched sessions (open chats keep theirs), emits `Unwatched` (router cancels that prompt notification), alerts only for sessions resumed in the current profile (`ResumedSessions.requestFor`), alerts/notices/intents carry the profile.
- Chats keep their profile: `ChatViewModel(profile)` wraps the link in `ProfileLink`; `ChatFeed` claims/releases/matches `srq-` by (profile, id) (T12b); `ResumedSessions` records `subscription_id`, counts chats per profile, `leave()`.
- UI (design.md §5): profile `FilterChip` (small) → `ModalBottomSheet` radio list; search bar; sections Pinned on this phone / Live / History; footer Load more / Loading / "newest 200" cap; long-press sheet (Rename, Pin on this phone); rename `AlertDialog`; extended FAB "New session" (online only). EN + ID strings; 5 Material icons.
- D29: `drainWakes()` once `gateway.ready` wins the start race (`LinkManager.attempt`); `EndpointChoice.order` = preferred kind's last-good, then overall last-good, then the rest (fallback kind's last-good first), `lastGoodByKind` stored (`last_good_kinds`); a T09 pairing without `typed` adopts its first last-good as `typed`, and per-kind last-goods are also cap-protected.
- D30: `listen.ErrorLog` (one slog bridge, Warn; `http: TLS handshake error` at Debug) used by LAN/Tailscale (`manager.go`) and loopback (`serve.go`). Note: LAN/Tailscale already bridged to slog but at Debug, so a handler panic there was dropped at the default level.
- protocol.md: §1 "Profiles from the phone" (no relay change needed; D11/D25 naming on the WS path), §4 phone's list/paging/rename/pin/create/detach + `profiles.list` row, §12 "Profiles (T14)". No server relay change.

Tests added/changed: SessionRepositoryTest (frames, merge, paging 50→200 + cap, profile switch, create, rename retry/no-retry), SessionWatcherTest (switch: detach old, keep open chat, new profile alerts with profile), SessionsViewModelTest (7), ProfileLinkTest (roster, routing, frames, selection, pins), SessionRowsTest, ResumedSessionsTest, AlertRouterTest, OpenSessionTest, EndpointChoiceTest, CredentialStoreTest (T09 pairing), LinkManagerTest (D29 race, Unconfined), listen/errorlog_test.go (3).

Open / not done:
- From source, not exercised: `profiles.list`, `scope:"stored"`, `session.mutate rename`, `session.detach`, sibling routing of `session.create`. Human check needed on the PC (stored list non-empty over the relay; rename shows on Desktop).
- History stops at 200 rows (WS has no offset; REST paging not relayed). Search covers loaded titles only.
- D29 NIT D (Go test helper matching runtime frame names) not in the brief; untouched. Server `resolveProfile` "default" semantics (D11/D25) unchanged: REST-only path, documented.
- Notification tags and drafts stay keyed by session id alone (a session id shared by two profiles would collide; not seen in practice).
