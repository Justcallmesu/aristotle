# IN3b fix brief — root `install.sh`: tests that guard, tidy code

Repo: `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, the original brief
`docs/release/runs/IN3/build-brief.md` (still binding) and the first report `docs/release/runs/IN3/build-report.md`.
Files: `install.sh` (repo root) and `scripts/test/root_install_test.sh` ONLY.

## Evidence from the PM checks (why this round exists)
1. **PM mutant SURVIVED:** replacing `sort -V` with a plain lexicographic `sort` at `install.sh:89` leaves
   `bash scripts/test/run.sh root_install` green. The "2.10.0 beats 2.9.0" case does not guard the order. Make the test fail
   on that mutant: the fake `dist/` must hold 2.9.0, 2.10.0 and 2.1.1 (and the junk dirs), and the test must assert the
   bundle that ran is exactly `aristotle-2.10.0` (the stub records which dir it is), not just that something ran.
2. **The report claims a root-refusal test; there is none** in `scripts/test/root_install_test.sh`. `EUID` is read-only in
   bash, so add a test seam (e.g. the uid comes from `id -u` through a small function, and the test puts an `id` stub first
   on PATH that prints 0) and a test: root -> exit 1, message, bundle installer never ran.
3. Check that the brief's case "`--bundle rel/path` from another cwd resolves correctly" really exists and asserts the
   bundle ran; add it if not.
4. Code shape (user's bar: clean, no noise): remove trailing whitespace, the trailing space in `usage() { `, and the comment
   "permit bundle_count to stay zero if it was"; simplify the `--bundle` counting (e.g. `[[ -z $BUNDLE_SET ]] || usage`);
   `newest_bundle` can be one pipeline over the valid dir names (keep it readable, one-line comments that say why).
   `--help` prints the usage line on stdout and exits 0 (keep); usage errors on stderr exit 2 (keep).
5. Prove it: run the PM's mutant yourself after the fix — copy `install.sh` to your scratch dir, `sed -i '<line>s#sort -V#sort#'`
   on the repo file, run `bash scripts/test/run.sh root_install` (must FAIL), restore from the scratch copy, verify sha256
   equals the copy, run again (must PASS). Same for the root seam (force the uid check false -> the root test must fail).
   Report both runs with real exit codes.

## Hard rules
Never `git checkout/restore/stash/reset/commit/push`. Never run the real root installer or any real bundle, never touch `dist/`,
`~/.local/bin`, systemd, `~/.hermes`. Other files (server/, scripts/install.sh, its tests and stubs) are under review right
now: do not touch them; run only `bash scripts/test/run.sh root_install`.
Report: `docs/release/runs/IN3/fix-report.md`.
