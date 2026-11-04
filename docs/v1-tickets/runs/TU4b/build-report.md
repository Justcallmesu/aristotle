# TU4b build report: folding on real chats, ICU regex guard

- **Folding: no defect found in the run rule or the mapper; nothing in production changed for it.** I rebuilt the phone's `session.resume` rows with Hermes' own `local_history` code (`display_rows` + `_merge_live_in_stored_order`) on a copy of `pm/state.db`, for the two chats in your captures (`scratch/ui/`, 10:42–10:46). I ran them through `HistoryMapper`, `ChatReducer` and `ToolFolding` on the JVM. I also ran 113 real sessions from the last 3 days (all profiles): 5,649 of 6,098 calls fold. What ends a run in real data is only what Q68 lists: commentary text (168) and cards (2).
- **History ("98 messages", FIFO session) does fold on the device.** Your own `t.png`/`t.xml` (10:44:44) shows "4 tool calls · last: execute_code" between "Taking it as…" and the delegate card. The JVM gives the same row there. h5–h12 are all one giant "[CONTEXT COMPACTION — REFERENCE ONLY]" message, so 12 swipes never got past it. Above it (46-hist-up), the runs are 1–2 calls long and are ended by user steers ("also the path is now different…") or commentary.
- **Live (`43-chat`, your running turn): not explained.** Real rows 26369–26408 plus a live round in Hermes' publish order (thinking, reasoning.delta, `message.delta null`, tool.generating, reasoning.available, tool.start/complete) give one folded card: `user · fold(11 calls, 6 reasoning)`. The screenshot instead shows patch · terminal · patch as three cards, which no fold rule produces. In the uiautomator tree, every visible card sits under one list child whose header is just above the viewport, unlike the history chat, where each row is its own child. That matches the folded card being **open**, but the only thing that opens it is `FoldState.toggle` (a tap on the header), and your script had no tap in the chat. Next device check: on a fresh open of a running chat, swipe down about 300 px and dump the screen. Look for "N tool calls" above the cards.
- **Regex audit:** I checked all 26 patterns in `src/main` (27 constants, 27 inline literals; 2 inline ones are templated). The dump of their actual pattern strings and flags compiles in real ICU 78 (`uregex_open`, built locally). `\$\{HOME}` reproduces your crash (`U_REGEX_RULE_SYNTAX`, index 79). No other bad regex: braces inside character classes (`PATH_CHAR`'s `(){}`) are valid in ICU, and the device's path shortening confirms it. The rule is now a comment at `ToolSummary.HOME` (ChatText.kt) and at the AttachmentRefs regex block.
- **Guard:** `IcuRegexGuardTest` has 3 tests:
  - Reflection over every static `Regex`/`Pattern` in the compiled app classes (≥20 required).
  - A source scan of every inline `Regex(`, `Pattern.compile(` and `"…".toRegex(` (≥20 required; a templated pattern must be a swept constant).
  - Self-tests of `IcuRegexRules` on the cases checked against ICU: no bare `{`/`}` outside a class, one quantifier per atom, bounded lookbehind, no `(?U)`/`\b{…}`/`[a-\w]`/CANON_EQ.

  `ICU_REGEX_DUMP=<file>` writes the patterns so they can be run through ICU by hand.
- **Regression tests:** `ToolFoldingRealTurnsTest` (2 tests) uses the two real turns, redacted (no names, paths, hosts or provider ids; roles, order, row ids, tool names, reasoning and failure shapes kept), in `src/test/resources/chat/`. It also checks that the live card keeps its key when its last call ends.
- **Mutations:**
  - Reverting the HOME escape fails both regex sweeps ("bare '}' at 78").
  - Making Reasoning end a run fails both real-turn tests.
- **Gate (worktree):** `./gradlew --no-daemon -q lintDebug testDebugUnitTest assembleDebug` exits 0, 672 tests, 0 failures.
- **`git -C …/wt-head diff --stat`:**
  - `AttachmentRefs.kt` +2 and `ChatText.kt` +4/−1 (your escape plus the comment).
  - Untracked: `IcuRegexGuardTest.kt`, `ui/chat/ToolFoldingRealTurnsTest.kt`, `src/test/resources/chat/{stored,running}-turn-with-calls.json`.
