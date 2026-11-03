# Aristotle — design notes (TD1 DRAFT)

Status: **draft for the user's review** (spec Q37, Q38). Once the user picks, the PM turns this into `docs/design.md`,
which every Compose ticket (T07, T09–T19) follows. Board: `docs/design/mockups.html`. Icons: `docs/design/icon-concepts.html`.

Everything here can be built with Material 3 Compose components plus a custom `MaterialTheme` (colorScheme, typography,
shapes). No blur, no gradients, no web-only effects.

## 1. What the screens are for

| Screen | Surface | The one decision on it |
|---|---|---|
| Pair, battery guide | Configure | "Is this my laptop?" / "Are the settings done?" |
| Sessions | Operate | Which session needs me now (running, waiting for approval) |
| Chat | Operate | Let it run, steer it, queue a follow-up, or stop it |
| Approval / clarify sheets | Operate (modal) | Answer the prompt, fast, with the thumb |
| Connection states | Configure | What is wrong and the one thing that fixes it |

So: no hero, no cards grid, no dashboard numbers. Lists, a chat transcript, sheets, and left-aligned state pages.

## 2. Colour

Two identities (Q38 open). Both map to the same M3 `ColorScheme` roles, so the app code is identical and a setting can
switch between them. **Role rules (both identities):**

- `primary` = the main action on a screen (Pair, Allow once, Steer, Answer).
- `tertiary` = **live work only**: running spinner and "Running · step N", the streaming cursor. Nothing else uses it, so
  colour on the session list always means "something is happening".
- `error` = destructive or blocking: Deny, Stop, a failed tool call, "Needs your approval", invalid code.
- `secondaryContainer` = the user's own messages and tonal buttons (Queue, Scan QR).
- Surfaces are tonal (`surfaceContainer*`). Shadows only on the FAB and on bottom sheets.

### A — Aristotle (from the engraving)
Parchment and iron-gall ink from the plate; buttons are "printed" in ink in light mode and in parchment in dark mode.
One accent: **verdigris**, the green of weathered bronze statues, used only for live work. It sits opposite the sepia on the
colour wheel, so it reads at a glance, and it leaves red free for errors.

| Role | Light | Dark |
|---|---|---|
| primary / onPrimary | `#3B2E23` / `#F8F0E1` | `#E8D6B8` / `#2F241A` |
| primaryContainer / onPrimaryContainer | `#E7D7BC` / `#2B2119` | `#4B3D30` / `#F3E4CA` |
| secondary | `#6E5843` | `#D6C2A6` |
| secondaryContainer / onSecondaryContainer | `#EADCC6` / `#2B2119` | `#4A3C2E` / `#F0E0C8` |
| tertiary / onTertiary | `#2C6A5C` / `#FFFFFF` | `#8BD0BC` / `#00382D` |
| tertiaryContainer / onTertiaryContainer | `#BFE3D6` / `#0B2A23` | `#1C4F43` / `#A9EDD8` |
| error / onError | `#B3261E` / `#FFFFFF` | `#FFB4A9` / `#690003` |
| errorContainer / onErrorContainer | `#F7D9D2` / `#5A110A` | `#8C1D13` / `#FFDAD4` |
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
| scrim | `#000000` (32 % light, 50 % dark) | |

Contrast (WCAG, computed): onSurface/surface 13.6 light, 14.5 dark; onSurfaceVariant/surface 7.2 / 9.8;
tertiary/surface 5.5 / 10.4; error/surface 5.7 / 10.9; onPrimary/primary 11.6 / 10.6; outline/surface 3.7 / 5.1. All text pairs ≥ 4.5.

### B — Material You
On Android 12+ the app uses `dynamicLightColorScheme(context)` / `dynamicDarkColorScheme(context)` from the wallpaper.
The values on the board are a **plausible sample** for a blue-grey wallpaper (standard M3 tonal-spot output for a blue seed),
not the user's real wallpaper colours; they are also the fallback scheme below Android 12 (the Poco runs Android 14).

