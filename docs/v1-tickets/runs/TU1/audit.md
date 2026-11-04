# TU1 — UI design audit (device screenshots vs docs/design.md + mockups)

Scope: scratch/ui/{10-list,02/15-profile,13-cron,14-search,16-history-chat,17-attach,19-live-chat,20-toolcard-open,21-model-sheet,23-slash,00-quote}.png + .xml.
Device density is ≈2.2 px/dp (session row 158 px = `Sizes.sessionRowHeight` 72 dp; IconButton 106 px = 48 dp); dp below use that.
Paths are relative to `android/app/src/main/java/io/github/justcallmesu/aristotle/ui/`. Settings (TS1) is not covered.
Clear (nothing to fix): **colour roles** (verdigris only on Running / spinners, red only on Failed / Stop), **touch targets**
(smallest is 48 dp: back, icons, chips, menu rows, slash rows; model rows 56), **radii** (they match §3 except S4/S6).
Not from the app: the grey bar at x≈1062 y 293–437 in every capture, the blank 00-quote.png included, is HyperOS's
sidebar handle. 00-quote.png caught a blank frame, so the Q46 quote screen is **not audited**; re-capture it.

## Findings

| ID | Sev | Screen · element | What is wrong | Fix |
|---|---|---|---|---|
| G1 | HIGH | All screens · all sans + mono text | Atkinson Next / Mono are **not rendering**. You see the HyperOS fallback: thin strokes (~6 % of em), a `1` with no base in "511 messages" (10-list [35,795]), a plain unslashed `0` in "10208" (20-toolcard [61,983]). Literata titles do render. This is the main reason it all looks thin and cheap. `theme/Type.kt:21-34` | Bundle Atkinson Hyperlegible Next + Mono 400/500/600 under `res/font/` (OFL) and keep Literata downloadable. Log `FontFamily.Resolver` failures in debug builds. Confirm with `adb logcat -s FontsContract` before the fix. Needs D1 |
| G2 | MED | Every button, plus the effort chips | `heightIn(min = Sizes.minTouchTarget)` on Button / OutlinedButton / FilledTonalButton / FilterChip makes the **visible** control 48 dp tall. You get heavy slabs (Stop/Queue/Steer [26,2230]–[1054,2336]); mockup `.btn` is 40. Sites: `chat/Composer.kt:209,229,236,241`, `chat/ModelSheet.kt:147`, `chat/PromptSheet.kt:207,215,285,376,382,408`, `cron/CronFormPage.kt:191`, `sessions/SessionsScreen.kt:511,527`, `terminal/TerminalScreen.kt:179,238` | Remove the modifier on M3 buttons and chips. M3 already pads touch to 48 dp (`minimumInteractiveComponentSize`), so visible size becomes 40 dp (button) / 32 dp (chip) |
| G3 | MED | Top bar icons, tool-card icons | Filled and outlined glyphs are mixed. Filled: `ic_settings` gear, `ic_description`, `ic_build` wrench (vision_analyze, 19 [62,735]). Outlined: `ic_terminal`, `ic_schedule`. Top-bar icons are `onSurface` at 24 dp and outweigh the 12 sp subtitle | Use Material Symbols **Outlined** weight 400 for every `res/drawable/ic_*.xml` the UI uses. Tint top-bar actions `onSurfaceVariant` |
| G4 | LOW | Chat code | Spacing off the 4 dp grid: `6.dp` (`ChatItems.kt:115,208,266`, `ChatScreen.kt:361`), `10.dp` (`ChatItems.kt:356` FoldHeader). Bubble `14/10` is OK (mockup) | 6→8 (or 4), 10→12. Add `Spacing.xxs = 2.dp` only if one is really needed |
| L1 | MED | 10-list · top bar [604,112]–[1071,218] | Chip + clock + search + gear (+ terminal when on) squeeze the wordmark and read as a toolbar. Mockup has chip + search only. The clock icon reads as "history", not "Scheduled" | Keep chip, search and one ⋮ overflow; put Scheduled, Terminal and Settings in the overflow (D5) |
| L2 | MED | 10-list · profile chip [604,130]–[753,200] | `FilterChipDefaults` border = `outline` #96846F and a regular-weight label: it looks like a dropdown text field. `SessionsScreen.kt:261-273` | Border 1 dp `outlineVariant`, label `labelLarge` (14/600), 32 dp, padding 12 start / 8 end, trailing icon 18 dp `onSurfaceVariant` (mockup `.chip`) |
| L3 | LOW | 10-list · trailing time ("now" [1005,381]) | Vertically centred. Mockup top-aligns it with the title (`.tr padding-top 6px`) | `RowTimeText` (`SessionsScreen.kt:495`): align Top, padding top 4 dp, keep `tnum` |
| L4 | LOW | 10-list · rows | 72 dp rows hold ~32 dp of content (Compose trims line height) with no separators. Together with G1 the list looks sparse and floaty | Keep 72 dp (spec) and re-shoot after G1. If still floaty, add a 1 dp `outlineVariant` divider inset 16 dp between rows of one section |
| L5 | LOW | 14-search · field [115,112]–[1071,218] | No ✕ to clear the query. The caret draws over the "S" of the placeholder | Trailing `IconButton(ic_close)` while the query is non-empty. Placeholder `onSurfaceVariant` at 8 dp start, which is already there |
| L6 | MED | 13-cron · empty state | Caption at x=35 (16 dp), "No scheduled jobs" at x=53 (24 dp): two left edges. `cron/CronScreen.kt:354` uses `Spacing.xxl` / `xxxl` | `padding(horizontal = Spacing.listGutter, top = Spacing.l)`, title `titleSmall`, body 4 dp below |
| C1 | HIGH | 19-live-chat · tool-card summaries | 8 of 9 summaries start `/home/justcallmesu/.hermes/profiles/pm/cache…` or `S=/home/…`, so the part that tells cards apart is ellipsized away. The cards become a wall of identical rows | Display-only formatter in `chat/ChatText.kt`: `$HOME`→`~`, collapse middle path segments (`~/…/scratch/x.png`), basename first for file tools, drop leading `VAR=…;` assignments for terminal. Data in `data/chat/ToolText.kt` stays as is |
| C2 | HIGH | 19/20/23 · chat item rhythm | A tool header is 60 dp (FoldHeader min 48 + 2×6 padding, `ChatItems.kt:115,208`); with `spacedBy(Spacing.m)` (`ChatScreen.kt:471`) that is 72 dp per call. A bare "Reasoning" row takes 48 + 2×12 = 72 dp ([35,851]–[1045,957]). About 10 steps fit on a screen and there is no grouping | Header = 48 dp including padding (vertical 0). Reasoning row 32 dp visible (`Sizes.reasoningRow`, already defined, unused) with 48 touch. Spacing by previous item: 4 dp between consecutive work items (tool / reasoning / notice), 12 dp between message and work, 20 dp before a user bubble. Replace `spacedBy` with per-item top padding (D2) |
| C3 | MED | 23-slash · top-bar subtitle [168,176]–[675,204] | `spacedBy(6.dp)` plus a `"· $details"` prefix (`ChatScreen.kt:361,373`) gives "Running · step 8 ␣␣· pm", so separators are visibly uneven | One `Text` with an AnnotatedString: tertiary span "Running · step 8", then " · pm · model · Medium" in `onSurfaceVariant`. Spinner 14 dp + 6 dp gap. Drop-down icon 14→18 dp |
| C4 | MED | 16-history-chat · top bar | The subtitle appears after load (xml bounds [124,176][438,182], only 2 px tall mid-expansion). The title sits lower without it, so the bar jumps ~7 dp | Always reserve the subtitle line. Show the profile from the opened row right away. No enter animation on it |
| C5 | MED | 16-history-chat · markdown rhythm | Spacing is inverted: paragraph→paragraph 5 dp ([35,435]→[35,447]) but bullet→bullet 12 dp ([1171]→[1198]). `ChatMarkdown.kt:68-76` sets no padding | `markdownPadding(block = 12.dp, list = 4.dp, listItemTop = 2.dp, listItemBottom = 2.dp, indentList = 16.dp)` |
| C6 | MED | 16-history-chat · table [70,530]–[1010,886] | Cells are ellipsized to 1 line ("spec, run …", "unt…"), so content is lost | Table in a `horizontalScroll`, cells wrap at min 120 / max 240 dp. With > 3 columns render it as a mono block |
| C7 | LOW | 16-history-chat · inline code | Padded with spaces (xml `' docs.tar.gz  '`), which gives double gaps ("puts  docs/  and"). Size is 15 sp chat text (`ChatMarkdown.kt:147`); spec says 13/18 | `inlineCode = AristotleTextStyles.codeInline` with a background span and no space padding (a U+2009 thin space each side at most) |
| C8 | LOW | 20-toolcard · open output | Bottom inset 6 dp vs 12 dp on the sides ([61,1563]–[1019,1724] inside [35,1431]–[1045,1737]). `ChatItems.kt:266` | `bottom = Spacing.m` |
| C9 | LOW | 16-history · code block [35,995]–[1045,1091] | Long line cut at the right edge with no end inset ("…-134") | 16 dp end padding inside the scroll content (no fade: §7 forbids gradients) |
| S1 | MED | 19-live-chat · busy composer [0,2088]–[1080,2400] | 142 dp of chrome. With the field empty, Queue and Steer sit as two disabled grey slabs. With the keyboard + slash menu (23) only ~3 transcript rows remain | After G2 the buttons are 40 dp. Show Queue + Steer + hint only while the field has content (150 ms fade). Stop always visible (D4) |
| S2 | MED | 23-slash · command rows | `heightIn(48)` + 4 dp vertical padding + centred (`SlashMenuPanel.kt:142`). 1-line rows float, 2-line rows touch the next one (/goal content 981–1065 inside 970–1076 vs /prompt 784–839). The last row is cut mid-line by `MENU_MAX_HEIGHT` 240 dp (`:50`) | Padding 8 dp vertical, align Top, description `maxLines = 1` with the "(usage: …)" tail removed. Max height = 4.5 rows so the cut reads as scrollable |
| S3 | LOW | 23-slash · panel [26,712]–[1054,1240] | `surfaceContainerLowest` well inside the `surfaceContainer` composer (`SlashMenuPanel.kt:71`) reads as a hole | `surfaceContainerHigh`, shape `medium` |
| S4 | MED | 21-model-sheet · effort chips [53,426]–[1012,638] | Chips are 48 dp boxes (G2). The FlowRow has no vertical arrangement, so rows are 0 dp apart vs 8 dp horizontally, and "Max · Ultra" are orphaned (`ModelSheet.kt:140-148`) | Remove `heightIn`, `FlowRow(verticalArrangement = spacedBy(8.dp))`. Or one horizontally scrolling row of 32 dp chips |
| S5 | LOW | 21-model-sheet · list | No visible selected row. The current model is only named in "Now: …" | On open, scroll the current row to the top of the list, or pin it as the first selected row |
| S6 | MED | 17-attach · menu [26,1864]–[282,2182] | DropdownMenu defaults: `surfaceContainer` on `surface`, 4 dp corners, shadow invisible on dark. The menu dissolves into the transcript text behind it | `AttachmentViews.kt:96`: `containerColor = surfaceContainerHigh`, `shape = medium`, 1 dp `outlineVariant` border, opens 8 dp above the clip |
| S7 | LOW | 02-profile-sheet | Layout matches the mockup (titleLarge, 56 dp radio rows, 24 dp gutter). Only G1 applies | — |

