# IN1 + IN2 review report (deep, fresh context)

Reviewer: fresh-context subagent. Scope: brief `docs/release/runs/IN2/review-brief.md` (working tree on top of 41fb0ad;
repo-root `install.sh` / `root_install_test.sh` not read as final, not run).

## Verdict: CHANGES-REQUIRED

Two MAJORs, both in IN2 and both cheap to fix: (1) the interactive `sudo` path never runs, (2) `env -u HERMES_HOME` alone
does not pin the Hermes home. IN1 on its own is APPROVED-WITH-FINDINGS (only minors and nits).

## What I ran (read-only)
- `cd server && gofmt -l ./internal/app; go vet ./internal/app; go test -count=1 ./internal/app/` -> clean, `ok` (exit 0).
- `go test -count=1 -race -run 'Status|Probe' ./internal/app/` -> **FAIL**, a data race in the test (finding 5).
- `bash scripts/test/run.sh install` -> `install_test: all passed`, exit 0.
- Probe `~/.hermes/profiles/pm/cache/scratch/in2review/tty_probe.sh`: runs the bundle `install.sh` under a pty
  (`script -qec`) with the repo's own stubs first on PATH (nothing real runs), and records the form of each `sudo` call.
- Read-only Hermes source: `~/.hermes/hermes-agent/hermes_cli/{gateway.py,main.py,profiles.py}`.

## Findings

### Must fix before 2.2.0

**1. MAJOR (IN2): the interactive `sudo` path never runs, so `priv` always uses `sudo -n`.**
`scripts/install.sh:209,212` checks `[[ -t 0 && -t 1 ]]` at call time. Every `priv` call runs inside a command
substitution (stdout is a pipe: `:334` detection, `:360` ufw allow) or inside a `while … done <<< "$RULES"` loop (stdin is the
here-string: `:372`, `:374` through `apply_firewall_rules:385-387`). So `interactive` is false at every call site except the
reload (`:389`) and the notice (`:384`).
Evidence: the pty probe (stdin and stdout both a TTY) printed "sudo may ask for your password…" and then logged only
`sudo -n ufw status`, `sudo -n ufw allow …` for ufw, and `sudo -n firewall-cmd --permanent --query/--add-rich-rule …` for firewalld.
Effect on a real interactive install with no cached sudo ticket:
- ufw: `sudo -n ufw status` is refused, so FIREWALL=unknown. The user gets a warning and copy-paste commands, and the firewall
  is **not** opened (this is G2 on the second PC again).
- firewalld: `--state` needs no sudo, so detection says running. Every `sudo -n` add is then refused and only
  "firewalld refused the rule …" is printed. **No commands are printed**, nothing is added, and there is no reload. The user is
  left with nothing to run.
This breaks P23 ("`sudo` only here, interactive; no TTY or `sudo -n` refused -> print the exact commands"). The tests cannot
see it: the harness has no TTY, and the stub `sudo` ignores `-n`.
Fix:
- Compute `INTERACTIVE` once at top level, e.g. `[[ -t 0 && -t 1 ]] && INTERACTIVE=yes || INTERACTIVE=no` before
  `parse_flags`, and make `priv` and the notice read it. sudo reads the password and writes its prompt on `/dev/tty`, so a
  plain `sudo` inside `$(…)` or in the loop still prompts.
- Print the "sudo may ask for your password" notice **before** `detect_firewall`, because `priv ufw status` is the first prompt.
- When any rule fails (ufw or firewalld), fall back to `print_firewall_commands "$FIREWALL"`.
- Add a test that runs `install.sh` under `script -qec` (util-linux; skip when absent) and asserts `sudo ufw …` without `-n`.

