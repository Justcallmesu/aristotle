# TU3 build report — session list top bar, chip, Scheduled, sub-page/terminal bars

Gate (`GATE_ONLY=android`): lint + Kotlin compile pass; 667 unit tests run, **1 fails: `ChatLayoutTextTest.shellAssignmentsInFrontOfTheCommandGo` (TU4's file, in progress)**. Nothing of mine fails. Not installed, no adb.
- **L1 (Q70)** fixed: top bar is wordmark + connection line · profile chip · search · ⋮. `OverflowMenu` (`SessionsScreen.kt`) holds Scheduled, Terminal (only when `terminalAvailable`), Settings. Entry list is the pure `listMenuEntries()` in `SessionRows.kt`. New `res/drawable/ic_more_vert.xml` (outlined 24 dp; none existed) and string `sessions_more` (en + id, appended at the end).
- **L2** fixed: chip label `labelLarge`, border `outlineVariant` via `filterChipBorder`, trailing icon 18 dp `onSurfaceVariant`. The 32 dp height and 12/8 padding come from M3 `FilterChip` (no override).
- **L3** fixed: `SessionRowItem` is now a plain `Row` (M3 `ListItem` centres the trailing slot). Time is top-aligned, 4 dp top padding, `tnum` kept. Row min 72 dp, gutter 16, vertical 8.
- **L4** not touched: it needs the device re-shoot after TU2; no dividers added.
- **L5** already satisfied: the ✕ (`sessions_search_clear`) was already in `SearchBar`. I only tinted it `onSurfaceVariant`.
- **L6** fixed: `CronScreen.EmptyBody` and the sessions `EmptyBody` (same bug) use `listGutter` / `Spacing.l`, so there is one left edge.
- **G2** (my files): TU2 already removed the `heightIn(48)` modifiers; none are left, and `TouchTargetTest` passes.
- **G3** (top-bar part): back, search, ✕ and ⋮ icons in the list, search, `SubPageTopBar` and terminal bars are tinted `onSurfaceVariant`. The terminal "more" button now uses ⋮ (`ic_more_vert`) instead of the expand chevron.
- Files: `ui/sessions/SessionsScreen.kt`, `SessionRows.kt`, `ui/cron/CronScreen.kt`, `ui/components/SubPageTopBar.kt`, `ui/terminal/TerminalScreen.kt` (top bar only), `res/drawable/ic_more_vert.xml` (new, outside the list: no existing ⋮), `res/values{,-in}/strings.xml` (appended).
- Tests: `SessionRowsTest.overflowMenuListsTerminalOnlyWhenAvailable`. Row/chip/menu layout is UI-only, so it needs a device check.
- New tokens: none.