| Role | Light | Dark |
|---|---|---|
| primary / onPrimary | `#34618E` / `#FFFFFF` | `#A0CAFD` / `#003258` |
| primaryContainer / onPrimaryContainer | `#D1E4FF` / `#001D36` | `#194975` / `#D1E4FF` |
| secondary / secondaryContainer / onSecondaryContainer | `#535F70` / `#D7E3F7` / `#101C2B` | `#BBC7DB` / `#3B4858` / `#D7E3F7` |
| tertiary / tertiaryContainer | `#6B5778` / `#F2DAFF` | `#D6BEE4` / `#523F5F` |
| error / errorContainer / onErrorContainer | `#BA1A1A` / `#FFDAD6` / `#410002` | `#FFB4AB` / `#93000A` / `#FFDAD6` |
| surface / onSurface / onSurfaceVariant | `#F8F9FF` / `#191C20` / `#43474E` | `#111418` / `#E1E2E8` / `#C3C7CF` |
| surfaceContainerLowest…Highest | `#FFFFFF` `#F2F3FA` `#ECEEF4` `#E6E8EE` `#E1E2E8` | `#0C0E13` `#191C20` `#1D2024` `#282A2F` `#33353A` |
| outline / outlineVariant | `#73777F` / `#C3C7CF` | `#8D9199` / `#43474E` |

With dynamic colour the "live" role becomes whatever the wallpaper gives `tertiary`; it may sit close to `primary` on some
wallpapers. That is the trade-off of B.

## 3. Type

| Family | Role | Why |
|---|---|---|
| **Literata** (Google Fonts, variable, opsz + wght) | Display: wordmark, screen and sheet titles (`headline*`, `titleLarge`) | A book face made for screens (Google Play Books). It has Greek, keeps the engraving's 19th-century bookish voice, and stays readable at 18 sp on a phone, which most display serifs do not. |
| **Atkinson Hyperlegible Next** (Google Fonts) | UI sans: everything else | Built by the Braille Institute so that 0/O, 1/l/I and similar pairs never get confused. Aristotle shows pairing codes, fingerprints, IPs and session times; the server already treats O→0 and I/L→1 as the same, so the UI should make them distinct. |
| **Atkinson Hyperlegible Mono** | Code: commands, tool output, fingerprint, pairing code | Same family as the UI sans, so code inside sentences does not jump in size or colour. |

All three load through Compose downloadable fonts (`GoogleFont.Provider` with `R.array.com_google_android_gms_fonts_certs`),
falling back to `FontFamily.Serif` / `SansSerif` / `Monospace` if Play services cannot fetch them. The "Roboto Flex" tweak on
the board shows the stock M3 type for comparison. Note: Atkinson's zero is slashed (`0`), also in times like 22:09.

M3 `Typography` slots (sp, line height, weight):

| Slot | Font | Size / line | Weight | Used for |
|---|---|---|---|---|
| headlineSmall | Literata | 24 / 32 | 500 | Pair title, sheet titles, state page titles |
| titleLarge | Literata | 22 / 28 | 500 (wordmark 600) | Wordmark, profile sheet title. Chat top bar uses `titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp)` because session titles are long |
| titleMedium | Atkinson | 16 / 24 | 600 | Clarify question |
| titleSmall | Atkinson | 14 / 20 | 600 | Card and banner titles |
| bodyLarge | Atkinson | 16 / 24 | 400 | List item headlines, text fields, composer |
| bodyMedium | Atkinson | 14 / 20 | 400 | Body text, supporting copy |
| bodySmall | Atkinson | 12 / 16 | 400 | Field supporting text, hints |
| labelLarge | Atkinson | 14 / 20 | 600 | Buttons |
| labelMedium | Atkinson | 12 / 16 | 600 | Section headers, "Thought for 4 s", sheet context line |
| labelSmall | Atkinson | 11 / 16 | 500 | (not used yet) |
| — chat text | Atkinson | 15 / 23 | 400 | Assistant and user messages: `bodyLarge.copy(fontSize = 15.sp, lineHeight = 23.sp)` |
| — code | Atkinson Mono | 13 / 18 inline, 11.5 / 17 in tool output, 17 / 24 fingerprint | 400–500 | |

Display slots (`display*`, `headlineLarge/Medium`) are not used: a phone remote has no screen that needs them.
Times and durations use tabular figures (`fontFeatureSettings = "tnum"`).

## 4. Shape, spacing, elevation, motion

**Shape** (`Shapes`): A uses "cut stone" corners, B the M3 defaults. Shape is separable from colour if the user wants to mix.

| Token | A | B | Used for |
|---|---|---|---|
| extraSmall | 4 dp | 4 dp | Text fields |
| small | 6 dp | 8 dp | Chips, code blocks |
| medium | 10 dp | 12 dp | Tool cards, banners, fingerprint card |
| large | 14 dp | 16 dp | FAB, message bubbles |
| extraLarge | 24 dp | 28 dp | Bottom sheets, composer top |
| buttons | 8 dp | full (pill) | All `Button` variants (pass `shape =` explicitly in A) |

