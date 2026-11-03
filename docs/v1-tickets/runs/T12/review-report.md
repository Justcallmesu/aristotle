# T12 review (+ T11b re-check) — fresh reviewer

Scope: brief, build brief/report, spec Q50/Q51, the T12 diff incl. untracked files, `c2b07ff` (seam/LinkManager), Hermes source, `server/internal/relay/`, material3 1.4.0 bytecode. No gradle; PM gate-18 `PASS android`.

**1. Frame shapes — PASS.** `approval.respond`/`clarify.respond` carry exactly the 4 keys Hermes checks (`Prompts.kt:196-227` vs `gateway/session_controls.py:925-935`), and choice ∈ offered `choices` (`Prompts.kt:197`, `session_pending_controls.py:22-34,95`).
Batch: `clarify.lock {request_id, question_id, answer|null}` per open qid (`Prompts.kt:235-249`; `session_clarify_lock.py:15-38`, `prompt_voice.py:265-285`, Desktop `clarify.ts:325-333`). Multi-select JSON array accepted (`clarify_tool.py:37-47`).
sudo/secret `{"jsonrpc","id","result":{"value"}}`, read at `session_server_requests.py:78-97`. `""` is a real skip: sudo `_sudo_cb() or ""` gives no `-S` rewrite and runs the command unchanged (`tools/terminal_tool_sudo.py:199,495-501`, so no empty password reaches sudo). Secret: `if not value: _skipped` (`session_secret_prompt.py:45-46`).
An empty field is never sent as an answer (`Prompts.kt:258`). Vault method names match `session_vault_prompt.py:57-69`.

**2. §6 correction — CORRECT.** The owner's `dispatch` routes ANY srq response to `_resolve_response` (`session_controls.py:283-289,909-923`). `resolve` pops it for all viewers; an `error` (4404 too) means value `""` (`session_server_requests.py:78-97`), then `request.cancel resolved` goes to everyone (`:203-208`).
4404 decline counting is sidecar-only (`tui_gateway/server_requests.py:249-278`). Ignoring window bridges is right: they are `replay=False` with 10-45 s deadlines (`gateway/local_desktop_ui.py:40-45,210-219`), and a phone 4404 would empty the Desktop's read. §12 (`protocol.md:497-500`) now contradicts §6. Replace that bullet with:
> Hermes writes `srq-` requests to every attached viewer (§6). The phone answers only `sudo`/`secret` (`result.value`, `""` = skip) and batch `clarify` (via `clarify.lock`). It never answers or declines window bridges or `vault.*`: on the owner, any response (`error`/`4404` included) settles the request for every viewer. Unanswered requests wait for another viewer or their deadline. The owner's fanout ignores the per-socket `server_requests` flag (`tui_gateway/transport.py:209-211`, `session_server_requests.py:171-178`), so `server_requests:false` does not fail them early (sidecar-only).

**3. Q51 — PASS.** The value lives only in a `remember`ed `SecretField`, and `take()` clears it on send, cancel and dispose (`PromptSheet.kt:347-350`). Redacted `toString` on `SecretField`, `PromptAnswer.Value` and `PromptReply.Response`. Never in `ChatUiState`.
Logs are class names only (`AppContainer.kt:70`, `BackgroundController.kt:73`, `LinkService.kt:49`). No OkHttp logging interceptor, no crash handler. The notification text is generic, `VISIBILITY_PRIVATE`.
Sheet window `SecureOn` (`PromptSheet.kt:107`): material3 1.4.0 `updateParameters` → `setSecurePolicy` → `Window.setFlags(FLAG_SECURE)` runs at construction and on every SideEffect, so an in-place switch to a secret prompt is covered too. Activity flag: `:386-391`.
EditText: `IME_FLAG_NO_PERSONALIZED_LEARNING`, `NO_SUGGESTIONS`, autofill excluded, `isSaveEnabled=false` (`:408-426`). The relay logs counts plus allow-listed method names, never frame content (`session.go:244-259`, `phoneframe.go:10`).

**4. Q50 — PASS.** No notification actions (`Notifier.kt:93-99`). The content intent only opens the chat. MainActivity has no `showWhenLocked`, so the keyguard is dismissed before the sheet shows. An external `OPEN_SESSION` cannot answer (see MINOR-2).
**5. Always/stale — PASS** except MAJOR-1. Always needs `confirmAlways == key` (`ChatViewModel.kt:98-101`); `send` is guarded by `sending`. A withdrawn prompt sends nothing (`:126`). `already_resolved`/`expired` show a snackbar.
**6. T11b — PASS.** The omit-resume keeps the caught-up `lastSeq`/epoch (`ChatReducer.kt:79`); an epoch mismatch does a full resume (`ChatFeed.kt:118-121`). `withOpenRequests` touches only `prompts`.
The old socket gets a 1000 close (or a cancel while connecting) before the next `openRelay` (`LinkManager.kt:241`, `RelaySocket.kt:79-82`, joined by `coroutineScope`). T12 changes no seam logic.

**MAJOR-1 (approve an unseen command).** "Allow once" puts the key into `answered` at once (`ChatViewModel.kt:129`). The sheet then swaps in place to the next queued prompt (`PromptSheetState.kt:43`), and its buttons are enabled because `busy` checks the new key (`PromptSheet.kt:123`). `sending` clears after about one RTT.
So a double tap, or a tap that lands as a new prompt auto-raises the sheet (`PromptSheetState.kt:68`; in T13 the composer's Send sits where "Allow once" is), approves a command never shown. Fix: arm each newly shown prompt in the ViewModel (ignore choose/answer/confirm for about 600 ms after its key first becomes `showing`). Test: two prompts, Once, then an immediate Once ⇒ exactly one frame.
**MINOR-1.** `answered` never expires. After `Refused`, or an srq response OkHttp accepted but lost with the socket, the chip stays and opens nothing until the prompt settles (sudo waits 120 s). Drop the key on `Refused` and on a new link generation/snapshot.
**MINOR-2.** The exported MainActivity accepts `OPEN_SESSION` (+`prompt=true`, `title`) from any app (`MainActivity.kt:41-47`). It cannot answer, but it can raise the sheet on demand (tap-timing; MAJOR-1's arm delay covers this) and spoof the fallback title. Fix: a per-process nonce extra, or a non-exported alias.
**NIT.** With "show" on, `VISIBLE_PASSWORD` re-enables copy/cut (only if the user does it). `clarify.lock` is missing from relay `knownMethods` (audited as "other"). Srq frames are matched by `session_id` alone, and ids repeat across profiles on a host-scoped socket.

VERDICT: CHANGES-REQUIRED
