# DS3c Build Report

## Summary
Fixed tray menu lifecycle buttons for other service words.

## Changes Made

### desktop/tray/src/menu.rs
**Lines 63–74** (`lifecycle()` function):
- Changed logic: `Service::Other` now returns `None` (no Start, no Stop…) instead of `Some(Entry::Stop)`
- Updated doc comment to clarify that `activating`, `deactivating`, `reloading`, `unknown`, etc. show neither button, with the recovery exception for `server == "no-answer"` and `service == "active"` only

**Line 155** (test):
- Renamed `other_service_words_show_stop_for_recovery` → `other_service_words_show_neither`
- Updated assertion: expects empty `[]` instead of `[Entry::Stop]`
- Kept `no_answer_with_active_service_keeps_stop_for_recovery` test passing

### docs/desktop/gates.sh
No changes needed. Test count remains **94** (unchanged).

## Verification

**Gate Summary:**
```
PASS desktop-fmt (0)
PASS desktop-clippy (0)
PASS desktop-test (0)
rust-tests passed=94 failed=0
PASS desktop-test-count (94)
PASS desktop-tray-build (0)
PASS desktop-tray-ldd (0)
```

All tests pass. Confirmed:
- Renamed test `other_service_words_show_neither` now passes with correct expectation (empty lifecycle)
- Recovery exception test `no_answer_with_active_service_keeps_stop_for_recovery` still passes (Stop… remains when server doesn't answer but service is active)
- Design spec §4.1 alignment: lifecycle buttons now match the window (no buttons for other service words, except the recovery case)
