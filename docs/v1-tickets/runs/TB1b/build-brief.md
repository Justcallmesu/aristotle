# TB1b — TB1 review fix round

route: std. Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/TB1/review-report.md` (MAJOR-1, MINOR-2,
MINOR-3, nits) and `runs/TB1/build-report.md`. TB1 is uncommitted in the tree; continue it.

## Do
1. **MAJOR-1:** the composer always keeps its space. While the IME is visible the status stack collapses to its one-line headers (or
   hides; pick one and say why); otherwise it is laid out with `weight(1f, fill = false)` + the 40% cap measured against the space left
   after the composer, never the window. Pure test for the rule (IME up -> collapsed; space left small -> capped).
2. **MINOR-2:** a kill whose frame was sent but whose reply never came (link dropped) says "Outcome unknown — check the list when the PC
   is back" (EN + ID), not "Not sent". "Not sent" only when the frame never left. Test.
3. **MINOR-3 (PM decision: Desktop parity):** poll `process.list` every 5 s only while at least one row is running; otherwise read once
   on opening the chat, on reconnect, and on the refresh events already wired, and stop the timer. Test both.
4. Nits if one-liners: close the stop dialog when its process disappears from the list; the waiting chip keeps "answer" wording while
   an answer is in flight.

## Hard rules
Files: `android/**` (not `ui/theme/**`), `docs/v1-tickets/runs/TB1b/build-report.md`. strings append-only. No commits; never git
checkout/restore/stash/reset. No adb.
Verify: `GATE_ONLY=android bash docs/v1-tickets/gates.sh docs/v1-tickets/runs/TB1b` exits 0. Report ≤12 lines.
