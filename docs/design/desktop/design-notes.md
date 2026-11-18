# Aristotle Server (desktop) — design notes

Board: `docs/design/desktop/mockups.html` (open it directly in a browser; fonts are the local files in `fonts/`, no network).
Screenshots: `docs/design/desktop/shots/`. Status: **draft for the user's approval (DSD)**. Nothing here is a spec decision yet.

**Surface: Operate / Monitor.** A small control panel: read the server's state, then act on it. No hero, no tiles, no charts, no
invented numbers. Every value comes from `docs/protocol.md` §22 (fixtures in `server/internal/app/testdata/json/`) or is labelled
a sample (log attribute values, the `api_origin`, the host name `cachyos`).

Brand base is the Android app's binding `docs/design.md` (palette A, Atkinson + Literata, Soft radii, tonal surfaces, no gradients,
no blur, no glow). Deviations are listed under "Deviations from design.md" and need a decision.

## 1. Window

- Start size 960 x 640, minimum proposed 720 x 520. At 720 the content area is 504-552 px wide; every view reflows through
  container queries (two columns become one under 600 px, the QR tile shrinks from 248 to 208 px under 600 px). Views scroll
  vertically inside the window; nothing scrolls sideways except the logs when "Wrap lines" is off.
- Title bar is KDE's (native decorations); the board draws one only to show the 32 px it costs.
- Light and dark follow the system (P7); no in-app switch. The board's Theme toggle only simulates it.
- Language follows the system locale (P10/D16); the board's EN/ID toggle only simulates it.

## 2. Tokens

CSS variables in `:root[data-theme]`, copied 1:1 from `docs/design.md` §1 (M3 role → CSS variable). Components use only the
variables; no hex in component CSS (except the tray and notification mock panels, which imitate KDE, not the app).

| M3 role (design.md) | CSS variable | Light | Dark |
|---|---|---|---|
| background = surface | `--surface` | `#F5EEDF` | `#17130F` |
| onSurface | `--on-surface` | `#2B2119` | `#EDE3D1` |
| onSurfaceVariant | `--on-surface-v` | `#5C4B3C` | `#CBBBA4` |
| surfaceContainerLowest | `--c-lowest` | `#FFFBF3` | `#110E0B` |
| surfaceContainerLow | `--c-low` | `#F0E7D5` | `#1F1A15` |
| surfaceContainer | `--c` | `#EBE1CD` | `#231E19` |
| surfaceContainerHigh | `--c-high` | `#E5DAC4` | `#2D2721` |
| surfaceContainerHighest | `--c-highest` | `#DFD3BB` | `#38312A` |
| outline / outlineVariant | `--outline` / `--outline-v` | `#8A7763` / `#D3C4AA` | `#96846F` / `#4B4035` |
| primary / onPrimary | `--primary` / `--on-primary` | `#3B2E23` / `#F8F0E1` | `#E8D6B8` / `#2F241A` |
| primaryContainer / on | `--primary-c` / `--on-primary-c` | `#E7D7BC` / `#2B2119` | `#4B3D30` / `#F3E4CA` |
| secondaryContainer / on | `--secondary-c` / `--on-secondary-c` | `#EADCC6` / `#2B2119` | `#4A3C2E` / `#F0E0C8` |
| tertiary / onTertiary | `--tert` / `--on-tert` | `#2C6A5C` / `#FFFFFF` | `#8BD0BC` / `#00382D` |
| tertiaryContainer / on | `--tert-c` / `--on-tert-c` | `#BFE3D6` / `#0B2A23` | `#1C4F43` / `#A9EDD8` |
| error / onError | `--err` / `--on-err` | `#B3261E` / `#FFFFFF` | `#FFB4A9` / `#690003` |
| errorContainer / on | `--err-c` / `--on-err-c` | `#F7D9D2` / `#5A110A` | `#8C1D13` / `#FFDAD4` |
| inverseSurface (unused so far) | `--inverse` / `--on-inverse` | `#3A3029` / `#F5EEDF` | `#EDE3D1` / `#2B2119` |
| scrim | `--scrim` | `#000` @ 32 % | `#000` @ 50 % |
| **new** warn (see deviations) | `--warn` | `#8A5300` | `#E8B04F` |
| **new** warn container / on | `--warn-c` / `--on-warn-c` | `#F4DFA8` / `#3B2600` | `#4E3A12` / `#FBE2AE` |
| QR tile (both themes: ink on near-white, a QR must not invert) | `--qr-ink` / `--qr-bg` | `#2B2119` / `#FFFBF3` | same |

