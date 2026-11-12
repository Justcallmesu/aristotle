# IN1 + IN2 review brief (deep, fresh context)

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, then spec `docs/release/spec.md` section
"Complete installer (2026-11-12)" (Q136, Q137, P21-P25 are the contract), the briefs and reports:
`docs/release/runs/IN1/{build-brief,build-report}.md`, `docs/release/runs/IN2/{build-brief,build-report}.md`.

## Scope (uncommitted working tree, base HEAD 41fb0ad)
- IN1: `git diff HEAD -- server/` + new `server/internal/app/status_test.go`.
- IN2: `git diff HEAD -- scripts/install.sh scripts/test/install_test.sh scripts/test/stubs/aristotle-server` + new stubs
  `scripts/test/stubs/{hermes,loginctl,sudo,ufw,firewall-cmd}`.
- NOT in scope: repo-root `install.sh` and `scripts/test/root_install_test.sh` (another worker is fixing them right now; do not
  read them as final, do not run their tests).

## Already checked by the PM (hunt elsewhere)
Gate GREEN (gofmt/vet/805 Go tests; script tests install/release/root_install pass). Mutants KILLED: `env -u HERMES_HOME`
dropped (install test), firewall port hard-coded 8760 (install test, port 8799 case), not-running reported as reachable
(`TestProbeHermes`, `TestControlStatusCarriesPortListenersHermes`).

## Review questions (answer each, cite file:line)
1. **Contract:** does `status --porcelain` match P22 exactly (line set, order, single spaces, IPv6 unbracketed, lines only when
   running, exit code = plain status)? Does install.sh parse it robustly (a value with spaces is sanitized to `_`; an `iface`
   with odd chars; a server answer without new fields)?
2. **Hermes probe:** is `Identify` truly side-effect free (read `server/internal/hermes/control.go` and what it calls: no ticket,
   no log of secrets)? Goroutine/ctx: can a hung Identify leak a goroutine per `status` call, and does it matter? Control
   `status` is reachable from the phone's remote-terminal processes (`terminalcmds.go` allow-list): is exposing Hermes
   reachability + LAN subnets there acceptable (it was already exposing addresses)? Say so explicitly.
3. **Same Hermes home (P24):** the server dials `$HERMES_HOME` else `~/.hermes` (`server/internal/hermes/hermes.go:~115`); the
   unit sets none. The installer unsets `HERMES_HOME` for `hermes gateway install`. Is that the same home in every case,
   including a user whose interactive shell exports HERMES_HOME on purpose (e.g. they run Hermes from a non-default home)?
   What does the server then dial, and does the installer make it worse or just report? Read the Hermes source read-only
   (`~/.hermes/hermes-agent/hermes_cli/gateway.py`, the `install`/`systemd_start` paths the IN2 report cites) and confirm the
   builder's claims: non-TTY install starts the unit without prompts; an existing unit is started, not replaced; a running
   gateway is not restarted (a restart would cut the user's live Hermes sessions — that would be a MAJOR).
4. **Firewall (security):** rules only from LAN subnets, never `any`, never for Tailscale; idempotent on re-run (ufw duplicate
   detection by output text `existing rule` — is that string stable and locale-independent? `LANG` could translate it);
   firewalld rich-rule text valid (`firewall-cmd` syntax) and family right for IPv6; `sudo` used only in the firewall step
   (grep every `sudo`/`priv` call); `sudo -n` path never hangs; no password ever echoed/logged. Is detection via
   `priv ufw status` acceptable (it prompts for the password during detection when interactive)? Any input from porcelain that
   reaches a root command unquoted or could inject (a subnet string from the server — could it ever contain a quote/space)?
5. **Failure paths:** gateway never reachable -> exit 1 AFTER the server binary was swapped: is the message + journal enough,
   and is the rollback command still shown where useful? Firewall/linger failures never fail the install. `set -e` traps:
   any command in the new functions whose non-zero exit would abort the script unintentionally (e.g. `loginctl show-user`
   on a system without logind, `awk` on empty input, `read` at EOF in `while read` loops).
6. **Tests:** do they guard behaviour or only output text? Name any acceptance item from the IN2 brief with no real test
   (the report admits: interactive sudo path, "no firewall tool" branch). Is the "no tool" branch reachable in tests at all
   given `has_tool` adds `/usr/sbin:/sbin` (the real ufw on the PM's PC would be found) — a test-isolation bug?
7. **Code shape:** small named functions, no duplication, comments say why (PROJECT.md "Code shape"). Anything misleading?

Run whatever you need read-only and the suites: `cd server && go test ./internal/app/`, `bash scripts/test/run.sh install`.
You may write throwaway probes in your scratch dir. Never run the real installer, real sudo/ufw/firewall-cmd/loginctl writes,
`systemctl --user` writes or `hermes gateway ...`; never touch `~/.hermes` state, `~/.local/bin`, running services. Never edit
repo files; never git checkout/restore/stash/reset/commit/push.

## Output
`docs/release/runs/IN2/review-report.md`: verdict APPROVED / APPROVED-WITH-FINDINGS / CHANGES-REQUIRED; findings numbered with
severity BLOCKER/MAJOR/MINOR/NIT, ticket (IN1/IN2), file:line, evidence, and the concrete fix. Separate what must be fixed
before the 2.2.0 release from what can be deferred.
