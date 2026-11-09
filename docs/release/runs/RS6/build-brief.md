# RS6 — bundle NIT: THIRD_PARTY_NOTICES.txt mode

route: light. Repo `/home/justcallmesu/project/personal/Aristotle`, HEAD `9eef533`. Read `scripts/release.sh` and `scripts/test/release_test.sh`.
`dist/aristotle-2.1.0/THIRD_PARTY_NOTICES.txt` is mode 600 (the others 644/755). Find why (a temp file from `mktemp`, or the source's mode),
make every non-executable bundle file 644 and the executables 755, and extend `release_test.sh`'s end-to-end bundle check to assert the
modes. Files: `scripts/release.sh`, `scripts/test/**`, `docs/release/runs/RS6/build-report.md`. Never run release.sh on the real repo; no
commits; never git checkout/restore/stash/reset; never read `~/.config/aristotle`. Avoid `bash -c`/heredocs/chained `$(...)`.
Gate after your LAST edit: `bash scripts/test/run.sh` exits 0 (check the exit code itself, not through a pipe). Report ≤ 5 lines.