**2. MAJOR (IN2): `env -u HERMES_HOME` does not mean "default home"; Hermes' sticky `active_profile` wins.**
`scripts/install.sh:272`. With `HERMES_HOME` unset (or pointing at the root), the Hermes CLI reads
`~/.hermes/active_profile` (`hermes_cli/main.py:606-620`, which applies the sticky profile when HERMES_HOME is unset or not
`…/profiles/<x>`). So after `hermes profile use <name>`, `env -u HERMES_HOME hermes gateway install` installs and starts
`hermes-gateway-<name>.service` for `~/.hermes/profiles/<name>`. Under a multiplexer config it may instead exit 78
(`gateway.py:4264-4280`). The aristotle-server unit dials `~/.hermes` (`server/internal/hermes/hermes.go` `resolveHome`), and
the server does not read `active_profile`. Result: either an extra gateway that is enabled at boot and can fight the user's
Desktop/other gateway for the same bot tokens, or a failed install. Either way the installer polls for 30 s, exits 1 **after**
the binary swap, and prints the journal of `hermes-gateway`, which is the wrong unit. This breaks P24 ("the **same Hermes home**
the service dials").
Fix: `env -u HERMES_HOME "$HERMES_BIN" -p default gateway install < /dev/null`. `resolve_profile_env("default")` returns the
root (`profiles.py:2397-2400`), and `-p` before the subcommand is pre-parsed (`main.py:474-501`). Update the comment at
`:256-258` and make the gateway test assert `hermes -p default gateway install` (the stub already records args).

**3. MINOR (IN2): a failed gateway step does not print the reason, and the `error` state is handled as "not running".**
`scripts/install.sh:263-273`, `:250-254`.
(a) P24: "Still unreachable -> exit 1 **with the reason** and the gateway journal tail". Porcelain carries only the state
(by design, P22), and `gateway_failed` prints only "the server still cannot reach Hermes after 30s". For the most confusing
causes the gateway journal looks healthy: `unsupported hermes runtime protocol`, `control socket belongs to another user`,
`not ready: missing capability`.
(b) For `hermes error` (a gateway **answered** identify but is not usable), the script says "Hermes gateway is not running;
installing it as a user service" and runs `hermes gateway install`. That is misleading and normally a no-op. In one corner it
is harmful: non-interactive install stops and removes a legacy `hermes.service` gateway (`gateway.py:3562-3569`, `:2649-2650`).
That cuts the live sessions of a gateway that was answering.
(c) After `exit 1` the user is not told that the server itself is installed and running, or that linger, firewall and pairing
were skipped (`:435-437` never run), so re-running is needed.
Fix:
- Run `hermes gateway install` only for `not-running`. For `error`, just `wait_for_hermes` (this covers a gateway that is still
  starting).
- On failure print the server's own line: `"$TARGET" status 2>/dev/null | grep '^Hermes:'`.
- Add one line: "aristotle-server is installed and running; fix Hermes, then run this installer again (linger, firewall and
  pairing were skipped)."
- Test the `error` state: no gateway call, reason printed.

**4. MINOR (IN2): ufw parsing depends on the locale; a translated "Status: active" silently skips the firewall.**
`scripts/install.sh:334-339`, `:361`. Both strings are gettext'd in ufw (`ufw/backend_iptables.py:485,489`
`_("Status: active…")`, `:1079` `_("Skipping adding existing rule")`), and Debian/Ubuntu ship ufw translations. With a
translated status line (e.g. `Status: aktiv`) the `"Status: "*` arm treats ufw as inactive, and the script prints "no active
firewall detected; nothing to open": the G2 bug, silently. "existing rule" only affects the added/kept report, because ufw
dedups by itself. (This Arch PC has no ufw `.mo` files and en_US, so it is not visible here.)
Fix: `priv env LC_ALL=C ufw …` for both `status` and `allow`. Use `env`, not a `VAR=` prefix, so sudo's env policy does not
drop it. Add a stub case that answers in another language unless `LC_ALL=C` is set.