## Implementation tickets (disjoint files)

**TU2 — Type, icons, theme tokens** (run first; TU3–TU5 re-shoot after it).
Files: `theme/Type.kt`, `theme/Spacing.kt`, `res/font/**` (new), `res/drawable/ic_{settings,description,build,schedule,search,terminal,expand_more,attach_file,send,stop,close}.xml`, `docs/design.md` §2/§4 (after D1/D2).
Brief: make Atkinson Hyperlegible Next and Mono actually render. Bundle 400/500/600 as `res/font` families with
Literata kept downloadable. Keep the device fallback last and log resolver failures in debug. Swap every glyph the UI
uses to Material Symbols Outlined weight 400 on a 24 dp grid (G3). Add any spacing token TU4 needs (G4). Before/after
device captures of 10-list and 20-toolcard must show slashed zeros and Atkinson's `1`.

**TU3 — Session list, top bars, Scheduled**.
Files: `sessions/SessionsScreen.kt`, `sessions/SessionSheets.kt`, `cron/CronScreen.kt`, `cron/CronFormPage.kt`, `terminal/TerminalScreen.kt`, `components/SubPageTopBar.kt`.
Brief: L1 (chip + search + ⋮ overflow holding Scheduled, Terminal, Settings; D5), L2 chip styling to mockup `.chip`, L3
top-aligned tabular time, L5 clear button in search, L6 cron empty-state alignment. Remove the visible 48 dp `heightIn`
on buttons in these files (G2). Re-check L4 on device after TU2 and add inset dividers only if rows still float.

