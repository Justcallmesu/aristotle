# T06b build brief — relay review fixes

route: deep (concurrency on the live path)

Repo `/home/justcallmesu/project/personal/Aristotle`. Read `PROJECT.md`, `docs/protocol.md` §12, `docs/v1-tickets/runs/T06/review-report.md`
(all findings), then `server/internal/relay/`.

## Fix (each with a test)
1. **MAJOR — slow phone cut by keepalive** (`relay/session.go:156-172`): a healthy but slow phone downloading a large Hermes
   frame must not be closed 1011. Judge liveness by progress: while bytes are still being written to the phone, the pong
   deadline does not expire (e.g. reset the deadline on each successful write chunk / track last write progress), and only a
   phone that has made no read or write progress for the timeout is closed. If the library supports writing a message in
   chunks (`Writer` with fragments), write large frames in 32–64 KiB pieces so pings are not stuck behind one huge write;
   if pings still cannot interleave, the progress rule alone must prevent the false close. Test: the reviewer's scenario at
   reduced scale (phone reading at a fixed slow rate, frames larger than rate × pong timeout) delivers every frame; a phone
   that stops reading entirely is still closed.
2. **MINOR — revoke on a stalled phone** delivers no 4401: make the revoke path close the phone side within 1 s even when
   the write side is blocked (close the underlying conn after a short grace, and document in §12 that a non-draining phone
   gets a TCP close instead of 4401). Test.
3. **MINOR — secrets in method-name log** (`relay/limits.go:161-184`): count only known Hermes method names (list them from
   `docs/protocol.md` §2–§9 + catalog), everything else as `other`. Test a token-like method is logged as `other`.
4. **NITs (docs §12)**: Hermes->phone read limit is 64 MiB and a larger frame ends the relay with 4502; a mismatched Origin
   on the upgrade gets 403 (part of the cross-site defence).
5. Do NOT change `desktop_local` handling — that is a pending user decision.

## Hard rules
Files: `server/**`, `docs/protocol.md` §12, `docs/v1-tickets/runs/T06b/build-report.md`. No new modules. Never connect to the
real Hermes. No commits.

## Verify
`cd server && gofmt -l . && go vet ./... && go test -race -count=1 ./...`; run the relay package `-count=5`.

## Report
`docs/v1-tickets/runs/T06b/build-report.md`, ≤30 lines: each finding -> FIXED (test) / WONTFIX (why); commands + exit codes.
