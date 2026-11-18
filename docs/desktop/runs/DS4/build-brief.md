# DS4 build brief — Status, Service, Logs views

First read and follow `docs/desktop/runs/views-shared-brief.md` (binding). Your folders: `desktop/ui/src/views/status/`,
`desktop/ui/src/views/service/`, `desktop/ui/src/views/logs/`. Spec/design sections: design.md §4.1, §4.2, §4.3 Status, §4.4 Service
(incl. "Which buttons are on"), §4.5 Logs, §3.7 log viewer.

## Status
State headline from the §4.1 table (rows 0-9, P17/P18), server facts (service, starts at login via `installed`/P13, version, port,
Hermes with reason), listeners, phones summary + terminal summary with buttons that navigate to Devices / Terminal.

## Service
Start / Stop / Restart per §4.4 "Which buttons are on" (must agree with the tray's menu rule: other service words -> all off;
no-answer + active -> Stop/Restart on). Stop and Restart through `ConfirmDialog` with the D15 texts. The `--confirm stop` start
intent (P14) opens the Service view with the stop dialog already open (read the intent from the shared state module; do not edit
it). Working / succeeded / failed via `useCommand`; failure shows `CommandFailed` with the server message.

## Logs
`logs_start` on open, `logs_stop` on leaving the view and on unmount (test both); 2,000-line cap (D19-7); wrap on by default with a
no-wrap toggle (horizontal scroll); follow on/off with "N new lines" when scrolled up; stream ended state; needs-server state when
stopped. Lines rendered as text, never HTML (escape test with a line containing `<script>`).

Report path: `docs/desktop/runs/DS4/build-report.md`.
