# T06c build brief — relay liveness: a dead phone must close even while Hermes streams

route: deep (fix round 2 of the relay; cap reached after this — a further failure goes to the user)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `docs/v1-tickets/runs/T06b/review-report.md` (MAJOR finding in full),
`docs/protocol.md` §12, then `server/internal/relay/liveness.go`, `session.go` and their tests (uncommitted T06b working tree —
build on it, do not revert it).

## Fix
1. Write progress counts only when a write actually had to wait for the phone to drain (i.e. it was blocked and then made
   progress), NOT when a small write is accepted immediately by the kernel buffer. Keep sending a ping every 25 s regardless of
   outgoing traffic; close 1011 if no pong AND no real drain progress within 15 s of the ping. The T06b slow-phone guarantee must
   still hold (a phone steadily draining a big message is never cut).
2. Nit: while a phone frame is waiting for Hermes to accept it, pings to the phone still go out.
3. Remove `event` from the known-method allowlist (only Hermes sends it).
4. Update §12 row for 1011 to describe the real rule.

## Tests (`-race`, reduced timings)
- Non-reading phone + Hermes sending a ~90-byte event every 40 ms -> closed 1011 within ping+pong (+ small margin).
- Same with small socket buffers and 1 ms events -> closed within the same bound.
- Slow but steady phone downloading frames larger than rate × pong timeout -> all frames arrive (T06b test still passes).
- Silent Hermes, non-reading phone -> closed 1011 (existing).
- Relay package `-race -count=5` green.

## Hard rules
Files: `server/internal/relay/**`, `docs/protocol.md` §12, `docs/v1-tickets/runs/T06c/build-report.md`. Never connect to the real
Hermes. No commits. If the coder/websocket API gives no way to tell a blocked write from an immediate one, STOP and report the options.

## Report
`docs/v1-tickets/runs/T06c/build-report.md`, ≤25 lines: the rule as implemented; tests by name; commands + exit codes.
