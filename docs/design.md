# Aristotle — design spec (binding)

Source: spec Q37–Q42, approved from `docs/design/design-notes.md` + `mockups.html` (palette A). Every Compose UI ticket
(T07, T09–T19) follows this. Built only from Material 3 Compose + a custom `MaterialTheme` (colorScheme, typography, shapes).
No blur, no gradients, no web-only effects. Deviations need a spec decision.

## 1. Colour (palette A, Q38)

**No dynamic colour.** No `dynamicLightColorScheme`/`dynamicDarkColorScheme`, no Material You option, no settings switch.
Theme follows the system light/dark by default; Settings › Appearance can force Light or Dark (Q60). Text size Small/Default/
Large scales app text 0.9/1.0/1.15, but no text renders below 11 sp (clamp). (T07 removes the T02 shell's dynamic colour.)

| M3 role | Light | Dark |
|---|---|---|
| primary / onPrimary | `#3B2E23` / `#F8F0E1` | `#E8D6B8` / `#2F241A` |
| primaryContainer / on | `#E7D7BC` / `#2B2119` | `#4B3D30` / `#F3E4CA` |
| secondary | `#6E5843` | `#D6C2A6` |
| onSecondary | `#FFFFFF` | `#2F241A` |
| secondaryContainer / on | `#EADCC6` / `#2B2119` | `#4A3C2E` / `#F0E0C8` |
| tertiary / onTertiary | `#2C6A5C` / `#FFFFFF` | `#8BD0BC` / `#00382D` |
| tertiaryContainer / on | `#BFE3D6` / `#0B2A23` | `#1C4F43` / `#A9EDD8` |
| error / onError | `#B3261E` / `#FFFFFF` | `#FFB4A9` / `#690003` |
| errorContainer / on | `#F7D9D2` / `#5A110A` | `#8C1D13` / `#FFDAD4` |
| background = surface | `#F5EEDF` | `#17130F` |
| onBackground = onSurface | `#2B2119` | `#EDE3D1` |
| onSurfaceVariant | `#5C4B3C` | `#CBBBA4` |
| surfaceContainerLowest | `#FFFBF3` | `#110E0B` |
| surfaceContainerLow | `#F0E7D5` | `#1F1A15` |
| surfaceContainer | `#EBE1CD` | `#231E19` |
| surfaceContainerHigh | `#E5DAC4` | `#2D2721` |
| surfaceContainerHighest = surfaceVariant | `#DFD3BB` | `#38312A` |
| outline / outlineVariant | `#8A7763` / `#D3C4AA` | `#96846F` / `#4B4035` |
| inverseSurface / inverseOnSurface | `#3A3029` / `#F5EEDF` | `#EDE3D1` / `#2B2119` |
| inversePrimary | `#E8D6B8` | `#3B2E23` |
| scrim | `#000000` @ 32 % | `#000000` @ 50 % |

Also set `surfaceDim`/`surfaceBright` = `surface` / `surfaceContainerHighest`, `surfaceTint` = `primary`.

**Role rules**
- `primary` = the main action on a screen (Pair, Allow once, Steer, Answer, Send). Buttons are ink on parchment (light), parchment on ink (dark).
- `tertiary` (verdigris) = **live work only**: running spinner, "Running · step N", streaming cursor, reconnecting progress bar. Nothing else, ever: colour on a list must mean "something is happening". Not for links, selection, toggles, icons at rest.
- `error` (red) = Deny, Stop, failed tool call, "Needs your approval", invalid input. Nothing else is red.
- `secondaryContainer` = the user's own message bubbles and tonal buttons (Queue, Scan QR, Check again).
- Surfaces are tonal (`surfaceContainer*`). No hard-coded colours in composables; always `MaterialTheme.colorScheme.*`.

**Contrast (WCAG, computed; all ≥ 4.5:1 for text)**

| Pair | Light | Dark |
|---|---|---|
| onSurface / surface | 13.6 | 14.5 |
| onSurfaceVariant / surface | 7.2 | 9.8 |
| onPrimary / primary | 11.6 | 10.6 |
| onTertiary / tertiary | 6.3 | 7.4 |
| tertiary / surface | 5.5 | 10.4 |
| error / surface | 5.7 | 10.9 |
| onError / error | 6.5 | 7.7 |
| onSecondaryContainer / secondaryContainer | 11.7 | 8.2 |
| onPrimaryContainer / primaryContainer | 11.1 | 8.3 |
| onTertiaryContainer / tertiaryContainer | 11.1 | 7.0 |
| onErrorContainer / errorContainer | 10.4 | 7.1 |
| onSurfaceVariant / surfaceContainerHighest | 5.6 | 6.8 |

Non-text: outline / surface = 3.7 light, 5.1 dark (≥ 3:1, borders and field outlines only; never for text).
**Trap:** light `tertiary` and `error` on `surfaceContainerHighest` are 4.3 and 4.4 — below 4.5. On banners and code blocks
(Highest) use `onSurface`/`onSurfaceVariant` for text; put tertiary/error only on `surface`, `surfaceContainerLow` or as a
non-text icon/indicator. Failed output lines in a Highest block: use `errorContainer`-tinted line with `onErrorContainer` text
in light mode instead of `error` text.

## 2. Type (Q40)

- **Literata** (variable, opsz + wght): wordmark and titles — `headlineSmall`, `titleLarge`.
- **Atkinson Hyperlegible Next**: all other UI text.
- **Atkinson Hyperlegible Mono**: commands, tool output, pairing code, fingerprint, close-code details.
- Slashed zero is accepted. Times/durations: `fontFeatureSettings = "tnum"`.

Loading (Q66): Atkinson Hyperlegible Next and Mono are **bundled** (OFL, `docs/licences/OFL.txt`) as static `res/font`
TTFs 400/500/600, from the upstream `googlefonts/atkinson-hyperlegible-next(-mono)` repos; the Play-services provider does
not serve them. Literata stays a Compose downloadable font, `GoogleFont.Provider("com.google.android.gms.fonts",
"com.google.android.gms", R.array.com_google_android_gms_fonts_certs)`, weights 400/500/600. Each `FontFamily` ends in a
device fallback: `serif` (Literata), `sans-serif` (Atkinson), `monospace` (Mono). Debug builds log font load failures.

| Slot | Font | Size / line (sp) | Weight | Use |
|---|---|---|---|---|
| headlineSmall | Literata | 24 / 32 | 500 | Pair title, sheet titles, state-page titles |
| titleLarge | Literata | 22 / 28 | 500 (wordmark 600) | Wordmark, profile sheet title; chat top bar uses 18 / 24 |
| titleMedium | Atkinson | 16 / 24 | 600 | Clarify question |
| titleSmall | Atkinson | 14 / 20 | 600 | Card and banner titles |
| bodyLarge | Atkinson | 16 / 24 | 400 | List headlines, text fields, composer |
| bodyMedium | Atkinson | 14 / 20 | 400 | Body, supporting copy |
| bodySmall | Atkinson | 12 / 16 | 400 | Field support text, hints |
| labelLarge | Atkinson | 14 / 20 | 600 | Buttons |
| labelMedium | Atkinson | 12 / 16 | 600 | Section headers, "Thought for 4 s", sheet context line |
| labelSmall | Atkinson | 11 / 16 | 500 | spare |
| chat text (derived) | Atkinson | 15 / 23 | 400 | `bodyLarge.copy(15.sp, 23.sp)` messages |
| code inline | Mono | 13 / 18 | 400 | |
| code tool output | Mono | 11.5 / 17 | 400 | |
| fingerprint | Mono | 17 / 24 | 500 | |
| pairing code | Mono | 22 / 28 | 500 | two groups of 4 |

`display*`, `headlineLarge/Medium` unused. Never use `fontSize` below 11 sp. Honour system font scale; layouts must not clip at 1.3×.

## 3. Shape (Q39, replaced by Q75: Soft)

Rounded, **no pills**: each radius is below half its control's height. Nothing uses `CircleShape` or `RoundedCornerShape(50)`
except the avatar-free spinner and the radio/checkbox controls M3 draws itself. Full v2 notes: `docs/design/v2/design-notes.md`
(board `docs/design/v2/mockups.html`).

| Token | Radius | Use |
|---|---|---|
| extraSmall | 4 dp | Inner corners of grouped rows, inline code |
| small | 12 dp | Chips (profile, model, waiting), code/output blocks, segmented outer |
| medium | 16 dp | Step list container, cards (delegate, notification, notice), command block, selected model row, menus |
| large | 20 dp | User bubble, grouped-row outer corners, live cards, New chat button, FABs |
| extraLarge | 28 dp | `ModalBottomSheet` top corners, dialogs |
| `ButtonShape` | 12 dp | Every `Button`/`FilledTonalButton`/`OutlinedButton`/`TextButton`: pass `shape = ButtonShape` explicitly |
| `FieldShape` | 16 dp | Search fields (home, model sheet) |
| `ComposerShape` | 24 dp, all corners | The floating composer field |
| `SquareIconButtonShape` | 12 dp | 40 dp Send / Stop square buttons |

Nested rounded blocks within 8 dp of the container edge: inner radius = outer − inset.

**Grouped rows** (`ui/components/GroupedRows.kt`): a list section is a column of `Surface` rows (`surfaceContainerLow`,
`onSurface`) 2 dp apart. Outer corners `large`, inner corners `extraSmall`: first = top outer, last = bottom outer, middle = all
inner, a single row = all outer (`groupedRowShape(index, count)`). Used for the live block, Settings and the Connection
sub-page, choice rows in sheets.

**Slider** (`ui/components/AristotleSlider.kt`): stepped, for ordered levels (reasoning effort: 7 or 8 levels). Active track and
handle `primary`, inactive track `surfaceContainerHighest`, stops `onSurfaceVariant` (inactive) / `onPrimary` (active). The level
name is always written as text above the slider (`titleMedium`) and set as `stateDescription`; end labels are optional. Haptic
tick per level, and the callback fires once per gesture end, only if the level changed (one `config.set` per gesture).

## 4. Spacing, elevation, motion

**Spacing** (4 dp grid): 4, 8, 12, 16, 20, 24, 32 (2 dp only between Markdown list items). Gutters 16 dp (lists, chat),
20–24 dp (forms, sheets). Related items 8, groups 16–20. Row heights: session row 72, list/choice row 56, tool card header
48 including its padding, reasoning row 32 visible.

**Controls (Q67)**: touch targets are ≥ 48 dp and come from Material's `minimumInteractiveComponentSize`; never stretch the
visible control with `heightIn(48)`. Visible sizes stay Material's: buttons 40, chips 32.

**Chat rhythm (Q67)**: the gap above a transcript item depends on the item before it: 4 dp between consecutive work items
(tool / reasoning / notice, `Spacing.chatWorkGap`), 12 dp between a message and work (`chatMessageGap`), 20 dp before a user
bubble (`chatTurnGap`).

**Elevation**: tonal only. Lists/chat = `surface`; cards and sheets = `surfaceContainerLow`; composer field = `surfaceContainerHigh`; grouped rows = `surfaceContainerLow`;
banners and code = `surfaceContainerHighest`. Shadows only on the extended FAB (level 3) and `ModalBottomSheet` (default).

**Motion**
- Expand/collapse (tool card, reasoning): `expandVertically + fadeIn`, 300 ms; in = emphasized-decelerate
  `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)`, out = standard-accelerate `CubicBezierEasing(0.3f, 0f, 1f, 1f)` at 200 ms;
  chevron rotates 180° over the same time.
- Sheets: M3 default `ModalBottomSheet` motion. Button press: M3 ripple. No custom animation on list taps or typing.
- Only live states self-animate: running spinner (`CircularProgressIndicator`, 14 dp, 2 dp stroke) and the reconnecting
  `LinearProgressIndicator`. They stop the moment the state ends. The streaming cursor does not blink.
- **Reduced motion**: if `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, all transitions are instant (duration 0, no
  fade), the spinner renders as a static 270° arc, and the progress bar as a static determinate bar at 40 %. State changes are
  conveyed by text, never by motion alone.

## 5. Components

**Session row** (`ListItem`, 72 dp, 2 lines): headline = title (1 line, ellipsis). Supporting text priority:
"Needs your approval" (`error`, hand icon) > "Running · step N" (`tertiary`, spinner) > "N messages" (`onSurfaceVariant`).
Trailing: last-activity time, "now" while running. Pinned: pin as leading icon. Offline: no spinners; running rows read
"Was running at HH:MM".

**Profile chip**: `FilterChip` (small shape) in the top bar with the active profile name only — no status dot. Tap opens
`ModalBottomSheet` with a radio list of `served_profiles`; selecting re-lists sessions.

**Pairing code + fingerprint**: code field mono, two groups of 4, case-insensitive; `401` sets field error "Invalid or
expired pairing code" + help line (fingerprint stays confirmed); `403` shows an address-field error. Fingerprint card
(`medium`, `surfaceContainerLow`): 16 groups in a 4 × 4 grid, mono 17/24, `onSurface`; actions "They don't match" (text) and
"They match, pair" (filled). The card shows on every address, Tailscale included (Q49: HTTPS everywhere). Scan QR = tonal
primary path (the fingerprint comes from the QR, so the compare step is skipped and the fingerprint is shown small for
reference); manual entry below a divider.

**Chat transcript**: user messages in `secondaryContainer` bubbles (`large`), right-aligned, max 82 % width; assistant text
no bubble, full width. Inline code `surfaceContainerHigh` + mono. Streaming: `tertiary` block cursor at the text end.
Auto-scroll to bottom unless the user scrolled up. Reasoning row: `labelMedium` text button "Thought for N s" + chevron
(collapsed by default; "Thinking…" while streaming); expanded text italic `onSurfaceVariant`, no side rule.

**Tool card** (`Surface`, `medium`, `surfaceContainerLow`): header ≥ 48 dp = tool icon, tool name (mono 600), one-line args
summary (mono, ellipsis), trailing duration (tnum) or "Failed" (`error`, on `surfaceContainerLow` only), chevron. Tap
toggles. Body: output in `surfaceContainerHighest`, mono 11.5/17, no wrap, horizontal scroll, first ~12 lines + "Show all N
lines" text button. Collapsed by default except the last failed card.

**Composer** (`surfaceContainer`, top corners `extraLarge`)
- Idle: text field + Send (`primary`).
- Busy: text field; row below with **Stop** left (outlined, `error` content, stop icon), **Queue** (tonal) and **Steer**
  (filled) right; one hint line explaining Steer vs Queue. Above the field, queued prompts from `session.info.pending`
  ("Queued · 1 — text", ✕ cancels). Never touches `busy_input_mode`.
- Disconnected: field disabled, "You can send once reconnected", Send disabled.

**Approval sheet** (`ModalBottomSheet`, not swipe-dismissible while pending; Q41): context line (session · profile,
`labelMedium`); `headlineSmall` "Run this command?"; command in a mono block (`surfaceContainerHighest`); "Reason Hermes
gave" + description; tabular countdown from `timeout_s`. Buttons: **Deny** (outlined, `error`) and **Allow once** (filled)
side by side, equal width, in the thumb zone; below, smaller text buttons **For this session** and **Always allow** plus one
line explaining Always. Show only choices present in `choices`. Withdrawn (`approval.settled` not by us): title "Answered
elsewhere", command dimmed, only Close, auto-closes ~3 s; `already_resolved` → snackbar "Answered on another device first".

**Clarify sheet**: same frame; question `titleMedium`; radio list of `choices` (56 dp rows) + "Other answer" (text field);
filled Answer. Multi-select uses checkboxes. Same withdrawn behaviour.

**Banners** (`Surface`, `medium`, `surfaceContainerHighest`, under the top bar; not snackbars; text `onSurface`):
- Offline: cloud-off icon, "PC offline since HH:MM", cause line, "Retry now". No spinners anywhere while offline.
- Reconnecting: `LinearProgressIndicator` (`tertiary`), "Reconnecting… try N, next in S s", cause line. Transcript stays readable.
- Compatibility (Q63): error-coloured `ic_error` icon (non-text, so the 4.4 trap does not apply), one `onSurface` line, no action; shown while the active PC's server and this app's protocols do not meet.
- What's new card (Q64, `Surface`, `medium`, `surfaceContainerLow`, sessions list top, after an update): title `titleSmall`, text buttons Dismiss / What's new; opening the page from the card or from About dismisses it.

**Connection pages** (replace the list, keep the top bar): left-aligned `headlineSmall` headline (no icon above it), one
paragraph = cause + the one fix, mono Details block (close code + reason), one button.
- 4503 `hermes_not_running`: "Hermes isn't running" — Retry now (filled).
- 4505 `hermes_protocol_unsupported`: "Hermes was updated" — Check again (tonal).

**Battery guide**: numbered steps; detectable step shows "Done"; unreadable HyperOS steps get "Open settings" + "I've done
this" checkbox.

**Strings**: UI copy in `strings.xml` + `values-in/strings.xml` (Q28). Agent/user content is never translated.

## 6. Icon (Q42)

Direction: **concept B, hand on chin, line art** (`docs/design/icon-concepts.html`), ink `#2B2119` on parchment `#F3EAD8`.
TD1 rated B weak at 48 dp (fist reads as a blob, thin line fades on dark launchers). **T20 must simplify it** (heavier
stroke, fewer interior lines, larger fist, silhouette first) until it reads at 48 dp on the Poco home screen; supply
adaptive foreground (safe zone 66 dp of 108) + monochrome layer. The user approves on device.

Icons: Material Symbols Outlined, weight 400, 24 dp, everywhere in the UI (launcher/notification icons excepted). (TU2)

App lock screen (TS2) is the second exception to the no-centred-stack rule: icon, app name, one Unlock button, centred.

## 7. Do not

- No gradients, glassmorphism/blur, glow, or decorative shadows.
- No blue/purple "tech" hue; no accent other than verdigris, and verdigris only for live work.
- No icon above state-page headlines; no centered hero stacks; no feature-tile grids or dashboard stat numbers.
  Exception (Q46): the quote loading screen is a centred stack with the static app icon above the quote, the quote set in
  Literata (a reading text, not UI), and a calm line-by-line fade-in with a `tertiary` progress line.
- No accent rails/left borders on text blocks.
- No M3 pill buttons (Soft radii, §3); no `CircleShape` containers.
- No decorative dots, badges, or status colours that mark nothing.
- No default Roboto/Material type; no text below 11 sp; no hard-coded hex in composables.
- No dynamic colour. No animation that is not a live state or an expand/collapse.
- No red except Deny/Stop/errors; no colour as the only signal.