Tray colours (not app tokens, they sit on KDE's panel): green `#1F7A5F` / `#5FD1AE`, amber `#B26A00` / `#F2B33D`, red `#C8261C` / `#FF6B5E`
(light panel `#EFF0F1` / dark panel `#31363B`).

Contrast, computed (WCAG, same formula as design.md), new pairs only; palette A pairs are in design.md:

| Pair | Light | Dark |
|---|---|---|
| warn / surface | 5.48 | 9.46 |
| warn / surfaceContainerLow | 5.15 | 8.84 |
| onWarnContainer / warnContainer | 10.91 | 8.56 |
| tray green / panel (non-text, needs 3) | 4.59 | 6.52 |
| tray amber / panel | 3.71 | 6.56 |
| tray red / panel | 4.91 | 4.37 |

Trap kept from design.md: light `tertiary` and `error` on `surfaceContainerHighest` are below 4.5, so banners and the log box (Highest)
use `onSurface` text and put colour only on the shape beside it; failed log lines use `errorContainer` + `onErrorContainer`,
warnings use `warnContainer` + `onWarnContainer`. Light `warn` on `Highest` is 4.27 (below 4.5) and on `High` 4.57; on Highest it is used for the triangle shape only
(non-text, needs 3), text there is `onSurface`; warn text sits on `surface` or `Low` (5.48 / 5.15).

Role rules applied to state (needs decision 4): the **state light** is a shape + a word, never colour alone: green circle
(`tertiary`) = running and Hermes reachable; amber triangle (`warn`) = running but Hermes not reachable or the check failed;
red square (`error`) = stopped, not installed, or an unusable `terminal.json`. The same three shapes are the tray badges.

## 3. Type

Same faces as the Android app (design.md §2), so the desktop app looks like the same product.

- **Atkinson Hyperlegible Next** 400/500/600: all UI text. **Atkinson Hyperlegible Mono** 400/500/600: ids, addresses, log lines,
  pairing code, fingerprint. Both OFL (`docs/licences/OFL.txt`), the TTFs are already in `android/app/src/main/res/font/` and are
  copied into `docs/design/desktop/fonts/` for the board. In the Tauri app: convert to woff2, ship as local `@font-face`
  (offline, no CDN, fits the strict CSP of P4). Why: legible at 12-14 px on a 1x panel, distinguishes `0/O` and `1/l/I` in ids and
  fingerprints, and it is already the brand.
- **Literata** (variable) for titles and the wordmark only. **Not available on this PC, so the board falls back to the system
  serif**: the title shapes in the screenshots are not Literata's. If the user accepts it, the app bundles Literata variable woff2
  (about 100-150 KB, OFL, needs a download the user approves; open choice 10). Fallback if declined: Atkinson 600 for titles.
- Slashed zero is accepted (design.md). Counters and countdowns use `tnum`.

| Slot | Font | Size / line (px) | Weight | Use |
|---|---|---|---|---|
| view title | Literata | 22 / 28 | 500 | `h1` per view, dialog titles |
| state headline | Literata | 18-20 / 24-26 | 500 | "Server running", shell band, empty-state titles |
| body | Atkinson | 14 / 20 | 400 | everything default |
| button | Atkinson | 14 / 20 | 600 | |
| label / group header | Atkinson | 12 / 16 | 600 | group headers, field labels, table heads, tags |
| small / hint | Atkinson | 12 / 16 | 400 | support text |
| mono | Mono | 13 / 18 | 400 | addresses, ids, code blocks |
| log | Mono | 12.5 / 18 | 400 | journal lines |
| fingerprint | Mono | 14 / 22 | 500 | 4 x 4 groups |
| pairing code | Mono | 28 / 36 (24 / 32 under 600 px) | 500 | two groups of 4 |

Minimum text size 12 px (desktop equivalent of the 11 sp floor). Honour the system font scale (`rem`-based in the app; the board uses px for fidelity).

## 4. Shape, spacing, elevation, motion

- Radii from design.md: 4 (grouped inner corners, tags), 12 (buttons, fields, code blocks), 16 (banners, cards), 20 (grouped-row outer
  corners, empty states, srow), 28 (dialogs). No pills, no circles. Buttons 36 px high (32 compact), radius 12.
- Spacing: 4, 8, 12, 16, 20, 24, 32. Content padding 24 (16 compact). Grouped rows 2 px apart, 48 px high (38 compact).
- Elevation is tonal. The only shadow is the dialog's (M3 default for dialogs). Windows, panes and tiles have borders, not shadows.
- Motion: the only moving things are live states: the countdown bar and its text (1 s steps), the indeterminate bar while Start /
  Stop / Restart is running (`tertiary`). Dialogs and nav change instantly. `prefers-reduced-motion` stops the bar. State is always
  also written as text.

## 5. Shell options (navigation only; the six views are identical in all three)

| # | Name | Composition | For | Against |
|---|---|---|---|---|
| 1 | Conservative | six tabs in one row under the title bar | closest to a Qt settings window, trivial to build, best at 720 px | state is visible only on the Status tab |
| 2 | **Strong fit (recommended)** | 196 px sidebar (168 px at 720), views grouped **Server** (Status, Service, Logs) and **Phones** (Pair, Devices, Terminal), live counts (Devices 2, Terminal On/Off), **status footer on every view** | answers "is it up?" from any view; grouping matches how the user thinks | 196 px less content; at 720 px Pair and Devices get tight (handled, see §1) |
| 3 | Divergent | a status band above the tabs: state sentence, version, Hermes, and **Restart / Stop** (or Start) always at hand | fastest to act | puts Stop on every screen (accident risk, D15 confirm still guards it); Status view loses its headline and gets thin |

Navigation is real: every nav item opens a view; keyboard order is nav, then content; `aria-current="page"` marks the open view.

## 6. Components per view

Shared: **Grouped rows** (design.md §3), **State light** (shape + word), **Button** (primary / tonal / outline / outline-danger / filled-danger /
text, 36 px), **Switch** (`role=switch`, 40 x 22, radius 12, text label beside it; square-cornered thumb to obey "no circles"), **Banner** (Highest surface, `onSurface` text, no
left rail), **Dialog** (`alertdialog`, scrim, focus starts on Cancel, Esc and scrim click cancel, Tab stays inside), **Code block**
(Highest), **Empty state** (title + cause + the one action), **Needs-server state** (shown by Pair, Devices, Terminal when the server is down).

| View | Real states drawn | Components |
|---|---|---|
| **Status** | running + Hermes reachable; running + `not-running` with the server's reason; running + `error` ("no answer within 2s"); server stopped (`server: not-running`); service not installed (P3, `enabled: not-found`); version mismatch banner (P5) | state headline row (+ Start when stopped), groups Server / Listening on / Phones, install command with Copy (no install button, P3), mismatch banner above everything |
| **Service** | running; Stop confirm; Restart confirm; restarting (indeterminate bar, buttons off); stopped; not installed | state row, state group (unit, state, starts at login), Start / Stop / Restart with the reason a button is off written under them |
| **Logs** | follow on ("Live" with shape); follow off with "N new lines" + Jump to latest; server stopped (journal ends) | Follow switch, Wrap switch, raw journal lines (prefix dimmed, level word bold, WARN and ERROR on tinted rows), capped-buffer note |
| **Pair** | before a code (optional name, Show pairing code); code + QR + countdown (04:12) + fingerprint 4x4 + addresses; expired (tile says so, no QR kept); no listener (`qr_error` shown as Details, no code shown) | QR tile (always dark on near-white), pairing code in two groups of four with Copy, countdown text + determinate bar, fingerprint card, address list with kind / iface / default route |
| **Devices** | list (name, id, paired, last seen, terminal grant); empty; revoke confirm (D15); server stopped | table in grouped-row form, Revoke… per row, Pair a phone shortcut |
| **Terminal** | off; on with nothing yet; on with one grant and one open terminal; on + `warning`; `problem` (switch disabled, Check again); D7 enable dialog; D7 grant dialog | switch + description, granted phones list with Remove grant and a "Grant to" select, open terminals (phone name resolved from `device_id`, listener, since, pid, id), banner for `problem` and `warning`, D7 dialog |

**D7 dialog:** title, a `errorContainer` box with the CLI's own warning ("A granted phone gets a shell with FULL access to your
account: files, SSH keys, Hermes. Revoking the phone later does not undo what was done."), Cancel (outlined, focused) and one filled
**error** button ("Turn on remote terminal" / "Grant full access"). No "don't ask again", no checkbox. A notification follows the change (D7, drawn).

**Tray (D4, D14, D17):** the monochrome glyph from `docs/design/icon/aristotle-b2-monochrome.svg` (the fold is a real hole), tinted by state
(green / amber / red) plus a state-shaped badge top right, where the glyph has no ink. Drawn at 16, 22, 24 and 32 px on Breeze light and
dark panels, plus 16 px at 4x. **Honest reading of the 1x drawings:** at 22 px and up the thinker and the badge shape are readable;
at 16 px the glyph is a small blob and the circle / triangle / square are 3-4 px, so there **colour does the work and the shape
cue is weak**. Open choice 5. Menu: Open (bold, default), Pair a phone… (disabled when the server is down), separator, Start server or Stop server, separator, Quit.

**Notifications (D5, D7):** Plasma popup, app icon, "Aristotle Server", title, one-line body, Open. EN and ID for all four D5 texts plus the D7 one. None contains a code, QR payload or token (P11): bodies use only device names from `devices list`.

## 7. Strings

All app strings are in the board's `I18N` object (`en` and `id`, 170 keys each, parity checked in a script; 12 keys are identical on purpose:
Status, Terminal, Server, Port, LAN, Tailscale, Hermes, Unit, PID, app name). It is the draft of the frontend message files (P10); the tray menu,
tooltip and notification keys (`m_*`, `tip_*`, `n_*`) are the draft of the Rust-side table. Indonesian is written for reading ("Ponsel melihat
“Hermes tidak berjalan” sampai Hermes dinyalakan"), second person "kamu", not word-for-word. Technical field names (`qr_error`, `problem`, `warning`) are shown raw on purpose.

## 8. Deviations from design.md (need a decision)

1. **Amber is not in palette A.** Added `warn` / `warnContainer` for "Hermes unreachable" (D4 asks for an amber tray state). Alternative: no amber in the window, the triangle + words only.
2. **Role rules stretched by one component.** design.md reserves `tertiary` for live work and `error` for failures. The state light uses `tertiary` for "running" (it is live) and `error` for "stopped / not installed", to match D4's green / red tray.
3. **Icons:** design.md says Material Symbols everywhere. The desktop UI uses none (text navigation, text buttons) so nothing to bundle; only the tray and notification icon are images.
4. **Dark error button:** in dark theme M3 `error` is `#FFB4A9` (salmon), so the D7 "red button" reads salmon on dark, red on light. Alternative: `errorContainer` fill `#8C1D13` with `onErrorContainer` text (darker, redder, 7.1:1).

## 9. Open choices for the user (numbered, with my recommendation)

1. **Shell:** 1 tabs / **2 grouped sidebar + status footer (recommended)** / 3 status band. Tweaks → Shell flips the live window.
2. **Density default:** **comfortable (48 px rows, recommended)** or compact (38 px). Compact stays as a Tweak; I would not make it a user setting in v1 (P3 says no settings editor).
3. **Add an amber `warn` token** to the desktop palette: **yes (recommended)**, or words + triangle only.
4. **Let the state light use `tertiary` for running and `error` for stopped**: **yes (recommended)**, it is one component, always shape + word.
5. **Tray at 16 px:** **A (recommended): ship tint + badge as drawn, and have the icon ticket draw a heavier 16 px variant (head and hand only)**, user approves on the real panel; B: tint only, no badge (colour only, fails colour-blind users); C: badge only, neutral glyph.
6. **Pair view and when a code is minted:** **opening the view shows "Show pairing code" (recommended; opening it never issues a code)**; the tray's "Pair a phone…" opens the view and issues one immediately. Alternative: always issue on open (one click less, a code per glance, each logged).
7. **Log window:** keep the last **2,000 lines** (proposal, not from the spec), **wrap on by default (recommended)**, a switch for no-wrap with horizontal scroll.
8. **"Turn off" for the remote terminal has no confirm** in D15, but it kills open terminals' processes (the CLI says so). **Recommend a D15-style confirm only when `open` is non-empty**; drawn without it for now.
9. **Dialog focus** starts on Cancel for every destructive confirm, incl. D7. Recommended; stated here so it is a decision, not an accident.
10. **Literata for titles:** **bundle Literata variable woff2 (recommended, matches Android; needs the user to approve one font download)** or Atkinson 600 for titles (zero new assets).
11. **Not installed** is drawn from `enabled: "not-found"` in `status --json`. That value is what newer systemd prints for an unknown unit; **ASSUMED, not run on this PC**. DS tickets must check it, or the server should expose a `installed` flag.
12. **Stop in the band (shell 3) and in the tray** are one click from any screen; both go through the D15 confirm. If shell 3 wins, consider hiding Stop behind the Service view.

## 10. Slop self-audit (claude-design rubric, 10 tells, 0 = clean)

| # | Tell | Fired? | Evidence |
|---|---|---|---|
| 1 | Tech gradient | no | no gradient anywhere |
| 2 | Generic tech hue | no | parchment, ink, verdigris from palette A; only addition is an amber for a real state |
| 3 | Feature-tile grid | no | grouped rows with different weights; no equal tiles; Status leads with the state, not a grid |
| 4 | Accent rail | no | banners have no left rail; state sits in a shape + word |
| 5 | Unearned blur | no | none; one shadow, on dialogs only |
| 6 | Monument stat | no | the biggest numbers are the pairing code and countdown, which are the task |
| 7 | Icon topper | no | no icons above headings; no icons in the window |
| 8 | Center stack | no | left-aligned everywhere; only dialogs are centred (scrim convention) |
| 9 | Default type | no | Atkinson + Literata, chosen and justified |
| 10 | Wrong surface | no | Operate / Monitor, named first; state first, action next to it |

**Score 0 / 10.** Weak spots the rubric does not catch (my own reading): Literata is not rendered on the board (system serif fallback);
the tray glyph at 16 px is weak (§6, choice 5); the window is a lot of beige by design (palette A), so hierarchy relies on type and
grouping, not colour; Service and Status overlap in shell 3 on purpose.
Checklist (antislop-ui): no decorative dots (every shape marks a state), no fake numbers (fixtures or marked samples), empty / error
states name cause and action, nav items all open a real view, motion has a purpose and stops under reduced motion.

## 11. Verified / not verified

See `docs/desktop/runs/DSD/build-report.md`.
