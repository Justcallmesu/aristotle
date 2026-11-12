# IN2c fix brief — gateway wait budget + no silent downgrade (P26)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, spec `docs/release/spec.md` row **P26** (binding), and the
re-review `docs/release/runs/IN2/re-review-report.md` (findings 1-4 with evidence).

## Fix
1. **Re-review #1 MAJOR** (`scripts/install.sh` gateway wait, around `:242-249,298`): the Hermes wait gets its own budget
   `ARISTOTLE_GATEWAY_TIMEOUT` (default 120, validated as a number like `ARISTOTLE_INSTALL_TIMEOUT`, wall-clock deadline).
   Print one line before waiting: `Waiting for Hermes to become ready (up to <n>s; a fresh gateway takes about half a minute)...`.
   The server wait keeps `ARISTOTLE_INSTALL_TIMEOUT`. Update the header comment. Test: with `ARISTOTLE_INSTALL_TIMEOUT=1` and
   the hermes stub becoming reachable after polls that exceed 1 s but not the gateway budget, the install succeeds; and a bad
   `ARISTOTLE_GATEWAY_TIMEOUT` value exits 2.
2. **Re-review #2 MINOR** (test gap): make a test that fails if the failure path stops printing the server's Hermes reason
   (`:266`): e.g. the stub returns a distinct reason only on the polls AFTER the gateway install, and the test asserts that text.
3. **Re-review #3 MINOR** (repo-root `install.sh:66-70`): pick the newest valid `aristotle-x.y.z` dir by version first; if its
   `install.sh` is missing or not executable, exit 1 with
   `error: <dir>/install.sh is missing or not executable (an incomplete copy?); fix it (chmod +x) or pass --bundle <dir>` —
   never fall back to an older bundle. Test: 2.10.0 with a non-executable install.sh and 2.9.0 fine -> exit 1, nothing ran.
4. **Re-review #4 NITs (IN3)**: accept `--bundle=<dir>` as well; `--help` says the other flags go to the bundle installer
   (`--pair`, `--no-pair`, `--no-gateway`, `--no-firewall`); the no-bundle message says `scripts/release.sh` needs the signing
   key (dev PC) and "copy a release bundle folder to this PC and pass --bundle <dir>". Tests for `--bundle=`.
Leave re-review #5/#6 NITs alone (deferred by the PM).

## Files you may edit
`scripts/install.sh`, `scripts/test/install_test.sh`, `scripts/test/stubs/*`, `install.sh` (repo root),
`scripts/test/root_install_test.sh`. Nothing else (server/ is committed; do not touch).

## Verify (real exit codes)
`bash scripts/test/run.sh` (all suites). Show each new test RED against a scratch copy of the pre-fix file (never git).

## Hard rules
Never git checkout/restore/stash/reset/commit/push. Never run the real installer, real sudo/ufw/firewall-cmd/loginctl writes,
systemctl --user writes or real hermes gateway commands; never touch `~/.hermes` state, `~/.local/bin`, `dist/`, running services.
Report: `docs/release/runs/IN2/fix2-report.md`.
