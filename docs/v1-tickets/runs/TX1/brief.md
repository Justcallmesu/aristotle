# TX1 — discovery for Q81 (no code)

route: deep. Read-only. Hermes source: `/home/justcallmesu/.hermes/hermes-agent` (gateway `tui_gateway/**` incl. `contracts/**`,
`apps/desktop/src/**`, `hermes_cli/**`, `tools/**`). Aristotle repo: `/home/justcallmesu/project/personal/Aristotle` (`docs/protocol.md`,
`docs/spec.md`, `server/internal/**`, `android/app/src/main/java/**`). Never connect to the running Hermes; never write outside the report.

Answer each with file:line citations, marking VERIFIED vs ASSUMED, and for each give the smallest buildable design for the phone
(which RPC/fields, what the server must add if anything, polling/events, risks):

1. **All running work in one screen**: how does the Desktop know which sessions are running / waiting / have subagents and background
   processes across ALL sessions and profiles (session.list fields? an agents.list / delegation registry / process registry global
   list? events)? Is there a gateway method that lists running work globally, or must the phone fan out per session? Cost per call.
2. **Usage / cost per session**: does Hermes store or expose token counts / cost per session or per turn (session fields, usage
   events, `usage`/`cost`/`tokens` in contracts or state.db schema; Desktop UI that shows it)? Exact field names and units; is cost
   computed or only tokens (pricing table?).
3. **Session grouping and sorting by projects like the Desktop**: what is a "project" in Hermes Desktop (cwd? a projects table? the
   desktop_project tool? a workspace?), how does the Desktop group and sort the sidebar (sort keys, pinned, groups), and is the project
   of a session available through any gateway RPC the phone can call (session.list fields?) — or only in Desktop-local storage?
4. **Clipboard to PC and back**: Hermes has nothing to do with it presumably — design it in aristotle-server: how could the server read/
   write the desktop clipboard on this Linux laptop (Wayland `wl-copy`/`wl-paste`, X11 `xclip`; KDE? check `XDG_SESSION_TYPE`,
   `WAYLAND_DISPLAY` availability for a systemd --user service vs the demo run; `docs/terminal.md` T19c stripped GUI env from the
   terminal on purpose — reconcile); security (which devices may, size cap, no history, sensitive marking on the phone, log nothing);
   is `wl-clipboard`/`xclip` installed (check with `command -v` only).

Report: `docs/v1-tickets/runs/TX1/report.md`, ≤80 lines, plus a short list of decisions the user must make (2-4 options each with
consequences) and a proposed ticket list with dependencies and sizes.
