# TS2b — app lock review minors

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TS2/review-report.md` (the MINOR findings,
with file:line) and `runs/TS2/build-report.md`, then `MainActivity.kt`, `data/AppLock.kt`, `data/FreshAuth.kt`, `ui/lock/*`.

## Do (each with a test where logic is pure)
1. Recents thumbnail on API 29-32: while the lock is on, set FLAG_SECURE in `onPause` (clear it on resume unless another screen needs it —
   keep the existing FLAG_SECURE users (prompt sheet, lock screen) working: use a small reference-counted owner set, not a boolean).
2. Bound the auth-in-progress exemption: start a check only when the activity is resumed; the time away is measured from the stop and
   counts unless the check PASSED (an abandoned/never-returning check no longer suppresses locking).
3. Multi-window: attach the auth host in `onResume`, detach only the activity that is attached (a closed second window must not break
   Unlock).

## Hard rules
Files: `MainActivity.kt`, `data/AppLock.kt`, `data/FreshAuth.kt`, `ui/lock/**`, the Flag-secure owners you touch, their tests,
`docs/v1-tickets/runs/TS2b/build-report.md`. Do NOT touch `ui/chat/ToolFolding.kt`, `ChatText.kt`, `ChatScreen.kt`, `ChatItems.kt`,
`data/chat/**` (another worker) or `server/**`. No commits; never git checkout/restore/stash/reset. No adb.

## Verify
`GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TS2b` exits 0. Report ≤15 lines.