**5. MINOR (IN1, test): data race and possible flake in the probe tests.**
`server/internal/app/status_test.go:165` and `:210` read `stub.calls` without `stub.mu`, while `Identify` writes it at `:133`.
`go test -race -run 'Status|Probe' ./internal/app/` FAILs (2 races, both in the `hangs` case, where `probeHermes` returns on
`ctx.Done()` and nothing orders it against the goroutine). Under load the goroutine may not have run within 50 ms, so
`len(calls)==1` can fail.
Fix: add a `Calls()` accessor under the mutex, and in the hang case wait (bounded) for the call to be recorded.

### Can be deferred (after 2.2.0)

**6. MINOR (IN2): the gateway and firewall decisions trust one porcelain snapshot without checking `server running`.**
- If the server stops between `wait_for_status` and `read_porcelain` (crash-loop), the porcelain holds only
  `service/enabled/server not-running`. The script then warns "cannot check Hermes: this aristotle-server does not report it"
  (`:265`), says "the server reports no LAN address, so no rule is needed" (`:407-409`), and **exits 0**.
- The control socket is served before the listen manager's first `Reconcile` (`server/internal/app/serve.go:197-211`), so an
  early answer can carry no `lan` lines and the firewall step is skipped. The window is small, but the result is silent.
Fix: `ensure_gateway` requires `server running`, else `on_failure … ; exit 1`. When `port` is present but no `lan` line, re-read
once after 1-2 s. Word the message "the server did not report a LAN listener", not "no rule is needed".

**7. MINOR (IN2, tests): gaps and one misnamed case.**
- `install_test.sh:404-408` "a failing firewall rule never fails the install" uses `STUB_SUDO_REFUSE`, so it fails at
  **detection**. The rule-failure paths are untested: `add_ufw_rule` warn (`:363`), firewalld add refused (`:377`, today no
  commands are printed), and reload failure (`:389`).
- Untested: the interactive path (finding 1), the `error` state (finding 3), server not running at the gateway step (finding 6).
- Test isolation: the "no firewall tool" branch is unreachable because `has_tool` (`:215`) hard-codes `/usr/sbin:/sbin`, and
  the PM PC has `/usr/sbin/ufw`. The `clean_path` farm also links `/usr/sbin`, so a test that dropped the ufw stub would run the
  real `/usr/sbin/ufw` through the sudo stub. That is harmless today (it is not root), but it is not hermetic.
Fix: add a seam, e.g. `SBIN_DIRS="${ARISTOTLE_SBIN_DIRS-/usr/sbin:/sbin}"` (tests set it empty and drop the stubs), then add
the cases above.
On whether the tests check behaviour: mostly yes. They assert recorded argv in `calls.log` (rule args, port 8799,
`HERMES_HOME=<unset>`, no ufw calls under `--no-firewall`), not just output text.

**8. MINOR (IN1): duplicated Hermes probe with different classification.**
`hermesRunning` (`controlcmds.go:231-246`, literal `2*time.Second`) and the new `probeHermes` (`:183`, `hermesProbeTimeout`)
both run Identify with a 2 s timeout and classify `ErrHermesNotRunning`. They disagree on the other errors: pc-status (phone)
says Hermes "running" for `ErrNotReady`/unsupported protocol, while `status` says `error`. P22 says the status fact "is the
truth the phone sees". PROJECT.md "no duplication".
Fix: build `hermesRunning` on `probeHermes` (running = state != not-running, or decide that error = not running) and share the
constant. Optional NIT: the goroutine+select in `probeHermes` is redundant because `Connector.Identify` honours ctx.

**9. MINOR (IN2): firewalld zone and reload.**
`add_firewalld_rule` (`:369-379`) writes to the **default zone** only. If the LAN interface is bound to another zone, the rule
never matches, yet it is reported as "added". `--reload` (`:389`) also drops any runtime-only rules the user had.
Fix: use `--zone="$(firewall-cmd --get-zone-of-interface=<iface>)"` with the iface from the `lan` line (that is what the
porcelain iface is for), and add runtime + `--permanent` instead of reloading.

