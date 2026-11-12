# IN3 build brief — root `install.sh` (picks a bundle, runs its installer)

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md` first. Spec: `docs/release/spec.md` section
"Complete installer (2026-11-12)": decisions **Q135, Q138, P21** apply.

## Goal
A new executable `install.sh` at the repo root, run on the PC itself from a checkout:
- `./install.sh [--bundle <dir>] [args...]`. Every argument except `--bundle <dir>` is passed **unchanged** to the bundle's
  `install.sh` (do not validate them: the bundle installer owns its flags; today `--pair`/`--no-pair`, soon `--no-gateway`/
  `--no-firewall`). `--bundle` may appear anywhere; given twice or without a value -> usage, exit 2. `-h/--help` -> usage, exit 0.
- Without `--bundle`: the newest `dist/aristotle-<x.y.z>/` next to the script (resolve the repo dir from `BASH_SOURCE`, so it
  works from any cwd), by **numeric version order** (2.10.0 > 2.9.0; `sort -V` or explicit), ignoring `dist/gate-*` and any
  name that is not exactly `aristotle-<digits>.<digits>.<digits>`, and only dirs that contain an executable `install.sh`.
- None found -> error naming both ways out: build one with `scripts/release.sh <x.y.z>` (needs the signing key, dev PC only) or
  copy a bundle here and pass `--bundle <dir>`; exit 1.
- `--bundle <dir>`: must be a dir with `install.sh` (else error, exit 1); relative paths resolved against the caller's cwd.
- Print one line `Installing <bundle dir>` then `exec` the bundle's `install.sh` (its exit code is the result; its checksum check
  stays the only integrity check — do not duplicate it).
- Refuse to run as root (`EUID 0`): the server is a per-user systemd service; message says run as your user.

## Non-goals
No ssh/remote mode (Q135). No building from source (Q138). No copying files. Do not edit `scripts/install.sh` (another worker owns it).

## Code shape
`set -euo pipefail`, small named functions (`parse_args`, `newest_bundle`, `require_bundle`, ...), header comment with usage,
same style as `scripts/install.sh` (read it). shellcheck-clean if `shellcheck` exists (do not install it).

## Files you may create/edit
`install.sh` (repo root, mode 755), `scripts/test/root_install_test.sh` (new; picked up by `scripts/test/run.sh` automatically),
a stub bundle installer inside the test's temp dir only. Other workers edit `server/`, `scripts/install.sh`,
`scripts/test/install_test.sh`, `scripts/test/stubs/`: **not yours, do not touch.** Report: `docs/release/runs/IN3/build-report.md`.

## Acceptance tests (`scripts/test/root_install_test.sh`, follow `scripts/test/install_test.sh` + `scripts/test/lib.sh` style)
Copy the root `install.sh` into a temp fake repo with a fake `dist/` (stub bundle `install.sh` that records its args and cwd and
exits with a chosen code). Never run against the real `dist/`.
- picks 2.10.0 over 2.9.0 and 2.1.1; ignores `gate-2.10.0`, `aristotle-2.11.0-rc1`, a dir without `install.sh`.
- args pass through unchanged and in order (`--no-pair --no-gateway`), with `--bundle` removed, `--bundle` before or after them.
- `--bundle rel/path` from another cwd resolves correctly; missing dir / no install.sh -> exit 1 with message.
- no bundle -> exit 1, message names `scripts/release.sh` and `--bundle`.
- `--bundle` twice / without value -> exit 2. `--help` -> exit 0.
- bundle installer's exit code (e.g. 3) is returned.
- running as root refused (simulate with an `EUID` seam or a guard function you can override in tests; say how).
Show the tests RED first (before the script exists) and report it.

## Verify
`bash scripts/test/run.sh root_install` (exit 0); `shellcheck install.sh` if available. Real exit codes.

## Hard rules
Never `git checkout/restore/stash/reset/commit/push`. Never run the real root installer or any bundle's real `install.sh`, never
touch `~/.local/bin`, systemd, `~/.hermes` or `dist/`.

## Stop and ask if
A requirement above conflicts with how `scripts/release.sh` names or lays out bundles (read it: `scripts/release.sh`).
