# Re-review: IN2 (after IN2b) + IN3 (after IN3b), deep, fresh context

Scope: working tree on 41fb0ad. IN2 = `scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/*`. IN3 = repo-root
`install.sh`, `scripts/test/root_install_test.sh`. IN1 = only `server/internal/app/status_test.go`.

## Verdict: CHANGES-REQUIRED (one MAJOR, a one-line fix). Everything else can wait.

All seven findings from the first review are closed (table below). The fix round added no new defect in the sudo, locale, porcelain
or firewall paths. One issue the first review missed remains (N1): the installer waits only 30 s for the Hermes gateway to become
**ready**. On this PC a gateway start takes 25-43 s to reach ready, so in the main second-PC scenario a working install can end with
exit 1 and "fix Hermes". The PC is not left worse off, and running the installer again fixes it. If the user accepts "run it again"
as the workaround, N1 drops to MINOR and both tickets are APPROVED-WITH-FINDINGS.

IN3 on its own: APPROVED-WITH-FINDINGS (one MINOR, a few NITs).

## What I ran (read-only, stubs only, absolute paths)
- `bash scripts/test/run.sh`: exit 0 (install_test, release_test, root_install_test all PASS, 0 FAIL lines).
- `cd server && gofmt -l .` printed no files. `go vet ./internal/app/` passed. `go test -race -count=3 ./internal/app/` -> `ok` (27.9 s).
- I ran the first reviewer's pty probe again (`~/.hermes/profiles/pm/cache/scratch/in2review/tty_probe.sh`, new case dirs
  `ufw2`, `fwd2`, `ufwnot`). I also ran my own probe, `~/.hermes/profiles/pm/cache/scratch/in2rereview/probe.sh`. It types
  "passwords" into the pty 1.5 s apart, not ahead of time, and uses `STUB_SUDO_ASKTTY=1` and `ARISTOTLE_SBIN_DIRS=`.
- I ran 6 extra mutants of `scripts/install.sh` from scratch copies via `INSTALL_SH=` (the repo file was not edited). Results are
  under N2.
- I ran a root-installer probe in a scratch fake repo (`in2rereview/root/`): empty args, args with spaces or `$`, relative
  `--bundle`, `--bundle=`, `--bundle ""`, a non-executable newest bundle, and TTY passing through `exec`.
- Read-only sources: Hermes `hermes_cli/{main.py,profiles.py,gateway.py}`, `gateway/{run_bootstrap.py,run_runtime.py,control_socket.py}`,
  server `internal/listen/manager.go`, `internal/hermes/control.go`, and the journals (`journalctl --user`) and `~/.hermes/logs/gateway.log`,
  read only.

## Review findings #1-#7 (first review)

