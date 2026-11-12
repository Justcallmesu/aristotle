# IN3b fix report — root `install.sh`

Files changed: `install.sh`, `scripts/test/root_install_test.sh` (nothing else).

## Changes
- **sort order guarded:** the `newest` case holds 2.9.0, 2.10.0, 2.1.1. The stub logs its own dir name and the test asserts the log is exactly `BUNDLE aristotle-2.10.0 args: `. The junk case now has gate-2.10.0, aristotle-2.11.0-rc1, aristotle-nightly, aristotle-2.11 and an `aristotle-3.0.0` without install.sh. A non-executable install.sh case was added.
- **root refusal test added:** `install.sh` reads the uid through `current_uid() { id -u; }` (EUID is read-only). The test puts an `id` stub on PATH (`id_root` prints 0, the default `id_user` prints 1000, so the suite also passes when run as root). Asserts exit 1, message "not as root", and an empty calls log.
- **`--bundle rel/path` from another cwd:** it was missing. Added (cwd = case dir, `--bundle repo/dist/aristotle-1.0.0`, asserts that bundle ran with the right args). Also added auto-detect from another cwd.
- Every case now asserts which bundle ran and the exact args; usage-error and `--help` cases assert the bundle did not run.
- **Code shape:** no trailing whitespace (grep clean), `usage() {` fixed, the noise comment removed, `--bundle` counting replaced by `BUNDLE_SET`, `newest_bundle` is one for-loop piped into `sort -V | tail -n 1`. `--help` prints usage to stdout exit 0; usage errors go to stderr exit 2.

## Verification (real exit codes)
- `bash scripts/test/run.sh root_install` -> exit 0 (PASS).
- `shellcheck` is not installed, so it was not run.
- Mutant 1, `sed -i '70s#sort -V#sort#' install.sh` -> exit **1**: `FAIL: exactly aristotle-2.10.0 ran: got 'BUNDLE aristotle-2.9.0 args: '`. Restored from the scratch copy, sha256 matched (`6e9a983b0546333402d8d7300884a7b55dda3145141f74a9e2f01da047a637ed`); rerun -> exit **0**.
- Mutant 2, root check `== 0` -> `== 99999` (line 23) -> exit **1**, 3 failures (root refused: exit 0 want 1; message missing; bundle ran). Restored, sha256 matched; rerun -> exit **0**.
- Final `install.sh` is mode 755, identical to the verified copy.
