# Review — v2 redesign TR2-TR6 (+ TH1b CredentialCodec)

Scope: `git diff HEAD -- android` + untracked files, TR2-6 briefs/reports, design-notes §3-§6/§10, mockups.html CSS (read, not rendered), `git show 935264f` CredentialCodec. ViewModels, ComposerController and prompt logic untouched. pm-gate-54 GREEN. Not checked on a device.

**Behaviour preserved: holds.**
- Prompt sheets: only clarify rows (PromptSheet.kt:300) and the secret field container (:390) changed; Q41 order, 600 ms arm, FLAG_SECURE, fresh-auth gates and send-on-cancel untouched.
- Composer: send/queue/steer/stop/`cancelQueued`/draft epoch call the same actions; `showsBusyExtras` kept; wired at ChatScreen.kt:311-313.
- Home: paging (`totalItemsCount`, footer last), pull-to-refresh, rename, pins, profile fallback, Retry (now in the PC line) all work. Settings: unpair/re-pair/delete use the same dialogs and ViewModel calls.
- Model sheet: rows and slider disabled while sending; `pickReasoning` fires once per gesture end, only on change.

**Q75 "N steps": correct.** ToolFolding.kt:78-100 folds every run, incl. one call and reasoning-only. `summary(live)` (:38): running only while live, failed count, live call named. Real-turn fixtures updated ("fold(thought)", "Running · 17 steps"); 13 tests.

**Findings (no BLOCKER/MAJOR)**
- MINOR-1: with the title tap gone, `chipLabel(chat.model=null)` gives no chip (ChatScreen.kt:311), so nothing opens the model sheet. Fix: a "Model" chip fallback; check on device if `session.info.model` is ever missing.
- MINOR-2: Queue/Steer/hint no longer fade; `AnimatedVisibility(visible = true)` never animates (Composer.kt:331). Doc :291 and TR4's report claim a fade.
- MINOR-3: busy command Send (`StopSend`, Composer.kt:305) lost the `mode == Idle|Busy` guard, so it is enabled while `Covered`. Low risk (sheet covers it); restore it.
- MINOR-4: "Waiting for/Needs your approval" (strings.xml:692, :711) also shows for clarify/sudo/secret prompts (was "Hermes is waiting for you"). Pick the text by prompt kind.
- MINOR-5: `model_count` (strings.xml:688) reads "1 models" (lint PluralsCandidate); make it a plural.
- MINOR-6 (Q67): steps line uses touch-bounds expansion, not the `minimumInteractiveComponentSize` the notes ask for (ChatItems.kt:192); stacked step rows are 44 dp targets (:512). Accepted (board fixes 36/44); Q67 needs a written exception.
- NIT, model sheet vs §4/§5.3: search field on Highest with no focus ring (ModelSheet.kt:215); check tint not `primary` (:348); rows mono 13 not 14; stale "opened from the chat's top bar" (:116); running note lost when "sends X" shows (:264-266); effort not in choices can't commit back to index 0 (:240).
- NIT: output preview still 40 lines, board ~12 (ChatItems.kt:67, unstated). Offline notice jumps 4 dp when `trying` flips (OfflineBanner.kt:44). Settings `LargeTopAppBar` also takes sub-page scroll (SettingsScreen.kt:113-115).
- NIT, cleanup: ChatScreen `ProfileMark`/`ChatSubtitle.initial` copy the public `ui/sessions` ones (and differ on code points/Locale); SettingsModel.kt:41 copies the `remembersDeletion` rule; `ModelSheetRows` and `Sizes.reasoningRow` are dead.

**Board fidelity: matches.** Home C (300×132 cards, 52 dp ledger, 56 dp time column, bottom bar + FAB, 48 dp avatar target, name beside per Q76/brief), floating composer (ComposerShape, 40 dp square Send/Stop, chip), steps line/list, Settings grouped rows (64 dp), Soft corners.
**Workers' deviations: all accepted.** TR2: cards lack a step (no data), `ic_add`, richer offline notice, search flow (add `edit_square` later). TR3: reasoning-only "1 step" (PM may prefer "Thought for N s"); its private ProfileMark is now redundant. TR4: Stop · Send for a busy command. TR5: effort label on its own line (slider draws the name). TR6: `SubPageTopBar`, sheet rows on High (correct on a Low sheet), secret EditText without background.

**Unused resources (lint), from this batch:** strings `sessions_new`, `sessions_section_live`, `sessions_section_history`, `sessions_offline_line`, `offline_title`, `session_pinned`, `chat_waiting`, `model_open`, plurals `chat_tool_group`; drawable `ic_attach_file`. Unused before this batch: `settings_addresses`, `settings_version_value`, `cron_tz_note`, `settings_address_delete_text`.

**TH1b deletion rule: correct.** `remembersDeletion` (CredentialCodec.kt:199) is true only when another stored address of the same network stays. `withoutAddress` (:182) keeps the last address. `withLink` filters remembered deletions; only a pin-verified `found` brings one back. Dialog copy matches (SettingsModel.kt:41); tests CredentialStoreTest.kt:335-363. NIT: `merge` past `MAX_STORED` can evict the surviving sibling, leaving a remembered last-of-kind deletion (rare).

VERDICT: PASS-WITH-FINDINGS
