# Re-review brief — IN2 (after fix round IN2b) + IN3 (after IN3b), deep, fresh context

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, spec `docs/release/spec.md` "Complete installer" (Q135-Q139,
P21-P25). Inputs: `docs/release/runs/IN2/{build-brief,build-report,review-report,fix-brief,fix-report}.md`,
`docs/release/runs/IN3/{build-brief,build-report,fix-brief,fix-report}.md`.

## Scope (uncommitted, base HEAD 41fb0ad) — review the WHOLE tickets, not only the fix diff
- IN2: `scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/*`.
- IN3: repo-root `install.sh`, `scripts/test/root_install_test.sh` (never reviewed before).
- IN1 (`server/`) was reviewed already; only re-check `server/internal/app/status_test.go` (race fix).

## PM evidence already in hand (hunt elsewhere)
Gate GREEN: gofmt/vet, 805 Go tests, `go test -race ./internal/app/`, script suites install/release/root_install.
Mutants KILLED: sudo forced non-interactive (`install.sh:215`), `-p default` dropped (`:296`), `LC_ALL=C` dropped on ufw allow
(`:386`), earlier HERMES_HOME leak and hard-coded port; root installer: `sort -V`->`sort`, root guard never fires.

## Check
1. Each of review findings #1-#7 (`docs/release/runs/IN2/review-report.md`) is really closed: say CLOSED/OPEN per item with
   file:line. For #1 run the reviewer's pty probe again (`~/.hermes/profiles/pm/cache/scratch/in2review/`, absolute paths,
   stubs only) and confirm plain `sudo` with a terminal for detection and every rule.
2. New risks the fix introduced: fd-3 loops (does any command inside read stdin unexpectedly?), the 5 s LAN re-read, the
   `error`-state wait, `ARISTOTLE_SBIN_DIRS` (test-only override: can it weaken a real run? it should only ever ADD dirs or be
   ignored when unset), messages that tell the user the true partial state.
3. IN3 root installer against its brief: arg pass-through (quoting, empty args, args with spaces), `--bundle` relative path,
   `exec` semantics, root refusal via `id -u` (a PATH-planted `id` is a test seam: is that acceptable for a non-security
   convenience guard? say so), version selection, error texts. Code shape per PROJECT.md.
4. End-to-end reading: walk through the real second-PC scenario (Desktop running, no gateway, ufw active, linger on, run from a
   terminal) step by step through the root installer -> bundle installer, and the same with no sudo cache and with
   `hermes profile use X` set. Does every step do what Q135-Q139 promise?

This is the last allowed fix round for IN2. A CHANGES-REQUIRED now goes to the user: separate real BLOCKER/MAJOR (wrong
user-visible behaviour, security, an install that leaves the PC worse) from MINOR/NIT that can be deferred.

Read-only: never edit repo files, never run the real installer / sudo / ufw / firewall-cmd / loginctl writes / systemctl --user
writes / hermes gateway commands, never touch `~/.hermes` state, `~/.local/bin`, running services; absolute stub paths in
probes; never git checkout/restore/stash/reset/commit/push.
Output: `docs/release/runs/IN2/re-review-report.md` (verdict, numbered findings with severity, ticket, file:line, evidence, fix).
