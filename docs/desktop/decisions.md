# Desktop app — run decisions
- 2026-11-15 Grill in 3 rounds: D1-D16 answered (D2 = Tauri, user's pick over the PM's Wails recommendation; all others as
  recommended). User answered the closing round (D15/D16) without objecting to the design diagram: taken as confirmation (D0).
  PM technical decisions P1-P11 recorded in `spec.md`, override welcome.
- 2026-11-15 **Paused by the user for the night.** Resume point:
  1. Write the DRAFT `docs/desktop/tickets.md` (DS1-DS7 as listed in the closing round: DS1 server `--json` + `start|stop|restart`
     (P9), DS2 Tauri tray spike on KDE, DS3 desktop core, DS4 Status/Service/Logs, DS5 Pair/Devices, DS6 Terminal, DS7 installer +
     bundle + release + second PC) with acceptance criteria and verify commands, and show it to the user.
  2. Dispatch nothing until the user approves the breakdown. DS2+ also wait on the user's `sudo pacman -Syu` (D11).
  3. `docs/desktop/` is uncommitted: the user approves the spec before it is committed.
- 2026-11-15 Resumed. DRAFT `tickets.md` written (DS0 user toolchain fix, DS1-DS7, deferred DL1-DL3); shown to the user for
  approval. Rust still broken (`rustc` cannot load libLLVM.so.23.1). Nothing dispatched.
- 2026-11-18 User "continue" read as approval of the ticket breakdown (PM reading, stated in chat). DS0 done by the user
  (`pacman -Syu` + reboot: kernel 7.3.4, rustc 1.100.0). PM added DSD (design pass, mockups) before the UI tickets
  (thin-orchestrator rule: no UI ticket without an approved design); DS4-DS6 now also wait on DSD approval.
  Wave 1 = DS1 (server) + DS2 (Tauri spike) in parallel (disjoint dirs). Spec docs committed first.
- 2026-11-18 P12 (PM technical): `stop` and `restart` refuse inside an Aristotle remote terminal (the restart would kill the
  caller's own shell mid-command), reusing the existing detection; `start` is allowed. Accident guard, not a security boundary.