| # | State | Where | Evidence |
|---|---|---|---|
| 1 sudo never prompts | **CLOSED** | `scripts/install.sh:33` (INTERACTIVE once), `:215` priv, `:461` notice before `:462` detect, fd-3 loops `:333-339`, `:413-415`, fallback `:467-470` | The probe under a pty logs only plain `sudo env LC_ALL=C ufw status` and one plain `sudo … ufw allow …` per rule (4 rules with 2 subnets and port 8799). For firewalld: plain query/add per rule plus `--reload`. No `sudo -n` anywhere. With passwords typed 1.5 s apart, the stub read one password from `/dev/tty` before detection and one before each rule, and both rules were applied. With sudo refused, the exact ufw and firewalld commands were printed. Tests: `install_test.sh:521-546`. |
| 2 sticky profile | **CLOSED** | `:296`, comment `:276-278` | `main.py:600-620` reads `active_profile` only when no `-p` is given. `profiles.py` `resolve_profile_env`: `canon == "default"` returns the root. The `-p default` pre-parse is at `main.py:474-505`. The multiplexer exit 78 applies only to named profiles (`gateway.py:4264`), so not to `default`. Test: `install_test.sh:301`. |
| 3 reason / `error` state | **CLOSED** (test gap, N2) | `:262-271`, `:292-297` | `error` never runs `gateway install`. The installer waits, then prints the server's `Hermes:` line, the journal tail and the "installed and running; linger, firewall and pairing were NOT done" line. Tests: `:307-338`. |
| 4 locale | **CLOSED** | `:359`, `:368`, `:386`, `:399-401`, `:417` | `env LC_ALL=C` comes after sudo, so env_reset does not drop it. The ufw stub answers `Status: aktiv` unless `LC_ALL=C`. Test: `:472`. PM mutant killed. |
| 5 race | **CLOSED** | `status_test.go:143-157,182,224` | `Calls()` reads under the mutex, and `waitCalls` waits at most 2 s. `-race -count=3` passes. |
| 6 trust in a stale snapshot | **CLOSED** | `:254-258`, `:282`, `:432-442`, message `:452` | `server running` is required before the gateway step and on every firewall read. The 5 s LAN re-read is enough: `listen.Manager.Run` reconciles at once (`manager.go:49-54`). In the journal, every daytime restart had its LAN listener within about 80 ms of `listening` (for example 11-09 11:42:57.275 -> .351). The 10 s gaps in the journal all come right after boot, when the network is still coming up, and no install runs then. Tests: `:340-354`, `:433-447`. Mutants m1/m2 below are killed. |
| 7 isolation / gaps | **CLOSED** | `:29`, `:218`; `install_test.sh:41-55` | The `clean_path` farm drops real `ufw` and `firewall-cmd`. New cases: no tool (`:486`), ufw refuses (`:496`), firewalld refuses (`:507`), reload fails (`:517`). The misnamed case was renamed. |

## New findings

### Must fix (or accept explicitly)

**N1. MAJOR (IN2): the gateway is given only 30 s to become ready, which is often too short.**
`scripts/install.sh:242-249,298` (`wait_for_hermes` uses `TIMEOUT`, the same 30 s budget as the server start).
- The server counts Hermes as `reachable` only when identify returns `state "ready"` with `session-authority-v1`
  (`server/internal/hermes/control.go:91-95`; anything else is `error`).
- Hermes publishes `ready` last: after MCP discovery (which may block up to 120 s), `runner.start()` (platform connects), cron and
  housekeeping (`gateway/run_bootstrap.py:863-876`, `run_runtime.py:691-696`).
- The unit is `Type=simple` (`~/.config/systemd/user/hermes-gateway.service`), so `hermes gateway install` returns as soon as the
  process has been spawned.
- Measured on this PC: time from systemd "Started Hermes Agent Gateway" to "Gateway housekeeping started" (= ready) for the last 8
  starts was 25.1, 24.7, 28.1, **43.4**, 29.3, 29.5, 24.6 and 28.8 s. Opening the control socket alone takes about 8 s, and the
  Telegram connect about 16 s. One start in eight goes over 30 s, and four more finish within 1.5 s of the limit. These were all
  cold starts at login.

Effect in the main scenario: the server is up and Hermes is fine but still `starting`, yet the installer exits 1. It prints
`Hermes: not running (hermes is not ready: state "starting")`, "fix Hermes", and says linger, firewall and pairing were not done.
Running it again succeeds. Nothing is left worse, but the output is wrong and the user has to run it twice.

Fix (2-3 lines): give the gateway wait its own budget, e.g. `GATEWAY_TIMEOUT="${ARISTOTLE_GATEWAY_TIMEOUT:-120}"`, and use it in
`wait_for_hermes` and in the `gateway_failed` text. Print "waiting for Hermes to become ready (up to Ns)…" before the wait. Tests
keep a small value.

### Can be deferred

**N2. MINOR (IN2, tests): the "reason on failure" line is not guarded.**
Mutant m6 replaced `"$TARGET" status 2> /dev/null | grep '^Hermes:'` in `gateway_failed` (`:266`) with `true | grep …`. The suite
still passes, exit 0. The assertions at `install_test.sh:311,323,333` look for `Hermes:   not reachable: stub reason`, which
`wait_for_status` already prints at the start, before the gateway step (`:158-160`).
Fix: have the stub return a different reason after the gateway step (e.g. `STUB_HERMES_REASON_LATE`), or check that the line comes
after `error:`.
Mutants m1 (drop `require_server_running` in the LAN loop), m2 (no LAN re-read), m3 (no command fallback after a refused rule),
m4 (`error` treated as `not-running`) and m5 (`RULE_FAILED` reset) were all KILLED (3, 3, 5, 1 and 4 FAIL lines).

