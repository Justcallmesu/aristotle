# T10 review: FGS + notifications + Xiaomi guide
Fresh reviewer. Read the brief docs, the diff and the untracked files, plus Hermes source (read-only) for the resume/subscription lifetime. No gradle run. PM gate pm-gate-14: PASS android.

## Findings
- **MAJOR-1: `session.replay_gap` is ignored, so a watched session goes silent until the next reconnect.** Hermes ends a viewer's subscription when its fanout backlog overflows. The socket stays up (`gateway/session_authority.py:389-395` `_retire_overflowed`; `session_events.py:66-73` sends `session.replay_gap`; protocol §2 says "re-resume"). `SessionWatcher.onEvent` (SessionWatcher.kt:88-103) drops that event, and the id stays in `resumed`, so it is never resumed again on that socket. Turn-finished and needs-you alerts for that session are then lost for the socket's life, which can be hours in the background. The risk is highest in exactly this case: the phone now receives every running session's full delta/tool stream over a slow Tailscale or cellular link. The new protocol §2 note ("A resumed session stays resumed for the socket's life") contradicts §2's own replay_gap line. Fix: on `session.replay_gap` remove the id from `resumed` and resume it again (or trigger a relist). Add a SessionWatcher test and correct the §2 note.
- **MINOR-2: needs-you notifications re-alert and go stale.** Each new relay re-sends the snapshot `prompts`. `AlertRouter` posts again even when `shownPrompts[sid]` is already that prompt, and `Notifier.alert` has no `setOnlyAlertOnce`. So every Wi-Fi↔Tailscale switch or backoff reconnect re-buzzes a HIGH heads-up and brings back one the user dismissed. The reverse also happens: a prompt settled while the link was down is never cancelled, because a snapshot without it does not clear it. Fix: skip a promptId that is already shown, and on resume cancel `needs:` for sessions whose snapshot lacks the shown prompt.
- **MINOR-3: an automatic resume changes Hermes session lifecycle (spec Q4 "without disturbing").** Resume calls `cancel_persisted_close` (`session_authority.py:383-384` → `session_viewer_close.py:137-146`). A TUI/CLI closed mid-turn (`tui_close`) has its stored close cancelled by the background watcher. When the phone socket drops, it is replaced by an orphan reap (`session_controls.py:951`, `arm_detached_orphan_close`, reason `ws_orphan_reap`). The Desktop sends no `end_reason`, so this is narrow. It still needs one line in the protocol §2 note and a PM accept/decide.
- **MINOR-4: background traffic.** Every running session's full event stream now reaches the phone while it is backgrounded. Two independent `session.list` loops (SessionWatcher plus T09's SessionRepository, whose VM outlives the screen) refetch on every `sessions.changed`. Rate check against the T06 limit: phone-sent frames are at most about 1 list/s per loop plus at most 50 resumes per relay (`limit 50`, running only), around 170/min worst case. That is far from 600/min, so there is no `4429` risk. Data and battery cost is unmeasured; add it to DC5. Consider one shared list.
- **MINOR-5:** `srq-` `sudo`/`secret`/batch-clarify requests for watched sessions now reach the phone and are not notified or declined. Record this for T12. Nit: "Wants to run a command" is also shown for file-write approvals (`command:"<write to AGENTS.md>"`).

## Verified OK
- Manifest: `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; FOREGROUND_SERVICE, `_SPECIAL_USE` and POST_NOTIFICATIONS are declared. The service is not exported.
- `startForeground` runs first thing in `onStartCommand`, typed on API 34+. A refusal (`ForegroundServiceStartNotAllowedException` ⊂ ISE) is caught for both the controller start and a sticky restart.
- The service starts from the last activity's `onActivityStopped`, which uses the leaving-visible exemption; config changes are skipped.
- `START_STICKY`, `onTaskRemoved` is a no-op, no wake lock.
- Stop: `stopByUser` → `LinkManager.stop()` → `LinkState.Stopped`, the socket is closed (tested), and the link resumes on the next activity start. `BackgroundPolicy` ends the service on Stopped, Revoked, HermesUpdated, unpaired or the setting off. The service stays while credentials are Loading.
- Both PendingIntents are explicit and use `FLAG_IMMUTABLE`.
- Channels: link LOW (silent, no badge), turn DEFAULT, needs HIGH. Names in EN and ID.
- Alerts are `VISIBILITY_PRIVATE` with a public "Aristotle · Turn finished / Hermes needs you" version.
- The approval `command` is never read: `promptOf` reads `question` for clarify only. The clarify first line (≤120 chars) is visible only when unlocked, including heads-up; acceptable under Q7c. Turn text is `message.complete.text`, never tool output.
- `outcome:"cancelled"` is skipped. `*.settled` cancels only the prompt that is shown.
- No duplicate resume within one socket. A timeout counts as resumed; a refusal is retried on the next pass.
- AlertRouter runs on Main (container scope); the events collector starts UNDISPATCHED before the list call.
- Xiaomi intents: explicit components, wrapped in `try`/catch of ActivityNotFound and SecurityException, falling back to App details. No `resolveActivity`, so package visibility cannot cause false fallbacks. Whether the components land on the right page on HyperOS is left to DC4.
- The guide matches design.md §5. The badge-colour deviation is justified by §1.
- EN and ID strings are mirrored (51 lines each); no hard-coded UI text found.

VERDICT: CHANGES-REQUIRED
