# IN2c fix report: gateway wait budget + no silent downgrade (P26)

## Result
`bash scripts/test/run.sh` -> **exit 0** (install_test, release_test, root_install_test all PASS, 0 FAIL lines).

## Changes
1. **#1 MAJOR** `scripts/install.sh`: new `ARISTOTLE_GATEWAY_TIMEOUT` (default 120), validated like `ARISTOTLE_INSTALL_TIMEOUT`
   (non-number -> message + exit 2). `wait_for_hermes` uses it (wall-clock deadline); the failure text says `after <n>s` with it.
   One line is printed before the wait (both the `not-running` and `error` paths):
   `Waiting for Hermes to become ready (up to <n>s; a fresh gateway takes about half a minute)...`.
   The server wait still uses `ARISTOTLE_INSTALL_TIMEOUT`. Header comment updated.
2. **#2 MINOR** stub `aristotle-server`: new `STUB_HERMES_REASON_LATE`, which replaces the plain-status `Hermes:` reason only after
   the hermes stub ran `gateway install`. The `gatewaydown` case sets it and asserts that text, so a failure path that stops
   printing the server's reason fails the test.
3. **#3 MINOR** root `install.sh`: `newest_bundle` picks the newest `aristotle-x.y.z` by version regardless of `install.sh` mode.
   If that one's `install.sh` is missing or not executable: exit 1 with
   `error: <dir>/install.sh is missing or not executable (an incomplete copy?); fix it (chmod +x) or pass --bundle <dir>`.
   Never falls back to an older bundle. An explicit `--bundle` keeps its old message.
4. **#4 NITs** root `install.sh`: `--bundle=<dir>` accepted (duplicate with `--bundle` -> exit 2; empty -> exit 1 "does not exist");
   `--help` lists `--pair, --no-pair, --no-gateway, --no-firewall` as going to the bundle installer; no-bundle message says
   `scripts/release.sh needs the signing key (dev PC only): copy a release bundle folder to this PC and pass --bundle <dir>`.

## Tests
- `install_test.sh`: `run_install` / pty wrapper pass `ARISTOTLE_GATEWAY_TIMEOUT` (`GATEWAY_TIMEOUT`, default 5). New case `gatewayslow`
  (INSTALL_TIMEOUT=1, Hermes reachable on the 4th poll after about 3 s, GATEWAY_TIMEOUT=10 -> exit 0 and the wait line). New case
  `gatewaybad` (`abc`, `-5`, `1.5`, `5 s` -> exit 2, nothing installed). `gatewaydown` and `gatewayerror` now set `GATEWAY_TIMEOUT=2`
  (they used `INSTALL_TIMEOUT=2` before).
- `root_install_test.sh`: the old "non-executable newest is ignored" case was replaced by the new behaviour (2.10.0 mode 644 + 2.9.0 fine ->
  exit 1, calls log empty, no "Installing"; newest dir without install.sh -> exit 1). `--bundle=` cases (first, middle, with `--bundle`,
  missing dir, empty). Help and no-bundle message assertions. Removed the empty `aristotle-3.0.0` dir from the "ignore junk" case (that
  dir is now a hard error by design).
- Default value 120 is not asserted by a test (tests would wait 120 s); it is only in the code.

## RED before (scratch pre-fix copies in `~/.hermes/profiles/pm/cache/scratch/in2c/`, no git)
- Pre-fix `scripts/install.sh` via `INSTALL_SH=`: `install_test.sh` exit 1, 13 FAIL lines: gatewayslow (exit 1, want 0; no wait line),
  gatewaydown/gatewayerror (no wait line, `after 2s` missing), gatewaybad (exit 0, want 2, for all 4 values, and the binary got installed).
- Pre-fix mutant m6 (`true | grep '^Hermes:'` in `gateway_failed`) on the new tests: `FAIL: printed the server's Hermes line read AFTER the gateway step`.
- Pre-fix root `install.sh` with the new `root_install_test.sh`: exit 1, 17 FAIL lines (non-executable newest -> exit 0 and 2.9.0 ran;
  `--bundle=` passed through and 2.10.0 ran; help and no-bundle texts missing).

## Files modified
`scripts/install.sh`, `install.sh`, `scripts/test/install_test.sh`, `scripts/test/root_install_test.sh`,
`scripts/test/stubs/aristotle-server`. Created: this report. Nothing else touched (no git commands, no real installer, no sudo/ufw/hermes).
Re-review #5/#6 NITs left alone as instructed.
