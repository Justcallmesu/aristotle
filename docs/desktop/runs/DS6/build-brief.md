# DS6 build brief — Terminal view

First read and follow `docs/desktop/runs/views-shared-brief.md` (binding). Your folder: `desktop/ui/src/views/terminal/`.
Design: §4.8 Terminal, §3.6 dialogs (D7 dialog), §4.1/4.2. Background: `docs/terminal.md` "What enabling means" (the D7 warning
text comes from design.md, which copied the CLI's wording).

## Terminal
Read `terminal_status` (via the IPC client) and the snapshot: switch state, granted devices (name from the devices list, "a device"
fallback), open terminals (device, since, listener), the `problem` state (terminal.json unusable: switch off, explain, no actions),
needs-server state.
- **Turn on** and **Grant** go through the D7 dialog: full-access warning text, red confirm button, no "don't ask again", focus on
  Cancel (D7, D19-9). After success the tray notification is the tray's job; the view just refreshes.
- **Turn off** confirms only when open terminals exist (D19-8, `d_toff_t`/`d_toff_b`), else runs directly. **Remove grant** runs
  directly (it narrows access).
- Mismatch / not installed: all actions disabled with the written reason.
Tests: every dialog path (Cancel sends nothing), the "confirm only with open terminals" rule both ways, the D7 dialog has no
remember option and a red button, problem state disables everything.

Report path: `docs/desktop/runs/DS6/build-report.md`.
