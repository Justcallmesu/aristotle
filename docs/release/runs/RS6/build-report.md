# RS6 build report — bundle file modes

- **Issue:** `THIRD_PARTY_NOTICES.txt` (copied from `server/` source, mode 600) was bundled with mode 600 instead of 644 (other data files use 644, executables use 755).
- **Fix:** Added explicit `chmod 0644` after copying all non-executable bundle files (THIRD_PARTY_NOTICES.txt, RELEASE-NOTES.md, SHA256SUMS, APK) and `chmod 0755` for executables (aristotle-server, install.sh) in `scripts/release.sh` (lines 129, 131, 133–135).
- **Tests:** Extended `release_test.sh` end-to-end bundle check to assert file modes on all six bundle files: executables 755, data files 644 (lines 144–150).
- **Result:** `bash scripts/test/run.sh` exits 0 (install_test and release_test both pass; all new mode assertions pass).
