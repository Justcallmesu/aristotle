# IN2b fix report

Brief: `fix-brief.md`. Review findings #1-#7 fixed; #8-#14 untouched (HERMES_HOME policy unchanged beyond `-p default`).

## Changes
Files edited: `scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/{sudo,ufw,firewall-cmd,hermes,aristotle-server}`,
`server/internal/app/status_test.go` (race only). Repo-root `install.sh` / `root_install_test.sh` not touched.

1. **#1 sudo prompts.** `INTERACTIVE=yes|no` is set once at the top (`[[ -t 0 && -t 1 ]]`, before any redirection); `priv` reads it.
   The "sudo may ask for your password" notice is printed before `detect_firewall`. Rule loops read on fd 3
   (`while read ... <&3; done 3<<< "$RULES"`), so stdin stays the terminal. Any failed rule, or a failed reload, sets
   `RULE_FAILED` and prints the exact commands for the detected firewall (`print_firewall_commands "$FIREWALL"`); `unknown`/`missing`
   still prints both.
   Pty test (`script -qec`, skipped with a reason when `script` is missing): the sudo stub reads a "password" from `/dev/tty` itself;
   asserts no `sudo -n`, plain `sudo env LC_ALL=C ufw status` / each `ufw allow`, 3 of 3 password reads, both rules applied
   (also firewalld incl. reload, and the interactive refused case). So sudo does prompt inside the loop after the fd change.
2. **#2** `env -u HERMES_HOME "$HERMES_BIN" -p default gateway install < /dev/null`, with a comment citing `hermes_cli/main.py`
   (`~/.hermes/active_profile` read when no `-p`; `-p default` = root home). `-p` exists in this Hermes (`main.py:497`, help text).
   Hermes stub strips `-p <name>` and records argv; tests assert `hermes -p default gateway install`.
3. **#3** Failure prints the server's `Hermes:` line (plain `status`), the gateway journal tail, then "aristotle-server <version> is
   installed and running; linger, firewall and pairing were NOT done; fix Hermes, then run the installer again." State `error` never
   runs `gateway install`: it waits (`wait_for_hermes`) and then fails the same way. Tests for both (and for `error` + `--no-gateway` = warning only).
4. **#4** `priv env LC_ALL=C ufw status|allow`, `priv env LC_ALL=C firewall-cmd ...`, `LC_ALL=C firewall-cmd --state`. The ufw stub now
   answers `Status: aktiv` unless `LC_ALL=C`, and both stubs record the env; tests assert `LC_ALL=C` and no `LC_ALL=<unset>`.
5. **#5** `stubHermes.Calls()` (under the lock) + bounded `waitCalls`; both reads use them.
6. **#6** `require_server_running` (porcelain must say `server running`, else `on_failure "cannot read the server status..."`, exit 1) in
   `ensure_gateway` and in every firewall read. `read_lan_porcelain` re-reads up to min(5s, timeout) when `port` is present but no `lan`
   line; the final message is "the server did not report a LAN listener, so no rule was added". Tests: server dead at gateway step, server
   dying before the firewall step, LAN line appearing on a later read, no LAN after the re-reads.
7. **#7** `ARISTOTLE_SBIN_DIRS` seam (default `/usr/sbin:/sbin`); tests set it empty, and the `clean_path` farm now also drops real `ufw` and
   `firewall-cmd`, so no real firewall tool is reachable. New cases: no firewall tool, "ufw refuses a rule", firewalld refuses, reload fails;
   `ufwfail` renamed `sudodetect` (sudo refused at detection).
   Test-only addition: `INSTALL_SH` env overrides the installer under test (used for the RED runs). Stub only: `aristotle-server` stops
   counting `STUB_HERMES_AFTER_POLLS` once reachable.

## Verification (real exit codes)
- `bash scripts/test/run.sh` -> exit 0: install_test, release_test, root_install_test all PASS (install_test: 237+ checks).
- `cd server && gofmt -l .` -> no files; `go vet ./...` -> exit 0; `go test -race -count=1 ./internal/app/` -> `ok`, exit 0.

## RED before the fix
- Scripts: `INSTALL_SH=<pre-fix install.sh copy> bash scripts/test/install_test.sh` (new tests/stubs) -> exit 1, ~60 FAILs, among them: `-p default` missing;
  "NOT done" line missing; `error` ran `hermes gateway`; server-not-running exits 0; no `LC_ALL=C`; late LAN gives 0 rules; no-tool case ran sudo;
  ufw/firewalld refusing printed no commands; pty: `sudo -n` used, no notice, 0 password reads, 0 rules applied.
- Go: original `status_test.go` with `go test -race -run 'Status|Probe'` -> FAIL, 2x `WARNING: DATA RACE`; fixed file -> ok.
- Pre-fix copies are in `~/.hermes/profiles/pm/cache/scratch/in2fix/` (no git used).

## Notes
- The old tests that assert 4 rules now rely on the LC_ALL stub: against the pre-fix script they fail as a translated ufw would be treated as inactive (intended).
- The no-LAN case now costs up to 5s (re-read) on a PC with no LAN address; tests use `INSTALL_TIMEOUT=2`.
- `has_tool` / PATH issue for a `/usr/sbin`-only `firewall-cmd` (#12) left as deferred.
