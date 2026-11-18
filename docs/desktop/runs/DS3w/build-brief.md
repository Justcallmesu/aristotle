# DS3w build brief — window app skeleton + all IPC + frontend toolchain

Repo `/home/justcallmesu/project/personal/Aristotle` (read `PROJECT.md`). Read in full: `docs/desktop/spec.md` (D3, D6, D7,
D13-D19, P3-P17), `docs/desktop/design.md` (binding: tokens, type, shell 2, components, string table, §4 states),
`docs/protocol.md` §22, `docs/spikes/desktop-tray.md` (Tauri findings: versions, destroy/exit behaviour, gate times),
`docs/desktop/tickets.md` "Deferred review findings" DF1-DF6 (fold them in), and the committed `desktop/` workspace (core + tray).
Load skills `antislop-ui`, `frontend-ui-engineering`, `antislop-code`.

## Goal
The window app `aristotle-desktop` with every IPC command the views need, and an empty, styled shell. Views themselves are DS4-DS6
(they will only edit `desktop/ui/src/views/<view>/` + their own strings), so everything shared lands now.

### `desktop/app` crate (bin `aristotle-desktop`; Tauri 2.12.6 pinned as in the spike; tray crate stays tauri-free)
- One window built at start (min 720x520 per design.md); the **process exits when the window closes** (D18; no tray here, no
  hide-to-tray). `tauri-plugin-single-instance`: a second launch focuses the window and hands over its args.
- Args (P14): none -> Status view; `--pair` -> Pair view and issue a code at once (D19-6); `--confirm stop` -> Service view with the
  D15 stop dialog open. Parse into a typed `StartIntent`, emitted to the UI on start and on hand-off. Unknown args ignored.
- IPC commands (thin wrappers over `aristotle-core`; one module per concern, no logic duplicated from core):
  `snapshot` (current `Snapshot` + assessment), a background poller every 5 s **only while the window is open** emitting
  `snapshot` events; `service_action(start|stop|restart)` — writes the P15 expected-stop stamp before stop/restart and **deletes it
  when the command fails** (DF6); `pair` (returns the §22 pair result incl. `qr_payload`; never logged, never stored, P11);
  `devices_revoke(id)`, `terminal_grant(id)`, `terminal_ungrant(id)`, `terminal_enable`, `terminal_disable`; `logs_start` /
  `logs_stop` streaming `aristotle-server logs -f` lines as events (child killed on `logs_stop`, on window close, and on app exit;
  test it), capped buffer handled in the UI. Every command returns a typed result; CLI errors map to the core error kinds.
- P4 security: capabilities allow only these commands; no shell, fs, http, opener plugins; strict CSP (no remote content, no
  `unsafe-eval`); `withGlobalTauri` off.
- Version mismatch (P5/P17) and not-installed (P13) flags come from core's assessment; commands refuse actions while mismatched.

### `desktop/ui` (React + TypeScript + Vite; Vitest + Testing Library; ESLint)
- Design tokens from design.md as CSS custom properties (light + dark via `prefers-color-scheme`, P7); fonts: copy Atkinson from
  `docs/design/desktop/fonts/` into `desktop/ui/src/assets/fonts/`; download the Literata file named in design.md §8 from the URL
  there (approved, D19-10; one file, verify its size and OFL licence, add `OFL.txt`).
- i18n: `en` and `id` message files with every design.md key; locale from the system (`navigator.language` / Tauri locale);
  a parity test (every key in both).
- Shell 2: grouped sidebar + status footer, the 6 views as routes with an empty placeholder each, the version-mismatch banner, the
  not-installed / no-CLI full states (design.md §4.1 rows 1-2), one shared state-light component (shape + word), one shared
  confirm-dialog component (focus on Cancel, Esc, focus trap — D19-9) that DS4-DS6 reuse. Status footer shows the live state.
- A typed IPC client module (`src/ipc/`) wrapping every command and event above; views import only from it.

### Gate (`docs/desktop/gates.sh`)
Add: `npm ci` (in `desktop/ui`), `npm run lint`, `npm run typecheck`, `npm test`, `npx tauri build --no-bundle` for the app, and
the app binary exists. DF1: per-test-binary ">= 1 test" for the named Rust test binaries (keep `MIN_TESTS`, raise it to the new
total). DF2: `ldd` of `aristotle-tray` is an allowlist (linux-vdso, libc, libm, libgcc_s, ld-linux) instead of the denylist.
Core fixes DF3 (kill the group + join the reader when a grandchild holds stdout), DF4 (re-ask `version --json` every 12 polls or
when `status.result.version` changes), DF5 (`PermissionDenied` -> CLI missing). Each with a test seen RED first.

## Tests (no display needed)
Rust: arg parsing -> `StartIntent`; stamp written before stop/restart and deleted on failure; logs child killed on stop/close
(fake binary); capability file contains only the listed commands (parse it). UI: i18n parity; shell renders 6 nav items; dialog
focus on Cancel + Esc; mismatch banner disables actions; IPC client types compile against the command list.

## Smoke (after the gate)
Build the repo's server into a scratch dir (`bash scripts/build-server.sh` per its usage, output outside the repo) and run the app
with `ARISTOTLE_SERVER_BIN=<that absolute path>` on the user's desktop (GUI env as in the DS3 brief) for a few minutes: the window
opens, the footer shows the live state, closing it ends the process (`pgrep` empty). Screenshot the window only (spectacle
active-window mode), never full screen. Read-only server commands only; never click Start/Stop/Pair/Revoke/terminal buttons.

## Files you may create/edit
`desktop/app/**`, `desktop/ui/**`, `desktop/core/**` (DF3-DF5 only), `desktop/Cargo.toml` (workspace member), `desktop/Cargo.lock`,
`desktop/.gitignore` (add `ui/node_modules/`, `ui/dist/`), `docs/desktop/gates.sh`. `desktop/tray/**` belongs to another worker
(DSI icons) running now: not yours.

## Hard rules
Never `git add/commit/push/checkout/restore/stash/reset`. No system installs (no pacman/yay/sudo/npm -g/cargo install/rustup);
project-local npm/cargo deps and the one Literata download are allowed. Never touch the installed server service, its state,
`~/.config/autostart`, Hermes. Kill every process you start. No skill/memory edits.

## Stop and ask if
A view needs a §22 field that does not exist; Tauri needs a system package; the single-instance plugin cannot hand over args.

## Report
`docs/desktop/runs/DS3w/build-report.md`: files, IPC command table (command -> core call -> UI event), tests with names, RED
evidence, gate summary verbatim, smoke result + screenshot path, deviations, open questions.
