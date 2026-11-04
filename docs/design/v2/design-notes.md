# Aristotle v2 — design notes (TD2 + TD2b)

Status: **chosen** (Q75, 2026-11-04): Home **C** without message counts, **Soft** corners, effort **slider**, the **"N steps"**
line; TD2b: the profile is an **avatar button top right** on Home C opening today's profile sheet (§5.6). Board:
`docs/design/v2/mockups.html` (Tweaks: view, theme, corners, PC link state, profile label). The PM folds the chosen parts
into `docs/design.md`; A, B and the Round / v1 / segmented alternatives stay on the board for the record only.
TD3 adds §5.7 and board section 6: three PC status card options (open, **A recommended**), for the user to pick on the phone.

What does not change: palette A (every `ColorScheme` value in design.md §1), the role rules (verdigris = live work only,
red = Deny / Stop / errors only), the fonts (Literata titles, Atkinson Hyperlegible Next / Mono, design.md §2), tonal
elevation, motion rules, Q41 approval order, Q67 sizes (40 dp buttons, 32 dp chips, 48 dp touch from
`minimumInteractiveComponentSize`). The user said the problem is "not the color but the component ui shape", so v2
changes **shapes, containers and layout**.

Everything below uses only Material 3 Compose parts plus our `MaterialTheme` (colorScheme, typography, shapes). No blur,
no gradients, no shadows beyond the two design.md already allows (extended FAB, `ModalBottomSheet`). The compose BOM in
the repo is 2026.06.01, so the M3 1.3+ `Slider` (16 dp track, stop indicators, narrow handle) is the default look.

## 1. Screens and the one decision on each

| Screen | Surface | Decision |
|---|---|---|
| Home | Operate | Which session needs me now; start a new one |
| Chat | Operate | Read the answer; steer, queue or stop |
| Model sheet | Configure (modal) | Which model; how much reasoning |
| Settings + sub-page | Configure | Find the one setting |
| Approval sheet | Operate (modal) | Allow or deny, with the thumb |

## 2. What changes, in one list

1. **Softer corner scale** (§3), still no pills: each radius is below half its control's height.
2. **Grouped rows**: a list section becomes a column of `Surface` rows 2 dp apart; outer corners `large`, inner corners
   `extraSmall`. Used for the live block (home B), Settings, and the Connection sub-page.
3. **Filled search field** always visible on home B (48 dp, `surfaceContainerHigh`, `FieldShape`), instead of a top-bar icon.
4. **Floating composer**: one rounded field (`surfaceContainerHigh`, `ComposerShape`) inset 12 dp, holding the text, attach,
   the model chip and Send / Stop. Replaces the full-width `surfaceContainer` panel with top corners.
5. **"N steps" line**: every run of consecutive work items (reasoning + tool calls) folds into one quiet 36 dp line; opened,
   it shows compact 44 dp step rows in one container. Replaces one card per call / per fold (Q68 change, §6).
6. **Model sheet**: search on top, providers as collapsible sections, effort as a stepped slider in a footer (§5.3).
7. **History rows are one line** (56 dp target, time + title on Home C). No message count on history rows (Q75).
8. **Profile avatar** (TD2b, §5.6): a 40 dp Soft square with the profile's initial, its name under it, top right of Home C;
   replaces the profile chip. Same sheet as today.

## 3. Shapes → `Shapes` + three custom shapes

Tweak "Corners" on the board shows all three scales on every screen. **Recommended: Soft.**

| Token (Compose) | v1 (today, Q39) | **Soft (rec.)** | Round (ChatGPT-like) | Used for |
|---|---|---|---|---|
| `shapes.extraSmall` | 4 | **4** | 4 | Inner corners of grouped rows, inline code |
| `shapes.small` | 6 | **12** | 16 (pill on 32 dp) | Chips (profile, model, waiting), code/output blocks, segmented outer |
| `shapes.medium` | 10 | **16** | 20 | Step list container, cards (delegate, notification, notice), command block, selected model row, menus |
| `shapes.large` | 14 | **20** | 24 | User bubble, grouped-row outer corners, live cards (C), New chat button, FABs |
| `shapes.extraLarge` | 24 | **28** | 28 | `ModalBottomSheet` top corners, dialogs |
| `ButtonShape` | 8 | **12** | 20 (pill) | Every `Button` / `FilledTonalButton` / `OutlinedButton` / `TextButton` |
| `FieldShape` (new) | 4 | **16** | 24 (pill) | Search fields (home, model sheet) |
| `ComposerShape` (new) | 24 top only | **24** all corners | 28 (pill at one line) | The composer field |
| `IconButtonSquare` (new) | 8 | **12** | 20 | 40 dp Send / Stop square buttons |

`Shape.kt` (Soft):
```kotlin
internal val AristotleShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)
val ButtonShape = RoundedCornerShape(12.dp)
val FieldShape = RoundedCornerShape(16.dp)
val ComposerShape = RoundedCornerShape(24.dp)
val SquareIconButtonShape = RoundedCornerShape(12.dp)
/** Grouped rows: index/count → shape (outer = large, inner = extraSmall). */
fun groupedRowShape(index: Int, count: Int): Shape
```
Round is what the reference apps do; it breaks Q39 ("no pills") and makes the 40 dp buttons read as pills, so it is shown
only so the user can see the boundary. Nested corners stay concentric (inner = outer − inset) as in design.md §3.

