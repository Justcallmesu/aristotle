# TQ3 — review-Q80 minors

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/review-Q80.md` (MINOR-1,2,3,5 + NITs with
file:line) and the TL2/TM2/TR7/TP1a reports.

## Do
1. MINOR-1: crash files keep the exception message only for the app's own exception classes (package `io.github.justcallmesu.aristotle`)
   and a small allow-list (e.g. IllegalStateException with our own messages? — no: only our classes); others: class name only. Test.
2. MINOR-2: the crash report is claimed when the dialog first shows, not at process start (a background service restart must not consume
   it). Test.
3. MINOR-3: long-press on a reply works while it streams (the gesture must not restart on each token — key the pointerInput on the
   message key, not its text). Test if pure, else explain.
4. MINOR-5: the phone keeps the last known CPU % for a PC when a reading comes back with `cpu_percent: null` but other fields present,
   so the meter doesn't vanish and the layout doesn't jump (show it dimmed as stale until a fresh value). Test.
5. Server: `disk.path` is sent as `"~"` (or the path relative to home) — update protocol §16. Test.
6. NITs: CPU uses its own string (not the battery one); address in the mono font per §5.7; the red Hermes line not shown on offline
   (stale) values; Revoked clears the log like Unpair.

## Hard rules
Files: `android/**` (not `ui/theme/**`), `server/internal/pcstatus/**`, `server/internal/httpapi/**` (only if needed), `docs/protocol.md`
§16, `docs/v1-tickets/runs/TQ3/build-report.md`. strings append-only. No commits; never git checkout/restore/stash/reset. No adb; never
touch port 8760.
Verify: `bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TQ3` (both sides) exits 0. Report ≤10 lines.
