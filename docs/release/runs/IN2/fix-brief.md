# IN2b fix brief — installer: sudo that really prompts, the right Hermes home, locale, honest failures

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, spec `docs/release/spec.md` "Complete installer"
(Q136, Q137, P22-P25), the IN2 brief/report and **the review `docs/release/runs/IN2/review-report.md`** (findings by number below;
read each finding's evidence there). The reviewer's pty probe is in `~/.hermes/profiles/pm/cache/scratch/in2review/` (reuse it).

## Fix (all required)
1. **Review #1 MAJOR — sudo never prompts.** Decide interactivity ONCE at the top (`INTERACTIVE=yes|no` from `[[ -t 0 && -t 1 ]]`
   before any redirection) and make `priv` use that variable, never re-test the tty inside `$(...)`/here-string loops. Make sure
   the interactive `sudo` reads the password from the terminal even inside a `while read ... <<<` loop (feed the loop on another
   fd, e.g. `while read -r ... <&3; done 3<<< "$RULES"`, or loop over an array) — sudo itself reads `/dev/tty`, but verify by
   test. Print the "sudo may ask for your password" notice before detection. When ANY rule fails or detection is `unknown`,
   print the exact commands for the detected firewall (firewalld users currently get no commands). Add a test that runs the
   installer under a pty (`script -qec '<cmd>' /dev/null`, skip with a reason when `script` is missing) and asserts the sudo
   stub saw a plain `sudo` (no `-n`) call for detection and for each rule.
2. **Review #2 MAJOR — wrong Hermes home via sticky profile.** Call `"$HERMES_BIN" -p default gateway install` (still with
   `env -u HERMES_HOME`, stdin `/dev/null`). Cite in a code comment why (`hermes_cli/main.py` reads `~/.hermes/active_profile`
   when no `-p` is given; `-p default` = the root home the server dials). Test: the hermes stub records argv; assert
   `-p default gateway install`.
3. **Review #3 MINOR — failure gives no reason / `error` state.** On final failure print the server's Hermes reason (plain
   `status` `Hermes:` line) plus the journal tail, and say plainly what state the PC is in: "aristotle-server <version> is
   installed and running; linger, firewall and pairing were NOT done; fix Hermes, then run the installer again." State
   `error` (the gateway answered something) must NOT run `gateway install`: print the reason and the advice, then fail the
   same way. Tests for both.
4. **Review #4 MINOR — locale.** Run ufw (status and allow) under `LC_ALL=C` through sudo (`priv env LC_ALL=C ufw ...`) and
   `firewall-cmd` too. Test: the stubs record the env and the test asserts `LC_ALL=C`.
5. **Review #5 MINOR (IN1 test) — race.** `server/internal/app/status_test.go:165,210`: read the stub's calls under its lock
   (or atomic). `cd server && go test -race -count=1 ./internal/app/` must pass.
6. **Review #6** — the porcelain must say `server running` before any step trusts it; otherwise (dead server) the gateway and
   firewall steps report "cannot read the server status" and the install fails as `wait_for_status` would. Also: when the
   server answers but reports no LAN listener yet (first scan not done), re-read the porcelain for a few seconds (bounded by
   the same timeout) before concluding "no LAN address". Tests.
7. **Review #7 test isolation** — make `has_tool`'s extra dirs overridable for tests only (e.g. `ARISTOTLE_SBIN_DIRS`, same
   style as `ARISTOTLE_CGROUP_FILE`) and add the "no firewall tool at all" test; rename the misnamed `ufwfail` case and add a
   real "ufw refuses a rule" case.

## Not in this round (leave alone)
Review #8-#14 are deferred by the PM (board table). In particular do NOT change the HERMES_HOME policy beyond `-p default`
(#10 is a user decision, pending).

## Files you may edit
`scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/*`, `server/internal/app/status_test.go` (race only).
The repo-root `install.sh` and `scripts/test/root_install_test.sh` are finished: do not touch.

## Verify (real exit codes in the report)
`bash scripts/test/run.sh` (all script suites), `cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./internal/app/`.
Show each new test RED against the pre-fix file (scratch copy, never git).

## Hard rules
Never git checkout/restore/stash/reset/commit/push. Never run the real installer, real sudo/ufw/firewall-cmd/loginctl writes,
`systemctl --user` writes or real `hermes ... gateway ...`; never touch `~/.hermes` state, `~/.local/bin`, running services.
Use absolute paths in probes so no real tool is reached by accident.
Report: `docs/release/runs/IN2/fix-report.md`.

## Stop and ask if
`-p default` does not exist in the installed Hermes version on this PC (`hermes --help` / `hermes_cli/main.py` argparser), or
sudo under a pty still cannot prompt inside the loop after the fd change.