**N3. MINOR (IN3): a newer bundle whose `install.sh` lost its x bit is skipped silently, and an older one is installed.**
`install.sh:66-70`. Probe: with `aristotle-2.10.0/install.sh` at mode 644 and 2.9.0 executable, the output was
`Installing …/aristotle-2.9.0`, exit 0. That is a downgrade with only one line to notice it. A bundle copied through a zip or a
browser download can easily lose the x bit.
Fix: pick the highest version among the valid names whatever the mode. `require_bundle`'s `-x` check then fails loudly, or run
`bash "$BUNDLE_DIR/install.sh"` instead of needing the x bit. The `-x` filter came from the brief, so this is a brief-level change.

**N4. NIT (IN3): help and error texts.**
- `--help` (`:12,38`) does not list the bundle flags (`--pair`, `--no-pair`, `--no-gateway`, `--no-firewall`), so a user who runs
  `./install.sh --help` never learns about them. Add one line, "other flags go to the bundle's install.sh: …".
- `--bundle=dir` is passed through to the bundle installer, which then exits 2 with its own usage. Either accept `--bundle=*` or
  reject it as a usage error.
- The no-bundle text (`:84-86`) leaves out what the brief asked for: `release.sh` needs the signing key and works on the dev PC only.
  "copy a bundle here" is also unclear, because a bundle copied into `dist/` would need no `--bundle`.

**N5. NIT (IN2): small honesty and consistency gaps.**
- `:473`: when an interactive sudo fails (wrong password three times, or the user is not in sudoers), the warning says "no tool
  found, or sudo needs a password that cannot be asked". The printed commands are still right.
- `gateway_failed` (`:269`) says "is installed and running" without checking. If the server dies while waiting, the version shows
  as `?` and the claim is false.
- `offer_pairing` (`:199`) tests the tty again, against the rule at `:31-32`. This is harmless at top level; use `$INTERACTIVE`.
- The bundle installer itself does not refuse root (this predates IN2; only the root installer has the guard).

**N6. NIT (IN2): `ARISTOTLE_SBIN_DIRS` replaces the default dirs instead of adding to them.** It is acceptable.
`${ARISTOTLE_SBIN_DIRS-/usr/sbin:/sbin}` (`:29`): when the variable is unset you get the defaults. When it is set, even to empty,
its value replaces them. It is applied only to `command -v` inside `has_tool` (`:218`; a temporary `PATH=` does not carry over).
Root commands are resolved by sudo (`sudo env … ufw`), and `firewall-cmd --state` uses the user's PATH. So the variable cannot make
root run another binary. At worst detection becomes `missing`, which prints a warning plus both sets of commands, never a silent
skip. It works the same way as `ARISTOTLE_CGROUP_FILE`. Optionally say "replaces" in the comment.

## Answers to the brief's questions

**2. New risks from the fix.**
- **fd-3 loops:** nothing inside the loops reads stdin. The loops run `sudo`, `env`, `ufw allow`, `firewall-cmd --query/--add` and
  `echo`/`printf`. Real sudo reads the password from `/dev/tty`, and the probe confirmed prompts inside the loop. fd 3 is inherited
  by children harmlessly, since sudo closes extra fds. So fd 3 is defensive: the pty test would pass without it. That is fine.
