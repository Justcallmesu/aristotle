# IN2 build report

## Files changed
- `scripts/install.sh` (extended; mode 0755 kept)
- `scripts/test/install_test.sh` (new cases; `new_case` copies the new stubs; `run_install` uses a hermes-free PATH)
- `scripts/test/stubs/aristotle-server` (`status --porcelain`; env: `STUB_PORCELAIN_HERMES` reachable|not-running|error|none, `STUB_PORCELAIN_PORT`, `STUB_PORCELAIN_LAN`, `STUB_HERMES_AFTER_POLLS`)
- New stubs: `hermes`, `loginctl`, `sudo`, `ufw`, `firewall-cmd` (all record to `$STUB_LOG`; the hermes stub also records `HERMES_HOME=` or `<unset>`).
- Not touched: `server/`, root `install.sh`, `root_install_test.sh`, `docs/release/README.md`, git.

## What install.sh does now
parse_flags (any order of `--pair|--no-pair`, `--no-gateway`, `--no-firewall`; else usage, exit 2) -> refuse_in_app_terminal -> `check_hermes_cli` (exit 1 before anything is touched) -> verify/app/binary/install/wait (unchanged) -> `ensure_gateway` -> `ensure_linger` -> `open_firewall` -> old tail.
One porcelain reader (`read_porcelain`, `porcelain_value`, `porcelain_lan`) is used by all three new steps.
Functions: `check_hermes_cli`, `ensure_gateway`, `wait_for_hermes`, `gateway_failed`, `ensure_linger`, `detect_firewall`, `open_firewall`, `apply_firewall_rules`, `add_ufw_rule`, `add_firewalld_rule`, `rich_rule`, `print_firewall_commands`, `priv`, `interactive`.

## Tests added (all in `install_test.sh`)
Flag parsing; hermes missing (nothing installed, nothing run); `--no-gateway` without hermes; `~/.local/bin/hermes` fallback; reachable (no gateway call); not reachable (gateway install with `HERMES_HOME=/x` exported, recorded as `<unset>`, reachable on poll 3); never reachable (exit 1, journal tail); `hermes gateway install` failing (exit 1, journal); `--no-gateway` + not reachable (warning, exit 0); no porcelain hermes line (warning, exit 0); linger no/yes/failing; ufw with 2 subnets and port 8799 (exactly 4 allow rules, no Tailscale address, `sudo -n`); ufw existing rule reported as kept; firewalld add + reload, existing rule not re-added, no reload without change, ipv6 family; no active firewall; no LAN listener; sudo refused (commands for both firewalls printed, exit 0); `--no-firewall` (commands printed, no sudo/ufw call); unusable firewall never fails the install.

## RED before
Same new test file + stubs run against a scratch copy of the OLD `install.sh` (`~/.hermes/profiles/pm/cache/scratch/install.sh.old`, no git): exit 1, 54 FAIL lines. 53 are real (e.g. `--no-gateway --no-firewall --no-pair: exit 2, want 0`; `hermes missing: exit 0, want 1` with the binary installed and `aristotle-server install --force --start` run; no gateway/linger/firewall calls; no `Hermes: reachable`). The 54th (`scripts/release.sh is not executable`) is an artifact of my scratch tree lacking release.sh, not a real failure.

## Hermes source (read-only, `~/.hermes/hermes-agent/hermes_cli/`)
- `gateway.py:5200-5230` (`_cmd_install`) -> `_install_systemd_from_cli` `gateway.py:5163-5185`: on a non-TTY stdin (`non_interactive`) `--start-now` and `--start-on-login` default to True without prompting (`_flag`, 5174-5178). So `hermes gateway install </dev/null` installs, enables and starts. Flags exist in `subcommands/gateway.py:173-180`; I use none (the defaults are what I want).
- Unit already exists and is current: `gateway.py:3593-3612` prints "Service already installed ... Use --force to reinstall", enables if disabled, ensures linger, returns; then `systemd_start` (`gateway.py:3704-3711`) still runs `systemctl start` (a no-op on a running gateway; it never restarts/replaces it). Outdated unit: refreshed in place (3598-3604). No `--force` used.
- Prompts: only `prompt_yes_no` (`gateway.py:3566` legacy units: auto-yes when non-interactive; `cli_output.py:67-86` returns "" on EOF). No stop condition hit.
- Unit pinning another HERMES_HOME: `unit_belongs_to_caller` -> `sys.exit(1)` (`gateway.py:3556-3558`); the installer then prints the journal and exits 1.
- `systemd_install` also runs `_ensure_linger_enabled` (`gateway.py:3409`), so linger may already be on by the time `ensure_linger` runs (then it does nothing).
- Side effect to know: non-interactive install removes legacy Hermes units (`gateway.py:3563-3569`).
- ufw: "Skipping adding existing rule" is in `/usr/lib/python3.14/site-packages/ufw/backend_iptables.py:1079`, so re-running `ufw allow` adds no duplicate (script reports it as "kept").

## Verify
`bash scripts/test/run.sh install` -> `install_test: all passed`, `PASS install_test`, **exit 0** (~12 s). `bash -n scripts/install.sh` ok. `shellcheck` is not installed: not run (not installed, per brief).

## Deviations / notes
- Flags are parsed before `refuse_in_app_terminal` (as today; the brief's order lists refuse first). Only visible with a bad flag inside the app terminal (exit 2 vs 1).
- Tool detection (`has_tool`) also looks in `/usr/sbin`, `/sbin` (ufw is absent from a normal user PATH on Debian). Firewall verdict is read from `sudo ufw status` first line / `firewall-cmd --state` (the latter without sudo); any other output (e.g. sudo refused) = "cannot check": warn and print the commands for both firewalls. No tool at all gives the same output. The "no tool exists" branch is not unit-tested because `has_tool` finds the real `/usr/sbin/ufw` on a dev PC.
- Interactive sudo (TTY) path is not covered by tests (no TTY in the harness); code is `priv` -> plain `sudo`.
- `hermes gateway install` failing exits 1 at once (journal tail) rather than polling out the timeout.
- Tests build a symlink farm PATH without any `hermes` (real `/usr/bin/hermes` exists here), so the "hermes missing" case is hermetic.
- Go's `loginctl` advice line (`server/internal/app/servicecmds.go:74`) left for the server worker.
