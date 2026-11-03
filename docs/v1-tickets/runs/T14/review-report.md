# T14 review — sessions screen, profiles, D29, D30

Scope: `git diff HEAD -- android server docs/protocol.md` plus untracked files, checked against the Hermes source (read-only). I ran `go test ./internal/listen ./internal/app` in a temp GOCACHE: ok. PM gate pm-gate-24 GREEN. No BLOCKER and no MAJOR found.

**1. Methods vs source: all shapes match.**
- `session.list`: accepted keys `{limit,title,include_hidden,scope}`, limit 1..200 (`session_controls.py:561-566`). `profile` is stripped for `_PROFILE_IMPLICIT` verbs (`:92-98`, `:313-318`) or routed to the sibling, which strips it again. Stored rows carry `id`/`session_id`/`resolved_id` (`session_listing.py:21-24`).
- `session.mutate` rename keys = `_FIELDS` (`session_mutations.py:23`, `canonical.py:91-97`). The conflict returns the fresh `revision` in `error.data` (`session_controls.py:845-849`, `:30-32`). Fence and conflict read use the same row (`hermes_state_runtime.py:520-525`); the retry has a new request_id, so it is consistent.
- `session.detach {session_id, subscription_id}` = a viewer leaving (`:714-737`). `session.create {request_id, source:"gui"}` (`canonical_sessions.py:19-21`).
- `profiles.list {include_sessions}`: `profile` allowed (`session_group_controls.py:45,56`), own row first (`:390-422`). `last_session` is always set (`:333`) and only for siblings with a reader (`:424-430`), so `ProfileRoster`'s key-presence test is correct.

**2. Rename at rev 0 + one retry — MINOR.** Yes, it silently overwrites a Desktop rename made while the phone's dialog is open (last writer wins). The Desktop refuses without a known revision (`canonical-protocol.ts:336-338`) and after a conflict only adopts the fence (`:282-296`); it auto-retries only model/reasoning (`:300-308`). No cheap way to read the revision first: list rows have none (`canonical_sessions.py:171-181`), nor does the `session.info` result (`session_local.py:447-489`); it only comes on subscription events. Every turn bumps it, so it would be stale on busy sessions anyway. Hermes designs the refusal for a retry without re-read (`session_controls.py:845-846`). The harm is limited to a title. Fix: protocol §4 should say "last writer wins, unlike the Desktop", not "as Hermes intends".

**3. Profile routing: correct.**
- Relay ticket is host-scoped (`server/internal/hermes/conn.go:41`, Hermes `chat_ws.py:676`); `_sibling_for` routes by name (`session_controls.py:173-198`). A sibling re-binds the native transport (`session_local_migration.py:19-29`), so the stored list should work there. Not exercised.
- `ErrUnknownProfile` is REST-only (`control.go:151`); on WS, `profile_mismatch`/`runtime_draining` show as ListFailed or a snackbar.
- Chat and watcher calls go through `ProfileLink(profile)`; create/rename/detach frames carry `profile`. `profiles.list` is sent bare (launch roster). `srq-` responses carry no profile; Hermes settles them across siblings (`:909-919`).
- T12b still holds: `requestFor(gen, id, profile)` in ChatFeed and the watcher, which now drops events that are not `ours()`. A switch detaches the old profile's sessions except open chats; `Unwatched` clears that profile's prompt notification.
- Session-id collisions: `--clone-all` excludes state.db (`hermes_cli/profiles.py:91-93`), so only hand-copied profiles collide. Affected: notification tags, PendingIntent requestCode, drafts, `openSession` (id only, `AristotleApp.kt` ChatRoute). NIT.

**4. Merge / paging / search: correct.** Merge on any shared id (`SessionFrames.kt:86-91`); pinned rows are excluded from Live/History, so LazyColumn keys stay unique (PM's mutant confirms). Each page refetches and replaces the whole list: no duplicates, 4 pages at most. Search is local over titles.

**5. D29 and D30: correct.** D29: `drainWakes()` once ready wins the race (`LinkManager.kt:238-243`); order = kind's last-good, then overall last-good, then the rest; T09 pairings adopt their first last-good as `typed`. D30: one bridge on all three servers (`errorlog.go`, `manager.go:159`, `serve.go:165`), panics at Warn, TLS handshake errors at Debug. net/http lines carry remote address and panic value/stack, never headers, bodies or the token.

**6. design.md and strings: conform.** FilterChip `small` → ModalBottomSheet radio list (56 dp rows), extended FAB `large`, buttons 8 dp, pin as leading icon, sheets `surfaceContainerLow`. EN/ID key parity holds (`app_name`/`pair_command` untranslated, as before).

**Findings (no fix round needed):**
- MINOR-1: rename is last-writer-wins (§2); fix the wording in protocol §4.
- MINOR-2: a failing `scope:"stored"` call fails the whole list, live rows included (`SessionRepository.kt:137-158`); before T14 live still showed. Degrade to live rows + a history error.
- MINOR-3: `sessions.changed` fires on every `message_count` move, at most every 2 s (`change_watcher.py:193-197,381,395`). Each refetches live 200 + stored up to 200 rows (≈55 KB on a slow link, plus Hermes' N+1 walk, `session_listing.py:42-86`). Refetch stored only on show/loadMore/reload or when the live ids change.
- MINOR-4: a remembered profile no longer served shows ListFailed (the chip recovers), but the watcher silently watches nothing, so no notifications at all (`resumeRunning` returns on RpcException). Fall back to the launch profile on `profile_mismatch`, or surface it.
- NIT-5: a switch during an in-flight watcher resume records no `subscription_id`, so no detach is sent; the orphan subscription lasts until the socket ends (its events are filtered).
- NIT-6: id collisions (§3). NIT-7: searching auto-pages to 200 via `LoadMoreAtTheEnd` when few matches show; untitled rows never match.
- NIT-8: copy says "laptop" (`sessions_empty_body`) but "PC" (`sessions_history_capped`). NIT-9: the kind's stale last-good still precedes the overall last-good (one timeout at most); a sibling whose store read fails is hidden from the picker (`session_group_controls.py:398-412`).

VERDICT: PASS-WITH-FINDINGS