## 4. Colour roles per new component (palette unchanged)

| Component | Container | Content | Contrast (light / dark) |
|---|---|---|---|
| Search field | `surfaceContainerHigh` | placeholder + icon `onSurfaceVariant`, text `onSurface`; focused 2 dp `primary` ring | 6.0 / 7.9 |
| Grouped rows | `surfaceContainerLow` | `onSurface`, summary `onSurfaceVariant` | 6.8 / 9.2 (summary) |
| Live status in grouped rows / cards | `surfaceContainerLow` | `tertiary` "Running · step 8", `error` "Needs your approval" | 5.1 / 9.7; 5.3 / 10.2 |
| Composer field | `surfaceContainerHigh` | text `onSurface`, placeholder `onSurfaceVariant` | 11.4 / 11.6; 6.0 / 7.9 |
| Model chip (in composer) | `surfaceContainerHighest`, no border | `onSurface` | 11.4+ |
| Send | `primary` / `onPrimary`; disabled `onSurface` 12 % / 38 % | | 11.6 / 10.6 |
| Stop (square) | `errorContainer` / `onErrorContainer` | | 10.4 / 7.1 |
| Waiting chip | `errorContainer` / `onErrorContainer` | | 10.4 / 7.1 |
| Steps line | none (on `surface`) | `onSurfaceVariant`; live: `tertiary` label + spinner | 7.2 / 9.8; 5.5 / 10.4 |
| Step list | `surfaceContainerLow`; output block `surfaceContainerHighest` | mono `onSurface` / `onSurfaceVariant`; "Failed" `error` on Low only | 5.3 / 10.2 |
| Selected model row | `secondaryContainer` | `onSecondaryContainer`, check `primary` | 11.7 / 8.2 |
| New chat (B) | `primary` / `onPrimary` | | 11.6 / 10.6 |
| Extended FAB (A), FAB (C) | `primaryContainer` / `onPrimaryContainer` | | 11.1 / 8.3 |
| Bottom app bar (C) | `surfaceContainer` | icons `onSurfaceVariant` | 6.4 / 8.8 |
| Profile avatar (C) | `secondaryContainer` | initial `onSecondaryContainer`; name under it `onSurfaceVariant` on `surface` | 11.7 / 8.2; 7.2 / 9.8 |
| Profile mark in chat subtitle | `secondaryContainer` | `onSecondaryContainer` (decorative; the name beside it is the text) | 11.7 / 8.2 |
| Notices (offline line, compat) | `surfaceContainerHighest` | `onSurface` text; compat icon `error` (non-text) | 11.4 / 11.6 |
| Slider | active `primary`, inactive `surfaceContainerHighest`, stops `onSurfaceVariant` (inactive) / `onPrimary` (active), handle `primary` | | handle 8.9 / 9.0 vs track; stops 5.6 / 6.8 |

The slider's inactive track is only 1.2:1 against the sheet (M3's own default is similar); the handle (8.9:1) and the
stop dots (5.6:1) carry WCAG 1.4.11, and the level name is always written out as text.

## 5. Components → Compose

### 5.1 Lists and home
- **Top bar (A, B)**: `TopAppBar` (no scroll colour change). Title = `Column { wordmark (titleLarge, 600); PC line (bodySmall,
  onSurfaceVariant) }`. Actions: profile chip (`FilterChip`-styled `AssistChip`, `shapes.small`, 1 dp `outlineVariant`
  border, `labelLarge`), A: search `IconButton`; ⋮ `DropdownMenu` (`surfaceContainerHigh`, `shapes.medium`, 1 dp
  `outlineVariant` border; TU1 S6). B drops the search icon because the field is always shown.
- **PC line states**: online "100.74.61.96 · via Tailscale"; offline: cloud-off icon, "Offline since 22:10", inline
  `TextButton` "Retry now" in the same line; one `notice` line under the search: "little-boy stopped answering. This list is
  from 22:09." Compat (Q63) = one notice line with the error icon; What's new (Q64) = one `surfaceContainerLow` notice with
  Dismiss / See. Never more than two lines of notices.
- **Search field** (B): `TextField` with `FieldShape`, `TextFieldDefaults.colors(focused/unfocusedContainerColor =
  surfaceContainerHigh, indicator colours = Transparent)`, leading search icon, trailing clear `IconButton` while non-empty
  (TU1 L5). Height 48 visible (M3 `TextField` min 56 → use `heightIn(min = 48.dp)` with `contentPadding` 12 vertical, or
  the `SearchBarDefaults.InputField` inside a plain `Box`; not `SearchBar` itself, whose expanded mode is full-screen).
- **Live block** (B): grouped rows, 64 dp, `ListItem(colors = containerColor = Transparent)` inside each row `Surface`.
  Supporting line uses design.md's priority (approval > running > count). Trailing "now".
- **History rows** (B): `ListItem` one line, 56 dp (52 dp drawn content + touch), headline `bodyLarge` ellipsis, trailing
  time `bodySmall` + `tnum`. Section headers `labelMedium` `onSurfaceVariant`, 16 dp gutter: Live · N, Pinned on this
  phone, Today, Yesterday, Earlier. Day grouping by the phone's local date of last activity.