- **5 s LAN re-read:** bounded (`min(5, TIMEOUT)`) and ample (see #6). It only costs 5 s on a PC that has no LAN.
- **`error`-state wait:** correct, and it no longer touches a legacy `hermes.service`. Its budget is too short (N1).
- **`ARISTOTLE_SBIN_DIRS`:** see N6.
- **Partial-state messages:** true in every path I traced (`on_failure` with rollback, `gateway_failed` with the "NOT done" line,
  firewall warning plus commands), except the two NITs in N5.

**3. IN3 against its brief.**
- **Pass-through:** `"${OTHER_ARGS[@]}"` keeps empty args, spaces and `$` literally. The probe got
  `[--no-pair][][a b][$x][--x=1 2]`, and `--bundle` was removed wherever it appeared.
- **Relative `--bundle`:** resolved against the caller's cwd. `exec "rel/b/install.sh"` contains a slash, so there is no PATH
  lookup. The bundle's own `HERE` resolution makes the cwd irrelevant. The case is tested (`root_install_test.sh:117-123`).
- **`exec`:** the exit code is passed through (test: 3). The probe under `script` showed `tty` inside the bundle, so INTERACTIVE
  survives.
- **Version selection:** `sort -V` over the regex-filtered names. The `.aristotle-x.partial` stage dir from `release.sh:24` is a dot
  dir and never matches the glob. The PM mutant is killed. The non-executable case is N3.
- **Root refusal through a PATH-planted `id`:** acceptable. It is a convenience guard, not a security boundary: root can always run
  the bundle installer directly, and anyone who controls PATH controls the script anyway. `sudo ./install.sh` resets PATH to
  `secure_path`, so the real `id` answers. One side effect: it runs before `--help`, so `sudo ./install.sh --help` is refused
  (harmless).
- **Code shape:** small named functions and a header with usage. No trailing whitespace or noise left. `shellcheck` is not
  installed. `bash -n` passes.

**4. End-to-end on the second PC** (Desktop running, no gateway, ufw active, linger on, from a terminal):
1. `./install.sh --bundle ~/aristotle-2.2.0`: not root, the bundle is found, `Installing …`, then exec. stdin and stdout are still
   the terminal, so INTERACTIVE=yes.
2. Bundle: flags, then the app-terminal check, then `hermes` is found (refused here, before anything is touched, if it is missing:
   P24), then checksums, APK pair, binary swap, `install --force --start`, `wait_for_status`.
3. Porcelain says `server running` and `hermes not-running` (no `~/.hermes/gateway.sock`). The installer runs
   `env -u HERMES_HOME hermes -p default gateway install </dev/null`. Without a TTY that installs, enables and starts
   `hermes-gateway.service` for `~/.hermes` with no prompts (`gateway.py:5171-5185`), and Hermes enables linger itself too. Then it
   waits for `reachable`: **N1 applies here.** (Q137, P24 kept, apart from the budget.)
4. Linger is already `yes`, so nothing happens (P25).
5. Firewall: the LAN line is there at once. The notice prints, then `sudo env LC_ALL=C ufw status` gives `Status: active`. With a
   cached sudo ticket there is no prompt; with none, sudo prompts once on the tty and caches. Then two plain `sudo … ufw allow`
   calls, from that subnet only, for 8760/tcp and 5353/udp, none for Tailscale. The output lists the rules added or kept and the
   "older network / run again" line (Q136, P23). On a re-run ufw skips the duplicates, which are reported as "kept".
6. Version, APK note, and the pairing question (y/N on a tty).
- **No sudo cache:** the same steps; the prompt comes at detection, after the notice. A wrong password or no sudo rights gives a
  warning plus copy-paste commands, and the install still succeeds (P23). Only the wording is off (N5).
- **`hermes profile use X` set:** `-p default` overrides the sticky profile, so the gateway goes to the root home the server dials
  (`hermes-gateway`, the same unit `gateway_failed` reads the journal of). If a `hermes-gateway-X` is already running for X, the
  root gateway is added next to it. That is the Q139 behaviour.
- **Desktop running:** no effect on the steps above. The Desktop serves no `gateway.sock`, which matches the original G1 symptom.

## Files
- Created: `docs/release/runs/IN2/re-review-report.md` (this file). No repo file was edited.
- Scratch only: `~/.hermes/profiles/pm/cache/scratch/in2rereview/` (probe, mutant copies, fake root repo) and new case dirs
  `ufw2`, `fwd2`, `ufwnot` under `…/in2review/`.