**10. MINOR (IN2): a user who exports a non-default HERMES_HOME on purpose.**
The server dials `$HERMES_HOME` from the systemd **user manager** environment, else `~/.hermes`. The unit sets none, and this
PC's manager env has no `HERMES_*` (`systemctl --user show-environment`, read-only). For a shell that exports, say,
`HERMES_HOME=/opt/h`, the server dials `~/.hermes` and reports `not-running`. The installer then creates and enables a
**second** gateway on a possibly unconfigured `~/.hermes` and reports success: the phone ends up on the wrong Hermes. That is
worse than the old "report only". (For `~/.hermes/profiles/<x>` the root gateway multiplexes, so `env -u` is right; that is
the case it was built for.)
Fix: if the inherited `HERMES_HOME` resolves outside `~/.hermes` (neither the root nor `profiles/*`), warn that the service
dials `~/.hermes` and skip the gateway install (exit 1 or require `--no-gateway`). Optionally also warn when
`systemctl --user show-environment` has `HERMES_HOME`.

**11. NIT (IN2): validate inputs before root commands (defence in depth).**
There is no injection today. `subnet` comes from `netip.Prefix.Masked().String()` through `porcelainWord`
(`servicecmds.go:210,221`), so it never holds a space or quote; values go to `sudo` as separate quoted argv, with no `eval`;
the firewalld rule text is inside `"…"`. Tailscale lines are never used (`porcelain_lan` takes only `$1=="lan"`), and source
is never `any`.
Still, `[[ $port =~ ^[0-9]+$ ]]` and an RFC1918 CIDR check with prefix >= /8 before any `priv` call costs two lines. A
misconfigured `/0`-`/7` on-link prefix would otherwise open the port beyond the LAN.

**12. NIT (IN2): `has_tool` finds a `/usr/sbin`-only `firewall-cmd`, but `:343` runs it with the user's PATH.**
I verified the bash behaviour: a temporary `PATH=` on `command -v` does not carry over to the later call. The result is
"command not found" -> unknown. Use the path `command -v` resolved.

**13. NIT (IN1)**
- `porcelainWord("")` produces an empty field (e.g. `version ` with a trailing space). That breaks "single spaces" and the
  positional parsing; emit `-`/`unknown`. Practically only `version` could be empty: `State()` never returns "", and
  LAN `Subnet` is always valid.
- `status_test.go` imports: `internal/control` sits in the std-lib group.
- `type answer struct{ err error }` could be `chan error`.
- The `app.go:34` help line is ~130 columns.

**14. NIT (IN2)**
- `priv`'s comment (`:211`) promises a prompt that never happens until finding 1 is fixed.
- `print_firewall_commands` (`:306-316`) rebuilds the ufw argv as text, separately from `add_ufw_rule`: two sources for one
  command shape. Consider one argv builder printed with `printf '%q '`.
- `loginctl enable-linger` can raise a polkit password prompt over SSH; acceptable, but say so or pass `--no-ask-password`
  when not interactive.

## Answers to the review questions

1. **Contract (P22).** Matches:
   - Line set and order: `service`, `enabled`, `server`, then only when answering `version`, `port`, `lan…`, `tailscale…`,
     `hermes` (`servicecmds.go:191-218`). `Active()` sorts LAN before Tailscale (`listen/manager.go:216-226`).
   - Single spaces via `porcelainWord`, except an empty value (NIT 13).
   - IPv6 is unbracketed (`Addr.String()`, `controlcmds.go:172`; test `status_test.go:57`).
   - The exit code is shared with plain `status` (`servicecmds.go:153-156`), and `hermes error` still exits 0 (tested).
   - Parsing: values with spaces become `_`. `iface` is never used by the installer, and odd characters are sanitized to `?`.
     An old server gives no `port`/`lan`/`hermes`, which leads to the "cannot check Hermes" warning (tested) but also a
     misleading "no rule is needed" (finding 6).
   - Note: LAN listeners are IPv4-only (`listen/addr.go:28-32`), so the firewalld ipv6 branch is currently dead (harmless).
