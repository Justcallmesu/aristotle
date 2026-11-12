# IN3-T1 tidy report
- install.sh `require_bundle`: two `-x $BUNDLE_DIR/install.sh` checks folded into one, placed after BUNDLE_DIR is chosen.
- One message: `error: <dir>/install.sh is missing or not executable (an incomplete copy?); fix it (chmod +x) or pass --bundle <dir>`.
- scripts/test/root_install_test.sh line 172: assertion updated from "install.sh does not exist" to "install.sh is missing or not executable".
- Behaviour otherwise unchanged (exit 1, stderr, dir-exists and no-bundle checks kept).
- Verify: `bash scripts/test/run.sh root_install` exit code 0.