**TU4 — Chat transcript: items, tool cards, markdown, chat top bar**.
Files: `chat/ChatScreen.kt`, `chat/ChatItems.kt`, `chat/ChatMarkdown.kt`, `chat/ChatText.kt`, `chat/SubagentViews.kt`.
Brief: C1 display-only tool summary shortening (~, basename-first, strip env assignments; unit-test the formatter).
C2 rhythm: 48 dp headers including padding, 32 dp reasoning row, 4/12/20 dp spacing by previous item type. C3 one
AnnotatedString subtitle and C4 reserved subtitle line. C5 markdown padding, C6 scrollable tables, C7 inline code at
13 sp, C8/C9 insets. Keep the expand/collapse motion as spec'd. Acceptance: on 19-live-chat ≥ 14 tool steps per screen
and every summary shows its distinguishing tail.

**TU5 — Composer, slash menu, sheets, menus**.
Files: `chat/Composer.kt`, `chat/ChatComposer.kt`, `chat/SlashMenuPanel.kt`, `chat/ModelSheet.kt`, `chat/AttachmentViews.kt`, `chat/PromptSheet.kt`.
Brief: G2 in these files (40 dp visible buttons, 32 dp chips). S1 busy composer: Queue/Steer/hint appear only with
content (D4), Stop always. S2/S3 slash rows (8 dp padding, 1-line description, 4.5-row cap, `surfaceContainerHigh`).
S4 effort chips with 8 dp row gap. S5 current model scrolled into view. S6 attach menu surface, shape and border.
Approval and clarify sheets keep the Q41 layout; only button heights change.

## PM decisions (design.md rules that should change)

- **D1 §2 fonts:** replace "Do not bundle font files in v1" with "bundle Atkinson Hyperlegible Next + Mono (OFL); Literata stays downloadable". The GMS provider evidently does not serve them on this phone (G1).
- **D2 §4 sizing / spacing:** add "48 dp touch comes from `minimumInteractiveComponentSize`; never stretch the visible control (buttons 40, chips 32, reasoning row 32)". Add chat spacing "4 dp within a run of work items, 12 dp message↔work, 20 dp before a user bubble".
- **D3 §5 tool card:** allow a shortened display summary (`~`, basename-first, env assignments dropped). Decide whether ≥ 3 consecutive tool calls fold into one "N tool calls" card (spec now says one card per call; this is the biggest remaining noise lever).
- **D4 §5 composer busy:** Queue, Steer and the hint line show only while the field has content; Stop is always visible.
- **D5 §5 sessions top bar:** wordmark · profile chip · search · ⋮ (Scheduled, Terminal, Settings), matching the mockup's two-action bar.