Where a rounded block sits within 8 dp of its container's edge, keep the corners concentric: inner radius = outer radius − inset.

**Spacing**: 4 dp grid. Screen gutters 16 dp (lists, chat) and 20–24 dp (forms, sheets). Between related items 8 dp,
between groups 16–20 dp. Touch targets ≥ 48 dp (M3 minimum), even when the visible chip/button is 32–40 dp.

**Elevation**: tonal only. Level 0 surface for lists and chat, `surfaceContainerLow` for cards and sheets,
`surfaceContainer` for the composer, `surfaceContainerHighest` for banners and code. Shadow elevation only on the
extended FAB (level 3) and `ModalBottomSheet` (default).

**Motion** (M3 motion tokens):
- Sheets: M3 default `ModalBottomSheet` enter/exit. Tool card and reasoning expand: `AnimatedVisibility(expandVertically + fadeIn)`,
  `MotionTokens.DurationMedium2` (300 ms), emphasized-decelerate in, standard-accelerate out, chevron rotates 180°.
- Only **live states** move on their own: the running spinner (`CircularProgressIndicator`, 14 dp, 2 dp stroke) and the
  reconnecting `LinearProgressIndicator`. Both stop the moment the state ends. The streaming cursor does not blink.
- Button press: M3 ripple. No custom animation on list taps or typing.
- Respect "Remove animations" (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`): show the spinner as a static arc.

## 5. Component rules

**Session row** (`ListItem`, two lines, 72 dp): headline = title (1 line, ellipsis); supporting = state, in this priority:
"Needs your approval" (`error`, hand icon) > "Running · step N" (`tertiary`, spinner, from `running` + `agent.step`) >
"N messages" (`onSurfaceVariant`). Trailing = last activity time, or "now" while running. Pinned rows get a pin as leading icon.
Offline: no spinners, a running row says "Was running at HH:MM".

**Profile switcher**: `FilterChip`-style chip in the top bar showing the active profile name; tap opens a `ModalBottomSheet`
with a radio list of `served_profiles` names. Switching re-lists sessions for that profile.

**Pair**: Scan QR is the tonal primary path; manual entry below a divider. Fields are `OutlinedTextField`; the code field is
mono, shown in two groups of 4, case-insensitive. After the app reaches the address it shows the fingerprint card: 16 groups
in a 4 × 4 grid, mono 17 sp, with "They don't match" (text) and "They match, pair" (filled). On a Tailscale (100.x) address
the card is replaced by one line: no certificate there, WireGuard encrypts. Error `401` sets the code field's error state with
"Invalid or expired pairing code" and the help line; the fingerprint stays confirmed. `403` (wrong network) is a different
message on the address field: "This address doesn't accept your phone's network; try the other address" (draft, not on the board).

**Chat transcript**: user messages in `secondaryContainer` bubbles, right-aligned, max 82 % width. Assistant text has no bubble,
full width. Inline code gets `surfaceContainerHigh` and the mono font. While streaming, a `tertiary` block cursor ends the text.
The list keeps itself scrolled to the bottom unless the user has scrolled up.

**Reasoning row**: a `labelMedium` text button "Thought for N s" with a chevron; expands to the reasoning text in italic
`onSurfaceVariant`. Collapsed by default. While `thinking.delta` is arriving it reads "Thinking…".

**Tool card** (`Surface(shape = medium, color = surfaceContainerLow)`): header row 48 dp min = tool icon, tool name (mono 600),
one-line summary of the args (mono, ellipsis), trailing meta (duration with `tnum`; "Failed" in `error` when `is_error`),
chevron. Tap toggles. Body: output in a `surfaceContainerHighest` block, mono 11.5 sp, no wrapping, horizontal scroll,
first ~12 lines plus a "Show all N lines" text button. Failed output lines (`FAIL`, `--- FAIL`) use `error`.
Cards are collapsed by default except the last failed one.

**Composer**: `surfaceContainer` panel with the top corners at `extraLarge`.
- Idle: text field + Send.
- Busy (Q10): text field, then a row: **Stop** (outlined, `error` content, stop icon) on the left, away from the others;
  **Queue** (tonal) and **Steer** (filled) on the right. One hint line under it explains Steer vs Queue. Above the field, the
  queue from `session.info.pending` ("Queued · 1 — also run gofmt -l .", with ✕ = `prompt.cancel`). Never touches `busy_input_mode`.
- Disconnected: field disabled with "You can send once reconnected", Send disabled.

**Approval sheet** (`ModalBottomSheet`, not dismissible by swipe while pending): context line (session · profile),
headlineSmall "Run this command?", the command in a mono block, "Reason Hermes gave" + the `description`, a countdown from
`timeout_s` (tabular). Buttons: bottom row **Deny** (outlined, `error`) and **Allow once** (filled), side by side where the
thumb is; below them the wider grants **For this session** and **Always allow** as text buttons, with one line explaining
Always. Only the choices present in `choices` are shown. The same prompt also arrives as a notification with actions (T12).

**Withdrawn** (`approval.settled` / `clarify.settled` for a prompt this phone did not answer): the sheet swaps to
"Answered elsewhere" + "It was answered on another device, timed out, or the turn ended", command dimmed, only Close; it closes
itself after ~3 s if untouched. If our own tap loses the race (`already_resolved`): snackbar "Answered on another device first".

**Clarify sheet**: same frame; the question in titleMedium; a radio list of `choices` (56 dp rows) + "Other answer"
(opens a text field); Answer (filled). Multi-select uses checkboxes instead.

**Banners** (custom `Surface(shape = medium, color = surfaceContainerHighest)`, below the top bar, not a snackbar):
- PC offline: cloud-off icon, "PC offline since HH:MM", "little-boy stopped answering. This list is from HH:MM.", Retry now.
- Reconnecting: `LinearProgressIndicator`, "Reconnecting… try N, next in S s", cause line ("Lost the connection at 22:14 on Wi-Fi.
  Also trying Tailscale."). Transcript stays readable.

**Connection state pages** (replace the list, keep the top bar): left-aligned headline, one paragraph with cause + the one
fix, a mono Details block with the close code and reason (for bug reports), one button.
- 4503 `hermes_not_running`: "Hermes isn't running" — Retry now (filled).
- 4505 `hermes_protocol_unsupported`: "Hermes was updated" — Check again (tonal; retrying won't help until the server is updated).

**Battery guide** (T10): numbered steps; step 1 (battery optimisation) is detected by the app and shows "Done"; HyperOS
battery saver and Autostart can't be read, so each has "Open settings" + an "I've done this" checkbox.

**Strings**: all UI copy in `strings.xml` + `values-in/strings.xml` (Q28). Agent and user content (messages, tool output,
clarify questions and choices, approval descriptions) is never translated. Indonesian uses Android's own vocabulary where it
exists ("Sandingkan" for pair, as in Bluetooth settings). All copy on the board is draft.

## 6. Slop self-audit (claude-design ten tells)

Run on the board after the first full render, then repaired and re-scored.

| # | Tell | First pass | After repair |
|---|---|---|---|
| 1 | Tech gradient | no | no |
| 2 | Generic tech hue | B only: blue primary. Kept on purpose, the brief asks for a blue-grey Material You sample. A uses verdigris. | B only (by request) |
| 3 | Feature-tile grid | no | no |
| 4 | Accent rail | borderline: 1 dp left rule on expanded reasoning text | removed; italic + muted colour only |
| 5 | Unearned blur | no | no |
| 6 | Monument stat | no | no |
| 7 | Icon topper | yes: 32 dp glyph above the 4503/4505 headlines | removed; the headline carries it |
| 8 | Center stack | no (state pages are left-aligned) | no |
| 9 | Default type | no (Literata + Atkinson chosen; Roboto Flex is an opt-in comparison) | no |
| 10 | Wrong surface | no (Operate / Configure, see §1) | no |

Also removed (antislop-ui): a decorative dot on the profile chip that marked nothing.
**Score: 3/10 first pass → 0/10 for A, 1/10 for B (tell 2, requested).**

## 7. Open questions for the user

1. Identity A or B (Q38)? Or A as the default with a switch to B in settings?
2. A's buttons: cut-stone 8 dp corners or M3 pills?
3. Keep Literata + Atkinson, or plain Roboto Flex? (Atkinson's slashed zero shows in times.)
4. Approval layout: Deny + Allow once side by side at the bottom, rarer grants as text buttons. OK?
5. Icon: concept A (seated), B (hand on chin) or C (coin head) to take into T20.
