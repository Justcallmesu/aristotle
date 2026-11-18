# Aristotle Server (desktop) — design spec (binding)

Source: desktop spec D19 (the user's picks of `docs/design/desktop/design-notes.md` §9), board `docs/design/desktop/mockups.html`,
data contract `docs/protocol.md` §22, brand base `docs/design.md` (Android, palette A). Every desktop UI ticket (DS3w, DS4, DS5, DS6,
and the tray/notification parts of DS3) follows this file. The unchosen options (tabs shell, status band, compact density, Tweaks) are
record only, in `docs/design/desktop/`; nothing here implements them. A deviation needs a spec decision.

**Surface: Operate / Monitor.** Read the server's state, then act on it. State first, the action beside it. No hero, no tiles,
no charts, no icons in the window, no invented numbers: every value comes from §22 or is a label.

## 0. Decisions that shape everything (D19)

| # | Binding choice |
|---|---|
| 1 | Shell: **grouped sidebar + status footer**, on every view. |
| 2 | **Comfortable** density only: rows 48, buttons 36, content padding 24, gap 16. |
| 3 | Amber `warn` token added to the palette. |
| 4 | State light: `tertiary` circle = running and Hermes reachable; `warn` triangle = running, Hermes not reachable; `error` square = stopped. Always shape **and** word. |
| 5 | Tray = state tint + state-shaped badge; the icon ticket (DSI) draws a heavier 16 px variant, approved on the real panel. |
| 6 | Pair view opens on **"Show pairing code"** (opening issues no code). Tray "Pair a phone…" opens the view and issues one at once (P14). |
| 7 | Logs keep **2,000 lines**, **wrap on**. |
| 8 | "Turn off" (remote terminal) confirms **only when `open` is non-empty**. |
| 9 | Every destructive dialog **focuses Cancel**. |
| 10 | Titles in **Literata**, body in **Atkinson**. |
| 11 | Replaced by P13: "not installed" reads `installed`, never `enabled`. |

Theme follows the system light/dark (P7); language follows the system locale (P10/D16); neither has an in-app switch.
Window: start 960 × 640, minimum 720 × 520 (from design-notes §1, not a D19 pick), native KDE title bar "Aristotle Server".

## 1. Tokens

Light and dark as CSS custom properties. Components use only these variables; no hex in component CSS. Roles follow `docs/design.md` §1
(M3 name in the comment). The set is copied from the board.

```css
:root{
  --font-ui:"Atkinson Hyperlegible Next",sans-serif;
  --font-title:"Literata","Source Serif 4",Georgia,serif;
  --font-mono:"Atkinson Hyperlegible Mono",monospace;
  --r-xs:4px;--r-s:12px;--r-m:16px;--r-l:20px;--r-xl:28px;
  --sp-1:4px;--sp-2:8px;--sp-3:12px;--sp-4:16px;--sp-5:20px;--sp-6:24px;--sp-8:32px;
  --row:48px;--btn:36px;--pad:24px;--gap:16px;            /* comfortable, the only density */
  --ease-out:cubic-bezier(.05,.7,.1,1);--ease-in:cubic-bezier(.3,0,1,1);
}
:root[data-theme="light"]{
  color-scheme:light;
  --primary:#3B2E23;--on-primary:#F8F0E1;--primary-c:#E7D7BC;--on-primary-c:#2B2119;
  --secondary:#6E5843;--secondary-c:#EADCC6;--on-secondary-c:#2B2119;
  --tert:#2C6A5C;--on-tert:#FFFFFF;--tert-c:#BFE3D6;--on-tert-c:#0B2A23;
  --err:#B3261E;--on-err:#FFFFFF;--err-c:#F7D9D2;--on-err-c:#5A110A;
  --warn:#8A5300;--warn-c:#F4DFA8;--on-warn-c:#3B2600;
  --surface:#F5EEDF;--on-surface:#2B2119;--on-surface-v:#5C4B3C;
  --c-lowest:#FFFBF3;--c-low:#F0E7D5;--c:#EBE1CD;--c-high:#E5DAC4;--c-highest:#DFD3BB;
  --outline:#8A7763;--outline-v:#D3C4AA;
  --inverse:#3A3029;--on-inverse:#F5EEDF;--scrim:rgba(0,0,0,.32);
  --qr-ink:#2B2119;--qr-bg:#FFFBF3;
}
:root[data-theme="dark"]{
  color-scheme:dark;
  --primary:#E8D6B8;--on-primary:#2F241A;--primary-c:#4B3D30;--on-primary-c:#F3E4CA;
  --secondary:#D6C2A6;--secondary-c:#4A3C2E;--on-secondary-c:#F0E0C8;
  --tert:#8BD0BC;--on-tert:#00382D;--tert-c:#1C4F43;--on-tert-c:#A9EDD8;
  --err:#FFB4A9;--on-err:#690003;--err-c:#8C1D13;--on-err-c:#FFDAD4;
  --warn:#E8B04F;--warn-c:#4E3A12;--on-warn-c:#FBE2AE;
  --surface:#17130F;--on-surface:#EDE3D1;--on-surface-v:#CBBBA4;
  --c-lowest:#110E0B;--c-low:#1F1A15;--c:#231E19;--c-high:#2D2721;--c-highest:#38312A;
  --outline:#96846F;--outline-v:#4B4035;
  --inverse:#EDE3D1;--on-inverse:#2B2119;--scrim:rgba(0,0,0,.5);
  --qr-ink:#2B2119;--qr-bg:#FFFBF3;                         /* a QR never inverts */
}
```

Variable ↔ M3 role: `--surface` background = surface · `--on-surface` onSurface · `--on-surface-v` onSurfaceVariant · `--c-lowest/-low/-/-high/-highest`
surfaceContainerLowest…Highest · `--outline`/`--outline-v` outline/outlineVariant · `--primary*`, `--secondary*`, `--tert*`, `--err*` the M3 roles
· `--warn`, `--warn-c`, `--on-warn-c` are **new** (D19-3, not in `docs/design.md`). `--primary-c` and `--inverse*` are defined but unused so far.

**Role rules for state** (D19-4 stretches `docs/design.md` by one component, the state light):
- `tertiary` = running / live (state light circle, the "Live" word, progress fills). Not links, selection or icons at rest.
- `warn` = Hermes not reachable or the check failed (triangle, warning tags, WARN log rows, warning banner shape).
- `error` = stopped, not installed, destructive buttons, failures. Nothing else is red.
- Colour is never the only signal: every state has a shape and a word (§4.3).
- **Trap** (inherited): light `tertiary`, `error` and `warn` text on `--c-highest` are below 4.5 (4.26 / 4.41 / 4.27). Banners and the log box sit on
  Highest, so their text is `--on-surface`, colour only on the shape beside it; failed log rows use `--err-c` + `--on-err-c`, warnings `--warn-c` + `--on-warn-c`.
  `warn` text goes on `--surface` or `--c-low` only.

## 2. Type, spacing, shape, elevation, motion

### 2.1 Type scale

Atkinson Hyperlegible Next 400/500/600 (UI), Atkinson Hyperlegible Mono 400/500/600 (ids, addresses, log, code), Literata variable (titles and wordmark
only). Sizes in `rem` (1 rem = 16 px) so the system font scale applies; px shown at 1×. Minimum text size 12 px. Slashed zero is accepted. Counters and
countdowns use `font-variant-numeric: tabular-nums` (`tnum`).

| Slot | Font | px / line | Weight | Use |
|---|---|---|---|---|
| view title | Literata | 22 / 28 | 500 | the one `h1` per view, dialog titles |
| state headline | Literata | 18 / 24 | 500 | "Server running", state rows, empty-state titles |
| wordmark | Literata | 18 / 24 | 600 | sidebar "Aristotle" |
| body | Atkinson | 14 / 20 | 400 | default |
| button | Atkinson | 14 / 20 | 600 | |
| small button | Atkinson | 13 / 18 | 600 | |
| label / group header | Atkinson | 12 / 16 | 600 | group headers, field labels, table heads, tags |
| small / hint | Atkinson | 12 / 16 | 400 | support text |
| mono | Mono | 13 / 18 | 400 | addresses, ids, code blocks |
| log | Mono | 12.5 / 18 | 400 | journal lines |
| fingerprint | Mono | 14 / 22 | 500 | 4 × 4 groups |
| pairing code | Mono | 28 / 36 (24 / 32 under 600 px content width), letter-spacing .04em | 500 | two groups of 4 |
| countdown | Atkinson | 14 / 20 | 600 | `tnum` |

`body{margin:0;font:400 14px/20px var(--font-ui)}`. `:focus-visible{outline:2px solid var(--primary);outline-offset:2px}`.

### 2.2 Spacing, radii, elevation

- Spacing: 4, 8, 12, 16, 20, 24, 32. Content padding `--pad` 24; related items 8, groups 16 (`--gap`). Grouped rows 2 apart, 48 high.
- Radii: **4** (grouped inner corners, tags, nav items) · **12** (buttons incl. small, fields, code blocks, selected nav item, switch track) · **16** (banners, cards, QR tile, log box, fingerprint card) · **20** (grouped-row outer corners, empty states, state row) · **28** (dialogs). No pills, no circles. (Board small buttons use 8; use 12, see report.)
- Elevation is tonal. The **only** shadow is the dialog's: `0 8px 24px rgba(0,0,0,.28)`. Windows, panes and tiles use 1 px borders (`--outline-v`; `--outline` for controls).
- Surfaces: window `--surface` · sidebar and grouped rows and state row and empty state `--c-low` · dialog `--c-high` · banners, code blocks, log box, dead QR tile `--c-highest` · fields `--surface`.

### 2.3 Motion and reduced motion

Only live states move: the countdown text and its determinate bar (1 s steps) and the indeterminate bar while Start / Stop / Restart runs
(`@keyframes ind{0%{margin-left:-35%}100%{margin-left:100%}}`, fill 35 % wide, `1.4s var(--ease-out) infinite`). Dialogs and navigation change instantly; no hover
animation; button hover is `filter:brightness(.97)`. State is also always written as text.
`@media (prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}`: the indeterminate bar stays a still 35 % fill, the label
("Restarting…") carries the state; the countdown keeps its 1 s text updates (text, not motion).

## 3. Components

### 3.1 Window shell (shell 2)

```
┌──────────────┬───────────────────────────────────────────┐   native title bar (KDE), not drawn by the app
│ Aristotle    │ [banner: version mismatch]                │
│ Server       │ ┌ content (padding 24, scrolls vertically) │
│ SERVER       │ │ h1 view title                           │
│  Status      │ │ …view…                                  │
│  Service     │ └─────────────────────────────────────────┘
│  Logs        │
│ PHONES       │
│  Pair        │
│  Devices  2  │
│  Terminal On │
│ ─────────────│
│ ▲ State word │   status footer, same on every view
│   1.2.3 · …  │
└──────────────┴───────────────────────────────────────────┘
```

- `<aside class="side">` 196 px wide (168 px when the window is under 800 px wide), `--c-low`, `border-right:1px solid var(--outline-v)`, padding `16px 8px 8px`, column.
- Wordmark: "Aristotle" Literata 600 18/24, padding `0 8px`; under it "Server" 12/16 `--on-surface-v`, padding `0 8px 16px` (brand names, not translated).
- Group header (`grp_server`, `grp_phones`): 12/16 600 `--on-surface-v`, padding `12px 8px 4px`. Groups: **Server** = Status, Service, Logs; **Phones** = Pair, Devices, Terminal.
- Nav item: `<button>` in `<nav aria-label=nav_label>`, 36 high, padding `0 8px`, radius 4, label left, count right (12 px, `tnum`, `--on-surface-v`), items 2 apart. Hover `--c-high`.
  Current: `aria-current="page"`, bg `--secondary-c`, text and count `--on-secondary-c`, weight 600, radius 12. Counts: Devices = number of paired phones; Terminal = `t_on` / `t_off`; both empty while the server is not running.
- Status footer: pinned to the bottom (`margin-top:auto`), `border-top:1px solid var(--outline-v)`, padding `12px 8px 4px`, 12/16, `role="status"`.
  Line 1 (600): state light (12 px) + the state headline (§4.1). Line 2 (`--on-surface-v`, indented 19 px): server running → `<version> · Hermes <hs_* word, lowercased>` (`Hermes` literal); otherwise the `svc_*` word of `service`.
- Main: `<main>` = banner slot (§3.4, above the content, margin `var(--pad) var(--pad) 0`) + `.content` (padding `--pad`, `container-type:inline-size`, `overflow:auto`).
- View header: `h1` 22/28 Literata 500; optional subtitle `p` 14/20 `--on-surface-v`, max-width 62ch, margin-top 2; optional right-aligned actions; margin-bottom `--gap`. Wraps under 600 px.
- Two-column layout: `grid-template-columns:1fr 1fr; gap:16px 24px`; **one** column when the content container is under 600 px wide (container query, not a viewport query).
- Views never scroll sideways, except the log box with Wrap off.
- Closing the window exits the window app (D18); Escape does not close it.

### 3.2 State light

12-viewBox SVG, `aria-hidden`, drawn with `fill:currentColor`; **always** followed by a word in the DOM.
`ok` circle `<circle cx="6" cy="6" r="5"/>` colour `--tert` · `warn` triangle `<path d="M6 .8 11.4 10.6H.6Z"/>` colour `--warn` · `err` square `<rect x=".8" y=".8" width="10.4" height="10.4" rx="1.5"/>` colour `--err`.
Sizes: 14 px beside a headline and in banners, 12 px in the footer and table rows, 10 px beside "Live". The tray badges use the same three shapes (§6).

### 3.3 Buttons, fields, switch, tag, progress

| Variant | Fill / text / border | Use |
|---|---|---|
| primary | `--primary` / `--on-primary` | the main action of a view or dialog (Start, Show pairing code, Turn on…) |
| tonal | `--secondary-c` / `--on-secondary-c` | secondary actions (Restart, Pair a phone, Grant…, Copy, Check again) |
| default | `--c-high` / `--on-surface` | rare |
| outline | transparent / `--on-surface` / 1 px `--outline` | Cancel, Make a new code (active code) |
| outline danger | transparent / `--err` / 1 px `--err` | Stop server, Revoke… |
| filled danger | `--err` / `--on-err` | the confirm of Stop, Revoke, D7, Turn off |
| text | transparent / padding `0 10px` | Open Devices, Open Terminal; `text danger` (`--err`): Remove grant |

Button: height `--btn` (36), padding `0 16px`, radius 12, 14/20 600, gap 8, `white-space:nowrap`. Small: 28 high, padding `0 10px`, 13/18, radius 12.
Disabled: `disabled`, opacity .45, `cursor:not-allowed`; the **reason is written beside it** (§4.3 `dis_*`), never only a dimmed button. In dark theme the filled-danger button is salmon (`--err` `#FFB4A9`) with dark text; accepted.
Field / select: height 36, `1px solid var(--outline)`, radius 12, bg `--surface`, padding `0 12px`; label above (12/16 600 `--on-surface-v`, margin-bottom 4), hint below (12/16, margin `4px 0 16px`).
Switch: `<button role="switch" aria-checked>` + a text label beside it (500). Track 40 × 22, radius 12, 2 px `--outline` border, bg `--c-highest`; thumb 12 × 12, radius 4, at `left:3px` (off, colour `--on-surface-v`) / `left:21px` (on, `--on-primary`); on: track `--primary`. Disabled: `aria-disabled="true"`, opacity .5.
Tag: 12/16 600, padding `1px 6px`, radius 4, bg `--c-highest` / text `--on-surface`; `err` = `--err-c`/`--on-err-c`; `warn` = `--warn-c`/`--on-warn-c`; `ok` = `--tert-c`/`--on-tert-c`.
Progress: 4 px high, radius 2, track `--c-highest`, fill `--tert`; `role="progressbar"` with an `aria-label`.
Copy buttons: on success the label becomes `copied` for 1.2 s inside an `aria-live="polite"` region, then reverts.

### 3.4 Banners and code blocks

Banner: bg `--c-highest`, text `--on-surface`, radius 16, padding `12px 16px`, flex gap 12, margin-bottom `--gap`; **no left rail**; a 14 px state light first; title 600 then body; optional actions right. `role="alert"` for blocking problems (version mismatch, `problem`), `role="status"` for `warning`.
Code block: bg `--c-highest`, `--on-surface`, radius 12, padding `10px 14px`, mono 13/18, `overflow-wrap:anywhere`. Used for `./install.sh`, `problem: …`, `warning: …`, `qr_error: …`, `error: …`.

### 3.5 Grouped rows and tables

Group = optional header (12/16 600 `--on-surface-v`, margin `4px 0 6px`) + rows 2 px apart; row bg `--c-low`, min-height 48, padding `6px 16px`, gap 16, radius 4; first row top corners 20, last row bottom corners 20, a lone row 20.
Row = key (120 px wide, `--on-surface-v`; 84 px in the Listening group; 104 px under 600 px) + value (flex, `overflow-wrap:anywhere`) + optional end slot. Column row: key above value, padding `10px 16px`.
Devices table uses the same look: `border-collapse:separate; border-spacing:0 2px`, `th` 12/16 600 `--on-surface-v` padding `0 16px 4px`, `td` bg `--c-low` height 48 padding `8px 16px`; same corner rule (4 inner, 20 outer). Under 600 px content width the Paired and Terminal columns are hidden.
State row (`srow`): bg `--c-low`, radius 20, padding `14px 16px`, flex gap 20 wrap, title Literata 18/24 500 with a 14 px state light, optional action at the end.
Empty state: bg `--c-low`, radius 20, padding `20px 24px`, max-width 560; title Literata 18/24, body `--on-surface-v` (cause), then **the one action**.

### 3.6 Dialogs

`role="alertdialog" aria-modal="true" aria-labelledby=<title id>` over a scrim (`--scrim`, fills the window, padding 24, centred): bg `--c-high`, radius 28, padding 24, width `min(440px,100%)`, the shadow of §2.2.
Title Literata 22/28 500, margin-bottom 12; body paragraphs margin-bottom 12; actions right-aligned, gap 8, margin-top 20: **Cancel** (outline) then the confirm.
Behaviour (D19-9): initial focus on **Cancel**; Tab and Shift+Tab stay inside; Esc, scrim click and Cancel close and send nothing; closing returns focus to the control that opened it; no "don't ask again", no checkbox; the confirm runs once and closes the dialog (the working state shows in the view, §4.2).
Warn box (D7 only): bg `--err-c`, text `--on-err-c` (600), radius 12, padding `10px 14px`, margin-bottom 12.

| Dialog | Opened by | Title | Body | Confirm button |
|---|---|---|---|---|
| stop (D15) | Service "Stop server"; tray "Stop server…" (P14) | `d_stop_t` | `d_stop_b` | filled danger `b_stop` |
| restart (D15) | Service "Restart server" | `d_restart_t` | `d_restart_b` | primary `b_restart` |
| revoke (D15) | Devices "Revoke…" | `d_revoke_t` (`{name}`) | `d_revoke_b` | filled danger `revoke` |
| enable terminal (D7) | switch off→on, "Turn on…" | `d7_enable_t` | warn box `d7_warn`, then `d7_nogrant` only when `granted` is empty | filled danger `d7_enable_b` |
| grant terminal (D7) | "Grant…" | `d7_grant_t` (`{name}`) | warn box `d7_warn` | filled danger `d7_grant_b` |
| turn off terminal (D19-8) | switch on→off **only if `open` is non-empty**; with none open it runs at once | `d_toff_t` | `d_toff_b` (`{n}` = `open` length) | filled danger `t_turn_off` |

Start, Pair, refresh, Remove grant and Check again have no dialog (D15).

### 3.7 QR block and log viewer

- **QR tile**: 248 × 248 (208 × 208 under 600 px), padding 12 (10), radius 16, bg `--qr-bg`, `1px solid var(--outline-v)`, SVG fills the tile, `shape-rendering:crispEdges`, quiet zone 4 modules, ink `--qr-ink`, both themes the same; `role="img" aria-label=qr_label`. Content = `qr_payload` rendered by a QR library (byte mode); the app encodes nothing else (P11).
  **Dead tile** (expired, no address): bg `--c-highest`, dashed `--outline` border, left-aligned text at the bottom, padding 16; title 600, body 13/18.
- **Log box**: bg `--c-highest`, radius 16, padding `8px 0`, mono 12.5/18, fills the space under the log bar (min-height 240), `role="log" tabindex="0" aria-live="off" aria-label=logs_title`. Line: padding `1px 14px`. Wrap on: hanging indent (`padding-left:28px;text-indent:-14px`); wrap off: `white-space:pre`, padding-left 14, horizontal scroll. Journal prefix (`Mon DD HH:MM:SS host unit[pid]:`) `--on-surface-v`; `level=WARN|ERROR|…` token weight 600; a line with `level=ERROR` gets bg `--err-c` + text `--on-err-c`, `level=WARN` bg `--warn-c` + `--on-warn-c`; other lines plain. Lines are shown raw (no parsing beyond the level token), values are escaped text.
  Buffer: last **2,000** lines, oldest drop; a note under the box (`cap_note`, 12/16 `--on-surface-v`, margin-top 8).

## 4. Views

Shared rules for every view:

### 4.1 State model (what each view reads)

One derived `world` from `status --json` (§22) plus the CLI itself, evaluated in this order; the first match wins:

| # | Condition (field) | Headline (title / sub) | Light | Tray |
|---|---|---|---|---|
| 0 | no poll finished yet | `st_loading` / – | none | – |
| 1 | the `aristotle-server` binary cannot be run | `st_nocli` / `sub_nocli` | err | red |
| 2 | `installed == false` | `st_notinst` / `sub_notinst` | err | red |
| 3 | `server == "no-answer"` | `st_noanswer` / `sub_noanswer` | err | red |
| 4 | `server == "not-running"` | `st_stopped` / `sub_stopped` | err | red |
| 5 | `server == "running"` and `service != "active"` (`unknown`, or a server started by hand) | `st_outside` / `sub_outside` | err | red |
| 6 | `server == "running"`, `result.hermes.state == "reachable"` | `st_running` / `sub_ok` | ok | green |
| 7 | `server == "running"`, `hermes.state == "not-running"` | `st_running` / `sub_herm` | warn | amber |
| 8 | `server == "running"`, `hermes.state == "error"` | `st_running` / `sub_hermerr` | warn | amber |
| 9 | `server == "running"`, `result.hermes` absent (old server, first scan) | `st_running` / `sub_hermunk` | warn | amber |

Rows 5 and 9 follow spec **P17** (PM, 2026-11-18): unknown is not healthy, and a hand-started server is not the service.
Any other `hermes.state` word counts as row 8. `result.hermes.reason` (server text, not translated) shows under the Hermes row in mono 12/16.
**Version mismatch** (P5, P17) is a separate flag, set when the app's own build version differs from `version --json`'s `version` OR from the running server's `result.version` (dev builds skip it). It adds a banner (`mm_title`, `mm_body` with `{srv}` and `{app}` = the two versions, warn light 14 px, `role="alert"`) above every view and **disables every action**: Start, Stop, Restart, Show pairing code / Make a new code, Revoke…, the Terminal switch, Turn on…, Grant…, Remove grant. Reading still works; the written reason is `dis_mismatch`.
A systemctl word the string table has no key for (`activating`, …) is shown raw. `service` and `enabled` equal to `"unknown"` use `svc_unknown` / `en_unknown`. "Starts at login" shows `en_not-found` ("Not installed") whenever `installed == false`, whatever `enabled` says (P13).

### 4.2 Action and error states (all views)

- **Working**: while Start / Stop / Restart runs, all three buttons are off, an indeterminate bar (max-width 420, margin-top 14) with `aria-label` and the hint `working_start|working_stop|working_restart` appears; it ends when the command returns and the next poll confirms the state. Before `stop` / `restart` the window writes the P15 stamp.
- **Command failed** (`{"error":…}` or a non-zero exit): a `role="alert"` banner above the view content with `act_failed_t`, `act_failed_b` and a code block `error: <text>` (the CLI's text, shown, never logged); stays until the next action or a view change. A `refused:` error from `stop`/`restart` shows the same way.
- **Needs the running server** (Pair, Devices, Terminal when `server != running`): empty state with `needs_title`, `needs_body` and, only when `installed`, no version mismatch and `service != active`, a primary **Start server**.
- **Empty and failed reads**: a missing value renders `—`; a failed `devices list` / `terminal status` read leaves the last good data and shows `—` where none exists.

### 4.3 Status (default view)

Layout: sr-only `h1` (`nav_status`), state row (headline + sub, Start server primary when `service != active`, not installed → no button, no mismatch), then the two columns.
- Not installed: a group `install_cmd` with code block `./install.sh` + tonal small **Copy** (`copy`); **no install button** (P3). Status then lists the same Server group with "—" values.
- Left column, group `g_server`: `k_service` (`svc_*` word), `k_enabled` (`en_enabled` / `en_disabled` / `en_not-found` / `en_unknown`), `k_version` (version + tag `app_expects` in `err` when mismatched), `k_port` (mono `tnum`, `—` when absent), `k_hermes` (state light + `hs_*` word; reason below).
- Right column: group `g_listen` — one row per `result.listeners[]`: key `kind_<kind>`; value `<ip>:<port>` mono nowrap and below it mono 12 `<iface>[ · <subnet>][ · default route]` (`default_route` from the `addresses[]` entry with the same `iface`); no listener → one row `no_listen` (stopped) or `no_listen_up` (running but `listeners` absent). Then group `g_phones` (only when running): `k_paired` = `paired_summary` (`{n}`, `{t}` = last seen of the most recent device) or `none_paired`, with text button `open_devices`; `k_terminal` = `term_summary_on` (`{open}`, `{granted}`) or `term_summary_off`, with `open_terminal`.
- Drivers: `service`, `enabled`, `installed`, `server`, `result.{version,port,listeners,addresses,hermes}`, `devices list`, `terminal status`.

### 4.4 Service

Layout: `h1` `svc_title` + sub `svc_sub`; state row (state light + headline, no action); group `svc_state`: `svc_unit` = mono `aristotle-server.service` + ` · systemd --user`, `k_service`, `k_enabled`; button row: **Start server** (primary when it is the available action, else default), **Stop server** (outline danger → stop dialog), **Restart server** (tonal → restart dialog); under them the reason hint (12/16, margin-top 10).
Which buttons are on: `service == active` → Stop and Restart on, Start off (`dis_running`); `service` inactive/failed → Start on, Stop and Restart off (`dis_stopped`); any other word (`activating`, `unknown`, …), `installed == false` (`dis_notinst`), mismatch (`dis_mismatch`) or a running command → all off. With `server == no-answer` and `service == active`, Stop and Restart stay on (recovery).
States: running · stop confirm · restart confirm · working (§4.2) · stopped · not installed · server not answering. Drivers: `service`, `installed`, `server`, the command's own `service` word (`start|stop|restart --json`).

### 4.5 Logs

Layout: `h1` `logs_title` + sub `logs_sub`; log bar (flex, gap 20, wrap): switch **Follow** (`follow`, on by default), switch **Wrap lines** (`wrap`, **on**, D19-7), status text; the log box; the cap note. Source: `aristotle-server logs -f`, raw journal lines (P9).
- Follow on: status = 10 px circle in `--tert` + `live` (600, `--tert` on `--surface`), box scrolls to the newest line.
- Follow off (the user scrolled up or flipped the switch): status `paused` (`{n}` = lines that arrived since, 600, `--on-surface`); a primary small **Jump to latest** (`jump`) sits sticky at the bottom centre of the box (`bottom:8px`); pressing it turns Follow on.
- Server stopped (`server != running`): Follow switch disabled, no status text, the last lines stay, hint `logs_stopped` before the cap note. Driver: `server`.
- Stream ended or failed to start: box keeps its lines, `role="alert"` row inside the bar `logs_ended` + tonal small **Reconnect** (`logs_retry`).
- Empty: no lines yet shows nothing but the box (a loading word is not needed; lines arrive at once from the journal).

### 4.6 Pair

Layout: `h1` `pair_title`; states below. Pair needs the running server (§4.2). All buttons off under mismatch.
1. **Before a code** (D19-6): sub `pair_sub`; optional field `pair_name` (max-width 380, placeholder `pixel`, hint `pair_name_hint`; a name starting with `-` shows `pair_name_err` under the field and keeps the button off); primary **Show pairing code** (`pair_show`) runs `pair [--name N] --json`. Opening the view never runs it.
2. **Making a code**: the button shows `pair_busy` and is off.
3. **Code** (`pair --json` succeeded, no `qr_error`): row `gap 24`, column under 460 px. Left: QR tile (from `qr_payload`) + below it, width equal to the tile, countdown `pair_expires` (`{t}` = `mm:ss` to `expires_at`, 14/20 600 `tnum`) and a determinate bar (`aria-valuemin 0`, `aria-valuemax 300`, `aria-valuenow` = seconds left, fill `remain/300`). Right: label `pair_code`, the code in two groups of 4 (`code`, `data-sensitive`) + tonal small **Copy** (copies the raw 8-character `code`); hint `pair_scan`; label `pair_fp` + fingerprint card (16 groups, 4 × 4) + hint `pair_fp_hint`; label `pair_addr` + address rows (`url` mono 13/18; right `kind_<kind> · <iface>[ · default route]` 12/16; 1 px `--outline-v` separators; stacked under 600 px); outline **Make a new code** (`pair_new`).
4. **Expired** (at `expires_at`; the code, QR and fingerprint are dropped from the DOM): dead tile with `pair_expired` + `pair_expired_b` (`{t}` = `HH:MM` of `expires_at`); right: label `pair_addr` is kept with its rows and a primary **Make a new code**.
5. **No listener** (`qr_error` set, `qr_payload` empty): no code is shown. Dead tile `pair_none_t`; right: `pair_none_b`, label `details` + code block `qr_error: <text>`, primary **Try again** (`pair_try`).
6. **Failed** (`error`): §4.2 banner, form stays.
Rules: the code, QR payload and fingerprint exist in memory and DOM only while this view shows them; leaving the view or the window closing drops them (P11). They never reach a notification, the tray, a log line or a panic message. Entry from the tray (`aristotle-desktop --pair`) skips state 1 and goes to 2.

### 4.7 Devices

Layout: `h1` `dev_title` + sub `dev_sub`, tonal **Pair a phone** (`b_pair`, opens Pair state 1) at the right; table (§3.5): `c_name` (name 600 + id mono 12/16 below), `c_paired` (`dmy`), `c_seen` (relative, `title` = ISO), `c_term` (tag `granted` when the id is in `terminal status` `granted`, else `—`), and **Revoke…** (`revoke` + "…", outline danger small → revoke dialog). Rows in the order the CLI returns.
- Empty (`devices == []`): empty state `empty_dev_t` + `empty_dev_b` + primary **Pair a phone** (`b_pair`).
- Server stopped: needs-server state. Mismatch: Revoke… off, `dis_mismatch` hint under the table.
- Time text: `now` under 1 min; `ago_min` under 60 min; same calendar day `today` (`HH:MM`, 24 h, local); otherwise `<date> HH:MM` where date is `Mon D` (EN) or `D Mon` (ID, `MON.id`: Jan Feb Mar Apr Mei Jun Jul Agu Sep Okt Nov Des). Drivers: `devices[].{id,name,created_at,last_seen_at}`.

### 4.8 Terminal

Layout: `h1` `t_title` + sub `t_sub`; state row with the switch (label `t_on`/`t_off`, description `t_on_d`/`t_off_d` 14/20 `--on-surface` indented 50 px) and, when off, primary **Turn on…** (`t_turn_on`); when on, hint `t_off_hint` (12/16) under the row; banners; two columns.
- Switch: off→on opens the D7 enable dialog; on→off runs `terminal disable --json` at once, or opens the turn-off dialog when `open` is non-empty (D19-8).
- Left column, group `t_grants`: rows from `granted[]` (name 600 or `t_gone` when `name` is empty, id mono 12/16, end text-danger small **Remove grant** `t_remove`, no dialog); none → row `t_grants_empty`. Add row (only if any paired phone exists): label `t_grant_to`, select of paired phones without a grant (max-width 240), tonal small **Grant…** (`t_grant`) → grant dialog with the selected name; when every phone has a grant the row is `t_grant_all`. Shown whether the switch is on or off.
- Right column (only when `enabled`), group `t_open`: one row per `open[]`: title = device name from `device_id` (else the raw id), sub `kind_<listener> · HH:MM · dur_min · c_pid <pid>` (`since`), end = terminal `id` mono 12; none → `t_open_empty`.
- `warning` present (after a change): `role="status"` banner, warn light, title `t_warn_t`, code block `warning: <text>`; content stays usable.
- `problem` present: only `role="alert"` banner (err light, `t_prob_t`, `t_prob_b`, code block `problem: <text>`, tonal **Check again** `t_check` re-reads `terminal status`) and the state row with the switch **disabled**; no lists. The app never edits `terminal.json`.
- After a successful enable or grant the window sends the D7 notification (`n_d7_t`/`n_d7_b`).
- Server stopped: needs-server state. Mismatch: switch, Turn on…, Grant…, Remove grant off. Drivers: `enabled`, `granted[]`, `open[]`, `changed`, `warning`, `problem`, `devices list` names.

## 5. Strings (EN + ID)

Source: the board's `I18N` (`en` and `id`, 170 keys each, parity checked) plus **NEW** keys this doc needs (marked ★, see the report) and four **CHANGED** keys (⚠).
Frontend message files take every key except those prefixed `m_`, `tip_`, `n_` and `now_t`, which are the Rust-side table (tray menu, tooltip, notifications; P10).
Params in `{braces}`. Technical field names (`qr_error`, `problem`, `warning`, `error`) are shown raw on purpose. Indonesian is written for reading, second person "kamu". Brand names and "Hermes", "Tailscale", "LAN", "PID" stay as is.

Totals: 170 board keys = 169 kept or changed + 1 dropped; 20 new; **189 keys** in the files. ⚠ = changed from the board (reason in the report), ★ = new. Surface UI = frontend `en`/`id` files, Rust = tray/notification table.

| Key | EN | ID | Surface |
|---|---|---|---|
| `nav_status` | Status | Status | UI |
| `nav_service` | Service | Layanan | UI |
| `nav_logs` | Logs | Log | UI |
| `nav_pair` | Pair | Pasangkan | UI |
| `nav_devices` | Devices | Perangkat | UI |
| `nav_terminal` | Terminal | Terminal | UI |
| `grp_server` | Server | Server | UI |
| `grp_phones` | Phones | Ponsel | UI |
| `app` | Aristotle Server | Aristotle Server | UI |
| `st_running` | Server running | Server berjalan | UI |
| `st_stopped` | Server stopped | Server berhenti | UI |
| `st_notinst` | Service not installed | Layanan belum terpasang | UI |
| `sub_ok` | Hermes is reachable. Phones can connect. | Hermes terjangkau. Ponsel bisa terhubung. | UI |
| `sub_herm` | Phones see “Hermes isn’t running” until it starts. | Ponsel melihat “Hermes tidak berjalan” sampai Hermes dinyalakan. | UI |
| `sub_hermerr` | Hermes did not answer the check. Chats from phones may fail. | Hermes tidak menjawab pengecekan. Obrolan dari ponsel mungkin gagal. | UI |
| `sub_stopped` | The service is inactive. Phones can’t connect until you start it. | Layanan nonaktif. Ponsel tidak bisa terhubung sampai kamu menyalakannya. | UI |
| `sub_notinst` | aristotle-server.service is missing. Run the installer, then reopen this window. | aristotle-server.service tidak ditemukan. Jalankan installer, lalu buka jendela ini lagi. | UI |
| `g_server` | Server | Server | UI |
| `g_listen` | Listening on | Mendengarkan di | UI |
| `g_phones` | Phones | Ponsel | UI |
| `k_service` | Service | Layanan | UI |
| `k_enabled` | Starts at login | Mulai saat login | UI |
| `k_version` | Server version | Versi server | UI |
| `k_port` | Port | Port | UI |
| `k_hermes` | Hermes | Hermes | UI |
| `k_paired` | Paired phones | Ponsel terpasang | UI |
| `k_terminal` | Remote terminal | Terminal jarak jauh | UI |
| `en_enabled` | Yes | Ya | UI |
| `en_disabled` | No | Tidak | UI |
| `en_not-found` | Not installed | Belum terpasang | UI |
| `hs_reachable` | Reachable | Terjangkau | UI |
| `hs_not-running` | Not running | Tidak berjalan | UI |
| `hs_error` | Check failed | Pengecekan gagal | UI |
| `hs_none` | Unknown while stopped | Tidak diketahui saat berhenti | UI |
| `svc_active` | active | aktif | UI |
| `svc_inactive` | inactive | nonaktif | UI |
| `svc_failed` | failed | gagal | UI |
| `kind_lan` | LAN | LAN | UI |
| `kind_tailscale` | Tailscale | Tailscale | UI |
| `default_route` | default route | rute utama | UI |
| `no_listen` | No listener while the server is stopped. | Tidak ada listener selagi server berhenti. | UI |
| `term_summary_on` | On · {open} open · {granted} granted | Aktif · {open} terbuka · {granted} diberi izin | UI |
| `term_summary_off` | Off | Mati | UI |
| `paired_summary` | {n} · last seen {t} | {n} · terakhir aktif {t} | UI |
| `none_paired` | None | Belum ada | UI |
| `install_cmd` | Run in the release folder: | Jalankan di folder rilis: | UI |
| `copy` | Copy | Salin | UI |
| `open_devices` | Open Devices | Buka Perangkat | UI |
| `open_terminal` | Open Terminal | Buka Terminal | UI |
| `mm_title` | Server and app don’t match | Server dan aplikasi tidak cocok | UI |
| `mm_body ⚠` | The server is version {srv}, this app is version {app}. Re-run the installer so both update together. Actions are off until then. | Server versi {srv}, aplikasi ini versi {app}. Jalankan ulang installer supaya keduanya diperbarui bersamaan. Tombol aksi nonaktif sampai saat itu. | UI |
| `needs_title` | Needs the running server | Perlu server yang berjalan | UI |
| `needs_body` | Start the server to read paired phones, pairing codes and the terminal switch. | Nyalakan server untuk melihat ponsel terpasang, kode pairing, dan sakelar terminal. | UI |
| `b_start` | Start server | Nyalakan server | UI |
| `b_stop` | Stop server | Hentikan server | UI |
| `b_restart` | Restart server | Mulai ulang server | UI |
| `b_cancel` | Cancel | Batal | UI |
| `svc_title` | Service | Layanan | UI |
| `svc_sub` | aristotle-server.service, a systemd user unit. It starts at login and keeps running when this window closes. | aristotle-server.service, unit systemd pengguna. Menyala saat login dan tetap berjalan walau jendela ini ditutup. | UI |
| `svc_unit` | Unit | Unit | UI |
| `svc_state` | State | Keadaan | UI |
| `dis_running` | Start is off: the server is already running. | Tombol Nyalakan mati: server sudah berjalan. | UI |
| `dis_stopped` | Stop and Restart are off: the server isn’t running. | Tombol Hentikan dan Mulai ulang mati: server tidak berjalan. | UI |
| `dis_mismatch` | Actions are off until the server is updated. | Tombol aksi mati sampai server diperbarui. | UI |
| `dis_notinst` | Nothing to control: the service isn’t installed. | Tidak ada yang bisa dikendalikan: layanan belum terpasang. | UI |
| `working_restart` | Restarting… | Memulai ulang… | UI |
| `working_stop` | Stopping… | Menghentikan… | UI |
| `d_stop_t` | Stop the server? | Hentikan server? | UI |
| `d_stop_b` | Phones disconnect and can’t reconnect until the server starts again. | Ponsel terputus dan tidak bisa tersambung lagi sampai server dinyalakan. | UI |
| `d_restart_t` | Restart the server? | Mulai ulang server? | UI |
| `d_restart_b` | Phones disconnect for a moment and reconnect on their own. | Ponsel terputus sebentar lalu tersambung sendiri. | UI |
| `logs_title` | Logs | Log | UI |
| `logs_sub` | Live from the journal: journalctl --user -u aristotle-server | Langsung dari journal: journalctl --user -u aristotle-server | UI |
| `follow` | Follow | Ikuti | UI |
| `wrap` | Wrap lines | Bungkus baris | UI |
| `live` | Live | Langsung | UI |
| `paused` | Paused · {n} new lines | Dijeda · {n} baris baru | UI |
| `jump` | Jump to latest | Ke baris terbaru | UI |
| `cap_note` | This window keeps the last {n} lines; older ones drop off. Full history stays in the journal. | Jendela ini menyimpan {n} baris terakhir; yang lebih lama dibuang. Riwayat lengkap tetap ada di journal. | UI |
| `logs_stopped` | The server is stopped. The journal ends at the last line above. | Server berhenti. Journal berakhir di baris terakhir di atas. | UI |
| `pair_title` | Pair a phone | Pasangkan ponsel | UI |
| `pair_sub` | Make a code, then scan the QR in the Aristotle app or type the code. The code expires; the countdown shows when. | Buat kode, lalu pindai QR di aplikasi Aristotle atau ketik kodenya. Kode ada masa berlakunya; hitung mundur menunjukkan sisanya. | UI |
| `pair_name` | Phone name (optional) | Nama ponsel (opsional) | UI |
| `pair_name_hint` | Shown in the Devices list. | Tampil di daftar Perangkat. | UI |
| `pair_show` | Show pairing code | Tampilkan kode pairing | UI |
| `pair_code` | Pairing code | Kode pairing | UI |
| `pair_expires` | Expires in {t} | Kedaluwarsa dalam {t} | UI |
| `pair_scan` | In the Aristotle app, tap Scan QR. | Di aplikasi Aristotle, ketuk Scan QR. | UI |
| `pair_fp` | Certificate fingerprint | Sidik jari sertifikat | UI |
| `pair_fp_hint` | The phone shows the same 16 groups. Compare them if you typed the code by hand. | Ponsel menampilkan 16 kelompok yang sama. Cocokkan jika kamu mengetik kode secara manual. | UI |
| `pair_addr` | Addresses in the QR | Alamat di dalam QR | UI |
| `pair_new` | Make a new code | Buat kode baru | UI |
| `pair_try` | Try again | Coba lagi | UI |
| `pair_expired` | Code expired | Kode kedaluwarsa | UI |
| `pair_expired_b` | It expired at {t}. Make a new code to pair. | Kedaluwarsa pukul {t}. Buat kode baru untuk memasangkan. | UI |
| `pair_none_t` | No address to pair on | Tidak ada alamat untuk pairing | UI |
| `pair_none_b` | The server has no network listener up (LAN and Tailscale are both down), so the phone has nowhere to connect. Connect to Wi-Fi or Tailscale, then try again. | Server tidak punya listener jaringan yang aktif (LAN dan Tailscale sama-sama mati), jadi ponsel tidak punya tujuan. Sambungkan Wi-Fi atau Tailscale, lalu coba lagi. | UI |
| `details` | Details | Rincian | UI |
| `dev_title` | Devices | Perangkat | UI |
| `dev_sub` | Phones paired with this PC. | Ponsel yang dipasangkan dengan PC ini. | UI |
| `c_name` | Name | Nama | UI |
| `c_paired` | Paired | Dipasangkan | UI |
| `c_seen` | Last seen | Terakhir aktif | UI |
| `c_term` | Terminal | Terminal | UI |
| `c_phone` | Phone | Ponsel | UI |
| `c_via` | Via | Lewat | UI |
| `c_since` | Since | Sejak | UI |
| `c_pid` | PID | PID | UI |
| `granted` | Granted | Diizinkan | UI |
| `revoke` | Revoke | Cabut | UI |
| `ago_min` | {n} min ago | {n} menit lalu | UI |
| `today` | Today {t} | Hari ini {t} | UI |
| `now` | just now | baru saja | UI |
| `protocol` | removed: `status --json` has no `protocol` field (P5 compares versions) | | UI |
| `app_expects ⚠` | app is {n} | aplikasi {n} | UI |
| `dur_min` | {n} min | {n} mnt | UI |
| `qr_label` | Pairing QR code | Kode QR pairing | UI |
| `empty_dev_t` | No phones paired | Belum ada ponsel yang terpasang | UI |
| `empty_dev_b` | A phone appears here after it pairs. Make a pairing code first. | Ponsel muncul di sini setelah dipasangkan. Buat kode pairing dulu. | UI |
| `b_pair` | Pair a phone | Pasangkan ponsel | UI |
| `d_revoke_t` | Revoke {name}? | Cabut akses {name}? | UI |
| `d_revoke_b` | The phone is signed out and must pair again. Its remote terminal grant and open terminals end. | Ponsel akan keluar dan harus dipasangkan lagi. Izin terminal jarak jauh dan terminal yang terbuka ikut berakhir. | UI |
| `t_title` | Remote terminal | Terminal jarak jauh | UI |
| `t_sub` | Lets a granted phone open a shell on this PC. | Memungkinkan ponsel yang diberi izin membuka shell di PC ini. | UI |
| `t_on` | On | Aktif | UI |
| `t_off` | Off | Mati | UI |
| `t_off_d` | No phone can open a shell. | Tidak ada ponsel yang bisa membuka shell. | UI |
| `t_on_d` | Granted phones can open a shell with full access to your account. | Ponsel yang diberi izin bisa membuka shell dengan akses penuh ke akunmu. | UI |
| `t_turn_on` | Turn on… | Aktifkan… | UI |
| `t_turn_off` | Turn off | Matikan | UI |
| `t_off_hint` | Turning off closes open terminals and ends their processes. | Mematikan akan menutup terminal yang terbuka dan menghentikan prosesnya. | UI |
| `t_grants` | Granted phones | Ponsel dengan izin | UI |
| `t_grants_empty` | No phone holds a grant. Grant one below. | Belum ada ponsel yang diberi izin. Beri izin di bawah. | UI |
| `t_remove` | Remove grant | Cabut izin | UI |
| `t_grant_to` | Grant to | Beri izin ke | UI |
| `t_grant` | Grant… | Beri izin… | UI |
| `t_grant_all` | Every paired phone already has a grant. | Semua ponsel terpasang sudah punya izin. | UI |
| `t_gone` | (no longer paired) | (sudah tidak terpasang) | UI |
| `t_open` | Open terminals | Terminal yang terbuka | UI |
| `t_open_empty` | No terminal is open. | Tidak ada terminal yang terbuka. | UI |
| `t_prob_t` | terminal.json can’t be read | terminal.json tidak bisa dibaca | UI |
| `t_prob_b` | The remote terminal stays off until the file is fixed or removed. The app doesn’t edit it. | Terminal jarak jauh tetap mati sampai file itu diperbaiki atau dihapus. Aplikasi tidak mengubahnya. | UI |
| `t_check` | Check again | Cek lagi | UI |
| `t_warn_t` | Saved, with a warning | Tersimpan, dengan peringatan | UI |
| `d7_enable_t` | Turn on the remote terminal? | Aktifkan terminal jarak jauh? | UI |
| `d7_grant_t` | Give {name} a terminal? | Beri {name} akses terminal? | UI |
| `d7_warn` | A granted phone gets a shell with FULL access to your account: files, SSH keys, Hermes. Revoking the phone later does not undo what was done. | Ponsel yang diberi izin mendapat shell dengan akses PENUH ke akunmu: file, kunci SSH, Hermes. Mencabut ponsel nanti tidak membatalkan apa yang sudah dilakukan. | UI |
| `d7_nogrant` | No phone holds a grant yet. You grant phones one by one. | Belum ada ponsel yang diberi izin. Izin diberikan satu per satu. | UI |
| `d7_enable_b` | Turn on remote terminal | Aktifkan terminal jarak jauh | UI |
| `d7_grant_b` | Grant full access | Beri akses penuh | UI |
| `m_open` | Open | Buka | Rust |
| `m_pair` | Pair a phone… | Pasangkan ponsel… | Rust |
| `m_start` | Start server | Nyalakan server | Rust |
| `m_stop ⚠` | Stop server… | Hentikan server… | Rust |
| `m_quit` | Quit | Keluar | Rust |
| `tip_ok` | Server running · Hermes reachable | Server berjalan · Hermes terjangkau | Rust |
| `tip_warn` | Server running · Hermes not reachable | Server berjalan · Hermes tidak terjangkau | Rust |
| `tip_err` | Server stopped | Server berhenti | Rust |
| `n_paired_t` | New phone paired | Ponsel baru dipasangkan | Rust |
| `n_paired_b` | {name} can now reach this PC. | {name} sekarang bisa terhubung ke PC ini. | Rust |
| `n_term_t` | Remote terminal opened | Terminal jarak jauh dibuka | Rust |
| `n_term_b ⚠` | {name} opened a shell on this PC ({via}). | {name} membuka shell di PC ini ({via}). | Rust |
| `n_herm_t` | Hermes is not reachable | Hermes tidak terjangkau | Rust |
| `n_herm_b` | Phones can’t start chats until Hermes runs again. | Ponsel belum bisa memulai obrolan sampai Hermes berjalan lagi. | Rust |
| `n_stop_t` | Aristotle server stopped | Server Aristotle berhenti | Rust |
| `n_stop_b` | It was not stopped from this app. Open the app to start it again. | Bukan dihentikan dari aplikasi ini. Buka aplikasi untuk menyalakannya lagi. | Rust |
| `n_d7_t` | Remote terminal turned on | Terminal jarak jauh diaktifkan | Rust |
| `n_d7_b` | Granted phones can open a shell with full access to your account. | Ponsel yang diberi izin bisa membuka shell dengan akses penuh ke akunmu. | Rust |
| `n_open` | Open | Buka | Rust |
| `now_t` | now | baru saja | Rust |
| `st_loading ★` | Checking the server… | Memeriksa server… | UI |
| `st_noanswer ★` | Server not answering | Server tidak menjawab | UI |
| `sub_noanswer ★` | The service is active but the server did not answer. It may still be starting. Check Logs if this stays. | Layanan aktif tetapi server tidak menjawab. Mungkin masih mulai. Lihat Log jika ini berlanjut. | UI |
| `st_nocli ★` | aristotle-server not found | aristotle-server tidak ditemukan | UI |
| `sub_nocli ★` | The app could not run ~/.local/bin/aristotle-server. Run the installer, then reopen this window. | Aplikasi tidak bisa menjalankan ~/.local/bin/aristotle-server. Jalankan installer, lalu buka jendela ini lagi. | UI |
| `svc_unknown ★` | unknown | tidak diketahui | UI |
| `en_unknown ★` | Unknown | Tidak diketahui | UI |
| `hs_unknown ★` | Unknown | Tidak diketahui | UI |
| `st_outside ★` | Running outside the service | Berjalan di luar layanan | UI + tray tooltip |
| `sub_outside ★` | The server answers, but not as the aristotle-server service, so start, stop and restart may not reach it. | Server menjawab, tetapi bukan sebagai layanan aristotle-server, jadi nyalakan, matikan, dan mulai ulang mungkin tidak berpengaruh. | UI |
| `sub_hermunk ★` | This server does not report Hermes. Update it with the installer. | Server ini tidak melaporkan Hermes. Perbarui dengan installer. | UI + tray tooltip |
| `no_listen_up ★` | No network listener is up. | Tidak ada listener jaringan yang aktif. | UI |
| `working_start ★` | Starting… | Menyalakan… | UI |
| `act_failed_t ★` | That did not work | Itu tidak berhasil | UI |
| `act_failed_b ★` | The command failed. The message from the server is below. | Perintah gagal. Pesan dari server ada di bawah. | UI |
| `logs_ended ★` | The log stream stopped. | Aliran log berhenti. | UI |
| `logs_retry ★` | Reconnect | Sambungkan lagi | UI |
| `pair_busy ★` | Making a code… | Membuat kode… | UI |
| `pair_name_err ★` | A name can’t start with “-”. | Nama tidak boleh diawali “-”. | UI |
| `d_toff_t ★` | Turn off the remote terminal? | Matikan terminal jarak jauh? | UI |
| `d_toff_b ★` | Open terminals: {n}. Turning off closes them and ends their processes. | Terminal yang terbuka: {n}. Mematikan akan menutupnya dan menghentikan prosesnya. | UI |
| `copied ★` | Copied | Tersalin | UI |
| `nav_label ★` | Views | Tampilan | UI |

## 6. Tray and notifications (D4, D5, D14, D17, P14)

- Icon: the monochrome glyph (`docs/design/icon/aristotle-b2-monochrome.svg`, the fold is a real hole) tinted by state + a badge top right in the state shape (circle / triangle / square). Colours (not app tokens, they sit on KDE's panel): green `#1F7A5F` / `#5FD1AE`, amber `#B26A00` / `#F2B33D`, red `#C8261C` / `#FF6B5E` (light panel `#EFF0F1` / dark `#31363B`). Contrast vs panel (non-text, needs 3): green 4.59 / 6.52, amber 3.71 / 6.56, red 4.91 / 4.37. At 16 px colour does the work and the shape cue is weak; DSI draws the heavier 16 px variant and the user approves it on the real panel (D19-5).
- Menu: **Open** (bold, default) · **Pair a phone…** (disabled when the server is down) · separator · **Start server** (stopped) or **Stop server…** (running) · separator · **Quit** (UI only). Tooltip `tip_ok` / `tip_warn` / `tip_err` under the title "Aristotle Server". Left click = Open. Actions that need a dialog or a view launch the window app with an argument (P14); Start runs directly.
- Notifications: app icon, "Aristotle Server", title, one-line body, **Open** button (`n_open`; none on the D7 one). Texts `n_paired_*`, `n_term_*`, `n_herm_*`, `n_stop_*` (D5), `n_d7_*` (D7). Bodies use only device names from `devices list` and the listener word; never a code, QR payload, fingerprint or token (P11).

## 7. Accessibility

- **Focus order**: window → sidebar nav (Server group, then Phones group, in visual order) → the view's content, top to bottom, left to right (banner, header actions, controls, lists) → nothing in the footer (it is text, `role="status"`). Dialogs trap Tab and start on Cancel (§3.6). One visible focus style everywhere: 2 px `--primary` outline, 2 px offset (`--primary`/`--surface` 11.35 light, 12.97 dark).
- **Landmarks**: `<nav aria-label>` for the sidebar, `<main>` for the content, one `h1` per view (Status has a screen-reader-only `h1`), `aria-current="page"` on the open view, `role="switch"` with `aria-checked`, `role="alertdialog"`, `role="log"` (non-live), `role="progressbar"`, `role="img"` with label on the QR. `lang` on `<html>` follows the locale.
- **Not colour alone**: state = shape + word (footer, headline, Hermes row, banners); WARN/ERROR log rows also carry the `level=` word; the switch carries On/Off text and thumb position; the Live and Paused states are words.
- **Contrast** (WCAG, computed with the formula of `docs/design.md`; text needs 4.5, non-text 3). All pairs below are used by this design.

  | Pair | Light | Dark |
  |---|---|---|
  | onSurface / surface | 13.62 | 14.53 |
  | onSurfaceVariant / surface | 7.19 | 9.84 |
  | onSurface / surfaceContainerLow | 12.81 | 13.57 |
  | onSurfaceVariant / surfaceContainerLow | 6.77 | 9.19 |
  | onSurface / surfaceContainerHighest | 10.62 | 10.06 |
  | onSurfaceVariant / surfaceContainerHighest | 5.61 | 6.82 |
  | onSurface / surfaceContainerHigh (dialog) | 11.36 | 11.60 |
  | onPrimary / primary | 11.58 | 10.62 |
  | onSecondaryContainer / secondaryContainer | 11.65 | 8.20 |
  | onSurfaceVariant / secondaryContainer | 6.15 | 5.66 |
  | onTertiary / tertiary | 6.32 | 7.39 |
  | onError / error | 6.54 | 7.71 |
  | onErrorContainer / errorContainer | 10.41 | 7.06 |
  | onTertiaryContainer / tertiaryContainer | 11.08 | 7.02 |
  | onWarnContainer / warnContainer | 10.91 | 8.56 |
  | tertiary / surface (Live word) | 5.47 | 10.42 |
  | warn / surface · / surfaceContainerLow (text) | 5.48 · 5.15 | 9.46 · 8.84 |
  | error / surface · / surfaceContainerLow · / surfaceContainerHigh (danger outline text) | 5.66 · 5.32 · 4.72 | 10.87 · 10.15 · 8.68 |
  | **trap** tertiary · error · warn / surfaceContainerHighest (shape only, never text) | 4.26 · 4.41 · 4.27 | 7.22 · 7.53 · 6.55 |
  | outline / surface (borders, switch track; non-text) | 3.71 | 5.13 |
  | outline / surfaceContainerLow (field and button borders in rows) | 3.49 | 4.79 |
  | onSurfaceVariant / surfaceContainerHighest (off switch thumb on its track) | 5.61 | 6.82 |
  | QR ink / QR background | 15.25 | 15.25 |

  Not allowed: `--outline` as the off thumb colour (2.89 on the light track, below 3); that is why the thumb uses `--on-surface-v`.
- **Targets**: nav items 36 px, buttons 36 px (small 28 px; none under 24 px), switch 40 × 22 with its label clickable.
- **Live regions**: footer `role="status"` (text changes only when the state changes, not on every poll); banners as in §3.4; copy confirmation polite; the log box and the countdown are not live (no per-line or per-second announcements).
- **Motion**: §2.3. Text size follows the system scale (`rem`).

## 8. Fonts

| Family | Files (source) | Weights | Licence |
|---|---|---|---|
| Atkinson Hyperlegible Next | `atkinson_next_regular.ttf`, `atkinson_next_medium.ttf`, `atkinson_next_semibold.ttf` from `android/app/src/main/res/font/` (copies already in `docs/design/desktop/fonts/`) | 400 / 500 / 600 (semibold also serves 700) | OFL 1.1, `docs/licences/OFL.txt` |
| Atkinson Hyperlegible Mono | `atkinson_mono_regular.ttf`, `atkinson_mono_medium.ttf`, `atkinson_mono_semibold.ttf`, same folder | 400 / 500 / 600 | OFL 1.1, same file |
| Literata (variable, roman only) | `Literata[opsz,wght].ttf` | 200–900 axis; used at 500 and 600 | OFL 1.1, "Copyright 2017 The Literata Project Authors" |

- **Where they live**: `desktop/ui/src/assets/fonts/`, copied there by **DS3w** (not by this design pass). Local `@font-face` only (offline, no CDN; fits the strict CSP of P4: `font-src 'self'`), `font-display:block`. Convert the TTFs to `.woff2` if a converter is already on the machine, otherwise ship the TTFs; no install for this. Literata: `@font-face{font-family:"Literata";src:url(...) format("truetype");font-weight:200 900;font-display:block}` with `font-optical-sizing:auto`.
- **Literata download (one OFL font, approved in D19-10)**: DS3w fetches exactly one file, the roman variable TTF (not the italic), from the Google Fonts repository (upstream `https://github.com/googlefonts/literata`, `fonts/variable/Literata[opsz,wght].ttf`, release 3.103):
  `https://raw.githubusercontent.com/google/fonts/main/ofl/literata/Literata%5Bopsz%2Cwght%5D.ttf`
  and its licence text from `https://raw.githubusercontent.com/google/fonts/main/ofl/literata/OFL.txt`, saved next to it as `OFL-Literata.txt` (the OFL requires the copyright notice to travel with the font). Nothing here has been downloaded; the file's size is not checked (design-notes' 100–150 KB for woff2 is an estimate).
- Licence notice: ship `docs/licences/OFL.txt` (Atkinson) and `OFL-Literata.txt` with the app; keep font names unmodified.
- Fallbacks: Literata → `"Source Serif 4", Georgia, serif`; Atkinson Next → `sans-serif`; Mono → `monospace`.

## 9. Where the rest lives

Option record, screenshots and the board: `docs/design/desktop/` (`design-notes.md`, `mockups.html`, `shots/`). Data shapes: `docs/protocol.md` §22 and `server/internal/app/testdata/json/`. Decisions: `docs/desktop/spec.md` (D19, P1–P16). Tickets: `docs/desktop/tickets.md`.
