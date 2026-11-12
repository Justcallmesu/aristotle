# IN2 build brief — bundle `scripts/install.sh`: Hermes gateway, linger, firewall

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` first. Spec: `docs/release/spec.md` section
"Complete installer (2026-11-12)": decisions **Q136, Q137, P21, P23, P24, P25** apply; **P22** is the contract you parse.
Install doc for context: `docs/release/README.md` (do NOT edit it; the PM does).

## Why (verified on a real second PC, 2026-11-12)
`install.sh` reported success, yet: the Hermes gateway was not running (only the Desktop app; server log
`dial unix ~/.hermes/gateway.sock: no such file`), ufw dropped LAN traffic to 8760/tcp (Tailscale worked), linger was only printed.

## Goal: extend `scripts/install.sh` (keep every existing behaviour and test)
New order (existing steps unchanged unless named):
1. `refuse_in_app_terminal` (as today).
2. Parse flags: any combination of `--pair | --no-pair` (mutually exclusive, as today), `--no-gateway`, `--no-firewall`; unknown
   -> usage, exit 2. Update the header comment + usage line.
3. **Hermes CLI pre-check** (P24): unless `--no-gateway`, find `hermes` (PATH, then `~/.local/bin/hermes`); missing -> error
   "Hermes Agent is not installed ... (or pass --no-gateway)", exit 1, **before anything is touched**.
4. `verify_bundle`, `install_app_update`, `install_binary`, `install --force --start`, `wait_for_status` (as today).
5. **Gateway step** (Q137, P24): read `"$TARGET" status --porcelain` (P22). `hermes reachable` -> say "Hermes: reachable".
   Otherwise, without `--no-gateway`: say what you do ("Hermes gateway is not running; installing it as a user service"), run
   the gateway install/start for the **same Hermes home the service dials**: the systemd unit sets no `HERMES_HOME`, so run
   `hermes` with `HERMES_HOME` unset (`env -u HERMES_HOME`), stdin from `/dev/null`. Find the right commands by READING
   (read-only) the Hermes source `~/.hermes/hermes-agent/hermes_cli/` (the `gateway install` / `start` subcommands): what
   `install` does when the unit already exists, whether it starts the service, whether it can prompt. Observed on a real PC:
   `hermes gateway install </dev/null` installed, enabled and started it. Then poll the porcelain `hermes` line until
   `reachable`, bounded by `ARISTOTLE_INSTALL_TIMEOUT` (same wall-clock style as `wait_for_status`). Still not reachable ->
   print the reason, `journalctl --user -u hermes-gateway -n 30 --no-pager`, and exit 1.
   With `--no-gateway` and not reachable: warn with the command to run, continue (exit 0 at the end).
   A server that prints no `hermes` line (older binary) -> warn "cannot check Hermes", continue.
6. **Linger** (P25): `loginctl show-user "$USER" -p Linger` not `Linger=yes` -> `loginctl enable-linger "$USER"`; failure = warning.
7. **Firewall** (Q136, P23): from porcelain take `port` and every `lan <iface> <ip> <subnet>` line (no rule for Tailscale).
   For each LAN subnet the rules are: `<port>/tcp` and `5353/udp` from that subnet only.
   - Detect: ufw active (`sudo ufw status` first line `Status: active`), else firewalld running (`firewall-cmd --state` ->
     `running`), else "no active firewall detected" (skip, say so) — but when neither tool exists or detection needs a password
     that cannot be asked, print the commands for both and a note.
   - ufw: `ufw allow from <subnet> to any port <port> proto tcp comment 'aristotle'` and the 5353/udp twin (ufw itself skips an
     existing identical rule; verify that claim in the ufw man page/behaviour you can read, else check `ufw status` first).
     firewalld: rich rules `rule family="ipv4|ipv6" source address="<subnet>" port port="<port>" protocol="tcp" accept`
     (+ udp 5353), `--permanent` then `--reload`; skip a rule `--query-rich-rule` already finds.
   - `sudo` only here. Interactive (stdin+stdout TTY): plain `sudo` (it prompts). Not interactive: `sudo -n`; refused -> print the
     exact commands, warn, continue. `--no-firewall` -> print the commands only.
   - A firewall failure never fails the install (exit stays as the other steps decide).
   - Print the rules added/kept, and say: "On another network, run the installer again."
8. Existing tail: version, rollback, PATH note, app note, pairing offer.
Remove the now-wrong advice printed by the server's `install` only if it is in THIS script (the `loginctl` advice line comes from
the Go binary, `server/internal/app/servicecmds.go:74`: leave Go alone, another worker owns `server/`).

## Code shape
Small named bash functions (one per step: `check_hermes_cli`, `ensure_gateway`, `ensure_linger`, `open_firewall`, ...), one
porcelain reader function used by all steps, no duplication, `set -euo pipefail` kept, shellcheck-clean if `shellcheck` exists
(do not install it). Match the existing comment style (each function says what and why in one line).

## Files you may create/edit
`scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/` (extend the `aristotle-server` stub with
`status --porcelain` driven by env vars, e.g. `STUB_PORCELAIN_HERMES`, `STUB_PORCELAIN_LAN`; add stubs `hermes`, `loginctl`,
`sudo`, `ufw`, `firewall-cmd` that record calls to `$STUB_LOG`). Other workers edit `server/` and the repo-root `install.sh` +
`scripts/test/root_install_test.sh` now: **not yours, do not touch.** Report: `docs/release/runs/IN2/build-report.md`.

## Acceptance tests (extend `scripts/test/install_test.sh`, its temp-HOME + stub pattern; nothing touches the real system)
- hermes CLI missing, no `--no-gateway` -> exit 1, nothing installed (no binary, no app copy, no `install` call).
- hermes reachable -> no `hermes gateway` call.
- not reachable -> `hermes gateway install` called with HERMES_HOME **unset** even when the test exports `HERMES_HOME=/x`
  (the stub records its env), then reachable after N polls -> exit 0.
- never reachable -> exit 1, journal tail printed.
- `--no-gateway` + not reachable -> no gateway call, warning, exit 0.
- old server without porcelain hermes line -> warning, exit 0.
- linger `no` -> `enable-linger` called; `yes` -> not called; failing loginctl -> warning only.
- ufw active, 2 LAN subnets -> exactly 4 allow rules with the porcelain port (use a non-default port like 8799 to prove it is
  read, not hard-coded); no rule for the tailscale line.
- firewalld running -> rich rules + reload; existing rule (query succeeds) not re-added.
- non-interactive and `sudo -n` refused -> commands printed, exit 0. `--no-firewall` -> commands printed, no sudo call.
- flag parsing: `--pair --no-pair` -> exit 2; `--no-gateway --no-firewall --no-pair` accepted in any order; unknown flag -> exit 2.
- all existing install tests still pass unchanged (except where the new pre-check needs a `hermes` stub on PATH: add it in
  `new_case`).
Show the new tests RED against the old `install.sh` (keep a scratch copy; never git) and report it.

## Verify
`bash scripts/test/run.sh install` (exit 0) and, if available, `shellcheck scripts/install.sh`. Report real exit codes.

## Hard rules
Never `git checkout/restore/stash/reset/commit/push`. Never run the real installer, real `sudo`, `ufw`, `firewall-cmd`,
`loginctl enable-linger`, `systemctl --user` writes, or real `hermes gateway ...`. Never touch `~/.hermes`, `~/.local/bin`, or the
running services. Reading Hermes source under `~/.hermes/hermes-agent/` is allowed (read-only).

## Stop and ask if
- The Hermes source shows `gateway install` can prompt or replace a running gateway in a way `</dev/null` does not handle.
- The P22 porcelain format cannot express something you need.
