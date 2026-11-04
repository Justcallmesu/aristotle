# TU4b — tool-call folding never triggers on the device (+ ICU regex crash)

route: deep (diagnose). Work ONLY in the worktree `/home/justcallmesu/.hermes/profiles/pm/cache/scratch/wt-head` (detached HEAD 6493893 +
PM's one-line fix in `ui/chat/ChatText.kt:108`). The main repo tree is being edited by another worker: do not touch it, except writing your
report to `/home/justcallmesu/project/personal/Aristotle/docs/v1-tickets/runs/TU4b/build-report.md`.

## Evidence (PM, Poco X5 Pro, build of 6493893)
1. **Crash on launch into a chat**: `PatternSyntaxException ... near index 79` at `ToolSummary.<clinit>(ChatText.kt:108)`: Android's ICU
   regex rejects the unescaped `}` in `\$\{HOME}`; the JVM unit tests accept it. PM escaped it (`\{HOME\}`). Do: audit EVERY `Regex(`
   / `toRegex` / `Pattern.compile` in `android/app/src/main/**` for ICU-vs-JDK differences (unescaped `{`/`}` outside a quantifier,
   possessive/odd constructs, `\h`, unicode classes, lookbehind with unbounded length) and add a guard: a unit test that compiles every
   regex constant through a strict check (e.g. reject a bare `}`/`{` not part of `{n,m}`) — since ICU isn't on the JVM, make the test
   express the ICU rules we rely on; document the rule in a short comment where regexes live.
2. **Folding (Q68) never happens on real chats**, live or history: long runs `terminal · Reasoning · terminal · Reasoning · patch ·
   terminal · patch …` render as single cards; no "N tool calls" header anywhere after scrolling 12 screens of a 98-message history
   session. `ToolFolding.rows` looks right for Tool/Reasoning/blank Assistant (`ui/chat/ToolFolding.kt`), so real `chat.items` must
   contain something between the calls that ends a run (or the rows aren't what reaches the LazyColumn). Find the root cause with REAL data:
   you may READ (never write) the Hermes session store under `/home/justcallmesu/.hermes/` (e.g. the sqlite state db / session JSON) to
   build a fixture of one real assistant turn with tool calls, map it through `HistoryMapper` (and the live path through `ChatReducer` with
   a frame fixture if the repo has captured frames under `docs/` or test resources), run `ToolFolding.rows`, and see what breaks the run.
   Fix the root cause (in ToolFolding's run rule or in the mapper, whichever is wrong — e.g. empty/whitespace/`\u200b` assistant text,
   per-tool assistant items carrying tool-call JSON, notices), and add a regression test using the real-shaped fixture (redact any secrets
   and personal paths in the fixture).

## Files
`ui/chat/{ToolFolding,ChatText,ChatScreen,ChatItems}.kt`, `data/chat/{HistoryMapper,ChatReducer}.kt` only if the root cause is there,
other files containing a bad regex (the one-line escape only), tests. Never git checkout/restore/stash/reset/commit in either tree.
Never connect to Hermes; read-only file reads only. No adb.

## Verify
In the worktree: `cd android && ./gradlew --no-daemon -q lintDebug testDebugUnitTest assembleDebug` exits 0.

## Report
≤20 lines: root cause (with the real item sequence that broke the run), fix, regex audit result, tests, `git -C <worktree> diff --stat`.