2. **Hermes probe.**
   - `Identify` is side-effect free. It sends one `identify` verb (`hermes/control.go:69-86`), no ticket, and nothing is
     logged. The reason is an error string (socket path, state), with no tokens.
   - No goroutine leak: Identify honours ctx (`DialContext` `:186`, `SetDeadline(deadlineFor(ctx))` `:197`, `AfterFunc`
     close `:198`), and `probeHermes` cancels on return. So a hung Hermes costs at most 2 s per call, well under the
     control client's 5 s `ioTimeout` (`control/control.go:24`).
   - Exposure through the terminal allow-list (`terminalcmds.go:30`): **acceptable.** A remote-terminal process already runs
     as the user and can read `ip addr` and dial the Hermes socket itself. Reachability plus on-link subnets reveal nothing it
     could not get directly, and `status` already exposed addresses.
3. **Same Hermes home.**
   - The builder's claims are confirmed:
     - Non-TTY install starts without prompts (`gateway.py:5171-5185`, `_flag` -> True).
     - An existing current unit gets "already installed" + enable + return, and an outdated one is rewritten in place
       (`:3578-3604`).
     - `systemd_start` does `systemctl start`, a no-op on a running gateway, and never restarts it (`:3704-3710`).
   - **Exceptions:**
     - The sticky `active_profile` breaks "same home" (finding 2, MAJOR).
     - Non-interactive install stops a legacy `hermes.service` (`:3562-3569`), reachable only via the `error` state
       (finding 3).
     - For a deliberately non-default HERMES_HOME the installer makes things worse, not just reports (finding 10).
4. **Firewall.**
   - Sources are LAN subnets only; no Tailscale rule and never `any` (tested).
   - Idempotent: ufw dedups by itself, firewalld via `--query-rich-rule`. Report text and **detection** depend on the locale
     (finding 4).
   - Rich-rule syntax is valid; the family is picked by colon.
   - `sudo`/`priv` appear only at `:334,360,372,374,389`, all in the firewall step. `sudo -n` never hangs, and no password
     ever passes through the script.
   - The interactive path is dead (finding 1).
   - Detection via `priv ufw status` is acceptable (ufw status needs root) **if** the notice comes before it.
   - No injection path (NIT 11).
5. **Failure paths.**
   - Gateway exit 1 after the swap prints the journal tail but lacks the reason and the "server is up, rerun" line (finding 3).
     The rollback command is rightly not shown, because the rollback would not fix Hermes.
   - Firewall and linger failures never fail the install (tested; `:281-285`, every `priv` is guarded).
   - `set -e` review: none of these abort the script: `loginctl show-user` (inside `[[ "$(…)" ]]` in an `if`), the missing
     loginctl binary case, `awk` on empty input (exit 0), `read` at EOF (loop condition), `[[ … ]] && …` lists (never the
     function's last command), `read_porcelain` (`|| true`).
6. **Tests.** See finding 7. Not covered: the interactive sudo path (where finding 1 lives), the no-tool branch (unreachable;
   isolation bug), rule-failure paths, the `error` state, sticky profile. The Go tests guard behaviour; one race (finding 5).
7. **Code shape.**
   - Small, named, commented functions. A single porcelain reader (`read_porcelain`/`porcelain_value`/`porcelain_lan`) feeds
     every step.
   - Duplication: `hermesRunning` vs `probeHermes` (finding 8); ufw argv built twice (NIT 14).
   - Misleading: the `priv` comment, the "not running" message for `error`, the `ufwfail` test name.

## Files
- Created: `docs/release/runs/IN2/review-report.md` (this file). Throwaway probe in `~/.hermes/profiles/pm/cache/scratch/in2review/`.
  No repo file edited.
