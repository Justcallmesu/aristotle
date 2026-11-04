# Review brief — T19a (remote terminal, server) — SECURITY

Fresh reviewer; you did not write this. This code gives a phone a shell on the user's laptop. Repo
`/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`; `docs/spec.md` Q7c, Q52–Q55; `docs/v1-tickets/runs/T19/design-draft.md`,
`runs/T19/redteam-report.md` (every BLOCKER/MAJOR must be closed), `runs/T19a/build-brief.md` + `build-report.md`; `docs/terminal.md`;
`docs/protocol.md` §11.2, §12, §14. Diff: `git diff HEAD -- server docs` + untracked `server/internal/terminal/`, `docs/terminal.md`.
PM gate GREEN both sides `runs/pm-gate-35` (311 Go); PM mutation (escalation list emptied) fails 2 tests. Edit nothing but your report; no
git state changes. You may run `go test -race` with temp XDG dirs. NEVER start the server on a real listener, never touch port 8760 or the
real state dir, never install the unit, never run `systemd-run`.

Attack it, with file:line evidence:
1. Any path to a shell while disabled / ungranted / revoked (pre-upgrade check, post-register re-check, races with disable/revoke/shutdown,
   grant file read errors -> fail closed?).
2. Escalation refusal: can a terminal process bypass `InTerminal` (double fork + reparent to init, `setsid`, `systemd-run --user`,
   unsetting the marker, a process the server can't read, pid reuse between peer-cred read and check, connecting via a copied socket
   path)? What is still possible from the shell (editing `terminal.json`/`devices.json` directly, reading `tls-key.pem`) — is it honestly
   documented in `docs/terminal.md`? Is the "can't read -> inside" rule safe for the user's own PC terminals?
3. Cleanup: cgroup path (delegation detection via "unit file says Delegate=yes" — can it be fooled, and what happens if it's wrong?),
   fallback session kill; zombie/leak on rapid open/close; caps enforced under concurrency.
4. Liveness + limits: input byte-rate cap, output backpressure (memory bounded?), frame cap, resize clamp, idle from input only, 8 h max.
5. Env: what the shell inherits (service env, `SSH_AUTH_SOCK`, `DBUS_*`, `NOTIFY_SOCKET`, aristotle vars) — anything that leaks or lets
   the shell talk to systemd as the service (NOTIFY_SOCKET!)?
6. Logging: no PTY content, no keystrokes, no tokens.
7. The relay refactor (`relay.PhoneConn`) changed no relay behaviour (tests unchanged — confirm by reading).
8. `Delegate=yes` in the unit: any downside for the relay/service; `install --force` upgrade path documented.
Only BLOCKER/MAJOR trigger a fix round. Report ≤40 lines at `docs/v1-tickets/runs/T19a/review-report.md`, last line
`VERDICT: PASS | PASS-WITH-FINDINGS | CHANGES-REQUIRED`.