- **New chat** (B): `Button(shape = shapes.large, height 56, fillMaxWidth, contentPadding horizontal 24)`, icon
  `edit_square` + `labelLarge` at 16 sp. Sits in a non-scrolling bottom slot of the `Scaffold`, 8 dp above the nav bar.
- **Home C head** (chosen): not a `TopAppBar`; a non-scrolling `Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp,
  bottom = 4.dp), verticalAlignment = Top)`: `Column(Modifier.weight(1f)) { wordmark (Literata 600 28/36); PC line }` and
  the profile avatar (§5.6) at the end. Notices (offline, compat, what's new) sit under it as in B.
- **Live cards** (C): `Card(shape = large, containerColor = surfaceContainerLow)` 300 × ≥132 dp in a `LazyRow` with
  `snapFlingBehavior`; title `titleLarge.copy(18.sp, 24.sp)` 2 lines; last step in mono 12. **Ledger rows** (C): one line,
  `Row(heightIn(min = 52.dp), padding 16 / 14, CenterVertically)`: 56 dp time column (`bodyMedium` 13 sp, `tnum`,
  `onSurfaceVariant`; time today/yesterday, date earlier) + title `bodyLarge` one line ellipsis. **No message count**
  (Q75); drop the `session_messages` plural from the row. **Bottom app bar** (C): `BottomAppBar(actions = { search, Scheduled, ⋮ },
  floatingActionButton = { FloatingActionButton(shape = large) })`; the profile chip is gone (§5.6).

### 5.2 Chat
- **Top bar**: back, title `titleLarge.copy(18.sp, 24.sp)` one line, subtitle one `AnnotatedString` (TU1 C3): tertiary span
  "Running · step 8" + " · " + profile mark + "pm" (§5.6). The model and effort move out of the subtitle into the
  composer chip, so idle reads just the mark + "pm".
- **Assistant text**: no container, full width, chat text 15/23. **User bubble**: `Surface(shape = shapes.large,
  color = secondaryContainer)`, right, max 82 %, padding 14 / 10. Rhythm unchanged (4 / 12 / 20 dp, Q67).
- **Steps line**: `Row` 36 dp visible, 48 dp touch (`minimumInteractiveComponentSize` on the clickable), `labelLarge`
  `onSurfaceVariant`: chevron 20 dp, "5 steps", " · execute_code" in mono 13 (the last tool). Live: spinner 14 dp +
  "Running · N steps" in `tertiary`, the running tool name after it. A failed step adds " · 1 failed" in `error`.
  Collapsed by default. Open: `Surface(shape = medium, color = surfaceContainerLow)` with 44 dp step rows (icon 18,
  name mono 600 13, args mono 13 ellipsized with the TU1 C1 shortener, trailing duration / Failed / spinner); reasoning is a
  step row in italic ("Thought for 2 s", opens its text); tapping a tool row opens its output block below it
  (`surfaceContainerHighest`, `shapes.small`, mono 11.5/17, horizontal scroll, first ~12 lines + "Show all N lines").
  Expand motion as design.md §4.
- **Notification card** (TC1): one line, `shapes.medium`, `surfaceContainerLow`, task icon + text `onSurfaceVariant`.
- **Delegate card**: `Surface(shape = medium, color = surfaceContainerLow)`: header "delegate_task · 2 subagents"
  (`labelMedium`), one 56 dp row per child (status glyph, goal one line, meta line "Done · model · 4 m 12 s" or tertiary
  "Running · 1 m 05 s"), trailing chevron opens the child page (T17).
- **Composer** (`Column` in the `Scaffold` bottom bar, `surface` behind, padding 12 / 6 / 12 / 8):
  - Above the field: waiting chip (`AssistChip`, `errorContainer`, hand icon, "Waiting for your approval", opens the sheet)
    and queued prompts (`Surface(medium, surfaceContainerLow)`: "Queued · 1" + text + ✕).
  - Field: `Surface(shape = ComposerShape, color = surfaceContainerHigh)` holding a `BasicTextField` (bodyLarge, 1–6 lines)
    and an action row: attach `IconButton` (+), model chip, spacer, trailing action.
  - Trailing action by state (Q69): idle → Send (`FilledIconButton`, 40 dp, `SquareIconButtonShape`, `primary`; disabled
    while empty). Busy + empty → Stop (`FilledIconButton`, `errorContainer`). Busy + text → the model chip hides and the
    row reads Stop · Queue (`FilledTonalButton`) · Steer (`Button`), with the hint line under the field. Offline → field
    disabled, "You can send once reconnected".
  - Model chip: `AssistChip(shape = small, colors = containerColor surfaceContainerHighest, border = null)`, label mono 13
    "claude-opus-5-5 · Medium" (ellipsis, max 250 dp), trailing expand icon; opens the model sheet.

### 5.3 Model sheet — and slider vs segmented

Layout: `ModalBottomSheet(containerColor = surfaceContainerLow, skipPartiallyExpanded = true)` →
`Column { head; search field; LazyColumn(Modifier.weight(1f)); EffortFooter }`.
- Head: "Model" `headlineSmall`; "Now: claude-opus-5-5 · Anthropic" `labelMedium`.
- Search: same field as home, placeholder "Search 117 models" (count from the catalog). Filtering is local over
  `ModelCatalog.groups`; every group with a hit opens and shows only the hits, count "3 of 13", match underlined. The
  footer hides while the query is non-empty (the keyboard is up anyway).
- Providers in Hermes' order: header row 56 dp (name `titleMedium`, "· 44 models" `bodySmall`, chevron), collapsed except the
  session's provider (`ModelGroup.matches(provider)`). Provider `warning` shows under its header when opened.
- Model rows 48 dp, mono 14; the current one is a `secondaryContainer` row (`shapes.medium`, inset 12 dp) with a trailing
  check; locked models show "Needs a paid plan" and are not selectable. On open, scroll by the least amount that reveals
  the current row (TU1 S5), so collapsed providers above it stay visible.
- **EffortFooter**: 1 dp `outlineVariant` divider, padding 24 / 12 / 16. Label "Reasoning effort" + the level name
  (`titleMedium`) on one line, the control, end labels "Off" / "Ultra", one note line ("Hermes is working: a change applies
  from your next message." or "This route sends Max." from `ReasoningLevels.clampedTo`). Hidden when
  `ReasoningLevels.choices(caps)` is empty.

**Recommendation: the stepped slider.**
- Real data: `ReasoningLevels.choices` returns **0, 7 or 8 levels** (Off + minimal … ultra, or the same without Off), never
  2–6. Eight segments do not fit one `SingleChoiceSegmentedButtonRow` at 345 dp (43 dp each; "Extra high" needs ~76 dp), so
  the segmented version needs two rows of four, which TalkBack reads as two groups and which breaks the segmented shape.
- The levels are ordered (more effort → slower, costlier). A slider says that; eight equal buttons do not.
- Accessibility: one focusable control; `Modifier.semantics { stateDescription = reasoningLabel(level) }` makes TalkBack read
  "Reasoning effort, Medium"; swipe up/down (and D-pad / volume keys) moves one level; the level name is always written
  in text above, so nothing depends on seeing the handle; the handle's touch area is 48 dp.
- Segmented is better only for ≤ 5 short options with direct tap; keep it in mind if Hermes ever exposes fewer levels.

```kotlin
var index by remember(levels, current) { mutableIntStateOf(levels.indexOf(current).coerceAtLeast(0)) }
Slider(
    value = index.toFloat(),
    onValueChange = { v -> val i = v.roundToInt(); if (i != index) { index = i; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } },
    onValueChangeFinished = { if (levels[index] != current) onPick(levels[index]) },   // one config call per gesture, not per tick
    valueRange = 0f..(levels.size - 1).toFloat(),
    steps = levels.size - 2,
    enabled = !sending,
    modifier = Modifier.semantics { stateDescription = label(levels[index]) },
)
```
(Default `SliderDefaults.colors` already map to primary / secondaryContainer; override `inactiveTrackColor =
surfaceContainerHighest`, `inactiveTickColor = onSurfaceVariant`, `activeTickColor = onPrimary` as in §4.)

### 5.4 Settings
- List page: `LargeTopAppBar` ("Settings" in `headlineMedium` = Literata 500 28/36, a new slot; collapses on scroll to the 22 sp title). Categories (Q72) in
  three grouped blocks: Connection & PCs + Notifications; Appearance + Language; Security + About. Each row 64 dp: 24 dp
  outlined leading icon (`onSurfaceVariant`, no tonal container), title `bodyLarge`, one-line summary `bodyMedium`
  `onSurfaceVariant` (existing `settings_summary_*` strings), trailing chevron.
- Sub-page (Connection & PCs): `TopAppBar` title. Blocks: PC (name `titleMedium`, link state) + fingerprint (mono 15/22,
  4 × 4); "Saved addresses" grouped rows (address mono 13, "source · worked …" or **in use now**, delete `IconButton`; the
  only address has no delete, Q73); Find PC (`FilledTonalButton`, `ButtonShape`) + one line; Connection history row; Pair
  again; Unpair this PC (`error` title, the only red on the page). The fingerprint on the board is a sample.

### 5.5 Approval sheet
Q41 order unchanged: context line → `headlineSmall` question → command block (`shapes.medium`, `surfaceContainerHighest`,
mono 13/19, scrolls both ways) → "Reason Hermes gave" + description → countdown (`tnum`) → Deny (`OutlinedButton`, `error`
content, `outline` border) and Allow once (`Button`) side by side, equal width → `TextButton`s For this session / Always
allow → the Always note. Always allow opens the second step in place (`surfaceContainerHighest` block, Cancel / Always
allow), as T12b already does. Only the shapes change (sheet 28, buttons 12, block 16). Clarify and secret sheets follow the
same frame; their 56 dp choice rows become grouped rows.

### 5.6 Profile switcher (TD2b)

The user's decision: an avatar button top right on Home C opens **the same sheet as today**. Board section 1b.

**Avatar button** (replaces `ProfileChip` in `SessionsScreen.kt`; shown when `state.profileName != null`, enabled only
while `LinkState.Online`, as the chip was):
```kotlin
@Composable
fun ProfileAvatarButton(name: String, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.sessions_profile_description, name)          // "Hermes profile: %1$s" (exists)
    Column(
        modifier = Modifier
            .widthIn(min = 48.dp, max = 80.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button,
                onClickLabel = stringResource(R.string.sessions_profile_change), onClick = onClick)  // new: "Change profile"
            .semantics { contentDescription = description }
            .alpha(if (enabled) 1f else 0.38f)
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ProfileMark(name, size = 40.dp, shape = MaterialTheme.shapes.medium,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold))
        Text(name, style = MaterialTheme.typography.labelSmall                                // 11 sp; 14 lh, 600 here
                .copy(lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp).clearAndSetSemantics { })
    }
}

/** The profile's initial on a Soft square. Decorative: callers carry the name as text or contentDescription. */
@Composable
fun ProfileMark(name: String, size: Dp, shape: Shape, style: TextStyle) =
    Surface(shape = shape, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(size).clearAndSetSemantics { }) {
        Box(contentAlignment = Alignment.Center) { Text(name.trim().take(1).uppercase().ifEmpty { "?" }, style = style) }
    }
```
- Shape: 40 dp square with `shapes.medium` (16 dp Soft) reads "circle-ish" and stays below half its side, so Q39's
  "no pills" holds. Colour `secondaryContainer`, not `primaryContainer`, so it does not compete with the New chat FAB.
- **Label under the avatar (recommended)** over beside / icon only (Tweak "Profile label"): the initial alone is
  ambiguous on this PC (`default` and `developer` are both D); under keeps the wordmark line full width; beside costs
  ~110 dp of the head row and looks like the chip again. Long names ellipsize at 72 dp (`bkm-reports-bot` →
  "bkm-reports…"); the sheet and TalkBack ("Hermes profile: bkm-reports-bot, Button, double-tap to Change profile") have
  the full name. Touch target 48 × 62 dp.
- Offline: disabled at 38 % (no sheet without a roster), same as the chip today.

**Sheet**: `ProfileSheet` in `SessionSheets.kt` keeps its content and behaviour (title "Hermes profile" `titleLarge`, the
body line, a 56 dp radio row per served profile in Hermes' order with the launch profile first, the selection rule, the
loading line, the failure line + Retry now; picking re-lists). Only Soft shapes change:
- Sheet top corners 28 dp: default `ModalBottomSheet` shape = `shapes.extraLarge` once TR1's Soft `Shapes` land; no code.
- `ProfileRow`: add `.clip(MaterialTheme.shapes.medium)` before `.selectable(...)` and `.padding(horizontal = 12.dp)`
  inside, with the sheet column's horizontal padding at 12 dp for the rows (24 dp for title/body), so the radio stays on
  the 24 dp line and the pressed state is a 16 dp Soft shape instead of a hard rectangle.
- Retry button: `ButtonShape` (12 dp), already used.
- **No live session count per profile**: `profiles.list {include_sessions:true}` returns `last_session`,
  `worker_session` and `canonical_session` per served row (`gateway/session_group_controls.py:316-370`), no count, so the
  sheet shows none (brief: "otherwise none").

**Chat top bar**: the profile stays text, last in the subtitle, prefixed by `ProfileMark(name, 16.dp, shapes.extraSmall,
Literata 600 11 sp)` as inline content (`appendInlineContent` + `InlineTextContent(Placeholder(16.sp, 16.sp,
PlaceholderVerticalAlign.TextCenter))`), so the chat echoes the avatar the user tapped on Home. It is not a button: a
chat keeps the profile it was opened in (T14). States: running "◌ Running · step 8 · [P] pm", waiting "✋ Needs your
approval · [P] pm" (error), idle "[P] pm", offline "☁ Offline · [P] pm". `fallbackProfile` (TU1 C4) unchanged.

### 5.7 PC status card (TD3, Q78)

The user: "make the pc status card more beautiful, its kinda bare and rough". Board section 6 draws three options of the
same §16 reading inside the top of Home C (wordmark + avatar above, Live now below), collapsed and opened, light and dark,
with his PC's values (battery 58 %, CPU 27 %, RAM 7.0 / 11.5 GB, disk 34 GB free of 184 GB, Hermes Running, Tailscale Up,
up 28 m, 192.168.100.88 · Wi-Fi), plus charging, Hermes not running and offline "as of 17:40". Tweak "PC card (Home C)"
puts each option into the full Home C screens. What stays: the card's place and rules from today's `PcStatusCard.kt`
(online it replaces the head's PC line and carries the address when opened; offline the head shows the offline line and
the card shows the last values with "as of"; the whole head row is one ≥ 48 dp button read as one sentence).

| | **A — Meters (recommended)** | B — Dials | C — Ledger line |
|---|---|---|---|
| Collapsed | Battery · CPU · RAM, each label 12 / value 16 sp / 4 dp linear bar | Three 48 dp circular dials (percent inside) + a Hermes disc, 11 sp captions | "● Hermes ● Tailscale Up 28 m", then a strip of three 3 dp bars with 13 sp values |
| Opened | 2-column tonal tiles (Disk + bar, Uptime, Hermes, Tailscale), address tile full width | Grouped rows (Disk + bar, Tailscale, Uptime, Address) | Ledger: 72 dp label column, value, 56 dp bar |
| Height (collapsed / opened; today 48 / ~112) | 76 / 294 dp | 110 / 316 dp | 91 / 224 dp |
| Exact RAM GB while collapsed | 16 sp | 11 sp under a 61 % dial | 13 sp |

**Recommendation: A.** It answers "bare" with shape (three bars) without hiding the numbers the user reads today; values
stay 16 sp text, so nothing depends on judging a bar. Collapsed it costs 28 dp over today, B costs 62 dp and turns the
head into a dashboard (rings, a centred cluster) above the live cards that are the page's point; B's RAM dial can only
show a percent, so the GB he knows move to 11 sp. C is the most coherent with the ledger but is still mostly text, the
thing he called bare. A's opened tiles reuse the tonal step of the live cards (`surfaceContainer` on `surfaceContainerLow`).

**Colour roles** (palette A; no verdigris, nothing here is live work; red only for Hermes not running):

| Part | Colour | Contrast (light / dark) |
|---|---|---|
| Card | `surfaceContainerLow`, `shapes.large` (20) | |
| Labels, sub-lines, chevron, tile icons | `onSurfaceVariant` | 6.8 / 9.2 on Low; 6.4 / 8.8 on tiles |
| Values | `onSurface` | 12.8 / 13.6 |
| Bar indicator / track | `primary` / `surfaceContainerHighest` | indicator vs track 8.9 / 9.0; track vs card 1.2 (M3 default; the value is always text) |
| Tiles | `surfaceContainer`, `shapes.small` (12 = 20 − 8 inset, concentric) | |
| Hermes not running (line, tile icon + value) | `error` | 5.3 / 10.2 on Low; 5.0 / 9.7 on tiles |
| Offline (as of) | values `onSurfaceVariant`, bars `outline` (replaces today's 0.6 alpha, which had no contrast floor) | 6.8 / 9.2; bars 3.5 / 4.8 on Low |

**Compose (A)** — replaces the body of `ui/sessions/PcStatusCard.kt`; the signature gains `network` for the address icon:
```kotlin
@Composable
fun PcStatusCard(status: PcStatus, address: String, network: Network?, expanded: Boolean, onToggle: () -> Unit,
                 asOf: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val locale = LocalLocale.current.platformLocale
    val stale = asOf != null
    val sentence = cardSentence(status, locale, asOf)   // compactSentence today + ", Hermes not running" + ", values as of 17:40"
    val toggleLabel = stringResource(if (expanded) R.string.pc_collapse else R.string.pc_expand)
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize()) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
                    .clickable(onClickLabel = toggleLabel, role = Role.Button, onClick = onToggle)
                    .heightIn(min = Sizes.minTouchTarget)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)
                    .clearAndSetSemantics { contentDescription = sentence },
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (asOf != null) AsOfLine(asOf)                                   // history 14 dp + "As of 17:40", labelMedium
                    if (status.hermesRunning == false) HermesDownLine()                // error 18 dp + "Hermes is not running", titleSmall, error
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        status.battery?.let { Meter(stringResource(R.string.pc_battery_label), AnnotatedString("${it.percent}%"),
                            it.percent / 100f, stale, charging = it.charging, modifier = Modifier.weight(1f)) }
                        status.cpuPercent?.let { Meter(stringResource(R.string.pc_cpu_label),
                            AnnotatedString("${PcStatusFormat.percent(it)}%"), (it / 100).toFloat(), stale, modifier = Modifier.weight(1f)) }
                        status.memory?.let { Meter(stringResource(R.string.pc_ram_label), ramValue(it, locale),   // "7.0 / 11.5" + " GB" span (bodySmall, onSurfaceVariant)
                            it.usedBytes.toFloat() / it.totalBytes, stale, modifier = Modifier.weight(1.55f)) }
                    }
                    // battery, CPU and RAM all null: show the uptime here as text ("Up 28 m") so the row is never empty
                }
                val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "pc-chevron")
                Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(Sizes.fieldIcon).rotate(turn))
            }
            AnimatedVisibility(visible = expanded) { DetailTiles(status, address, network, locale, stale) }
        }
    }
}

@Composable
private fun Meter(label: String, value: AnnotatedString, fraction: Float, stale: Boolean, charging: Boolean = false,
                  modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
            if (charging) Icon(painterResource(R.drawable.ic_bolt), null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        Text(value, style = AristotleTextStyles.tabular(MaterialTheme.typography.titleMedium),
            color = if (stale) colors.onSurfaceVariant else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
            color = if (stale) colors.outline else colors.primary,
            trackColor = colors.surfaceContainerHighest,
            strokeCap = StrokeCap.Round, gapSize = 4.dp, drawStopIndicator = {},
        )
    }
}

private data class PcTile(@DrawableRes val icon: Int, val label: String, val value: String,
                          val sub: String? = null, val fraction: Float? = null, val error: Boolean = false)

@Composable
private fun DetailTiles(status: PcStatus, address: String, network: Network?, locale: Locale, stale: Boolean) {
    val tiles = buildList {                  // order: Disk, Uptime, Hermes, Tailscale; a null field drops its tile
        status.disk?.let { add(PcTile(R.drawable.ic_hard_drive, s(R.string.pc_disk_label), s(R.string.pc_disk_free, diskSize(it.freeBytes)),
            sub = s(R.string.pc_disk_of, diskSize(it.totalBytes)), fraction = 1f - it.freeBytes.toFloat() / it.totalBytes)) }
        status.uptimeSeconds?.let { add(PcTile(R.drawable.ic_timer, s(R.string.pc_uptime_label), uptimeValue(it), sub = s(R.string.pc_uptime_since_boot))) }
        status.hermesRunning?.let { add(PcTile(if (it) R.drawable.ic_check_circle else R.drawable.ic_error, s(R.string.pc_hermes_label),
            s(if (it) R.string.pc_hermes_running else R.string.pc_hermes_not_running), error = !it)) }
        status.tailscaleUp?.let { add(PcTile(R.drawable.ic_vpn_lock, s(R.string.pc_tailscale_label), s(if (it) R.string.pc_tailscale_up else R.string.pc_tailscale_down))) }
    }
    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { Tile(it, stale, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (address.isNotBlank()) Tile(PcTile(if (network == Network.Tailscale) R.drawable.ic_vpn_lock else R.drawable.ic_wifi,
            s(R.string.pc_address_label), address), stale, Modifier.fillMaxWidth())   // address part in Mono 500, as on the board
    }
}

@Composable
private fun Tile(t: PcTile, stale: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.small, color = colors.surfaceContainer, modifier = modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(t.icon), null, tint = if (t.error) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(t.value, style = AristotleTextStyles.tabular(MaterialTheme.typography.titleSmall),
                    color = when { t.error -> colors.error; stale -> colors.onSurfaceVariant; else -> colors.onSurface })
                t.sub?.let { Text(it, style = AristotleTextStyles.tabular(MaterialTheme.typography.bodySmall), color = colors.onSurfaceVariant) }
                t.fraction?.let { /* same LinearProgressIndicator as Meter, padding(top = 6.dp) */ }
            }
        }
    }
}
```
- `s(...)` = `stringResource`; `diskSize` / `uptimeValue` wrap `PcStatusFormat` (unchanged). `SessionsScreen` passes
  `network = (state.link as? LinkState.Online)?.endpoint?.network`; the rest of its call site is unchanged.
- TalkBack: head row = one button, "PC status. Battery 58 percent, processor 27 percent, memory 7.0 of 11.5 gigabytes
  used. Hermes not running. Values as of 17:40." + "double-tap to Show more PC details". Each tile is one merged node
  ("Disk, 34 GB free, of 184 GB"); bars and icons are decorative (the value is always text).
- Motion unchanged: `animateContentSize` + `AnimatedVisibility` (expand + fade), chevron 180° at 300 ms, emphasized
  decelerate (design.md §4). No colour thresholds on high CPU / low disk / low battery: red is errors only.
- Desktop PC (battery null): CPU and RAM meters share the row (weights 1 : 1.55).
- **New icons** (Material Symbols Outlined, 24 dp vectors): `ic_hard_drive`, `ic_timer`, `ic_vpn_lock`, `ic_wifi`,
  `ic_history`. Existing: `ic_bolt`, `ic_error`, `ic_check_circle`, `ic_expand_more`.
- **New strings** (EN; ID needed): `pc_battery_label` "Battery", `pc_cpu_label` "CPU", `pc_ram_label` "RAM",
  `pc_ram_gb` "%1$s / %2$s" (+ " GB" span), `pc_disk_label` "Disk", `pc_disk_free` "%1$s free", `pc_disk_of` "of %1$s",
  `pc_uptime_label` "Uptime", `pc_uptime_value_dh` / `_hm` / `_m` "%1$d d %2$d h" / "%1$d h %2$d m" / "%1$d m",
  `pc_uptime_since_boot` "since boot", `pc_address_label` "Address", `pc_hermes_down_line` "Hermes is not running",
  `pc_as_of_title` "As of %1$s", `pc_say_title` "PC status", `pc_say_as_of` "values as of %1$s". Today's `pc_cpu_value`,
  `pc_ram_value`, `pc_disk_value` and `pc_uptime_*` ("Up 28 m") become unused.
- Tests: `data/PcStatusTest.kt` (parser + `PcStatusFormat`) is untouched; add a Compose UI test for the sentence with
  Hermes down + as-of and one for the battery-null row (there is no card UI test today).

## 6. Spec / design.md changes this needs (PM)

| Where | Change |
|---|---|
| Q39, design.md §3 | Replace the radius table with the Soft column of §3; add `FieldShape`, `ComposerShape`, `SquareIconButtonShape`, grouped-row shape. Keep "no pills" (radius < half height). |
| Q68, design.md §5 tool card | A run of consecutive work items (reasoning + tool calls, any length, including 1) is one "N steps" line; opened = one container of step rows; output opens per step; " · N failed" in error on the line. |
| design.md §5 composer | Floating field (§5.2); model chip lives in the composer; busy + text hides the chip and shows Stop · Queue · Steer (Q69 kept). |
| design.md §5 session row | Home B: history rows one line (title + time), counts on long press; live rows keep the status line. |
| Q70 | Replaced by Q75 + TD2b: Home C head = wordmark + PC line + profile avatar (top right); search, Scheduled, ⋮ and New chat in the bottom app bar. |
| design.md §5 profile chip | Becomes the profile avatar (§5.6); profile sheet keeps its content, Soft shapes; chat subtitle gets the 16 dp profile mark. |
| design.md §5 model sheet (T15) | §5.3: search, collapsible providers, effort footer with slider; one `config.set` per gesture. |
| design.md §4 elevation | Composer field `surfaceContainerHigh` (was the `surfaceContainer` panel); grouped rows `surfaceContainerLow`. |
| strings (EN + ID) | New: "Change profile" / "Ganti profil" (avatar `onClickLabel`), "New chat", "%d steps", "Running · %d steps", "%d failed", "Search %d models", "%d models", "%1$d of %2$d", "Live · %d", "Was waiting at %s". |

## 7. Home: the three options

| | A — conservative | **B — strong fit (recommended)** | C — divergent |
|---|---|---|---|
| Composition | Today's list: top bar with search icon, 72 dp two-line rows, day groups, extended FAB | Search field on top, live sessions in one grouped block, one-line history by day, full-width New chat at the bottom | Live sessions as wide cards with their latest step, history as a ledger with a time column, all controls in a bottom app bar |
| Answers the user | Little: same shapes, tidier | Yes: looks like the apps he named, New chat in the thumb zone, live work stands apart | Partly: very different, more one-handed, but further from ChatGPT/Claude |
| Build cost | Small (TU3-sized) | Medium | Medium-large; changes Q70 |
| Risk | "Still looks the same" | Hides message counts | Cards scroll sideways; more to learn |
| **User's pick (Q75)** | | | **Chosen**, without message counts; profile avatar top right (TD2b) |

## 8. Slop self-audit (claude-design ten tells)

| # | Tell | First pass | After repair |
|---|---|---|---|
| 1 | Tech gradient | no | no |
| 2 | Generic tech hue | no (palette A) | no |
| 3 | Feature-tile grid | no; C's live cards are a list of real sessions with live state, not tiles | no |
| 4 | Accent rail | no | no |
| 5 | Unearned blur | no | no |
| 6 | Monument stat | no | no |
| 7 | Icon topper | no; Settings has 24 dp leading icons in-row (scanning aid), no tonal icon containers | no |
| 8 | Center stack | no (only the New chat button's label is centred) | no |
| 9 | Default type | no (Literata + Atkinson) | no |
| 10 | Wrong surface | no (Operate / Configure, §1) | no |

TD2b re-check (avatar, sheet, chat mark): no new tells; the avatar is a monogram button in a row, not an icon topper
above a heading, and uses palette A roles.

TD3 re-check (PC card, board section 6): no gradient, blur, accent rail or icon topper; dials are 48 dp with 13 sp inside
(no monument stat). Closest call is #3 on A's opened tiles (icon + label + value, 2 × 2); kept because each tile is a real
reading of different weight (disk carries a bar, Hermes can turn red), icons sit in-row at 20 dp, and the tiles are the
secondary layer under the meters. First pass had a layout bug, not slop: C's strip collided with the chevron column and
its dot line wrapped with a dangling separator; the strip now spans the card and the line wraps by group. Score 0/10.

**Score 0/10 (first pass and after).** Bugs fixed during review (not slop): approval status lost its red in list rows
(CSS specificity), fingerprint grid overflowed (now 4 × 4 as design.md says), the model sheet scrolled the collapsed
providers out of view (now minimal scroll), New chat button not full width, chip truncating "Medium".

## 9. Answers (Q75, TD2b)

1. Home: **C**. 2. Corners: **Soft**. 3. Effort: **slider**. 4. Steps line: yes, reasoning + tools, even one call.
5. No message count on history rows. 6. Profile: avatar top right + today's sheet (§5.6). Open: the profile label
   position (recommended under; Tweak on the board) for the user to confirm on the phone.

## 10. Suggested build tickets (after the choice)

- **TR1 Theme**: `Shape.kt` (Soft), new shapes, grouped-row helper, slider colours. design.md §3/§4 update.
- **TR2 Home C**: `sessions/SessionsScreen.kt` (head row + `ProfileAvatarButton`, bottom app bar, remove `ProfileChip`),
  `SessionRows.kt` (one-line ledger rows, no count), `SessionSheets.kt` (`ProfileRow` Soft press shape),
  `OfflineBanner.kt` → notice lines; chat subtitle profile mark in `ChatScreen.kt` with TR3/TR4.
- **TR3 Chat transcript**: steps line + step list (`ChatItems.kt`, `ToolFolding.kt` grouping incl. reasoning), delegate and
  notification cards.
- **TR4 Composer**: floating field, model chip, Send/Stop/Queue/Steer states (`Composer.kt`, `ChatComposer.kt`).
- **TR5 Model sheet**: search, collapsible providers, effort footer + slider (`ModelSheet.kt`); unit-test filtering and
  the one-call-per-gesture rule.
- **TR6 Settings + sheets**: grouped rows in `SettingsScreen.kt` / `SettingsConnection.kt`; shapes in `PromptSheet.kt`.
- **TR7 PC card** (after the user picks in board section 6): `PcStatusCard.kt` per §5.7, the `network` argument in
  `SessionsScreen.kt`, five icons, strings EN + ID.
Each ticket re-shoots its screens on the Poco against this board.
